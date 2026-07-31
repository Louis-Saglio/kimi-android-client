package fr.louissaglio.kimi.android.data.chat

/**
 * Parses the server's `Attached file "<name>" (<mime>, <n> bytes): <path> — …` text
 * notice back into an attachment chip model (ticket 034). The server rewrites every
 * non-image `file` part of a prompt into this notice before enqueueing
 * (docs/server-api.md, Prompts); the wording is an explicit client contract — the
 * regex mirrors the web client's ATTACHED_FILE_NOTICE_RE.
 */
object AttachmentNotice {

    private val NOTICE_RE =
        Regex("""^Attached file "(.+)" \(([^,]+), (\d+) bytes\): (.+) — open it with the Read tool$""")

    // File-store ids come in two shapes: v1 `f_`<26-char ULID> and v2 `f_`<randomUUID>.
    // Anchored at the start of the `<fileId>-<name>` basename — splitting on the first
    // '-' would truncate v2 UUID ids at their first inner hyphen.
    private val FILE_STORE_ID_AT_START_RE =
        Regex("""^f_(?:[0-9A-Za-z]{26}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})(?=-)""")

    /** The chip for a notice text, or null when [text] is not the notice — the caller
     * keeps non-matching text as a plain text part. */
    fun parse(text: String): ChatMessage.Part.Attachment? {
        val match = NOTICE_RE.matchEntire(text.trim()) ?: return null
        val basename = match.groupValues[4].split('/', '\\').last()
        val fileId = FILE_STORE_ID_AT_START_RE.find(basename)?.value
        return ChatMessage.Part.Attachment(
            name = match.groupValues[1],
            mimeType = match.groupValues[2],
            // A size past Long range can't parse: treat it as no-notice — the caller
            // keeps the text as a plain part (the parse contract above).
            sizeBytes = match.groupValues[3].toLongOrNull() ?: return null,
            fileId = fileId,
        )
    }
}
