package fr.louissaglio.kimi.android.data.chat

import java.time.Instant
import kotlinx.serialization.json.JsonObject

/**
 * Domain model for a history message, as exposed by [ChatRepository] to ViewModels.
 * Mirrors `GET /api/v1/sessions/{id}/messages` (docs/server-api.md), reduced to the
 * part types the chat screen renders. [createdAt] is the server's `created_at`;
 * null on splice echoes that carry no timestamp.
 */
data class ChatMessage(
    val id: String,
    val role: Role,
    val parts: List<Part>,
    val createdAt: Instant? = null,
    /** The message's `origin.kind` (docs/server-api.md); null for regular
     * user/assistant messages. [ORIGIN_KIND_COMPACTION_SUMMARY] marks the persisted
     * compaction summary (ticket 056): it renders as a divider, never a user bubble. */
    val originKind: String? = null,
) {
    enum class Role { User, Assistant, Tool, Other }

    sealed interface Part {
        data class Text(val text: String) : Part
        data class Thinking(val text: String) : Part

        /** [preview] is the one-line summary of the call's input (ToolPreview), shown on the row;
         * [input] is the full input as pretty-printed JSON, the expanded row's fallback when the
         * call carried no object [args] (ticket 118). [args] is the raw `input` object itself
         * (ticket 118): the expanded row renders it as a key/value table (ToolDetailFields). */
        data class ToolUse(
            val toolCallId: String,
            val toolName: String,
            val preview: String = "",
            val input: String = "",
            val args: JsonObject? = null,
        ) : Part

        /** [output] is the full result text, shown by the expanded row (ticket 023). */
        data class ToolResult(val toolCallId: String, val isError: Boolean, val output: String = "") : Part

        /** A non-image uploaded file, from the message origin's structured `attachments`
         * array (ticket 160, 0.39.1+ — docs/server-api.md, Prompts). [fileId] comes from
         * the materialized path's `<fileId>-` prefix; null means the bytes cannot be
         * fetched back and the chip is inert. */
        data class Attachment(
            val name: String,
            val mimeType: String,
            val sizeBytes: Long,
            val fileId: String?,
        ) : Part

        /** An uploaded image inlined into the message (ticket 034). [base64Data] is set
         * for base64 and data-url sources (ACK echo and history shapes,
         * docs/server-api.md), [fileId] for a file-referenced source. [isSessionMedia]
         * marks a 0.37.2 `session_media` source (ticket 104): its [fileId] is
         * session-scoped media, fetched via `GET /api/v1/sessions/{id}/media/{file_id}`
         * rather than `/api/v1/files/{id}`. [name] is the original file's name on
         * 0.42.0+ media parts (ticket 160), shown as the bubble's filename label; null
         * on older shapes. The wire media_type is dropped: nothing reads it, bytes are
         * decoded as-is. */
        data class Image(
            val base64Data: String?,
            val fileId: String?,
            val isSessionMedia: Boolean = false,
            val name: String? = null,
        ) : Part
    }
}

/** One page of the message history; [hasMore] mirrors the envelope's `has_more` flag. */
data class MessagePage(
    val items: List<ChatMessage>,
    val hasMore: Boolean,
)

/** `origin.kind` of the user message a compaction appends to history (bare summary,
 * docs/server-api.md, Compact) — and of the id-less summary in the `context.spliced`
 * rewrite (prefixed text there; both are kept out of the user-bubble path). */
const val ORIGIN_KIND_COMPACTION_SUMMARY = "compaction_summary"
