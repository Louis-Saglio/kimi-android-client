package fr.louissaglio.kimi.android.ui.sessions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Same harness as ChatViewModelTest: the real [SessionsRepository] on a [MockWebServer],
 * so the create request body and the state/effect mapping are covered end-to-end. The
 * HTTP call runs on real I/O threads, so waits use real-time timeouts, not runTest's clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionListViewModelTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var viewModel: SessionListViewModel

    private class FakePreferencesDataStore(prefs: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(prefs)
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Before
    fun setUp() {
        server.start()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val settingsRepository = SettingsRepository(
            FakePreferencesDataStore(
                preferencesOf(stringPreferencesKey("server_url") to server.url("/").toString()),
            ),
            appScope,
        )
        viewModel = SessionListViewModel(
            SessionsRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }),
            settingsRepository,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        appScope.cancel()
        server.shutdown()
    }

    @Test
    fun `opening the dialog prefills the default working directory`() {
        viewModel.onNewSessionClick()

        val state = viewModel.uiState.value
        assertTrue(state.showCreateDialog)
        assertEquals("/home/ubuntu", state.newSessionCwd)
        assertEquals("", state.newSessionTitle)
        assertNull(state.newSessionCwdError)
    }

    @Test
    fun `confirm with empty path shows inline error and sends no request`() {
        viewModel.onNewSessionClick()
        viewModel.onNewSessionCwdChange("   ")

        viewModel.onCreateSessionConfirm()

        val state = viewModel.uiState.value
        assertEquals("Enter a working directory", state.newSessionCwdError)
        assertTrue(state.showCreateDialog)
        assertFalse(state.isCreating)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `confirm with relative path shows inline error and sends no request`() {
        viewModel.onNewSessionClick()
        viewModel.onNewSessionCwdChange("tmp/spike")

        viewModel.onCreateSessionConfirm()

        assertEquals("Path must be absolute", viewModel.uiState.value.newSessionCwdError)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `editing the path clears the validation error`() {
        viewModel.onNewSessionClick()
        viewModel.onNewSessionCwdChange("")
        viewModel.onCreateSessionConfirm()

        viewModel.onNewSessionCwdChange("/tmp")

        assertNull(viewModel.uiState.value.newSessionCwdError)
    }

    @Test
    fun `successful confirm posts creation, dismisses dialog and emits SessionCreated`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":0,"msg":"success","data":{"id":"session_new","title":"My session",""" +
                    """"updated_at":"2026-07-19T05:29:30.815Z","status":"idle",""" +
                    """"metadata":{"cwd":"/tmp/spike-008"}},"request_id":"r"}""",
            ),
        )
        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onNewSessionClick()
            viewModel.onNewSessionCwdChange(" /tmp/spike-008 ")
            viewModel.onNewSessionTitleChange("My session")

            viewModel.onCreateSessionConfirm()

            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/api/v1/sessions", request.path)
            // The path is trimmed before sending.
            assertEquals("""{"metadata":{"cwd":"/tmp/spike-008"},"title":"My session"}""", request.body.readUtf8())

            awaitState { !it.showCreateDialog && !it.isCreating }
            withTimeout(5_000) { while (effects.isEmpty()) delay(10) }
            assertEquals(SessionListViewModel.Effect.SessionCreated("session_new"), effects.single())
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `server failure keeps the dialog open with input intact and emits CreateFailed`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"code":40409,"msg":"workspace root /tmp/nope does not exist","data":null,"request_id":"r"}""",
            ),
        )
        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onNewSessionClick()
            viewModel.onNewSessionCwdChange("/tmp/nope")

            viewModel.onCreateSessionConfirm()

            withTimeout(5_000) { while (effects.isEmpty()) delay(10) }
            assertEquals(
                SessionListViewModel.Effect.CreateFailed("workspace root /tmp/nope does not exist"),
                effects.single(),
            )
            val state = viewModel.uiState.value
            assertTrue(state.showCreateDialog)
            assertFalse(state.isCreating)
            assertEquals("/tmp/nope", state.newSessionCwd)
        } finally {
            effectJob.cancel()
        }
    }

    private suspend fun awaitState(predicate: (SessionListViewModel.UiState) -> Boolean) =
        withTimeout(5_000) { while (!predicate(viewModel.uiState.value)) delay(10) }
}
