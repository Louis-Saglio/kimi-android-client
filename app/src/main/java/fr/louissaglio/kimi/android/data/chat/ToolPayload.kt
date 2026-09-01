package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * A tool call's typed payload (ticket 119): the parsed, per-tool model of the call's
 * raw `args`, derived next to them (ChatTimeline, first-writer-wins like `args`
 * itself). The generic key/value table (ToolDetailFields) stays the fallback for
 * everything without a typed payload; a row whose payload parsed gets the rich
 * treatment instead — for [Edit], the `+a −b` stat chip and the diff screen.
 */
sealed interface ToolPayload {

    /** The Edit call's four inputs, diffed client-side (old vs new, line level) —
     * no file fetch, no surrounding-context reconstruction. */
    data class Edit(
        val path: String,
        val oldString: String,
        val newString: String,
        val replaceAll: Boolean,
    ) : ToolPayload
}

/** The payload parser: one variant per known tool, null for anything else —
 * unknown tool, absent args, or an Edit call missing one of its strings (an
 * unparseable Edit row keeps the generic expansion). */
object ToolPayloads {

    private const val EDIT_TOOL_NAME = "Edit"

    fun of(toolName: String, args: JsonObject?): ToolPayload? = when (toolName) {
        EDIT_TOOL_NAME -> editOf(args)
        else -> null
    }

    private fun editOf(args: JsonObject?): ToolPayload.Edit? {
        if (args == null) return null
        // Empty old/new strings are legitimate (pure insertion/deletion) — only a
        // missing or non-string key rejects the payload.
        val path = args.stringOf("path") ?: return null
        val oldString = args.stringOf("old_string") ?: return null
        val newString = args.stringOf("new_string") ?: return null
        val replaceAll = (args["replace_all"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.booleanOrNull ?: false
        return ToolPayload.Edit(path, oldString, newString, replaceAll)
    }

    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
