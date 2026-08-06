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
        val job = launch { socket.observe(settings(), "session_1").collect { emitted += it } }
        try {
            awaitCondition { emitted.count { it is SocketEvent.Event } >= eventCount }
            if (settleMillis > 0) delay(settleMillis)
        } finally {
            job.cancel()
        }
        emitted
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
