package fr.louissaglio.kimi.android.ui.sessions

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.chat.ModelsRepository
import fr.louissaglio.kimi.android.data.sessions.Session
import fr.louissaglio.kimi.android.data.sessions.SessionActivityStore
import fr.louissaglio.kimi.android.data.sessions.SessionListCache
import fr.louissaglio.kimi.android.data.sessions.SessionListEvent
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.fakes.FakeSessionListSocket
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import fr.louissaglio.kimi.android.testutil.testModelsRepository
import fr.louissaglio.kimi.android.testutil.testSessionsRepository
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
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
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: SessionListViewModel
    private lateinit var activityStore: SessionActivityStore
    private lateinit var fakeSocket: FakeSessionListSocket
    private lateinit var modelsRepository: ModelsRepository
    private lateinit var cacheDir: File
    private lateinit var sessionListCache: SessionListCache

    @Before
    fun setUp() {
        server.start()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settingsRepository = SettingsRepository(
            FakePreferencesDataStore(
                preferencesOf(stringPreferencesKey("server_url") to server.url("/").toString()),
            ),
            appScope,
        )
        activityStore = SessionActivityStore()
        fakeSocket = FakeSessionListSocket()
        modelsRepository = testModelsRepository()
        cacheDir = createTempDirectory("session-list-view-model-test").toFile()
        sessionListCache = SessionListCache(cacheDir, Json { ignoreUnknownKeys = true })
        viewModel = createViewModel()
    }

    @After
    fun tearDown() {
        // Stop the socket collection so no viewModelScope job outlives the test class:
        // a leaked job resuming on Dispatchers.Main races the next class's setMain.
        viewModel.onScreenStopped()
        Dispatchers.resetMain()
        appScope.cancel()
        server.close()
        cacheDir.deleteRecursively()
    }

    private fun createViewModel(): SessionListViewModel = SessionListViewModel(
        testSessionsRepository(),
        settingsRepository,
        activityStore,
        fakeSocket,
        sessionListCache,
        modelsRepository,
    )

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
    fun `interaction flag comes from the latest fetched payload only`() = runBlocking {
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
        assertTrue(viewModel.uiState.value.sessions!![0].isAwaitingInteraction)
        assertFalse(viewModel.uiState.value.sessions!![1].isAwaitingInteraction)

        // After the approval is resolved, the next refresh must clear the badge — the
        // state carries the server value only, nothing is latched locally.
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        viewModel.onPullToRefresh()

        awaitState(viewModel.uiState) { it.sessions?.none { s -> s.isAwaitingInteraction } == true }
        assertFalse(viewModel.uiState.value.sessions!![0].isAwaitingInteraction)
    }

    @Test
    fun `create success inserts the new session at the top of the loaded list`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)
        // The workspaces fetch fails so the dialog stays on the fallback field.
        server.enqueue(MockResponse(code = 500))
        server.enqueue(
            MockResponse(body = 
                """{"code":0,"msg":"success","data":{"id":"session_new","title":"New session",""" +
                    """"updated_at":"2026-08-02T06:00:00.000Z","busy":false,""" +
                    """"metadata":{"cwd":"/home/ubuntu"}},"request_id":"r"}""",
            ),
        )
        viewModel.onNewSessionClick()
        server.takeRequest(5, TimeUnit.SECONDS) // workspaces fetch

        viewModel.onCreateSessionConfirm()

        awaitState(viewModel.uiState) { it.sessions?.firstOrNull()?.id == "session_new" }
        assertEquals(
            listOf("session_new", "session_1", "session_2"),
            viewModel.uiState.value.sessions!!.map { it.id },
        )
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions", request.target)
    }

    @Test
    fun `an in-flight refresh when the create succeeds cannot drop the inserted session`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        // A reconnect fires the silent refresh; its stale page is delayed past the
        // create below (ON_START no longer refreshes a loaded list, ticket 112).
        server.enqueue(
            MockResponse(body = pageBody(hasMore = false, "session_stale"))
                .newBuilder()
                .bodyDelay(500, TimeUnit.MILLISECONDS)
                .build(),
        )
        server.enqueue(MockResponse(code = 500)) // workspaces fetch
        server.enqueue(
            MockResponse(body = 
                """{"code":0,"msg":"success","data":{"id":"session_new","title":"New session",""" +
                    """"updated_at":"2026-08-02T06:00:00.000Z","busy":false,""" +
                    """"metadata":{"cwd":"/home/ubuntu"}},"request_id":"r"}""",
            ),
        )
        fakeSocket.events.tryEmit(SessionListEvent.Reconnected)
        server.takeRequest(5, TimeUnit.SECONDS) // the silent refresh request
        viewModel.onNewSessionClick()
        server.takeRequest(5, TimeUnit.SECONDS) // workspaces fetch

        viewModel.onCreateSessionConfirm()

        awaitState(viewModel.uiState) { it.sessions?.firstOrNull()?.id == "session_new" }
        server.takeRequest(5, TimeUnit.SECONDS) // create POST
        assertEquals(listOf("session_new", "session_1"), viewModel.uiState.value.sessions!!.map { it.id })

        // The delayed stale page now lands on the cancelled refresh job: discarded.
        delay(700)
        assertEquals(listOf("session_new", "session_1"), viewModel.uiState.value.sessions!!.map { it.id })
    }

    @Test
    fun `ON_START with a loaded list fires no request and touches nothing`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        val states = CopyOnWriteArrayList<SessionListViewModel.UiState>()
        val stateJob = launch { viewModel.uiState.collect { states += it } }
        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            val loaded = viewModel.uiState.value.sessions

            viewModel.onScreenStarted()

            // Manual refresh only (ticket 112): no HTTP, not even a state update —
            // only the (faked) socket re-opens. A request would have arrived by now.
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
            assertSame(loaded, viewModel.uiState.value.sessions)
            assertFalse(viewModel.uiState.value.isLoading)
            assertFalse(viewModel.uiState.value.isRefreshing)
            assertTrue(states.none { it.isLoading || it.isRefreshing })
            assertTrue(effects.isEmpty())
        } finally {
            stateJob.cancel()
            effectJob.cancel()
        }
    }

    @Test
    fun `pull-to-refresh still sets isRefreshing and emits RefreshFailed on failure`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            server.enqueue(MockResponse(code = 500).newBuilder().bodyDelay(200, TimeUnit.MILLISECONDS).build())
            viewModel.onPullToRefresh()

            // The spinner flag is set synchronously by the eager launch.
            assertTrue(viewModel.uiState.value.isRefreshing)
            awaitCondition { effects.isNotEmpty() }
            assertTrue(effects.single() is SessionListViewModel.Effect.RefreshFailed)
            // The loaded list survives the failed refresh.
            assertEquals(listOf("session_1"), viewModel.uiState.value.sessions!!.map { it.id })
            assertFalse(viewModel.uiState.value.isRefreshing)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `a sent message bumps its session to the top with the recorded timestamp`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        val sentAt = Instant.parse("2026-08-02T06:00:00.000Z")
        activityStore.record("session_2", sentAt)
        viewModel.onScreenStarted()

        // The bump is applied synchronously, and with no automatic refresh (ticket
        // 112) it is the visible update until the next manual one.
        val bumped = viewModel.uiState.value.sessions!!
        assertEquals(listOf("session_2", "session_1"), bumped.map { it.id })
        assertEquals(sentAt, bumped.first().updatedAt)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `several bumped sessions order most-recent-activity first`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2", "session_3")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        activityStore.record("session_3", Instant.parse("2026-08-02T05:00:00.000Z"))
        activityStore.record("session_1", Instant.parse("2026-08-02T06:00:00.000Z"))
        // Activity for a session outside the loaded page is dropped.
        activityStore.record("session_other", Instant.parse("2026-08-02T07:00:00.000Z"))
        viewModel.onScreenStarted()

        val bumped = viewModel.uiState.value.sessions!!
        assertEquals(listOf("session_1", "session_3", "session_2"), bumped.map { it.id })
        assertEquals(Instant.parse("2026-08-02T06:00:00.000Z"), bumped[0].updatedAt)
        assertEquals(Instant.parse("2026-08-02T05:00:00.000Z"), bumped[1].updatedAt)

        // No refresh follows the bump (ticket 112).
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `no recorded activity leaves the loaded list untouched`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)
        val loaded = viewModel.uiState.value.sessions

        viewModel.onScreenStarted()

        // Nothing recorded → not even a state update: the same list instance.
        assertSame(loaded, viewModel.uiState.value.sessions)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    // --- Workspace labels (ticket 114) ---

    @Test
    fun `workspace labels derive from the loaded cwds and relabel when a page changes the set`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"items":[
                  {"id":"session_1","title":"A","updated_at":"2026-07-17T03:48:31.606Z",
                   "busy":false,"metadata":{"cwd":"/home/a/app"}}
                ],"has_more":true},"request_id":"r"}""",
            ),
        )
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }

        assertEquals(mapOf("/home/a/app" to "app"), viewModel.uiState.value.workspaceLabels)

        // The next page brings a colliding basename: both labels gain a parent segment.
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"items":[
                  {"id":"session_2","title":"B","updated_at":"2026-07-17T03:48:31.606Z",
                   "busy":false,"metadata":{"cwd":"/home/b/app"}}
                ],"has_more":false},"request_id":"r"}""",
            ),
        )
        viewModel.onEndReached()

        awaitState(viewModel.uiState) { it.sessions?.size == 2 }
        assertEquals(
            mapOf("/home/a/app" to "a/app", "/home/b/app" to "b/app"),
            viewModel.uiState.value.workspaceLabels,
        )
    }

    // --- Disk cache (ticket 111) ---

    @Test
    fun `a warm cache renders instantly and ON_START fetches nothing`() = runBlocking {
        sessionListCache.save(
            server.url("/").toString(),
            listOf(
                Session(
                    id = "session_cached",
                    title = "Cached",
                    cwd = "/tmp/cached",
                    updatedAt = Instant.parse("2026-08-30T10:00:00.000Z"),
                    isRunning = true,
                    isAwaitingInteraction = true,
                ),
            ),
            hasMore = true,
        )
        // Rebuilt with the cache already on disk, like a cold process start.
        viewModel = createViewModel()

        // Populated from disk during init, before any request was even made.
        val warm = viewModel.uiState.value
        val cached = warm.sessions!!
        assertEquals(listOf("session_cached"), cached.map { it.id })
        assertTrue(warm.hasMore)
        assertTrue(cached.single().isRunning)
        assertTrue(cached.single().isAwaitingInteraction)
        assertEquals(0, server.requestCount)

        val states = CopyOnWriteArrayList<SessionListViewModel.UiState>()
        val stateJob = launch { viewModel.uiState.collect { states += it } }
        try {
            viewModel.onScreenStarted()

            // Manual refresh only (ticket 112): with the cached list on screen,
            // ON_START fetches nothing — the cached rows stay as they are.
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
            assertEquals(0, server.requestCount)
            assertEquals(listOf("session_cached"), viewModel.uiState.value.sessions!!.map { it.id })
            assertTrue(states.none { it.sessions == null || it.isLoading })
        } finally {
            stateJob.cancel()
        }
    }

    @Test
    fun `without a cache the cold start spins until the first page lands`() = runBlocking {
        server.enqueue(
            MockResponse(body = pageBody(hasMore = false, "session_1"))
                .newBuilder()
                .bodyDelay(300, TimeUnit.MILLISECONDS)
                .build(),
        )

        viewModel.onScreenStarted()

        // Nothing on disk: sessions == null → LoadingState, the visible initial load
        // (the flag is set synchronously by the eager launch).
        assertNull(viewModel.uiState.value.sessions)
        assertTrue(viewModel.uiState.value.isLoading)

        awaitState(viewModel.uiState) { it.sessions != null }
        assertEquals(listOf("session_1"), viewModel.uiState.value.sessions!!.map { it.id })
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `a failed initial load shows the error state and Retry reloads`() = runBlocking {
        server.enqueue(MockResponse(code = 500))

        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { it.error != null }
        val failed = viewModel.uiState.value
        assertNull(failed.sessions)
        assertFalse(failed.isLoading)
        assertEquals("Unexpected server response (HTTP 500)", failed.error)

        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onRetry()

        awaitState(viewModel.uiState) { it.sessions != null }
        val state = viewModel.uiState.value
        assertEquals(listOf("session_1"), state.sessions!!.map { it.id })
        assertNull(state.error)
        assertFalse(state.isLoading)
        assertEquals("/api/v1/sessions", server.takeRequest(5, TimeUnit.SECONDS)!!.target)
        assertEquals("/api/v1/sessions", server.takeRequest(5, TimeUnit.SECONDS)!!.target)
    }

    @Test
    fun `the loaded list is written through to the disk cache`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }

        // The write is debounced: the file holds the list (and hasMore) shortly after.
        awaitCondition {
            sessionListCache.load(server.url("/").toString())
                ?.let { it.sessions.map { s -> s.id } == listOf("session_1", "session_2") && it.hasMore } == true
        }
    }

    // --- Live socket patches (ticket 087, FakeSessionListSocket) ---

    @Test
    fun `a work_changed event lights the running dot and clears it, without any refresh`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        fakeSocket.events.tryEmit(SessionListEvent.WorkChanged("session_2", busy = true, pendingInteraction = "none"))
        awaitState(viewModel.uiState) { it.sessions!![1].isRunning }
        // The row patched in place: order untouched.
        assertEquals(listOf("session_1", "session_2"), viewModel.uiState.value.sessions!!.map { it.id })

        fakeSocket.events.tryEmit(SessionListEvent.WorkChanged("session_2", busy = false, pendingInteraction = "none"))
        awaitState(viewModel.uiState) { it.sessions!!.none { s -> s.isRunning } }
        // Both patches were pure WebSocket: no REST call beyond the initial load.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `approvals and questions light the needs-me badge, none clears it`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }

        fakeSocket.events.tryEmit(SessionListEvent.WorkChanged("session_1", busy = true, pendingInteraction = "question"))
        awaitState(viewModel.uiState) { it.sessions!![0].isAwaitingInteraction }

        fakeSocket.events.tryEmit(SessionListEvent.WorkChanged("session_1", busy = true, pendingInteraction = "none"))
        awaitState(viewModel.uiState) { !it.sessions!![0].isAwaitingInteraction }

        fakeSocket.events.tryEmit(SessionListEvent.WorkChanged("session_1", busy = true, pendingInteraction = "approval"))
        awaitState(viewModel.uiState) { it.sessions!![0].isAwaitingInteraction }

        fakeSocket.events.tryEmit(SessionListEvent.WorkChanged("session_1", busy = false, pendingInteraction = "none"))
        awaitState(viewModel.uiState) { !it.sessions!![0].isAwaitingInteraction && !it.sessions[0].isRunning }
    }

    @Test
    fun `a meta updated event patches the row title live`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }

        fakeSocket.events.tryEmit(SessionListEvent.MetaUpdated("session_1", "Auto-titled by the server"))
        awaitState(viewModel.uiState) { it.sessions!![0].title == "Auto-titled by the server" }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a session archived event drops the row without any refresh`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        fakeSocket.events.tryEmit(SessionListEvent.SessionArchived("session_1"))

        awaitState(viewModel.uiState) { it.sessions?.map { s -> s.id } == listOf("session_2") }
        // Pure WebSocket removal: no REST call beyond the initial load.
        assertEquals(1, server.requestCount)
    }

    // --- Global config/model-catalog events (ticket 110) ---

    @Test
    fun `a config changed event drops the models and config caches`() = runBlocking {
        // Warm both per-server caches (models list + config projection).
        server.enqueue(MockResponse(body = MODELS_BODY))
        server.enqueue(MockResponse(body = configBody("high")))
        val settings = settingsRepository.current()
        modelsRepository.models(settings).getOrThrow()
        modelsRepository.config(settings).getOrThrow()
        server.takeRequest(5, TimeUnit.SECONDS)
        server.takeRequest(5, TimeUnit.SECONDS)
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        server.enqueue(MockResponse(body = MODELS_BODY_UPDATED))
        server.enqueue(MockResponse(body = configBody("low")))
        fakeSocket.events.tryEmit(SessionListEvent.ConfigChanged)

        // The event has landed once the models cache is gone (both caches drop together).
        awaitCondition { modelsRepository.cachedSupportEfforts(settings, "kimi-code/k3") == null }
        // The next reads refetch and serve the new values.
        assertEquals("K3.5", modelsRepository.models(settings).getOrThrow().single().displayName)
        assertEquals("low", modelsRepository.config(settings).getOrThrow().defaultEffort)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `a model catalog changed event drops the caches, a session archived event does not`() = runBlocking {
        server.enqueue(MockResponse(body = MODELS_BODY))
        server.enqueue(MockResponse(body = configBody("high")))
        val settings = settingsRepository.current()
        modelsRepository.models(settings).getOrThrow()
        modelsRepository.config(settings).getOrThrow()
        server.takeRequest(5, TimeUnit.SECONDS)
        server.takeRequest(5, TimeUnit.SECONDS)
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        // An unrelated global event leaves the caches alone: the row drops, the
        // config read keeps serving the cache.
        fakeSocket.events.tryEmit(SessionListEvent.SessionArchived("session_1"))
        awaitState(viewModel.uiState) { it.sessions!!.isEmpty() }
        assertEquals("high", modelsRepository.config(settings).getOrThrow().defaultEffort)
        assertEquals(3, server.requestCount)

        server.enqueue(MockResponse(body = configBody("low")))
        fakeSocket.events.tryEmit(SessionListEvent.ModelCatalogChanged)

        awaitCondition { modelsRepository.cachedSupportEfforts(settings, "kimi-code/k3") == null }
        assertEquals("low", modelsRepository.config(settings).getOrThrow().defaultEffort)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `events for sessions not in the list are ignored`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        val loaded = viewModel.uiState.value.sessions

        fakeSocket.events.tryEmit(SessionListEvent.WorkChanged("session_unknown", busy = true, pendingInteraction = "approval"))
        fakeSocket.events.tryEmit(SessionListEvent.MetaUpdated("session_unknown", "Ghost"))
        delay(200) // let any (wrong) patch land

        // Not even a state update: the same list instance.
        assertSame(loaded, viewModel.uiState.value.sessions)
    }

    @Test
    fun `a reconnect triggers exactly one silent refresh`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        fakeSocket.events.tryEmit(SessionListEvent.Reconnected)

        assertEquals("/api/v1/sessions", server.takeRequest(5, TimeUnit.SECONDS)!!.target)
        awaitState(viewModel.uiState) { it.sessions?.size == 2 }
        // The heal is a one-shot: no further request.
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `the socket collection starts on ON_START and stops on ON_STOP`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitCondition { fakeSocket.collections == 1 }

        viewModel.onScreenStopped()
        awaitCondition { fakeSocket.completions == 1 }

        // The next ON_START (back from chat) opens a fresh collection — and fires
        // no refresh now that the list is loaded (ticket 112).
        viewModel.onScreenStarted()
        awaitCondition { fakeSocket.collections == 2 }
    }

    // --- Grouped-by-workspace view (ticket 115) ---

    @Test
    fun `the toggle defaults to flat, switches to grouped and survives recreation`() = runBlocking {
        assertFalse(viewModel.uiState.value.groupedView)
        assertNull(viewModel.uiState.value.groups)
        server.enqueue(MockResponse(body = groupsBody("/tmp/a" to listOf("session_1"))))

        viewModel.onViewModeToggle()

        assertTrue(viewModel.uiState.value.groupedView)
        awaitState(viewModel.uiState) { it.groups != null }
        assertEquals(GROUPED_TARGET, server.takeRequest(5, TimeUnit.SECONDS)!!.target)
        assertTrue(settingsRepository.isSessionListGrouped())

        // Recreated with the same DataStore (process death): the persisted mode is
        // re-read, so ON_START loads the groups, never the flat list.
        viewModel = createViewModel()
        server.enqueue(MockResponse(body = groupsBody("/tmp/a" to listOf("session_1"))))
        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { it.groupedView && it.groups != null }
        assertEquals(GROUPED_TARGET, server.takeRequest(5, TimeUnit.SECONDS)!!.target)
        assertEquals(listOf("session_1"), viewModel.uiState.value.groups!!.single().sessions.map { it.id })
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `toggling back to a never-loaded flat list fires its initial load`() = runBlocking {
        enterGroupedMode(groupsBody("/tmp/a" to listOf("session_1")))
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))

        viewModel.onViewModeToggle()

        assertFalse(viewModel.uiState.value.groupedView)
        awaitState(viewModel.uiState) { it.sessions != null }
        assertEquals("/api/v1/sessions", server.takeRequest(5, TimeUnit.SECONDS)!!.target)
        assertFalse(settingsRepository.isSessionListGrouped())
    }

    @Test
    fun `ON_START with loaded groups fires no request`() = runBlocking {
        enterGroupedMode(groupsBody("/tmp/a" to listOf("session_1")))

        viewModel.onScreenStarted()

        // Manual refresh only (ticket 112), mode-aware: groups on screen = content.
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `pull-to-refresh in grouped mode re-fetches the groups`() = runBlocking {
        enterGroupedMode(groupsBody("/tmp/a" to listOf("session_1")))
        server.enqueue(MockResponse(body = groupsBody("/tmp/a" to listOf("session_1", "session_2"))))

        viewModel.onPullToRefresh()

        assertEquals(GROUPED_TARGET, server.takeRequest(5, TimeUnit.SECONDS)!!.target)
        awaitState(viewModel.uiState) { it.groups?.single()?.sessions?.size == 2 }
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `a reconnect in grouped mode re-fetches the groups once`() = runBlocking {
        enterGroupedMode(groupsBody("/tmp/a" to listOf("session_1")))
        server.enqueue(MockResponse(body = groupsBody("/tmp/a" to listOf("session_1", "session_2"))))

        fakeSocket.events.tryEmit(SessionListEvent.Reconnected)

        assertEquals(GROUPED_TARGET, server.takeRequest(5, TimeUnit.SECONDS)!!.target)
        awaitState(viewModel.uiState) { it.groups?.single()?.sessions?.size == 2 }
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a work_changed event patches the row inside its group without moving it`() = runBlocking {
        enterGroupedMode(groupsBody("/tmp/a" to listOf("session_1", "session_2"), "/tmp/b" to listOf("session_3")))

        fakeSocket.events.tryEmit(SessionListEvent.WorkChanged("session_2", busy = true, pendingInteraction = "approval"))

        awaitState(viewModel.uiState) { it.groups!![0].sessions[1].isAwaitingInteraction }
        val groups = viewModel.uiState.value.groups!!
        assertTrue(groups[0].sessions[1].isRunning)
        // The row patched in place: order untouched, in both groups.
        assertEquals(listOf("session_1", "session_2"), groups[0].sessions.map { it.id })
        assertEquals(listOf("session_3"), groups[1].sessions.map { it.id })
        // Pure WebSocket patch: no REST call beyond the initial grouped load.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a meta updated event patches the row title inside its group`() = runBlocking {
        enterGroupedMode(groupsBody("/tmp/a" to listOf("session_1", "session_2"), "/tmp/b" to listOf("session_3")))

        fakeSocket.events.tryEmit(SessionListEvent.MetaUpdated("session_3", "Retitled"))

        awaitState(viewModel.uiState) { it.groups!![1].sessions[0].title == "Retitled" }
        val groups = viewModel.uiState.value.groups!!
        assertEquals(listOf("session_3"), groups[1].sessions.map { it.id })
        assertEquals(listOf("session_1", "session_2"), groups[0].sessions.map { it.id })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a session archived event drops the row from its group`() = runBlocking {
        enterGroupedMode(groupsBody("/tmp/a" to listOf("session_1", "session_2"), "/tmp/b" to listOf("session_3")))

        fakeSocket.events.tryEmit(SessionListEvent.SessionArchived("session_1"))

        awaitState(viewModel.uiState) { it.groups!![0].sessions.map { s -> s.id } == listOf("session_2") }
        assertEquals(listOf("session_3"), viewModel.uiState.value.groups!![1].sessions.map { it.id })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `create success prepends the new session to the matching group`() = runBlocking {
        enterGroupedMode(groupsBody("/tmp/a" to listOf("session_1"), "/tmp/b" to listOf("session_2")))
        server.enqueue(MockResponse(code = 500)) // workspaces fetch
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"id":"session_new","title":"New session",""" +
                    """"updated_at":"2026-08-02T06:00:00.000Z","busy":false,""" +
                    """"metadata":{"cwd":"/tmp/b"}},"request_id":"r"}""",
            ),
        )
        viewModel.onNewSessionClick()
        server.takeRequest(5, TimeUnit.SECONDS) // workspaces fetch
        viewModel.onNewSessionCwdChange("/tmp/b")

        viewModel.onCreateSessionConfirm()

        awaitState(viewModel.uiState) { it.groups!![1].sessions.first().id == "session_new" }
        val groups = viewModel.uiState.value.groups!!
        assertEquals(listOf("session_new", "session_2"), groups[1].sessions.map { it.id })
        assertEquals(listOf("session_1"), groups[0].sessions.map { it.id })
        // The flat list was never loaded in grouped mode and stays that way — a
        // one-row flat list would clobber the disk cache.
        assertNull(viewModel.uiState.value.sessions)
    }

    @Test
    fun `the activity bump moves the session to the top of its group only`() = runBlocking {
        enterGroupedMode(groupsBody("/tmp/a" to listOf("session_1", "session_2"), "/tmp/b" to listOf("session_3")))

        val sentAt = Instant.parse("2026-08-02T06:00:00.000Z")
        activityStore.record("session_2", sentAt)
        viewModel.onScreenStarted()

        val groups = viewModel.uiState.value.groups!!
        assertEquals(listOf("session_2", "session_1"), groups[0].sessions.map { it.id })
        assertEquals(sentAt, groups[0].sessions.first().updatedAt)
        // Groups themselves never reorder.
        assertEquals(listOf("/tmp/a", "/tmp/b"), groups.map { it.cwd })
        assertEquals(listOf("session_3"), groups[1].sessions.map { it.id })
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    // --- Per-group show more (ticket 116) ---

    @Test
    fun `show more appends the group's next v1 page deduplicated`() = runBlocking {
        enterGroupedMode(groupsBodyWithTotals(Triple("/tmp/a", listOf("session_1", "session_2"), 4)))
        // The expansion page overlaps the loaded rows by one; the duplicate must not
        // appear twice (same rule as the flat list's paging).
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_2", "session_3")))

        viewModel.onGroupShowMore("wd_a")

        awaitState(viewModel.uiState) { it.groups?.single()?.sessions?.size == 3 }
        val group = viewModel.uiState.value.groups!!.single()
        assertEquals(listOf("session_1", "session_2", "session_3"), group.sessions.map { it.id })
        assertEquals(4, group.total)
        // The workspace filter, the 5-row page size and the group's last loaded id
        // ride the same v1 call.
        assertEquals(
            "/api/v1/sessions?workspace_id=wd_a&page_size=5&before_id=session_2",
            server.takeRequest(5, TimeUnit.SECONDS)!!.target,
        )
        // The footer state settled: nothing loading, no error.
        assertTrue(viewModel.uiState.value.groupExpansions.isEmpty())
    }

    @Test
    fun `show more is a no-op once the group is fully loaded`() = runBlocking {
        // groupsBody's default total == loaded: no footer, so a tap fires nothing.
        enterGroupedMode(groupsBody("/tmp/a" to listOf("session_1", "session_2")))

        viewModel.onGroupShowMore("wd_a")

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a rapid second tap while the expansion is in flight fires no second request`() = runBlocking {
        enterGroupedMode(groupsBodyWithTotals(Triple("/tmp/a", listOf("session_1"), 3)))
        server.enqueue(
            MockResponse(body = pageBody(hasMore = true, "session_2"))
                .newBuilder()
                .bodyDelay(300, TimeUnit.MILLISECONDS)
                .build(),
        )

        viewModel.onGroupShowMore("wd_a")
        // The eager launch already flagged the group as loading…
        assertTrue(viewModel.uiState.value.groupExpansions.getValue("wd_a").isLoading)
        // …and the in-flight job (not a state flag) is the guard: this tap is swallowed.
        viewModel.onGroupShowMore("wd_a")

        assertEquals(
            "/api/v1/sessions?workspace_id=wd_a&page_size=5&before_id=session_1",
            server.takeRequest(5, TimeUnit.SECONDS)!!.target,
        )
        awaitState(viewModel.uiState) { it.groups?.single()?.sessions?.size == 2 }
        // Exactly the grouped load plus one expansion request hit the server.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a failed expansion keeps the group intact and the footer retry loads the page`() = runBlocking {
        enterGroupedMode(groupsBodyWithTotals(Triple("/tmp/a", listOf("session_1", "session_2"), 4)))
        server.enqueue(MockResponse(code = 500))

        viewModel.onGroupShowMore("wd_a")

        awaitState(viewModel.uiState) { it.groupExpansions["wd_a"]?.error != null }
        val failed = viewModel.uiState.value
        assertEquals(listOf("session_1", "session_2"), failed.groups!!.single().sessions.map { it.id })
        assertEquals("Unexpected server response (HTTP 500)", failed.groupExpansions.getValue("wd_a").error)
        assertFalse(failed.groupExpansions.getValue("wd_a").isLoading)

        // The footer retry attempts the same page again.
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_3")))
        viewModel.onGroupShowMore("wd_a")

        awaitState(viewModel.uiState) { it.groups?.single()?.sessions?.size == 3 }
        assertEquals(
            listOf("session_1", "session_2", "session_3"),
            viewModel.uiState.value.groups!!.single().sessions.map { it.id },
        )
        assertTrue(viewModel.uiState.value.groupExpansions.isEmpty())
        assertEquals(
            "/api/v1/sessions?workspace_id=wd_a&page_size=5&before_id=session_2",
            server.takeRequest(5, TimeUnit.SECONDS)!!.target,
        )
    }

    @Test
    fun `show more pages five sessions at a time`() = runBlocking {
        enterGroupedMode(groupsBodyWithTotals(Triple("/tmp/a", listOf("session_1", "session_2"), 8)))
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_3", "session_4", "session_5", "session_6", "session_7")))

        viewModel.onGroupShowMore("wd_a")

        awaitState(viewModel.uiState) { it.groups?.single()?.sessions?.size == 7 }
        // The group still has one session server-side: the footer stays for another page.
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_8")))
        viewModel.onGroupShowMore("wd_a")

        awaitState(viewModel.uiState) { it.groups?.single()?.sessions?.size == 8 }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `reaching the group total removes the footer`() = runBlocking {
        enterGroupedMode(groupsBodyWithTotals(Triple("/tmp/a", listOf("session_1"), 2)))
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_2")))

        viewModel.onGroupShowMore("wd_a")

        awaitState(viewModel.uiState) { it.groups?.single()?.sessions?.size == 2 }
        // loaded == total: the footer is gone, a further tap fires nothing.
        viewModel.onGroupShowMore("wd_a")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `socket patches and archive drops apply to expanded rows`() = runBlocking {
        enterGroupedMode(groupsBodyWithTotals(Triple("/tmp/a", listOf("session_1"), 3)))
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_2")))
        viewModel.onGroupShowMore("wd_a")
        awaitState(viewModel.uiState) { it.groups?.single()?.sessions?.size == 2 }
        server.takeRequest(5, TimeUnit.SECONDS) // drain the expansion request

        // The row loaded by the expansion patches in place, by session id like any other.
        fakeSocket.events.tryEmit(SessionListEvent.WorkChanged("session_2", busy = true, pendingInteraction = "approval"))
        awaitState(viewModel.uiState) { it.groups!!.single().sessions[1].isAwaitingInteraction }
        assertEquals(listOf("session_1", "session_2"), viewModel.uiState.value.groups!!.single().sessions.map { it.id })

        fakeSocket.events.tryEmit(SessionListEvent.SessionArchived("session_2"))
        awaitState(viewModel.uiState) { it.groups?.single()?.sessions?.map { s -> s.id } == listOf("session_1") }
        // Pure WebSocket activity: no REST call beyond the grouped load and the expansion.
        assertEquals(2, server.requestCount)
    }

    // --- Group folding ---

    @Test
    fun `tapping a group header folds it and tapping again unfolds it`() = runBlocking {
        enterGroupedMode(groupsBody("/tmp/a" to listOf("session_1"), "/tmp/b" to listOf("session_2")))

        viewModel.onGroupFoldToggle("wd_a")
        assertEquals(setOf("wd_a"), viewModel.uiState.value.foldedGroupIds)

        viewModel.onGroupFoldToggle("wd_a")
        assertTrue(viewModel.uiState.value.foldedGroupIds.isEmpty())
    }

    @Test
    fun `a refresh keeps the folded groups folded`() = runBlocking {
        enterGroupedMode(groupsBody("/tmp/a" to listOf("session_1")))
        viewModel.onGroupFoldToggle("wd_a")

        server.enqueue(MockResponse(body = groupsBody("/tmp/a" to listOf("session_1"))))
        viewModel.onPullToRefresh()

        awaitState(viewModel.uiState) { !it.isRefreshing }
        assertEquals(setOf("wd_a"), viewModel.uiState.value.foldedGroupIds)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `the group new-session button prefills the dialog with that workspace root`() = runBlocking {
        server.enqueue(MockResponse(body = WORKSPACES_BODY))

        viewModel.onNewSessionClick("/tmp/old")

        awaitState(viewModel.uiState) { it.workspaces != null }
        val state = viewModel.uiState.value
        assertTrue(state.showCreateDialog)
        // The workspaces fetch must not clobber the pre-selected group cwd.
        assertEquals("/tmp/old", state.newSessionCwd)
        assertEquals("/api/v1/workspaces", server.takeRequest(5, TimeUnit.SECONDS)!!.target)
    }

    // --- Group show less ---

    @Test
    fun `show less drops the last five rows and the show-more footer comes back`() = runBlocking {
        enterGroupedMode(groupsBodyWithTotals(Triple("/tmp/a", listOf("session_1", "session_2"), 8)))
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_3", "session_4", "session_5", "session_6", "session_7")))
        viewModel.onGroupShowMore("wd_a")
        awaitState(viewModel.uiState) { it.groups?.single()?.sessions?.size == 7 }
        server.takeRequest(5, TimeUnit.SECONDS) // drain the expansion request

        viewModel.onGroupShowLess("wd_a")

        assertEquals(listOf("session_1", "session_2"), viewModel.uiState.value.groups!!.single().sessions.map { it.id })
        // Back below the total: show more pages again from the last visible row.
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_3", "session_4", "session_5", "session_6", "session_7")))
        viewModel.onGroupShowMore("wd_a")
        awaitState(viewModel.uiState) { it.groups?.single()?.sessions?.size == 7 }
        assertEquals(
            "/api/v1/sessions?workspace_id=wd_a&page_size=5&before_id=session_2",
            server.takeRequest(5, TimeUnit.SECONDS)!!.target,
        )
    }

    @Test
    fun `show less while an expansion is in flight cancels it`() = runBlocking {
        enterGroupedMode(
            groupsBodyWithTotals(
                Triple("/tmp/a", listOf("session_1", "session_2", "session_3", "session_4", "session_5", "session_6"), 12),
            ),
        )
        server.enqueue(
            MockResponse(body = pageBody(hasMore = true, "session_7", "session_8", "session_9", "session_10", "session_11"))
                .newBuilder()
                .bodyDelay(300, TimeUnit.MILLISECONDS)
                .build(),
        )
        viewModel.onGroupShowMore("wd_a")
        assertTrue(viewModel.uiState.value.groupExpansions.getValue("wd_a").isLoading)

        viewModel.onGroupShowLess("wd_a")

        // The loading entry is dropped immediately, the last five rows hidden…
        assertTrue(viewModel.uiState.value.groupExpansions.isEmpty())
        assertEquals(listOf("session_1"), viewModel.uiState.value.groups!!.single().sessions.map { it.id })
        // …and the cancelled page never lands.
        delay(500)
        assertEquals(listOf("session_1"), viewModel.uiState.value.groups!!.single().sessions.map { it.id })
    }

    @Test
    fun `show less at five rows or fewer hides nothing`() = runBlocking {
        enterGroupedMode(groupsBodyWithTotals(Triple("/tmp/a", listOf("session_1", "session_2"), 8)))

        viewModel.onGroupShowLess("wd_a")

        assertEquals(listOf("session_1", "session_2"), viewModel.uiState.value.groups!!.single().sessions.map { it.id })
        assertEquals(1, server.requestCount)
    }

    /**
     * Puts the screen in grouped mode with [body] answered to the grouped fetch,
     * drains that request, and runs ON_START — with groups on screen it fires no
     * further request (112) but opens the (faked) socket collection. The mode is
     * exercised through the real toggle, so the DataStore write is covered too.
     */
    private suspend fun enterGroupedMode(body: String) {
        server.enqueue(MockResponse(body = body))
        viewModel.onViewModeToggle()
        awaitState(viewModel.uiState) { it.groups != null }
        assertEquals(GROUPED_TARGET, server.takeRequest(5, TimeUnit.SECONDS)!!.target)
        viewModel.onScreenStarted()
    }

    private fun groupsBody(vararg groups: Pair<String, List<String>>): String =
        groupsBodyWithTotals(*groups.map { (cwd, ids) -> Triple(cwd, ids, ids.size) }.toTypedArray())

    /** [groupsBody] with an explicit server-side [Triple.third] total per group — a group
     * holding fewer rows than its total is what the show-more footer expands (116). */
    private fun groupsBodyWithTotals(vararg groups: Triple<String, List<String>, Int>): String {
        val groupsJson = groups.joinToString(",") { (cwd, ids, total) ->
            val workspaceId = "wd_" + cwd.substringAfterLast('/')
            val sessions = ids.joinToString(",") { id ->
                """{"id":"$id","workspace":{"id":"$workspaceId","cwd":"$cwd"},""" +
                    """"meta":{"title":"Title $id","last_prompt":null,"created_at":1787253993333,""" +
                    """"updated_at":1787253993333,"archived":false,"archived_at":null},""" +
                    """"activity":{"status":"idle","model":""}}"""
            }
            """{"workspace":{"id":"$workspaceId","cwd":"$cwd"},"sessions":[$sessions],"total":$total}"""
        }
        return """{"code":0,"msg":"success","data":{"groups":[$groupsJson],""" +
            """"total":${groups.size},"has_more":false,"next_page_token":null},"request_id":"r"}"""
    }

    private fun pageBody(hasMore: Boolean, vararg ids: String): String {
        val items = ids.joinToString(",") { id ->
            """{"id":"$id","title":"Title $id","updated_at":"2026-07-17T03:48:31.606Z",""" +
                """"busy":false,"metadata":{"cwd":"/tmp/$id"}}"""
        }
        return """{"code":0,"msg":"success","data":{"items":[$items],"has_more":$hasMore},"request_id":"r"}"""
    }

    private fun configBody(effort: String): String =
        """{"code":0,"msg":"success","data":{"default_model":"kimi-code/k3",""" +
            """"thinking":{"enabled":true,"effort":"$effort"},""" +
            """"providers":{"managed:kimi-code":{"type":"kimi",""" +
            """"base_url":"https://api.kimi.com/coding/v1","has_api_key":true}}},"request_id":"r"}"""

    private companion object {
        const val MODELS_BODY = """
            {"code":0,"msg":"success","data":{"items":[
              {"provider":"kimi-code","model":"kimi-code/k3","display_name":"K3",
               "max_context_size":1048576,"support_efforts":["low","medium","high"],
               "default_effort":"high"}
            ]},"request_id":"r"}
        """
        const val MODELS_BODY_UPDATED = """
            {"code":0,"msg":"success","data":{"items":[
              {"provider":"kimi-code","model":"kimi-code/k3","display_name":"K3.5",
               "max_context_size":1048576,"support_efforts":["low","medium","high"],
               "default_effort":"high"}
            ]},"request_id":"r"}
        """
        const val GROUPED_TARGET = "/api/v2/sessions?view=by_workspace&group.page_size=5"

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
