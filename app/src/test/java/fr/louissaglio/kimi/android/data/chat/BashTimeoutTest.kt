package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bash input's timeout knobs and the effective-budget rule (ticket 097):
 * `timeout`/`disable_timeout` exist only in the call input (REST never exposes
 * them), and the screen's budget is `seconds ?: if (disabled) null else 600` —
 * null meaning "no budget UI".
 */
class BashTimeoutTest {

    @Test
    fun `an explicit timeout parses to seconds`() {
        val timeout = bashTimeoutOf("Bash", """{"command":"sleep 90","timeout":30}""")

        assertEquals(BashTimeout(seconds = 30, disabled = false), timeout)
        assertEquals(30, timeout?.effectiveSeconds)
        assertFalse(timeout?.isDefault ?: true)
    }

    @Test
    fun `an unset timeout falls back to the 600 s background default, labeled as default`() {
        val timeout = bashTimeoutOf("Bash", """{"command":"sleep 90"}""")

        assertEquals(BashTimeout(seconds = null, disabled = false), timeout)
        assertEquals(DEFAULT_BACKGROUND_BASH_TIMEOUT_SECONDS, timeout?.effectiveSeconds)
        assertTrue(timeout?.isDefault ?: false)
    }

    @Test
    fun `disable_timeout means no kill, hence no budget UI`() {
        val timeout = bashTimeoutOf("Bash", """{"command":"sleep 90","disable_timeout":true}""")

        assertEquals(BashTimeout(seconds = null, disabled = true), timeout)
        assertNull(timeout?.effectiveSeconds)
        assertFalse(timeout?.isDefault ?: true)
    }

    @Test
    fun `an explicit timeout wins over disable_timeout`() {
        // The ticket's rule orders seconds first — a call carrying both keeps its budget.
        val timeout = bashTimeoutOf("Bash", """{"command":"sleep 90","timeout":30,"disable_timeout":true}""")

        assertEquals(30, timeout?.effectiveSeconds)
    }

    @Test
    fun `non-Bash calls and unparseable input carry no timeout`() {
        assertNull(bashTimeoutOf("Agent", """{"prompt":"x","timeout":30}"""))
        assertNull(bashTimeoutOf("Bash", ""))
        assertNull(bashTimeoutOf("Bash", "not json"))
        assertNull(bashTimeoutOf("Bash", """["timeout",30]"""))
    }
}
