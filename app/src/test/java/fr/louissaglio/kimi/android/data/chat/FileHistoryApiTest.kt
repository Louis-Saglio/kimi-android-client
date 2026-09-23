package fr.louissaglio.kimi.android.data.chat

import androidx.datastore.preferences.core.emptyPreferences
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.testutil.testChatRepository
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

/** The turn file-history mapping (ticket 149), driven through the repository facade
 * against a MockWebServer — the ChatRepositoryTest harness. */
class FileHistoryApiTest {

    private val server = MockWebServer()
    private val repository = testChatRepository(FakePreferencesDataStore(emptyPreferences()))

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun settings() = ServerSettings(serverUrl = server.url("/").toString())

    @Test
    fun `getChanges maps the documented shape including a binary row`() = runBlocking {
        // docs/server-api.md, Turn file history: {changes:[{path, status, additions,
        // deletions, binary?, oversize?}], recorded}.
        server.enqueue(
            MockResponse(body =
                """
                {"code":0,"msg":"success","data":{"changes":[
                  {"path":"/home/ubuntu/spike/notes.txt","status":"modified","additions":3,"deletions":1},
                  {"path":"/home/ubuntu/spike/logo.png","status":"added","additions":0,"deletions":0,"binary":true},
                  {"path":"/home/ubuntu/spike/huge.log","status":"deleted","additions":0,"deletions":42,"oversize":true}
                ],"recorded":true},"request_id":"01KX"}
                """.trimIndent(),
            ),
        )

        val page = repository.getFileChanges(settings(), "session_1", 2).getOrThrow()

        assertEquals("/api/v1/sessions/session_1/file-history/changes?turn_id=2", server.takeRequest().target)
        assertTrue(page.recorded)
        assertEquals(
            listOf(
                FileChange("/home/ubuntu/spike/notes.txt", FileChange.Status.Modified, additions = 3, deletions = 1),
                FileChange(
                    "/home/ubuntu/spike/logo.png",
                    FileChange.Status.Added,
                    additions = 0,
                    deletions = 0,
                    binary = true,
                ),
                FileChange(
                    "/home/ubuntu/spike/huge.log",
                    FileChange.Status.Deleted,
                    additions = 0,
                    deletions = 42,
                    oversize = true,
                ),
            ),
            page.changes,
        )
    }

    @Test
    fun `getChanges maps an unrecorded turn to the empty list`() = runBlocking {
        // A turn with nothing recorded answers {"changes":[],"recorded":false}
        // (docs/server-api.md — live-verified).
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"changes":[],"recorded":false},"request_id":"01KX"}""",
            ),
        )

        val page = repository.getFileChanges(settings(), "session_1", 0).getOrThrow()

        assertTrue(page.changes.isEmpty())
        assertFalse(page.recorded)
    }

    @Test
    fun `getChanges maps an unknown session to an Api error`() = runBlocking {
        // The route's only declared error (docs/server-api.md): SESSION_NOT_FOUND.
        server.enqueue(
            MockResponse(body =
                """{"code":40401,"msg":"session session_1 does not exist","data":null,"request_id":"01KX"}""",
            ),
        )

        val error = repository.getFileChanges(settings(), "session_1", 0).exceptionOrNull()

        assertTrue((error as? DataError.Api)?.code == 40401)
    }

    @Test
    fun `getContent maps both phases with the path URL-encoded`() = runBlocking {
        // docs/server-api.md, Turn file history: {content:{version, content?,
        // binary?} | null} — the path rides as an encoded query param (ticket 150).
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"content":{"version":3,"content":"before text"}},"request_id":"01KX"}""",
            ),
        )
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"content":{"version":4,"content":"after text"}},"request_id":"01KX"}""",
            ),
        )

        val before = repository.getFileContent(settings(), "session_1", 2, "/home/ubuntu/spike/notes.txt", FilePhase.Start)
            .getOrThrow()
        val after = repository.getFileContent(settings(), "session_1", 2, "/home/ubuntu/spike/notes.txt", FilePhase.End)
            .getOrThrow()

        assertEquals(
            "/api/v1/sessions/session_1/file-history/content?turn_id=2" +
                "&path=%2Fhome%2Fubuntu%2Fspike%2Fnotes.txt&phase=start",
            server.takeRequest().target,
        )
        assertEquals(
            "/api/v1/sessions/session_1/file-history/content?turn_id=2" +
                "&path=%2Fhome%2Fubuntu%2Fspike%2Fnotes.txt&phase=end",
            server.takeRequest().target,
        )
        assertEquals(FileContent(version = 3, content = "before text", binary = false), before)
        assertEquals(FileContent(version = 4, content = "after text", binary = false), after)
    }

    @Test
    fun `getContent maps a null content to a null snapshot`() = runBlocking {
        // A file that did not exist at the phase answers {"content":null}
        // (docs/server-api.md) — an added file's start, a deleted file's end.
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"content":null},"request_id":"01KX"}""",
            ),
        )

        val snapshot = repository.getFileContent(settings(), "session_1", 2, "/tmp/new.txt", FilePhase.Start).getOrThrow()

        assertNull(snapshot)
    }

    @Test
    fun `getContent maps a binary snapshot without text`() = runBlocking {
        // A binary file's snapshot carries no content string — the viewer renders
        // its placeholder, no further fetch.
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"content":{"version":1,"binary":true}},"request_id":"01KX"}""",
            ),
        )

        val snapshot = repository.getFileContent(settings(), "session_1", 2, "/tmp/logo.png", FilePhase.End).getOrThrow()

        assertEquals(FileContent(version = 1, content = null, binary = true), snapshot)
    }
}
