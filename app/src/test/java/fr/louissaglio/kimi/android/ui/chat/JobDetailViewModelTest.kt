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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var subagentStore: SubagentStreamStore

    @Volatile
    private var serverSocket: WebSocket? = null
    private val clientFrames = CopyOnWriteArrayList<String>()
    private val requests = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
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
        assertEquals("tick 119", state.outputPreview)
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
            SessionEvent.SubagentSpawned("agent-3", "explore", "tool_1", "doc-verify", runInBackground = false),
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
            SessionEvent.SubagentSpawned("agent-3", "explore", "tool_1", "doc-verify", runInBackground = false),
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
