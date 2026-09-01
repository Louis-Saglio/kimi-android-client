package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The payload-derived row display values (ticket 120): the Read range chip and the
 * Write size chip's raw numbers. */
class ToolPayloadTest {

    @Test
    fun `a full-file read shows no range chip`() {
        assertNull(ToolPayload.Read("/tmp/a.txt", offset = null, limit = null).rangeLabel)
    }

    @Test
    fun `a ranged read shows its lines chip`() {
        assertEquals("lines 40–139", ToolPayload.Read("/tmp/a.txt", offset = 40, limit = 100).rangeLabel)
    }

    @Test
    fun `a limit-only read ranges from the first line`() {
        assertEquals("lines 1–50", ToolPayload.Read("/tmp/a.txt", offset = null, limit = 50).rangeLabel)
    }

    @Test
    fun `an offset-only read is open-ended`() {
        assertEquals("lines 40–end", ToolPayload.Read("/tmp/a.txt", offset = 40, limit = null).rangeLabel)
    }

    @Test
    fun `the write size chip counts lines and utf-8 bytes`() {
        val write = ToolPayload.Write("/tmp/a.txt", "one\ntwo\n", append = false)

        // The trailing newline's empty tail counts (the ticket's `content.lines().size`).
        assertEquals(3, write.lineCount)
        assertEquals(8, write.byteSize)
        // Multi-byte characters size as UTF-8, not chars: "é" is 2 bytes.
        assertEquals(2, ToolPayload.Write("/tmp/a.txt", "é", append = false).byteSize)
        assertEquals(0, ToolPayload.Write("/tmp/a.txt", "", append = false).byteSize)
    }
}
