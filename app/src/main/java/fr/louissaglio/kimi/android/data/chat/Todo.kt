package fr.louissaglio.kimi.android.data.chat

/** One item of an agent's todo list (ticket 042), mirrored read-only from the agent's
 * flat `todo` frame (server 2.5.0 — docs/server-api.md, Todos; ticket 178). */
data class Todo(val title: String, val status: Status) {
    enum class Status { Pending, InProgress, Done }
}

/** The tool name the server emits for the agent's todo tool: its calls render no
 * timeline row (ticket 042 — the widget, fed by the `todo` frame, replaces them). */
const val TODO_LIST_TOOL_NAME = "TodoList"

/**
 * The `todo` frame's items → the domain list (ticket 178), deliberately total: items
 * without a non-blank title are skipped, and an unknown status string degrades to
 * Pending — the server-side set (pending / in_progress / done) may grow, and a pending
 * row is the least wrong rendering. An empty list clears the widget.
 */
internal fun TodoFrame.toTodos(): List<Todo> = items.mapNotNull { item ->
    val title = item.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
    val status = when (item.status) {
        "done" -> Todo.Status.Done
        "in_progress" -> Todo.Status.InProgress
        else -> Todo.Status.Pending
    }
    Todo(title, status)
}
