package fr.louissaglio.kimi.android.data.sessions

import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionListCacheTest {

    private lateinit var dir: File
    private lateinit var cache: SessionListCache

    @Before
    fun setUp() {
        dir = createTempDirectory("session-list-cache-test").toFile()
        cache = SessionListCache(dir, Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a saved list loads back field for field`() {
        val sessions = listOf(
            Session(
                id = "session_1",
                title = "First",
                cwd = "/tmp/first",
                updatedAt = Instant.parse("2026-07-17T03:48:31.606Z"),
                isRunning = true,
                isAwaitingInteraction = true,
                // The interrupted badge survives the cold start (ticket 159).
                isLastTurnInterrupted = true,
            ),
            // Null title and cwd round-trip as null, not as empty strings.
            Session(
                id = "session_2",
                title = null,
                cwd = null,
                updatedAt = Instant.parse("2026-08-02T06:00:00.000Z"),
                isRunning = false,
                isAwaitingInteraction = false,
            ),
        )

        cache.save("http://server-a", sessions, hasMore = true)

        assertEquals(SessionListCache.Entry(sessions, hasMore = true), cache.load("http://server-a"))
    }

    @Test
    fun `loading an unknown server returns null`() {
        assertNull(cache.load("http://never-saved"))
    }

    @Test
    fun `a corrupted file decodes as absent and is deleted`() {
        cache.save("http://server-a", listOf(session("session_1")), hasMore = false)
        val file = dir.listFiles()!!.single()
        file.writeText("this is not json {")

        assertNull(cache.load("http://server-a"))
        assertFalse(file.exists())
        // The next save starts from a clean slate.
        cache.save("http://server-a", listOf(session("session_1")), hasMore = false)
        assertEquals(listOf("session_1"), cache.load("http://server-a")!!.sessions.map { it.id })
    }

    @Test
    fun `different base urls keep independent caches`() {
        cache.save("http://server-a", listOf(session("session_a")), hasMore = true)
        cache.save("http://server-b", listOf(session("session_b1"), session("session_b2")), hasMore = false)

        assertEquals(
            SessionListCache.Entry(listOf(session("session_a")), hasMore = true),
            cache.load("http://server-a"),
        )
        assertEquals(
            SessionListCache.Entry(listOf(session("session_b1"), session("session_b2")), hasMore = false),
            cache.load("http://server-b"),
        )
        // One file per server, and overwriting one leaves the other untouched.
        assertEquals(2, dir.listFiles()!!.size)
        cache.save("http://server-a", emptyList(), hasMore = false)
        assertEquals(emptyList<Session>(), cache.load("http://server-a")!!.sessions)
        assertEquals(2, cache.load("http://server-b")!!.sessions.size)
    }

    private fun session(id: String) = Session(
        id = id,
        title = "Title $id",
        cwd = "/tmp/$id",
        updatedAt = Instant.parse("2026-07-17T03:48:31.606Z"),
        isRunning = false,
        isAwaitingInteraction = false,
    )
}
