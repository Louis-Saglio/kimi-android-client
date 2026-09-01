package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Bash output's `Command failed with exit code: N` trailer lifting (ticket
 * 121): harness prose, not wire protocol — anything unclean degrades to verbatim. */
class BashOutputsTest {

    @Test
    fun `a failing output strips exactly the trailer line and extracts the code`() {
        val lifted = BashOutputs.of("first\nsecond\nCommand failed with exit code: 1")

        assertEquals("first\nsecond", lifted.output)
        assertEquals(1, lifted.exitCode)
    }

    @Test
    fun `a trailing newline after the trailer is tolerated`() {
        val lifted = BashOutputs.of("boom\nCommand failed with exit code: 42\n")

        assertEquals("boom", lifted.output)
        assertEquals(42, lifted.exitCode)
    }

    @Test
    fun `an output holding only the trailer strips to empty`() {
        val lifted = BashOutputs.of("Command failed with exit code: 2")

        assertEquals("", lifted.output)
        assertEquals(2, lifted.exitCode)
    }

    @Test
    fun `no trailer leaves the output untouched with no chip`() {
        assertEquals(BashOutputs.Lifted("all good\n", null), BashOutputs.of("all good\n"))
    }

    @Test
    fun `a mid-text trailer mention is not a trailer`() {
        val output = "Command failed with exit code: 1\nretrying\nok\n"

        assertEquals(BashOutputs.Lifted(output, null), BashOutputs.of(output))
    }

    @Test
    fun `a malformed trailer leaves the output untouched`() {
        // A non-numeric code, a wording change, an overflowing number: verbatim, no
        // chip — a server wording drift must degrade to today's behavior, never crash.
        for (output in listOf(
            "boom\nCommand failed with exit code: N",
            "boom\nCommand failed with exit code 1",
            "boom\nCommand failed with exit code: 99999999999999999999",
        )) {
            assertEquals(BashOutputs.Lifted(output, null), BashOutputs.of(output))
        }
    }
}
