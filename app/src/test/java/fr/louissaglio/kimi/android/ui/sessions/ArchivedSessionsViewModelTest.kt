package fr.louissaglio.kimi.android.ui.sessions

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.sessions.displayName
import fr.louissaglio.kimi.android.data.sessions.workspaceFolder
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
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
 * Same harness as SessionListViewModelTest: the real [SessionsRepository] on a
 * [MockWebServer], so the query params and the state/effect mapping are covered
 * end-to-end. The HTTP call runs on real I/O threads, so waits use real-time
 * timeouts. The ViewModel loads on init (the archived screen gets one fresh instance
 * per navigation), so tests enqueue the first page before touching anything else —
 * MockWebServer holds the request until a response is queued.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArchivedSessionsViewModelTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: ArchivedSessionsViewModel

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
        viewModel = ArchivedSessionsViewModel(testSessionsRepository(), settingsRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        appScope.cancel()
        server.close()
    }

    @Test
    fun `first page maps rows with the title fallback, workspace basename and archive time`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"items":[
                  {"id":"session_a1","title":"Old experiment","updated_at":"2026-09-10T08:00:00.000Z",
                   "busy":false,"archived":true,"archived_at":"2026-09-14T09:00:00.000Z",
                   "metadata":{"cwd":"/home/ubuntu/spike"}},
                  {"id":"session_a2","title":"","updated_at":"2026-09-01T08:00:00.000Z",
                   "busy":false,"archived":true,
                   "metadata":{"cwd":"/tmp/old"}}
                ],"has_more":true},"request_id":"r"}""",
            ),
        )

        awaitState(viewModel.uiState) { it.sessions != null }
        val state = viewModel.uiState.value
        val first = state.sessions!![0]
        assertEquals("Old experiment", first.displayName)
        assertEquals("spike", first.workspaceFolder)
        // The row's archive time is archived_at…
        assertEquals(Instant.parse("2026-09-14T09:00:00.000Z"), first.archiveTime)
        val second = state.sessions[1]
        // …blank title falls back to the cwd basename like the main list, and an
        // absent archived_at falls back to updated_at.
        assertEquals("old", second.displayName)
        assertEquals("old", second.workspaceFolder)
        assertEquals(Instant.parse("2026-09-01T08:00:00.000Z"), second.archiveTime)
        // has_more is what drives the paging footer.
        assertTrue(state.hasMore)
        assertFalse(state.isLoading)
        assertEquals(
            "/api/v1/sessions?archived_only=true&exclude_empty=true",
            server.takeRequest(5, TimeUnit.SECONDS)!!.target,
        )
    }

    @Test
    fun `pagination appends the next page deduplicated and stops when has_more is false`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_a1", "session_a2")))
        awaitState(viewModel.uiState) { it.sessions != null }
        // Page 2 overlaps page 1 by one row; the duplicate must not appear twice.
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_a2", "session_a3")))

        viewModel.onEndReached()

        awaitState(viewModel.uiState) { it.sessions?.size == 3 }
        val state = viewModel.uiState.value
        assertEquals(listOf("session_a1", "session_a2", "session_a3"), state.sessions!!.map { it.id })
        assertFalse(state.hasMore)
        assertFalse(state.isLoadingMore)
        assertNull(state.loadMoreError)
        assertEquals(
            "/api/v1/sessions?archived_only=true&exclude_empty=true",
            server.takeRequest(5, TimeUnit.SECONDS)!!.target,
        )
        assertEquals(
            "/api/v1/sessions?archived_only=true&exclude_empty=true&before_id=session_a2",
            server.takeRequest(5, TimeUnit.SECONDS)!!.target,
        )

        // No more pages: the scroll trigger is a no-op.
        viewModel.onEndReached()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `failed next page keeps the loaded list and exposes a footer error`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_a1", "session_a2")))
        awaitState(viewModel.uiState) { it.sessions != null }
        server.enqueue(MockResponse(code = 500))

        viewModel.onEndReached()

        awaitState(viewModel.uiState) { it.loadMoreError != null }
        val state = viewModel.uiState.value
        assertEquals(listOf("session_a1", "session_a2"), state.sessions!!.map { it.id })
        assertEquals("Unexpected server response (HTTP 500)", state.loadMoreError)
        assertFalse(state.isLoadingMore)
        // Still true so the footer retry can attempt the same page again.
        assertTrue(state.hasMore)
    }

    @Test
    fun `a failed initial load shows the error state and Retry reloads`() = runBlocking {
        server.enqueue(MockResponse(code = 500))

        awaitState(viewModel.uiState) { it.error != null }
        val failed = viewModel.uiState.value
        assertNull(failed.sessions)
        assertFalse(failed.isLoading)
        assertEquals("Unexpected server response (HTTP 500)", failed.error)

        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_a1")))
        viewModel.onRetry()

        awaitState(viewModel.uiState) { it.sessions != null }
        val state = viewModel.uiState.value
        assertEquals(listOf("session_a1"), state.sessions!!.map { it.id })
        assertNull(state.error)
        assertFalse(state.isLoading)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `pull-to-refresh sets isRefreshing and emits RefreshFailed on failure`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_a1")))
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        val effects = CopyOnWriteArrayList<ArchivedSessionsViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            server.enqueue(MockResponse(code = 500).newBuilder().bodyDelay(200, TimeUnit.MILLISECONDS).build())
            viewModel.onPullToRefresh()

            // The spinner flag is set synchronously by the eager launch.
            assertTrue(viewModel.uiState.value.isRefreshing)
            awaitCondition { effects.isNotEmpty() }
            assertEquals(ArchivedSessionsViewModel.Effect.RefreshFailed("Unexpected server response (HTTP 500)"), effects.single())
            // The loaded list survives the failed refresh.
            assertEquals(listOf("session_a1"), viewModel.uiState.value.sessions!!.map { it.id })
            assertFalse(viewModel.uiState.value.isRefreshing)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `restore success removes the row and emits the restored effect`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_a1", "session_a2")))
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        server.enqueue(MockResponse(body = restoreBody("session_a1")))
        val effects = CopyOnWriteArrayList<ArchivedSessionsViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onRestoreSession("session_a1")

            awaitState(viewModel.uiState) { it.sessions?.map { s -> s.id } == listOf("session_a2") }
            awaitCondition { effects.isNotEmpty() }
            assertEquals(ArchivedSessionsViewModel.Effect.SessionRestored, effects.single())
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/api/v1/sessions/session_a1:restore", request.target)
            // The restore POST is the only extra traffic: no refresh follows.
            assertEquals(2, server.requestCount)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `restore failure keeps the row and emits RestoreFailed with the server message`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_a1")))
        awaitState(viewModel.uiState) { it.sessions != null }
        server.takeRequest(5, TimeUnit.SECONDS)

        server.enqueue(
            MockResponse(body =
                """{"code":40401,"msg":"session session_a1 does not exist","data":null,"request_id":"r"}""",
            ),
        )
        val effects = CopyOnWriteArrayList<ArchivedSessionsViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onRestoreSession("session_a1")

            awaitCondition { effects.isNotEmpty() }
            assertEquals(
                ArchivedSessionsViewModel.Effect.RestoreFailed("session session_a1 does not exist"),
                effects.single(),
            )
            assertEquals(listOf("session_a1"), viewModel.uiState.value.sessions!!.map { it.id })
            assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
        } finally {
            effectJob.cancel()
        }
    }

    /** One archived-list page: every row archived with an ISO archived_at (the 0.42.0
     * shape, verified live 2026-09-14 — ticket 132). */
    private fun pageBody(hasMore: Boolean, vararg ids: String): String {
        val items = ids.joinToString(",") { id ->
            """{"id":"$id","title":"Title $id","updated_at":"2026-09-10T08:00:00.000Z",""" +
                """"busy":false,"archived":true,"archived_at":"2026-09-14T09:00:00.000Z",""" +
                """"metadata":{"cwd":"/tmp/$id"}}"""
        }
        return """{"code":0,"msg":"success","data":{"items":[$items],"has_more":$hasMore},"request_id":"r"}"""
    }

    /** The `:restore` success envelope (ticket 131): the standard v1 session object
     * with archived:false — the ViewModel only needs the zero code. */
    private fun restoreBody(id: String): String =
        """{"code":0,"msg":"success","data":{"id":"$id","title":"Title $id",""" +
            """"updated_at":"2026-09-10T08:00:00.000Z","busy":false,"archived":false,""" +
            """"metadata":{"cwd":"/tmp/$id"}},"request_id":"r"}"""
}
