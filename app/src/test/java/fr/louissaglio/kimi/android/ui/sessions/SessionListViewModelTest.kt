package fr.louissaglio.kimi.android.ui.sessions

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.chat.ModelsRepository
import fr.louissaglio.kimi.android.data.sessions.Session
import fr.louissaglio.kimi.android.data.sessions.SessionActivityStore
import fr.louissaglio.kimi.android.data.sessions.SessionListCache
import fr.louissaglio.kimi.android.data.sessions.SessionListEvent
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.sessions.UnreadSessionsStore
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
    private lateinit var unreadDir: File
    private lateinit var unreadStore: UnreadSessionsStore

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
        unreadDir = createTempDirectory("session-list-unread-test").toFile()
        unreadStore = UnreadSessionsStore(unreadDir, Json { ignoreUnknownKeys = true })
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
        unreadDir.deleteRecursively()
    }

    private fun createViewModel(): SessionListViewModel = SessionListViewModel(
        testSessionsRepository(),
        settingsRepository,
        activityStore,
        fakeSocket,
        sessionListCache,
        unreadStore,
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
    fun `a work_changed event lights the running indicator and clears it, without any refresh`() = runBlocking {
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
    fun `a completed turn end marks the session unread, an abort does not`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        fakeSocket.events.tryEmit(SessionListEvent.WorkChanged("session_1", busy = true, pendingInteraction = "none"))
        awaitState(viewModel.uiState) { it.sessions!![0].isRunning }
        fakeSocket.events.tryEmit(SessionListEvent.WorkChanged("session_2", busy = true, pendingInteraction = "none"))
        awaitState(viewModel.uiState) { it.sessions!![1].isRunning }

        fakeSocket.events.tryEmit(
            SessionListEvent.WorkChanged("session_1", busy = false, pendingInteraction = "none", lastTurnReason = "completed"),
        )
        awaitState(viewModel.uiState) { "session_1" in it.unreadIds }

        fakeSocket.events.tryEmit(
            SessionListEvent.WorkChanged("session_2", busy = false, pendingInteraction = "none", lastTurnReason = "cancelled"),
        )
        awaitState(viewModel.uiState) { !it.sessions!![1].isRunning }
        assertEquals(setOf("session_1"), viewModel.uiState.value.unreadIds)

        // The mark is written through to disk — cold-start parity with the web's localStorage.
        assertEquals(setOf("session_1"), unreadStore.unreadIds(server.url("/").toString()).value)
    }

    @Test
    fun `opening a session clears its unread marker`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        fakeSocket.events.tryEmit(SessionListEvent.WorkChanged("session_1", busy = true, pendingInteraction = "none"))
        awaitState(viewModel.uiState) { it.sessions!![0].isRunning }
        fakeSocket.events.tryEmit(
            SessionListEvent.WorkChanged("session_1", busy = false, pendingInteraction = "none", lastTurnReason = "completed"),
        )
        awaitState(viewModel.uiState) { "session_1" in it.unreadIds }

        viewModel.clearUnread("session_1")

        awaitState(viewModel.uiState) { it.unreadIds.isEmpty() }
    }

    // --- Interrupted-last-turn badge (ticket 159) ---

    @Test
    fun `a cancelled turn end lights the interrupted badge live, a completed one clears it`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        fakeSocket.events.tryEmit(SessionListEvent.WorkChanged("session_1", busy = true, pendingInteraction = "none"))
        awaitState(viewModel.uiState) { it.sessions!![0].isRunning }

        fakeSocket.events.tryEmit(
            SessionListEvent.WorkChanged("session_1", busy = false, pendingInteraction = "none", lastTurnReason = "cancelled"),
        )
        awaitState(viewModel.uiState) { it.sessions!![0].isLastTurnInterrupted }

        // A new turn runs: the spinner wins while running, and the flag itself stays
        // until that turn's own end frame (busy frames carry no reason).
        fakeSocket.events.tryEmit(SessionListEvent.WorkChanged("session_1", busy = true, pendingInteraction = "none"))
        awaitState(viewModel.uiState) { it.sessions!![0].isRunning && it.sessions[0].isLastTurnInterrupted }

        fakeSocket.events.tryEmit(
            SessionListEvent.WorkChanged("session_1", busy = false, pendingInteraction = "none", lastTurnReason = "completed"),
        )
        awaitState(viewModel.uiState) { !it.sessions!![0].isLastTurnInterrupted }
        // Pure WebSocket: no REST call beyond the initial load.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a refresh clears the interrupted badge once the turn completes server-side`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"items":[
                  {"id":"session_1","title":"Aborted","updated_at":"2026-07-17T03:48:31.606Z",
                   "busy":false,"last_turn_reason":"cancelled","metadata":{"cwd":"/tmp/a"}}
                ],"has_more":false},"request_id":"r"}""",
            ),
        )
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions?.single()?.isLastTurnInterrupted == true }

        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"items":[
                  {"id":"session_1","title":"Aborted","updated_at":"2026-07-17T03:48:31.606Z",
                   "busy":false,"last_turn_reason":"completed","metadata":{"cwd":"/tmp/a"}}
                ],"has_more":false},"request_id":"r"}""",
            ),
        )
        viewModel.onPullToRefresh()

        // The REST field is the source of truth: "completed" on the next refresh
        // clears the badge, no WS event needed.
        awaitState(viewModel.uiState) { it.sessions!!.none { s -> s.isLastTurnInterrupted } }
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

    @Test
    fun `a session created event inserts the row live at its updated_at position`() = runBlocking {
        server.enqueue(
            MockResponse(body = pageBodyWithStamps(
                "session_1" to "2026-07-17T03:48:31.606Z",
                "session_2" to "2026-07-16T03:48:31.606Z",
            )),
        )
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        // A create from another client (ticket 129): the frame carries the full
        // session, so the row lands at its sorted position, between the two loaded
        // rows here — not at the top.
        fakeSocket.events.tryEmit(SessionListEvent.SessionCreated(createdSession()))

        awaitState(viewModel.uiState) {
            it.sessions?.map { s -> s.id } == listOf("session_1", "session_new", "session_2")
        }
        // Pure WebSocket insert: no REST call beyond the initial load.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a session created event for an already listed session does not duplicate it`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        // The echo of the app's own create/fork (008/128): the frame is broadcast to
        // every connection, including the originator's — the upsert must not add a
        // second row.
        fakeSocket.events.tryEmit(
            SessionListEvent.SessionCreated(
                Session(
                    id = "session_2",
                    title = "Title session_2",
                    cwd = "/tmp/session_2",
                    updatedAt = Instant.parse("2026-07-17T03:48:31.606Z"),
                    isRunning = false,
                    isAwaitingInteraction = false,
                ),
            ),
        )

        delay(200) // let any (wrong) duplicate land
        assertEquals(listOf("session_1", "session_2"), viewModel.uiState.value.sessions!!.map { it.id })
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
    fun `the archived-restore flag fires exactly one silent refresh`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        // Back from the archived screen after a restore (ticket 132): restore pushed
        // no WS event, so the nav-result flag is what picks the restored row up.
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_restored")))
        viewModel.onRestoreFromArchived()

        assertEquals("/api/v1/sessions", server.takeRequest(5, TimeUnit.SECONDS)!!.target)
        awaitState(viewModel.uiState) { it.sessions?.map { s -> s.id } == listOf("session_1", "session_restored") }
        // Same silent semantics as the reconnect heal: no spinner flags, one request.
        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.isRefreshing)
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
    fun `a session created event inserts the row into the matching group at its updated_at position`() = runBlocking {
        enterGroupedMode(
            groupsBodyWithStamps(
                "/tmp/a" to listOf("session_1" to 1784260111606L),
                "/tmp/b" to listOf("session_2" to 1784173711606L),
            ),
        )

        // Newer than session_2 (1784173711606 == 2026-07-16T03:48:31.606Z): the row
        // lands ahead of it in its own group, matched by cwd like create/fork.
        fakeSocket.events.tryEmit(
            SessionListEvent.SessionCreated(
                createdSession(cwd = "/tmp/b", updatedAt = Instant.parse("2026-07-17T00:00:00.000Z")),
            ),
        )

        awaitState(viewModel.uiState) { state ->
            state.groups!!.first { it.cwd == "/tmp/b" }.sessions.map { it.id } == listOf("session_new", "session_2")
        }
        val groups = viewModel.uiState.value.groups!!
        // The other group is untouched, and the never-loaded flat list stays null —
        // a one-row list would clobber the disk cache (same rule as create/fork).
        assertEquals(listOf("session_1"), groups.first { it.cwd == "/tmp/a" }.sessions.map { it.id })
        assertNull(viewModel.uiState.value.sessions)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a session created event for a workspace with no group on screen is left out`() = runBlocking {
        enterGroupedMode(groupsBody("/tmp/a" to listOf("session_1")))

        fakeSocket.events.tryEmit(SessionListEvent.SessionCreated(createdSession(cwd = "/tmp/unknown")))

        delay(200) // let any (wrong) insert land
        // The row joins no group (same as create/fork: the next grouped load
        // reconciles) and the never-loaded flat list stays null.
        assertEquals(listOf("session_1"), viewModel.uiState.value.groups!!.single().sessions.map { it.id })
        assertNull(viewModel.uiState.value.sessions)
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

    // --- Forking from the list (ticket 128) ---

    @Test
    fun `fork success inserts the fork at its updated_at position in the flat list`() = runBlocking {
        server.enqueue(
            MockResponse(body = pageBodyWithStamps(
                "session_1" to "2026-07-17T03:48:31.606Z",
                "session_2" to "2026-07-16T03:48:31.606Z",
            )),
        )
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        // The fork inherits the source's updated_at (ticket 127): the sorted insert
        // lands it right behind the source, not at the top like create.
        server.enqueue(MockResponse(body = forkBody("/tmp/session_1")))
        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onForkSession(viewModel.uiState.value.sessions!!.first { it.id == "session_1" })

            awaitState(viewModel.uiState) { it.sessions?.any { s -> s.id == "session_fork" } == true }
            assertEquals(
                listOf("session_1", "session_fork", "session_2"),
                viewModel.uiState.value.sessions!!.map { it.id },
            )
            awaitCondition { effects.isNotEmpty() }
            val forked = effects.single() as SessionListViewModel.Effect.SessionForked
            assertEquals("session_fork", forked.session.id)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/api/v1/sessions/session_1:fork", request.target)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `fork success inserts the fork into the matching group at its updated_at position`() = runBlocking {
        enterGroupedMode(
            groupsBodyWithStamps(
                "/tmp/a" to listOf("session_1" to 1784260111606L, "session_2" to 1784173711606L),
                "/tmp/b" to listOf("session_3" to 1784260111606L),
            ),
        )

        server.enqueue(MockResponse(body = forkBody("/tmp/a")))
        val source = viewModel.uiState.value.groups!!.first { it.cwd == "/tmp/a" }.sessions.first()
        viewModel.onForkSession(source)

        awaitState(viewModel.uiState) { state ->
            state.groups!!.first { it.cwd == "/tmp/a" }.sessions.any { it.id == "session_fork" }
        }
        val groups = viewModel.uiState.value.groups!!
        // Same instant as session_1 (1784260111606 == 2026-07-17T03:48:31.606Z): the
        // fork lands right behind it, ahead of the older session_2.
        assertEquals(
            listOf("session_1", "session_fork", "session_2"),
            groups.first { it.cwd == "/tmp/a" }.sessions.map { it.id },
        )
        // The other group is untouched, and the never-loaded flat list stays null —
        // a one-row list would clobber the disk cache (same rule as create).
        assertEquals(listOf("session_3"), groups.first { it.cwd == "/tmp/b" }.sessions.map { it.id })
        assertNull(viewModel.uiState.value.sessions)
        assertEquals("/api/v1/sessions/session_1:fork", server.takeRequest(5, TimeUnit.SECONDS)!!.target)
    }

    @Test
    fun `forking a running session is a no-op`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"items":[
                  {"id":"session_1","title":"Busy","updated_at":"2026-07-17T03:48:31.606Z",
                   "busy":true,"metadata":{"cwd":"/tmp/a"}}
                ],"has_more":false},"request_id":"r"}""",
            ),
        )
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)
        val loaded = viewModel.uiState.value.sessions

        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onForkSession(loaded!!.single())

            // The server refuses a busy fork with 40901 (ticket 127), so the intent —
            // like the disabled dialog action — fires nothing: no request, no effect,
            // not even a state update.
            delay(300)
            assertEquals(1, server.requestCount)
            assertSame(loaded, viewModel.uiState.value.sessions)
            assertTrue(effects.isEmpty())
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `a failed fork emits ForkFailed with the server message and leaves the list untouched`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)
        // A turn started server-side between the list load and the tap (127 capture).
        server.enqueue(
            MockResponse(body =
                """{"code":40901,"msg":"Session \"session_1\" cannot be forked while a turn is running",""" +
                    """"data":null,"request_id":"r"}""",
            ),
        )
        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onForkSession(viewModel.uiState.value.sessions!!.single())

            awaitCondition { effects.isNotEmpty() }
            assertEquals(
                SessionListViewModel.Effect.ForkFailed(
                    "Session \"session_1\" cannot be forked while a turn is running",
                ),
                effects.single(),
            )
            assertEquals(listOf("session_1"), viewModel.uiState.value.sessions!!.map { it.id })
            assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
        } finally {
            effectJob.cancel()
        }
    }

    // --- Archiving from the list (ticket 131) ---

    @Test
    fun `archive success removes the row from the flat list and its group and emits the undo effect`() = runBlocking {
        // Both modes loaded at once (ticket 115): the flat page holds session_1 and
        // session_2, the grouped fetch's workspace /tmp/a holds session_1 and session_3.
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)
        server.enqueue(MockResponse(body = groupsBody("/tmp/a" to listOf("session_1", "session_3"))))
        viewModel.onViewModeToggle()
        awaitState(viewModel.uiState) { it.groups != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        server.enqueue(MockResponse(body = ARCHIVE_BODY))
        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onArchiveSession("session_1")

            awaitState(viewModel.uiState) { state ->
                state.sessions?.map { it.id } == listOf("session_2") &&
                    state.groups?.single()?.sessions?.map { it.id } == listOf("session_3")
            }
            awaitCondition { effects.isNotEmpty() }
            assertEquals(SessionListViewModel.Effect.SessionArchived("session_1"), effects.single())
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/api/v1/sessions/session_1:archive", request.target)
            // The archive POST is the only extra traffic: no refresh follows.
            assertEquals(3, server.requestCount)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `a failed archive keeps the row and emits ArchiveFailed with the server message`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)
        server.enqueue(
            MockResponse(body =
                """{"code":40401,"msg":"session session_1 does not exist","data":null,"request_id":"r"}""",
            ),
        )
        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onArchiveSession("session_1")

            awaitCondition { effects.isNotEmpty() }
            assertEquals(
                SessionListViewModel.Effect.ArchiveFailed("session session_1 does not exist"),
                effects.single(),
            )
            assertEquals(listOf("session_1"), viewModel.uiState.value.sessions!!.map { it.id })
            assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `undo reinserts the archived row at its remembered positions in both lists`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)
        server.enqueue(MockResponse(body = groupsBody("/tmp/a" to listOf("session_1", "session_3"))))
        viewModel.onViewModeToggle()
        awaitState(viewModel.uiState) { it.groups != null }
        server.takeRequest(5, TimeUnit.SECONDS)
        server.enqueue(MockResponse(body = ARCHIVE_BODY))
        viewModel.onArchiveSession("session_1")
        awaitState(viewModel.uiState) { it.sessions?.map { s -> s.id } == listOf("session_2") }
        server.takeRequest(5, TimeUnit.SECONDS)

        // Restore pushes no WS event (verified live 2026-09-14 on 0.42.0), so the
        // remembered row goes back at its remembered positions — updated_at is stable
        // across archive/restore, keeping those the sorted ones.
        server.enqueue(MockResponse(body = restoreBody("session_1")))
        viewModel.onUndoArchive("session_1")

        awaitState(viewModel.uiState) { state ->
            state.sessions?.map { it.id } == listOf("session_1", "session_2") &&
                state.groups?.single()?.sessions?.map { it.id } == listOf("session_1", "session_3")
        }
        assertEquals("/api/v1/sessions/session_1:restore", server.takeRequest(5, TimeUnit.SECONDS)!!.target)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `undo does not duplicate the row when the socket re-added it first`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)
        server.enqueue(MockResponse(body = ARCHIVE_BODY))
        viewModel.onArchiveSession("session_1")
        awaitState(viewModel.uiState) { it.sessions?.map { s -> s.id } == listOf("session_2") }
        server.takeRequest(5, TimeUnit.SECONDS)

        // A create event for the same id beats the undo (ticket 129's upsert): the
        // row is back before the restore lands — the reinsert must skip, not add a
        // second row.
        fakeSocket.events.tryEmit(
            SessionListEvent.SessionCreated(createdSession(id = "session_1", cwd = "/tmp/session_1")),
        )
        awaitState(viewModel.uiState) { it.sessions?.map { s -> s.id } == listOf("session_2", "session_1") }

        server.enqueue(MockResponse(body = restoreBody("session_1")))
        viewModel.onUndoArchive("session_1")

        server.takeRequest(5, TimeUnit.SECONDS) // the restore POST
        delay(300) // let any (wrong) duplicate land
        assertEquals(listOf("session_2", "session_1"), viewModel.uiState.value.sessions!!.map { it.id })
    }

    @Test
    fun `a failed restore emits RestoreFailed and leaves the session archived`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)
        server.enqueue(MockResponse(body = ARCHIVE_BODY))
        viewModel.onArchiveSession("session_1")
        awaitState(viewModel.uiState) { it.sessions?.map { s -> s.id } == listOf("session_2") }
        server.takeRequest(5, TimeUnit.SECONDS)

        server.enqueue(
            MockResponse(body =
                """{"code":40401,"msg":"session session_1 does not exist","data":null,"request_id":"r"}""",
            ),
        )
        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onUndoArchive("session_1")

            awaitCondition { effects.size == 2 }
            assertEquals(SessionListViewModel.Effect.SessionArchived("session_1"), effects[0])
            assertEquals(
                SessionListViewModel.Effect.RestoreFailed("session session_1 does not exist"),
                effects[1],
            )
            // The row stays out — the session is still archived server-side.
            assertEquals(listOf("session_2"), viewModel.uiState.value.sessions!!.map { it.id })
            assertEquals("/api/v1/sessions/session_1:restore", server.takeRequest(5, TimeUnit.SECONDS)!!.target)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `a stale undo for a superseded archive is a no-op`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)
        // Two archives in a row: the second takes the single undo slot, so the first
        // snackbar's Undo must not restore the wrong session.
        server.enqueue(MockResponse(body = ARCHIVE_BODY))
        viewModel.onArchiveSession("session_1")
        awaitState(viewModel.uiState) { it.sessions?.map { s -> s.id } == listOf("session_2") }
        server.enqueue(MockResponse(body = ARCHIVE_BODY))
        viewModel.onArchiveSession("session_2")
        awaitState(viewModel.uiState) { it.sessions?.isEmpty() == true }

        viewModel.onUndoArchive("session_1")

        // No restore POST fires for the superseded id: only the two archive calls.
        assertEquals(3, server.requestCount)
        assertTrue(viewModel.uiState.value.sessions!!.isEmpty())
    }

    @Test
    fun `delete success removes the row from the flat list and its group with no snackbar`() = runBlocking {
        // Both modes loaded at once (ticket 115), same setup as the archive test:
        // the flat page holds session_1 and session_2, the grouped fetch's
        // workspace /tmp/a holds session_1 and session_3.
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)
        server.enqueue(MockResponse(body = groupsBody("/tmp/a" to listOf("session_1", "session_3"))))
        viewModel.onViewModeToggle()
        awaitState(viewModel.uiState) { it.groups != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        server.enqueue(MockResponse(body = DELETE_BODY))
        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onDeleteSession("session_1")

            awaitState(viewModel.uiState) { state ->
                state.sessions?.map { it.id } == listOf("session_2") &&
                    state.groups?.single()?.sessions?.map { it.id } == listOf("session_3")
            }
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/api/v1/sessions/session_1:delete", request.target)
            // Delete is final (no undo snackbar, ticket 151): the gone row is the
            // only feedback, so no effect at all — and no refresh follows.
            delay(300)
            assertTrue(effects.isEmpty())
            assertEquals(3, server.requestCount)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `an already-deleted session drops the row without an error effect`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)
        // 40401: another client deleted first — the end state is the requested one,
        // so the row drops like a success and no error toast shows (ticket 151).
        server.enqueue(
            MockResponse(body =
                """{"code":40401,"msg":"session session_1 does not exist","data":null,"request_id":"r"}""",
            ),
        )
        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onDeleteSession("session_1")

            awaitState(viewModel.uiState) { it.sessions?.map { s -> s.id } == listOf("session_2") }
            assertEquals("/api/v1/sessions/session_1:delete", server.takeRequest(5, TimeUnit.SECONDS)!!.target)
            delay(300)
            assertTrue(effects.isEmpty())
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `a failed delete keeps the row and emits DeleteFailed with the server message`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)
        server.enqueue(
            MockResponse(body = """{"code":50001,"msg":"boom","data":null,"request_id":"r"}"""),
        )
        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onDeleteSession("session_1")

            awaitCondition { effects.isNotEmpty() }
            assertEquals(SessionListViewModel.Effect.DeleteFailed("boom"), effects.single())
            assertEquals(listOf("session_1"), viewModel.uiState.value.sessions!!.map { it.id })
            assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
        } finally {
            effectJob.cancel()
        }
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

    /** [pageBody] with explicit per-session ISO updated_at stamps — the fork sorted-insert test needs distinct timestamps (ticket 128). */
    private fun pageBodyWithStamps(vararg sessions: Pair<String, String>): String {
        val items = sessions.joinToString(",") { (id, updatedAt) ->
            """{"id":"$id","title":"Title $id","updated_at":"$updatedAt",""" +
                """"busy":false,"metadata":{"cwd":"/tmp/$id"}}"""
        }
        return """{"code":0,"msg":"success","data":{"items":[$items],"has_more":false},"request_id":"r"}"""
    }

    /** [groupsBodyWithTotals] with explicit per-session epoch-ms updated_at stamps (ticket 128); total == loaded, so no footer. */
    private fun groupsBodyWithStamps(vararg groups: Pair<String, List<Pair<String, Long>>>): String {
        val groupsJson = groups.joinToString(",") { (cwd, sessions) ->
            val workspaceId = "wd_" + cwd.substringAfterLast('/')
            val sessionsJson = sessions.joinToString(",") { (id, updatedAt) ->
                """{"id":"$id","workspace":{"id":"$workspaceId","cwd":"$cwd"},""" +
                    """"meta":{"title":"Title $id","last_prompt":null,"created_at":$updatedAt,""" +
                    """"updated_at":$updatedAt,"archived":false,"archived_at":null},""" +
                    """"activity":{"status":"idle","model":""}}"""
            }
            """{"workspace":{"id":"$workspaceId","cwd":"$cwd"},"sessions":[$sessionsJson],"total":${sessions.size}}"""
        }
        return """{"code":0,"msg":"success","data":{"groups":[$groupsJson],""" +
            """"total":${groups.size},"has_more":false,"next_page_token":null},"request_id":"r"}"""
    }

    /** The `:fork` success envelope (ticket 127 capture): the fork of session_1 inherits its updated_at and carries [cwd]. */
    private fun forkBody(cwd: String): String =
        """{"code":0,"msg":"success","data":{"id":"session_fork","title":"Fork: Title session_1",""" +
            """"updated_at":"2026-07-17T03:48:31.606Z","busy":false,""" +
            """"metadata":{"cwd":"$cwd"}},"request_id":"r"}"""

    /** The `:restore` success envelope (ticket 131): the standard v1 session object
     * with archived:false — the ViewModel reinserts its remembered row, so only the
     * zero code matters here. */
    private fun restoreBody(id: String): String =
        """{"code":0,"msg":"success","data":{"id":"$id","title":"Title $id",""" +
            """"updated_at":"2026-07-17T03:48:31.606Z","busy":false,"archived":false,""" +
            """"metadata":{"cwd":"/tmp/$id"}},"request_id":"r"}"""

    /** The session an `event.session.created` frame carries (ticket 129); the default
     * [updatedAt] lands it between pageBodyWithStamps' two rows. */
    private fun createdSession(
        id: String = "session_new",
        cwd: String = "/tmp/session_new",
        updatedAt: Instant = Instant.parse("2026-07-16T12:00:00.000Z"),
    ): Session = Session(
        id = id,
        title = "Created elsewhere",
        cwd = cwd,
        updatedAt = updatedAt,
        isRunning = false,
        isAwaitingInteraction = false,
    )

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

        /** The `:archive` success envelope (verified live 2026-09-14 on 0.42.0 — ticket 131). */
        const val ARCHIVE_BODY = """{"code":0,"msg":"success","data":{"archived":true},"request_id":"r"}"""

        /** The `:delete` success envelope (verified live 2026-09-09 on 0.42.0 — ticket 151). */
        const val DELETE_BODY = """{"code":0,"msg":"success","data":{"deleted":true},"request_id":"r"}"""

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
