package fr.louissaglio.kimi.android.ui.sessions

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.sessions.SessionActivityStore
import fr.louissaglio.kimi.android.data.sessions.SessionListEvent
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.fakes.FakeSessionListSocket
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import fr.louissaglio.kimi.android.testutil.testSessionsRepository
import java.time.Instant
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
    private lateinit var viewModel: SessionListViewModel
    private lateinit var activityStore: SessionActivityStore
    private lateinit var fakeSocket: FakeSessionListSocket

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
        activityStore = SessionActivityStore()
        fakeSocket = FakeSessionListSocket()
        viewModel = SessionListViewModel(
            testSessionsRepository(),
            settingsRepository,
            activityStore,
            fakeSocket,
        )
    }

    @After
    fun tearDown() {
        // Stop the socket collection so no viewModelScope job outlives the test class:
        // a leaked job resuming on Dispatchers.Main races the next class's setMain.
        viewModel.onScreenStopped()
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

        // A silent refresh starts; its stale page is delayed past the create below.
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
        viewModel.onScreenStarted()
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
    fun `ON_START with a loaded list refreshes without any loading flag or effect`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        val states = CopyOnWriteArrayList<SessionListViewModel.UiState>()
        val stateJob = launch { viewModel.uiState.collect { states += it } }
        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_2")))
            viewModel.onScreenStarted()

            // The silent refresh launched eagerly: the flags must be unset already.
            assertFalse(viewModel.uiState.value.isLoading)
            assertFalse(viewModel.uiState.value.isRefreshing)

            awaitState(viewModel.uiState) { it.hasMore }
            assertEquals(listOf("session_2"), viewModel.uiState.value.sessions!!.map { it.id })
            assertTrue(states.none { it.isLoading || it.isRefreshing })
            assertTrue(effects.isEmpty())
        } finally {
            stateJob.cancel()
            effectJob.cancel()
        }
    }

    @Test
    fun `ON_START refresh failure keeps the old list and emits nothing`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        val effects = CopyOnWriteArrayList<SessionListViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            server.enqueue(MockResponse(code = 500))
            viewModel.onScreenStarted()
            server.takeRequest(5, TimeUnit.SECONDS) // the silent refresh request
            delay(300) // let the 500 land

            assertEquals(listOf("session_1"), viewModel.uiState.value.sessions!!.map { it.id })
            assertNull(viewModel.uiState.value.error)
            assertTrue(effects.isEmpty())
        } finally {
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
        // The refresh response is enqueued only after the bump assertions: the
        // in-flight refresh request waits for it inside MockWebServer.
        viewModel.onScreenStarted()

        // The bump is applied synchronously, before the refresh fires...
        val bumped = viewModel.uiState.value.sessions!!
        assertEquals(listOf("session_2", "session_1"), bumped.map { it.id })
        assertEquals(sentAt, bumped.first().updatedAt)

        // ...then the silent refresh lands with the same server ordering — no jump back.
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_2", "session_1")))
        awaitState(viewModel.uiState) { it.hasMore }
        assertEquals(listOf("session_2", "session_1"), viewModel.uiState.value.sessions!!.map { it.id })
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

        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_1", "session_3", "session_2")))
        awaitState(viewModel.uiState) { it.hasMore }
    }

    @Test
    fun `no recorded activity leaves the loaded list untouched until the refresh lands`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)
        val loaded = viewModel.uiState.value.sessions

        viewModel.onScreenStarted()

        // Nothing recorded → not even a state update: the same list instance.
        assertSame(loaded, viewModel.uiState.value.sessions)

        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_1")))
        awaitState(viewModel.uiState) { it.hasMore }
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

        // The next ON_START (back from chat) opens a fresh collection.
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onScreenStarted()
        awaitCondition { fakeSocket.collections == 2 }
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
