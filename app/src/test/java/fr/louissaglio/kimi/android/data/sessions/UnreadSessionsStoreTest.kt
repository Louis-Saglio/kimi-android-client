package fr.louissaglio.kimi.android.data.sessions

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnreadSessionsStoreTest {

    private lateinit var dir: File
    private lateinit var store: UnreadSessionsStore

    @Before
    fun setUp() {
        dir = createTempDirectory("unread-sessions-store-test").toFile()
        store = UnreadSessionsStore(dir, Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `marks round-trip and clear removes them`() {
        assertEquals(emptySet<String>(), store.unreadIds("https://a").value)

        store.mark("https://a", "session_1")
        store.mark("https://a", "session_2")
        assertEquals(setOf("session_1", "session_2"), store.unreadIds("https://a").value)

        store.clear("https://a", "session_1")
        assertEquals(setOf("session_2"), store.unreadIds("https://a").value)
    }

    @Test
    fun `servers are isolated`() {
        store.mark("https://a", "session_1")

        assertEquals(emptySet<String>(), store.unreadIds("https://b").value)
    }

    @Test
    fun `marks survive a store recreation`() {
        store.mark("https://a", "session_1")

        val reloaded = UnreadSessionsStore(dir, Json { ignoreUnknownKeys = true })
        assertEquals(setOf("session_1"), reloaded.unreadIds("https://a").value)
    }

    @Test
    fun `a corrupt file is treated as empty and deleted`() {
        store.mark("https://a", "session_1")
        val file = dir.listFiles()!!.single()
        file.writeText("not json")

        val reloaded = UnreadSessionsStore(dir, Json { ignoreUnknownKeys = true })
        assertEquals(emptySet<String>(), reloaded.unreadIds("https://a").value)
        assertFalse(file.exists())
    }

    @Test
    fun `the flow republishes on mark and clear`() = runTest {
        val seen = mutableListOf<Set<String>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { store.unreadIds("https://a").toList(seen) }

        store.mark("https://a", "session_1")
        store.clear("https://a", "session_1")

        assertEquals(listOf(emptySet(), setOf("session_1"), emptySet<String>()), seen)
        job.cancel()
    }
}
