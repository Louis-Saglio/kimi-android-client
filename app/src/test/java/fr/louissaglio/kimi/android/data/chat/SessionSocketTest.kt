package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.ackFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.appendOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.opsFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.promptOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.resetFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.stepOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.textOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.toolOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.turnOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.undoOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.userMessage
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.webSocketUpgradeResponse
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The 2.5.0 session socket (ticket 175): the `subscribe_v2` frame, its ack, the
 * transcript frames folded into domain events, and the connection machinery
 * (heartbeat, stall watchdog, reconnect re-subscribe) — against a real WebSocket.
 */
class SessionSocketTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val socket = SessionSocket(OkHttpClient(), json, ChatMessageMapper(json))

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun settings() = ServerSettings(serverUrl = server.url("/").toString())

    /** Collects until [eventCount] session events arrived, then waits [settleMillis]
     * longer as proof that nothing more comes. [clientFrames] records what the app
     * sent. */
    private suspend fun runSocketEvents(
        vararg frames: String,
        eventCount: Int = frames.size,
        settleMillis: Long = 0,
        sessionId: String = "session_1",
        clientFrames: MutableList<String> = CopyOnWriteArrayList(),
    ): List<SocketEvent> = coroutineScope {
        server.enqueue(
            webSocketUpgradeResponse(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        frames.forEach(webSocket::send)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        clientFrames += text
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

    private fun List<SocketEvent>.sessionEvents(): List<SessionEvent> =
        filterIsInstance<SocketEvent.Event>().map { it.event }

    // --- subscribe_v2 + ack ------------------------------------------------------------

    @Test
    fun `the socket subscribes with one subscribe_v2 frame and never the old subscribe`() = runBlocking {
        val clientFrames = CopyOnWriteArrayList<String>()
        runSocketEvents(
            ackFrame(),
            opsFrame(turnOp("t1", "running")),
            eventCount = 1,
            settleMillis = 100,
            clientFrames = clientFrames,
        )

        assertEquals(
            listOf(
                """{"type":"subscribe_v2","id":"c_1","payload":{"session_id":"session_1","transcript":{"*":"delta"}}}""",
            ),
            clientFrames,
        )
        assertFalse(clientFrames.any { "\"type\":\"subscribe\"" in it })
    }

    @Test
    fun `a not_found ack fails the stream with SessionNotFound and never reconnects`() = runBlocking {
        server.enqueue(
            webSocketUpgradeResponse(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(ackFrame(accepted = emptyList(), notFound = listOf("session_1")))
                    }
                },
            ),
        )

        val events = withTimeout(5_000) { socket.observe(settings(), "session_1").toList() }

        assertEquals(listOf(SocketEvent.Connected, SocketEvent.Failed(DataError.SessionNotFound)), events)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `an ack for another request id or another session is not a failure`() = runBlocking {
        val emitted = runSocketEvents(
            ackFrame(id = "u1", accepted = emptyList(), notFound = listOf("session_1")),
            ackFrame(accepted = emptyList(), notFound = listOf("session_other")),
            opsFrame(turnOp("t1", "running")),
            eventCount = 1,
        )

        assertEquals(listOf(SocketEvent.Connected, SocketEvent.Event(SessionEvent.TurnStarted())), emitted)
    }

    @Test
    fun `the subscribe-time state frames and the reset emit no events`() = runBlocking {
        // The verbatim subscribe order: per-agent state frames, transcript.reset, ack.
        // The reset's items are always empty — it must neither render nor clear.
        val emitted = runSocketEvents(
            """{"type":"task.list","session_id":"session_1","agent_id":"main","tasks":[]}""",
            """{"type":"agent","session_id":"session_1","agent_id":"main","event_created_at":"2026-11-04T19:25:52.678Z",""" +
                """"status":"idle","permission_mode":"auto","plan_mode":false,"swarm_mode":false,"tower_mode":false}""",
            """{"type":"usage","session_id":"session_1","agent_id":"main","context_tokens":22433,"max_context_tokens":1000000}""",
            """{"type":"goal","session_id":"session_1","agent_id":"main","event_created_at":"2026-11-04T19:25:52.679Z"}""",
            """{"type":"todo","session_id":"session_1","agent_id":"main","items":[]}""",
            resetFrame(seq = 18),
            ackFrame(),
            opsFrame(promptOp("msg_1", "running"), seq = 19),
            eventCount = 1,
            settleMillis = 100,
        )

        assertEquals(
            listOf(SocketEvent.Connected, SocketEvent.Event(SessionEvent.PromptUpdated("msg_1", PromptStatus.Running))),
            emitted,
        )
    }

    // --- transcript.ops → domain events ------------------------------------------------

    @Test
    fun `a live prompt round streams the turn, thinking, text and a tool call`() = runBlocking {
        // The captured round-trip (docs/server-api.md, Captured round-trips), plus a
        // tool call inside the step.
        val emitted = runSocketEvents(
            opsFrame(promptOp("msg_1", "running"), seq = 19),
            opsFrame(turnOp("t1", "running"), seq = 20),
            opsFrame(undoOp("live-m4", userMessage("msg_1", "List the files")), seq = 21),
            opsFrame(stepOp("t1", "t1.1", "running"), seq = 23),
            opsFrame(textOp("t1.1.f1", "", kind = "thinking"), appendOp("t1.1.f1", 0, "Listing"), seq = 24),
            opsFrame(textOp("t1.1.f2", ""), appendOp("t1.1.f2", 0, "Sure"), seq = 25),
            opsFrame(appendOp("t1.1.f2", 4, "!"), seq = 26),
            opsFrame(toolOp("call_1", "Bash", "running", ""","input":{"command":"ls"}"""), seq = 27),
            opsFrame(toolOp("call_1", "Bash", "done", ""","input":{"command":"ls"},"output":"a.txt\n""""), seq = 28),
            opsFrame(textOp("t1.1.f2", "Sure!"), stepOp("t1", "t1.1", "completed"), seq = 29),
            opsFrame(turnOp("t1", "completed", ""","durationMs":1545"""), seq = 30),
            opsFrame(promptOp("msg_1", "completed"), seq = 31),
            eventCount = 11,
        )

        val events = emitted.sessionEvents()
        val splice = events[2] as SessionEvent.ContextSpliced
        assertEquals(listOf("msg_1"), splice.messages.map { it.id })
        assertEquals(
            listOf(
                SessionEvent.PromptUpdated("msg_1", PromptStatus.Running),
                SessionEvent.TurnStarted(),
                splice,
                SessionEvent.StepStarted(1, "t1.1"),
                SessionEvent.ThinkingDelta("Listing"),
                SessionEvent.AssistantDelta("Sure"),
                SessionEvent.AssistantDelta("!"),
                SessionEvent.ToolCallStarted(
                    "call_1", "Bash", preview = ToolPreview.of("Bash", buildJsonObject { put("command", "ls") }),
                    input = "{\n    \"command\": \"ls\"\n}", args = buildJsonObject { put("command", "ls") },
                ),
                SessionEvent.ToolCallResult("call_1", false, "a.txt\n"),
                SessionEvent.StepCompleted(1),
                SessionEvent.TurnEnded("completed"),
                SessionEvent.PromptUpdated("msg_1", PromptStatus.Completed),
            ),
            events,
        )
    }

    @Test
    fun `a sub-agent's ops carry its agent id and never read as main`() = runBlocking {
        val emitted = runSocketEvents(
            resetFrame(agentId = "agent-0", seq = 0),
            opsFrame(turnOp("t0", "running"), textOp("t0.1.f1", "sub hello", turnId = "t0"), agentId = "agent-0"),
            opsFrame(textOp("t1.1.f1", "main hello")),
            eventCount = 3,
        )

        val events = emitted.sessionEvents()
        assertEquals(
            listOf(
                SessionEvent.TurnStarted("agent-0"),
                SessionEvent.AssistantDelta("sub hello", "agent-0"),
                SessionEvent.AssistantDelta("main hello"),
            ),
            events,
        )
        assertEquals(listOf(true, true, false), events.map { it.isSubagentScoped() })
    }

    @Test
    fun `the legacy event families are no longer parsed`() = runBlocking {
        // The pre-2.5.0 frames the deleted branches consumed: none may emit anything.
        // The trailing op proves the socket survived them.
        fun legacy(type: String, payload: String) =
            """{"type":"$type","seq":2,"epoch":"ep_1","session_id":"session_1","timestamp":"2026-11-04T19:00:00.000Z",""" +
                """"payload":{"type":"$type",$payload"agentId":"main","sessionId":"session_1"}}"""
        val emitted = runSocketEvents(
            legacy("assistant.delta", """"turnId":1,"delta":"x","""),
            legacy("thinking.delta", """"turnId":1,"delta":"x","""),
            legacy("turn.started", """"turnId":1,"""),
            legacy("turn.ended", """"turnId":1,"reason":"completed","""),
            legacy("turn.step.started", """"step":1,"stepId":"s1","""),
            legacy("tool.call.started", """"toolCallId":"c","name":"Bash","args":{},"""),
            legacy("tool.result", """"toolCallId":"c","output":"o","""),
            legacy("tool.progress", """"toolCallId":"c","update":{"kind":"stdout","text":"o"},"""),
            legacy("context.spliced", """"messages":[{"id":"m","role":"user","content":[{"type":"text","text":"x"}]}],"""),
            legacy("prompt.queued", """"promptId":"m","queueLength":1,"""),
            legacy("agent.status.updated", """"contextTokens":1,"maxContextTokens":2,"model":"k3","""),
            legacy("subagent.spawned", """"subagentId":"agent-1","parentToolCallId":"c","""),
            legacy("task.started", """"info":{"taskId":"t","status":"running","kind":"process","startedAt":1},"""),
            legacy("compaction.started", """"trigger":"manual","""),
            legacy("event.approval.requested", """"approval_id":"a","tool_name":"Bash","action":"x","""),
            legacy("event.question.requested", """"question_id":"q","tool_call_id":"c","questions":[],"""),
            legacy("permission.approval.requested", """"approvalId":"a","""),
            opsFrame(turnOp("t1", "running")),
            eventCount = 1,
            settleMillis = 200,
        )

        assertEquals(listOf(SessionEvent.TurnStarted()), emitted.sessionEvents())
    }

    @Test
    fun `frames for a foreign session or the global channel are dropped, untagged ones delivered`() = runBlocking {
        val emitted = runSocketEvents(
            opsFrame(textOp("f1", "own ")),
            opsFrame(textOp("f2", "foreign"), sessionId = "session_2"),
            """{"type":"session.meta.updated","session_id":"__global__","timestamp":"…",""" +
                """"payload":{"type":"session.meta.updated","title":"x"}}""",
            // No session_id: fail-open, delivered.
            """{"type":"transcript.ops","payload":{"type":"transcript.ops","agent_id":"main",""" +
                """"ops":[${textOp("f3", "untagged")}],"seq":3}}""",
            eventCount = 2,
            settleMillis = 200,
        )

        assertEquals(
            listOf(SessionEvent.AssistantDelta("own "), SessionEvent.AssistantDelta("untagged")),
            emitted.sessionEvents(),
        )
    }

    @Test
    fun `the fold outlives the collection so a re-subscribe never re-emits shown text`() = runBlocking {
        // Ticket 048's gate restart: ON_STOP cancels the collection, ON_START opens a
        // new one. The second subscribe's upsert of the same frame emits only its news.
        val first = runSocketEvents(opsFrame(turnOp("t1", "running"), textOp("t1.1.f2", "hel")), eventCount = 2)
        val second = runSocketEvents(opsFrame(turnOp("t1", "running"), textOp("t1.1.f2", "hello")), eventCount = 1)

        assertEquals(listOf(SessionEvent.TurnStarted(), SessionEvent.AssistantDelta("hel")), first.sessionEvents())
        assertEquals(listOf(SessionEvent.AssistantDelta("lo")), second.sessionEvents())
    }

    // --- heartbeat ---------------------------------------------------------------------

    @Test
    fun `a server ping is answered with a pong echoing the nonce`() = runBlocking {
        // The answer must go over the wire as exactly one pong with the echoed nonce —
        // the only client frame after the subscribe.
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
        val emitted = runSocketEvents(
            """{"type":"ping","payload":{}}""",
            // One deliverable frame after the ping proves the socket survived.
            opsFrame(textOp("f1", "alive")),
            eventCount = 1,
            settleMillis = 200,
            clientFrames = clientFrames,
        )

        assertEquals(listOf(SessionEvent.AssistantDelta("alive")), emitted.sessionEvents())
        // Only the subscribe ever went out.
        assertEquals(1, clientFrames.size)
    }

    // --- stall watchdog + reconnect ----------------------------------------------------

    @Test
    fun `inbound silence past three heartbeats drops the socket and re-subscribes`() = runBlocking {
        // Ticket 152: a subscribed socket stalls silently. Silence past 3x heartbeat_ms
        // must be treated as a drop: close, backoff reconnect, a fresh subscribe_v2 (the
        // subscription does not survive), and the Reconnected the collector's REST
        // resync rides on.
        val secondFrames = CopyOnWriteArrayList<String>()
        val emitted = CopyOnWriteArrayList<SocketEvent>()
        server.enqueue(
            webSocketUpgradeResponse(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // 50 ms heartbeat → the stall threshold is 150 ms of silence.
                        webSocket.send("""{"type":"server_hello","payload":{"heartbeat_ms":50}}""")
                        webSocket.send(opsFrame(turnOp("t1", "running")))
                    }
                },
            ),
        )
        server.enqueue(
            webSocketUpgradeResponse(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        secondFrames += text
                    }
                },
            ),
        )
        coroutineScope {
            val job = launch { socket.observe(settings(), "session_1").collect { emitted += it } }
            try {
                // The reconnect follows the first backoff step (1 s) after the stall.
                awaitCondition(timeoutMs = 10_000) { secondFrames.isNotEmpty() }
            } finally {
                job.cancel()
            }
        }

        assertEquals(
            listOf(
                SocketEvent.Connected,
                SocketEvent.Event(SessionEvent.TurnStarted()),
                SocketEvent.Disconnected,
                SocketEvent.Reconnected,
            ),
            emitted,
        )
        assertEquals(
            listOf("""{"type":"subscribe_v2","id":"c_2","payload":{"session_id":"session_1","transcript":{"*":"delta"}}}"""),
            secondFrames,
        )
    }

    @Test
    fun `any inbound frame resets the stall watchdog`() = runBlocking {
        // Frames dripping every 150 ms against a 300 ms threshold (100 ms heartbeat):
        // over two full threshold windows of traffic, no drop may happen.
        val emitted = CopyOnWriteArrayList<SocketEvent>()
        server.enqueue(
            webSocketUpgradeResponse(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"server_hello","payload":{"heartbeat_ms":100}}""")
                        webSocket.send(opsFrame(textOp("drip", "")))
                        thread(isDaemon = true) {
                            repeat(5) {
                                Thread.sleep(150)
                                webSocket.send(opsFrame(appendOp("drip", it, "d")))
                            }
                        }
                    }
                },
            ),
        )
        coroutineScope {
            val job = launch { socket.observe(settings(), "session_1").collect { emitted += it } }
            try {
                awaitCondition { emitted.count { it is SocketEvent.Event } >= 5 }
            } finally {
                job.cancel()
            }
        }

        // Still the one connection — every drip re-armed the watchdog.
        assertEquals(1, server.requestCount)
        assertEquals(
            listOf(SocketEvent.Connected) + List(5) { SocketEvent.Event(SessionEvent.AssistantDelta("d")) },
            emitted,
        )
    }

    @Test
    fun `server pings alone keep the socket alive`() = runBlocking {
        // The heartbeat ping IS an inbound frame: pings every 150 ms (and nothing
        // else) against the 300 ms threshold must never trip the watchdog.
        val clientFrames = CopyOnWriteArrayList<String>()
        server.enqueue(
            webSocketUpgradeResponse(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"type":"server_hello","payload":{"heartbeat_ms":100}}""")
                        thread(isDaemon = true) {
                            repeat(5) {
                                Thread.sleep(150)
                                webSocket.send("""{"type":"ping","payload":{"nonce":"n$it"}}""")
                            }
                        }
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
                // The subscribe plus the pong answering each ping of the drip.
                awaitCondition { clientFrames.size >= 6 }
            } finally {
                job.cancel()
            }
        }

        assertEquals(1, server.requestCount)
        assertEquals("""{"type":"pong","payload":{"nonce":"n4"}}""", clientFrames[5])
    }

    @Test
    fun `the watchdog re-arms across reconnects`() = runBlocking {
        // Each connection delivers one frame and then stalls: the reconnect must carry
        // a fresh watchdog, so the second stall fires too and a third connection opens.
        val thirdFrames = CopyOnWriteArrayList<String>()
        val emitted = CopyOnWriteArrayList<SocketEvent>()
        fun stallAfterOneFrame(turnId: String) = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"server_hello","payload":{"heartbeat_ms":50}}""")
                webSocket.send(opsFrame(turnOp(turnId, "running")))
            }
        }
        server.enqueue(webSocketUpgradeResponse(stallAfterOneFrame("t1")))
        server.enqueue(webSocketUpgradeResponse(stallAfterOneFrame("t2")))
        server.enqueue(
            webSocketUpgradeResponse(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        thirdFrames += text
                    }
                },
            ),
        )
        coroutineScope {
            val job = launch { socket.observe(settings(), "session_1").collect { emitted += it } }
            try {
                // Stall (150 ms) + 1 s backoff, then stall + 2 s backoff.
                awaitCondition(timeoutMs = 15_000) { thirdFrames.isNotEmpty() }
            } finally {
                job.cancel()
            }
        }

        assertEquals(
            listOf(
                SocketEvent.Connected,
                SocketEvent.Event(SessionEvent.TurnStarted()),
                SocketEvent.Disconnected,
                SocketEvent.Reconnected,
                SocketEvent.Event(SessionEvent.TurnStarted()),
                SocketEvent.Disconnected,
                SocketEvent.Reconnected,
            ),
            emitted,
        )
        assertTrue(thirdFrames.single().contains("\"type\":\"subscribe_v2\""))
    }
}
