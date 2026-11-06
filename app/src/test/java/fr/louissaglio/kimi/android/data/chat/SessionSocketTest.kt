package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.ackFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.agentFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.appendOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.approvalEntity
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.approvalFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.compactionCompletedOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.compactionStartedOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.compactionUndoOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.opsFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.promptOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.questionEntity
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.questionFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.resetFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.stepOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskListFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskOutputFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskResponseFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskState
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.textOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.todoFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.toolOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.turnOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.undoOp
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.usageFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.userMessage
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.workChangedFrame
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.webSocketUpgradeResponse
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * transcript frames folded into domain events, the `agent`/`usage` state frames
 * (ticket 177), and the connection machinery
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
    fun `the subscribe-time task list, agent, usage and todo frames seed the state, the rest and the reset emit nothing`() = runBlocking {
        // The verbatim subscribe order: per-agent state frames, transcript.reset, ack.
        // The running-task list (ticket 179), the agent/usage pair (ticket 177) and the
        // todo list (ticket 178) are the chat's seed; the reset's items are always
        // empty — it must neither render nor clear.
        val emitted = runSocketEvents(
            """{"type":"task.list","session_id":"session_1","agent_id":"main","tasks":[]}""",
            agentFrame(model = "deepseek/deepseek-v4-flash", thinking = "max", permissionMode = "auto"),
            usageFrame(22433, 1_000_000),
            """{"type":"goal","session_id":"session_1","agent_id":"main","event_created_at":"2026-11-04T19:25:52.679Z"}""",
            todoFrame("List files" to "in_progress", "Reply done" to "pending"),
            resetFrame(seq = 18),
            ackFrame(),
            opsFrame(promptOp("msg_1", "running"), seq = 19),
            eventCount = 9,
            settleMillis = 100,
        )

        assertEquals(
            listOf(
                SocketEvent.Connected,
                SocketEvent.Event(SessionEvent.TaskListed(emptyList())),
                SocketEvent.Event(SessionEvent.SessionModelUpdated("deepseek/deepseek-v4-flash", "max")),
                SocketEvent.Event(SessionEvent.SessionPermissionUpdated("auto")),
                SocketEvent.Event(SessionEvent.AgentBusyUpdated(running = false)),
                SocketEvent.Event(SessionEvent.ContextUsageUpdated(ContextUsage(22433, 1_000_000))),
                SocketEvent.Event(
                    SessionEvent.TodoUpdated(
                        listOf(Todo("List files", Todo.Status.InProgress), Todo("Reply done", Todo.Status.Pending)),
                    ),
                ),
                // The accepted ack resets the approval and question queues (tickets
                // 171/172) — empty here.
                SocketEvent.Event(SessionEvent.ApprovalsReset(emptyList())),
                SocketEvent.Event(SessionEvent.QuestionsReset(emptyList())),
                SocketEvent.Event(SessionEvent.PromptUpdated("msg_1", PromptStatus.Running)),
            ),
            emitted,
        )
    }

    // --- agent / usage state frames (ticket 177) ---------------------------------------

    @Test
    fun `a model-less agent frame names no binding and a running one reports busy`() = runBlocking {
        val emitted = runSocketEvents(
            agentFrame(status = "running", model = null, thinking = null, permissionMode = "manual"),
            eventCount = 2,
            settleMillis = 100,
        )

        assertEquals(
            listOf(
                SessionEvent.SessionPermissionUpdated("manual"),
                SessionEvent.AgentBusyUpdated(running = true),
            ),
            emitted.sessionEvents(),
        )
    }

    @Test
    fun `usage frames without a positive limit emit nothing`() = runBlocking {
        val emitted = runSocketEvents(
            usageFrame(500, maxContextTokens = null),
            usageFrame(500, maxContextTokens = 0),
            usageFrame(600, 262_144),
            eventCount = 1,
            settleMillis = 100,
        )

        assertEquals(
            listOf(SessionEvent.ContextUsageUpdated(ContextUsage(600, 262_144))),
            emitted.sessionEvents(),
        )
    }

    @Test
    fun `a child's agent frame is emitted tagged, its usage and foreign sessions' are not`() = runBlocking {
        // Ticket 182: a child's `agent` frame (part of its announcement) routes to the
        // sub-agent store by its agent id — isSubagentScoped keeps it off the main chat.
        // A child's usage frame is dropped (the page has no context ring), and a
        // foreign session's frames never surface.
        val emitted = runSocketEvents(
            agentFrame(status = "running", model = "kimi-code/k3", thinking = "high", agentId = "agent-0"),
            usageFrame(10, 1_000, agentId = "agent-0"),
            agentFrame(status = "running", model = "kimi-code/k3", sessionId = "session_2"),
            usageFrame(700, 262_144),
            eventCount = 4,
            settleMillis = 150,
        )

        val events = emitted.sessionEvents()
        assertEquals(
            listOf(
                SessionEvent.SessionModelUpdated("kimi-code/k3", "high", agentId = "agent-0"),
                SessionEvent.SessionPermissionUpdated("auto", agentId = "agent-0"),
                SessionEvent.AgentBusyUpdated(running = true, agentId = "agent-0"),
                SessionEvent.ContextUsageUpdated(ContextUsage(700, 262_144)),
            ),
            events,
        )
        assertTrue(events.take(3).all { it.isSubagentScoped() })
        assertFalse(events.last().isSubagentScoped())
    }

    @Test
    fun `the agent frame mapping treats blank values and unknown statuses as nothing said`() {
        assertEquals(
            emptyList<SessionEvent>(),
            AgentStateFrame(status = "paused", model = "", thinking = " ", permissionMode = "").toSessionEvents(),
        )
        assertEquals(
            listOf(SessionEvent.SessionModelUpdated(model = null, thinkingEffort = "low")),
            AgentStateFrame(thinking = "low").toSessionEvents(),
        )
        assertEquals(null, UsageFrame(contextTokens = null, maxContextTokens = 100).toSessionEvent())
    }

    // --- task frames and controls (ticket 179) --------------------------------------

    private fun shellTask(
        taskId: String,
        running: Boolean = true,
        outcome: TaskOutcome? = null,
        description: String = "Bash: sleep 30",
        endedAt: Instant? = null,
    ) = TaskState(
        taskId = taskId,
        kind = TaskState.Kind.Shell,
        status = if (running) TaskState.Status.Running else TaskState.Status.Ended,
        detached = true,
        description = description,
        outcome = outcome,
        startedAt = Instant.parse("2026-11-04T19:30:00.000Z"),
        endedAt = endedAt,
    )

    @Test
    fun `task list and task frames emit the full states, tagged with the owning agent`() = runBlocking {
        // Shaped after the verbatim 2.5.0 captures (docs/server-api.md, Task frames and
        // controls): a foreground shell task and a background sub-agent spawn.
        val emitted = runSocketEvents(
            taskListFrame(),
            taskFrame(
                taskState(
                    "bash-s9yny87s", detached = false, description = "Bash: sleep 30 && echo watched-ok",
                    extra = ""","parent_tool_call_id":"call_00_x","command":"sleep 30 && echo watched-ok"""",
                ),
            ),
            taskFrame(
                taskState(
                    "agent-v93x0tjv", kind = "subagent", description = "Sleep then reply bg-ok",
                    extra = ""","phase":"working","child_agent_id":"agent-1","parent_tool_call_id":"call_00_y",""" +
                        """"model":"deepseek/deepseek-v4-flash","thinking":"max"""",
                ),
            ),
            taskListFrame(taskState("bash-2"), agentId = "agent-0"),
            taskFrame(taskState("bash-9"), sessionId = "session_2"),
            eventCount = 4,
            settleMillis = 150,
        )

        assertEquals(
            listOf(
                SessionEvent.TaskListed(emptyList()),
                SessionEvent.TaskUpdated(
                    TaskState(
                        taskId = "bash-s9yny87s",
                        kind = TaskState.Kind.Shell,
                        status = TaskState.Status.Running,
                        detached = false,
                        description = "Bash: sleep 30 && echo watched-ok",
                        parentToolCallId = "call_00_x",
                        command = "sleep 30 && echo watched-ok",
                        startedAt = Instant.parse("2026-11-04T19:30:00.000Z"),
                    ),
                ),
                SessionEvent.TaskUpdated(
                    TaskState(
                        taskId = "agent-v93x0tjv",
                        kind = TaskState.Kind.Subagent,
                        status = TaskState.Status.Running,
                        detached = true,
                        description = "Sleep then reply bg-ok",
                        phase = "working",
                        childAgentId = "agent-1",
                        parentToolCallId = "call_00_y",
                        startedAt = Instant.parse("2026-11-04T19:30:00.000Z"),
                        model = "deepseek/deepseek-v4-flash",
                        thinking = "max",
                    ),
                ),
                SessionEvent.TaskListed(listOf(shellTask("bash-2")), agentId = "agent-0"),
            ),
            emitted.sessionEvents(),
        )
    }

    @Test
    fun `the task state mapping keeps the frame's vocabulary and drops unusable entries`() {
        val json = Json { ignoreUnknownKeys = true }
        fun decode(body: String) = json.decodeFromString<TaskStateDto>(body).toTaskState()

        val ended = decode(
            """{"task_id":"agent-1","kind":"subagent","status":"ended","outcome":"killed","detached":false,""" +
                """"started_at":"2026-11-04T19:30:00.000Z","ended_at":1785210118804,""" +
                """"result_summary":"","error":"Aborted by the user","state_reason":"user"}""",
        )!!
        assertEquals(TaskState.Status.Ended, ended.status)
        assertEquals(TaskOutcome.Killed, ended.outcome)
        assertEquals(Instant.ofEpochMilli(1785210118804), ended.endedAt)
        assertEquals(null, ended.resultSummary)
        assertEquals("Aborted by the user", ended.error)
        assertEquals("user", ended.stateReason)

        // Background questions and future kinds are "other"; an unknown outcome is none.
        assertEquals(TaskState.Kind.Other, decode("""{"task_id":"q","kind":"other","status":"running"}""")!!.kind)
        assertEquals(TaskState.Kind.Other, decode("""{"task_id":"q","kind":"cron","status":"running"}""")!!.kind)
        assertEquals(null, decode("""{"task_id":"t","kind":"shell","status":"ended","outcome":"vanished"}""")!!.outcome)
        // A running task carries no outcome even if the frame names one.
        assertEquals(null, decode("""{"task_id":"t","kind":"shell","status":"running","outcome":"completed"}""")!!.outcome)
        // No id or an unknown status: the entry is unusable.
        assertEquals(null, decode("""{"task_id":"","kind":"shell","status":"running"}"""))
        assertEquals(null, decode("""{"task_id":"t","kind":"shell","status":"paused"}"""))
    }

    @Test
    fun `the task record follows frames and lists, keeping ended tasks`() = runBlocking {
        runSocketEvents(
            taskListFrame(taskState("bash-1"), taskState("bash-2")),
            taskFrame(taskState("bash-1", status = "ended", outcome = "completed", endedAt = "2026-11-04T19:31:00.000Z")),
            // A later list: bash-2 ended unseen and leaves the record; bash-1's final
            // state stays.
            taskListFrame(taskState("bash-3")),
            eventCount = 3,
        )

        assertEquals(
            shellTask("bash-1", running = false, outcome = TaskOutcome.Completed, endedAt = Instant.parse("2026-11-04T19:31:00.000Z")),
            socket.observeTask("session_1", "main", "bash-1").first(),
        )
        assertEquals(null, socket.observeTask("session_1", "main", "bash-2").first())
        assertEquals(shellTask("bash-3"), socket.observeTask("session_1", "main", "bash-3").first())
        // Keyed by owning agent.
        assertEquals(null, socket.observeTask("session_1", "agent-0", "bash-3").first())
    }

    /** Opens one interactive connection: [onControl] answers every client frame that
     * carries a `request_id`. Returns the emitted events and the collection job. */
    private suspend fun CoroutineScope.openInteractive(
        clientFrames: MutableList<String>,
        onControl: (WebSocket, String) -> Unit,
    ): Pair<CopyOnWriteArrayList<SocketEvent>, kotlinx.coroutines.Job> {
        server.enqueue(
            webSocketUpgradeResponse(
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        clientFrames += text
                        val requestId = json.parseToJsonElement(text).jsonObject["request_id"]?.jsonPrimitive?.content
                        if (requestId != null) onControl(webSocket, requestId)
                    }
                },
            ),
        )
        val emitted = CopyOnWriteArrayList<SocketEvent>()
        val job = launch { socket.observe(settings(), "session_1").collect { emitted += it } }
        awaitCondition { emitted.contains(SocketEvent.Connected) && clientFrames.isNotEmpty() }
        return emitted to job
    }

    @Test
    fun `a task control is a flat frame and its code-0 reply's state is emitted before it returns`() = runBlocking {
        val clientFrames = CopyOnWriteArrayList<String>()
        coroutineScope {
            val (emitted, job) = openInteractive(clientFrames) { webSocket, requestId ->
                webSocket.send(
                    taskResponseFrame(
                        requestId,
                        task = taskState("bash-1", status = "ended", outcome = "killed", endedAt = "2026-11-04T19:31:00.000Z"),
                    ),
                )
            }
            try {
                val result = socket.sendTaskControl("session_1", TaskControl.Stop, "main", "bash-1")

                val killed = shellTask("bash-1", running = false, outcome = TaskOutcome.Killed, endedAt = Instant.parse("2026-11-04T19:31:00.000Z"))
                assertEquals(killed, result.getOrThrow())
                // Applied like any task frame — already delivered when the call returns.
                assertEquals(listOf(SessionEvent.TaskUpdated(killed)), emitted.sessionEvents())
                assertEquals(killed, socket.observeTask("session_1", "main", "bash-1").first())
                assertEquals(
                    """{"type":"task.stop","request_id":"t_1","session_id":"session_1","agent_id":"main","task_id":"bash-1"}""",
                    clientFrames.last(),
                )
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun `an error reply fails with its code and applies nothing`() = runBlocking {
        val clientFrames = CopyOnWriteArrayList<String>()
        coroutineScope {
            val (emitted, job) = openInteractive(clientFrames) { webSocket, requestId ->
                webSocket.send(
                    taskResponseFrame(
                        requestId, code = 40933, msg = "task cannot be moved to the background",
                        task = taskState("agent-1", kind = "subagent", detached = false),
                    ),
                )
            }
            try {
                val error = socket.sendTaskControl("session_1", TaskControl.Background, "main", "agent-1").exceptionOrNull()

                assertEquals(DataError.Api(40933, "task cannot be moved to the background"), error)
                assertTrue(clientFrames.last().startsWith("""{"type":"task.background","request_id":"""))
                delay(100)
                assertEquals(emptyList<SessionEvent>(), emitted.sessionEvents())
                assertEquals(null, socket.observeTask("session_1", "main", "agent-1").first())
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun `a control without an open connection or whose connection drops fails as a network error`() = runBlocking {
        val noSocket = socket.sendTaskControl("session_1", TaskControl.Stop, "main", "bash-1").exceptionOrNull()
        assertTrue(noSocket is DataError.Network)

        val clientFrames = CopyOnWriteArrayList<String>()
        coroutineScope {
            // The server closes the socket instead of replying.
            val (_, job) = openInteractive(clientFrames) { webSocket, _ -> webSocket.close(1000, "bye") }
            try {
                val dropped = withTimeout(5_000) {
                    socket.sendTaskControl("session_1", TaskControl.Stop, "main", "bash-1")
                }.exceptionOrNull()
                assertTrue(dropped is DataError.Network)
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun `a reply for an unknown request id is ignored`() = runBlocking {
        val emitted = runSocketEvents(
            taskResponseFrame("t_404", task = taskState("bash-1")),
            taskFrame(taskState("bash-2")),
            eventCount = 1,
            settleMillis = 100,
        )

        assertEquals(listOf(SessionEvent.TaskUpdated(shellTask("bash-2"))), emitted.sessionEvents())
    }

    // --- task output watch (ticket 180) -----------------------------------------------

    @Test
    fun `task watch is a flat frame with its offsets and the chunks that follow reach its watcher`() = runBlocking {
        val clientFrames = CopyOnWriteArrayList<String>()
        coroutineScope {
            val (emitted, job) = openInteractive(clientFrames) { webSocket, requestId ->
                // The verbatim order: the reply with the state, then the tail chunk,
                // then a live chunk; another task's chunk is not this watcher's.
                webSocket.send(taskResponseFrame(requestId, task = taskState("bash-1")))
                webSocket.send(taskOutputFrame("bash-1", offset = 0, text = ""))
                webSocket.send(taskOutputFrame("bash-2", offset = 0, text = "other"))
                webSocket.send(taskOutputFrame("bash-1", offset = 0, text = "tick ✓\n"))
            }
            try {
                val chunks = CopyOnWriteArrayList<TaskOutputChunk>()
                val watcher = launch { socket.observeTaskOutput("session_1", "main", "bash-1").collect { chunks += it } }
                awaitCondition { socket.observeConnection("session_1").value != null }
                delay(50) // let the watcher subscribe

                val result = socket.watchTask("session_1", "main", "bash-1", sinceOffset = 42, tailBytes = 32768)

                assertEquals(shellTask("bash-1"), result.getOrThrow())
                assertEquals(
                    """{"type":"task.watch","request_id":"t_1","session_id":"session_1","agent_id":"main",""" +
                        """"task_id":"bash-1","since_offset":42,"tail_bytes":32768}""",
                    clientFrames.last(),
                )
                // The reply's state applies like any task frame.
                assertEquals(listOf(SessionEvent.TaskUpdated(shellTask("bash-1"))), emitted.sessionEvents())
                awaitCondition { chunks.size == 2 }
                assertEquals(
                    listOf(TaskOutputChunk("main", "bash-1", 0, ""), TaskOutputChunk("main", "bash-1", 0, "tick ✓\n")),
                    chunks,
                )
                watcher.cancel()
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun `a first watch omits since_offset and unwatch is a flat fire-and-forget frame`() = runBlocking {
        assertFalse(socket.unwatchTask("session_1", "main", "bash-1"))

        val clientFrames = CopyOnWriteArrayList<String>()
        coroutineScope {
            val (_, job) = openInteractive(clientFrames) { webSocket, requestId ->
                webSocket.send(taskResponseFrame(requestId, task = taskState("bash-1")))
            }
            try {
                socket.watchTask("session_1", "main", "bash-1", sinceOffset = null, tailBytes = 32768).getOrThrow()
                assertTrue(clientFrames.last().endsWith(""""task_id":"bash-1","tail_bytes":32768}"""))

                assertTrue(socket.unwatchTask("session_1", "main", "bash-1"))
                awaitCondition { clientFrames.last().contains("task.unwatch") }
                assertEquals(
                    """{"type":"task.unwatch","request_id":"t_2","session_id":"session_1","agent_id":"main","task_id":"bash-1"}""",
                    clientFrames.last(),
                )
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun `a watch error reply fails with its code`() = runBlocking {
        val clientFrames = CopyOnWriteArrayList<String>()
        coroutineScope {
            val (_, job) = openInteractive(clientFrames) { webSocket, requestId ->
                webSocket.send(taskResponseFrame(requestId, code = 50003, msg = "task output unreadable"))
            }
            try {
                val error = socket.watchTask("session_1", "main", "bash-1", null, 32768).exceptionOrNull()
                assertEquals(DataError.Api(50003, "task output unreadable"), error)
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun `the open connection id is set while a connection is open and cleared when it drops`() = runBlocking {
        assertEquals(null, socket.observeConnection("session_1").value)
        val clientFrames = CopyOnWriteArrayList<String>()
        coroutineScope {
            val (_, job) = openInteractive(clientFrames) { _, _ -> }
            val id = socket.observeConnection("session_1").value
            assertTrue(id != null)
            job.cancel()
            job.join()
            assertEquals(null, socket.observeConnection("session_1").value)
        }
    }

    // --- todo state frames (ticket 178) ----------------------------------------------

    @Test
    fun `every todo frame emits the full list, tagged with its agent, and an empty one clears`() = runBlocking {
        // A child's list is tagged with its id (the sub-agent store's, never the chat's);
        // a foreign session's frame is dropped like every other session frame.
        val emitted = runSocketEvents(
            todoFrame("Read a file" to "done"),
            todoFrame("Explore" to "in_progress", agentId = "agent-0"),
            todoFrame("Not ours" to "pending", sessionId = "session_2"),
            todoFrame(),
            eventCount = 3,
            settleMillis = 150,
        )

        assertEquals(
            listOf(
                SessionEvent.TodoUpdated(listOf(Todo("Read a file", Todo.Status.Done))),
                SessionEvent.TodoUpdated(listOf(Todo("Explore", Todo.Status.InProgress)), agentId = "agent-0"),
                SessionEvent.TodoUpdated(emptyList()),
            ),
            emitted.sessionEvents(),
        )
    }

    @Test
    fun `the todo frame mapping degrades unknown statuses to pending and skips untitled items`() {
        val frame = Json { ignoreUnknownKeys = true }.decodeFromString<TodoFrame>(
            """{"type":"todo","agent_id":"main","items":[""" +
                """{"title":"write","status":"done"},{"title":"wire","status":"in_progress"},""" +
                """{"title":"ship","status":"pending"},{"title":"x","status":"blocked"},{"title":"y"},""" +
                """{"status":"done"},{"title":"  ","status":"done"}]}""",
        )

        assertEquals(
            listOf(
                Todo("write", Todo.Status.Done),
                Todo("wire", Todo.Status.InProgress),
                Todo("ship", Todo.Status.Pending),
                Todo("x", Todo.Status.Pending),
                Todo("y", Todo.Status.Pending),
            ),
            frame.toTodos(),
        )
    }

    // --- transcript.ops → domain events ------------------------------------------------

    // --- interaction frames (ticket 171) ----------------------------------------------

    @Test
    fun `a pending approval interaction frame requests the card and every ended outcome resolves it`() = runBlocking {
        val emitted = runSocketEvents(
            approvalFrame("approval_1", command = "rm -rf /path/to/project/target"),
            // No tool_input_display: the action is the summary; a sub-agent asks.
            approvalFrame("approval_2", toolName = "Write", action = "Writing /tmp/a.txt", command = null, agentId = "agent-0"),
            approvalFrame("approval_1", status = "ended", outcome = "approved"),
            approvalFrame("approval_2", status = "ended", outcome = "rejected"),
            approvalFrame("approval_3", status = "ended", outcome = "cancelled"),
            eventCount = 5,
            settleMillis = 100,
        )

        assertEquals(
            listOf(
                SessionEvent.ApprovalRequested("approval_1", "Bash", "rm -rf /path/to/project/target"),
                SessionEvent.ApprovalRequested("approval_2", "Write", "Writing /tmp/a.txt", agentId = "agent-0"),
                SessionEvent.ApprovalResolved("approval_1"),
                SessionEvent.ApprovalResolved("approval_2"),
                SessionEvent.ApprovalResolved("approval_3"),
            ),
            emitted.sessionEvents(),
        )
    }

    @Test
    fun `foreign sessions, unknown kinds and undecodable requests emit nothing`() = runBlocking {
        val emitted = runSocketEvents(
            approvalFrame("approval_9", sessionId = "session_2"),
            questionFrame("question_9", sessionId = "session_2"),
            """{"type":"interaction","session_id":"session_1","agent_id":"main","interaction_id":"approval_8",""" +
                """"status":"pending","kind":"approval","request":{"tool_name":42}}""",
            """{"type":"interaction","session_id":"session_1","agent_id":"main","interaction_id":"question_8",""" +
                """"status":"pending","kind":"question","request":{"questions":"none"}}""",
            """{"type":"interaction","session_id":"session_1","agent_id":"main","interaction_id":"x_1",""" +
                """"status":"pending","kind":"survey","request":{}}""",
            approvalFrame("approval_1"),
            eventCount = 1,
            settleMillis = 150,
        )

        assertEquals(listOf(SessionEvent.ApprovalRequested("approval_1", "Bash", "echo hi")), emitted.sessionEvents())
    }

    @Test
    fun `a pending question interaction frame requests the wizard and every ended outcome resolves it`() = runBlocking {
        // The verbatim 2.4.0/2.5.0 pending question (docs/server-api.md, Interactions):
        // card id = interaction id, the row link = tool_call_id, the asker = agent_id.
        val multi = """[{"id":"q_0","question":"Colors?","header":"Palette","options":[{"id":"opt_0_0","label":"red"}],""" +
            """"allow_other":false,"multi_select":true},{"id":"q_1","question":"Mode?","header":"Mode",""" +
            """"options":[{"id":"opt_1_0","label":"fast"}],"allow_other":false,"multi_select":false},""" +
            """{"id":"q_2","question":"Confirm?","header":"Confirm","options":[]}]"""
        val emitted = runSocketEvents(
            questionFrame("question_10025aea", toolCallId = "call_00_IMrajNNAe1wdV1kRD3Zq6099"),
            questionFrame("question_2", questions = multi, agentId = "agent-1"),
            questionFrame("question_10025aea", status = "ended", outcome = "answered"),
            questionFrame("question_2", status = "ended", outcome = "dismissed"),
            questionFrame("question_3", status = "ended", outcome = "cancelled"),
            eventCount = 5,
            settleMillis = 100,
        )

        val events = emitted.sessionEvents()
        assertEquals(
            SessionEvent.QuestionRequested(
                PendingQuestion(
                    questionId = "question_10025aea",
                    toolCallId = "call_00_IMrajNNAe1wdV1kRD3Zq6099",
                    items = listOf(
                        PendingQuestion.Item(
                            id = "q_0",
                            header = "Dessert",
                            text = "Cake or pie?",
                            options = listOf(
                                PendingQuestion.Option("opt_0_0", "Cake", ""),
                                PendingQuestion.Option("opt_0_1", "Pie", ""),
                            ),
                            allowOther = true,
                        ),
                    ),
                ),
            ),
            events[0],
        )
        val subagentQuestion = (events[1] as SessionEvent.QuestionRequested).question
        assertEquals("agent-1", subagentQuestion.agentId)
        // multi_select decodes in both forms; absent = false.
        assertEquals(listOf(true, false, false), subagentQuestion.items.map { it.multiSelect })
        assertEquals(
            listOf(
                SessionEvent.QuestionResolved("question_10025aea"),
                SessionEvent.QuestionResolved("question_2"),
                SessionEvent.QuestionResolved("question_3"),
            ),
            events.drop(2),
        )
    }

    @Test
    fun `an accepted ack resets the approvals and questions to the reset snapshot's pending ones before the re-sent frames`() = runBlocking {
        // The verbatim subscribe order (docs/server-api.md, Interactions): the reset's
        // snapshot holds the full entity list (ended included), then the ack, then the
        // still-pending ones re-sent as standalone frames. A child's reset carrying the
        // same session-wide list must not double it.
        val interactions = "[" + listOf(
            approvalEntity("approval_0", state = "approved"),
            approvalEntity("approval_1", command = "rm -rf target"),
            questionEntity("question_0", state = "answered"),
            questionEntity("question_1"),
        ).joinToString(",") + "]"
        val emitted = runSocketEvents(
            resetFrame(seq = 18, interactions = interactions),
            resetFrame(agentId = "agent-0", seq = 0, interactions = interactions),
            ackFrame(),
            approvalFrame("approval_1", command = "rm -rf target"),
            questionFrame("question_1"),
            eventCount = 4,
            settleMillis = 100,
        )

        val events = emitted.sessionEvents()
        assertEquals(
            listOf(
                SessionEvent.ApprovalsReset(listOf(PendingApprovalRequest("approval_1", "Bash", "rm -rf target"))),
                SessionEvent.ApprovalRequested("approval_1", "Bash", "rm -rf target"),
            ),
            events.filter { it is SessionEvent.ApprovalsReset || it is SessionEvent.ApprovalRequested },
        )
        // Ticket 172: the question queue resets the same way — the answered entity
        // stays out, the child's duplicate list does not double the pending one, and
        // the re-sent frame follows the ack.
        val reset = events.filterIsInstance<SessionEvent.QuestionsReset>().single()
        assertEquals(listOf("question_1"), reset.pending.map { it.questionId })
        assertEquals("call_00_question_1", reset.pending.single().toolCallId)
        assertEquals("Cake or pie?", reset.pending.single().items.single().text)
        assertTrue(events.indexOf(reset) < events.indexOfFirst { it is SessionEvent.QuestionRequested })
        assertEquals("question_1", (events.last() as SessionEvent.QuestionRequested).question.questionId)
    }

    @Test
    fun `a resumed subscribe without a reset resets the approvals and questions to none, a refused or foreign ack resets nothing`() = runBlocking {
        // A resume replays ops instead of a reset (no snapshot): the queue empties and
        // the re-sent pending frames rebuild it. A refused or foreign ack resets nothing.
        val emitted = runSocketEvents(
            """{"type":"ack","id":"c_1","code":1,"msg":"invalid subscribe_v2 payload","payload":null}""",
            ackFrame(accepted = emptyList(), notFound = listOf("session_other")),
            ackFrame(id = "u1"),
            ackFrame(),
            approvalFrame("approval_1"),
            eventCount = 3,
            settleMillis = 100,
        )

        assertEquals(
            listOf(
                SessionEvent.ApprovalsReset(emptyList()),
                SessionEvent.QuestionsReset(emptyList()),
                SessionEvent.ApprovalRequested("approval_1", "Bash", "echo hi"),
            ),
            emitted.sessionEvents(),
        )
    }

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
    fun `a compaction streams as its markers and this session's work_changed frames`() = runBlocking {
        // Ticket 181, the 2.5.0 capture order: started marker, busy, the undo splice,
        // completed marker, idle. Another session's work_changed and a payload without
        // `busy` emit nothing.
        val emitted = runSocketEvents(
            opsFrame(compactionStartedOp(), seq = 32),
            workChangedFrame(busy = true),
            opsFrame(compactionUndoOp(kept = arrayOf(userMessage("msg_1", "kept"))), seq = 33),
            opsFrame(compactionCompletedOp(summary = "Working summary."), seq = 34),
            workChangedFrame(busy = false, sessionId = "session_2"),
            """{"type":"event.session.work_changed","session_id":"session_1","payload":{"type":"event.session.work_changed"}}""",
            workChangedFrame(busy = false),
            eventCount = 5,
            settleMillis = 200,
        )

        val events = emitted.sessionEvents()
        assertEquals(5, events.size)
        assertEquals(SessionEvent.CompactionStarted("manual"), events[0])
        assertEquals(SessionEvent.SessionWorkChanged(busy = true), events[1])
        assertEquals(listOf("msg_1", ""), (events[2] as SessionEvent.ContextSpliced).messages.map { it.id })
        assertEquals("Working summary.", (events[3] as SessionEvent.CompactionCompleted).summary)
        assertEquals(SessionEvent.SessionWorkChanged(busy = false), events[4])
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

    // --- resume watermarks (ticket 176) ------------------------------------------------

    /** The cold start's main transcript page: a running turn streaming "Hel". */
    private fun runningPage(seq: Long?) = TranscriptPage(
        agentId = MAIN_AGENT_ID,
        turns = listOf(
            TranscriptTurn(
                id = "t1", ordinal = 1, state = TranscriptState.Running, prompt = null,
                steps = listOf(
                    TranscriptStep(
                        "t1.1", TranscriptState.Running,
                        listOf(TranscriptFrame.Text("t1.1.f1", TranscriptFrame.Text.Role.Assistant, "Hel")),
                    ),
                ),
            ),
        ),
        hasMore = false,
        seq = seq,
    )

    @Test
    fun `a seeded transcript page makes the subscribe resume from its seq and the fold continue its text`() = runBlocking {
        socket.seedTranscript("session_1", runningPage(seq = 42))
        val clientFrames = CopyOnWriteArrayList<String>()

        val emitted = runSocketEvents(
            // The replay after 42: the turn header unchanged, the next chunk at the
            // seeded length.
            opsFrame(turnOp("t1", "running"), seq = 43),
            opsFrame(appendOp("t1.1.f1", 3, "lo"), seq = 44),
            eventCount = 1,
            settleMillis = 100,
            clientFrames = clientFrames,
        )

        assertEquals(
            """{"type":"subscribe_v2","id":"c_1","payload":{"session_id":"session_1","transcript":{"*":"delta"},""" +
                """"transcript_since":{"main":42}}}""",
            clientFrames.first(),
        )
        // No TurnStarted (the seeded turn is running already), no gap drop.
        assertEquals(listOf(SessionEvent.AssistantDelta("lo")), emitted.sessionEvents())
        assertEquals(44L, socket.watermark("session_1", MAIN_AGENT_ID))
    }

    @Test
    fun `every agent's batches advance its own watermark`() = runBlocking {
        runSocketEvents(
            opsFrame(turnOp("t1", "running"), seq = 9),
            opsFrame(turnOp("t0", "running"), agentId = "agent-3", seq = 4),
            eventCount = 2,
        )

        assertEquals(9L, socket.watermark("session_1", MAIN_AGENT_ID))
        assertEquals(4L, socket.watermark("session_1", "agent-3"))
        assertEquals(null, socket.watermark("session_2", MAIN_AGENT_ID))
    }

    @Test
    fun `a reset answering a resumed agent is the stale signal and restarts its fold`() = runBlocking {
        socket.seedTranscript("session_1", runningPage(seq = 42))

        val emitted = runSocketEvents(
            // The journal could not cover 42: a fresh baseline instead of the replay.
            resetFrame(agentId = MAIN_AGENT_ID, seq = 77),
            // The fold restarted: an append to the seeded frame is now unknown.
            opsFrame(appendOp("t1.1.f1", 3, "lo"), seq = 78),
            eventCount = 1,
            settleMillis = 150,
        )

        assertEquals(listOf(SessionEvent.TranscriptStale(MAIN_AGENT_ID)), emitted.sessionEvents())
        assertEquals(78L, socket.watermark("session_1", MAIN_AGENT_ID))
    }

    @Test
    fun `a baseline reset of an agent subscribed without a watermark is no stale signal`() = runBlocking {
        socket.seedTranscript("session_1", runningPage(seq = 42))

        val emitted = runSocketEvents(
            // A child never seen: its baseline (seq 0) — not a resume failure.
            resetFrame(agentId = "agent-0", seq = 0),
            opsFrame(appendOp("t1.1.f1", 3, "lo"), seq = 43),
            eventCount = 1,
            settleMillis = 100,
        )

        assertEquals(listOf(SessionEvent.AssistantDelta("lo")), emitted.sessionEvents())
        assertEquals(0L, socket.watermark("session_1", "agent-0"))
    }

    @Test
    fun `a seeded page without seq drops the watermark`() = runBlocking {
        socket.seedTranscript("session_1", runningPage(seq = 42))
        socket.seedTranscript("session_1", runningPage(seq = null))
        val clientFrames = CopyOnWriteArrayList<String>()

        runSocketEvents(opsFrame(appendOp("t1.1.f1", 3, "lo"), seq = 1), eventCount = 1, clientFrames = clientFrames)

        assertFalse(clientFrames.first(), "transcript_since" in clientFrames.first())
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
        // subscription does not survive) resuming from the batch already folded
        // (ticket 176), and the Reconnected the collector's resume check rides on.
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
            listOf(
                """{"type":"subscribe_v2","id":"c_2","payload":{"session_id":"session_1","transcript":{"*":"delta"},""" +
                    """"transcript_since":{"main":1}}}""",
            ),
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
