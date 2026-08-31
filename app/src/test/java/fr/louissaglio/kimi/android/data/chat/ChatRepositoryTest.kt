package fr.louissaglio.kimi.android.data.chat

import androidx.datastore.preferences.core.emptyPreferences
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.sessions.SessionActivityStore
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.testChatRepository
import fr.louissaglio.kimi.android.testutil.testChatTimeline
import fr.louissaglio.kimi.android.testutil.webSocketUpgradeResponse
import fr.louissaglio.kimi.android.ui.chat.ChatItem
import fr.louissaglio.kimi.android.ui.chat.ToolStatus
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatRepositoryTest {

    private val server = MockWebServer()
    private val activityStore = SessionActivityStore()
    private val repository = testChatRepository(FakePreferencesDataStore(emptyPreferences()), activityStore)

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun settings() = ServerSettings(serverUrl = server.url("/").toString())

    /** A `/api/v1/config` response without a default model (pre-0.28 server shape):
     * prompts then go out without a `model` field. */
    private fun enqueueConfigWithoutDefaultModel() = server.enqueue(
        MockResponse(body = 
            """{"code":0,"msg":"success","data":{"default_permission_mode":"yolo"},"request_id":"01KX"}""",
        ),
    )

    /**
     * Collects what one socket receives after the server sends [frames] on upgrade:
     * collects until [eventCount] substantive events arrived (server_hello and
     * phase-only agent.status frames emit none), then waits [settleMillis] longer as
     * proof that nothing more comes, and cancels. Returns every emitted [SocketEvent],
     * Connected first; callers assert on the [SocketEvent.Event] subset.
     */
    private suspend fun runSocketEvents(
        vararg frames: String,
        eventCount: Int = frames.size,
        settleMillis: Long = 0,
    ): List<SocketEvent> = coroutineScope {
        server.enqueue(
            webSocketUpgradeResponse(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        frames.forEach(webSocket::send)
                    }
                },
            ),
        )
        val emitted = CopyOnWriteArrayList<SocketEvent>()
        val job = launch { repository.observeSession(settings(), "session_1").collect { emitted += it } }
        try {
            awaitCondition { emitted.count { it is SocketEvent.Event } >= eventCount }
            if (settleMillis > 0) delay(settleMillis)
        } finally {
            job.cancel()
        }
        emitted
    }

    /** A WS upgrade that records every client frame into [clientFrames] and runs
     * [onOpen] on connect — the ticket 048 cursor tests drive two sequential
     * connections (two `observe()` collections) against one repository. */
    private fun wsUpgrade(
        clientFrames: CopyOnWriteArrayList<String>,
        onOpen: (WebSocket) -> Unit = {},
    ): MockResponse = webSocketUpgradeResponse(
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket)

            override fun onMessage(webSocket: WebSocket, text: String) {
                clientFrames += text
            }
        },
    )

    @Test
    fun `history envelope maps to domain messages oldest-first`() = runBlocking {
        server.enqueue(
            MockResponse(body = 
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
                ],"has_more":true},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val result = repository.getMessages(settings(), "session_1")

        val page = result.getOrThrow()
        assertEquals("/api/v1/sessions/session_1/messages?page_size=100", server.takeRequest().target)
        assertTrue(page.hasMore)
        // Wire order is newest-first; domain order is oldest-first.
        val messages = page.items
        assertEquals(3, messages.size)
        assertEquals(
            ChatMessage(
                "msg_1",
                ChatMessage.Role.User,
                listOf(ChatMessage.Part.Text("run echo hi")),
                createdAt = Instant.parse("2026-07-17T04:12:10.000Z"),
            ),
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
                createdAt = Instant.parse("2026-07-17T04:12:10.500Z"),
            ),
            messages[1],
        )
        assertEquals(
            ChatMessage(
                "msg_3",
                ChatMessage.Role.Tool,
                listOf(ChatMessage.Part.ToolResult("tool_1", true, "hi\n")),
                createdAt = Instant.parse("2026-07-17T04:12:11.000Z"),
            ),
            messages[2],
        )
    }

    @Test
    fun `snapshot messages page keeps its oldest-first wire order`() = runBlocking {
        // Regression (Louis, 2026-08-02): unlike /messages (newest-first on the wire),
        // the snapshot's messages.items come OLDEST-first — live-verified on 0.31.1
        // (item ids and created_at ascend across the page). Mapping them with the
        // /messages reversal inverted the cold-start timeline: the bottom pin landed
        // mid-session, tool results flattened BEFORE their calls (orphan done rows,
        // calls stuck Running), and the paging cursor became the newest id.
        server.enqueue(
            MockResponse(body =
                """
                {"code":0,"msg":"success","data":{
                  "as_of_seq":7,"epoch":"ep_1",
                  "session":{"id":"session_1","busy":false,
                    "usage":{"context_tokens":0,"context_limit":1048576}},
                  "messages":{"items":[
                    {"id":"msg_1","session_id":"session_1","role":"user",
                     "content":[{"type":"text","text":"run it"}],
                     "created_at":"2026-08-01T10:00:00.000Z"},
                    {"id":"msg_2","session_id":"session_1","role":"assistant",
                     "content":[{"type":"tool_use","tool_call_id":"tool_1","tool_name":"Edit",
                                 "input":{"path":"docs/server-api.md"}}],
                     "created_at":"2026-08-01T10:00:01.000Z"},
                    {"id":"msg_3","session_id":"session_1","role":"tool",
                     "content":[{"type":"tool_result","tool_call_id":"tool_1","output":"ok","is_error":false}],
                     "created_at":"2026-08-01T10:00:02.000Z"}
                  ],"has_more":true},
                  "in_flight_turn":null,"subagents":[],
                  "pending_approvals":[],"pending_questions":[]},
                 "request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val snapshot = repository.getSnapshot(settings(), "session_1").getOrThrow()

        assertEquals("/api/v1/sessions/session_1/snapshot", server.takeRequest().target)
        // Wire order preserved — NOT reversed like a /messages page.
        assertEquals(listOf("msg_1", "msg_2", "msg_3"), snapshot.messages.items.map { it.id })
        assertTrue(snapshot.messages.hasMore)
        assertEquals(7, snapshot.asOfSeq)
        assertEquals("ep_1", snapshot.epoch)
        // A real (non-placeholder) usage maps through — the pre-0.32.0 wire shape.
        assertEquals(ContextUsage(0, 1048576), snapshot.contextUsage)
    }

    @Test
    fun `snapshot placeholder usage maps to no context usage`() = runBlocking {
        // 0.32.0 (live-verified busy + idle, 2026-08-05): the snapshot's session.usage
        // is the server's all-zero placeholder on every read path — "unknown", never a
        // real 0/0 fill (web parity: kimi-web's isPlaceholderSessionUsage). Applied
        // as-is it showed a fake 0/0 ring on cold open until the next live frame; the
        // real fill only comes from /status and the WS agent.status.updated stream.
        server.enqueue(
            MockResponse(body =
                """
                {"code":0,"msg":"success","data":{
                  "as_of_seq":7,"epoch":"ep_1",
                  "session":{"id":"session_1","busy":false,
                    "usage":{"input_tokens":0,"output_tokens":0,"cache_read_tokens":0,
                             "cache_creation_tokens":0,"total_cost_usd":0,
                             "context_tokens":0,"context_limit":0,"turn_count":0}},
                  "messages":{"items":[],"has_more":false},
                  "in_flight_turn":null,"subagents":[],
                  "pending_approvals":[],"pending_questions":[]},
                 "request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val snapshot = repository.getSnapshot(settings(), "session_1").getOrThrow()

        assertNull(snapshot.contextUsage)
    }

    @Test
    fun `messages next page sends before_id and parses has_more`() = runBlocking {
        // First page (no cursor): has_more true — paging backwards continues (ticket 043).
        server.enqueue(
            MockResponse(body = 
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"id":"msg_3","role":"user","content":[{"type":"text","text":"third"}]},
                  {"id":"msg_2","role":"user","content":[{"type":"text","text":"second"}]}
                ],"has_more":true},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )
        // Older page (before_id = oldest loaded message): has_more false — stop.
        server.enqueue(
            MockResponse(body = 
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"id":"msg_1","role":"user","content":[{"type":"text","text":"first"}]}
                ],"has_more":false},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val first = repository.getMessages(settings(), "session_1")
        val second = repository.getMessages(settings(), "session_1", beforeId = "msg_2")

        assertEquals("/api/v1/sessions/session_1/messages?page_size=100", server.takeRequest().target)
        assertEquals("/api/v1/sessions/session_1/messages?page_size=100&before_id=msg_2", server.takeRequest().target)
        assertTrue(first.getOrThrow().hasMore)
        // Wire order is newest-first on paged responses too; domain stays oldest-first.
        assertEquals(listOf("msg_1"), second.getOrThrow().items.map { it.id })
        assertFalse(second.getOrThrow().hasMore)
    }

    @Test
    fun `a malformed created_at is dropped instead of failing the message`() = runBlocking {
        server.enqueue(
            MockResponse(body = 
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"id":"msg_1","session_id":"session_1","role":"user",
                   "content":[{"type":"text","text":"hi"}],
                   "created_at":"not-a-timestamp"}
                ],"has_more":false},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val messages = repository.getMessages(settings(), "session_1").getOrThrow().items

        assertEquals(
            ChatMessage("msg_1", ChatMessage.Role.User, listOf(ChatMessage.Part.Text("hi")), createdAt = null),
            messages.single(),
        )
    }

    @Test
    fun `history maps the attachment notice and image parts to domain parts`() = runBlocking {
        // Fixture per docs/server-api.md (ticket 034): the server rewrites file parts
        // into the "Attached file" notice; images come back as a data-url source in
        // history, as raw base64 in the prompt ACK echo, or as a file reference.
        server.enqueue(
            MockResponse(body = 
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"id":"msg_1","session_id":"session_1","role":"user",
                   "content":[
                     {"type":"text","text":"Attached file \"notes.txt\" (text/plain, 340 bytes): /home/ubuntu/.kimi-code/sessions/wd_1/session_2/attachments/f_01KYA4RCBE4NPA1RX5TT7DB5J9-notes.txt — open it with the Read tool"},
                     {"type":"text","text":"what is in these?"},
                     {"type":"image","source":{"kind":"url","url":"data:image/png;base64,aGVsbG8="}},
                     {"type":"image","source":{"kind":"base64","media_type":"image/jpeg","data":"anBlZw=="}},
                     {"type":"image","source":{"kind":"file","file_id":"f_abc","media_type":"image/webp"}}
                   ],
                   "created_at":"2026-07-24T04:12:10.000Z"}
                ],"has_more":false},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val messages = repository.getMessages(settings(), "session_1").getOrThrow().items

        assertEquals(
            ChatMessage(
                "msg_1",
                ChatMessage.Role.User,
                listOf(
                    ChatMessage.Part.Attachment("notes.txt", "text/plain", 340, "f_01KYA4RCBE4NPA1RX5TT7DB5J9"),
                    ChatMessage.Part.Text("what is in these?"),
                    ChatMessage.Part.Image(base64Data = "aGVsbG8=", fileId = null),
                    ChatMessage.Part.Image(base64Data = "anBlZw==", fileId = null),
                    ChatMessage.Part.Image(base64Data = null, fileId = "f_abc"),
                ),
                createdAt = Instant.parse("2026-07-24T04:12:10.000Z"),
            ),
            messages.single(),
        )
    }

    @Test
    fun `history maps a session media image source to a fetchable image part`() = runBlocking {
        // 0.37.2 (ticket 104, docs/server-api.md): history and the prompt ACK echo
        // project an uploaded image as a session_media source; its id is session-scoped
        // and fetches through the session-media route, not /api/v1/files.
        server.enqueue(
            MockResponse(body =
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"id":"msg_1","session_id":"session_1","role":"user",
                   "content":[
                     {"type":"text","text":"what is in this?"},
                     {"type":"image","source":{"kind":"session_media","file_id":"f_img"}}
                   ],
                   "created_at":"2026-08-18T10:00:00.000Z"}
                ],"has_more":false},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val messages = repository.getMessages(settings(), "session_1").getOrThrow().items

        assertEquals(
            ChatMessage(
                "msg_1",
                ChatMessage.Role.User,
                listOf(
                    ChatMessage.Part.Text("what is in this?"),
                    ChatMessage.Part.Image(base64Data = null, fileId = "f_img", isSessionMedia = true),
                ),
                createdAt = Instant.parse("2026-08-18T10:00:00.000Z"),
            ),
            messages.single(),
        )
    }

    @Test
    fun `downloadFile streams the bytes and the content type`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body("hello file")
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .build(),
        )

        val download = repository.downloadFile(settings(), "f_1").getOrThrow()

        assertEquals("/api/v1/files/f_1", server.takeRequest().target)
        assertEquals("text/plain; charset=utf-8", download.mediaType)
        assertEquals("hello file", String(download.bytes))
    }

    @Test
    fun `downloadSessionMedia streams the bytes through the session media route`() = runBlocking {
        // Ticket 104 (docs/server-api.md, Files → Session media): the canonical route
        // for a session_media image source.
        server.enqueue(
            MockResponse.Builder()
                .body("hello media")
                .setHeader("Content-Type", "image/png")
                .build(),
        )

        val download = repository.downloadSessionMedia(settings(), "session_1", "f_1").getOrThrow()

        assertEquals("/api/v1/sessions/session_1/media/f_1", server.takeRequest().target)
        assertEquals("image/png", download.mediaType)
        assertEquals("hello media", String(download.bytes))
    }

    @Test
    fun `history tool result output and error flag reach the activity row`() = runBlocking {
        // Fixture per docs/server-api.md (ticket 023): the expanded row reads the full
        // call input and the result output, plus the error tint from is_error.
        server.enqueue(
            MockResponse(body = 
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

        val timeline = testChatTimeline()
        timeline.replaceHistory(repository.getMessages(settings(), "session_1").getOrThrow().items, keepLive = false)

        assertEquals(
            listOf(
                ChatItem.ToolGroup(
                    ChatItem.ToolActivity(
                        "tool_1",
                        "Bash",
                        ToolStatus.Error,
                        "./gradlew test",
                        input = "{\n    \"command\": \"./gradlew test\"\n}",
                        output = "FAIL 3 tests\n",
                    ),
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
            MockResponse(body = 
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

        val timeline = testChatTimeline()
        timeline.replaceHistory(repository.getMessages(settings(), "session_1").getOrThrow().items, keepLive = false)

        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "run echo doc-verify"),
                ChatItem.ToolGroup(
                    ChatItem.ToolActivity(
                        "tool_er2XKV7KXHB4A3U2AXGxZfrY",
                        "Bash",
                        ToolStatus.Running,
                        "echo doc-verify",
                        input = "{\n    \"command\": \"echo doc-verify\"\n}",
                    ),
                ),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `non-zero envelope code maps to Api error`() = runBlocking {
        server.enqueue(
            MockResponse(body = """{"code":40401,"msg":"session session_1 does not exist","data":null,"request_id":"01KX"}"""),
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
    fun `observeSession with an invalid base URL emits Failed and completes`() = runBlocking {
        val events = repository.observeSession(ServerSettings(serverUrl = "not a url"), "session_1").toList()

        assertEquals(listOf(SocketEvent.Failed(DataError.InvalidUrl)), events)
    }

    @Test
    fun `sendPrompt posts text content parts to the prompts endpoint`() = runBlocking {
        enqueueConfigWithoutDefaultModel()
        server.enqueue(
            MockResponse(body = 
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
        assertEquals("/api/v1/config", server.takeRequest().target)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/prompts", request.target)
        assertEquals(
            """{"content":[{"type":"text","text":"Reply with exactly: ok"}]}""",
            request.body!!.utf8(),
        )
    }

    @Test
    fun `sendPrompt maps non-zero envelope code to Api error`() = runBlocking {
        enqueueConfigWithoutDefaultModel()
        server.enqueue(
            MockResponse(body = """{"code":40401,"msg":"session session_1 does not exist","data":null,"request_id":"01KX"}"""),
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
            MockResponse(body = 
                """
                {"code":0,"msg":"success","data":{"prompt_id":"msg_1",
                  "user_message_id":"msg_1","status":"queued",
                  "content":[{"type":"text","text":"Stop counting"}],
                  "created_at":"2026-07-20T00:00:00.000Z"},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse(body = 
                """{"code":0,"msg":"success","data":{"steered":true,"prompt_ids":["msg_1"]},"request_id":"01KX"}""",
            ),
        )

        val result = repository.steer(settings(), "session_1", "Stop counting")

        assertTrue(result.isSuccess)
        assertEquals("/api/v1/config", server.takeRequest().target)
        val prompt = server.takeRequest()
        assertEquals("POST", prompt.method)
        assertEquals("/api/v1/sessions/session_1/prompts", prompt.target)
        assertEquals("""{"content":[{"type":"text","text":"Stop counting"}]}""", prompt.body!!.utf8())
        val steer = server.takeRequest()
        assertEquals("POST", steer.method)
        assertEquals("/api/v1/sessions/session_1/prompts:steer", steer.target)
        assertEquals("""{"prompt_ids":["msg_1"]}""", steer.body!!.utf8())
    }

    @Test
    fun `steer maps a non-zero steer envelope code to Api error`() = runBlocking {
        enqueueConfigWithoutDefaultModel()
        server.enqueue(MockResponse(body = PROMPT_ACK_BODY))
        server.enqueue(
            MockResponse(body = """{"code":40402,"msg":"no active prompt to steer into","data":null,"request_id":"01KX"}"""),
        )

        val result = repository.steer(settings(), "session_1", "too late")

        assertTrue(result.exceptionOrNull() is DataError.Api)
    }

    @Test
    fun `steer does not call the steer endpoint when the prompt post fails`() = runBlocking {
        enqueueConfigWithoutDefaultModel()
        server.enqueue(
            MockResponse(body = """{"code":40401,"msg":"session session_1 does not exist","data":null,"request_id":"01KX"}"""),
        )

        val result = repository.steer(settings(), "session_1", "hello")

        assertTrue(result.exceptionOrNull() is DataError.Api)
        assertEquals("/api/v1/config", server.takeRequest().target)
        assertEquals("/api/v1/sessions/session_1/prompts", server.takeRequest().target)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `sendPrompt success records the session activity`() = runBlocking {
        enqueueConfigWithoutDefaultModel()
        enqueuePromptAck()

        repository.sendPrompt(settings(), "session_1", "hello").getOrThrow()

        assertEquals(setOf("session_1"), activityStore.consumeAll().keys)
    }

    @Test
    fun `sendPrompt failure records no activity`() = runBlocking {
        enqueueConfigWithoutDefaultModel()
        server.enqueue(
            MockResponse(body = """{"code":40401,"msg":"session session_1 does not exist","data":null,"request_id":"01KX"}"""),
        )

        repository.sendPrompt(settings(), "session_1", "hello")

        assertTrue(activityStore.consumeAll().isEmpty())
    }

    @Test
    fun `steer success records the session activity`() = runBlocking {
        enqueueConfigWithoutDefaultModel()
        enqueuePromptAck()
        server.enqueue(
            MockResponse(body = 
                """{"code":0,"msg":"success","data":{"steered":true,"prompt_ids":["msg_1"]},"request_id":"01KX"}""",
            ),
        )

        repository.steer(settings(), "session_1", "Stop counting").getOrThrow()

        assertEquals(setOf("session_1"), activityStore.consumeAll().keys)
    }

    @Test
    fun `steer failure records no activity`() = runBlocking {
        enqueueConfigWithoutDefaultModel()
        enqueuePromptAck()
        server.enqueue(
            MockResponse(body = """{"code":40402,"msg":"no active prompt to steer into","data":null,"request_id":"01KX"}"""),
        )

        repository.steer(settings(), "session_1", "too late")

        assertTrue(activityStore.consumeAll().isEmpty())
    }

    @Test
    fun `sendPrompt sends the server default model in the prompt body`() = runBlocking {
        server.enqueue(
            MockResponse(body = 
                """{"code":0,"msg":"success","data":{"default_model":"kimi-code/k3"},"request_id":"01KX"}""",
            ),
        )
        server.enqueue(MockResponse(body = PROMPT_ACK_BODY))

        val result = repository.sendPrompt(settings(), "session_1", "hello")

        assertTrue(result.isSuccess)
        assertEquals("/api/v1/config", server.takeRequest().target)
        val prompt = server.takeRequest()
        assertEquals(
            """{"content":[{"type":"text","text":"hello"}],"model":"kimi-code/k3"}""",
            prompt.body!!.utf8(),
        )
    }

    @Test
    fun `steer sends the server default model in the prompt body`() = runBlocking {
        server.enqueue(
            MockResponse(body = 
                """{"code":0,"msg":"success","data":{"default_model":"kimi-code/k3"},"request_id":"01KX"}""",
            ),
        )
        server.enqueue(MockResponse(body = PROMPT_ACK_BODY))
        server.enqueue(
            MockResponse(body = 
                """{"code":0,"msg":"success","data":{"steered":true,"prompt_ids":["msg_1"]},"request_id":"01KX"}""",
            ),
        )

        val result = repository.steer(settings(), "session_1", "Stop counting")

        assertTrue(result.isSuccess)
        assertEquals("/api/v1/config", server.takeRequest().target)
        val prompt = server.takeRequest()
        assertEquals(
            """{"content":[{"type":"text","text":"Stop counting"}],"model":"kimi-code/k3"}""",
            prompt.body!!.utf8(),
        )
    }

    @Test
    fun `default model is fetched once per server URL`() = runBlocking {
        val promptAck = MockResponse(body = PROMPT_ACK_BODY)
        server.enqueue(
            MockResponse(body = 
                """{"code":0,"msg":"success","data":{"default_model":"kimi-code/k3"},"request_id":"01KX"}""",
            ),
        )
        server.enqueue(promptAck)
        server.enqueue(promptAck)

        repository.sendPrompt(settings(), "session_1", "one")
        repository.sendPrompt(settings(), "session_1", "two")

        // One config fetch, two prompts: the second send hits the cache.
        assertEquals("/api/v1/config", server.takeRequest().target)
        assertEquals("/api/v1/sessions/session_1/prompts", server.takeRequest().target)
        assertEquals("/api/v1/sessions/session_1/prompts", server.takeRequest().target)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))

        // A different server URL refetches the config.
        val other = MockWebServer()
        other.start()
        try {
            other.enqueue(
                MockResponse(body = 
                    """{"code":0,"msg":"success","data":{"default_model":"kimi-code/other"},"request_id":"01KX"}""",
                ),
            )
            other.enqueue(promptAck)

            repository.sendPrompt(ServerSettings(serverUrl = other.url("/").toString()), "session_1", "three")

            assertEquals("/api/v1/config", other.takeRequest().target)
            val prompt = other.takeRequest()
            assertTrue(prompt.body!!.utf8().contains(""""model":"kimi-code/other""""))
        } finally {
            other.close()
        }
    }

    @Test
    fun `prompt goes out without a model when the config fetch fails and config is retried next send`() = runBlocking {
        val promptAck = MockResponse(body = PROMPT_ACK_BODY)
        server.enqueue(MockResponse(code = 500))
        server.enqueue(promptAck)
        server.enqueue(MockResponse(code = 500))
        server.enqueue(promptAck)

        val first = repository.sendPrompt(settings(), "session_1", "one")
        val second = repository.sendPrompt(settings(), "session_1", "two")

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        assertEquals("/api/v1/config", server.takeRequest().target)
        assertEquals(
            """{"content":[{"type":"text","text":"one"}]}""",
            server.takeRequest().body!!.utf8(),
        )
        // Failures are not cached: the next send retries the config fetch.
        assertEquals("/api/v1/config", server.takeRequest().target)
        assertEquals("/api/v1/sessions/session_1/prompts", server.takeRequest().target)
    }

    @Test
    fun `getModels parses the models envelope`() = runBlocking {
        // Verified live 0.29.0 shape (docs/server-api.md): support_efforts/default_effort
        // only on K3.
        server.enqueue(
            MockResponse(body = 
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

        assertEquals("/api/v1/models", server.takeRequest().target)
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
        val modelsBody = MockResponse(body = 
            """{"code":0,"msg":"success","data":{"items":[
              |{"provider":"managed:kimi-code","model":"kimi-code/k3","display_name":"K3","max_context_size":1048576}
              |]},"request_id":"01KX"}""".trimMargin(),
        )
        server.enqueue(modelsBody)

        repository.getModels(settings())
        repository.getModels(settings())

        // One fetch, two calls: the second hits the cache.
        assertEquals("/api/v1/models", server.takeRequest().target)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))

        // A different server URL refetches the models.
        val other = MockWebServer()
        other.start()
        try {
            other.enqueue(modelsBody)

            repository.getModels(ServerSettings(serverUrl = other.url("/").toString()))

            assertEquals("/api/v1/models", other.takeRequest().target)
        } finally {
            other.close()
        }
    }

    @Test
    fun `sendPrompt sends the session's chosen model in the prompt body`() = runBlocking {
        repository.setSessionModel("session_1", "kimi-code/kimi-for-coding-highspeed")
        server.enqueue(
            MockResponse(body = 
                """{"code":0,"msg":"success","data":{"default_model":"kimi-code/k3"},"request_id":"01KX"}""",
            ),
        )
        server.enqueue(MockResponse(body = PROMPT_ACK_BODY))

        val result = repository.sendPrompt(settings(), "session_1", "hello")

        assertTrue(result.isSuccess)
        // The session choice wins over the server default — and the config is never
        // even fetched: the first request is the prompt itself.
        val prompt = server.takeRequest()
        assertEquals("/api/v1/sessions/session_1/prompts", prompt.target)
        assertEquals(
            """{"content":[{"type":"text","text":"hello"}],"model":"kimi-code/kimi-for-coding-highspeed"}""",
            prompt.body!!.utf8(),
        )
    }

    @Test
    fun `steer sends the session's chosen model in the prompt body`() = runBlocking {
        repository.setSessionModel("session_1", "kimi-code/kimi-for-coding-highspeed")
        server.enqueue(MockResponse(body = PROMPT_ACK_BODY))
        server.enqueue(
            MockResponse(body = 
                """{"code":0,"msg":"success","data":{"steered":true,"prompt_ids":["msg_1"]},"request_id":"01KX"}""",
            ),
        )

        val result = repository.steer(settings(), "session_1", "Stop counting")

        assertTrue(result.isSuccess)
        val prompt = server.takeRequest()
        assertEquals(
            """{"content":[{"type":"text","text":"Stop counting"}],"model":"kimi-code/kimi-for-coding-highspeed"}""",
            prompt.body!!.utf8(),
        )
        assertEquals("/api/v1/sessions/session_1/prompts:steer", server.takeRequest().target)
    }

    /** Enqueues the live 0.29.0 models list (docs/server-api.md) and primes the
     * repository's models cache — the effort is only sent once the cache confirms
     * the session's model supports it (ticket 032). */
    private suspend fun primeModelsCache() {
        server.enqueue(
            MockResponse(body = 
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
        assertEquals("/api/v1/models", server.takeRequest().target)
    }

    private fun enqueuePromptAck() = server.enqueue(MockResponse(body = PROMPT_ACK_BODY))

    @Test
    fun `sendPrompt sends the session's stored effort in the prompt body`() = runBlocking {
        repository.setSessionModel("session_1", "kimi-code/k3")
        repository.setSessionEffort("session_1", "low")
        primeModelsCache()
        enqueuePromptAck()

        val result = repository.sendPrompt(settings(), "session_1", "hello")

        assertTrue(result.isSuccess)
        val prompt = server.takeRequest()
        assertEquals("/api/v1/sessions/session_1/prompts", prompt.target)
        assertEquals(
            """{"content":[{"type":"text","text":"hello"}],"model":"kimi-code/k3","thinking":"low"}""",
            prompt.body!!.utf8(),
        )
    }

    @Test
    fun `steer sends the session's stored effort in the prompt body`() = runBlocking {
        repository.setSessionModel("session_1", "kimi-code/k3")
        repository.setSessionEffort("session_1", "max")
        primeModelsCache()
        enqueuePromptAck()
        server.enqueue(
            MockResponse(body = 
                """{"code":0,"msg":"success","data":{"steered":true,"prompt_ids":["msg_1"]},"request_id":"01KX"}""",
            ),
        )

        val result = repository.steer(settings(), "session_1", "Stop counting")

        assertTrue(result.isSuccess)
        val prompt = server.takeRequest()
        assertEquals(
            """{"content":[{"type":"text","text":"Stop counting"}],"model":"kimi-code/k3","thinking":"max"}""",
            prompt.body!!.utf8(),
        )
        assertEquals("/api/v1/sessions/session_1/prompts:steer", server.takeRequest().target)
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
            server.takeRequest().body!!.utf8(),
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
            server.takeRequest().body!!.utf8(),
        )
    }

    @Test
    fun `abortPrompt posts an empty body to the abort endpoint`() = runBlocking {
        server.enqueue(
            MockResponse(body = """{"code":0,"msg":"success","data":{"aborted":true},"request_id":"01KX"}"""),
        )

        val result = repository.abortPrompt(settings(), "session_1")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1:abort", request.target)
        assertEquals("{}", request.body!!.utf8())
    }

    @Test
    fun `abortPrompt maps non-zero envelope code to Api error`() = runBlocking {
        server.enqueue(
            MockResponse(body = """{"code":40001,"msg":"no running prompt","data":null,"request_id":"01KX"}"""),
        )

        val result = repository.abortPrompt(settings(), "session_1")

        assertTrue(result.exceptionOrNull() is DataError.Api)
    }

    @Test
    fun `getSessionStatus maps the busy flag and the context usage`() = runBlocking {
        server.enqueue(
            MockResponse(body = 
                """{"code":0,"msg":"success","data":{"busy":true,"context_tokens":25050,""" +
                    """"max_context_tokens":1048576,"context_usage":0.0239},"request_id":"01KX"}""",
            ),
        )

        assertEquals(
            SessionStatus(busy = true, contextUsage = ContextUsage(25050, 1048576)),
            repository.getSessionStatus(settings(), "session_1").getOrThrow(),
        )
        assertEquals("/api/v1/sessions/session_1/status", server.takeRequest().target)
    }

    @Test
    fun `getSessionStatus drops the context usage when max_context_tokens is omitted`() = runBlocking {
        // 0.34.0 (ticket 100): max_context_tokens is omittable; 0 is the engine's
        // unknown marker, never a real limit — no ContextUsage must reach the UI.
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"busy":true,"context_tokens":25050,""" +
                    """"context_usage":0.0239},"request_id":"01KX"}""",
            ),
        )

        assertEquals(
            SessionStatus(busy = true, contextUsage = null),
            repository.getSessionStatus(settings(), "session_1").getOrThrow(),
        )
    }

    @Test
    fun `getSessionStatus drops the context usage when max_context_tokens is zero`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"busy":false,"context_tokens":25050,""" +
                    """"max_context_tokens":0,"context_usage":0.0},"request_id":"01KX"}""",
            ),
        )

        assertEquals(
            SessionStatus(busy = false, contextUsage = null),
            repository.getSessionStatus(settings(), "session_1").getOrThrow(),
        )
    }

    @Test
    fun `agent status usage frame emits the context usage, phase frame emits nothing`() = runBlocking {
        // Verbatim 0.29.0 shapes (docs/server-api.md, Agent status). The settle wait
        // covers the phase frame: it must arrive and still produce no event.
        val emitted = runSocketEvents(
            """{"type":"agent.status.updated","seq":2,"epoch":"ep_1","volatile":true,""" +
                """"session_id":"session_1","timestamp":"2026-07-26T00:00:00.000Z",""" +
                """"payload":{"type":"agent.status.updated","model":"kimi-code/k3",""" +
                """"thinkingEffort":"high","maxContextTokens":1048576,"usage":{},""" +
                """"contextTokens":25050,"agentId":"main","sessionId":"session_1"}}""",
            """{"type":"agent.status.updated","seq":5,"epoch":"ep_1","volatile":true,""" +
                """"session_id":"session_1","timestamp":"2026-07-26T00:00:01.000Z",""" +
                """"payload":{"type":"agent.status.updated",""" +
                """"phase":{"kind":"running","turnId":0,"step":0,"stepId":"",""" +
                """"since":1784777223312},"agentId":"main","sessionId":"session_1"}}""",
            eventCount = 1,
            settleMillis = 200,
        )

        assertEquals(
            listOf(SocketEvent.Event(SessionEvent.ContextUsageUpdated(ContextUsage(25050, 1048576)))),
            emitted.filterIsInstance<SocketEvent.Event>(),
        )
    }

    @Test
    fun `socket subscribes and emits parsed events`() = runBlocking {
        val clientFrames = CopyOnWriteArrayList<String>()
        server.enqueue(
            webSocketUpgradeResponse(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"server_hello","payload":{"protocol_version":2,"heartbeat_ms":30000}}""")
                        webSocket.send(
                            """{"type":"assistant.delta","seq":5,"epoch":"ep_1","volatile":true,"offset":0,""" +
                                """"session_id":"session_1","timestamp":"2026-07-17T04:11:00.603Z",""" +
                                """"payload":{"type":"assistant.delta","turnId":1,"delta":"ok","agentId":"main","sessionId":"session_1"}}""",
                        )
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
            awaitCondition { emitted.any { it is SocketEvent.Event } }
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
        Unit
    }

    @Test
    fun `volatile frames do not advance the resume cursor`() = runBlocking {
        val clientFrames = CopyOnWriteArrayList<String>()
        server.enqueue(
            webSocketUpgradeResponse(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            """{"type":"turn.started","seq":5,"epoch":"ep_1","session_id":"session_1",""" +
                                """"timestamp":"2026-07-23T03:28:34.668Z","payload":{"type":"turn.started","turnId":0}}""",
                        )
                        // Volatile frames share the seq of the event they piggyback on
                        // (docs/server-api.md): this one must not move the resume cursor,
                        // or the replay after a drop could skip the substantive seq-6 event.
                        webSocket.send(
                            """{"type":"assistant.delta","seq":6,"epoch":"ep_1","volatile":true,"offset":0,""" +
                                """"session_id":"session_1","timestamp":"2026-07-23T03:28:34.768Z",""" +
                                """"payload":{"type":"assistant.delta","turnId":0,"delta":"ok"}}""",
                        )
                        webSocket.close(1000, "drop")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        clientFrames += text
                    }
                },
            ),
        )
        server.enqueue(
            webSocketUpgradeResponse(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        clientFrames += text
                    }
                },
            ),
        )

        val job = launch { repository.observeSession(settings(), "session_1").collect {} }
        try {
            // The reconnect goes through the 1 s first backoff step; only the resume
            // subscribe carries a cursor (the first one may race the server's close and
            // never reach the MockWebServer listener — it is not what is asserted).
            awaitCondition(10_000) { clientFrames.any { "\"cursors\"" in it } }
        } finally {
            job.cancel()
        }

        val resubscribe = clientFrames.first { "\"cursors\"" in it }
        assertTrue(resubscribe, resubscribe.contains("\"seq\":5"))
        assertTrue(resubscribe, resubscribe.contains("\"epoch\":\"ep_1\""))
        Unit
    }

    @Test
    fun `a fresh collection resumes with the stored cursor and emits Reconnected`() = runBlocking {
        // The chat screen's ON_STOP/ON_START gate cycle (ticket 048): cancelling one
        // observe() collection must not lose the resume cursor — the next collection
        // re-subscribes with it and the missed window replays.
        val clientFrames = CopyOnWriteArrayList<String>()
        server.enqueue(
            wsUpgrade(clientFrames) { ws ->
                ws.send(
                    """{"type":"turn.started","seq":5,"epoch":"ep_1","session_id":"session_1",""" +
                        """"timestamp":"2026-07-29T00:00:00.000Z","payload":{"type":"turn.started","turnId":0}}""",
                )
            },
        )
        server.enqueue(wsUpgrade(clientFrames))

        val first = CopyOnWriteArrayList<SocketEvent>()
        val job1 = launch { repository.observeSession(settings(), "session_1").collect { first += it } }
        awaitCondition { first.any { it is SocketEvent.Event } }
        job1.cancel()

        val second = CopyOnWriteArrayList<SocketEvent>()
        val job2 = launch { repository.observeSession(settings(), "session_1").collect { second += it } }
        try {
            awaitCondition { second.isNotEmpty() && clientFrames.any { "\"cursors\"" in it } }
        } finally {
            job2.cancel()
        }

        assertEquals(SocketEvent.Connected, first.first())
        // A subscribe carrying a stored cursor IS a resume, even as the collection's
        // first connection — the collector heals via the Reconnected path, and the
        // event carries the cursor the subscribe resumed with (ticket 085).
        assertEquals(SocketEvent.Reconnected(5, "ep_1"), second.first())
        val resubscribe = clientFrames.first { "\"cursors\"" in it }
        assertTrue(resubscribe, resubscribe.contains("\"seq\":5"))
        assertTrue(resubscribe, resubscribe.contains("\"epoch\":\"ep_1\""))
        Unit
    }

    @Test
    fun `a resync_required ack drops the stored cursor and emits ResyncRequired`() = runBlocking {
        val clientFrames = CopyOnWriteArrayList<String>()
        server.enqueue(
            wsUpgrade(clientFrames) { ws ->
                // A cursor-worthy frame first, then the ack refusing the resume
                // (docs/server-api.md, subscribe ack): no replay will come.
                ws.send(
                    """{"type":"turn.started","seq":5,"epoch":"ep_1","session_id":"session_1",""" +
                        """"timestamp":"2026-07-29T00:00:00.000Z","payload":{"type":"turn.started","turnId":0}}""",
                )
                ws.send(
                    """{"type":"ack","id":"c_1","code":0,"msg":"success","payload":{"accepted":[],""" +
                        """"not_found":[],"resync_required":["session_1"],"cursors":{}}}""",
                )
            },
        )
        server.enqueue(wsUpgrade(clientFrames))

        val first = CopyOnWriteArrayList<SocketEvent>()
        val job1 = launch { repository.observeSession(settings(), "session_1").collect { first += it } }
        try {
            awaitCondition { first.any { it is SocketEvent.ResyncRequired } }
        } finally {
            job1.cancel()
        }

        assertEquals(
            listOf(
                SocketEvent.Connected,
                SocketEvent.Event(SessionEvent.TurnStarted()),
                SocketEvent.ResyncRequired,
            ),
            first,
        )

        // The refused cursor is gone: the next collection starts blind again.
        val second = CopyOnWriteArrayList<SocketEvent>()
        val job2 = launch { repository.observeSession(settings(), "session_1").collect { second += it } }
        try {
            awaitCondition { second.isNotEmpty() && clientFrames.count { "\"subscribe\"" in it } >= 2 }
        } finally {
            job2.cancel()
        }

        assertEquals(SocketEvent.Connected, second.first())
        assertFalse(clientFrames.last { "\"subscribe\"" in it }.contains("\"cursors\""))
    }

    @Test
    fun `tool call started frame emits the command preview`() = runBlocking {
        val emitted = runSocketEvents(
            // Verbatim tool.call.started shape (docs/server-api.md: args and display on
            // start, unchanged from 0.23.3).
            """{"type":"tool.call.started","seq":9,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-19T15:07:43.000Z","payload":{"type":"tool.call.started",""" +
                """"turnId":0,"toolCallId":"tool_3oZjtQUGzDwUwQwfkXioAtne","name":"Bash",""" +
                """"args":{"command":"echo hello-from-spike"},""" +
                """"description":"Running: echo hello-from-spike",""" +
                """"display":{"kind":"command","command":"echo hello-from-spike",""" +
                """"cwd":"/tmp/spike","language":"bash"},"agentId":"main","sessionId":"session_1"}}""",
            // No display object: the preview is derived from args instead.
            """{"type":"tool.call.started","seq":10,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-19T15:07:44.000Z","payload":{"type":"tool.call.started",""" +
                """"turnId":0,"toolCallId":"tool_2","name":"Read",""" +
                """"args":{"path":"/tmp/spike/a.txt"},"agentId":"main","sessionId":"session_1"}}""",
        )

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
        val emitted = runSocketEvents(
            // tool.result shape (docs/server-api.md): toolCallId + output; the output is
            // a plain string for shell tools (ticket 023).
            """{"type":"tool.result","seq":14,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-19T15:07:50.000Z","payload":{"type":"tool.result",""" +
                """"turnId":0,"toolCallId":"tool_1","output":"ok\n","agentId":"main","sessionId":"session_1"}}""",
            // Structured tools (AskUserQuestion) carry a JSON object output.
            """{"type":"tool.result","seq":15,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-19T15:07:51.000Z","payload":{"type":"tool.result",""" +
                """"turnId":0,"toolCallId":"tool_2","is_error":true,""" +
                """"output":{"answers":{"Color?":"blue"}},"agentId":"main","sessionId":"session_1"}}""",
        )

        val events = emitted.filterIsInstance<SocketEvent.Event>().map { it.event }
        assertEquals(SessionEvent.ToolCallResult("tool_1", false, "ok\n"), events[0])
        assertEquals(
            SessionEvent.ToolCallResult("tool_2", true, "{\n    \"answers\": {\n        \"Color?\": \"blue\"\n    }\n}"),
            events[1],
        )
    }

    @Test
    fun `tool progress frame emits the output chunk`() = runBlocking {
        // Verbatim tool.progress shape (docs/server-api.md, 0.29.0): a volatile frame
        // whose update carries one live output chunk.
        val emitted = runSocketEvents(
            """{"type":"tool.progress","seq":14,"epoch":"ep_1","volatile":true,""" +
                """"session_id":"session_1","timestamp":"2026-07-23T03:28:40.000Z",""" +
                """"payload":{"type":"tool.progress","turnId":0,""" +
                """"toolCallId":"tool_RMgBIy1asIorg1eV8P3ZhHiL",""" +
                """"update":{"kind":"stdout","text":"doc-verify-0290\n"},""" +
                """"agentId":"main","sessionId":"session_1"}}""",
        )

        val events = emitted.filterIsInstance<SocketEvent.Event>().map { it.event }
        assertEquals(
            SessionEvent.ToolCallProgress("tool_RMgBIy1asIorg1eV8P3ZhHiL", "doc-verify-0290\n"),
            events.first(),
        )
    }

    @Test
    fun `context spliced frame emits the spliced user message`() = runBlocking {
        // Verbatim 0.27.0 shape (docs/server-api.md, "Core event payloads").
        val emitted = runSocketEvents(
            """{"type":"context.spliced","seq":5,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-19T00:00:00.500Z","payload":{"type":"context.spliced",""" +
                """"start":0,"deleteCount":0,"messages":[{"role":"user",""" +
                """"content":[{"type":"text","text":"hello from the phone"}],"toolCalls":[],""" +
                """"origin":{"kind":"user"},"id":"msg_1"}],"agentId":"main","sessionId":"session_1"}}""",
        )

        assertEquals(
            SocketEvent.Event(
                SessionEvent.ContextSpliced(
                    listOf(
                        ChatMessage(
                            "msg_1",
                            ChatMessage.Role.User,
                            listOf(ChatMessage.Part.Text("hello from the phone")),
                            originKind = "user",
                        ),
                    ),
                ),
            ),
            emitted.first { it is SocketEvent.Event },
        )
    }

    @Test
    fun `context spliced frame without a message id emits the steered user message`() = runBlocking {
        // Steer echo shape (docs/server-api.md, steer round-trip): the spliced steered
        // message carries no id.
        val emitted = runSocketEvents(
            """{"type":"context.spliced","seq":12,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-20T00:00:01.000Z","payload":{"type":"context.spliced",""" +
                """"start":1,"deleteCount":0,"messages":[{"role":"user",""" +
                """"content":[{"type":"text","text":"Stop counting. Reply with exactly: steered-ok"}],""" +
                """"toolCalls":[],"origin":{"kind":"user"}}],"agentId":"main","sessionId":"session_1"}}""",
        )

        assertEquals(
            SocketEvent.Event(
                SessionEvent.ContextSpliced(
                    listOf(
                        ChatMessage(
                            "",
                            ChatMessage.Role.User,
                            listOf(ChatMessage.Part.Text("Stop counting. Reply with exactly: steered-ok")),
                            originKind = "user",
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
            MockResponse(body = 
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

        val timeline = testChatTimeline()
        timeline.replaceHistory(repository.getMessages(settings(), "session_1").getOrThrow().items, keepLive = false)

        // The envelope-only messages produce no row; the mixed one keeps its genuine text.
        assertEquals(listOf(ChatItem.UserMessage("msg_3", "deploy the app")), timeline.items())
    }

    @Test
    fun `context spliced filters internal envelopes from the echoed user message`() = runBlocking {
        val emitted = runSocketEvents(
            """{"type":"context.spliced","seq":5,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-21T00:00:00.000Z","payload":{"type":"context.spliced",""" +
                """"start":0,"deleteCount":0,"messages":[{"role":"user",""" +
                """"content":[{"type":"text","text":"<system-reminder>\ninternal\n</system-reminder>"}],""" +
                """"toolCalls":[],"origin":{"kind":"injection"},"id":"msg_9"}],""" +
                """"agentId":"main","sessionId":"session_1"}}""",
        )

        val timeline = testChatTimeline()
        timeline.apply((emitted.first { it is SocketEvent.Event } as SocketEvent.Event).event)

        assertEquals(emptyList<ChatItem>(), timeline.items())
    }

    @Test
    fun `question frames emit the requested question then the resolved id`() = runBlocking {
        // Verbatim 0.27.0 shapes (docs/server-api.md, "Core event payloads").
        val emitted = runSocketEvents(
            """{"type":"event.question.requested","seq":9,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-20T06:06:49.808Z","payload":{"type":"event.question.requested",""" +
                """"question_id":"tool_mU6ITagcpKjwSyiUKLHPZYWw","session_id":"session_1","turn_id":0,""" +
                """"tool_call_id":"tool_mU6ITagcpKjwSyiUKLHPZYWw",""" +
                """"questions":[{"id":"q_0","question":"Red or blue?","header":"Color",""" +
                """"options":[{"id":"opt_0_0","label":"red","description":"warm color"},""" +
                """{"id":"opt_0_1","label":"blue","description":"cool color"}],"allow_other":true}],""" +
                """"created_at":"2026-07-20T06:06:49.808Z","agentId":"main","sessionId":"session_1"}}""",
            """{"type":"event.question.answered","seq":11,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-20T06:08:12.548Z","payload":{"type":"event.question.answered",""" +
                """"question_id":"tool_mU6ITagcpKjwSyiUKLHPZYWw","answers":{"Red or blue?":"blue"},""" +
                """"resolved_at":"2026-07-20T06:08:12.548Z","agentId":"main","sessionId":"session_1"}}""",
            """{"type":"event.question.dismissed","seq":28,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-20T06:12:37.844Z","payload":{"type":"event.question.dismissed",""" +
                """"question_id":"tool_TThzzORvQB8tHTKyLhIL5bQt","dismissed_at":"2026-07-20T06:12:37.844Z",""" +
                """"agentId":"main","sessionId":"session_1"}}""",
        )

        val events = emitted.filterIsInstance<SocketEvent.Event>().map { it.event }
        assertEquals(
            SessionEvent.QuestionRequested(
                PendingQuestion(
                    questionId = "tool_mU6ITagcpKjwSyiUKLHPZYWw",
                    toolCallId = "tool_mU6ITagcpKjwSyiUKLHPZYWw",
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
    fun `question requested decodes the 0_38_0 split question and tool call ids`() = runBlocking {
        // Verbatim 0.38.0 capture (docs/server-api.md, "Core event payloads" →
        // Question requested): question_id is a dedicated question_<uuid> id,
        // distinct from the tool row's tool_call_id (ticket 106).
        val emitted = runSocketEvents(
            """{"type":"event.question.requested","seq":10,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-08-20T19:33:45.150Z","payload":{"type":"event.question.requested",""" +
                """"question_id":"question_a38aaf28-7dfe-489d-bca3-ea43c93b86fa",""" +
                """"session_id":"session_1","turn_id":0,""" +
                """"tool_call_id":"tool_EwFXscDF0HraGfTelCTRdRe9",""" +
                """"questions":[{"id":"q_0","question":"Pick a color?",""" +
                """"options":[{"id":"opt_0_0","label":"Red"},{"id":"opt_0_1","label":"Blue"}],""" +
                """"allow_other":true}],""" +
                """"created_at":"2026-08-20T19:33:45.150Z","agentId":"main","sessionId":"session_1"}}""",
        )

        val events = emitted.filterIsInstance<SocketEvent.Event>().map { it.event }
        assertEquals(
            SessionEvent.QuestionRequested(
                PendingQuestion(
                    questionId = "question_a38aaf28-7dfe-489d-bca3-ea43c93b86fa",
                    toolCallId = "tool_EwFXscDF0HraGfTelCTRdRe9",
                    items = listOf(
                        PendingQuestion.Item(
                            id = "q_0",
                            header = "",
                            text = "Pick a color?",
                            options = listOf(
                                PendingQuestion.Option("opt_0_0", "Red", null),
                                PendingQuestion.Option("opt_0_1", "Blue", null),
                            ),
                            allowOther = true,
                        ),
                    ),
                ),
            ),
            events.single(),
        )
    }

    @Test
    fun `getPendingQuestions parses the poll list`() = runBlocking {
        // The poll items carry the 0.38.0 dual-id shape (docs/server-api.md,
        // Questions — ticket 106).
        server.enqueue(
            MockResponse(body = 
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"type":"event.question.requested","question_id":"question_q1","session_id":"session_1",
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

        assertEquals("/api/v1/sessions/session_1/questions?status=pending", server.takeRequest().target)
        assertEquals(
            listOf(
                PendingQuestion(
                    questionId = "question_q1",
                    toolCallId = "tool_q1",
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
            MockResponse(body = 
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
        assertEquals("/api/v1/sessions/session_1/questions/tool_q1", request.target)
        assertEquals(
            """{"answers":{"q_0":{"kind":"single","option_id":"opt_0_1"},"q_1":{"kind":"other","text":"ramen"}}}""",
            request.body!!.utf8(),
        )
    }

    @Test
    fun `answerQuestion serializes the multi-select answer kinds`() = runBlocking {
        // Ticket 090: verified live against server 0.31.1 (docs/server-api.md).
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"resolved":true,"resolved_at":"2026-07-20T06:08:12.548Z"},"request_id":"01KX"}""",
            ),
        )

        val result = repository.answerQuestion(
            settings(),
            "session_1",
            "tool_q1",
            listOf(
                QuestionAnswer.Multi("q_0", listOf("opt_0_0", "opt_0_2")),
                QuestionAnswer.MultiWithOther("q_1", listOf("opt_1_0"), "Fish"),
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals(
            """{"answers":{"q_0":{"kind":"multi","option_ids":["opt_0_0","opt_0_2"]},""" +
                """"q_1":{"kind":"multi_with_other","option_ids":["opt_1_0"],"other_text":"Fish"}}}""",
            server.takeRequest().body!!.utf8(),
        )
    }

    @Test
    fun `pending questions poll parses multi_select and defaults it to false`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"type":"event.question.requested","question_id":"tool_q1","session_id":"session_1",
                   "turn_id":0,"tool_call_id":"tool_q1",
                   "questions":[
                     {"id":"q_0","question":"Colors?","header":"Palette",
                      "options":[{"id":"opt_0_0","label":"red"}],
                      "allow_other":true,"multi_select":true},
                     {"id":"q_1","question":"Mode?","header":"Mode",
                      "options":[{"id":"opt_1_0","label":"fast"}],
                      "allow_other":false,"multi_select":false},
                     {"id":"q_2","question":"Confirm?","header":"Confirm",
                      "options":[{"id":"opt_2_0","label":"yes"}],
                      "allow_other":false}
                   ],
                   "created_at":"2026-07-20T06:06:49.808Z","agentId":"main","sessionId":"session_1"}
                ]},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val result = repository.getPendingQuestions(settings(), "session_1")

        assertEquals(
            listOf(true, false, false),
            result.getOrThrow().single().items.map { it.multiSelect },
        )
    }

    @Test
    fun `answerQuestion maps an already-resolved question to Api error`() = runBlocking {
        server.enqueue(
            MockResponse(body = """{"code":40405,"msg":"question tool_q1 not found","data":null,"request_id":"01KX"}"""),
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
            MockResponse(body = 
                """{"code":40909,"msg":"question tool_q1 dismissed",""" +
                    """"data":{"dismissed":true,"dismissed_at":"2026-07-20T06:17:24.711Z"},"request_id":"01KX"}""",
            ),
        )

        val result = repository.dismissQuestion(settings(), "session_1", "tool_q1")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/questions/tool_q1:dismiss", request.target)
        assertEquals("{}", request.body!!.utf8())
    }

    @Test
    fun `dismissQuestion maps an already-resolved question to Api error`() = runBlocking {
        server.enqueue(
            MockResponse(body = """{"code":40405,"msg":"question tool_q1 not found","data":null,"request_id":"01KX"}"""),
        )

        val result = repository.dismissQuestion(settings(), "session_1", "tool_q1")

        assertTrue(result.exceptionOrNull() is DataError.Api)
    }

    @Test
    fun `uploadFile posts one multipart file and maps the response to FileMeta`() = runBlocking {
        // Live 0.29.0 response shape (docs/server-api.md, Files — ticket 033).
        server.enqueue(
            MockResponse(body = 
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
        assertEquals("/api/v1/files", request.target)
        assertTrue(request.headers["Content-Type"]?.startsWith("multipart/form-data") == true)
        val body = request.body!!.utf8()
        // One `file` part with the bytes and one `name` field, per the documented curl.
        assertTrue(body.contains("""name="file"; filename="attach-check.txt""""))
        assertTrue(body.contains("doc-verify-033"))
        assertTrue(body.contains("""name="name""""))
    }

    @Test
    fun `uploadFile maps an HTTP error to an Http error`() = runBlocking {
        server.enqueue(MockResponse(code = 500))

        val result = repository.uploadFile(settings(), "big.bin", "application/octet-stream", ByteArray(4))

        assertTrue((result.exceptionOrNull() as? DataError.Http)?.code == 500)
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
        assertEquals("/api/v1/config", server.takeRequest().target)
        assertEquals(
            """{"content":[""" +
                """{"type":"image","source":{"kind":"file","file_id":"f_img"}},""" +
                """{"type":"file","file_id":"f_txt","name":"notes.txt","media_type":"text/plain","size":27},""" +
                """{"type":"text","text":"look at these"}]}""",
            server.takeRequest().body!!.utf8(),
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
        assertEquals("/api/v1/config", server.takeRequest().target)
        assertEquals(
            """{"content":[{"type":"file","file_id":"f_txt","name":"notes.txt","media_type":"text/plain","size":27}]}""",
            server.takeRequest().body!!.utf8(),
        )
    }

    @Test
    fun `text-only sendPrompt body is unchanged by the attachments support`() = runBlocking {
        enqueueConfigWithoutDefaultModel()
        enqueuePromptAck()

        val result = repository.sendPrompt(settings(), "session_1", "hello")

        assertTrue(result.isSuccess)
        assertEquals("/api/v1/config", server.takeRequest().target)
        assertEquals(
            """{"content":[{"type":"text","text":"hello"}]}""",
            server.takeRequest().body!!.utf8(),
        )
    }

    @Test
    fun `sub-agent transcript frames emit events tagged with their agentId`() = runBlocking {
        // Sub-agents stream over the same session channel tagged with their own agentId
        // (docs/server-api.md, Sub-agents; fixtures from the 2026-07-28 0.29.2 capture).
        // Since ticket 045 every frame emits its event carrying the emitting agentId —
        // routing, not dropping, is what keeps them out of the main chat. The two main
        // frames (one with the tag, one without — absent means main) read as "main".
        val emitted = runSocketEvents(
            """{"type":"turn.started","seq":20,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-28T03:37:35.493Z","payload":{"type":"turn.started","turnId":0,""" +
                """"origin":{"kind":"system_trigger","name":"subagent"},"agentId":"agent-12","sessionId":"session_1"}}""",
            """{"type":"context.spliced","seq":21,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-28T03:37:35.494Z","payload":{"type":"context.spliced","start":0,""" +
                """"deleteCount":0,"messages":[{"role":"user","content":[{"type":"text","text":"sub task"}],""" +
                """"toolCalls":[],"origin":{"kind":"system_trigger","name":"subagent"},"id":"msg_sub"}],""" +
                """"agentId":"agent-12","sessionId":"session_1"}}""",
            """{"type":"thinking.delta","seq":23,"epoch":"ep_1","volatile":true,"offset":0,""" +
                """"session_id":"session_1","timestamp":"2026-07-28T03:37:36.000Z",""" +
                """"payload":{"type":"thinking.delta","turnId":0,"delta":"sub hmm","agentId":"agent-12","sessionId":"session_1"}}""",
            """{"type":"assistant.delta","seq":23,"epoch":"ep_1","volatile":true,"offset":0,""" +
                """"session_id":"session_1","timestamp":"2026-07-28T03:37:36.100Z",""" +
                """"payload":{"type":"assistant.delta","turnId":0,"delta":"sub text","agentId":"agent-12","sessionId":"session_1"}}""",
            """{"type":"tool.call.delta","seq":23,"epoch":"ep_1","volatile":true,""" +
                """"session_id":"session_1","timestamp":"2026-07-28T03:37:36.200Z",""" +
                """"payload":{"type":"tool.call.delta","turnId":0,"toolCallId":"tool_sub","name":"Bash",""" +
                """"agentId":"agent-12","sessionId":"session_1"}}""",
            """{"type":"tool.call.started","seq":24,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-28T03:37:36.300Z","payload":{"type":"tool.call.started","turnId":0,""" +
                """"toolCallId":"tool_sub","name":"Bash","args":{"command":"ls"},"agentId":"agent-12","sessionId":"session_1"}}""",
            """{"type":"tool.progress","seq":24,"epoch":"ep_1","volatile":true,""" +
                """"session_id":"session_1","timestamp":"2026-07-28T03:37:36.400Z",""" +
                """"payload":{"type":"tool.progress","toolCallId":"tool_sub",""" +
                """"update":{"kind":"stdout","text":"sub out"},"agentId":"agent-12","sessionId":"session_1"}}""",
            """{"type":"tool.result","seq":25,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-28T03:37:36.500Z","payload":{"type":"tool.result","turnId":0,""" +
                """"toolCallId":"tool_sub","output":"sub result","agentId":"agent-12","sessionId":"session_1"}}""",
            """{"type":"agent.status.updated","seq":25,"epoch":"ep_1","volatile":true,""" +
                """"session_id":"session_1","timestamp":"2026-07-28T03:37:36.600Z",""" +
                """"payload":{"type":"agent.status.updated","contextTokens":13967,"maxContextTokens":1048576,""" +
                """"agentId":"agent-12","sessionId":"session_1"}}""",
            """{"type":"turn.ended","seq":26,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-28T03:37:38.980Z","payload":{"type":"turn.ended","turnId":0,""" +
                """"reason":"completed","durationMs":3487,"agentId":"agent-12","sessionId":"session_1"}}""",
            """{"type":"assistant.delta","seq":27,"epoch":"ep_1","volatile":true,"offset":0,""" +
                """"session_id":"session_1","timestamp":"2026-07-28T03:37:39.000Z",""" +
                """"payload":{"type":"assistant.delta","turnId":1,"delta":"main text","agentId":"main","sessionId":"session_1"}}""",
            """{"type":"assistant.delta","seq":27,"epoch":"ep_1","volatile":true,"offset":9,""" +
                """"session_id":"session_1","timestamp":"2026-07-28T03:37:39.100Z",""" +
                """"payload":{"type":"assistant.delta","turnId":1,"delta":" more"}}""",
            eventCount = 12,
        )

        assertEquals(
            listOf(
                SocketEvent.Event(SessionEvent.TurnStarted("agent-12")),
                SocketEvent.Event(
                    SessionEvent.ContextSpliced(
                        listOf(
                            ChatMessage(
                                "msg_sub",
                                ChatMessage.Role.User,
                                listOf(ChatMessage.Part.Text("sub task")),
                                originKind = "system_trigger",
                            ),
                        ),
                        "agent-12",
                    ),
                ),
                SocketEvent.Event(SessionEvent.ThinkingDelta("sub hmm", "agent-12")),
                SocketEvent.Event(SessionEvent.AssistantDelta("sub text", "agent-12")),
                SocketEvent.Event(SessionEvent.ToolCallDelta("tool_sub", "Bash", "agent-12")),
                SocketEvent.Event(
                    SessionEvent.ToolCallStarted(
                        "tool_sub", "Bash", "ls", input = "{\n    \"command\": \"ls\"\n}", agentId = "agent-12",
                    ),
                ),
                SocketEvent.Event(SessionEvent.ToolCallProgress("tool_sub", "sub out", "agent-12")),
                SocketEvent.Event(SessionEvent.ToolCallResult("tool_sub", false, "sub result", "agent-12")),
                SocketEvent.Event(SessionEvent.ContextUsageUpdated(ContextUsage(13967, 1048576), "agent-12")),
                SocketEvent.Event(SessionEvent.TurnEnded("completed", "agent-12")),
                SocketEvent.Event(SessionEvent.AssistantDelta("main text")),
                SocketEvent.Event(SessionEvent.AssistantDelta(" more")),
            ),
            emitted.filterIsInstance<SocketEvent.Event>(),
        )
    }

    @Test
    fun `subagent lifecycle frames decode to their events`() = runBlocking {
        // Verbatim subagent.spawned/completed payloads from the 2026-07-28 0.29.2
        // capture (docs/server-api.md, Sub-agents); started/failed/suspended share the
        // payload minus/plus their own field. All are tagged agentId "main" yet scoped
        // to the sub-agent by the payload's subagentId.
        val emitted = runSocketEvents(
            """{"type":"subagent.spawned","seq":19,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-28T03:37:35.492Z","payload":{"type":"subagent.spawned",""" +
                """"subagentId":"agent-12","subagentName":"explore",""" +
                """"parentToolCallId":"tool_mhF8UXcf5QwvZbh5223tiIng","parentAgentId":"main",""" +
                """"callerAgentId":"main","description":"doc-verify-subagent","runInBackground":false,""" +
                """"agentId":"main","sessionId":"session_1"}}""",
            """{"type":"subagent.started","seq":22,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-28T03:37:35.600Z","payload":{"type":"subagent.started",""" +
                """"subagentId":"agent-12","agentId":"main","sessionId":"session_1"}}""",
            """{"type":"subagent.completed","seq":33,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-28T03:37:51.951Z","payload":{"type":"subagent.completed",""" +
                """"subagentId":"agent-12","resultSummary":"the sub-agent's result summary",""" +
                """"usage":{"inputOther":6462,"output":359,"inputCacheRead":21504,"inputCacheCreation":0},""" +
                """"contextTokens":14358,"agentId":"main","sessionId":"session_1"}}""",
            """{"type":"subagent.failed","seq":34,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-28T03:37:52.000Z","payload":{"type":"subagent.failed",""" +
                """"subagentId":"agent-13","error":"boom","agentId":"main","sessionId":"session_1"}}""",
            """{"type":"subagent.suspended","seq":35,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-28T03:37:53.000Z","payload":{"type":"subagent.suspended",""" +
                """"subagentId":"agent-13","reason":"waiting_on_input","agentId":"main","sessionId":"session_1"}}""",
        )

        assertEquals(
            listOf(
                SocketEvent.Event(
                    SessionEvent.SubagentSpawned(
                        subagentId = "agent-12",
                        name = "explore",
                        parentToolCallId = "tool_mhF8UXcf5QwvZbh5223tiIng",
                        description = "doc-verify-subagent",
                        runInBackground = false,
                    ),
                ),
                SocketEvent.Event(SessionEvent.SubagentStarted("agent-12")),
                SocketEvent.Event(SessionEvent.SubagentCompleted("agent-12", "the sub-agent's result summary")),
                SocketEvent.Event(SessionEvent.SubagentFailed("agent-13", "boom")),
                SocketEvent.Event(SessionEvent.SubagentSuspended("agent-13")),
            ),
            emitted.filterIsInstance<SocketEvent.Event>(),
        )
    }

    @Test
    fun `sub-agent approval and question frames still emit`() = runBlocking {
        // Interaction events are actionable whoever raised them (todo/044): a filtered
        // sub-agent approval would hang the session on an answer nobody can give.
        val emitted = runSocketEvents(
            """{"type":"event.approval.requested","seq":30,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-28T03:37:40.000Z","payload":{"type":"event.approval.requested",""" +
                """"session_id":"session_1","approval_id":"tool_sub_bash","turn_id":0,""" +
                """"tool_call_id":"tool_sub_bash","tool_name":"Bash","action":"Running: ls",""" +
                """"tool_input_display":{"kind":"command","command":"ls","cwd":"/tmp/spike","language":"bash"},""" +
                """"created_at":"2026-07-28T03:37:40.000Z","expires_at":"2026-07-29T03:37:40.000Z",""" +
                """"agentId":"agent-12","sessionId":"session_1"}}""",
            """{"type":"event.question.requested","seq":31,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-28T03:37:41.000Z","payload":{"type":"event.question.requested",""" +
                """"question_id":"tool_sub_q","session_id":"session_1","turn_id":0,"tool_call_id":"tool_sub_q",""" +
                """"questions":[{"id":"q_0","question":"Red or blue?","options":[],"allow_other":true}],""" +
                """"created_at":"2026-07-28T03:37:41.000Z","agentId":"agent-12","sessionId":"session_1"}}""",
        )

        val events = emitted.filterIsInstance<SocketEvent.Event>().map { it.event }
        assertEquals(
            SessionEvent.ApprovalRequested("tool_sub_bash", "Bash", "ls"),
            events[0],
        )
        assertEquals(
            SessionEvent.QuestionRequested(
                PendingQuestion(
                    questionId = "tool_sub_q",
                    toolCallId = "tool_sub_q",
                    items = listOf(
                        PendingQuestion.Item(id = "q_0", header = "", text = "Red or blue?", options = emptyList(), allowOther = true),
                    ),
                ),
            ),
            events[1],
        )
    }

    @Test
    fun `transcript sends agent_id and page_size and parses turns, frames and has_more`() = runBlocking {
        server.enqueue(MockResponse(body = TRANSCRIPT_FINISHED_BODY))

        val page = repository.getTranscript(settings(), "session_1", "agent-15", beforeTurn = null, pageSize = 20)
            .getOrThrow()

        val request = server.takeRequest()
        assertEquals("/api/v1/sessions/session_1/transcript?agent_id=agent-15&page_size=20", request.target)

        assertEquals("agent-15", page.agentId)
        assertEquals(false, page.hasMore)
        assertEquals(2, page.turns.size)
        val t0 = page.turns[0]
        assertEquals(TranscriptTurn("t0", 0, TranscriptState.Completed, null, t0.steps), t0)
        assertEquals(2, t0.steps.size)
        assertEquals(
            listOf(
                TranscriptFrame.Thinking("t0.1.f1", "Plan the listing."),
                TranscriptFrame.Tool(
                    id = "t0.1.tool_MxYFRM8czboqPL4FXxWD4rC1",
                    toolCallId = "tool_MxYFRM8czboqPL4FXxWD4rC1",
                    name = "Bash",
                    running = false,
                    preview = "ls -la /tmp/spike",
                    input = "{\n    \"command\": \"ls -la /tmp/spike\"\n}",
                    output = "total 0\ndrwxr-xr-x 2 ubuntu ubuntu 40 Jul 27 19:25 .\n",
                ),
            ),
            t0.steps[0].frames,
        )
        assertEquals(
            listOf(
                TranscriptFrame.Thinking("t0.2.f1", "Empty dir."),
                TranscriptFrame.Text("t0.2.f2", TranscriptFrame.Text.Role.Assistant, "`/tmp/spike` is empty — **0 files**."),
            ),
            t0.steps[1].frames,
        )
    }

    @Test
    fun `transcript sends the before_turn cursor when given`() = runBlocking {
        server.enqueue(MockResponse(body = TRANSCRIPT_FINISHED_BODY))

        repository.getTranscript(settings(), "session_1", "agent-15", beforeTurn = "t1", pageSize = 20).getOrThrow()

        assertEquals(
            "/api/v1/sessions/session_1/transcript?agent_id=agent-15&page_size=20&before_turn=t1",
            server.takeRequest().target,
        )
    }

    @Test
    fun `transcript tolerates markers and maps the running state and a user prompt`() = runBlocking {
        server.enqueue(MockResponse(body = TRANSCRIPT_RUNNING_BODY))
        server.enqueue(MockResponse(body = TRANSCRIPT_USER_TURN_BODY))

        // Running sub-agent (warm path): an undo marker rides along, the tool frame
        // is still running with only its progress tail as output.
        val running = repository.getTranscript(settings(), "session_1", "agent-23", beforeTurn = null, pageSize = 20)
            .getOrThrow()
        assertEquals(1, running.turns.size) // the marker item is dropped
        val turn = running.turns[0]
        assertEquals(TranscriptState.Running, turn.state)
        assertEquals(
            TranscriptFrame.Tool(
                id = "t0.1.tool_4eOtxCMTEBHdpOehIhgnYAAe",
                toolCallId = "tool_4eOtxCMTEBHdpOehIhgnYAAe",
                name = "Bash",
                running = true,
                preview = "sleep 45 && echo awake",
                input = "{\n    \"command\": \"sleep 45 && echo awake\",\n    \"timeout\": 60\n}",
                output = "",
            ),
            turn.steps[0].frames[1],
        )

        // A user-origin main-agent turn carries its prompt (docs/server-api.md, Transcript).
        val user = repository.getTranscript(settings(), "session_1", "main", beforeTurn = null, pageSize = 20)
            .getOrThrow()
        assertEquals("Do it again with normal Agent calls.", user.turns[0].prompt)
        assertEquals(true, user.hasMore)
    }

    @Test
    fun `compact posts an empty body to the compact endpoint`() = runBlocking {
        server.enqueue(
            MockResponse(body = """{"code":0,"msg":"success","data":{},"request_id":"01KX"}"""),
        )

        val result = repository.compact(settings(), "session_1")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1:compact", request.target)
        assertEquals("{}", request.body!!.utf8())
    }

    @Test
    fun `compact maps the 40910 envelope to an Api error carrying the server message`() = runBlocking {
        // docs/server-api.md, Compact errors: the snackbar shows the server's sentence.
        server.enqueue(
            MockResponse(body = 
                """{"code":40910,"msg":"No messages to compact in current history.","data":null,"request_id":"01KX"}""",
            ),
        )

        val result = repository.compact(settings(), "session_1")

        assertEquals(
            DataError.Api(40910, "No messages to compact in current history."),
            result.exceptionOrNull(),
        )
    }

    @Test
    fun `history maps metadata origin kind onto the message`() = runBlocking {
        // The persisted compaction summary (docs/server-api.md, Compact): a user-role
        // message with metadata.origin.kind "compaction_summary" and the bare summary.
        server.enqueue(
            MockResponse(body = 
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"id":"msg_2","session_id":"session_1","role":"user",
                   "content":[{"type":"text","text":"Working summary of the task so far."}],
                   "created_at":"2026-07-30T05:44:56.660Z","metadata":{"origin":{"kind":"compaction_summary"}}},
                  {"id":"msg_1","session_id":"session_1","role":"user",
                   "content":[{"type":"text","text":"run echo hi"}],
                   "created_at":"2026-07-17T04:12:10.000Z","metadata":{"origin":{"kind":"user"}}}
                ],"has_more":false},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val messages = repository.getMessages(settings(), "session_1").getOrThrow().items

        assertEquals(
            ChatMessage(
                "msg_1",
                ChatMessage.Role.User,
                listOf(ChatMessage.Part.Text("run echo hi")),
                createdAt = Instant.parse("2026-07-17T04:12:10.000Z"),
                originKind = "user",
            ),
            messages[0],
        )
        assertEquals(
            ChatMessage(
                "msg_2",
                ChatMessage.Role.User,
                listOf(ChatMessage.Part.Text("Working summary of the task so far.")),
                createdAt = Instant.parse("2026-07-30T05:44:56.660Z"),
                originKind = ORIGIN_KIND_COMPACTION_SUMMARY,
            ),
            messages[1],
        )
    }

    @Test
    fun `socket parses the compaction lifecycle frames`() = runBlocking {
        // Verbatim 0.30.0 shapes (docs/server-api.md, Compact), started with an
        // instruction, then completed with its result.
        val emitted = runSocketEvents(
            """{"type":"compaction.started","seq":25,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-30T05:48:54.194Z","payload":{"type":"compaction.started",""" +
                """"trigger":"manual","instruction":"Keep the marker verbatim.",""" +
                """"agentId":"main","sessionId":"session_1"}}""",
            """{"type":"compaction.completed","seq":37,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-30T05:44:56.676Z","payload":{"type":"compaction.completed",""" +
                """"result":{"summary":"Completed spike, nothing pending.",""" +
                """"compactedCount":8,"tokensBefore":850,"tokensAfter":386,"keptUserMessageCount":3},""" +
                """"agentId":"main","sessionId":"session_1"}}""",
        )

        assertEquals(
            listOf(
                SocketEvent.Event(SessionEvent.CompactionStarted("manual", "Keep the marker verbatim.")),
                SocketEvent.Event(
                    SessionEvent.CompactionCompleted(
                        summary = "Completed spike, nothing pending.",
                        compactedCount = 8,
                        tokensBefore = 850,
                        tokensAfter = 386,
                        keptUserMessageCount = 3,
                    ),
                ),
            ),
            emitted.filterIsInstance<SocketEvent.Event>(),
        )
    }

    @Test
    fun `socket decodes the compaction failure family permissively, payload or not`() = runBlocking {
        // failed/cancelled/blocked were never observed on this server (protocol-bundle
        // schemas only): any shape — including no payload at all — must still emit, so
        // the compacting state always ends (ticket 056).
        val emitted = runSocketEvents(
            """{"type":"compaction.failed","seq":40,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-30T05:50:00.000Z","payload":{"type":"compaction.failed",""" +
                """"message":"summarizer unreachable","agentId":"main","sessionId":"session_1"}}""",
            """{"type":"compaction.cancelled","seq":41,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-30T05:50:01.000Z"}""",
        )

        assertEquals(
            listOf(
                SocketEvent.Event(SessionEvent.CompactionFailed("compaction.failed", "summarizer unreachable")),
                SocketEvent.Event(SessionEvent.CompactionFailed("compaction.cancelled", null)),
            ),
            emitted.filterIsInstance<SocketEvent.Event>(),
        )
    }

    @Test
    fun `a splice frame decodes the top-level origin kind`() = runBlocking {
        // The compaction context rewrite (docs/server-api.md, Compact): the summary is
        // an id-less user message with origin.kind "compaction_summary" at the
        // message's TOP level (history keeps it under metadata.origin).
        val emitted = runSocketEvents(
            """{"type":"context.spliced","seq":36,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-30T05:44:56.600Z","payload":{"type":"context.spliced",""" +
                """"start":0,"deleteCount":8,"messages":[""" +
                """{"role":"user","content":[{"type":"text","text":"kept message"}],""" +
                """"origin":{"kind":"user"},"id":"msg_1"},""" +
                """{"role":"user","content":[{"type":"text","text":"The conversation so far has been compacted…"}],""" +
                """"origin":{"kind":"compaction_summary"},"id":null}],""" +
                """"agentId":"main","sessionId":"session_1"}}""",
        )

        val splice = emitted.filterIsInstance<SocketEvent.Event>().single().event as SessionEvent.ContextSpliced
        assertEquals(listOf("user", ORIGIN_KIND_COMPACTION_SUMMARY), splice.messages.map { it.originKind })
        assertEquals(listOf("msg_1", ""), splice.messages.map { it.id })
    }

    private companion object {
        /** The generic prompts POST ack (docs/server-api.md): the client only reads
         * data.prompt_id back (to steer it in, or to ignore it) — status/created_at go
         * unparsed, so one literal serves every plain-ack test. */
        const val PROMPT_ACK_BODY =
            """{"code":0,"msg":"success","data":{"prompt_id":"msg_1","user_message_id":"msg_1",""" +
                """"status":"running","created_at":"2026-07-24T00:00:00.000Z"},"request_id":"01KX"}"""

        /** Live 0.29.2 capture (2026-07-28, trimmed texts) of a FINISHED sub-agent's
         * transcript, cold-rebuild path: core fields only, two completed
         * system-triggered turns (the task turn, then the forced summary turn). */
        const val TRANSCRIPT_FINISHED_BODY =
            """{"code":0,"msg":"success","data":{"agent_id":"agent-15",""" +
                """"items":[""" +
                """{"kind":"turn","turnId":"t0","ordinal":0,"state":"completed",""" +
                """"origin":{"kind":"other","payload":{"kind":"system_trigger","name":"subagent"}},""" +
                """"steps":[""" +
                """{"kind":"step","stepId":"t0.1","turnId":"t0","ordinal":1,"state":"completed",""" +
                """"frames":[""" +
                """{"kind":"thinking","frameId":"t0.1.f1","text":"Plan the listing."},""" +
                """{"kind":"tool","frameId":"t0.1.tool_MxYFRM8czboqPL4FXxWD4rC1",""" +
                """"toolCallId":"tool_MxYFRM8czboqPL4FXxWD4rC1","name":"Bash","state":"done",""" +
                """"input":{"command":"ls -la /tmp/spike"},""" +
                """"output":"total 0\ndrwxr-xr-x 2 ubuntu ubuntu 40 Jul 27 19:25 .\n"}]},""" +
                """{"kind":"step","stepId":"t0.2","turnId":"t0","ordinal":2,"state":"completed",""" +
                """"frames":[""" +
                """{"kind":"thinking","frameId":"t0.2.f1","text":"Empty dir."},""" +
                """{"kind":"text","frameId":"t0.2.f2","role":"assistant","text":"`/tmp/spike` is empty — **0 files**."}]}]},""" +
                """{"kind":"turn","turnId":"t1","ordinal":1,"state":"completed",""" +
                """"origin":{"kind":"other","payload":{"kind":"system_trigger","name":"subagent"}},""" +
                """"steps":[""" +
                """{"kind":"step","stepId":"t1.1","turnId":"t1","ordinal":1,"state":"completed",""" +
                """"frames":[""" +
                """{"kind":"thinking","frameId":"t1.1.f1","text":"Summarize comprehensively."},""" +
                """{"kind":"text","frameId":"t1.1.f2","role":"assistant","text":"## Comprehensive Findings…"}]}]}],""" +
                """"has_more":false,""" +
                """"tasks":[],"interactions":[],"attachments":[],"todos":[],"meta":{},""" +
                """"agents":[{"agentId":"main","type":"main"},{"agentId":"agent-15","type":"sub","parentAgentId":"main"}],""" +
                """"pending_interactions":[],"seq":1},"request_id":"01KX"}"""

        /** Live 0.29.2 capture (2026-07-28, trimmed) of a RUNNING sub-agent, warm path:
         * startedAt fields, a running tool frame with inputText/display and no output
         * yet, and an interleaved undo marker (the task-prompt splice). */
        const val TRANSCRIPT_RUNNING_BODY =
            """{"code":0,"msg":"success","data":{"agent_id":"agent-23",""" +
                """"items":[""" +
                """{"kind":"turn","turnId":"t0","ordinal":0,"state":"running",""" +
                """"origin":{"kind":"other","payload":{"kind":"system_trigger","name":"subagent"}},""" +
                """"startedAt":"2026-07-28T10:03:06.221Z",""" +
                """"steps":[""" +
                """{"kind":"step","stepId":"t0.1","turnId":"t0","ordinal":1,"state":"running",""" +
                """"startedAt":"2026-07-28T10:03:06.237Z",""" +
                """"frames":[""" +
                """{"kind":"thinking","frameId":"t0.1.f1","text":"Run the sleep."},""" +
                """{"kind":"tool","frameId":"t0.1.tool_4eOtxCMTEBHdpOehIhgnYAAe",""" +
                """"toolCallId":"tool_4eOtxCMTEBHdpOehIhgnYAAe","name":"Bash","state":"running",""" +
                """"input":{"command":"sleep 45 && echo awake","timeout":60},""" +
                """"inputText":"{\"command\":\"sleep 45 && echo awake\",\"timeout\":60}",""" +
                """"display":{"kind":"command","command":"sleep 45 && echo awake",""" +
                """"cwd":"/home/ubuntu/kimi-android-client","language":"bash"}}]}]},""" +
                """{"kind":"marker","markerId":"live-m1","marker":"undo",""" +
                """"payload":{"start":0,"deleteCount":0,"messages":[{"role":"user",""" +
                """"content":[{"type":"text","text":"Run the Bash command…"}],""" +
                """"id":"msg_01KYM2V437","origin":{"kind":"system_trigger","name":"subagent"}}]},""" +
                """"at":"2026-07-28T10:03:06.300Z"}],""" +
                """"has_more":false,""" +
                """"tasks":[],"interactions":[],"attachments":[],"todos":[],"meta":{},""" +
                """"agents":[{"agentId":"main","type":"main"},{"agentId":"agent-23","type":"sub","parentAgentId":"main"}],""" +
                """"pending_interactions":[],"seq":1},"request_id":"01KX"}"""

        /** Live 0.29.2 capture (2026-07-28, trimmed) of a main-agent user-origin turn:
         * the `prompt` field only exists on this origin kind. */
        const val TRANSCRIPT_USER_TURN_BODY =
            """{"code":0,"msg":"success","data":{"agent_id":"main",""" +
                """"items":[""" +
                """{"kind":"turn","turnId":"t3","ordinal":3,"state":"completed","origin":{"kind":"user"},""" +
                """"prompt":"Do it again with normal Agent calls.",""" +
                """"steps":[""" +
                """{"kind":"step","stepId":"t3.1","turnId":"t3","ordinal":1,"state":"completed",""" +
                """"frames":[{"kind":"text","frameId":"t3.1.f1","role":"assistant","text":"Done."}]}]}],""" +
                """"has_more":true,""" +
                """"tasks":[],"interactions":[],"attachments":[],"todos":[],"meta":{},""" +
                """"agents":[{"agentId":"main","type":"main"}],""" +
                """"pending_interactions":[],"seq":1},"request_id":"01KX"}"""
    }
}
