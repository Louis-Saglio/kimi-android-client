package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Bash input's `description`/`command` reads: the job screen seeds its title and
 * Command section from them at navigation time (the tapped row already carries the
 * call input), so they parse at the same boundary as the timeout knobs — null for
 * non-Bash calls, blank/unparseable input, and a missing or blank description.
 */
class BashInputTest {

    @Test
    fun `the description and command parse from the call input`() {
        val input = """{"command":"sleep 90","description":"nap"}"""

        assertEquals("nap", bashDescriptionOf("Bash", input))
        assertEquals("sleep 90", bashCommandOf("Bash", input))
    }

    @Test
    fun `a missing or blank description parses to null`() {
        assertNull(bashDescriptionOf("Bash", """{"command":"sleep 90"}"""))
        assertNull(bashDescriptionOf("Bash", """{"command":"sleep 90","description":"  "}"""))
    }

    @Test
    fun `a missing command parses to null`() {
        assertNull(bashCommandOf("Bash", """{"description":"nap"}"""))
    }

    @Test
    fun `non-Bash calls and unparseable input carry nothing`() {
        assertNull(bashDescriptionOf("Agent", """{"description":"nap","command":"x"}"""))
        assertNull(bashCommandOf("Agent", """{"description":"nap","command":"x"}"""))
        assertNull(bashDescriptionOf("Bash", ""))
        assertNull(bashCommandOf("Bash", "not json"))
        assertNull(bashDescriptionOf("Bash", """["description","nap"]"""))
    }
}
