package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.chat.BackgroundTask
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.navigation.JobDetail
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import fr.louissaglio.kimi.android.testutil.testChatRepository
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
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(taskId: String = "bash-1") = JobDetailViewModel(
        repository,
        settingsRepository,
        JobDetail("session_1", taskId),
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

        val viewModel = newViewModel("bash-nope")
        awaitState(viewModel.uiState) { it.error != null }

        val state = viewModel.uiState.value
        assertEquals(DataError.Api(40406, "task not found").toUserMessage(), state.error)
        assertFalse(state.isLoading)
        assertNull(state.status)
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
    }
}
