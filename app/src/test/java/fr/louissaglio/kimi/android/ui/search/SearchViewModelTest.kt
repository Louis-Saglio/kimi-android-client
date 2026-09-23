package fr.louissaglio.kimi.android.ui.search

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.search.SearchHit
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.testutil.awaitState
import fr.louissaglio.kimi.android.testutil.testSearchRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Same harness as ArchivedSessionsViewModelTest: the real [SearchRepository] on a
 * [MockWebServer], so the request bodies and the state mapping are covered
 * end-to-end. The ViewModel fetches nothing until a query is submitted, so tests
 * enqueue the first page before calling onSearch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var viewModel: SearchViewModel

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
        viewModel = SearchViewModel(testSearchRepository(), settingsRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        appScope.cancel()
        server.close()
    }

    @Test
    fun `submitting a query posts the search and maps the rows`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", "session_2")))

        viewModel.onQueryChange("deploy failed")
        viewModel.onSearch()

        awaitState(viewModel.uiState) { it.results != null }
        val state = viewModel.uiState.value
        assertEquals("deploy failed", state.submittedQuery)
        assertEquals(listOf("session_1", "session_2"), state.results!!.map { it.sessionId })
        assertEquals(SearchHit.Role.User, state.results[0].role)
        assertFalse(state.isLoading)
        assertFalse(state.hasMore)
        assertFalse(state.indexStale)
        assertNull(state.error)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/v1/search", request.target)
        assertEquals("""{"query":"deploy failed","page_size":20}""", request.body!!.utf8())
    }

    @Test
    fun `a blank query submits nothing`() = runBlocking {
        viewModel.onQueryChange("   ")
        viewModel.onSearch()

        assertEquals(0, server.requestCount)
        assertNull(viewModel.uiState.value.submittedQuery)
    }

    @Test
    fun `next page passes the token back verbatim and stops when has_more is false`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_1", "session_2")))
        viewModel.onQueryChange("deploy")
        viewModel.onSearch()
        awaitState(viewModel.uiState) { it.results != null }
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_3")))

        viewModel.onEndReached()

        awaitState(viewModel.uiState) { it.results?.size == 3 }
        val state = viewModel.uiState.value
        assertEquals(listOf("session_1", "session_2", "session_3"), state.results!!.map { it.sessionId })
        assertFalse(state.hasMore)
        assertFalse(state.isLoadingMore)
        assertNull(state.loadMoreError)
        assertEquals(
            """{"query":"deploy","page_size":20}""",
            server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8(),
        )
        assertEquals(
            """{"query":"deploy","page_size":20,"page_token":"tok_page_1"}""",
            server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8(),
        )

        // No more pages: the scroll trigger is a no-op.
        viewModel.onEndReached()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a 40001 on the next page restarts the search from page one`() = runBlocking {
        // docs/server-api.md: a rejected page token (envelope 40001) is the documented
        // signal to restart the search from page one — no token on the retry.
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_1", "session_2")))
        viewModel.onQueryChange("deploy")
        viewModel.onSearch()
        awaitState(viewModel.uiState) { it.results != null }
        server.enqueue(
            MockResponse(body =
                """{"code":40001,"msg":"pageToken is malformed","data":null,"request_id":"r"}""",
            ),
        )
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_9")))

        viewModel.onEndReached()

        // The restart REPLACES the loaded rows with the fresh first page.
        awaitState(viewModel.uiState) { it.results?.map { h -> h.sessionId } == listOf("session_9") }
        val state = viewModel.uiState.value
        assertFalse(state.hasMore)
        assertFalse(state.isLoadingMore)
        assertNull(state.loadMoreError)
        assertNull(state.error)
        server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals(
            """{"query":"deploy","page_size":20,"page_token":"tok_page_1"}""",
            server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8(),
        )
        assertEquals(
            """{"query":"deploy","page_size":20}""",
            server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8(),
        )
    }

    @Test
    fun `a stale index flags the banner while the results stay usable`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1", stale = true)))

        viewModel.onQueryChange("deploy")
        viewModel.onSearch()

        awaitState(viewModel.uiState) { it.results != null }
        val state = viewModel.uiState.value
        assertTrue(state.indexStale)
        assertEquals(listOf("session_1"), state.results!!.map { it.sessionId })
    }

    @Test
    fun `a new query never reuses the old page token`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_1")))
        viewModel.onQueryChange("deploy")
        viewModel.onSearch()
        awaitState(viewModel.uiState) { it.results != null }
        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_7")))

        viewModel.onQueryChange("login timeout")
        viewModel.onSearch()

        awaitState(viewModel.uiState) { it.results?.map { h -> h.sessionId } == listOf("session_7") }
        server.takeRequest(5, TimeUnit.SECONDS)
        // The second search starts from page one: no page_token on the body.
        assertEquals(
            """{"query":"login timeout","page_size":20}""",
            server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8(),
        )
    }

    @Test
    fun `a failed first page shows the error state and Retry reloads`() = runBlocking {
        server.enqueue(MockResponse(code = 500))
        viewModel.onQueryChange("deploy")
        viewModel.onSearch()

        awaitState(viewModel.uiState) { it.error != null }
        val failed = viewModel.uiState.value
        assertNull(failed.results)
        assertFalse(failed.isLoading)
        assertEquals("Unexpected server response (HTTP 500)", failed.error)

        server.enqueue(MockResponse(body = pageBody(hasMore = false, "session_1")))
        viewModel.onRetry()

        awaitState(viewModel.uiState) { it.results != null }
        val state = viewModel.uiState.value
        assertEquals(listOf("session_1"), state.results!!.map { it.sessionId })
        assertNull(state.error)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a failed next page keeps the loaded results and exposes a footer error`() = runBlocking {
        server.enqueue(MockResponse(body = pageBody(hasMore = true, "session_1")))
        viewModel.onQueryChange("deploy")
        viewModel.onSearch()
        awaitState(viewModel.uiState) { it.results != null }
        server.enqueue(MockResponse(code = 500))

        viewModel.onEndReached()

        awaitState(viewModel.uiState) { it.loadMoreError != null }
        val state = viewModel.uiState.value
        assertEquals(listOf("session_1"), state.results!!.map { it.sessionId })
        assertEquals("Unexpected server response (HTTP 500)", state.loadMoreError)
        assertFalse(state.isLoadingMore)
        // Still true so the footer retry can attempt the same page again.
        assertTrue(state.hasMore)
    }

    /** One search page: one user hit per id, the token present while has_more (the
     * v2 keyset cursor's only contract — docs/server-api.md, Search). [stale] adds
     * the mid-rebuild `index_state.stale` flag. */
    private fun pageBody(hasMore: Boolean, vararg ids: String, stale: Boolean = false): String {
        val items = ids.joinToString(",") { id ->
            """{"session_id":"$id","workspace_id":"ws_1","session_title":"Title $id",""" +
                """"agent_id":"main","role":"user","snippet":"snippet $id",""" +
                """"time":1754323200000,"turn":1,"score":1.1}"""
        }
        val token = if (hasMore) ""","page_token":"tok_page_1"""" else ""
        val staleField = if (stale) ""","stale":true""" else ""
        return """{"code":0,"msg":"success","data":{"items":[$items],"has_more":$hasMore$token,""" +
            """"index_state":{"state":"ready","indexed_sessions":10,"total_sessions":10,""" +
            """"documents":42$staleField},"source":"index"},"request_id":"r"}"""
    }
}
