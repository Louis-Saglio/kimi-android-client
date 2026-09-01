package fr.louissaglio.kimi.android.data.chat

/**
 * The harness closes a failed foreground Bash call's output with a prose trailer —
 * `Command failed with exit code: N` (harness wording, NOT wire protocol — ticket
 * 121). [of] lifts it, the [ReadOutputs] pattern: the trailer line stripped from the
 * rendered text, the code extracted for the expanded row's `exit N` chip. Anything
 * that does not parse cleanly — trailer mid-text, a non-numeric or overflowing code —
 * leaves the output untouched with no chip: a server wording change must degrade to
 * verbatim, never crash.
 */
object BashOutputs {

    /** [output] is the verbatim text minus the stripped trailer; [exitCode] the
     * trailer's code (null when the output carried no clean trailer). */
    data class Lifted(val output: String, val exitCode: Int?)

    private val EXIT_CODE_TRAILER = Regex("""\s*Command failed with exit code: (\d+)\s*$""")

    fun of(output: String): Lifted {
        val match = EXIT_CODE_TRAILER.find(output) ?: return Lifted(output, null)
        val exitCode = match.groupValues[1].toIntOrNull() ?: return Lifted(output, null)
        return Lifted(output.removeRange(match.range).trimEnd(), exitCode)
    }
}
