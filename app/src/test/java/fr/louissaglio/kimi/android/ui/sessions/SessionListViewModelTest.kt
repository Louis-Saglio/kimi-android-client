package fr.louissaglio.kimi.android.ui.sessions

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import fr.louissaglio.kimi.android.testutil.testSessionsRepository
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
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
            testSessionsRepository(),
            settingsRepository,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        appScope.cancel()
        server.close()
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
    fun `confirm with empty path shows inline error and sends no request`() = runBlocking {
        viewModel.onNewSessionClick()
        viewModel.onNewSessionCwdChange("   ")

        viewModel.onCreateSessionConfirm()

        val state = viewModel.uiState.value
        assertEquals("Enter a working directory", state.newSessionCwdError)
        assertTrue(state.showCreateDialog)
        assertFalse(state.isCreating)
        // The only request is the best-effort workspaces fetch from opening the dialog.
        assertEquals("/api/v1/workspaces", server.takeRequest(5, TimeUnit.SECONDS)!!.target)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `confirm with relative path shows inline error and sends no request`() = runBlocking {
        viewModel.onNewSessionClick()
        viewModel.onNewSessionCwdChange("tmp/spike")

        viewModel.onCreateSessionConfirm()

        assertEquals("Path must be absolute", viewModel.uiState.value.newSessionCwdError)
        assertEquals("/api/v1/workspaces", server.takeRequest(5, TimeUnit.SECONDS)!!.target)
        assertEquals(1, server.requestCount)
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
        // The workspaces fetch fails so the dialog stays on the fallback field.
        server.enqueue(MockResponse(code = 500))
        server.enqueue(
            MockResponse(body = 
                """{"code":0,"msg":"success","data":{"id":"session_new","title":"My session",""" +
                    """"updated_at":"2026-07-19T05:29:30.815Z","busy":false,""" +
                    """"metadata":{"cwd":"/tmp/spike-008"}},"request_id":"r"}""",
            ),
        )
        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onNewSessionClick()
            viewModel.onNewSessionCwdChange(" /tmp/spike-008 ")
            viewModel.onNewSessionTitleChange("My session")

            // Wait for the workspaces fetch so MockWebServer hands the 500 to the
            // GET and the create body to the POST (responses are assigned in
            // request-arrival order).
            assertEquals("/api/v1/workspaces", server.takeRequest(5, TimeUnit.SECONDS)!!.target)
            viewModel.onCreateSessionConfirm()

            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/api/v1/sessions", request.target)
            // The path is trimmed before sending.
            assertEquals("""{"metadata":{"cwd":"/tmp/spike-008"},"title":"My session"}""", request.body!!.utf8())

            awaitState(viewModel.uiState) { !it.showCreateDialog && !it.isCreating }
            awaitCondition { effects.isNotEmpty() }
            val created = effects.single() as SessionListViewModel.Effect.SessionCreated
            assertEquals("session_new", created.session.id)
            assertEquals("My session", created.session.title)
            assertEquals("/tmp/spike-008", created.session.cwd)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `server failure keeps the dialog open with input intact and emits CreateFailed`() = runBlocking {
        server.enqueue(MockResponse(code = 500))
        server.enqueue(
            MockResponse(body = 
                """{"code":40409,"msg":"workspace root /tmp/nope does not exist","data":null,"request_id":"r"}""",
            ),
        )
        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onNewSessionClick()
            viewModel.onNewSessionCwdChange("/tmp/nope")

            // Same ordering reason as above: the workspaces GET must take the 500.
            server.takeRequest(5, TimeUnit.SECONDS)
            viewModel.onCreateSessionConfirm()

            awaitCondition { effects.isNotEmpty() }
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

    @Test
    fun `opening the dialog prefills the most recently used workspace root`() = runBlocking {
        server.enqueue(MockResponse(body = WORKSPACES_BODY))

        viewModel.onNewSessionClick()

        awaitState(viewModel.uiState) { it.workspaces != null }
        val state = viewModel.uiState.value
        assertEquals("/api/v1/workspaces", server.takeRequest(5, TimeUnit.SECONDS)!!.target)
        // Sorted client-side by last_opened_at desc; the newest root becomes the default.
        assertEquals(listOf("wd_new", "wd_old"), state.workspaces!!.map { it.id })
        assertEquals("/home/ubuntu/kimi-android-client", state.newSessionCwd)
    }

    @Test
    fun `failed workspaces fetch keeps the plain field with the default cwd`() = runBlocking {
        server.enqueue(MockResponse(code = 500))

        viewModel.onNewSessionClick()

        assertEquals("/api/v1/workspaces", server.takeRequest(5, TimeUnit.SECONDS)!!.target)
        // Let the failed fetch land; no error is surfaced and nothing changes.
        delay(200)
        val state = viewModel.uiState.value
        assertTrue(state.showCreateDialog)
        assertNull(state.workspaces)
        assertEquals("/home/ubuntu", state.newSessionCwd)
    }

    @Test
    fun `empty workspace list keeps the plain field with the default cwd`() = runBlocking {
        server.enqueue(
            MockResponse(body = """{"code":0,"msg":"success","data":{"items":[]},"request_id":"r"}"""),
        )

        viewModel.onNewSessionClick()

        server.takeRequest(5, TimeUnit.SECONDS)
        delay(200)
        val state = viewModel.uiState.value
        assertNull(state.workspaces)
        assertEquals("/home/ubuntu", state.newSessionCwd)
    }

    @Test
    fun `a custom path typed before the workspaces land is preserved`() = runBlocking {
        server.enqueue(MockResponse(body = WORKSPACES_BODY).newBuilder().bodyDelay(300, TimeUnit.MILLISECONDS).build())

        viewModel.onNewSessionClick()
        viewModel.onNewSessionCwdChange("/tmp/custom")

        awaitState(viewModel.uiState) { it.workspaces != null }
        assertEquals("/tmp/custom", viewModel.uiState.value.newSessionCwd)
    }

    @Test
    fun `selecting a workspace then confirming posts its root as cwd`() = runBlocking {
        server.enqueue(MockResponse(body = WORKSPACES_BODY))
        server.enqueue(
            MockResponse(body = 
                """{"code":0,"msg":"success","data":{"id":"session_new","title":"",""" +
                    """"updated_at":"2026-07-19T05:29:30.815Z","busy":false,""" +
                    """"metadata":{"cwd":"/tmp/old"}},"request_id":"r"}""",
            ),
        )
        viewModel.onNewSessionClick()
        awaitState(viewModel.uiState) { it.workspaces != null }

        // Selecting the second workspace from the dropdown sets its root.
        viewModel.onNewSessionCwdChange(viewModel.uiState.value.workspaces!![1].root)
        viewModel.onCreateSessionConfirm()

        server.takeRequest(5, TimeUnit.SECONDS) // workspaces fetch
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("""{"metadata":{"cwd":"/tmp/old"}}""", request.body!!.utf8())
    }

    @Test
    fun `onEndReached appends the next page deduplicated and stops when has_more is false`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        // Page 2 overlaps page 1 by one row; the duplicate must not appear twice.
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_2", "session_3")))

        viewModel.onEndReached()

        awaitState(viewModel.uiState) { it.sessions?.size == 3 }
        val state = viewModel.uiState.value
        assertEquals(listOf("session_1", "session_2", "session_3"), state.sessions!!.map { it.id })
        assertFalse(state.hasMore)
        assertFalse(state.isLoadingMore)
        assertNull(state.loadMoreError)
        assertEquals("/api/v1/sessions", server.takeRequest().target)
        assertEquals("/api/v1/sessions?before_id=session_2", server.takeRequest().target)
    }

    @Test
    fun `failed next page keeps the loaded list and exposes a footer error`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.enqueue(MockResponse(code = 500))

        viewModel.onEndReached()

        awaitState(viewModel.uiState) { it.loadMoreError != null }
        val state = viewModel.uiState.value
        assertEquals(listOf("session_1", "session_2"), state.sessions!!.map { it.id })
        assertEquals("Unexpected server response (HTTP 500)", state.loadMoreError)
        assertFalse(state.isLoadingMore)
        // Still true so the footer retry can attempt the same page again.
        assertTrue(state.hasMore)
    }

    @Test
    fun `onEndReached is a no-op when the server reported no more pages`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }

        viewModel.onEndReached()

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `refresh after paging resets to the first page`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_3")))
        viewModel.onEndReached()
        awaitState(viewModel.uiState) { it.sessions?.size == 3 }
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_9")))

        viewModel.onPullToRefresh()

        awaitState(viewModel.uiState) { it.sessions?.map { s -> s.id } == listOf("session_9") }
        val state = viewModel.uiState.value
        assertTrue(state.hasMore)
        assertNull(state.loadMoreError)
        // The refresh hit the endpoint without a cursor.
        val paths = (1..3).map { server.takeRequest(5, TimeUnit.SECONDS)!!.target }
        assertEquals("/api/v1/sessions", paths[0])
        assertEquals("/api/v1/sessions?before_id=session_2", paths[1])
        assertEquals("/api/v1/sessions", paths[2])
    }

    @Test
    fun `approval flag comes from the latest fetched payload only`() = runBlocking {
        server.enqueue(
            MockResponse(body = 
                """{"code":0,"msg":"success","data":{"items":[
                  {"id":"session_1","title":"Blocked","updated_at":"2026-07-17T03:48:31.606Z",
                   "busy":true,"pending_interaction":"approval","metadata":{"cwd":"/tmp/a"}},
                  {"id":"session_2","title":"Idle","updated_at":"2026-07-17T03:48:31.606Z",
                   "busy":false,"pending_interaction":"none","metadata":{"cwd":"/tmp/b"}}
                ],"has_more":false},"request_id":"r"}""",
            ),
        )
        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { it.sessions != null }
        assertTrue(viewModel.uiState.value.sessions!![0].isAwaitingApproval)
        assertFalse(viewModel.uiState.value.sessions!![1].isAwaitingApproval)

        // After the approval is resolved, the next refresh must clear the badge — the
        // state carries the server value only, nothing is latched locally.
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        viewModel.onPullToRefresh()

        awaitState(viewModel.uiState) { it.sessions?.none { s -> s.isAwaitingApproval } == true }
        assertFalse(viewModel.uiState.value.sessions!![0].isAwaitingApproval)
    }

    private fun pageBody(hasMore: Boolean, vararg ids: String): String {
        val items = ids.joinToString(",") { id ->
            """{"id":"$id","title":"Title $id","updated_at":"2026-07-17T03:48:31.606Z",""" +
                """"busy":false,"metadata":{"cwd":"/tmp/$id"}}"""
        }
        return """{"code":0,"msg":"success","data":{"items":[$items],"has_more":$hasMore},"request_id":"r"}"""
    }

    private companion object {
        const val WORKSPACES_BODY = """
            {"code":0,"msg":"success","data":{"items":[
              {"id":"wd_old","root":"/tmp/old","name":"old",
               "last_opened_at":"2026-07-10T10:00:00.000Z","session_count":2},
              {"id":"wd_new","root":"/home/ubuntu/kimi-android-client","name":"kimi-android-client",
               "last_opened_at":"2026-07-22T03:30:42.000Z","session_count":81}
            ]},"request_id":"r"}
        """
    }
}
