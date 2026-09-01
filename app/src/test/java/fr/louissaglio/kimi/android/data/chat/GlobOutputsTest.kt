package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Glob output parsing (ticket 122): the count is the non-blank lines, the
 * server's status lines (the 0.39.1 truncation marker head, the `Found N matches`
 * foot) are stripped, the known status-only outputs are not lists at all. */
class GlobOutputsTest {

    @Test
    fun `a plain path list counts its non-blank lines`() {
        val lifted = GlobOutputs.of("app/a.kt\napp/b.kt\n\napp/c.kt\n")

        assertEquals(GlobOutputs.Lifted(listOf("app/a.kt", "app/b.kt", "app/c.kt"), null), lifted)
    }

    @Test
    fun `a truncated output strips the head marker and lifts the cap`() {
        // The exact 0.39.1 marker — its own line break, trailing space included.
        val output = "[Truncated at 100 matches \n use a more specific pattern]\n" +
            "Only the first 100 matches are returned.\napp/a.kt\napp/b.kt"

        val lifted = GlobOutputs.of(output)

        assertEquals(GlobOutputs.Lifted(listOf("app/a.kt", "app/b.kt"), 100), lifted)
    }

    @Test
    fun `an exactly-capped output drops its Found footer from the count`() {
        val lifted = GlobOutputs.of("app/a.kt\napp/b.kt\nFound 2 matches")

        assertEquals(GlobOutputs.Lifted(listOf("app/a.kt", "app/b.kt"), null), lifted)
    }

    @Test
    fun `a marker not at the head is not stripped`() {
        // Doubtful shape (the marker follows other lines — stacked notices): it rides
        // along as list lines, no truncation chip.
        val output = "Glob timed out after 60s; partial results returned.\n" +
            "[Truncated at 100 matches \n use a more specific pattern]\n" +
            "Only the first 100 matches are returned.\napp/a.kt"

        assertEquals(
            GlobOutputs.Lifted(
                listOf(
                    "Glob timed out after 60s; partial results returned.",
                    "[Truncated at 100 matches ",
                    " use a more specific pattern]",
                    "Only the first 100 matches are returned.",
                    "app/a.kt",
                ),
                null,
            ),
            GlobOutputs.of(output),
        )
    }

    @Test
    fun `a drifted marker wording is not stripped`() {
        // A server wording change must degrade to verbatim-ish lines, never crash.
        val output = "[Truncated at 100 matches — use a more specific pattern]\napp/a.kt"

        assertEquals(
            GlobOutputs.Lifted(listOf("[Truncated at 100 matches — use a more specific pattern]", "app/a.kt"), null),
            GlobOutputs.of(output),
        )
    }

    @Test
    fun `the status-only outputs are not path lists`() {
        assertNull(GlobOutputs.of("No matches found"))
        assertNull(GlobOutputs.of("No non-sensitive matches found (2 sensitive file(s) filtered.)"))
    }

    @Test
    fun `a blank output has no parse`() {
        assertNull(GlobOutputs.of(""))
        assertNull(GlobOutputs.of("  \n"))
    }

    @Test
    fun `a marker-only output has no parse`() {
        val output = "[Truncated at 100 matches \n use a more specific pattern]\n" +
            "Only the first 100 matches are returned."

        assertNull(GlobOutputs.of(output))
    }
}
