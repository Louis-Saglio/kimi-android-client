package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Grep output parsing (ticket 123), one battery per output mode: well-formed
 * wire shapes structure, any line failing the mode's grammar degrades the WHOLE
 * output to null (the row renders it verbatim) — never a half-parsed mix. The
 * fixtures mirror the 0.39.1 wire shapes recorded in docs/server-api.md (Tools →
 * lookup tool output text). */
class GrepResultParserTest {

    // --- content mode ----------------------------------------------------------

    @Test
    fun `a well-formed content output parses into per-file groups`() {
        val output = "app/a.kt:12:beta match here\n" +
            "app/a.kt:30:another match\n" +
            "--\n" +
            "sub/b.kt:2:match in b"

        val result = GrepResultParser.of(output, GrepResultParser.MODE_CONTENT)

        assertEquals(
            GrepResultParser.Result.Content(
                listOf(
                    GrepResultParser.ContentItem.Group(
                        "app/a.kt",
                        listOf(
                            GrepResultParser.ContentLine(12, "beta match here", context = false),
                            GrepResultParser.ContentLine(30, "another match", context = false),
                        ),
                    ),
                    GrepResultParser.ContentItem.Separator,
                    GrepResultParser.ContentItem.Group(
                        "sub/b.kt",
                        listOf(GrepResultParser.ContentLine(2, "match in b", context = false)),
                    ),
                ),
                notices = emptyList(),
            ),
            result,
        )
    }

    @Test
    fun `-C context lines parse as context inside the group`() {
        // The -C 1 wire shape, cross-checked against the bundled rg: context lines
        // carry the `path-NN-text` form.
        val output = "app/a.kt:5:top match\n" +
            "app/a.kt-6-between\n" +
            "app/a.kt:7:lower match"

        val result = GrepResultParser.of(output, GrepResultParser.MODE_CONTENT)

        assertEquals(
            GrepResultParser.Result.Content(
                listOf(
                    GrepResultParser.ContentItem.Group(
                        "app/a.kt",
                        listOf(
                            GrepResultParser.ContentLine(5, "top match", context = false),
                            GrepResultParser.ContentLine(6, "between", context = true),
                            GrepResultParser.ContentLine(7, "lower match", context = false),
                        ),
                    ),
                ),
                notices = emptyList(),
            ),
            result,
        )
    }

    @Test
    fun `a separator splits two ranges of one file into two groups`() {
        val output = "app/a.kt:5:one\n--\napp/a.kt:40:two"

        val result = GrepResultParser.of(output, GrepResultParser.MODE_CONTENT)

        assertEquals(
            GrepResultParser.Result.Content(
                listOf(
                    GrepResultParser.ContentItem.Group(
                        "app/a.kt",
                        listOf(GrepResultParser.ContentLine(5, "one", context = false)),
                    ),
                    GrepResultParser.ContentItem.Separator,
                    GrepResultParser.ContentItem.Group(
                        "app/a.kt",
                        listOf(GrepResultParser.ContentLine(40, "two", context = false)),
                    ),
                ),
                notices = emptyList(),
            ),
            result,
        )
    }

    @Test
    fun `one bad line degrades the whole content output to verbatim`() {
        val output = "app/a.kt:12:beta match here\nnot a grep line at all\nsub/b.kt:2:match in b"

        assertNull(GrepResultParser.of(output, GrepResultParser.MODE_CONTENT))
    }

    @Test
    fun `a -n false content output has no line numbers and degrades to verbatim`() {
        // The payload carries no -n flag; the numberless `path:text` shape simply
        // fails the content grammar.
        assertNull(GrepResultParser.of("app/a.kt:beta match here", GrepResultParser.MODE_CONTENT))
    }

    @Test
    fun `content tail notices are stripped into the notices list`() {
        val output = "app/a.kt:12:beta match here\n" +
            "Filtered 1 sensitive file(s): .env\n" +
            "Results truncated to 250 lines (total: 400). Use offset=250 to see more."

        val result = GrepResultParser.of(output, GrepResultParser.MODE_CONTENT)

        assertEquals(
            GrepResultParser.Result.Content(
                listOf(
                    GrepResultParser.ContentItem.Group(
                        "app/a.kt",
                        listOf(GrepResultParser.ContentLine(12, "beta match here", context = false)),
                    ),
                ),
                notices = listOf(
                    "Filtered 1 sensitive file(s): .env",
                    "Results truncated to 250 lines (total: 400). Use offset=250 to see more.",
                ),
            ),
            result,
        )
    }

    @Test
    fun `the two-line rg output truncation marker strips as one notice`() {
        val output = "app/a.kt:12:beta match here\n" +
            "[Output truncated at 102400 bytes of rg output \n" +
            " the result set is incomplete. Narrow the pattern, path, or glob filters and re-run to recover complete results.]"

        val result = GrepResultParser.of(output, GrepResultParser.MODE_CONTENT)

        assertEquals(
            listOf(
                "[Output truncated at 102400 bytes of rg output \n" +
                    " the result set is incomplete. Narrow the pattern, path, or glob filters and re-run to recover complete results.]",
            ),
            (result as GrepResultParser.Result.Content).notices,
        )
    }

    @Test
    fun `a notice-shaped matched line is never stripped`() {
        // The notice wording inside a line's TEXT (not at a line start) is content.
        val output = "app/a.kt:12:Results truncated to 250 lines (total: 400). Use offset=250 to see more."

        val result = GrepResultParser.of(output, GrepResultParser.MODE_CONTENT)

        assertEquals(
            GrepResultParser.Result.Content(
                listOf(
                    GrepResultParser.ContentItem.Group(
                        "app/a.kt",
                        listOf(
                            GrepResultParser.ContentLine(
                                12,
                                "Results truncated to 250 lines (total: 400). Use offset=250 to see more.",
                                context = false,
                            ),
                        ),
                    ),
                ),
                notices = emptyList(),
            ),
            result,
        )
    }

    @Test
    fun `a notice wording mid-body fails the grammar instead of stripping`() {
        // Drifted or reordered notices are not silently dropped: the whole output
        // degrades to verbatim.
        val output = "Results truncated to 250 lines (total: 400). Use offset=250 to see more.\n" +
            "app/a.kt:12:beta match here"

        assertNull(GrepResultParser.of(output, GrepResultParser.MODE_CONTENT))
    }

    // --- files_with_matches mode ------------------------------------------------

    @Test
    fun `a well-formed files output parses as a path list`() {
        val result = GrepResultParser.of("app/a.kt\nsub/b.kt", GrepResultParser.MODE_FILES_WITH_MATCHES)

        assertEquals(
            GrepResultParser.Result.FileList(listOf("app/a.kt", "sub/b.kt"), notices = emptyList()),
            result,
        )
    }

    @Test
    fun `a blank line degrades the whole files output to verbatim`() {
        assertNull(GrepResultParser.of("app/a.kt\n\nsub/b.kt", GrepResultParser.MODE_FILES_WITH_MATCHES))
    }

    @Test
    fun `files tail notices are stripped into the notices list`() {
        val output = "app/a.kt\n" +
            "Grep timed out after 60s; partial results returned. Narrow the path, glob, or pattern and retry for complete results."

        val result = GrepResultParser.of(output, GrepResultParser.MODE_FILES_WITH_MATCHES)

        assertEquals(
            GrepResultParser.Result.FileList(
                listOf("app/a.kt"),
                notices = listOf(
                    "Grep timed out after 60s; partial results returned. Narrow the path, glob, or pattern and retry for complete results.",
                ),
            ),
            result,
        )
    }

    // --- count_matches mode -----------------------------------------------------

    @Test
    fun `a well-formed count output lifts the summary and parses the count lines`() {
        val output = "Found 5 total occurrences across 2 files.\n" +
            "app/a.kt:2\n" +
            "sub/b.kt:3"

        val result = GrepResultParser.of(output, GrepResultParser.MODE_COUNT_MATCHES)

        assertEquals(
            GrepResultParser.Result.Counts(
                listOf(
                    GrepResultParser.CountEntry("app/a.kt", 2),
                    GrepResultParser.CountEntry("sub/b.kt", 3),
                ),
                summary = "Found 5 total occurrences across 2 files.",
                notices = emptyList(),
            ),
            result,
        )
    }

    @Test
    fun `the count-mode pagination notice strips from the head`() {
        // In count mode the pagination notice sits in the head lines, right after
        // the summary.
        val output = "Found 500 total occurrences across 30 files.\n" +
            "Results truncated to 25 lines (total: 30). Use offset=25 to see more.\n" +
            "app/a.kt:12"

        val result = GrepResultParser.of(output, GrepResultParser.MODE_COUNT_MATCHES)

        assertEquals(
            GrepResultParser.Result.Counts(
                listOf(GrepResultParser.CountEntry("app/a.kt", 12)),
                summary = "Found 500 total occurrences across 30 files.",
                notices = listOf("Results truncated to 25 lines (total: 30). Use offset=25 to see more."),
            ),
            result,
        )
    }

    @Test
    fun `a count line splits path and count at the last colon`() {
        val result = GrepResultParser.of("app/weird:name.kt:7", GrepResultParser.MODE_COUNT_MATCHES)

        assertEquals(
            GrepResultParser.Result.Counts(
                listOf(GrepResultParser.CountEntry("app/weird:name.kt", 7)),
                summary = null,
                notices = emptyList(),
            ),
            result,
        )
    }

    @Test
    fun `one bad line degrades the whole count output to verbatim`() {
        val output = "Found 5 total occurrences across 2 files.\n" +
            "app/a.kt:2\n" +
            "sub/b.kt:many"

        assertNull(GrepResultParser.of(output, GrepResultParser.MODE_COUNT_MATCHES))
    }

    // --- shared rules ------------------------------------------------------------

    @Test
    fun `the status-only output is not a result body`() {
        assertNull(GrepResultParser.of("No non-sensitive matches found", GrepResultParser.MODE_CONTENT))
        assertNull(GrepResultParser.of("No non-sensitive matches found", GrepResultParser.MODE_FILES_WITH_MATCHES))
        assertNull(GrepResultParser.of("No non-sensitive matches found", GrepResultParser.MODE_COUNT_MATCHES))
    }

    @Test
    fun `an empty output has no parse`() {
        assertNull(GrepResultParser.of("", GrepResultParser.MODE_CONTENT))
        assertNull(GrepResultParser.of("  \n", GrepResultParser.MODE_FILES_WITH_MATCHES))
    }

    @Test
    fun `a notices-only output has no parse`() {
        // A partial run with zero results: the body is empty, the notices render
        // verbatim with it.
        val output = "Grep timed out after 60s; partial results returned. Narrow the path, glob, or pattern and retry for complete results."

        assertNull(GrepResultParser.of(output, GrepResultParser.MODE_CONTENT))
    }

    @Test
    fun `an unknown output mode has no grammar`() {
        assertNull(GrepResultParser.of("app/a.kt\n", "bogus_mode"))
    }
}
