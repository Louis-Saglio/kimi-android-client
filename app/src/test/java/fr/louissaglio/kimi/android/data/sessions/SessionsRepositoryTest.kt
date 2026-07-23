package fr.louissaglio.kimi.android.data.sessions

import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.time.Instant
import kotlinx.coroutines.runBlocking
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

class SessionsRepositoryTest {

    private val server = MockWebServer()
    private val repository = SessionsRepository(OkHttpClient(), Json { ignoreUnknownKeys = true })

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun settings() = ServerSettings(serverUrl = server.url("/").toString())

    @Test
    fun `envelope items are mapped to domain sessions`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"code":0,"msg":"success","data":{"items":[
                  {"id":"session_1","title":"Build an Android clone","created_at":"2026-07-17T03:34:49.193Z",
                   "updated_at":"2026-07-17T03:48:31.606Z","busy":true,"pending_interaction":"approval",
                   "archived":false,
                   "metadata":{"cwd":"/home/ubuntu/kimi-android-client"},
                   "usage":{"input_tokens":0},"permission_rules":[],"message_count":0,"last_seq":0},
                  {"id":"session_2","title":"","updated_at":"2026-07-16T10:00:00.000Z",
                   "busy":false,"metadata":{"cwd":"/tmp/spike"}}
                ],"has_more":true},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val result = repository.listSessions(settings())

        val page = result.getOrThrow()
        // First page: no pagination cursor is sent.
        assertEquals("/api/v1/sessions", server.takeRequest().path)
        assertTrue(page.hasMore)
        val sessions = page.items
        assertEquals(2, sessions.size)
        assertEquals(
            Session(
                id = "session_1",
                title = "Build an Android clone",
                cwd = "/home/ubuntu/kimi-android-client",
                updatedAt = Instant.parse("2026-07-17T03:48:31.606Z"),
                isRunning = true,
                isAwaitingApproval = true,
            ),
            sessions[0],
        )
        // Blank title maps to null so the UI falls back to the cwd; busy maps to isRunning.
        assertNull(sessions[1].title)
        assertEquals(false, sessions[1].isRunning)
        // No pending_interaction in the payload defaults to "none" — no approval flag.
        assertFalse(sessions[1].isAwaitingApproval)
    }

    @Test
    fun `next page sends before_id and parses has_more`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
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
        assertEquals("/api/v1/sessions?before_id=session_20", server.takeRequest().path)
        assertEquals(listOf("session_21"), page.items.map { it.id })
        assertFalse(page.hasMore)
    }

    @Test
    fun `non-zero envelope code maps to Api error`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"code":50001,"msg":"boom","data":null,"request_id":"01KX"}"""),
        )

        val result = repository.listSessions(settings())

        val error = result.exceptionOrNull()
        assertTrue(error is DataError.Api)
        assertEquals(50001, (error as DataError.Api).code)
    }

    @Test
    fun `http 401 maps to Auth error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = repository.listSessions(settings())

        assertTrue(result.exceptionOrNull() is DataError.Auth)
    }

    @Test
    fun `unreachable host maps to Network error`() = runBlocking {
        val deadServer = MockWebServer()
        deadServer.start()
        val deadSettings = ServerSettings(serverUrl = deadServer.url("/").toString())
        deadServer.shutdown()

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
        server.enqueue(MockResponse().setBody(CREATED_SESSION_BODY))

        val result = repository.createSession(settings(), "/tmp/spike-008", "My session")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions", request.path)
        assertEquals("""{"metadata":{"cwd":"/tmp/spike-008"},"title":"My session"}""", request.body.readUtf8())
        assertEquals(
            Session(
                id = "session_new",
                title = "My session",
                cwd = "/tmp/spike-008",
                updatedAt = Instant.parse("2026-07-19T05:29:30.815Z"),
                isRunning = false,
                isAwaitingApproval = false,
            ),
            result.getOrThrow(),
        )
    }

    @Test
    fun `create without title omits the field from the body`() = runBlocking {
        server.enqueue(MockResponse().setBody(CREATED_SESSION_BODY))

        repository.createSession(settings(), "/tmp/spike-008", null)

        assertEquals("""{"metadata":{"cwd":"/tmp/spike-008"}}""", server.takeRequest().body.readUtf8())
    }

    @Test
    fun `create surfaces a non-zero envelope code as Api error`() = runBlocking {
        // Shape of the server's refusal for a missing directory (verified live 2026-07-19).
        server.enqueue(
            MockResponse().setBody(
                """{"code":40409,"msg":"workspace root /tmp/nope does not exist","data":null,"request_id":"01KX"}""",
            ),
        )

        val error = repository.createSession(settings(), "/tmp/nope", null).exceptionOrNull()

        assertTrue(error is DataError.Api)
        assertEquals("workspace root /tmp/nope does not exist", (error as DataError.Api).serverMessage)
    }

    @Test
    fun `workspaces are sorted by last_opened_at descending`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
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
        assertEquals("/api/v1/workspaces", server.takeRequest().path)
        // Server order is not relied upon: client-side sort by last_opened_at desc.
        assertEquals(listOf("wd_new", "wd_root", "wd_old"), workspaces.map { it.id })
        assertEquals(Instant.parse("2026-07-22T03:30:42.000Z"), workspaces[0].lastOpenedAt)
        // Empty name is kept as-is; the UI falls back to the root for the label.
        assertEquals("", workspaces[1].name)
    }

    @Test
    fun `get fetches one session by id`() = runBlocking {
        server.enqueue(MockResponse().setBody(CREATED_SESSION_BODY))

        val result = repository.getSession(settings(), "session_new")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/sessions/session_new", request.path)
        assertEquals("session_new", result.getOrThrow().id)
        assertEquals("My session", result.getOrThrow().title)
    }

    @Test
    fun `get surfaces a non-zero envelope code as Api error`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
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
    }
}
