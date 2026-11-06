package fr.louissaglio.kimi.android.data.chat

import androidx.datastore.preferences.core.emptyPreferences
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.sessions.SessionActivityStore
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.testutil.TranscriptFrames
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
     * the state frames without news emit none), then waits [settleMillis] longer as
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
                        args = buildJsonObject { put("command", "echo doc-verify") },
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
    fun `transcript maps the 2_5 global state - seq, roster, prompts and the pending interactions`() = runBlocking {
        // The cold-start read model (ticket 176, docs/server-api.md, Transcript and
        // Pending state on REST — verified 2.5.1): the approval request is camelCase,
        // the question request keeps the snake_case question payload; ended entities
        // stay out of the pending lists.
        server.enqueue(
            MockResponse(body =
                """
                {"code":0,"msg":"success","data":{"agent_id":"main","items":[],"has_more":false,
                  "interactions":[
                    {"interactionId":"approval_1","interactionKind":"approval","toolCallId":"call_1","state":"pending",
                     "request":{"id":"approval_1","sessionId":"session_1","agentId":"agent-2","turnId":"t1",
                       "toolCallId":"call_1","toolName":"Bash","action":"Running: rm -rf target",
                       "display":{"kind":"command","command":"rm -rf target","cwd":"/tmp","language":"bash"}}},
                    {"interactionId":"approval_0","interactionKind":"approval","state":"approved",
                     "request":{"toolName":"Write","action":"Writing a.txt"},"response":{"decision":"approved"}},
                    {"interactionId":"question_1","interactionKind":"question","toolCallId":"call_q","state":"pending",
                     "request":{"question_id":"question_1","tool_call_id":"call_q","created_at":"2026-11-05T10:00:00.000Z",
                       "turn_id":"t1","questions":[{"id":"q_0","question":"Cake or pie?","header":"Dessert",
                       "options":[{"id":"opt_0_0","label":"Cake"}],"allow_other":true}]}},
                    {"interactionId":"question_0","interactionKind":"question","state":"answered",
                     "request":{"question_id":"question_0","questions":[]}}],
                  "pending_interactions":["approval_1","question_1"],
                  "attachments":[],
                  "prompts":[
                    {"promptId":"msg_a","status":"running","userMessageId":"msg_a","content":[],"createdAt":"2026-11-05T10:00:00.000Z"},
                    {"promptId":"msg_b","status":"queued","userMessageId":"msg_b","content":[],"createdAt":"2026-11-05T10:00:01.000Z"},
                    {"promptId":"msg_c","status":"brand_new","userMessageId":"msg_c","content":[]}],
                  "agents":[{"agentId":"main","type":"main"},{"agentId":"agent-2","type":"sub","parentAgentId":"main"}],
                  "seq":42},
                 "request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val page = repository.getTranscript(settings(), "session_1", "main", beforeTurn = null, pageSize = 1).getOrThrow()

        assertEquals(42L, page.seq)
        assertEquals(
            listOf(TranscriptAgent("main", isMain = true), TranscriptAgent("agent-2", isMain = false, parentAgentId = "main")),
            page.agents,
        )
        assertEquals(
            listOf(
                TranscriptPrompt("msg_a", PromptStatus.Running),
                TranscriptPrompt("msg_b", PromptStatus.Queued),
                TranscriptPrompt("msg_c", null),
            ),
            page.prompts,
        )
        assertEquals(
            listOf(PendingApprovalRequest("approval_1", "Bash", "rm -rf target", agentId = "agent-2")),
            page.pendingApprovals,
        )
        val question = page.pendingQuestions.single()
        assertEquals("question_1", question.questionId)
        assertEquals("call_q", question.toolCallId)
        assertEquals(MAIN_AGENT_ID, question.agentId)
        assertEquals(listOf("Cake"), question.items.single().options.map { it.label })
        assertNull(page.runningTurn)
    }

    @Test
    fun `transcript without seq and global state maps to an empty session state`() = runBlocking {
        // The sub-agent page's pages and a session that is not live: no seq, nothing
        // pending — the same page mapping, every 2.5 field optional.
        server.enqueue(
            MockResponse(body = """{"code":0,"msg":"success","data":{"agent_id":"agent-1","items":[],"has_more":false},"request_id":"r"}"""),
        )

        val page = repository.getTranscript(settings(), "session_1", "agent-1", beforeTurn = null, pageSize = 20).getOrThrow()

        assertNull(page.seq)
        assertTrue(page.agents.isEmpty())
        assertTrue(page.pendingApprovals.isEmpty())
        assertTrue(page.pendingQuestions.isEmpty())
    }

    @Test
    fun `a running turn's streaming tool frame renders no partial args and keeps its display`() = runBlocking {
        // Warm running frame: `input` absent while `inputText` streams — a fragment must
        // not become the row's input (first writer wins); the agent_call display maps.
        server.enqueue(
            MockResponse(body =
                """
                {"code":0,"msg":"success","data":{"agent_id":"main","has_more":false,"seq":3,"items":[
                  {"kind":"turn","turnId":"t1","ordinal":1,"state":"running","origin":{"kind":"user"},
                   "steps":[{"kind":"step","stepId":"t1.1","turnId":"t1","ordinal":1,"state":"running","frames":[
                     {"kind":"text","frameId":"t1.1.f1","role":"assistant","text":""},
                     {"kind":"tool","frameId":"t1.1.c1","toolCallId":"c1","name":"Bash","state":"running",
                      "inputText":"{\"command\":\"sl"},
                     {"kind":"tool","frameId":"t1.1.c2","toolCallId":"c2","name":"Agent","state":"error",
                      "inputText":"{\"prompt\":\"Reply ok\"}",
                      "display":{"kind":"agent_call","agent_name":"explore","prompt":"Reply ok","background":true},
                      "output":"boom"}]}]}]},
                 "request_id":"r"}
                """.trimIndent(),
            ),
        )

        val page = repository.getTranscript(settings(), "session_1", "main", beforeTurn = null, pageSize = 1).getOrThrow()

        val frames = page.runningTurn!!.steps.single().frames
        // A blank assistant text frame survives: the fold seeds its (empty) length.
        assertEquals(TranscriptFrame.Text("t1.1.f1", TranscriptFrame.Text.Role.Assistant, ""), frames[0])
        val streaming = frames[1] as TranscriptFrame.Tool
        assertNull(streaming.args)
        assertEquals("", streaming.input)
        assertTrue(streaming.running)
        val agent = frames[2] as TranscriptFrame.Tool
        assertEquals("Reply ok", (agent.args!!["prompt"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(AgentCallDisplay("explore", "Reply ok", background = true), agent.agentCall)
        assertTrue(agent.failed)
        assertFalse(agent.running)
    }

    @Test
    fun `prompt queue maps the active and queued prompts`() = runBlocking {
        // GET .../prompts (2.5.0, docs/server-api.md, Prompts → Queue snapshot — the
        // verbatim mid-turn shape).
        server.enqueue(
            MockResponse(body =
                """
                {"code":0,"msg":"success","data":{
                  "active":{"prompt_id":"msg_a","user_message_id":"msg_a","status":"running",
                            "content":[{"type":"text","text":"Run this bash command: sleep 25."}],
                            "created_at":"2026-11-04T10:00:00.000Z"},
                  "queued":[{"prompt_id":"msg_b","user_message_id":"msg_b","status":"queued",
                             "content":[{"type":"text","text":"Reply with exactly: steered-ok"}],
                             "created_at":"2026-11-04T10:00:01.000Z"}]},"request_id":"r"}
                """.trimIndent(),
            ),
        )

        val queue = repository.getPromptQueue(settings(), "session_1").getOrThrow()

        assertEquals("/api/v1/sessions/session_1/prompts", server.takeRequest().target)
        assertEquals("msg_a", queue.active?.promptId)
        val queued = queue.queued.single()
        assertEquals("msg_b", queued.promptId)
        assertEquals("msg_b", queued.message.id)
        assertEquals(ChatMessage.Role.User, queued.message.role)
        assertEquals(listOf(ChatMessage.Part.Text("Reply with exactly: steered-ok")), queued.message.parts)
    }

    @Test
    fun `transcript catch-up sends the cursor and reads the journal coverage`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"agent_id":"main","batches":[{"seq":43,"ops":[]}],""" +
                    """"latest_seq":43,"complete":true},"request_id":"r"}""",
            ),
        )
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"agent_id":"main","batches":[],"latest_seq":90,"complete":false},"request_id":"r"}""",
            ),
        )
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"agent_id":"main","batches":[],"latest_seq":3,"complete":true},"request_id":"r"}""",
            ),
        )

        val covered = repository.getTranscriptCatchUp(settings(), "session_1", "main", sinceSeq = 42).getOrThrow()
        assertEquals("/api/v1/sessions/session_1/transcript/ops?agent_id=main&since_seq=42", server.takeRequest().target)
        assertFalse(covered.isStale)

        // complete:false — the journal cannot reach back: a full refresh is required.
        assertTrue(repository.getTranscriptCatchUp(settings(), "session_1", "main", sinceSeq = 42).getOrThrow().isStale)
        // A latest seq behind the watermark: a restarted server's fresh journal.
        assertTrue(repository.getTranscriptCatchUp(settings(), "session_1", "main", sinceSeq = 42).getOrThrow().isStale)
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
    fun `history maps the origin attachments and image parts to domain parts`() = runBlocking {
        // Fixture per docs/server-api.md (tickets 034/160): file attachments ride
        // `metadata.origin.attachments` (0.39.1) with the rewritten "Attached file"
        // notice as a sibling text part; images come back as a data-url source in
        // history, as raw base64 in the prompt ACK echo, or as a file reference —
        // and carry the file's `name` since 0.42.0.
        server.enqueue(
            MockResponse(body = 
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"id":"msg_1","session_id":"session_1","role":"user",
                   "content":[
                     {"type":"text","text":"Attached file \"notes.txt\" (text/plain, 340 bytes): /home/ubuntu/.kimi-code/sessions/wd_1/session_2/attachments/f_01KYA4RCBE4NPA1RX5TT7DB5J9-notes.txt — open it with the Read tool"},
                     {"type":"text","text":"what is in these?"},
                     {"type":"image","source":{"kind":"url","url":"data:image/png;base64,aGVsbG8="},"name":"one.png"},
                     {"type":"image","source":{"kind":"base64","media_type":"image/jpeg","data":"anBlZw=="}},
                     {"type":"image","source":{"kind":"file","file_id":"f_abc","media_type":"image/webp"}}
                   ],
                   "metadata":{"origin":{"kind":"user","attachments":[
                     {"name":"notes.txt","mediaType":"text/plain","size":340,"path":"/home/ubuntu/.kimi-code/sessions/wd_1/session_2/attachments/f_01KYA4RCBE4NPA1RX5TT7DB5J9-notes.txt"}
                   ]}},
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
                    ChatMessage.Part.Text("what is in these?"),
                    ChatMessage.Part.Image(base64Data = "aGVsbG8=", fileId = null, name = "one.png"),
                    ChatMessage.Part.Image(base64Data = "anBlZw==", fileId = null),
                    ChatMessage.Part.Image(base64Data = null, fileId = "f_abc"),
                    ChatMessage.Part.Attachment("notes.txt", "text/plain", 340, "f_01KYA4RCBE4NPA1RX5TT7DB5J9"),
                ),
                createdAt = Instant.parse("2026-07-24T04:12:10.000Z"),
                originKind = "user",
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
                        args = buildJsonObject { put("command", "./gradlew test") },
                        // Ticket 121: a parseable Bash call carries its typed payload.
                        payload = ToolPayload.Bash("./gradlew test", null, null, null, false),
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
                        args = buildJsonObject { put("command", "echo doc-verify") },
                        // Ticket 121: a parseable Bash call carries its typed payload.
                        payload = ToolPayload.Bash("echo doc-verify", null, null, null, false),
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
            """{"content":[{"type":"text","text":"Reply with exactly: ok"}],"swarm_mode":false}""",
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
        assertEquals("""{"content":[{"type":"text","text":"Stop counting"}],"swarm_mode":false}""", prompt.body!!.utf8())
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
            """{"content":[{"type":"text","text":"hello"}],"model":"kimi-code/k3","swarm_mode":false}""",
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
            """{"content":[{"type":"text","text":"Stop counting"}],"model":"kimi-code/k3","swarm_mode":false}""",
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
            """{"content":[{"type":"text","text":"one"}],"swarm_mode":false}""",
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
            """{"content":[{"type":"text","text":"hello"}],"model":"kimi-code/kimi-for-coding-highspeed","swarm_mode":false}""",
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
            """{"content":[{"type":"text","text":"Stop counting"}],"model":"kimi-code/kimi-for-coding-highspeed","swarm_mode":false}""",
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
            """{"content":[{"type":"text","text":"hello"}],"model":"kimi-code/k3","thinking":"low","swarm_mode":false}""",
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
            """{"content":[{"type":"text","text":"Stop counting"}],"model":"kimi-code/k3","thinking":"max","swarm_mode":false}""",
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
            """{"content":[{"type":"text","text":"hello"}],"model":"kimi-code/k3","swarm_mode":false}""",
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
            """{"content":[{"type":"text","text":"hello"}],"model":"kimi-code/kimi-for-coding","swarm_mode":false}""",
            server.takeRequest().body!!.utf8(),
        )
    }

    @Test
    fun `sendPrompt sends the session's stored permission mode in the prompt body`() = runBlocking {
        // Ticket 141: the per-prompt permission_mode stamp binds the session for its
        // remaining turns (docs/server-api.md, Prompts).
        repository.setSessionPermission("session_1", "manual")
        enqueueConfigWithoutDefaultModel()
        enqueuePromptAck()

        val result = repository.sendPrompt(settings(), "session_1", "hello")

        assertTrue(result.isSuccess)
        assertEquals("/api/v1/config", server.takeRequest().target)
        val prompt = server.takeRequest()
        assertEquals("/api/v1/sessions/session_1/prompts", prompt.target)
        assertEquals(
            """{"content":[{"type":"text","text":"hello"}],"permission_mode":"manual","swarm_mode":false}""",
            prompt.body!!.utf8(),
        )
    }

    @Test
    fun `steer stamps the session's stored permission mode too`() = runBlocking {
        repository.setSessionPermission("session_1", "yolo")
        enqueueConfigWithoutDefaultModel()
        enqueuePromptAck()
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
            """{"content":[{"type":"text","text":"Stop counting"}],"permission_mode":"yolo","swarm_mode":false}""",
            prompt.body!!.utf8(),
        )
        assertEquals("/api/v1/sessions/session_1/prompts:steer", server.takeRequest().target)
    }

    @Test
    fun `no stored permission mode leaves the field out of the prompt body`() = runBlocking {
        // Ticket 141: absent store value = follow the server — the prompt carries no
        // permission_mode field at all (web parity; the stamp self-heals once picked).
        enqueueConfigWithoutDefaultModel()
        enqueuePromptAck()

        val result = repository.sendPrompt(settings(), "session_1", "hello")

        assertTrue(result.isSuccess)
        assertEquals("/api/v1/config", server.takeRequest().target)
        assertEquals(
            """{"content":[{"type":"text","text":"hello"}],"swarm_mode":false}""",
            server.takeRequest().body!!.utf8(),
        )
    }

    @Test
    fun `sendPrompt carries swarm_mode true when the chip is on and false when off`() = runBlocking {
        // Ticket 174: the server applies swarm_mode before enqueueing and an omitted
        // field leaves the mode unchanged — so false is sent explicitly, never dropped.
        enqueueConfigWithoutDefaultModel()
        enqueuePromptAck()
        enqueuePromptAck()

        assertTrue(repository.sendPrompt(settings(), "session_1", "fan out", swarmMode = true).isSuccess)
        assertTrue(repository.sendPrompt(settings(), "session_1", "back to one", swarmMode = false).isSuccess)

        assertEquals("/api/v1/config", server.takeRequest().target)
        assertEquals(
            """{"content":[{"type":"text","text":"fan out"}],"swarm_mode":true}""",
            server.takeRequest().body!!.utf8(),
        )
        assertEquals(
            """{"content":[{"type":"text","text":"back to one"}],"swarm_mode":false}""",
            server.takeRequest().body!!.utf8(),
        )
    }

    @Test
    fun `steer carries the swarm chip on its posted prompt`() = runBlocking {
        enqueueConfigWithoutDefaultModel()
        enqueuePromptAck()
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"steered":true,"prompt_ids":["msg_1"]},"request_id":"01KX"}""",
            ),
        )

        assertTrue(repository.steer(settings(), "session_1", "split it", swarmMode = true).isSuccess)

        assertEquals("/api/v1/config", server.takeRequest().target)
        assertEquals(
            """{"content":[{"type":"text","text":"split it"}],"swarm_mode":true}""",
            server.takeRequest().body!!.utf8(),
        )
        assertEquals("/api/v1/sessions/session_1/prompts:steer", server.takeRequest().target)
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
    fun `socket subscribes with subscribe_v2 and emits parsed events`() = runBlocking {
        val clientFrames = CopyOnWriteArrayList<String>()
        server.enqueue(
            wsUpgrade(clientFrames) { webSocket ->
                webSocket.send("""{"type":"server_hello","payload":{"protocol_version":2,"heartbeat_ms":10000}}""")
                webSocket.send(TranscriptFrames.ackFrame())
                webSocket.send(TranscriptFrames.opsFrame(TranscriptFrames.textOp("t1.1.f2", "ok")))
            },
        )

        val emitted = CopyOnWriteArrayList<SocketEvent>()
        val job = launch { repository.observeSession(settings(), "session_1").collect { emitted += it } }
        try {
            awaitCondition { emitted.count { it is SocketEvent.Event } >= 3 }
        } finally {
            job.cancel()
        }

        assertEquals(SocketEvent.Connected, emitted.first())
        assertEquals(
            // The accepted ack resets the approval and question queues (tickets
            // 171/172), then the op.
            listOf(
                SessionEvent.ApprovalsReset(emptyList()),
                SessionEvent.QuestionsReset(emptyList()),
                SessionEvent.AssistantDelta("ok"),
            ),
            emitted.filterIsInstance<SocketEvent.Event>().map { it.event },
        )
        val subscribe = clientFrames.first()
        assertTrue(subscribe.contains("\"type\":\"subscribe_v2\""))
        assertTrue(subscribe.contains("\"session_id\":\"session_1\""))
        Unit
    }

    @Test
    fun `an undo marker emits the spliced user message`() = runBlocking {
        // The captured prompt round (docs/server-api.md, Transcript protocol): the sent
        // message rides an `undo` marker.
        val emitted = runSocketEvents(
            TranscriptFrames.opsFrame(
                TranscriptFrames.undoOp("live-m4", TranscriptFrames.userMessage("msg_1", "hello from the phone")),
            ),
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
    fun `an undo marker without a message id emits the id-less user message`() = runBlocking {
        val emitted = runSocketEvents(
            TranscriptFrames.opsFrame(
                TranscriptFrames.undoOp(
                    "live-m9",
                    TranscriptFrames.userMessage(null, "Stop counting. Reply with exactly: steered-ok"),
                ),
            ),
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
    fun `an undo marker splice filters internal envelopes from the echoed user message`() = runBlocking {
        val emitted = runSocketEvents(
            TranscriptFrames.opsFrame(
                TranscriptFrames.undoOp(
                    "live-m5",
                    """{"role":"user","content":[{"type":"text","text":"<system-reminder>\ninternal\n</system-reminder>"}],""" +
                        """"origin":{"kind":"user"},"id":"msg_9"}""",
                ),
            ),
        )

        val timeline = testChatTimeline()
        timeline.apply((emitted.first { it is SocketEvent.Event } as SocketEvent.Event).event)

        assertEquals(emptyList<ChatItem>(), timeline.items())
    }

    // --- Question responses on the interactions route (ticket 172) --------------------

    @Test
    fun `answerQuestion posts the per-item answers to the interactions route`() = runBlocking {
        // The verbatim 2.4.0+ success (docs/server-api.md, Interactions → Respond).
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"outcome":"answered","ended_at":"2026-10-28T19:28:18.858Z"},"request_id":"01KX"}""",
            ),
        )

        val result = repository.answerQuestion(
            settings(),
            "session_1",
            "question_10025aea",
            listOf(
                QuestionAnswer.OptionPick("q_0", "opt_0_1"),
                QuestionAnswer.Other("q_1", "ramen"),
            ),
        )

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/interactions/question_10025aea", request.target)
        assertEquals(
            """{"kind":"question","answers":{"q_0":{"kind":"single","option_id":"opt_0_1"},""" +
                """"q_1":{"kind":"other","text":"ramen"}}}""",
            request.body!!.utf8(),
        )
    }

    @Test
    fun `answerQuestion serializes the multi-select answer kinds`() = runBlocking {
        // Ticket 090's mapping, unchanged by the 2.4.0 route move (ticket 172).
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"outcome":"answered","ended_at":"2026-10-28T19:28:18.858Z"},"request_id":"01KX"}""",
            ),
        )

        val result = repository.answerQuestion(
            settings(),
            "session_1",
            "question_1",
            listOf(
                QuestionAnswer.Multi("q_0", listOf("opt_0_0", "opt_0_2")),
                QuestionAnswer.MultiWithOther("q_1", listOf("opt_1_0"), "Fish"),
            ),
        )

        assertTrue(result.isSuccess)
        assertEquals(
            """{"kind":"question","answers":{"q_0":{"kind":"multi","option_ids":["opt_0_0","opt_0_2"]},""" +
                """"q_1":{"kind":"multi_with_other","option_ids":["opt_1_0"],"other_text":"Fish"}}}""",
            server.takeRequest().body!!.utf8(),
        )
    }

    @Test
    fun `answerQuestion surfaces the ended 40902 and the unknown 40404 as Api errors`() = runBlocking {
        // Verbatim error envelopes (docs/server-api.md, Interactions → Respond).
        server.enqueue(
            MockResponse(body =
                """{"code":40902,"msg":"interaction question_0c93eb23 has ended","data":null,"request_id":"01KX"}""",
            ),
        )
        server.enqueue(
            MockResponse(body =
                """{"code":40404,"msg":"interaction question_00000000 not found","data":null,"request_id":"01KX"}""",
            ),
        )
        val answers = listOf(QuestionAnswer.OptionPick("q_0", "opt_0_1"))

        val ended = repository.answerQuestion(settings(), "session_1", "question_0c93eb23", answers).exceptionOrNull()
        val unknown = repository.answerQuestion(settings(), "session_1", "question_00000000", answers).exceptionOrNull()

        assertEquals(DataError.Api(40902, "interaction question_0c93eb23 has ended"), ended)
        assertEquals(DataError.Api(40404, "interaction question_00000000 not found"), unknown)
    }

    @Test
    fun `dismissQuestion posts dismissed true and succeeds on the code 0 dismissed outcome`() = runBlocking {
        // 2.4.0+: a dismissal is an ordinary code-0 success (no 40909 special case).
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"outcome":"dismissed","ended_at":"2026-10-28T19:30:02.112Z"},"request_id":"01KX"}""",
            ),
        )

        val result = repository.dismissQuestion(settings(), "session_1", "question_1")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/interactions/question_1", request.target)
        assertEquals("""{"kind":"question","dismissed":true}""", request.body!!.utf8())
    }

    @Test
    fun `dismissQuestion maps a non-zero code to Api error`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """{"code":40902,"msg":"interaction question_1 has ended","data":null,"request_id":"01KX"}""",
            ),
        )

        val result = repository.dismissQuestion(settings(), "session_1", "question_1")

        assertEquals(DataError.Api(40902, "interaction question_1 has ended"), result.exceptionOrNull())
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
                """{"type":"text","text":"look at these"}],"swarm_mode":false}""",
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
            """{"content":[{"type":"file","file_id":"f_txt","name":"notes.txt","media_type":"text/plain","size":27}],"swarm_mode":false}""",
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
            """{"content":[{"type":"text","text":"hello"}],"swarm_mode":false}""",
            server.takeRequest().body!!.utf8(),
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
                    args = buildJsonObject { put("command", "ls -la /tmp/spike") },
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
                args = buildJsonObject {
                    put("command", "sleep 45 && echo awake")
                    put("timeout", 60)
                },
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
    fun `an undo marker decodes the top-level origin kind`() = runBlocking {
        // The compaction context rewrite (docs/server-api.md, Compact → WebSocket
        // sequence): the summary is an id-less user message with origin.kind
        // "compaction_summary" at the message's TOP level; the continuation injection
        // is dropped by the fold.
        val emitted = runSocketEvents(
            TranscriptFrames.opsFrame(
                TranscriptFrames.undoOp(
                    "live-m6",
                    TranscriptFrames.userMessage("msg_1", "kept message"),
                    """{"role":"user","content":[{"type":"text","text":"The conversation so far has been compacted…"}],""" +
                        """"origin":{"kind":"compaction_summary"},"id":null}""",
                    """{"role":"user","content":[{"type":"text","text":"Continue."}],""" +
                        """"origin":{"kind":"injection","variant":"compaction_continuation"}}""",
                ),
            ),
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
