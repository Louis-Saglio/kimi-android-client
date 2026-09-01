package fr.louissaglio.kimi.android.data.chat

/**
 * The FetchURL call's output opens with a tool-generated preamble (agent-core
 * fetch-url tool, verified against the bundled 0.39.1 source — see
 * docs/server-api.md, Tools → lookup tool output text):
 *
 * ```
 * The returned content is the main text extracted from the page. If you use it in
 * your answer, cite this page as a markdown link, e.g. [title](url).
 *
 * <the fetched content>
 * ```
 *
 * — or, when the page could not be distilled, `The returned content is the full
 * response body, returned verbatim.` as the first sentence. [of] lifts that first
 * sentence into [Lifted.note] (the expanded row's caption) and drops the
 * model-directed citation instruction; the content stays verbatim. Anything that
 * does not parse cleanly — drifted wording, the note mid-text, the blank separator
 * missing — leaves the output untouched with no note: the [ReadOutputs] pattern.
 */
object FetchUrlOutputs {

    /** [content] is the verbatim fetched text; [note] the lifted preamble sentence
     * (null when the output carried no clean head preamble — error outputs included). */
    data class Lifted(val content: String, val note: String?)

    // Head-anchored, the exact 0.39.1 wording; the note sentence itself is captured
    // loosely (`[^\n]+?`) so a reworded-but-shaped-alike preamble still lifts.
    private val NOTE_HEADER = Regex(
        """\A(The returned content is [^\n]+?\.) If you use it in your answer, cite this page as a markdown link, e\.g\. \[title]\(url\)\.\n\n"""
    )

    fun of(output: String): Lifted {
        val match = NOTE_HEADER.find(output) ?: return Lifted(output, null)
        return Lifted(output.removeRange(match.range), match.groupValues[1])
    }
}
