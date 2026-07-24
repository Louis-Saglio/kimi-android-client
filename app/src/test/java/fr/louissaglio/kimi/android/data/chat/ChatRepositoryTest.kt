package fr.louissaglio.kimi.android.data.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.ui.chat.ChatItem
import fr.louissaglio.kimi.android.ui.chat.ChatTimeline
import fr.louissaglio.kimi.android.ui.chat.ToolStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatRepositoryTest {

    private class FakePreferencesDataStore(prefs: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(prefs)
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private val server = MockWebServer()
    private val repository = ChatRepository(
        OkHttpClient(),
        Json { ignoreUnknownKeys = true },
        FakePreferencesDataStore(emptyPreferences()),
    )

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun settings() = ServerSettings(serverUrl = server.url("/").toString())

    /** A `/api/v1/config` response without a default model (pre-0.28 server shape):
     * prompts then go out without a `model` field. */
    private fun enqueueConfigWithoutDefaultModel() = server.enqueue(
        MockResponse().setBody(
            """{"code":0,"msg":"success","data":{"default_permission_mode":"yolo"},"request_id":"01KX"}""",
        ),
    )

    @Test
    fun `history envelope maps to domain messages oldest-first`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"id":"msg_3","session_id":"session_1","role":"tool",
                   "content":[{"type":"tool_result","tool_call_id":"tool_1","output":"hi\n","is_error":true}],
                   "created_at":"2026-07-17T04:12:11.000Z"},
                  {"id":"msg_2","session_id":"session_1","role":"assistant",
                   "content":[{"type":"thinking","thinking":"hmm"},
                              {"type":"text","text":"let me run that"},
                              {"type":"tool_use","tool_call_id":"tool_1","tool_name":"Bash",
                               "input":{"command":"echo doc-verify"}}],
                   "created_at":"2026-07-17T04:12:10.500Z"},
                  {"id":"msg_1","session_id":"session_1","role":"user",
                   "content":[{"type":"text","text":"run echo hi"}],
                   "created_at":"2026-07-17T04:12:10.000Z"}
                ],"has_more":false},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val result = repository.getMessages(settings(), "session_1")

        val messages = result.getOrThrow()
        assertEquals("/api/v1/sessions/session_1/messages?page_size=100", server.takeRequest().path)
        // Wire order is newest-first; domain order is oldest-first.
        assertEquals(3, messages.size)
        assertEquals(
            ChatMessage("msg_1", ChatMessage.Role.User, listOf(ChatMessage.Part.Text("run echo hi"))),
            messages[0],
        )
        assertEquals(
            ChatMessage(
                "msg_2",
                ChatMessage.Role.Assistant,
                listOf(
                    ChatMessage.Part.Thinking("hmm"),
                    ChatMessage.Part.Text("let me run that"),
                    ChatMessage.Part.ToolUse(
                        "tool_1",
                        "Bash",
                        "echo doc-verify",
                        input = "{\n    \"command\": \"echo doc-verify\"\n}",
                    ),
                ),
            ),
            messages[1],
        )
        assertEquals(
            ChatMessage("msg_3", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", true, "hi\n"))),
            messages[2],
        )
    }

    @Test
    fun `history tool result output and error flag reach the activity row`() = runBlocking {
        // Fixture per docs/server-api.md (ticket 023): the expanded row reads the full
        // call input and the result output, plus the error tint from is_error.
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"id":"msg_2","role":"tool",
                   "content":[{"type":"tool_result","tool_call_id":"tool_1","output":"FAIL 3 tests\n","is_error":true}]},
                  {"id":"msg_1","role":"assistant",
                   "content":[{"type":"tool_use","tool_call_id":"tool_1","tool_name":"Bash",
                               "input":{"command":"./gradlew test"}}]}
                ],"has_more":false},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val timeline = ChatTimeline()
        timeline.replaceHistory(repository.getMessages(settings(), "session_1").getOrThrow(), keepLive = false)

        assertEquals(
            listOf(
                ChatItem.ToolActivity(
                    "tool_1",
                    "Bash",
                    ToolStatus.Error,
                    "./gradlew test",
                    input = "{\n    \"command\": \"./gradlew test\"\n}",
                    output = "FAIL 3 tests\n",
                ),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `history tool use input surfaces as the activity row preview`() = runBlocking {
        // docs/server-api.md: {"type":"tool_use","tool_call_id":"...","tool_name":"Bash",
        //  "input":{"command":"echo doc-verify"}} (ticket 022).
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"id":"msg_2","role":"assistant",
                   "content":[{"type":"tool_use","tool_call_id":"tool_er2XKV7KXHB4A3U2AXGxZfrY","tool_name":"Bash",
                               "input":{"command":"echo doc-verify"}}]},
                  {"id":"msg_1","role":"user","content":[{"type":"text","text":"run echo doc-verify"}]}
                ],"has_more":false},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val timeline = ChatTimeline()
        timeline.replaceHistory(repository.getMessages(settings(), "session_1").getOrThrow(), keepLive = false)

        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "run echo doc-verify"),
                ChatItem.ToolActivity(
                    "tool_er2XKV7KXHB4A3U2AXGxZfrY",
                    "Bash",
                    ToolStatus.Running,
                    "echo doc-verify",
                    input = "{\n    \"command\": \"echo doc-verify\"\n}",
                ),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `non-zero envelope code maps to Api error`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"code":40401,"msg":"session session_1 does not exist","data":null,"request_id":"01KX"}"""),
        )

        val result = repository.getMessages(settings(), "session_1")

        assertTrue(result.exceptionOrNull() is DataError.Api)
    }

    @Test
    fun `invalid base URL maps to InvalidUrl error`() = runBlocking {
        val result = repository.getMessages(ServerSettings(serverUrl = "not a url"), "session_1")

        assertTrue(result.exceptionOrNull() is DataError.InvalidUrl)
    }

    @Test
    fun `sendPrompt posts text content parts to the prompts endpoint`() = runBlocking {
        enqueueConfigWithoutDefaultModel()
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"msg":"success","data":{"prompt_id":"prompt_1",
                  "user_message_id":"msg_session_1_pending_prompt_1","status":"running",
                  "content":[{"type":"text","text":"Reply with exactly: ok"}],
                  "created_at":"2026-07-17T04:10:56.484Z"},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val result = repository.sendPrompt(settings(), "session_1", "Reply with exactly: ok")

        assertTrue(result.isSuccess)
        assertEquals("/api/v1/config", server.takeRequest().path)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/prompts", request.path)
        assertEquals(
            """{"content":[{"type":"text","text":"Reply with exactly: ok"}]}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `sendPrompt maps non-zero envelope code to Api error`() = runBlocking {
        enqueueConfigWithoutDefaultModel()
        server.enqueue(
            MockResponse().setBody("""{"code":40401,"msg":"session session_1 does not exist","data":null,"request_id":"01KX"}"""),
        )

        val result = repository.sendPrompt(settings(), "session_1", "hello")

        assertTrue(result.exceptionOrNull() is DataError.Api)
    }

    @Test
    fun `steer posts the prompt then steers the returned prompt id into the turn`() = runBlocking {
        // docs/server-api.md, Steer: while busy the prompts POST queues the prompt, then
        // prompts:steer injects the queued id into the running turn.
        enqueueConfigWithoutDefaultModel()
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"msg":"success","data":{"prompt_id":"msg_1",
                  "user_message_id":"msg_1","status":"queued",
                  "content":[{"type":"text","text":"Stop counting"}],
                  "created_at":"2026-07-20T00:00:00.000Z"},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"success","data":{"steered":true,"prompt_ids":["msg_1"]},"request_id":"01KX"}""",
            ),
        )

        val result = repository.steer(settings(), "session_1", "Stop counting")

        assertTrue(result.isSuccess)
        assertEquals("/api/v1/config", server.takeRequest().path)
        val prompt = server.takeRequest()
        assertEquals("POST", prompt.method)
        assertEquals("/api/v1/sessions/session_1/prompts", prompt.path)
        assertEquals("""{"content":[{"type":"text","text":"Stop counting"}]}""", prompt.body.readUtf8())
        val steer = server.takeRequest()
        assertEquals("POST", steer.method)
        assertEquals("/api/v1/sessions/session_1/prompts:steer", steer.path)
        assertEquals("""{"prompt_ids":["msg_1"]}""", steer.body.readUtf8())
    }

    @Test
    fun `steer maps a non-zero steer envelope code to Api error`() = runBlocking {
        enqueueConfigWithoutDefaultModel()
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"success","data":{"prompt_id":"msg_1","user_message_id":"msg_1",""" +
                    """"status":"queued","created_at":"2026-07-20T00:00:00.000Z"},"request_id":"01KX"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody("""{"code":40402,"msg":"no active prompt to steer into","data":null,"request_id":"01KX"}"""),
        )

        val result = repository.steer(settings(), "session_1", "too late")

        assertTrue(result.exceptionOrNull() is DataError.Api)
    }

    @Test
    fun `steer does not call the steer endpoint when the prompt post fails`() = runBlocking {
        enqueueConfigWithoutDefaultModel()
        server.enqueue(
            MockResponse().setBody("""{"code":40401,"msg":"session session_1 does not exist","data":null,"request_id":"01KX"}"""),
        )

        val result = repository.steer(settings(), "session_1", "hello")

        assertTrue(result.exceptionOrNull() is DataError.Api)
        assertEquals("/api/v1/config", server.takeRequest().path)
        assertEquals("/api/v1/sessions/session_1/prompts", server.takeRequest().path)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `sendPrompt sends the server default model in the prompt body`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"success","data":{"default_model":"kimi-code/k3"},"request_id":"01KX"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"success","data":{"prompt_id":"msg_1","user_message_id":"msg_1",""" +
                    """"status":"running","created_at":"2026-07-21T00:00:00.000Z"},"request_id":"01KX"}""",
            ),
        )

        val result = repository.sendPrompt(settings(), "session_1", "hello")

        assertTrue(result.isSuccess)
        assertEquals("/api/v1/config", server.takeRequest().path)
        val prompt = server.takeRequest()
        assertEquals(
            """{"content":[{"type":"text","text":"hello"}],"model":"kimi-code/k3"}""",
            prompt.body.readUtf8(),
        )
    }

    @Test
    fun `steer sends the server default model in the prompt body`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"success","data":{"default_model":"kimi-code/k3"},"request_id":"01KX"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"success","data":{"prompt_id":"msg_1","user_message_id":"msg_1",""" +
                    """"status":"queued","created_at":"2026-07-21T00:00:00.000Z"},"request_id":"01KX"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"success","data":{"steered":true,"prompt_ids":["msg_1"]},"request_id":"01KX"}""",
            ),
        )

        val result = repository.steer(settings(), "session_1", "Stop counting")

        assertTrue(result.isSuccess)
        assertEquals("/api/v1/config", server.takeRequest().path)
        val prompt = server.takeRequest()
        assertEquals(
            """{"content":[{"type":"text","text":"Stop counting"}],"model":"kimi-code/k3"}""",
            prompt.body.readUtf8(),
        )
    }

    @Test
    fun `default model is fetched once per server URL`() = runBlocking {
        val promptAck = MockResponse().setBody(
            """{"code":0,"msg":"success","data":{"prompt_id":"msg_1","user_message_id":"msg_1",""" +
                """"status":"running","created_at":"2026-07-21T00:00:00.000Z"},"request_id":"01KX"}""",
        )
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"success","data":{"default_model":"kimi-code/k3"},"request_id":"01KX"}""",
            ),
        )
        server.enqueue(promptAck)
        server.enqueue(promptAck)

        repository.sendPrompt(settings(), "session_1", "one")
        repository.sendPrompt(settings(), "session_1", "two")

        // One config fetch, two prompts: the second send hits the cache.
        assertEquals("/api/v1/config", server.takeRequest().path)
        assertEquals("/api/v1/sessions/session_1/prompts", server.takeRequest().path)
        assertEquals("/api/v1/sessions/session_1/prompts", server.takeRequest().path)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))

        // A different server URL refetches the config.
        val other = MockWebServer()
        other.start()
        try {
            other.enqueue(
                MockResponse().setBody(
                    """{"code":0,"msg":"success","data":{"default_model":"kimi-code/other"},"request_id":"01KX"}""",
                ),
            )
            other.enqueue(promptAck)

            repository.sendPrompt(ServerSettings(serverUrl = other.url("/").toString()), "session_1", "three")

            assertEquals("/api/v1/config", other.takeRequest().path)
            val prompt = other.takeRequest()
            assertTrue(prompt.body.readUtf8().contains(""""model":"kimi-code/other""""))
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `prompt goes out without a model when the config fetch fails and config is retried next send`() = runBlocking {
        val promptAck = MockResponse().setBody(
            """{"code":0,"msg":"success","data":{"prompt_id":"msg_1","user_message_id":"msg_1",""" +
                """"status":"running","created_at":"2026-07-21T00:00:00.000Z"},"request_id":"01KX"}""",
        )
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(promptAck)
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(promptAck)

        val first = repository.sendPrompt(settings(), "session_1", "one")
        val second = repository.sendPrompt(settings(), "session_1", "two")

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        assertEquals("/api/v1/config", server.takeRequest().path)
        assertEquals(
            """{"content":[{"type":"text","text":"one"}]}""",
            server.takeRequest().body.readUtf8(),
        )
        // Failures are not cached: the next send retries the config fetch.
        assertEquals("/api/v1/config", server.takeRequest().path)
        assertEquals("/api/v1/sessions/session_1/prompts", server.takeRequest().path)
    }

    @Test
    fun `getModels parses the models envelope`() = runBlocking {
        // Verified live 0.29.0 shape (docs/server-api.md): support_efforts/default_effort
        // only on K3.
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"provider":"managed:kimi-code","model":"kimi-code/kimi-for-coding",
                   "display_name":"K2.7 Coding","max_context_size":262144,
                   "capabilities":["thinking","tool_use"]},
                  {"provider":"managed:kimi-code","model":"kimi-code/kimi-for-coding-highspeed",
                   "display_name":"K2.7 Coding Highspeed","max_context_size":262144,
                   "capabilities":["thinking","tool_use"]},
                  {"provider":"managed:kimi-code","model":"kimi-code/k3",
                   "display_name":"K3","max_context_size":1048576,
                   "capabilities":["thinking","tool_use"],
                   "support_efforts":["low","high","max"],"default_effort":"high"}
                ]},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val result = repository.getModels(settings())

        assertEquals("/api/v1/models", server.takeRequest().path)
        assertEquals(
            listOf(
                ServerModel("managed:kimi-code", "kimi-code/kimi-for-coding", "K2.7 Coding", 262_144),
                ServerModel("managed:kimi-code", "kimi-code/kimi-for-coding-highspeed", "K2.7 Coding Highspeed", 262_144),
                ServerModel(
                    "managed:kimi-code",
                    "kimi-code/k3",
                    "K3",
                    1_048_576,
                    supportEfforts = listOf("low", "high", "max"),
                    defaultEffort = "high",
                ),
            ),
            result.getOrThrow(),
        )
    }

    @Test
    fun `models are fetched once per server URL`() = runBlocking {
        val modelsBody = MockResponse().setBody(
            """{"code":0,"msg":"success","data":{"items":[
              |{"provider":"managed:kimi-code","model":"kimi-code/k3","display_name":"K3","max_context_size":1048576}
              |]},"request_id":"01KX"}""".trimMargin(),
        )
        server.enqueue(modelsBody)

        repository.getModels(settings())
        repository.getModels(settings())

        // One fetch, two calls: the second hits the cache.
        assertEquals("/api/v1/models", server.takeRequest().path)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))

        // A different server URL refetches the models.
        val other = MockWebServer()
        other.start()
        try {
            other.enqueue(modelsBody)

            repository.getModels(ServerSettings(serverUrl = other.url("/").toString()))

            assertEquals("/api/v1/models", other.takeRequest().path)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `sendPrompt sends the session's chosen model in the prompt body`() = runBlocking {
        repository.setSessionModel("session_1", "kimi-code/kimi-for-coding-highspeed")
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"success","data":{"default_model":"kimi-code/k3"},"request_id":"01KX"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"success","data":{"prompt_id":"msg_1","user_message_id":"msg_1",""" +
                    """"status":"running","created_at":"2026-07-24T00:00:00.000Z"},"request_id":"01KX"}""",
            ),
        )

        val result = repository.sendPrompt(settings(), "session_1", "hello")

        assertTrue(result.isSuccess)
        // The session choice wins over the server default — and the config is never
        // even fetched: the first request is the prompt itself.
        val prompt = server.takeRequest()
        assertEquals("/api/v1/sessions/session_1/prompts", prompt.path)
        assertEquals(
            """{"content":[{"type":"text","text":"hello"}],"model":"kimi-code/kimi-for-coding-highspeed"}""",
            prompt.body.readUtf8(),
        )
    }

    @Test
    fun `steer sends the session's chosen model in the prompt body`() = runBlocking {
        repository.setSessionModel("session_1", "kimi-code/kimi-for-coding-highspeed")
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"success","data":{"prompt_id":"msg_1","user_message_id":"msg_1",""" +
                    """"status":"queued","created_at":"2026-07-24T00:00:00.000Z"},"request_id":"01KX"}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"success","data":{"steered":true,"prompt_ids":["msg_1"]},"request_id":"01KX"}""",
            ),
        )

        val result = repository.steer(settings(), "session_1", "Stop counting")

        assertTrue(result.isSuccess)
        val prompt = server.takeRequest()
        assertEquals(
            """{"content":[{"type":"text","text":"Stop counting"}],"model":"kimi-code/kimi-for-coding-highspeed"}""",
            prompt.body.readUtf8(),
        )
        assertEquals("/api/v1/sessions/session_1/prompts:steer", server.takeRequest().path)
    }

    /** Enqueues the live 0.29.0 models list (docs/server-api.md) and primes the
     * repository's models cache — the effort is only sent once the cache confirms
     * the session's model supports it (ticket 032). */
    private suspend fun primeModelsCache() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"provider":"managed:kimi-code","model":"kimi-code/kimi-for-coding",
                   "display_name":"K2.7 Coding","max_context_size":262144},
                  {"provider":"managed:kimi-code","model":"kimi-code/k3",
                   "display_name":"K3","max_context_size":1048576,
                   "support_efforts":["low","high","max"],"default_effort":"high"}
                ]},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )
        repository.getModels(settings()).getOrThrow()
        assertEquals("/api/v1/models", server.takeRequest().path)
    }

    private fun enqueuePromptAck() = server.enqueue(
        MockResponse().setBody(
            """{"code":0,"msg":"success","data":{"prompt_id":"msg_1","user_message_id":"msg_1",""" +
                """"status":"running","created_at":"2026-07-24T00:00:00.000Z"},"request_id":"01KX"}""",
        ),
    )

    @Test
    fun `sendPrompt sends the session's stored effort in the prompt body`() = runBlocking {
        repository.setSessionModel("session_1", "kimi-code/k3")
        repository.setSessionEffort("session_1", "low")
        primeModelsCache()
        enqueuePromptAck()

        val result = repository.sendPrompt(settings(), "session_1", "hello")

        assertTrue(result.isSuccess)
        val prompt = server.takeRequest()
        assertEquals("/api/v1/sessions/session_1/prompts", prompt.path)
        assertEquals(
            """{"content":[{"type":"text","text":"hello"}],"model":"kimi-code/k3","thinking":"low"}""",
            prompt.body.readUtf8(),
        )
    }

    @Test
    fun `steer sends the session's stored effort in the prompt body`() = runBlocking {
        repository.setSessionModel("session_1", "kimi-code/k3")
        repository.setSessionEffort("session_1", "max")
        primeModelsCache()
        enqueuePromptAck()
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"success","data":{"steered":true,"prompt_ids":["msg_1"]},"request_id":"01KX"}""",
            ),
        )

        val result = repository.steer(settings(), "session_1", "Stop counting")

        assertTrue(result.isSuccess)
        val prompt = server.takeRequest()
        assertEquals(
            """{"content":[{"type":"text","text":"Stop counting"}],"model":"kimi-code/k3","thinking":"max"}""",
            prompt.body.readUtf8(),
        )
        assertEquals("/api/v1/sessions/session_1/prompts:steer", server.takeRequest().path)
    }

    @Test
    fun `no stored effort sends no thinking field`() = runBlocking {
        repository.setSessionModel("session_1", "kimi-code/k3")
        primeModelsCache()
        enqueuePromptAck()

        val result = repository.sendPrompt(settings(), "session_1", "hello")

        assertTrue(result.isSuccess)
        assertEquals(
            """{"content":[{"type":"text","text":"hello"}],"model":"kimi-code/k3"}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `stored effort incompatible with the session's model is not sent`() = runBlocking {
        // Picked on K3, then the session switched to K2.7 (no support_efforts):
        // the stale effort must not reach the wire (ticket 032).
        repository.setSessionModel("session_1", "kimi-code/kimi-for-coding")
        repository.setSessionEffort("session_1", "low")
        primeModelsCache()
        enqueuePromptAck()

        val result = repository.sendPrompt(settings(), "session_1", "hello")

        assertTrue(result.isSuccess)
        assertEquals(
            """{"content":[{"type":"text","text":"hello"}],"model":"kimi-code/kimi-for-coding"}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `abortPrompt posts an empty body to the abort endpoint`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"code":0,"msg":"success","data":{"aborted":true},"request_id":"01KX"}"""),
        )

        val result = repository.abortPrompt(settings(), "session_1")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1:abort", request.path)
        assertEquals("{}", request.body.readUtf8())
    }

    @Test
    fun `abortPrompt maps non-zero envelope code to Api error`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"code":40001,"msg":"no running prompt","data":null,"request_id":"01KX"}"""),
        )

        val result = repository.abortPrompt(settings(), "session_1")

        assertTrue(result.exceptionOrNull() is DataError.Api)
    }

    @Test
    fun `isSessionRunning maps the busy flag`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"code":0,"msg":"success","data":{"busy":true},"request_id":"01KX"}"""),
        )

        assertEquals(true, repository.isSessionRunning(settings(), "session_1").getOrThrow())
        assertEquals("/api/v1/sessions/session_1/status", server.takeRequest().path)
    }

    @Test
    fun `socket subscribes, answers pings, and emits parsed events`() = runBlocking {
        val clientFrames = CopyOnWriteArrayList<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"server_hello","payload":{"protocol_version":2,"heartbeat_ms":30000}}""")
                        webSocket.send(
                            """{"type":"assistant.delta","seq":5,"epoch":"ep_1","volatile":true,"offset":0,""" +
                                """"session_id":"session_1","timestamp":"2026-07-17T04:11:00.603Z",""" +
                                """"payload":{"type":"assistant.delta","turnId":1,"delta":"ok","agentId":"main","sessionId":"session_1"}}""",
                        )
                        webSocket.send("""{"type":"ping","payload":{"nonce":"n1"}}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        clientFrames += text
                    }
                },
            ),
        )

        val emitted = CopyOnWriteArrayList<SocketEvent>()
        val job = launch { repository.observeSession(settings(), "session_1").collect { emitted += it } }
        try {
            withTimeout(5_000) { while (clientFrames.none { "\"pong\"" in it }) delay(10) }
            withTimeout(5_000) { while (emitted.none { it is SocketEvent.Event }) delay(10) }
        } finally {
            job.cancel()
        }

        assertEquals(SocketEvent.Connected, emitted.first())
        assertEquals(
            SocketEvent.Event(SessionEvent.AssistantDelta("ok")),
            emitted.first { it is SocketEvent.Event },
        )
        val subscribe = clientFrames.first { "\"subscribe\"" in it }
        assertTrue(subscribe.contains("session_1"))
        val pong = clientFrames.first { "\"pong\"" in it }
        assertTrue(pong.contains("n1"))
        Unit
    }

    @Test
    fun `tool call started frame emits the command preview`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // Verbatim tool.call.started shape (docs/server-api.md: args and
                        // display on start, unchanged from 0.23.3).
                        webSocket.send(
                            """{"type":"tool.call.started","seq":9,"epoch":"ep_1","session_id":"session_1",""" +
                                """"timestamp":"2026-07-19T15:07:43.000Z","payload":{"type":"tool.call.started",""" +
                                """"turnId":0,"toolCallId":"tool_3oZjtQUGzDwUwQwfkXioAtne","name":"Bash",""" +
                                """"args":{"command":"echo hello-from-spike"},""" +
                                """"description":"Running: echo hello-from-spike",""" +
                                """"display":{"kind":"command","command":"echo hello-from-spike",""" +
                                """"cwd":"/tmp/spike","language":"bash"},"agentId":"main","sessionId":"session_1"}}""",
                        )
                        // No display object: the preview is derived from args instead.
                        webSocket.send(
                            """{"type":"tool.call.started","seq":10,"epoch":"ep_1","session_id":"session_1",""" +
                                """"timestamp":"2026-07-19T15:07:44.000Z","payload":{"type":"tool.call.started",""" +
                                """"turnId":0,"toolCallId":"tool_2","name":"Read",""" +
                                """"args":{"path":"/tmp/spike/a.txt"},"agentId":"main","sessionId":"session_1"}}""",
                        )
                    }
                },
            ),
        )

        val emitted = CopyOnWriteArrayList<SocketEvent>()
        val job = launch { repository.observeSession(settings(), "session_1").collect { emitted += it } }
        try {
            withTimeout(5_000) { while (emitted.count { it is SocketEvent.Event } < 2) delay(10) }
        } finally {
            job.cancel()
        }

        val events = emitted.filterIsInstance<SocketEvent.Event>().map { it.event }
        assertEquals(
            SessionEvent.ToolCallStarted(
                "tool_3oZjtQUGzDwUwQwfkXioAtne",
                "Bash",
                "echo hello-from-spike",
                input = "{\n    \"command\": \"echo hello-from-spike\"\n}",
            ),
            events[0],
        )
        assertEquals(
            SessionEvent.ToolCallStarted("tool_2", "Read", "/tmp/spike/a.txt", input = "{\n    \"path\": \"/tmp/spike/a.txt\"\n}"),
            events[1],
        )
    }

    @Test
    fun `tool result frame emits the full output and the error flag`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // tool.result shape (docs/server-api.md): toolCallId + output; the
                        // output is a plain string for shell tools (ticket 023).
                        webSocket.send(
                            """{"type":"tool.result","seq":14,"epoch":"ep_1","session_id":"session_1",""" +
                                """"timestamp":"2026-07-19T15:07:50.000Z","payload":{"type":"tool.result",""" +
                                """"turnId":0,"toolCallId":"tool_1","output":"ok\n","agentId":"main","sessionId":"session_1"}}""",
                        )
                        // Structured tools (AskUserQuestion) carry a JSON object output.
                        webSocket.send(
                            """{"type":"tool.result","seq":15,"epoch":"ep_1","session_id":"session_1",""" +
                                """"timestamp":"2026-07-19T15:07:51.000Z","payload":{"type":"tool.result",""" +
                                """"turnId":0,"toolCallId":"tool_2","is_error":true,""" +
                                """"output":{"answers":{"Color?":"blue"}},"agentId":"main","sessionId":"session_1"}}""",
                        )
                    }
                },
            ),
        )

        val emitted = CopyOnWriteArrayList<SocketEvent>()
        val job = launch { repository.observeSession(settings(), "session_1").collect { emitted += it } }
        try {
            withTimeout(5_000) { while (emitted.count { it is SocketEvent.Event } < 2) delay(10) }
        } finally {
            job.cancel()
        }

        val events = emitted.filterIsInstance<SocketEvent.Event>().map { it.event }
        assertEquals(SessionEvent.ToolCallResult("tool_1", false, "ok\n"), events[0])
        assertEquals(
            SessionEvent.ToolCallResult("tool_2", true, "{\n    \"answers\": {\n        \"Color?\": \"blue\"\n    }\n}"),
            events[1],
        )
    }

    @Test
    fun `tool progress frame emits the output chunk`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // Verbatim tool.progress shape (docs/server-api.md, 0.29.0): a
                        // volatile frame whose update carries one live output chunk.
                        webSocket.send(
                            """{"type":"tool.progress","seq":14,"epoch":"ep_1","volatile":true,""" +
                                """"session_id":"session_1","timestamp":"2026-07-23T03:28:40.000Z",""" +
                                """"payload":{"type":"tool.progress","turnId":0,""" +
                                """"toolCallId":"tool_RMgBIy1asIorg1eV8P3ZhHiL",""" +
                                """"update":{"kind":"stdout","text":"doc-verify-0290\n"},""" +
                                """"agentId":"main","sessionId":"session_1"}}""",
                        )
                    }
                },
            ),
        )

        val emitted = CopyOnWriteArrayList<SocketEvent>()
        val job = launch { repository.observeSession(settings(), "session_1").collect { emitted += it } }
        try {
            withTimeout(5_000) { while (emitted.none { it is SocketEvent.Event }) delay(10) }
        } finally {
            job.cancel()
        }

        val events = emitted.filterIsInstance<SocketEvent.Event>().map { it.event }
        assertEquals(
            SessionEvent.ToolCallProgress("tool_RMgBIy1asIorg1eV8P3ZhHiL", "doc-verify-0290\n"),
            events.first(),
        )
    }

    @Test
    fun `context spliced frame emits the spliced user message`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // Verbatim 0.27.0 shape (docs/server-api.md, "Core event payloads").
                        webSocket.send(
                            """{"type":"context.spliced","seq":5,"epoch":"ep_1","session_id":"session_1",""" +
                                """"timestamp":"2026-07-19T00:00:00.500Z","payload":{"type":"context.spliced",""" +
                                """"start":0,"deleteCount":0,"messages":[{"role":"user",""" +
                                """"content":[{"type":"text","text":"hello from the phone"}],"toolCalls":[],""" +
                                """"origin":{"kind":"user"},"id":"msg_1"}],"agentId":"main","sessionId":"session_1"}}""",
                        )
                    }
                },
            ),
        )

        val emitted = CopyOnWriteArrayList<SocketEvent>()
        val job = launch { repository.observeSession(settings(), "session_1").collect { emitted += it } }
        try {
            withTimeout(5_000) { while (emitted.none { it is SocketEvent.Event }) delay(10) }
        } finally {
            job.cancel()
        }

        assertEquals(
            SocketEvent.Event(
                SessionEvent.ContextSpliced(
                    listOf(
                        ChatMessage(
                            "msg_1",
                            ChatMessage.Role.User,
                            listOf(ChatMessage.Part.Text("hello from the phone")),
                        ),
                    ),
                ),
            ),
            emitted.first { it is SocketEvent.Event },
        )
    }

    @Test
    fun `context spliced frame without a message id emits the steered user message`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // Steer echo shape (docs/server-api.md, steer round-trip): the
                        // spliced steered message carries no id.
                        webSocket.send(
                            """{"type":"context.spliced","seq":12,"epoch":"ep_1","session_id":"session_1",""" +
                                """"timestamp":"2026-07-20T00:00:01.000Z","payload":{"type":"context.spliced",""" +
                                """"start":1,"deleteCount":0,"messages":[{"role":"user",""" +
                                """"content":[{"type":"text","text":"Stop counting. Reply with exactly: steered-ok"}],""" +
                                """"toolCalls":[],"origin":{"kind":"user"}}],"agentId":"main","sessionId":"session_1"}}""",
                        )
                    }
                },
            ),
        )

        val emitted = CopyOnWriteArrayList<SocketEvent>()
        val job = launch { repository.observeSession(settings(), "session_1").collect { emitted += it } }
        try {
            withTimeout(5_000) { while (emitted.none { it is SocketEvent.Event }) delay(10) }
        } finally {
            job.cancel()
        }

        assertEquals(
            SocketEvent.Event(
                SessionEvent.ContextSpliced(
                    listOf(
                        ChatMessage(
                            "",
                            ChatMessage.Role.User,
                            listOf(ChatMessage.Part.Text("Stop counting. Reply with exactly: steered-ok")),
                        ),
                    ),
                ),
            ),
            emitted.first { it is SocketEvent.Event },
        )
    }

    @Test
    fun `history filters internal envelopes from user messages`() = runBlocking {
        // docs/server-api.md, "Internal envelopes in user messages": server-injected
        // harness traffic arrives as user-role text and must not reach the timeline.
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"id":"msg_3","role":"user",
                   "content":[{"type":"text","text":"deploy the app\n\n<system-reminder>\ninternal note\n</system-reminder>"}]},
                  {"id":"msg_2","role":"user",
                   "content":[{"type":"text","text":"<notification id=\"n_1\" category=\"task\" type=\"task.completed\" source_kind=\"background_task\" source_id=\"task_1\">\nTitle: Build finished\n<output-file path=\"/tmp/out.log\" bytes=\"3\">...</output-file>\n</notification>"}]},
                  {"id":"msg_1","role":"user",
                   "content":[{"type":"text","text":"<system-reminder>\nMake sure that you NEVER mention this reminder to the user.\n</system-reminder>"}]}
                ],"has_more":false},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val timeline = ChatTimeline()
        timeline.replaceHistory(repository.getMessages(settings(), "session_1").getOrThrow(), keepLive = false)

        // The envelope-only messages produce no row; the mixed one keeps its genuine text.
        assertEquals(listOf(ChatItem.UserMessage("msg_3", "deploy the app")), timeline.items())
    }

    @Test
    fun `context spliced filters internal envelopes from the echoed user message`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            """{"type":"context.spliced","seq":5,"epoch":"ep_1","session_id":"session_1",""" +
                                """"timestamp":"2026-07-21T00:00:00.000Z","payload":{"type":"context.spliced",""" +
                                """"start":0,"deleteCount":0,"messages":[{"role":"user",""" +
                                """"content":[{"type":"text","text":"<system-reminder>\ninternal\n</system-reminder>"}],""" +
                                """"toolCalls":[],"origin":{"kind":"injection"},"id":"msg_9"}],""" +
                                """"agentId":"main","sessionId":"session_1"}}""",
                        )
                    }
                },
            ),
        )

        val emitted = CopyOnWriteArrayList<SocketEvent>()
        val job = launch { repository.observeSession(settings(), "session_1").collect { emitted += it } }
        try {
            withTimeout(5_000) { while (emitted.none { it is SocketEvent.Event }) delay(10) }
        } finally {
            job.cancel()
        }

        val timeline = ChatTimeline()
        timeline.apply((emitted.first { it is SocketEvent.Event } as SocketEvent.Event).event)

        assertEquals(emptyList<ChatItem>(), timeline.items())
    }

    @Test
    fun `question frames emit the requested question then the resolved id`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // Verbatim 0.27.0 shapes (docs/server-api.md, "Core event payloads").
                        webSocket.send(
                            """{"type":"event.question.requested","seq":9,"epoch":"ep_1","session_id":"session_1",""" +
                                """"timestamp":"2026-07-20T06:06:49.808Z","payload":{"type":"event.question.requested",""" +
                                """"question_id":"tool_mU6ITagcpKjwSyiUKLHPZYWw","session_id":"session_1","turn_id":0,""" +
                                """"tool_call_id":"tool_mU6ITagcpKjwSyiUKLHPZYWw",""" +
                                """"questions":[{"id":"q_0","question":"Red or blue?","header":"Color",""" +
                                """"options":[{"id":"opt_0_0","label":"red","description":"warm color"},""" +
                                """{"id":"opt_0_1","label":"blue","description":"cool color"}],"allow_other":true}],""" +
                                """"created_at":"2026-07-20T06:06:49.808Z","agentId":"main","sessionId":"session_1"}}""",
                        )
                        webSocket.send(
                            """{"type":"event.question.answered","seq":11,"epoch":"ep_1","session_id":"session_1",""" +
                                """"timestamp":"2026-07-20T06:08:12.548Z","payload":{"type":"event.question.answered",""" +
                                """"question_id":"tool_mU6ITagcpKjwSyiUKLHPZYWw","answers":{"Red or blue?":"blue"},""" +
                                """"resolved_at":"2026-07-20T06:08:12.548Z","agentId":"main","sessionId":"session_1"}}""",
                        )
                        webSocket.send(
                            """{"type":"event.question.dismissed","seq":28,"epoch":"ep_1","session_id":"session_1",""" +
                                """"timestamp":"2026-07-20T06:12:37.844Z","payload":{"type":"event.question.dismissed",""" +
                                """"question_id":"tool_TThzzORvQB8tHTKyLhIL5bQt","dismissed_at":"2026-07-20T06:12:37.844Z",""" +
                                """"agentId":"main","sessionId":"session_1"}}""",
                        )
                    }
                },
            ),
        )

        val emitted = CopyOnWriteArrayList<SocketEvent>()
        val job = launch { repository.observeSession(settings(), "session_1").collect { emitted += it } }
        try {
            withTimeout(5_000) { while (emitted.count { it is SocketEvent.Event } < 3) delay(10) }
        } finally {
            job.cancel()
        }

        val events = emitted.filterIsInstance<SocketEvent.Event>().map { it.event }
        assertEquals(
            SessionEvent.QuestionRequested(
                PendingQuestion(
                    questionId = "tool_mU6ITagcpKjwSyiUKLHPZYWw",
                    items = listOf(
                        PendingQuestion.Item(
                            id = "q_0",
                            header = "Color",
                            text = "Red or blue?",
                            options = listOf(
                                PendingQuestion.Option("opt_0_0", "red", "warm color"),
                                PendingQuestion.Option("opt_0_1", "blue", "cool color"),
                            ),
                            allowOther = true,
                        ),
                    ),
                ),
            ),
            events[0],
        )
        assertEquals(SessionEvent.QuestionResolved("tool_mU6ITagcpKjwSyiUKLHPZYWw"), events[1])
        assertEquals(SessionEvent.QuestionResolved("tool_TThzzORvQB8tHTKyLhIL5bQt"), events[2])
    }

    @Test
    fun `getPendingQuestions parses the poll list`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"type":"event.question.requested","question_id":"tool_q1","session_id":"session_1",
                   "turn_id":0,"tool_call_id":"tool_q1",
                   "questions":[{"id":"q_0","question":"Red or blue?","header":"Color",
                                 "options":[{"id":"opt_0_0","label":"red"},
                                            {"id":"opt_0_1","label":"blue","description":"cool color"}],
                                 "allow_other":true}],
                   "created_at":"2026-07-20T06:06:49.808Z","agentId":"main","sessionId":"session_1"}
                ]},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val result = repository.getPendingQuestions(settings(), "session_1")

        assertEquals("/api/v1/sessions/session_1/questions?status=pending", server.takeRequest().path)
        assertEquals(
            listOf(
                PendingQuestion(
                    questionId = "tool_q1",
                    items = listOf(
                        PendingQuestion.Item(
                            id = "q_0",
                            header = "Color",
                            text = "Red or blue?",
                            options = listOf(
                                PendingQuestion.Option("opt_0_0", "red", null),
                                PendingQuestion.Option("opt_0_1", "blue", "cool color"),
                            ),
                            allowOther = true,
                        ),
                    ),
                ),
            ),
            result.getOrThrow(),
        )
    }

    @Test
    fun `answerQuestion posts the per-item answers keyed by item id`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"success","data":{"resolved":true,"resolved_at":"2026-07-20T06:08:12.548Z"},"request_id":"01KX"}""",
            ),
        )

        val result = repository.answerQuestion(
            settings(),
            "session_1",
            "tool_q1",
            listOf(
                QuestionAnswer.OptionPick("q_0", "opt_0_1"),
                QuestionAnswer.Other("q_1", "ramen"),
            ),
        )

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/questions/tool_q1", request.path)
        assertEquals(
            """{"answers":{"q_0":{"kind":"single","option_id":"opt_0_1"},"q_1":{"kind":"other","text":"ramen"}}}""",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `answerQuestion maps an already-resolved question to Api error`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"code":40405,"msg":"question tool_q1 not found","data":null,"request_id":"01KX"}"""),
        )

        val result = repository.answerQuestion(
            settings(), "session_1", "tool_q1", listOf(QuestionAnswer.OptionPick("q_0", "opt_0_1")),
        )

        assertTrue(result.exceptionOrNull() is DataError.Api)
    }

    @Test
    fun `dismissQuestion treats the 40909 envelope as success`() = runBlocking {
        // docs/server-api.md: a successful dismiss is HTTP 200 with the non-zero code
        // 40909 — success is data.dismissed, not code == 0.
        server.enqueue(
            MockResponse().setBody(
                """{"code":40909,"msg":"question tool_q1 dismissed",""" +
                    """"data":{"dismissed":true,"dismissed_at":"2026-07-20T06:17:24.711Z"},"request_id":"01KX"}""",
            ),
        )

        val result = repository.dismissQuestion(settings(), "session_1", "tool_q1")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/questions/tool_q1:dismiss", request.path)
        assertEquals("{}", request.body.readUtf8())
    }

    @Test
    fun `dismissQuestion maps an already-resolved question to Api error`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"code":40405,"msg":"question tool_q1 not found","data":null,"request_id":"01KX"}"""),
        )

        val result = repository.dismissQuestion(settings(), "session_1", "tool_q1")

        assertTrue(result.exceptionOrNull() is DataError.Api)
    }

    @Test
    fun `uploadFile posts one multipart file and maps the response to FileMeta`() = runBlocking {
        // Live 0.29.0 response shape (docs/server-api.md, Files — ticket 033).
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"success","data":{"id":"f_e49777a6","name":"attach-check.txt",""" +
                    """"media_type":"text/plain","size":27,"created_at":"2026-07-24T11:56:55.720Z"},"request_id":"01KX"}""",
            ),
        )

        val result = repository.uploadFile(settings(), "attach-check.txt", "text/plain", "doc-verify-033".toByteArray())

        assertEquals(
            FileMeta(id = "f_e49777a6", name = "attach-check.txt", mediaType = "text/plain", sizeBytes = 27),
            result.getOrThrow(),
        )
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/files", request.path)
        assertTrue(request.headers["Content-Type"]?.startsWith("multipart/form-data") == true)
        val body = request.body.readUtf8()
        // One `file` part with the bytes and one `name` field, per the documented curl.
        assertTrue(body.contains("""name="file"; filename="attach-check.txt""""))
        assertTrue(body.contains("doc-verify-033"))
        assertTrue(body.contains("""name="name""""))
    }

    @Test
    fun `uploadFile maps a 413 to an Http error`() = runBlocking {
        // 50 MiB server limit (docs/server-api.md): surfaced as a plain send failure.
        server.enqueue(MockResponse().setResponseCode(413))

        val result = repository.uploadFile(settings(), "big.bin", "application/octet-stream", ByteArray(4))

        assertTrue((result.exceptionOrNull() as? DataError.Http)?.code == 413)
    }

    @Test
    fun `sendPrompt maps uploaded attachments to image and file content items before the text`() = runBlocking {
        // docs/server-api.md, Prompts: image/* becomes an image part, anything else a
        // file part; attachment items precede the text item (ticket 033).
        enqueueConfigWithoutDefaultModel()
        enqueuePromptAck()

        val result = repository.sendPrompt(
            settings(),
            "session_1",
            "look at these",
            attachments = listOf(
                FileMeta(id = "f_img", name = "photo.png", mediaType = "image/png", sizeBytes = 1_234),
                FileMeta(id = "f_txt", name = "notes.txt", mediaType = "text/plain", sizeBytes = 27),
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals("/api/v1/config", server.takeRequest().path)
        assertEquals(
            """{"content":[""" +
                """{"type":"image","source":{"kind":"file","file_id":"f_img"}},""" +
                """{"type":"file","file_id":"f_txt","name":"notes.txt","media_type":"text/plain","size":27},""" +
                """{"type":"text","text":"look at these"}]}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `sendPrompt with attachments and blank text sends no text item`() = runBlocking {
        enqueueConfigWithoutDefaultModel()
        enqueuePromptAck()

        val result = repository.sendPrompt(
            settings(),
            "session_1",
            "",
            attachments = listOf(FileMeta(id = "f_txt", name = "notes.txt", mediaType = "text/plain", sizeBytes = 27)),
        )

        assertTrue(result.isSuccess)
        assertEquals("/api/v1/config", server.takeRequest().path)
        assertEquals(
            """{"content":[{"type":"file","file_id":"f_txt","name":"notes.txt","media_type":"text/plain","size":27}]}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `text-only sendPrompt body is unchanged by the attachments support`() = runBlocking {
        enqueueConfigWithoutDefaultModel()
        enqueuePromptAck()

        val result = repository.sendPrompt(settings(), "session_1", "hello")

        assertTrue(result.isSuccess)
        assertEquals("/api/v1/config", server.takeRequest().path)
        assertEquals(
            """{"content":[{"type":"text","text":"hello"}]}""",
            server.takeRequest().body.readUtf8(),
        )
    }
}
