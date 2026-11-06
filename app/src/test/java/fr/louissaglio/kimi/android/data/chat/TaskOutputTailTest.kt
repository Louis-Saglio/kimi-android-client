package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The job screen's output tail (ticket 180): `task.output` chunks assembled by UTF-8
 * byte offset — duplicates ignored, gaps visible, memory bounded. */
class TaskOutputTailTest {

    @Test
    fun `contiguous chunks concatenate and the end is a byte offset`() {
        val tail = TaskOutputTail()
            .append(0, "tick 1\n")
            .append(7, "tick 2\n")

        assertEquals("tick 1\ntick 2\n", tail.render())
        assertEquals(0L, tail.startOffset)
        assertEquals(14L, tail.endOffset)
    }

    @Test
    fun `offsets count UTF-8 bytes, not characters`() {
        // "héllo ✓\n" is 8 characters but 11 bytes (é = 2, ✓ = 3).
        val first = "héllo ✓\n"
        val tail = TaskOutputTail().append(0, first).append(11, "next")

        assertEquals(15L, tail.endOffset)
        assertEquals("héllo ✓\nnext", tail.render())
        // A chunk placed by character count (8) is an overlap, not new text.
        assertSame(tail, tail.append(8, "llo"))
    }

    @Test
    fun `an empty first chunk is held and the next one continues from it`() {
        val empty = TaskOutputTail().append(0, "")
        assertTrue(!empty.isEmpty)
        assertEquals(0L, empty.endOffset)
        assertEquals("", empty.render())

        assertEquals("watched-ok\n", empty.append(0, "watched-ok\n").render())
    }

    @Test
    fun `a duplicate chunk is ignored and an overlapping one keeps only its new bytes`() {
        val tail = TaskOutputTail().append(0, "abc").append(3, "def")

        assertSame(tail, tail.append(3, "def"))
        assertSame(tail, tail.append(0, "ab"))
        val overlapped = tail.append(4, "efgh")
        assertEquals("abcdefgh", overlapped.render())
        assertEquals(8L, overlapped.endOffset)
    }

    @Test
    fun `a gap is a visible missing range, never concatenated`() {
        val tail = TaskOutputTail().append(0, "first\n").append(16, "after gap\n")

        assertEquals("first\n[… 10 bytes missing …]\nafter gap\n", tail.render())
        assertEquals(2, tail.segments.size)
        assertEquals(26L, tail.endOffset)
        // A gap after text without a trailing newline still gets its own line.
        assertEquals("a\n[… 4 bytes missing …]\nb", TaskOutputTail().append(0, "a").append(5, "b").render())
    }

    @Test
    fun `a tail that starts past the first byte says so`() {
        val tail = TaskOutputTail().append(1_000, "the last line\n")

        assertEquals("[… 1000 earlier bytes not shown …]\nthe last line\n", tail.render())
        assertEquals(1_000L, tail.startOffset)
    }

    @Test
    fun `the tail is bounded, dropping the oldest bytes on a character boundary`() {
        var tail = TaskOutputTail(maxBytes = 10).append(0, "0123456789")
        tail = tail.append(10, "ab")

        assertEquals(2L, tail.startOffset)
        assertEquals(12L, tail.endOffset)
        assertEquals("[… 2 earlier bytes not shown …]\n23456789ab", tail.render())

        // Cutting 1 byte would split "é" (2 bytes): the cut moves past the whole char.
        val multibyte = TaskOutputTail(maxBytes = 4).append(0, "éabc")
        assertEquals(2L, multibyte.startOffset)
        assertEquals("abc", multibyte.segments.single().text)
    }

    @Test
    fun `trimming drops whole old segments before cutting into one`() {
        val tail = TaskOutputTail(maxBytes = 6)
            .append(0, "aaaa")
            .append(10, "bbbb")
            .append(14, "cc")

        assertEquals(1, tail.segments.size)
        assertEquals(10L, tail.startOffset)
        assertEquals("bbbbcc", tail.segments.single().text)
    }

    @Test
    fun `an empty tail has no offsets and renders nothing`() {
        val tail = TaskOutputTail()
        assertTrue(tail.isEmpty)
        assertNull(tail.endOffset)
        assertEquals("", tail.render())
    }
}
