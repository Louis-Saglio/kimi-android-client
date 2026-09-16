package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.network.ApiClient
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The REST `/tasks` endpoints (docs/server-api.md, REST /tasks — ticket 092). */
class ChatApiTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val api = ChatApi(ApiClient(OkHttpClient(), json), ChatMessageMapper(json))

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun settings() = ServerSettings(serverUrl = server.url("/").toString())

    @Test
    fun `task list unwraps the envelope, maps the wire vocabulary and drops tool-kind entries`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"items":[""" +
                    // Verbatim task object (docs/server-api.md, REST /tasks).
                    """{"id":"agent-7noib8bb","session_id":"session_1","kind":"subagent",""" +
                    """"description":"doc-verify-bg","status":"completed",""" +
                    """"created_at":"2026-07-28T03:40:08.039Z","started_at":"2026-07-28T03:40:08.039Z",""" +
                    """"completed_at":"2026-07-28T03:40:22.372Z"},""" +
                    """{"id":"proc-9xk2","session_id":"session_1","kind":"bash",""" +
                    """"description":"sleep 1000","status":"running",""" +
                    """"created_at":"2026-07-28T03:41:00.000Z","started_at":"2026-07-28T03:41:00.000Z"},""" +
                    // Question flows surface as question cards in chat — never as jobs.
                    """{"id":"tool_q1","session_id":"session_1","kind":"tool",""" +
                    """"description":"Pick an option","status":"running",""" +
                    """"created_at":"2026-07-28T03:42:00.000Z","started_at":"2026-07-28T03:42:00.000Z"}""" +
                    """]},"request_id":"r"}""",
            ),
        )

        val tasks = api.listTasks(settings(), "session_1").getOrThrow()

        assertEquals(
            listOf(
                BackgroundTask(
                    id = "agent-7noib8bb",
                    kind = BackgroundTask.Kind.Subagent,
                    description = "doc-verify-bg",
                    status = BackgroundTask.Status.Completed,
                    createdAt = Instant.parse("2026-07-28T03:40:08.039Z"),
                    startedAt = Instant.parse("2026-07-28T03:40:08.039Z"),
                    completedAt = Instant.parse("2026-07-28T03:40:22.372Z"),
                ),
                BackgroundTask(
                    id = "proc-9xk2",
                    kind = BackgroundTask.Kind.Bash,
                    description = "sleep 1000",
                    status = BackgroundTask.Status.Running,
                    createdAt = Instant.parse("2026-07-28T03:41:00.000Z"),
                    startedAt = Instant.parse("2026-07-28T03:41:00.000Z"),
                ),
            ),
            tasks,
        )
    }

    @Test
    fun `task list sends the status filter only when provided`() = runBlocking {
        val body = """{"code":0,"msg":"success","data":{"items":[]},"request_id":"r"}"""
        server.enqueue(MockResponse(body = body))
        api.listTasks(settings(), "session_1", status = "running")
        assertEquals("/api/v1/sessions/session_1/tasks?status=running", server.takeRequest().target)

        server.enqueue(MockResponse(body = body))
        api.listTasks(settings(), "session_1")
        assertEquals("/api/v1/sessions/session_1/tasks", server.takeRequest().target)
    }

    @Test
    fun `cancel of a running task posts to the cancel endpoint and succeeds`() = runBlocking {
        server.enqueue(
            MockResponse(body = """{"code":0,"msg":"success","data":{"cancelled":true},"request_id":"r"}"""),
        )

        assertTrue(api.cancelTask(settings(), "session_1", "agent-7noib8bb").isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/tasks/agent-7noib8bb:cancel", request.target)
    }

    @Test
    fun `cancel of a terminal task surfaces the typed 40904`() = runBlocking {
        // Envelope-over-200 (docs/server-api.md): a terminal task answers code 40904
        // with data {"cancelled":false}.
        server.enqueue(
            MockResponse(body =
                """{"code":40904,"msg":"task is not running","data":{"cancelled":false,""" +
                    """"details":{"current_status":"completed"}},"request_id":"r"}""",
            ),
        )

        val error = api.cancelTask(settings(), "session_1", "agent-7noib8bb").exceptionOrNull()

        assertEquals(DataError.Api(40904, "task is not running"), error)
    }

    @Test
    fun `get task unwraps the envelope and parses command and output preview`() = runBlocking {
        // Verbatim bash task object with with_output (docs/server-api.md, REST /tasks —
        // ticket 096): bash-kind tasks carry `command`; the preview is the stored tail.
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"id":"bash-xbje00tt","kind":"bash",""" +
                    """"description":"tick counter loop","status":"completed",""" +
                    """"created_at":"2026-08-05T10:00:00.000Z","started_at":"2026-08-05T10:00:00.000Z",""" +
                    """"completed_at":"2026-08-05T10:02:00.000Z","command":"for i in …",""" +
                    """"output_preview":"tick 118\ntick 119\n","output_bytes":18},""" +
                    """"request_id":"r"}""",
            ),
        )

        val task = api.getTask(settings(), "session_1", "bash-xbje00tt").getOrThrow()

        assertEquals("/api/v1/sessions/session_1/tasks/bash-xbje00tt?with_output=true", server.takeRequest().target)
        assertEquals(
            BackgroundTask(
                id = "bash-xbje00tt",
                kind = BackgroundTask.Kind.Bash,
                description = "tick counter loop",
                status = BackgroundTask.Status.Completed,
                createdAt = Instant.parse("2026-08-05T10:00:00.000Z"),
                startedAt = Instant.parse("2026-08-05T10:00:00.000Z"),
                completedAt = Instant.parse("2026-08-05T10:02:00.000Z"),
                command = "for i in …",
                outputPreview = "tick 118\ntick 119\n",
                outputBytes = 18,
            ),
            task,
        )
    }

    @Test
    fun `get task omits the output query when not requested`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"id":"bash-xbje00tt","kind":"bash",""" +
                    """"description":"tick counter loop","status":"running",""" +
                    """"created_at":"2026-08-05T10:00:00.000Z","started_at":"2026-08-05T10:00:00.000Z"},""" +
                    """"request_id":"r"}""",
            ),
        )

        api.getTask(settings(), "session_1", "bash-xbje00tt", withOutput = false)

        assertEquals("/api/v1/sessions/session_1/tasks/bash-xbje00tt", server.takeRequest().target)
    }

    @Test
    fun `get task of an unknown id surfaces the typed 40406`() = runBlocking {
        // Envelope-over-200 (docs/server-api.md): an unknown task answers 40406.
        server.enqueue(
            MockResponse(body =
                """{"code":40406,"msg":"task not found","request_id":"r"}""",
            ),
        )

        val error = api.getTask(settings(), "session_1", "bash-nope").exceptionOrNull()

        assertEquals(DataError.Api(40406, "task not found"), error)
    }

    @Test
    fun `detach of a running foreground task posts to the detach endpoint and succeeds`() = runBlocking {
        // docs/server-api.md, REST /tasks (0.39.0 — ticket 138).
        server.enqueue(
            MockResponse(body = """{"code":0,"msg":"success","data":{"detached":true,"status":"running"},"request_id":"r"}"""),
        )

        assertTrue(api.detachTask(settings(), "session_1", "agent-rhh36xem").isSuccess)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/tasks/agent-rhh36xem:detach", request.target)
    }

    @Test
    fun `detach of an already-background or terminal task surfaces the refusal`() = runBlocking {
        // The refusal rides envelope code 0 (docs/server-api.md): success is
        // data.detached, not code == 0 — the dismissQuestion shape.
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"detached":false,"status":"completed"},"request_id":"r"}""",
            ),
        )

        val error = api.detachTask(settings(), "session_1", "agent-rhh36xem").exceptionOrNull()

        assertEquals(DataError.Api(0, "task is not a running foreground task (status: completed)"), error)
    }

    @Test
    fun `detach of an unknown task surfaces the typed 40406`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """{"code":40406,"msg":"task not found","request_id":"r"}""",
            ),
        )

        val error = api.detachTask(settings(), "session_1", "agent-nope").exceptionOrNull()

        assertEquals(DataError.Api(40406, "task not found"), error)
    }
}
