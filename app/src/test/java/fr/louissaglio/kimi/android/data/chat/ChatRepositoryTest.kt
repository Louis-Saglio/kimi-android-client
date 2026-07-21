package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
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

    private val server = MockWebServer()
    private val repository = ChatRepository(OkHttpClient(), Json { ignoreUnknownKeys = true })

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun settings() = ServerSettings(serverUrl = server.url("/").toString())

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
                               "input":{"command":"echo hi"}}],
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
                    ChatMessage.Part.ToolUse("tool_1", "Bash"),
                ),
            ),
            messages[1],
        )
        assertEquals(
            ChatMessage("msg_3", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", true))),
            messages[2],
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
        server.enqueue(
            MockResponse().setBody("""{"code":40401,"msg":"session session_1 does not exist","data":null,"request_id":"01KX"}"""),
        )

        val result = repository.steer(settings(), "session_1", "hello")

        assertTrue(result.exceptionOrNull() is DataError.Api)
        assertEquals("/api/v1/sessions/session_1/prompts", server.takeRequest().path)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
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
}
