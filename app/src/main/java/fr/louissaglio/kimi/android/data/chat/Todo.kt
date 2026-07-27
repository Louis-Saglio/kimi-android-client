package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One item of the agent's todo list (ticket 042), mirrored read-only from its
 * TodoList tool calls — the wire protocol has no dedicated todo event. */
data class Todo(val title: String, val status: Status) {
    enum class Status { Pending, InProgress, Done }
}

/**
 * Extracts the todo list from a tool call's input JSON, in the spirit of [ToolPreview]:
 * deliberately dumb and total — malformed input parses to null, never crashes.
 */
object TodoParser {

    /** The tool name the server emits for the agent's todo tool. */
    const val TOOL_NAME = "TodoList"

    private val json = Json { ignoreUnknownKeys = true }

    /** The todo list carried by a TodoList call, or null when [name] is not the todo
     * tool or [input] is missing/malformed — the caller then leaves the current widget
     * state untouched. An empty `todos` array parses to an empty list, which clears the
     * widget. Unknown status strings degrade to Pending: the server-side set
     * (pending / in_progress / done) may grow, and a pending row is the least wrong
     * rendering. Items without a non-blank title are skipped. */
    fun of(name: String, input: String): List<Todo>? {
        if (name != TOOL_NAME || input.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(input) }.getOrNull() as? JsonObject ?: return null
        val items = root["todos"] as? JsonArray ?: return null
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val title = (item["title"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val status = when ((item["status"] as? JsonPrimitive)?.contentOrNull) {
                "done" -> Todo.Status.Done
                "in_progress" -> Todo.Status.InProgress
                else -> Todo.Status.Pending
            }
            Todo(title, status)
        }
    }
}
