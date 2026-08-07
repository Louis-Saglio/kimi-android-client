package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.chat.BackgroundTask
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.TaskInfo
import fr.louissaglio.kimi.android.data.chat.TranscriptPage
import fr.louissaglio.kimi.android.data.chat.TranscriptState
import fr.louissaglio.kimi.android.data.chat.TranscriptTaskOrigin
import fr.louissaglio.kimi.android.data.chat.TranscriptTurn
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.navigation.JobDetail
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import fr.louissaglio.kimi.android.testutil.testChatRepository
import fr.louissaglio.kimi.android.testutil.testSubagentStreamStore
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The ViewModel runs against the real [ChatRepository] on a [MockWebServer] (the
 * SubagentViewModelTest pattern): the entry fetch, the output poll and the Stop call
 * all hit the mock. Waits run on the real clock — the poll is injected a 50 ms
 * interval and [Dispatchers.Unconfined], because delay on the test Main dispatcher
 * never fires outside runTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobDetailViewModelTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private lateinit var repository: ChatRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var subagentStore: SubagentStreamStore

    @Volatile
    private var taskBody = RUNNING_TASK_BODY

    @Volatile
    private var cancelBody = """{"code":0,"msg":"success","data":{"cancelled":true},"request_id":"r"}"""

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target.orEmpty()
                return when {
                    path.startsWith("/api/v1/sessions/session_1/tasks/") && path.endsWith(":cancel") ->
                        MockResponse(body = cancelBody)
                    path.startsWith("/api/v1/sessions/session_1/tasks/") ->
                        MockResponse(body = taskBody)
                    else -> MockResponse(code = 404)
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
        server.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(route: JobDetail = JobDetail("session_1", "bash-1")) = JobDetailViewModel(
        repository,
        settingsRepository,
        subagentStore,
        route,
        pollIntervalMillis = 50,
        pollDispatcher = Dispatchers.Unconfined,
    )

    private fun collectEffects(viewModel: JobDetailViewModel): CopyOnWriteArrayList<JobDetailViewModel.Effect> {
        val effects = CopyOnWriteArrayList<JobDetailViewModel.Effect>()
        appScope.launch { viewModel.effects.collect { effects += it } }
        return effects
    }

    @Test
    fun `the entry fetch populates the state from the output read`() = runBlocking {
        taskBody = COMPLETED_TASK_BODY

        val viewModel = newViewModel()
        awaitState(viewModel.uiState) { it.status != null }

        assertEquals(
            "/api/v1/sessions/session_1/tasks/bash-1?with_output=true",
            server.takeRequest(2, TimeUnit.SECONDS)?.target,
        )
        val state = viewModel.uiState.value
        assertEquals("tick counter loop", state.description)
        assertEquals(BackgroundTask.Status.Completed, state.status)
        assertEquals(Instant.parse("2026-08-05T10:00:00.000Z"), state.startedAt)
        assertEquals(Instant.parse("2026-08-05T10:02:00.000Z"), state.completedAt)
        assertEquals("for i in …", state.command)
        assertEquals("tick 118\ntick 119\n", state.outputPreview)
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `a running job polls while the screen is active and stops when it leaves`() = runBlocking {
        val viewModel = newViewModel()
        awaitState(viewModel.uiState) { it.status == BackgroundTask.Status.Running }
        server.takeRequest(2, TimeUnit.SECONDS) // the entry fetch

        viewModel.onScreenStarted()
        awaitCondition { server.requestCount >= 3 }

        viewModel.onScreenStopped()
        // Absorb a tick that was already in flight when the screen left…
        delay(150)
        val countAfterStop = server.requestCount
        // …then nothing more goes out (the 50 ms interval would tick ~3 times).
        delay(150)
        assertEquals(countAfterStop, server.requestCount)
    }

    @Test
    fun `a terminal job does not poll`() = runBlocking {
        taskBody = COMPLETED_TASK_BODY

        val viewModel = newViewModel()
        awaitState(viewModel.uiState) { it.status == BackgroundTask.Status.Completed }
        server.takeRequest(2, TimeUnit.SECONDS)

        viewModel.onScreenStarted()
        // The 50 ms interval would tick ~4 times were the gate open.
        delay(200)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `stop cancels the job and tolerates the 40904 already-terminal answer`() = runBlocking {
        // docs/server-api.md, REST /tasks: a terminal task answers 40904 with
        // {"cancelled":false} — already-terminal success, no snackbar.
        cancelBody =
            """{"code":40904,"msg":"task is not running","data":{"cancelled":false,""" +
                """"details":{"current_status":"completed"}},"request_id":"r"}"""
        val viewModel = newViewModel()
        val effects = collectEffects(viewModel)
        awaitState(viewModel.uiState) { it.status == BackgroundTask.Status.Running }
        server.takeRequest(2, TimeUnit.SECONDS)

        viewModel.onStop()

        val cancel = server.takeRequest(2, TimeUnit.SECONDS)
        assertEquals("POST", cancel?.method)
        assertEquals("/api/v1/sessions/session_1/tasks/bash-1:cancel", cancel?.target)
        delay(100)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `a failed stop surfaces the error effect`() = runBlocking {
        cancelBody = """{"code":40001,"msg":"invalid request","request_id":"r"}"""
        val viewModel = newViewModel()
        val effects = collectEffects(viewModel)
        awaitState(viewModel.uiState) { it.status == BackgroundTask.Status.Running }

        viewModel.onStop()

        awaitCondition { effects.isNotEmpty() }
        assertEquals(
            JobDetailViewModel.Effect.StopFailed(DataError.Api(40001, "invalid request").toUserMessage()),
            effects.single(),
        )
    }

    @Test
    fun `an unknown task shows the full-screen error state`() = runBlocking {
        taskBody = """{"code":40406,"msg":"task not found","request_id":"r"}"""

        val viewModel = newViewModel(JobDetail("session_1", "bash-nope"))
        awaitState(viewModel.uiState) { it.error != null }

        val state = viewModel.uiState.value
        assertEquals(DataError.Api(40406, "task not found").toUserMessage(), state.error)
        assertFalse(state.isLoading)
        assertNull(state.status)
        // Nothing was seeded on this route, so nothing masks the error.
        assertNull(state.description)
        assertNull(state.command)
    }

    @Test
    fun `the route's description and command seed the state before the fetch answers`() = runBlocking {
        val viewModel = newViewModel(
            JobDetail("session_1", "bash-1", description = "count ticks", command = "for i in …"),
        )

        // Seeded synchronously — the first composition already shows them.
        assertEquals("count ticks", viewModel.uiState.value.description)
        assertEquals("for i in …", viewModel.uiState.value.command)
    }

    @Test
    fun `the fetch overwrites the seeded description and command with the API values`() = runBlocking {
        // The server is authoritative: it invents `Bash: <command>…` when the agent
        // set no description, so the route seed must never stick.
        taskBody = COMPLETED_TASK_BODY

        val viewModel = newViewModel(
            JobDetail("session_1", "bash-1", description = "row description", command = "row command"),
        )
        awaitState(viewModel.uiState) { it.status != null }

        val state = viewModel.uiState.value
        assertEquals("tick counter loop", state.description)
        assertEquals("for i in …", state.command)
    }

    @Test
    fun `the route's budget and timed-out flag seed the state and survive the fetch`() = runBlocking {
        // Ticket 097: REST never carries either — the route arguments are the only
        // source, so the poll's state updates must leave them untouched.
        taskBody = FAILED_TASK_BODY

        val viewModel = newViewModel(
            JobDetail("session_1", "bash-1", budgetSeconds = 30, budgetIsDefault = false, timedOut = true),
        )
        // Seeded synchronously — the first composition already shows them.
        assertEquals(30, viewModel.uiState.value.budgetSeconds)
        assertTrue(viewModel.uiState.value.timedOut)

        awaitState(viewModel.uiState) { it.status != null }
        val state = viewModel.uiState.value
        // REST answers the collapsed "failed" — the flag is what tells timeout apart.
        assertEquals(BackgroundTask.Status.Failed, state.status)
        assertEquals(30, state.budgetSeconds)
        assertFalse(state.budgetIsDefault)
        assertTrue(state.timedOut)
    }

    // --- Store-backed mode (ticket 098) ------------------------------------------------

    private fun seedStoreJob(status: TaskInfo.Status, endedAt: Instant? = null) = runBlocking {
        subagentStore.apply(
            "session_1",
            SessionEvent.SubagentSpawned("agent-3", "explore", "tool_1", "doc-verify", runInBackground = false),
        )
        subagentStore.apply(
            "session_1",
            SessionEvent.TaskStarted(
                TaskInfo(
                    "bash-1", "nested bg", status,
                    Instant.ofEpochMilli(1785210101939), endedAt, kind = TaskInfo.Kind.Process,
                ),
                agentId = "agent-3",
            ),
        )
    }

    @Test
    fun `a sub-agent-owned task is store-backed and never calls REST`() = runBlocking {
        seedStoreJob(TaskInfo.Status.Running)

        val viewModel = newViewModel(
            JobDetail(
                "session_1", "bash-1",
                budgetSeconds = 600, budgetIsDefault = true,
                description = "count ticks", command = "for i in …",
                ownerAgentId = "agent-3",
            ),
        )
        awaitState(viewModel.uiState) { it.status == BackgroundTask.Status.Running }

        val state = viewModel.uiState.value
        assertTrue(state.storeBacked)
        assertEquals(BackgroundTask.Status.Running, state.status)
        assertEquals(Instant.ofEpochMilli(1785210101939), state.startedAt)
        // Title/Command/budget stay the route seeds.
        assertEquals("count ticks", state.description)
        assertEquals("for i in …", state.command)
        assertEquals(600, state.budgetSeconds)
        assertTrue(state.budgetIsDefault)
        assertFalse(state.isLoading)
        assertNull(state.error)
        // No entry fetch, and no poll even with the screen active on a running job.
        viewModel.onScreenStarted()
        delay(150)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the store-backed screen reflects a live task terminated`() = runBlocking {
        seedStoreJob(TaskInfo.Status.Running)
        val viewModel = newViewModel(JobDetail("session_1", "bash-1", ownerAgentId = "agent-3"))
        awaitState(viewModel.uiState) { it.status == BackgroundTask.Status.Running }

        subagentStore.apply(
            "session_1",
            SessionEvent.TaskTerminated(
                TaskInfo(
                    "bash-1", "nested bg", TaskInfo.Status.TimedOut,
                    Instant.ofEpochMilli(1785210101939), Instant.ofEpochMilli(1785210118804),
                    kind = TaskInfo.Kind.Process,
                ),
                agentId = "agent-3",
            ),
        )

        awaitState(viewModel.uiState) { it.status == BackgroundTask.Status.Failed }
        val state = viewModel.uiState.value
        // The core timed_out reaches the timed-out label through the store's Job.
        assertTrue(state.timedOut)
        assertEquals(Instant.ofEpochMilli(1785210118804), state.completedAt)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the store-backed mode offers no stop`() = runBlocking {
        seedStoreJob(TaskInfo.Status.Running)
        val viewModel = newViewModel(JobDetail("session_1", "bash-1", ownerAgentId = "agent-3"))
        val effects = collectEffects(viewModel)
        awaitState(viewModel.uiState) { it.status == BackgroundTask.Status.Running }

        viewModel.onStop()

        delay(100)
        assertEquals(0, server.requestCount)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `the store-backed mode shows the seeds without error when the store has no live frame`() = runBlocking {
        // Nothing live observed and no transcript landed (ticket 099's heal needs a
        // fetched page): the route seeds render, the status line stays empty, no
        // full-screen 40406.
        val viewModel = newViewModel(
            JobDetail("session_1", "bash-1", description = "count ticks", ownerAgentId = "agent-3"),
        )
        delay(100)

        val state = viewModel.uiState.value
        assertNull(state.status)
        assertNull(state.error)
        assertFalse(state.isLoading)
        assertEquals("count ticks", state.description)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a cold-opened store-backed screen shows the transcript-healed terminal status`() = runBlocking {
        // Ticket 099: the app never observed a live task.* frame (process death) —
        // the sub-agent page's transcript fetch mines the completion-notification
        // turn's task origin into the record's jobs, and this screen collects the
        // result: the true terminal status instead of an empty status line.
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
                        taskOrigin = TranscriptTaskOrigin("bash-1", TaskInfo.Status.Completed),
                    ),
                ),
                hasMore = false,
            ),
            keepLive = false,
        )

        val viewModel = newViewModel(
            JobDetail("session_1", "bash-1", description = "count ticks", ownerAgentId = "agent-3"),
        )
        awaitState(viewModel.uiState) { it.status == BackgroundTask.Status.Completed }

        val state = viewModel.uiState.value
        assertEquals(BackgroundTask.Status.Completed, state.status)
        // The origin carries no timestamps — unknown stays unknown.
        assertNull(state.startedAt)
        assertNull(state.completedAt)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a main-owned task id stays REST-backed`() = runBlocking {
        taskBody = COMPLETED_TASK_BODY

        val viewModel = newViewModel(JobDetail("session_1", "bash-1", ownerAgentId = "main"))
        awaitState(viewModel.uiState) { it.status != null }

        assertFalse(viewModel.uiState.value.storeBacked)
        assertEquals(
            "/api/v1/sessions/session_1/tasks/bash-1?with_output=true",
            server.takeRequest(2, TimeUnit.SECONDS)?.target,
        )
    }

    private companion object {
        /** A running bash task with its live output tail (docs/server-api.md, REST
         * /tasks — get with `with_output=true`). */
        const val RUNNING_TASK_BODY =
            """{"code":0,"msg":"success","data":{"id":"bash-1","kind":"bash",""" +
                """"description":"tick counter loop","status":"running",""" +
                """"created_at":"2026-08-05T10:00:00.000Z","started_at":"2026-08-05T10:00:00.000Z",""" +
                """"command":"for i in …","output_preview":"tick 41\n","output_bytes":8},""" +
                """"request_id":"r"}"""

        const val COMPLETED_TASK_BODY =
            """{"code":0,"msg":"success","data":{"id":"bash-1","kind":"bash",""" +
                """"description":"tick counter loop","status":"completed",""" +
                """"created_at":"2026-08-05T10:00:00.000Z","started_at":"2026-08-05T10:00:00.000Z",""" +
                """"completed_at":"2026-08-05T10:02:00.000Z","command":"for i in …",""" +
                """"output_preview":"tick 118\ntick 119\n","output_bytes":18},""" +
                """"request_id":"r"}"""

        /** A timed-out job as REST serves it (ticket 097): the core `timed_out`
         * collapses to `failed` — no timeout field anywhere on the wire. */
        const val FAILED_TASK_BODY =
            """{"code":0,"msg":"success","data":{"id":"bash-1","kind":"bash",""" +
                """"description":"tick counter loop","status":"failed",""" +
                """"created_at":"2026-08-05T10:00:00.000Z","started_at":"2026-08-05T10:00:00.000Z",""" +
                """"completed_at":"2026-08-05T10:00:30.000Z","command":"for i in …",""" +
                """"output_preview":"tick 28\n","output_bytes":7},""" +
                """"request_id":"r"}"""
    }
}
