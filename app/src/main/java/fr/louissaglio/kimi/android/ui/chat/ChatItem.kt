package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ChatMessage

enum class ToolStatus { Running, Done, Error }

/** One row of the chat timeline. [id] is the LazyColumn key: message id for bubbles,
 * tool call id for activity rows, synthetic `live_N` for in-flight streaming bubbles. */
sealed interface ChatItem {
    val id: String

    /** [attachments] are the non-image files recovered from the server's notice text,
     * [images] the inlined image parts (ticket 034); both empty for plain messages. */
    data class UserMessage(
        override val id: String,
        val text: String,
        val attachments: List<ChatMessage.Part.Attachment> = emptyList(),
        val images: List<ChatMessage.Part.Image> = emptyList(),
    ) : ChatItem

    data class AssistantMessage(
        override val id: String,
        val text: String,
        val thinking: String,
    ) : ChatItem

    /** One row per tool call. [preview] is the one-line summary of the call's input
     * (e.g. the shell command for Bash); blank when the server sent none. [input] and
     * [output] are the full call input (pretty JSON) and result text, revealed by the
     * expanded row (ticket 023); either may be blank while the call is still running.
     * While running, [output] accumulates the volatile tool.progress chunks (ticket 025)
     * — the definitive tool.result output replaces them wholesale. */
    data class ToolActivity(
        override val id: String,
        val name: String,
        val status: ToolStatus,
        val preview: String = "",
        val input: String = "",
        val output: String = "",
    ) : ChatItem
}
