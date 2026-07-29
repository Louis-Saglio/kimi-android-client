package fr.louissaglio.kimi.android.data.chat

import java.time.Instant

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
) {
    enum class Role { User, Assistant, Tool, Other }

    sealed interface Part {
        data class Text(val text: String) : Part
        data class Thinking(val text: String) : Part

        /** [preview] is the one-line summary of the call's input (ToolPreview), shown on the row;
         * [input] is the full input as pretty-printed JSON, shown by the expanded row (ticket 023). */
        data class ToolUse(
            val toolCallId: String,
            val toolName: String,
            val preview: String = "",
            val input: String = "",
        ) : Part

        /** [output] is the full result text, shown by the expanded row (ticket 023). */
        data class ToolResult(val toolCallId: String, val isError: Boolean, val output: String = "") : Part

        /** A non-image uploaded file, recovered from the server's `Attached file "…"`
         * text notice (AttachmentNotice, ticket 034). [fileId] comes from the
         * materialized basename prefix; null means the bytes cannot be fetched back. */
        data class Attachment(
            val name: String,
            val mimeType: String,
            val sizeBytes: Long,
            val fileId: String?,
        ) : Part

        /** An uploaded image inlined into the message (ticket 034). [base64Data] is set
         * for base64 and data-url sources (ACK echo and history shapes,
         * docs/server-api.md), [fileId] for a file-referenced source. The wire
         * media_type is dropped: nothing reads it — bytes are decoded as-is. */
        data class Image(
            val base64Data: String?,
            val fileId: String?,
        ) : Part
    }
}

/** One page of the message history; [hasMore] mirrors the envelope's `has_more` flag. */
data class MessagePage(
    val items: List<ChatMessage>,
    val hasMore: Boolean,
)
