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
}
