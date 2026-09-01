package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A tool call's typed payload (ticket 119): the parsed, per-tool model of the call's
 * raw `args`, derived next to them (ChatTimeline, first-writer-wins like `args`
 * itself). The generic key/value table (ToolDetailFields) stays the fallback for
 * everything without a typed payload; a row whose payload parsed gets the rich
 * treatment instead — for [Edit], the `+a −b` stat chip and the diff screen; for
 * [Read]/[Write] (ticket 120), the range/size chips and the file content screen.
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

    /** The Read call's inputs (ticket 120). [offset]/[limit] are the wire's
     * `line_offset`/`n_lines`; both null for a full-file read. The screen's content
     * is the call's OUTPUT, not anything here — the payload carries no file text. */
    data class Read(
        val path: String,
        val offset: Int?,
        val limit: Int?,
    ) : ToolPayload {

        /** The row's range chip ("lines 40–139"), or null when neither argument
         * restricts the read — a full-file read gets no chip. The numbers are the
         * wire's own (1-based as sent); an offset-only read is open-ended. */
        val rangeLabel: String?
            get() = when {
                offset != null && limit != null -> "lines $offset–${offset + limit - 1}"
                limit != null -> "lines 1–$limit"
                offset != null -> "lines $offset–end"
                else -> null
            }
    }

    /** The Write call's inputs (ticket 120); [append] is the `mode: "append"` flag —
     * overwrite is the default and shows no badge. */
    data class Write(
        val path: String,
        val content: String,
        val append: Boolean,
    ) : ToolPayload {

        /** The row chip's line count (ticket 120: `content.lines().size` — a
         * trailing newline's empty tail counts, as the expression does). */
        val lineCount: Int get() = content.lines().size

        /** The row chip's byte size — UTF-8, the wire's encoding. */
        val byteSize: Long get() = content.toByteArray().size.toLong()
    }
}

/** The payload parser: one variant per known tool, null for anything else —
 * unknown tool, absent args, or a call missing one of its required keys (an
 * unparseable row keeps the generic expansion). */
object ToolPayloads {

    private const val EDIT_TOOL_NAME = "Edit"
    private const val READ_TOOL_NAME = "Read"
    private const val WRITE_TOOL_NAME = "Write"

    fun of(toolName: String, args: JsonObject?): ToolPayload? = when (toolName) {
        EDIT_TOOL_NAME -> editOf(args)
        READ_TOOL_NAME -> readOf(args)
        WRITE_TOOL_NAME -> writeOf(args)
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

    private fun readOf(args: JsonObject?): ToolPayload.Read? {
        if (args == null) return null
        val path = args.stringOf("path") ?: return null
        return ToolPayload.Read(path, args.intOf("line_offset"), args.intOf("n_lines"))
    }

    private fun writeOf(args: JsonObject?): ToolPayload.Write? {
        if (args == null) return null
        val path = args.stringOf("path") ?: return null
        // Empty content is legitimate (writing an empty file) — like Edit's empty
        // strings, only a missing or non-string key rejects the payload.
        val content = args.stringOf("content") ?: return null
        val append = (args["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull == "append"
        return ToolPayload.Write(path, content, append)
    }

    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.intOf(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull
}
