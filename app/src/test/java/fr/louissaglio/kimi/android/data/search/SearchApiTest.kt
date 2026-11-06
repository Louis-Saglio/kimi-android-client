package fr.louissaglio.kimi.android.data.search

import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.testutil.testSearchRepository
import java.time.Instant
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The global-search mapping (ticket 146), driven through the repository facade
 * against a MockWebServer — the FileHistoryApiTest harness. */
class SearchApiTest {

    private val server = MockWebServer()
    private val repository = testSearchRepository()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun settings() = ServerSettings(serverUrl = server.url("/").toString())

    @Test
    fun `search posts the query and maps the documented stale-index page`() = runBlocking {
        // docs/server-api.md:2627-2630 — the verbatim data meta, captured live while
        // the index was mid-rebuild (stale:true); the items follow the documented
        // item shape (turn absent on the title hit, agent_id "" there).
        server.enqueue(
            MockResponse(body =
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"session_id":"session_1","workspace_id":"ws_1","session_title":"Deploy hotfix",
                   "agent_id":"main","role":"user","snippet":"why did the deploy fail yesterday",
                   "time":1754323200000,"turn":4,"score":1.43},
                  {"session_id":"session_2","workspace_id":"ws_1","session_title":"Infra notes",
                   "agent_id":"agent-3","role":"assistant","snippet":"the deploy failed because the runner",
                   "time":1754323260000,"turn":7,"step_id":"step_9","score":1.31},
                  {"session_id":"session_3","workspace_id":"ws_2","session_title":"deploy failed runbook",
                   "agent_id":"","role":"title","snippet":"deploy failed runbook",
                   "time":1754323320000,"score":0.9}
                ],"has_more": true, "page_token": "eyJ2IjoyLCJmIjoiU0R1ZlRzcHVzNzJxX056dSIsImciOjEsImIiOlsxLjQz…",
                 "index_state": {"state": "ready", "indexed_sessions": 291, "total_sessions": 291,
                                 "documents": 6313, "stale": true},
                 "source": "index"},"request_id":"01KY"}
                """.trimIndent(),
            ),
        )

        val page = repository.search(settings(), "deploy failed").getOrThrow()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/search", request.target)
        assertEquals("""{"query":"deploy failed","page_size":20}""", request.body!!.utf8())
        assertEquals(3, page.items.size)
        val first = page.items[0]
        assertEquals("session_1", first.sessionId)
        assertEquals("Deploy hotfix", first.sessionTitle)
        assertEquals(SearchHit.Role.User, first.role)
        assertEquals("why did the deploy fail yesterday", first.snippet)
        assertEquals(Instant.ofEpochMilli(1754323200000L), first.time)
        assertEquals(SearchHit.Role.Assistant, page.items[1].role)
        assertEquals(SearchHit.Role.Title, page.items[2].role)
        assertTrue(page.hasMore)
        assertEquals("eyJ2IjoyLCJmIjoiU0R1ZlRzcHVzNzJxX056dSIsImciOjEsImIiOlsxLjQz…", page.pageToken)
        assertTrue(page.indexStale)
    }

    @Test
    fun `search scoped to a session sends the container filter`() = runBlocking {
        // Ticket 147: container.session_id scopes the search to one session; the
        // answer's source is then "live" (docs/server-api.md:2611). container.agent_id
        // is ignored server-side and never sent.
        server.enqueue(
            MockResponse(body =
                """
                {"code":0,"msg":"success","data":{"items":[],"has_more":false,
                 "index_state":{"state":"ready","indexed_sessions":291,"total_sessions":291,"documents":6313},
                 "source":"live"},"request_id":"01KY"}
                """.trimIndent(),
            ),
        )

        repository.search(settings(), "deploy failed", sessionId = "session_1").getOrThrow()

        val request = server.takeRequest()
        assertEquals(
            """{"query":"deploy failed","page_size":20,"container":{"session_id":"session_1"}}""",
            request.body!!.utf8(),
        )
    }

    @Test
    fun `search passes the page token back verbatim and maps a current index`() = runBlocking {
        // A last page: has_more false, no token, and an index_state without
        // stale/degraded — no banner.
        server.enqueue(
            MockResponse(body =
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"session_id":"session_1","workspace_id":"ws_1","session_title":"Deploy hotfix",
                   "agent_id":"main","role":"user","snippet":"hit","time":1754323200000,"turn":4,"score":1.43}
                ],"has_more":false,
                 "index_state":{"state":"ready","indexed_sessions":291,"total_sessions":291,"documents":6313},
                 "source":"index"},"request_id":"01KY"}
                """.trimIndent(),
            ),
        )

        val page = repository.search(settings(), "deploy failed", pageToken = "eyJ2IjoxfQ==").getOrThrow()

        val request = server.takeRequest()
        assertEquals(
            """{"query":"deploy failed","page_size":20,"page_token":"eyJ2IjoxfQ=="}""",
            request.body!!.utf8(),
        )
        assertFalse(page.hasMore)
        assertNull(page.pageToken)
        assertFalse(page.indexStale)
    }

    @Test
    fun `search maps a degraded index to the banner flag`() = runBlocking {
        // 0.33.0's degraded: the last background refresh failed (docs/server-api.md).
        server.enqueue(
            MockResponse(body =
                """
                {"code":0,"msg":"success","data":{"items":[],"has_more":false,
                 "index_state":{"state":"ready","indexed_sessions":291,"total_sessions":291,
                                "documents":6313,"degraded":"refresh failed: disk full"},
                 "source":"index"},"request_id":"01KY"}
                """.trimIndent(),
            ),
        )

        val page = repository.search(settings(), "deploy failed").getOrThrow()

        assertTrue(page.indexStale)
    }

    @Test
    fun `search surfaces a rejected page token as the typed Api error`() = runBlocking {
        // A tampered token answers HTTP 200 with envelope 40001 (docs/server-api.md) —
        // the caller's documented signal to restart from page one.
        server.enqueue(
            MockResponse(body =
                """{"code":40001,"msg":"pageToken is malformed","data":null,"request_id":"01KY"}""",
            ),
        )

        val error = repository.search(settings(), "deploy failed", pageToken = "tampered").exceptionOrNull()

        assertTrue((error as? DataError.Api)?.code == 40001)
        assertEquals("pageToken is malformed", (error as DataError.Api).serverMessage)
    }

    @Test
    fun `search maps the disabled-index 50001 to SearchDisabled and leaves other 50001s alone`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """{"code":50001,"msg":"global search is disabled; set [database] global_search = true """ +
                    """or KIMI_CODE_GLOBAL_SEARCH=1 to enable it","data":null,"request_id":"01KZ"}""",
            ),
        )
        server.enqueue(
            MockResponse(body = """{"code":50001,"msg":"internal error","data":null,"request_id":"01L0"}"""),
        )

        assertEquals(DataError.SearchDisabled, repository.search(settings(), "deploy").exceptionOrNull())
        assertEquals(DataError.Api(50001, "internal error"), repository.search(settings(), "deploy").exceptionOrNull())
    }
}
