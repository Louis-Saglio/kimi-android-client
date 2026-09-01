package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** The FetchURL output's preamble lifting (ticket 122): the tool's head note
 * (0.39.1 wording) becomes the row caption, the content stays verbatim — anything
 * unclean degrades to untouched. */
class FetchUrlOutputsTest {

    private val citation = " If you use it in your answer, cite this page as a markdown link, e.g. [title](url).\n\n"

    @Test
    fun `an extracted-content output lifts its note sentence`() {
        val output = "The returned content is the main text extracted from the page.$citation" +
            "# Guide\n\nbody text"

        val lifted = FetchUrlOutputs.of(output)

        assertEquals("The returned content is the main text extracted from the page.", lifted.note)
        assertEquals("# Guide\n\nbody text", lifted.content)
    }

    @Test
    fun `a verbatim-body output lifts its note sentence`() {
        val output = "The returned content is the full response body, returned verbatim.$citation" +
            "<html>raw</html>"

        val lifted = FetchUrlOutputs.of(output)

        assertEquals("The returned content is the full response body, returned verbatim.", lifted.note)
        assertEquals("<html>raw</html>", lifted.content)
    }

    @Test
    fun `no preamble leaves the output untouched with no caption`() {
        val output = "Just some content, no note."

        assertEquals(FetchUrlOutputs.Lifted(output, null), FetchUrlOutputs.of(output))
    }

    @Test
    fun `an error output carries no caption`() {
        val output = "Failed to fetch URL. Status: 404. HTTP 404 Not Found"

        assertEquals(FetchUrlOutputs.Lifted(output, null), FetchUrlOutputs.of(output))
    }

    @Test
    fun `a drifted preamble leaves the output untouched`() {
        // Wording drift, the note mid-text, the blank separator missing: verbatim.
        for (output in listOf(
            "The returned content is the main text extracted from the page. Cite it.\n\nbody",
            "body\nThe returned content is the main text extracted from the page.$citation",
            "The returned content is the main text extracted from the page.${citation.trimEnd()}body",
        )) {
            assertEquals(FetchUrlOutputs.Lifted(output, null), FetchUrlOutputs.of(output))
        }
    }
}
