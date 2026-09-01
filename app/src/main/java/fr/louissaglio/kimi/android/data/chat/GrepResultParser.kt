package fr.louissaglio.kimi.android.data.chat

/**
 * The Grep call's successful output parsed per `output_mode` (ticket 123). The
 * wire shapes are the tool's own display prose — the agent-core-v2 grep tool
 * rewrites rg's NUL-separated records into plain text lines and appends its
 * notices (verified against the bundled 0.39.1 source, local rg cross-check;
 * see docs/server-api.md, Tools → lookup tool output text):
 *
 * - `content` (`-n` defaults on): `path:NN:text` match lines, `path-NN-text`
 *   context lines, `--` separators between non-contiguous ranges.
 * - `files_with_matches`: one bare path per line.
 * - `count_matches`: a `Found N total occurrences across M files.` head
 *   summary (omitted on an empty result), then `path:N` lines.
 *
 * The tool's notices (sensitive-filter report, head_limit pagination notice,
 * the two-line rg-output truncation marker, the timeout notice) ride along in
 * the same text; [of] strips them head/tail-anchored into [Result.notices]
 * (the expansion keeps them as captions) BEFORE parsing the body, so a
 * truncated result still structures. Anything else that does not fit the
 * mode's grammar — a drifted notice wording, a `-n: false` content output
 * (no line numbers at all), a blank line — degrades the WHOLE output to null
 * and the row renders it verbatim: never a half-parsed mix (the
 * [GlobOutputs]/[BashOutputs] tolerant-fallback pattern, tightened per ticket).
 */
object GrepResultParser {

    const val MODE_CONTENT = "content"
    const val MODE_FILES_WITH_MATCHES = "files_with_matches"
    const val MODE_COUNT_MATCHES = "count_matches"

    /** The parsed output; [notices] are the stripped tool notices in wire
     * order (empty when the output carried none). */
    sealed interface Result {
        val notices: List<String>

        /** `content`: [items] in wire order — per-file groups split by the
         * `--` separators. */
        data class Content(val items: List<ContentItem>, override val notices: List<String>) : Result

        /** `files_with_matches`: the bare paths in wire order. */
        data class FileList(val paths: List<String>, override val notices: List<String>) : Result

        /** `count_matches`: [summary] is the lifted `Found N …` head line
         * (null when the output carried none). */
        data class Counts(
            val entries: List<CountEntry>,
            val summary: String?,
            override val notices: List<String>,
        ) : Result
    }

    sealed interface ContentItem {

        /** A maximal run of consecutive same-path lines. A `--` between two
         * ranges of one file splits them into two groups — the header repeats,
         * as rg repeats the path on every line. */
        data class Group(val path: String, val lines: List<ContentLine>) : ContentItem

        /** A `--` line; renders as plain spacing, never as text. */
        data object Separator : ContentItem
    }

    /** A numbered content line; [context] lines (the wire's `path-NN-text`
     * form) render dimmed without their [number]. */
    data class ContentLine(val number: Int, val text: String, val context: Boolean)

    data class CountEntry(val path: String, val count: Int)

    /** Parses [output] as [mode]'s grammar; null = render verbatim. */
    fun of(output: String, mode: String): Result? {
        if (output.isBlank()) return null
        // The status-only outputs are not result bodies at all.
        if (NO_MATCHES.containsMatchIn(output)) return null
        val notices = mutableListOf<String>()
        var body = stripTailNotices(output, notices)
        var summary: String? = null
        if (mode == MODE_COUNT_MATCHES) {
            COUNT_SUMMARY.find(body)?.let { match ->
                summary = match.groupValues[1]
                body = body.removeRange(match.range)
            }
            // In count mode the pagination notice sits in the head, right
            // after the summary.
            HEAD_PAGINATION_NOTICE.find(body)?.let { match ->
                notices.add(0, match.value.trim())
                body = body.removeRange(match.range)
            }
        }
        // A notices-only output (a partial run with zero results) is verbatim.
        if (body.isBlank()) return null
        return when (mode) {
            MODE_CONTENT -> parseContent(body, notices)
            MODE_COUNT_MATCHES -> parseCounts(body, summary, notices)
            MODE_FILES_WITH_MATCHES -> parseFiles(body, notices)
            // An unknown (drifted) mode has no grammar: verbatim.
            else -> null
        }
    }

    /** The server's own lazy split (CONTENT_LINE_RE): the first `:`/`-` run
     * bracketing digits is the path boundary. Ambiguous by construction — a
     * pathological file name containing `-N-` mis-splits, while matched text
     * containing `:N:` stays safe (the real boundary comes first). */
    private val CONTENT_LINE = Regex("""(.*?)([:-])(\d+)\2(.*)""")
    private const val SEPARATOR = "--"
    private val COUNT_LINE = Regex("""(.*):(\d+)""")

    // The 0.39.1 notice wordings, anchored so the same prose inside a result
    // body is never touched: the count summary only at the head, the rest only
    // at the tail (the GlobOutputs `Found N matches` discipline — a path
    // literally named like a notice survives).
    private val NO_MATCHES = Regex("""\ANo (non-sensitive )?matches found\s*$""")
    private val COUNT_SUMMARY =
        Regex("""\A(Found \d+ total (?:non-sensitive )?occurrences? across \d+ files?\.)(?:\n|$)""")
    private val PAGINATION_NOTICE =
        """Results truncated to \d+ lines \(total: \d+(?: of a partial result set)?\)\. Use offset=\d+ to see more\."""
    private val HEAD_PAGINATION_NOTICE = Regex("""\A(?:$PAGINATION_NOTICE)(?:\n|$)""")
    // The `(?:\A|\n)` prefix pins a notice to a line boundary: a matched content
    // line whose TEXT happens to end with a notice wording is never stripped.
    private val TAIL_NOTICE = Regex(
        """(?:\A|\n)(?:Filtered \d+ sensitive file\(s\): [^\n]*|$PAGINATION_NOTICE""" +
            """|\[Output truncated at \d+ bytes of rg output \n the result set is incomplete\. Narrow the pattern, path, or glob filters and re-run to recover complete results\.\]""" +
            """|Grep timed out after \d+s; partial results returned(?:\. Narrow the path, glob, or pattern and retry for complete results\.)?)$"""
    )

    private fun parseContent(body: String, notices: List<String>): Result? {
        val items = mutableListOf<ContentItem>()
        var groupPath: String? = null
        var groupLines = mutableListOf<ContentLine>()
        fun flushGroup() {
            groupPath?.let { path -> items += ContentItem.Group(path, groupLines.toList()) }
            groupPath = null
            groupLines = mutableListOf()
        }
        for (line in body.lines()) {
            if (line == SEPARATOR) {
                flushGroup()
                items += ContentItem.Separator
                continue
            }
            val match = CONTENT_LINE.matchEntire(line) ?: return null
            val path = match.groupValues[1].takeIf { it.isNotEmpty() } ?: return null
            val number = match.groupValues[3].toIntOrNull() ?: return null
            if (path != groupPath) {
                flushGroup()
                groupPath = path
            }
            groupLines += ContentLine(number, match.groupValues[4], context = match.groupValues[2] == "-")
        }
        flushGroup()
        return Result.Content(items, notices)
    }

    private fun parseCounts(body: String, summary: String?, notices: List<String>): Result? {
        val entries = body.lines().map { line ->
            val match = COUNT_LINE.matchEntire(line) ?: return null
            val path = match.groupValues[1].takeIf { it.isNotEmpty() } ?: return null
            val count = match.groupValues[2].toIntOrNull() ?: return null
            CountEntry(path, count)
        }
        return Result.Counts(entries, summary, notices)
    }

    private fun parseFiles(body: String, notices: List<String>): Result? {
        val paths = body.lines()
        // rg's -l lists one non-blank path per line — nothing else fits.
        if (paths.any { it.isBlank() }) return null
        return Result.FileList(paths, notices)
    }

    /** Strips the trailing tool notices one at a time (they stack in a fixed
     * order; the loop is order-agnostic), collecting them in wire order. */
    private fun stripTailNotices(output: String, notices: MutableList<String>): String {
        var rest = output
        while (true) {
            val match = TAIL_NOTICE.find(rest) ?: break
            notices.add(0, match.value.trim())
            rest = rest.removeRange(match.range)
        }
        return rest
    }
}
