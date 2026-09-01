package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Read output's trailing `<system>…</system>` status-block lifting (ticket 120). */
class ReadOutputsTest {

    @Test
    fun `a clean trailing system block lifts into the caption`() {
        val output = "     1→one\n     2→two\n<system>truncated: showing lines 1-2 of 99</system>"

        val lifted = ReadOutputs.of(output)

        assertEquals("     1→one\n     2→two", lifted.content)
        assertEquals("truncated: showing lines 1-2 of 99", lifted.caption)
    }

    @Test
    fun `trailing whitespace after the block still lifts`() {
        val output = "     1→one\n<system>byte size: 1.2 kB</system>\n"

        val lifted = ReadOutputs.of(output)

        assertEquals("     1→one", lifted.content)
        assertEquals("byte size: 1.2 kB", lifted.caption)
    }

    @Test
    fun `output without a system block stays untouched`() {
        val output = "     1→one\n     2→two"

        assertEquals(ReadOutputs.Lifted(output, null), ReadOutputs.of(output))
    }

    @Test
    fun `a system block that is not trailing leaves the output untouched`() {
        val output = "     1→one\n<system>not a status</system>\n     2→two"

        assertEquals(ReadOutputs.Lifted(output, null), ReadOutputs.of(output))
    }

    @Test
    fun `an unclosed block leaves the output untouched`() {
        val output = "     1→one\n<system>truncated"

        assertEquals(ReadOutputs.Lifted(output, null), ReadOutputs.of(output))
    }

    @Test
    fun `an empty block lifts nothing`() {
        val output = "     1→one\n<system></system>"

        assertEquals(ReadOutputs.Lifted(output, null), ReadOutputs.of(output))
    }

    @Test
    fun `a content line merely containing a tag is not lifted`() {
        val output = "     1→val tag = \"<system>\"\n     2→done"

        assertEquals(ReadOutputs.Lifted(output, null), ReadOutputs.of(output))
    }
}
