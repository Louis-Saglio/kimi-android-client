package fr.louissaglio.kimi.android.ui.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageBytesCacheTest {

    private fun key(fileId: String, sessionId: String? = "session_1", serverUrl: String = "http://agent.example.com") =
        ImageBytesCache.Key(serverUrl, sessionId, fileId)

    private fun bytes(size: Int) = ByteArray(size) { it.toByte() }

    @Test
    fun `a stored entry is returned for its key only`() {
        val cache = ImageBytesCache(maxBytes = 100)
        cache.put(key("f_1"), bytes(10))

        assertArrayEquals(bytes(10), cache.get(key("f_1")))
        assertNull(cache.get(key("f_1", sessionId = "session_2")))
        assertNull(cache.get(key("f_1", sessionId = null)))
        assertNull(cache.get(key("f_1", serverUrl = "http://other.example.com")))
    }

    @Test
    fun `going over the byte budget evicts the least recently used entries`() {
        val cache = ImageBytesCache(maxBytes = 100)
        cache.put(key("a"), bytes(40))
        cache.put(key("b"), bytes(40))
        cache.get(key("a"))

        cache.put(key("c"), bytes(40))

        assertNull(cache.get(key("b")))
        assertArrayEquals(bytes(40), cache.get(key("a")))
        assertArrayEquals(bytes(40), cache.get(key("c")))
    }

    @Test
    fun `replacing an entry releases the old entry's bytes from the budget`() {
        val cache = ImageBytesCache(maxBytes = 100)
        cache.put(key("a"), bytes(60))
        cache.put(key("a"), bytes(30))

        cache.put(key("b"), bytes(70))

        assertArrayEquals(bytes(30), cache.get(key("a")))
        assertArrayEquals(bytes(70), cache.get(key("b")))
    }

    @Test
    fun `an entry larger than the whole budget is not kept and evicts nothing`() {
        val cache = ImageBytesCache(maxBytes = 100)
        cache.put(key("a"), bytes(50))

        cache.put(key("huge"), bytes(101))

        assertNull(cache.get(key("huge")))
        assertArrayEquals(bytes(50), cache.get(key("a")))
    }
}
