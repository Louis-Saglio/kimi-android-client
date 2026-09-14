package fr.louissaglio.kimi.android.data.sessions

import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.testutil.testSessionsRepository
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

class SessionsRepositoryTest {

    private val server = MockWebServer()
    private val repository = testSessionsRepository()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun settings() = ServerSettings(serverUrl = server.url("/").toString())

    @Test
    fun `envelope items are mapped to domain sessions`() = runBlocking {
        server.enqueue(
            MockResponse(body = 
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"id":"session_1","title":"Build an Android clone","created_at":"2026-07-17T03:34:49.193Z",
                   "updated_at":"2026-07-17T03:48:31.606Z","busy":true,"pending_interaction":"approval",
                   "archived":false,
                   "metadata":{"cwd":"/home/ubuntu/kimi-android-client"},
                   "usage":{"input_tokens":0},"permission_rules":[],"message_count":0,"last_seq":0},
                  {"id":"session_2","title":"","updated_at":"2026-07-16T10:00:00.000Z",
                   "busy":false,"metadata":{"cwd":"/tmp/spike"}},
                  {"id":"session_3","title":"Question pending","updated_at":"2026-07-16T11:00:00.000Z",
                   "busy":true,"pending_interaction":"question","metadata":{"cwd":"/tmp/q"}}
                ],"has_more":true},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val result = repository.listSessions(settings())

        val page = result.getOrThrow()
        // First page: no pagination cursor is sent.
        assertEquals("/api/v1/sessions", server.takeRequest().target)
        assertTrue(page.hasMore)
        val sessions = page.items
        assertEquals(3, sessions.size)
        assertEquals(
            Session(
                id = "session_1",
                title = "Build an Android clone",
                cwd = "/home/ubuntu/kimi-android-client",
                updatedAt = Instant.parse("2026-07-17T03:48:31.606Z"),
                isRunning = true,
                isAwaitingInteraction = true,
            ),
            sessions[0],
        )
        // Blank title maps to null so the UI falls back to the cwd; busy maps to isRunning.
        assertNull(sessions[1].title)
        assertEquals(false, sessions[1].isRunning)
        // No pending_interaction in the payload defaults to "none" — no badge.
        assertFalse(sessions[1].isAwaitingInteraction)
        // A pending question sets the badge too: approvals AND questions read as
        // "needs me" (ticket 087 — one rule for the REST mapping and the WS patch).
        assertTrue(sessions[2].isAwaitingInteraction)
    }

    @Test
    fun `next page sends before_id and parses has_more`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"id":"session_21","title":"Older session","updated_at":"2026-07-10T10:00:00.000Z",
                   "busy":false,"metadata":{"cwd":"/tmp/old"}}
                ],"has_more":false},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val result = repository.listSessions(settings(), beforeId = "session_20")

        val page = result.getOrThrow()
        assertEquals("/api/v1/sessions?before_id=session_20", server.takeRequest().target)
        assertEquals(listOf("session_21"), page.items.map { it.id })
        assertFalse(page.hasMore)
    }

    @Test
    fun `workspace filter sends workspace_id alongside the before_id cursor`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"id":"session_6","title":"Older in workspace","updated_at":"2026-07-10T10:00:00.000Z",
                   "busy":false,"metadata":{"cwd":"/tmp/spike"}}
                ],"has_more":true},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val result = repository.listSessions(settings(), beforeId = "session_5", workspaceId = "wd_spike")

        // The grouped view's show-more expansion (ticket 116): the workspace filter
        // and the cursor ride the same v1 call.
        assertEquals("/api/v1/sessions?workspace_id=wd_spike&before_id=session_5", server.takeRequest().target)
        assertEquals(listOf("session_6"), result.getOrThrow().items.map { it.id })
        assertTrue(result.getOrThrow().hasMore)
    }

    @Test
    fun `non-zero envelope code maps to Api error`() = runBlocking {
        server.enqueue(
            MockResponse(body = """{"code":50001,"msg":"boom","data":null,"request_id":"01KX"}"""),
        )

        val result = repository.listSessions(settings())

        assertEquals(DataError.Api(50001, "boom"), result.exceptionOrNull())
    }

    @Test
    fun `http 401 maps to Auth error`() = runBlocking {
        server.enqueue(MockResponse(code = 401))

        val result = repository.listSessions(settings())

        assertTrue(result.exceptionOrNull() is DataError.Auth)
    }

    @Test
    fun `unreachable host maps to Network error`() = runBlocking {
        val deadServer = MockWebServer()
        deadServer.start()
        val deadSettings = ServerSettings(serverUrl = deadServer.url("/").toString())
        deadServer.close()

        val result = repository.listSessions(deadSettings)

        assertTrue(result.exceptionOrNull() is DataError.Network)
    }

    @Test
    fun `invalid base URL maps to InvalidUrl error`() = runBlocking {
        val result = repository.listSessions(ServerSettings(serverUrl = "not a url"))

        assertTrue(result.exceptionOrNull() is DataError.InvalidUrl)
    }

    @Test
    fun `create posts cwd and title and maps the created session`() = runBlocking {
        server.enqueue(MockResponse(body = CREATED_SESSION_BODY))

        val result = repository.createSession(settings(), "/tmp/spike-008", "My session")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions", request.target)
        assertEquals("""{"metadata":{"cwd":"/tmp/spike-008"},"title":"My session"}""", request.body!!.utf8())
        assertEquals(
            Session(
                id = "session_new",
                title = "My session",
                cwd = "/tmp/spike-008",
                updatedAt = Instant.parse("2026-07-19T05:29:30.815Z"),
                isRunning = false,
                isAwaitingInteraction = false,
            ),
            result.getOrThrow(),
        )
    }

    @Test
    fun `create without title omits the field from the body`() = runBlocking {
        server.enqueue(MockResponse(body = CREATED_SESSION_BODY))

        repository.createSession(settings(), "/tmp/spike-008", null)

        assertEquals("""{"metadata":{"cwd":"/tmp/spike-008"}}""", server.takeRequest().body!!.utf8())
    }

    @Test
    fun `create surfaces a non-zero envelope code as Api error`() = runBlocking {
        // Shape of the server's refusal for a missing directory (verified live 2026-07-19).
        server.enqueue(
            MockResponse(body = 
                """{"code":40409,"msg":"workspace root /tmp/nope does not exist","data":null,"request_id":"01KX"}""",
            ),
        )

        val error = repository.createSession(settings(), "/tmp/nope", null).exceptionOrNull()

        assertEquals(DataError.Api(40409, "workspace root /tmp/nope does not exist"), error)
    }

    @Test
    fun `fork posts an empty body and maps the forked session`() = runBlocking {
        server.enqueue(MockResponse(body = FORKED_SESSION_BODY))

        val result = repository.forkSession(settings(), "session_d6c12410")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_d6c12410:fork", request.target)
        assertEquals("{}", request.body!!.utf8())
        assertEquals(
            Session(
                id = "session_4536c55c",
                title = "Fork: Fork source",
                cwd = "/home/ubuntu/spike",
                // Inherited from the source — it predates the fork's created_at.
                updatedAt = Instant.parse("2026-09-14T09:27:37.222Z"),
                isRunning = false,
                isAwaitingInteraction = false,
            ),
            result.getOrThrow(),
        )
    }

    @Test
    fun `fork surfaces the unknown-session envelope as Api error`() = runBlocking {
        // Verified live 2026-09-14 on 0.42.0 (docs/server-api.md; the stack is omitted).
        server.enqueue(
            MockResponse(body =
                """{"code":40401,"msg":"session session_nope does not exist","data":null,"request_id":"01KX"}""",
            ),
        )

        val error = repository.forkSession(settings(), "session_nope").exceptionOrNull()

        assertEquals(DataError.Api(40401, "session session_nope does not exist"), error)
    }

    @Test
    fun `fork surfaces the busy-source envelope as Api error`() = runBlocking {
        // Verbatim 0.42.0 capture (verified live 2026-09-14, docs/server-api.md).
        server.enqueue(
            MockResponse(body =
                """{"code":40901,"msg":"Session \"session_d6c12410\" cannot be forked while a turn is running",""" +
                    """"data":null,"request_id":"01KX"}""",
            ),
        )

        val error = repository.forkSession(settings(), "session_d6c12410").exceptionOrNull()

        assertEquals(
            DataError.Api(40901, "Session \"session_d6c12410\" cannot be forked while a turn is running"),
            error,
        )
    }

    @Test
    fun `grouped fetch hits the v2 by_workspace endpoint and maps groups onto domain sessions`() = runBlocking {
        server.enqueue(MockResponse(body = GROUPS_BODY))

        val result = repository.listSessionGroups(settings())

        assertEquals("/api/v2/sessions?view=by_workspace&group.page_size=5", server.takeRequest().target)
        val groups = result.getOrThrow()
        assertEquals(2, groups.size)
        // Group fields come from the group envelope, not the items.
        val spike = groups[0]
        assertEquals("wd_spike", spike.workspaceId)
        assertEquals("/tmp/spike", spike.cwd)
        // total is the workspace's full server-side count, not the page size.
        assertEquals(42, spike.total)
        assertEquals(4, spike.sessions.size)
        // v2 mapping: epoch-ms updated_at, cwd from the item's workspace, and the
        // activity.status state space — running → dot, approval/question → badge,
        // idle → neither.
        assertEquals(
            Session(
                id = "session_running",
                title = "Running",
                cwd = "/tmp/spike",
                updatedAt = Instant.ofEpochMilli(1787253993333),
                isRunning = true,
                isAwaitingInteraction = false,
            ),
            spike.sessions[0],
        )
        assertFalse(spike.sessions[1].isRunning)
        assertTrue(spike.sessions[1].isAwaitingInteraction)
        assertFalse(spike.sessions[2].isRunning)
        assertTrue(spike.sessions[2].isAwaitingInteraction)
        // A null title stays null so the UI falls back to the cwd.
        assertNull(spike.sessions[3].title)
        assertFalse(spike.sessions[3].isRunning)
        assertFalse(spike.sessions[3].isAwaitingInteraction)
        assertEquals(listOf("wd_other"), groups[1].let { listOf(it.workspaceId) })
        assertEquals(1, groups[1].total)
    }

    @Test
    fun `grouped fetch surfaces a non-zero envelope code as Api error`() = runBlocking {
        server.enqueue(
            MockResponse(body = """{"code":40001,"msg":"bad view","data":null,"request_id":"01KX"}"""),
        )

        assertEquals(DataError.Api(40001, "bad view"), repository.listSessionGroups(settings()).exceptionOrNull())
    }

    @Test
    fun `workspaces are sorted by last_opened_at descending`() = runBlocking {
        server.enqueue(
            MockResponse(body = 
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"id":"wd_old","root":"/tmp/old","name":"old",
                   "created_at":"2026-07-01T00:00:00.000Z",
                   "last_opened_at":"2026-07-10T10:00:00.000Z","session_count":2},
                  {"id":"wd_new","root":"/home/ubuntu","name":"ubuntu",
                   "created_at":"2026-07-01T00:00:00.000Z",
                   "last_opened_at":"2026-07-22T03:30:42.000Z","session_count":81},
                  {"id":"wd_root","root":"/","name":"",
                   "created_at":"2026-07-01T00:00:00.000Z",
                   "last_opened_at":"2026-07-15T00:00:00.000Z","session_count":1}
                ]},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val result = repository.listWorkspaces(settings())

        val workspaces = result.getOrThrow()
        assertEquals("/api/v1/workspaces", server.takeRequest().target)
        // Server order is not relied upon: client-side sort by last_opened_at desc.
        assertEquals(listOf("wd_new", "wd_root", "wd_old"), workspaces.map { it.id })
        assertEquals(Instant.parse("2026-07-22T03:30:42.000Z"), workspaces[0].lastOpenedAt)
        // Empty name is kept as-is; the UI falls back to the root for the label.
        assertEquals("", workspaces[1].name)
    }

    @Test
    fun `get fetches one session by id`() = runBlocking {
        server.enqueue(MockResponse(body = CREATED_SESSION_BODY))

        val result = repository.getSession(settings(), "session_new")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/sessions/session_new", request.target)
        assertEquals("session_new", result.getOrThrow().id)
        assertEquals("My session", result.getOrThrow().title)
    }

    @Test
    fun `get surfaces a non-zero envelope code as Api error`() = runBlocking {
        server.enqueue(
            MockResponse(body = 
                """{"code":40404,"msg":"session not found","data":null,"request_id":"01KX"}""",
            ),
        )

        assertTrue(repository.getSession(settings(), "session_nope").exceptionOrNull() is DataError.Api)
    }

    private companion object {
        const val CREATED_SESSION_BODY = """
            {"code":0,"msg":"success","data":{"id":"session_new","workspace_id":"wd_spike-008_1",
             "title":"My session","created_at":"2026-07-19T05:29:30.796Z",
             "updated_at":"2026-07-19T05:29:30.815Z","busy":false,"archived":false,
             "metadata":{"cwd":"/tmp/spike-008"},"agent_config":{"model":""},
             "usage":{"input_tokens":0},"permission_rules":[],"message_count":0,"last_seq":0},
             "request_id":"01KX"}
        """

        // Verbatim 0.42.0 fork capture (docs/server-api.md, verified live 2026-09-14),
        // ids shortened: the fork inherits the source's workspace/cwd, and its
        // updated_at predates its own created_at (copied from the source).
        const val FORKED_SESSION_BODY = """
            {"code":0,"msg":"success","data":{"id":"session_4536c55c","workspace_id":"wd_spike_6baf4c130e63",
             "title":"Fork: Fork source","created_at":"2026-09-14T09:27:49.767Z",
             "updated_at":"2026-09-14T09:27:37.222Z","busy":false,"archived":false,
             "metadata":{"cwd":"/home/ubuntu/spike"},"agent_config":{"model":""},
             "usage":{"input_tokens":0},"permission_rules":[],"message_count":0,"last_seq":0},
             "request_id":"01M2FKX17KGY62AT5CXNJYMRJH"}
        """

        // v2 wire shape (docs/server-api.md): epoch-ms timestamps, present-but-null
        // fields, activity.status carrying the live state. Extra keys (last_prompt,
        // archived_at, activity.model) are ignored like every unconsumed field.
        const val GROUPS_BODY = """
            {"code":0,"msg":"success","data":{"groups":[
              {"workspace":{"id":"wd_spike","cwd":"/tmp/spike"},
               "sessions":[
                 {"id":"session_running",
                  "workspace":{"id":"wd_spike","cwd":"/tmp/spike"},
                  "meta":{"title":"Running","last_prompt":null,"created_at":1787253993333,
                          "updated_at":1787253993333,"archived":false,"archived_at":null},
                  "activity":{"status":"running","model":""}},
                 {"id":"session_approval",
                  "workspace":{"id":"wd_spike","cwd":"/tmp/spike"},
                  "meta":{"title":"Approval","last_prompt":null,"created_at":1787253993333,
                          "updated_at":1787253993333,"archived":false,"archived_at":null},
                  "activity":{"status":"approval","model":""}},
                 {"id":"session_question",
                  "workspace":{"id":"wd_spike","cwd":"/tmp/spike"},
                  "meta":{"title":"Question","last_prompt":null,"created_at":1787253993333,
                          "updated_at":1787253993333,"archived":false,"archived_at":null},
                  "activity":{"status":"question","model":""}},
                 {"id":"session_idle",
                  "workspace":{"id":"wd_spike","cwd":"/tmp/spike"},
                  "meta":{"title":null,"last_prompt":null,"created_at":1787253993333,
                          "updated_at":1787253993333,"archived":false,"archived_at":null},
                  "activity":{"status":"idle","model":""}}],
               "total":42},
              {"workspace":{"id":"wd_other","cwd":"/tmp/other"},
               "sessions":[
                 {"id":"session_other",
                  "workspace":{"id":"wd_other","cwd":"/tmp/other"},
                  "meta":{"title":"Other","last_prompt":null,"created_at":1787253993333,
                          "updated_at":1787253993333,"archived":false,"archived_at":null},
                  "activity":{"status":"idle","model":""}}],
               "total":1}],
             "total":2,"has_more":false,"next_page_token":null},
            "request_id":"01KX"}
        """
    }
}
