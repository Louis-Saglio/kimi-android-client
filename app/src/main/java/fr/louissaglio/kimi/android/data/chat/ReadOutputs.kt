package fr.louissaglio.kimi.android.data.chat

/**
 * The Read call's output is the file extract verbatim — the wire format already
 * carries `line-number⇥content` prefixes — optionally closed by a trailing
 * `<system>…</system>` status block ("truncated", byte size, …). [of] separates the
 * two for the file content screen (ticket 120): a cleanly end-anchored block becomes
 * the caption chip, the extract the body. Anything that does not parse cleanly —
 * block mid-text, unclosed tag, empty block — leaves the output untouched with no
 * caption; shape drift must never break the screen.
 */
object ReadOutputs {

    /** [content] is the verbatim extract; [caption] the lifted status text (null when
     * the output carried no clean trailing block). */
    data class Lifted(val content: String, val caption: String?)

    private val STATUS_BLOCK = Regex("""\s*<system>([\s\S]*?)</system>\s*$""")

    fun of(output: String): Lifted {
        val match = STATUS_BLOCK.find(output) ?: return Lifted(output, null)
        val caption = match.groupValues[1].trim()
        if (caption.isEmpty()) return Lifted(output, null)
        return Lifted(output.removeRange(match.range).trimEnd(), caption)
    }
}
