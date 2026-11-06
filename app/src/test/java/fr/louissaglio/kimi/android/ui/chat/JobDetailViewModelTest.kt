package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.TaskOutcome
import fr.louissaglio.kimi.android.data.chat.TaskState
import fr.louissaglio.kimi.android.data.chat.TranscriptPage
import fr.louissaglio.kimi.android.data.chat.TranscriptState
import fr.louissaglio.kimi.android.data.chat.TranscriptTaskOrigin
import fr.louissaglio.kimi.android.data.chat.TranscriptTurn
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.navigation.JobDetail
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.ackFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskListFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskOutputFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskResponseFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskState
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import fr.louissaglio.kimi.android.testutil.testChatRepository
import fr.louissaglio.kimi.android.testutil.testSubagentStreamStore
import fr.louissaglio.kimi.android.testutil.webSocketUpgradeResponse
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The job screen on task frames (ticket 179): the frame-backed mode reads the session
 * socket's task record (a real socket on a [MockWebServer] — the collection a chat
 * would hold is started by the test) and stops with `task.stop`; the store-backed mode
 * (ticket 098) reads the sub-agent store's records. No REST request is ever made.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobDetailViewModelTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private lateinit var repository: ChatRepository
    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var subagentStore: SubagentStreamStore

    @Volatile
    private var serverSocket: WebSocket? = null
    private val clientFrames = CopyOnWriteArrayList<String>()
    private val requests = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target.orEmpty()
                requests += path
                return if (path.startsWith("/api/v1/ws")) {
                    webSocketUpgradeResponse(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                serverSocket = webSocket
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                clientFrames += text
                            }
                        },
                    )
                } else {
                    MockResponse(code = 404)
                }
            }
        }
        val dataStore = FakePreferencesDataStore(
            preferencesOf(stringPreferencesKey("server_url") to server.url("/").toString()),
        )
        repository = testChatRepository(dataStore)
        settingsRepository = SettingsRepository(dataStore, appScope)
        subagentStore = testSubagentStreamStore(
            streamCoalesceWindowMillis = 0,
            chatRepository = repository,
            settingsRepository = settingsRepository,
        )
        // The production shape: the chat (in the back stack) collects the session
        // socket, so the store's keep-alive must never start from these tests'
        // pageOpened calls.
        subagentStore.chatCollectionStarted("session_1")
    }

    @After
    fun tearDown() {
        appScope.cancel()
        server.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(route: JobDetail = JobDetail("session_1", "bash-1")) =
        JobDetailViewModel(repository, subagentStore, route)

    private fun collectEffects(viewModel: JobDetailViewModel): CopyOnWriteArrayList<JobDetailViewModel.Effect> {
        val effects = CopyOnWriteArrayList<JobDetailViewModel.Effect>()
        appScope.launch { viewModel.effects.collect { effects += it } }
        return effects
    }

    /** The session socket collection a chat would hold; returns the server end. */
    private suspend fun connect(): Pair<CoroutineJob, WebSocket> {
        val job = appScope.launch {
            repository.observeSession(settingsRepository.current(), "session_1").collect {}
        }
        awaitCondition { serverSocket != null }
        val socket = serverSocket!!
        socket.send(ackFrame())
        return job to socket
    }

    private fun sentStops() = clientFrames
        .map { Json.parseToJsonElement(it).jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == "task.stop" }

    @Test
    fun `the task frames populate the state, the latest one wins`() = runBlocking {
        val (_, socket) = connect()
        val viewModel = newViewModel(JobDetail("session_1", "bash-1", description = "row description", command = "row command"))

        socket.send(
            taskListFrame(
                taskState(
                    "bash-1",
                    description = "Bash: for i in …",
                    extra = ""","command":"for i in …"""",
                ),
            ),
        )
        awaitState(viewModel.uiState) { it.status == Job.Status.Running }
        var state = viewModel.uiState.value
        // The server is authoritative over the row seeds.
        assertEquals("Bash: for i in …", state.description)
        assertEquals("for i in …", state.command)
        assertEquals(Instant.parse("2026-11-04T19:30:00.000Z"), state.startedAt)
        assertNull(state.completedAt)
        assertFalse(state.isLoading)

        socket.send(
            taskFrame(
                taskState(
                    "bash-1",
                    status = "ended",
                    outcome = "completed",
                    description = "Bash: for i in …",
                    endedAt = "2026-11-04T19:32:00.000Z",
                    extra = ""","command":"for i in …","result_summary":"tick 119"""",
                ),
            ),
        )
        awaitState(viewModel.uiState) { it.status == Job.Status.Completed }
        state = viewModel.uiState.value
        assertEquals(Instant.parse("2026-11-04T19:32:00.000Z"), state.completedAt)
        assertEquals("tick 119", state.output)
        assertTrue(requests.none { "/tasks" in it })
    }

    @Test
    fun `the route seeds render before any frame and a frame without them keeps them`() = runBlocking {
        val (_, socket) = connect()
        val viewModel = newViewModel(
            JobDetail("session_1", "bash-1", description = "count ticks", command = "for i in …"),
        )

        // Seeded synchronously — the first composition already shows them.
        assertEquals("count ticks", viewModel.uiState.value.description)
        assertEquals("for i in …", viewModel.uiState.value.command)
        assertNull(viewModel.uiState.value.status)

        socket.send(taskFrame(taskState("bash-1", description = "")))
        awaitState(viewModel.uiState) { it.status == Job.Status.Running }
        assertEquals("count ticks", viewModel.uiState.value.description)
        assertEquals("for i in …", viewModel.uiState.value.command)
    }

    @Test
    fun `the route's budget and timed-out flag seed the state and a timed_out end sets the flag`() = runBlocking {
        val (_, socket) = connect()
        val seeded = newViewModel(
            JobDetail("session_1", "bash-1", budgetSeconds = 30, budgetIsDefault = false, timedOut = true),
        )
        assertEquals(30, seeded.uiState.value.budgetSeconds)
        assertTrue(seeded.uiState.value.timedOut)

        val live = newViewModel(JobDetail("session_1", "bash-1", budgetSeconds = 30))
        socket.send(taskFrame(taskState("bash-1", status = "ended", outcome = "timed_out", endedAt = "2026-11-04T19:30:30.000Z")))

        awaitState(live.uiState) { it.status == Job.Status.Failed }
        assertTrue(live.uiState.value.timedOut)
        assertEquals(30, live.uiState.value.budgetSeconds)
        assertFalse(live.uiState.value.budgetIsDefault)
    }

    @Test
    fun `a task the process never saw keeps the seeds with an empty status line`() = runBlocking {
        connect()
        val viewModel = newViewModel(JobDetail("session_1", "bash-gone", description = "old job"))
        delay(100)

        val state = viewModel.uiState.value
        assertNull(state.status)
        assertFalse(state.isLoading)
        assertEquals("old job", state.description)
    }

    @Test
    fun `stop sends task stop and the replied ended state flips the screen`() = runBlocking {
        val (_, socket) = connect()
        val viewModel = newViewModel()
        val effects = collectEffects(viewModel)
        socket.send(taskListFrame(taskState("bash-1")))
        awaitState(viewModel.uiState) { it.status == Job.Status.Running }

        viewModel.onStop()

        awaitCondition { sentStops().isNotEmpty() }
        val stop = sentStops().single()
        assertEquals("bash-1", stop["task_id"]?.jsonPrimitive?.content)
        assertEquals("main", stop["agent_id"]?.jsonPrimitive?.content)
        socket.send(
            taskResponseFrame(
                stop["request_id"]!!.jsonPrimitive.content,
                task = taskState("bash-1", status = "ended", outcome = "killed", endedAt = "2026-11-04T19:30:30.000Z"),
            ),
        )

        awaitState(viewModel.uiState) { it.status == Job.Status.Cancelled }
        assertTrue(effects.isEmpty())
        assertTrue(requests.none { "/tasks" in it })
    }

    @Test
    fun `an error reply to stop surfaces the error effect`() = runBlocking {
        val (_, socket) = connect()
        val viewModel = newViewModel()
        val effects = collectEffects(viewModel)
        socket.send(taskListFrame(taskState("bash-1")))
        awaitState(viewModel.uiState) { it.status == Job.Status.Running }

        viewModel.onStop()
        awaitCondition { sentStops().isNotEmpty() }
        socket.send(taskResponseFrame(sentStops().single()["request_id"]!!.jsonPrimitive.content, code = 40406, msg = "task not found"))

        awaitCondition { effects.isNotEmpty() }
        assertEquals(JobDetailViewModel.Effect.StopFailed("task not found"), effects.single())
        assertEquals(Job.Status.Running, viewModel.uiState.value.status)
    }

    // --- Output watch (ticket 180) -----------------------------------------------------

    private fun sent(type: String) = clientFrames
        .map { Json.parseToJsonElement(it).jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == type }

    private fun sentWatches() = sent("task.watch")

    private fun sentUnwatches() = sent("task.unwatch")

    private fun JsonObject.requestId() = this["request_id"]!!.jsonPrimitive.content

    /** Starts the screen on a running task and answers its watch; returns the server end. */
    private suspend fun watchingRunningTask(viewModel: JobDetailViewModel): WebSocket {
        val (_, socket) = connect()
        socket.send(taskListFrame(taskState("bash-1")))
        awaitState(viewModel.uiState) { it.status == Job.Status.Running }
        viewModel.onScreenStarted()
        awaitCondition { sentWatches().size == 1 }
        socket.send(taskResponseFrame(sentWatches().single().requestId(), task = taskState("bash-1")))
        return socket
    }

    @Test
    fun `opening a running job streams its output with one watch and no poll`() = runBlocking {
        val (_, socket) = connect()
        val viewModel = newViewModel()
        socket.send(taskListFrame(taskState("bash-1")))
        awaitState(viewModel.uiState) { it.status == Job.Status.Running }

        viewModel.onScreenStarted()

        awaitCondition { sentWatches().size == 1 }
        val watch = sentWatches().single()
        assertEquals("main", watch["agent_id"]?.jsonPrimitive?.content)
        assertEquals("bash-1", watch["task_id"]?.jsonPrimitive?.content)
        assertEquals(32768, watch["tail_bytes"]?.jsonPrimitive?.int)
        assertNull(watch["since_offset"])
        // Waiting for the first chunk.
        assertTrue(viewModel.uiState.value.isLoading)

        socket.send(taskResponseFrame(watch.requestId(), task = taskState("bash-1")))
        socket.send(taskOutputFrame("bash-1", offset = 0, text = ""))
        socket.send(taskOutputFrame("bash-1", offset = 0, text = "tick 1\n"))
        socket.send(taskOutputFrame("bash-1", offset = 7, text = "tick 2\n"))

        awaitState(viewModel.uiState) { it.output == "tick 1\ntick 2\n" }
        assertFalse(viewModel.uiState.value.isLoading)
        // Longer than the old 2 s poll: still one watch, nothing else, no REST at all.
        delay(2_500)
        assertEquals(1, sentWatches().size)
        assertTrue(sentUnwatches().isEmpty())
        assertTrue(requests.all { it.startsWith("/api/v1/ws") })
    }

    @Test
    fun `chunks append at their byte offsets, a duplicate is ignored and a gap stays visible`() = runBlocking {
        val viewModel = newViewModel()
        val socket = watchingRunningTask(viewModel)

        socket.send(taskOutputFrame("bash-1", offset = 0, text = "a\n"))
        socket.send(taskOutputFrame("bash-1", offset = 0, text = "a\n"))
        socket.send(taskOutputFrame("bash-1", offset = 10, text = "z\n"))

        awaitState(viewModel.uiState) { it.output?.endsWith("z\n") == true }
        assertEquals("a\n[… 8 bytes missing …]\nz\n", viewModel.uiState.value.output)
    }

    @Test
    fun `leaving sends task unwatch and re-entering resumes at the held byte offset`() = runBlocking {
        val viewModel = newViewModel()
        val socket = watchingRunningTask(viewModel)
        // 3 characters, 6 UTF-8 bytes.
        socket.send(taskOutputFrame("bash-1", offset = 0, text = "é✓\n"))
        awaitState(viewModel.uiState) { it.output == "é✓\n" }

        viewModel.onScreenStopped()

        awaitCondition { sentUnwatches().isNotEmpty() }
        val unwatch = sentUnwatches().single()
        assertEquals("bash-1", unwatch["task_id"]?.jsonPrimitive?.content)
        assertEquals("main", unwatch["agent_id"]?.jsonPrimitive?.content)

        viewModel.onScreenStarted()

        awaitCondition { sentWatches().size == 2 }
        assertEquals(6L, sentWatches().last()["since_offset"]?.jsonPrimitive?.long)
        // The held output stays on screen: no loading placeholder for a re-watch.
        assertFalse(viewModel.uiState.value.isLoading)
        socket.send(taskResponseFrame(sentWatches().last().requestId(), task = taskState("bash-1")))
        socket.send(taskOutputFrame("bash-1", offset = 6, text = "more\n"))
        awaitState(viewModel.uiState) { it.output == "é✓\nmore\n" }
    }

    @Test
    fun `a reconnect re-sends the watch from the held byte offset`() = runBlocking {
        val viewModel = newViewModel()
        val socket = watchingRunningTask(viewModel)
        socket.send(taskOutputFrame("bash-1", offset = 0, text = "before drop\n"))
        awaitState(viewModel.uiState) { it.output == "before drop\n" }

        serverSocket = null
        socket.close(1000, "bye")

        awaitCondition { sentWatches().size == 2 }
        assertEquals(12L, sentWatches().last()["since_offset"]?.jsonPrimitive?.long)
        val reconnected = serverSocket!!
        reconnected.send(taskResponseFrame(sentWatches().last().requestId(), task = taskState("bash-1")))
        // A chunk overlapping what is held keeps only its new bytes.
        reconnected.send(taskOutputFrame("bash-1", offset = 7, text = "drop\nafter\n"))
        awaitState(viewModel.uiState) { it.output == "before drop\nafter\n" }
    }

    @Test
    fun `a task that ends while watched keeps its final state and output and unwatches`() = runBlocking {
        val viewModel = newViewModel()
        val socket = watchingRunningTask(viewModel)
        socket.send(taskOutputFrame("bash-1", offset = 0, text = "watched-ok\n"))
        awaitState(viewModel.uiState) { it.output == "watched-ok\n" }

        socket.send(
            taskFrame(
                taskState(
                    "bash-1", status = "ended", outcome = "completed", endedAt = "2026-11-04T19:30:30.000Z",
                    extra = ""","result_summary":"summary"""",
                ),
            ),
        )

        awaitCondition { sentUnwatches().size == 1 }
        val state = viewModel.uiState.value
        assertEquals(Job.Status.Completed, state.status)
        // The streamed tail wins over the summary.
        assertEquals("watched-ok\n", state.output)

        // An ended task is never re-watched.
        viewModel.onScreenStopped()
        viewModel.onScreenStarted()
        delay(150)
        assertEquals(1, sentWatches().size)
        assertEquals(1, sentUnwatches().size)
    }

    @Test
    fun `an already-ended task is watched once for its final tail, then unwatched`() = runBlocking {
        val (_, socket) = connect()
        val viewModel = newViewModel()
        val ended = taskState("bash-1", status = "ended", outcome = "failed", endedAt = "2026-11-04T19:30:30.000Z")
        socket.send(taskFrame(ended))
        awaitState(viewModel.uiState) { it.status == Job.Status.Failed }

        viewModel.onScreenStarted()
        awaitCondition { sentWatches().size == 1 }
        socket.send(taskResponseFrame(sentWatches().single().requestId(), task = ended))
        delay(100)
        // The reply alone does not unwatch: the tail chunk follows it.
        assertTrue(sentUnwatches().isEmpty())
        socket.send(taskOutputFrame("bash-1", offset = 0, text = "exit 1\n"))

        awaitCondition { sentUnwatches().size == 1 }
        assertEquals("exit 1\n", viewModel.uiState.value.output)
        assertEquals(Job.Status.Failed, viewModel.uiState.value.status)
    }

    @Test
    fun `a watch left unanswered on an open connection is re-sent on it`() = runBlocking {
        val (_, socket) = connect()
        val viewModel = newViewModel()
        socket.send(taskListFrame(taskState("bash-1")))
        awaitState(viewModel.uiState) { it.status == Job.Status.Running }

        viewModel.onScreenStarted()
        awaitCondition { sentWatches().size == 1 }
        // No reply: the control's 15 s timeout (on the Main test clock) passes while
        // the connection stays up.
        mainDispatcher.scheduler.advanceTimeBy(15_001)

        awaitCondition { sentWatches().size == 2 }
        val retry = sentWatches().last()
        assertEquals(32768, retry["tail_bytes"]?.jsonPrimitive?.int)
        socket.send(taskResponseFrame(retry.requestId(), task = taskState("bash-1")))
        socket.send(taskOutputFrame("bash-1", offset = 0, text = "after retry\n"))
        awaitState(viewModel.uiState) { it.output == "after retry\n" }
    }

    @Test
    fun `40406 and 50003 watch replies surface the error and fall back to the summary`() = runBlocking {
        val (_, socket) = connect()
        listOf(40406 to "task not found", 50003 to "task output unreadable").forEachIndexed { index, (code, msg) ->
            val taskId = "bash-${index + 10}"
            val viewModel = newViewModel(JobDetail("session_1", taskId))
            socket.send(
                taskFrame(
                    taskState(
                        taskId, status = "ended", outcome = "completed", endedAt = "2026-11-04T19:30:30.000Z",
                        extra = ""","result_summary":"done"""",
                    ),
                ),
            )
            awaitState(viewModel.uiState) { it.status == Job.Status.Completed }
            viewModel.onScreenStarted()
            awaitCondition { sentWatches().size == index + 1 }
            socket.send(taskResponseFrame(sentWatches().last().requestId(), code = code, msg = msg))

            awaitState(viewModel.uiState) { it.outputError != null }
            val state = viewModel.uiState.value
            assertEquals(msg, state.outputError)
            assertEquals("done", state.output)
            assertFalse(state.isLoading)
            assertEquals(Job.Status.Completed, state.status)

            // A refused watch is not retried on re-entry.
            viewModel.onScreenStopped()
            viewModel.onScreenStarted()
            delay(100)
            assertEquals(index + 1, sentWatches().size)
        }
    }

    // --- Store-backed mode (ticket 098) ------------------------------------------------

    private fun ownTask(running: Boolean, outcome: TaskOutcome? = null, endedAt: Instant? = null) = TaskState(
        taskId = "bash-1",
        kind = TaskState.Kind.Shell,
        status = if (running) TaskState.Status.Running else TaskState.Status.Ended,
        detached = true,
        description = "nested bg",
        outcome = outcome,
        startedAt = Instant.ofEpochMilli(1785210101939),
        endedAt = endedAt,
    )

    private fun seedStoreJob() = runBlocking {
        subagentStore.apply(
            "session_1",
            SessionEvent.TaskUpdated(
                TaskState(
                    "agent-t3", TaskState.Kind.Subagent, TaskState.Status.Running, detached = false,
                    description = "doc-verify", phase = "working", childAgentId = "agent-3", parentToolCallId = "tool_1",
                ),
            ),
        )
        subagentStore.apply("session_1", SessionEvent.TaskUpdated(ownTask(running = true), agentId = "agent-3"))
    }

    @Test
    fun `a sub-agent-owned task is store-backed and never touches the socket`() = runBlocking {
        seedStoreJob()

        val viewModel = newViewModel(
            JobDetail(
                "session_1", "bash-1",
                budgetSeconds = 600, budgetIsDefault = true,
                description = "count ticks", command = "for i in …",
                ownerAgentId = "agent-3",
            ),
        )
        awaitState(viewModel.uiState) { it.status == Job.Status.Running }

        val state = viewModel.uiState.value
        assertTrue(state.storeBacked)
        assertEquals(Instant.ofEpochMilli(1785210101939), state.startedAt)
        // Title/Command/budget stay the route seeds.
        assertEquals("count ticks", state.description)
        assertEquals("for i in …", state.command)
        assertEquals(600, state.budgetSeconds)
        assertTrue(state.budgetIsDefault)
        assertFalse(state.isLoading)
        viewModel.onScreenStarted()
        delay(150)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the store-backed screen reflects the child's ended task frame`() = runBlocking {
        seedStoreJob()
        val viewModel = newViewModel(JobDetail("session_1", "bash-1", ownerAgentId = "agent-3"))
        awaitState(viewModel.uiState) { it.status == Job.Status.Running }

        subagentStore.apply(
            "session_1",
            SessionEvent.TaskUpdated(
                ownTask(running = false, outcome = TaskOutcome.TimedOut, endedAt = Instant.ofEpochMilli(1785210118804)),
                agentId = "agent-3",
            ),
        )

        awaitState(viewModel.uiState) { it.status == Job.Status.Failed }
        val state = viewModel.uiState.value
        // The timed_out outcome reaches the timed-out label through the store's Job.
        assertTrue(state.timedOut)
        assertEquals(Instant.ofEpochMilli(1785210118804), state.completedAt)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the store-backed mode offers no stop`() = runBlocking {
        seedStoreJob()
        val viewModel = newViewModel(JobDetail("session_1", "bash-1", ownerAgentId = "agent-3"))
        val effects = collectEffects(viewModel)
        awaitState(viewModel.uiState) { it.status == Job.Status.Running }

        viewModel.onStop()

        delay(100)
        assertEquals(0, server.requestCount)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `the store-backed mode shows the seeds when the store has no frame`() = runBlocking {
        val viewModel = newViewModel(
            JobDetail("session_1", "bash-1", description = "count ticks", ownerAgentId = "agent-3"),
        )
        delay(100)

        val state = viewModel.uiState.value
        assertNull(state.status)
        assertFalse(state.isLoading)
        assertEquals("count ticks", state.description)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a cold-opened store-backed screen shows the transcript-healed terminal status`() = runBlocking {
        // Ticket 099: no task frame was observed (process death) — the sub-agent
        // page's transcript fetch mines the completion-notification turn's task
        // origin into the record's jobs, and this screen collects the result.
        subagentStore.apply(
            "session_1",
            SessionEvent.TaskUpdated(
                TaskState(
                    "agent-t3", TaskState.Kind.Subagent, TaskState.Status.Running, detached = false,
                    description = "doc-verify", phase = "working", childAgentId = "agent-3", parentToolCallId = "tool_1",
                ),
            ),
        )
        subagentStore.applyTranscriptPage(
            "session_1", "agent-3",
            TranscriptPage(
                "agent-3",
                listOf(
                    TranscriptTurn(
                        "t0", 0, TranscriptState.Completed, null, emptyList(),
                        taskOrigin = TranscriptTaskOrigin("bash-1", TaskOutcome.Completed),
                    ),
                ),
                hasMore = false,
            ),
            keepLive = false,
        )

        val viewModel = newViewModel(
            JobDetail("session_1", "bash-1", description = "count ticks", ownerAgentId = "agent-3"),
        )
        awaitState(viewModel.uiState) { it.status == Job.Status.Completed }

        val state = viewModel.uiState.value
        // The origin carries no timestamps — unknown stays unknown.
        assertNull(state.startedAt)
        assertNull(state.completedAt)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a main-owned task id stays frame-backed`() = runBlocking {
        val (_, socket) = connect()
        val viewModel = newViewModel(JobDetail("session_1", "bash-1", ownerAgentId = "main"))
        socket.send(taskFrame(taskState("bash-1")))

        awaitState(viewModel.uiState) { it.status == Job.Status.Running }
        assertFalse(viewModel.uiState.value.storeBacked)
    }
}
