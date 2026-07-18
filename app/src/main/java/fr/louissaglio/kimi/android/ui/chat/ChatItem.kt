package fr.louissaglio.kimi.android.ui.chat

enum class ToolStatus { Running, Done, Error }

/** One row of the chat timeline. [id] is the LazyColumn key: message id for bubbles,
 * tool call id for activity rows, synthetic `live_N` for in-flight streaming bubbles. */
sealed interface ChatItem {
    val id: String

    data class UserMessage(override val id: String, val text: String) : ChatItem

    data class AssistantMessage(
        override val id: String,
        val text: String,
        val thinking: String,
    ) : ChatItem

    data class ToolActivity(
        override val id: String,
        val name: String,
        val status: ToolStatus,
    ) : ChatItem
}
