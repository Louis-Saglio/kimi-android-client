package fr.louissaglio.kimi.android.data.chat

/**
 * Domain model for a history message, as exposed by [ChatRepository] to ViewModels.
 * Mirrors `GET /api/v1/sessions/{id}/messages` (docs/server-api.md), reduced to the
 * part types the chat screen renders.
 */
data class ChatMessage(
    val id: String,
    val role: Role,
    val parts: List<Part>,
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
    }
}
