package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.webSocketUpgradeResponse
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SessionSocketTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val socket = SessionSocket(OkHttpClient(), json, ChatMessageMapper(json))

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun settings() = ServerSettings(serverUrl = server.url("/").toString())

    /** Same harness as ChatRepositoryTest.runSocketEvents: collects until [eventCount]
     * events arrived, then waits [settleMillis] longer as proof that nothing more comes. */
    private suspend fun runSocketEvents(
        vararg frames: String,
        eventCount: Int = frames.size,
        settleMillis: Long = 0,
        sessionId: String = "session_1",
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
        val job = launch { socket.observe(settings(), sessionId).collect { emitted += it } }
        try {
            awaitCondition { emitted.count { it is SocketEvent.Event } >= eventCount }
            if (settleMillis > 0) delay(settleMillis)
        } finally {
            job.cancel()
        }
        emitted
    }

    @Test
    fun `a server ping is answered with a pong echoing the nonce`() = runBlocking {
        // 0.35.0 heartbeat (docs/server-api.md, Heartbeat): the answer must go over the
        // wire as exactly one pong with the echoed nonce — the only client frames after
        // the subscribe.
        val clientFrames = CopyOnWriteArrayList<String>()
        server.enqueue(
            webSocketUpgradeResponse(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"ping","payload":{"nonce":"abc"}}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        clientFrames += text
                    }
                },
            ),
        )
        coroutineScope {
            val job = launch { socket.observe(settings(), "session_1").collect {} }
            try {
                awaitCondition { clientFrames.size >= 2 }
                // Settle: no second pong, no other frame.
                delay(200)
            } finally {
                job.cancel()
            }
        }

        assertEquals(2, clientFrames.size)
        assertEquals("""{"type":"pong","payload":{"nonce":"abc"}}""", clientFrames[1])
    }

    @Test
    fun `a ping without a nonce is ignored`() = runBlocking {
        val clientFrames = CopyOnWriteArrayList<String>()
        val emitted = CopyOnWriteArrayList<SocketEvent>()
        server.enqueue(
            webSocketUpgradeResponse(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"ping","payload":{}}""")
                        // One deliverable frame after the ping proves the socket survived.
                        webSocket.send(
                            """{"type":"assistant.delta","seq":2,"epoch":"ep_1","volatile":true,"offset":0,""" +
                                """"session_id":"session_1","timestamp":"2026-08-12T00:00:00.000Z",""" +
                                """"payload":{"type":"assistant.delta","turnId":1,"delta":"alive","agentId":"main","sessionId":"session_1"}}""",
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        clientFrames += text
                    }
                },
            ),
        )
        coroutineScope {
            val job = launch { socket.observe(settings(), "session_1").collect { emitted += it } }
            try {
                awaitCondition { emitted.any { it is SocketEvent.Event } }
                // Settle: the malformed ping must have arrived and produced no frame.
                delay(200)
            } finally {
                job.cancel()
            }
        }

        assertEquals(
            listOf(SocketEvent.Event(SessionEvent.AssistantDelta("alive"))),
            emitted.filterIsInstance<SocketEvent.Event>(),
        )
        // Only the subscribe ever went out.
        assertEquals(1, clientFrames.size)
    }

    @Test
    fun `task started and terminated frames parse into task events`() = runBlocking {
        val emitted = runSocketEvents(
            // Same shape as the verbatim task.terminated of docs/server-api.md
            // (Background lifecycle), with the running status and no endedAt.
            """{"type":"task.started","seq":52,"epoch":"ep_1","session_id":"session_1",""" +
                """"payload":{"type":"task.started",""" +
                """"info":{"taskId":"agent-7noib8bb","description":"doc-verify-bg","status":"running",""" +
                """"detached":true,"startedAt":1785210101939,"timeoutMs":7200000,""" +
                """"kind":"agent","agentId":"agent-13","subagentType":"explore"},""" +
                """"agentId":"main","sessionId":"session_1"}}""",
            // Verbatim task.terminated of the cancel run (docs/server-api.md:1467-1478):
            // CORE status "killed", epoch-millis times, the sub-agent link in agentId.
            """{"type":"task.terminated","seq":109,"epoch":"ep_1","session_id":"session_1",""" +
                """"payload":{"type":"task.terminated",""" +
                """"info":{"taskId":"agent-lftjyr4o","description":"doc-verify-cancel",""" +
                """"status":"killed","detached":true,"startedAt":1785210101939,"endedAt":1785210118804,""" +
                """"stopReason":"Aborted by the user","timeoutMs":7200000,""" +
                """"kind":"agent","agentId":"agent-14","subagentType":"agent"},""" +
                """"agentId":"main","sessionId":"session_1"}}""",
        )

        assertEquals(
            listOf(
                SocketEvent.Event(
                    SessionEvent.TaskStarted(
                        TaskInfo(
                            taskId = "agent-7noib8bb",
                            description = "doc-verify-bg",
                            status = TaskInfo.Status.Running,
                            startedAt = Instant.ofEpochMilli(1785210101939),
                            kind = TaskInfo.Kind.Agent,
                            agentId = "agent-13",
                            subagentType = "explore",
                        ),
                    ),
                ),
                SocketEvent.Event(
                    SessionEvent.TaskTerminated(
                        TaskInfo(
                            taskId = "agent-lftjyr4o",
                            description = "doc-verify-cancel",
                            status = TaskInfo.Status.Killed,
                            startedAt = Instant.ofEpochMilli(1785210101939),
                            endedAt = Instant.ofEpochMilli(1785210118804),
                            stopReason = "Aborted by the user",
                            kind = TaskInfo.Kind.Agent,
                            agentId = "agent-14",
                            subagentType = "agent",
                        ),
                    ),
                ),
            ),
            emitted.filterIsInstance<SocketEvent.Event>(),
        )
    }

    @Test
    fun `an approval request frame carries the asking agent id from the payload stamp`() = runBlocking {
        // Verbatim 0.33.0 capture of a SUB-AGENT's request — the legacy
        // event.approval.requested at protocol-verify-0.33.0/ws-s1.jsonl:535 (the v2
        // twin the ticket references, :532, is one of the permission.approval.*
        // duplicates this socket deliberately ignores). Shape unchanged at 0.43.1
        // (docs/server-api.md, Permissions). The frame keeps its real session id, so
        // the collection subscribes to it.
        val emitted = runSocketEvents(
            """{"type":"event.approval.requested","seq":149,""" +
                """"session_id":"session_5921be1f-f753-445f-983f-288aa3bae382","timestamp":"2026-08-05T19:42:12.906Z",""" +
                """"payload":{"type":"event.approval.requested","agentId":"agent-1",""" +
                """"sessionId":"session_5921be1f-f753-445f-983f-288aa3bae382","approval_id":"tool_B1gn9gVO0nEwtnqs3CtmVMql",""" +
                """"session_id":"session_5921be1f-f753-445f-983f-288aa3bae382","turn_id":0,"tool_call_id":"tool_B1gn9gVO0nEwtnqs3CtmVMql",""" +
                """"tool_name":"Bash","action":"Running: sleep 300 && echo awake",""" +
                """"tool_input_display":{"kind":"command","command":"sleep 300 && echo awake","cwd":"/home/ubuntu/spike","language":"bash"},""" +
                """"created_at":"2026-08-05T19:42:12.905Z","expires_at":"2026-08-06T19:42:12.905Z"},"epoch":"ep_01KZ9PJAZMRMFAX8RWB0NYG8GM"}""",
            sessionId = "session_5921be1f-f753-445f-983f-288aa3bae382",
        )

        assertEquals(
            listOf(
                SocketEvent.Event(
                    SessionEvent.ApprovalRequested(
                        approvalId = "tool_B1gn9gVO0nEwtnqs3CtmVMql",
                        toolName = "Bash",
                        summary = "sleep 300 && echo awake",
                        agentId = "agent-1",
                    ),
                ),
            ),
            emitted.filterIsInstance<SocketEvent.Event>(),
        )
    }

    @Test
    fun `a tool call started frame carries the server description onto the event`() = runBlocking {
        // Doc-verbatim frame (docs/server-api.md, tool.call.started): the payload's
        // `description` is the server-rendered action — it lands on the event and
        // wins the row preview over display.command (ticket 153).
        val emitted = runSocketEvents(
            """{"type":"tool.call.started","seq":14,""" +
                """"payload":{"type":"tool.call.started","turnId":0,""" +
                """"toolCallId":"tool_RMgBIy1asIorg1eV8P3ZhHiL","name":"Bash",""" +
                """"args":{"command":"echo doc-verify-0290"},""" +
                """"description":"Running: echo doc-verify-0290",""" +
                """"display":{"kind":"command","command":"echo doc-verify-0290",""" +
                """"cwd":"/home/ubuntu/spike","language":"bash"},""" +
                """"agentId":"main","sessionId":"session_..."}}""",
        )

        assertEquals(
            listOf(
                SocketEvent.Event(
                    SessionEvent.ToolCallStarted(
                        toolCallId = "tool_RMgBIy1asIorg1eV8P3ZhHiL",
                        name = "Bash",
                        preview = "Running: echo doc-verify-0290",
                        input = "{\n    \"command\": \"echo doc-verify-0290\"\n}",
                        args = buildJsonObject { put("command", "echo doc-verify-0290") },
                        description = "Running: echo doc-verify-0290",
                    ),
                ),
            ),
            emitted.filterIsInstance<SocketEvent.Event>(),
        )
    }

    @Test
    fun `task frames carry the owning agent id from the payload stamp`() = runBlocking {
        val emitted = runSocketEvents(
            // A sub-agent-spawned background bash: the payload's agentId stamp names
            // the OWNING agent (its task store holds the task; REST /tasks serves only
            // the main agent's). info.agentId stays null on bash kinds.
            """{"type":"task.started","seq":60,"epoch":"ep_1","session_id":"session_1",""" +
                """"payload":{"type":"task.started",""" +
                """"info":{"taskId":"proc-sub1","description":"nested bg bash","status":"running",""" +
                """"detached":true,"startedAt":1785210101939,"kind":"process"},""" +
                """"agentId":"agent-12","sessionId":"session_1"}}""",
        )

        assertEquals(
            listOf(
                SocketEvent.Event(
                    SessionEvent.TaskStarted(
                        TaskInfo(
                            taskId = "proc-sub1",
                            description = "nested bg bash",
                            status = TaskInfo.Status.Running,
                            startedAt = Instant.ofEpochMilli(1785210101939),
                            kind = TaskInfo.Kind.Process,
                        ),
                        agentId = "agent-12",
                    ),
                ),
            ),
            emitted.filterIsInstance<SocketEvent.Event>(),
        )
    }

    @Test
    fun `task notified and the legacy background task aliases are ignored`() = runBlocking {
        // task.notified and the legacy background.task.* aliases are deliberately NOT
        // parsed (web parity — ticket 092): they fall into the unknown-type ignore,
        // so the settle wait must find them delivered with NO event produced.
        val emitted = runSocketEvents(
            """{"type":"task.notified","seq":89,"epoch":"ep_1","session_id":"session_1",""" +
                """"payload":{"type":"task.notified","notificationType":"task.completed","title":"t",""" +
                """"body":"b","severity":"info","sourceKind":"background_task","sourceId":"agent-7noib8bb",""" +
                """"agentId":"main","sessionId":"session_1"}}""",
            """{"type":"background.task.started","seq":53,"epoch":"ep_1","session_id":"session_1",""" +
                """"payload":{"type":"background.task.started",""" +
                """"info":{"taskId":"agent-7noib8bb","description":"doc-verify-bg","status":"running",""" +
                """"startedAt":1785210101939,"kind":"agent","agentId":"agent-13"},""" +
                """"agentId":"main","sessionId":"session_1"}}""",
            """{"type":"background.task.terminated","seq":85,"epoch":"ep_1","session_id":"session_1",""" +
                """"payload":{"type":"background.task.terminated",""" +
                """"info":{"taskId":"agent-7noib8bb","description":"doc-verify-bg","status":"completed",""" +
                """"startedAt":1785210101939,"endedAt":1785210118804,"kind":"agent","agentId":"agent-13"},""" +
                """"agentId":"main","sessionId":"session_1"}}""",
            // One deliverable event to await; the settle covers the ignored frames.
            """{"type":"assistant.delta","seq":90,"epoch":"ep_1","volatile":true,"offset":0,""" +
                """"session_id":"session_1","timestamp":"2026-08-03T00:00:00.000Z",""" +
                """"payload":{"type":"assistant.delta","turnId":1,"delta":"after","agentId":"main","sessionId":"session_1"}}""",
            eventCount = 1,
            settleMillis = 200,
        )

        assertEquals(
            listOf(SocketEvent.Event(SessionEvent.AssistantDelta("after"))),
            emitted.filterIsInstance<SocketEvent.Event>(),
        )
    }

    @Test
    fun `frames for a foreign session are dropped, own and untagged frames are delivered`() = runBlocking {
        // 0.31.0 caveat (docs/server-api.md): the server sends events for sessions this
        // connection never subscribed to, so the envelope session_id is the filter key.
        // The settle wait covers the foreign frame: it must arrive and still produce no
        // event (it is dropped with a Log.w; unit-test Log is a stub, so the observable
        // assertion is non-delivery).
        val emitted = runSocketEvents(
            """{"type":"assistant.delta","seq":2,"epoch":"ep_1","volatile":true,"offset":0,""" +
                """"session_id":"session_1","timestamp":"2026-07-31T00:00:00.000Z",""" +
                """"payload":{"type":"assistant.delta","turnId":1,"delta":"own ","agentId":"main","sessionId":"session_1"}}""",
            """{"type":"assistant.delta","seq":7,"epoch":"ep_9","volatile":true,"offset":0,""" +
                """"session_id":"session_2","timestamp":"2026-07-31T00:00:00.100Z",""" +
                """"payload":{"type":"assistant.delta","turnId":3,"delta":"foreign","agentId":"main","sessionId":"session_2"}}""",
            // No session_id (control frames carry none): fail-open, delivered.
            """{"type":"assistant.delta","seq":3,"epoch":"ep_1","volatile":true,"offset":4,""" +
                """"timestamp":"2026-07-31T00:00:00.200Z",""" +
                """"payload":{"type":"assistant.delta","turnId":1,"delta":"untagged"}}""",
            eventCount = 2,
            settleMillis = 200,
        )

        assertEquals(
            listOf(
                SocketEvent.Event(SessionEvent.AssistantDelta("own ")),
                SocketEvent.Event(SessionEvent.AssistantDelta("untagged")),
            ),
            emitted.filterIsInstance<SocketEvent.Event>(),
        )
    }
}
