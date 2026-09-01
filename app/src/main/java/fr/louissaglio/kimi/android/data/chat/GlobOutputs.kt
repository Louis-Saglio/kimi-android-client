package fr.louissaglio.kimi.android.data.chat

/**
 * The Glob call's successful output is one path per line (relative to the search
 * root when it sits under the workspace, absolute otherwise). Two server-generated
 * status lines ride along (agent-core glob tool, verified against the bundled 0.39.1
 * source — see docs/server-api.md, Tools → lookup tool output text):
 *
 * - when the match cap (100) bites, the output OPENS with a three-line marker —
 *   `[Truncated at 100 matches ` / ` use a more specific pattern]` (one marker
 *   across two lines, the tool's own line break) then
 *   `Only the first 100 matches are returned.` — [of] strips it and lifts the cap
 *   into [Lifted.truncatedCount] for the expanded row's truncation chip;
 * - an exactly-100-match output CLOSES with `Found 100 matches` — stripped too: the
 *   row's own "N matches" header already carries the count.
 *
 * Every other notice the tool can emit (timeout/stdout-truncation/traversal-warning
 * heads, the `Filtered N sensitive file(s).` tail) stays in the list — those lines
 * carry information nothing else shows, and their rarity makes the off-by-one header
 * count acceptable. Anything that is not a path list at all (the known status-only
 * outputs, a blank output) parses to null → the row renders the output verbatim.
 * Shape drift must degrade to verbatim, never crash — the [BashOutputs] pattern.
 */
object GlobOutputs {

    /** [lines] are the match lines (blank lines dropped); [truncatedCount] is the
     * stripped marker's cap (null when the output carried no clean head marker). */
    data class Lifted(val lines: List<String>, val truncatedCount: Int?)

    // Head-anchored, the exact 0.39.1 wording — including the marker's own line
    // break (a trailing space before it, a leading one after).
    private val TRUNCATION_HEADER = Regex(
        """\A\[Truncated at (\d+) matches \n use a more specific pattern\]\nOnly the first \d+ matches are returned\.(?:\n|$)"""
    )
    // End-anchored on a line boundary: a path named "Found N matches" is not the
    // footer.
    private val FOUND_FOOTER = Regex("""\nFound \d+ matches\s*$""")
    private val NO_MATCHES = Regex("""\ANo matches found\s*$""")
    private val NO_NON_SENSITIVE = Regex("""\ANo non-sensitive matches found \(\d+ sensitive file\(s\) filtered\.\)\s*$""")

    fun of(output: String): Lifted? {
        if (output.isBlank()) return null
        if (NO_MATCHES.containsMatchIn(output) || NO_NON_SENSITIVE.containsMatchIn(output)) return null
        val marker = TRUNCATION_HEADER.find(output)
        val stripped = (marker?.let { output.removeRange(it.range) } ?: output)
            .replace(FOUND_FOOTER, "")
        val lines = stripped.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        return Lifted(lines, marker?.groupValues?.get(1)?.toIntOrNull())
    }
}
