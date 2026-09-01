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
 * [Read]/[Write] (ticket 120), the range/size chips and the file content screen;
 * for [Bash] (ticket 121), the terminal-styled inline expansion (the first
 * payload-typed expanded row — the others navigate away);
 * for [Glob]/[WebSearch]/[FetchUrl] (ticket 122), the read-only lookup rows — they
 * keep the inline expansion like [Bash], with the result rendered per tool (counted
 * path list, markdown, verbatim content) instead of the generic args table.
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

    /** The Bash call's inputs (ticket 121). [command] is required — a call without
     * one keeps the generic expansion. [description]/[cwd] are null when unset or
     * blank (neither earns UI space then). [timeoutSeconds]/[timeoutDisabled] are the
     * input's timeout knobs, the same [bashTimeoutOf] derivation as the row's budget
     * fields (ticket 097) — the payload carries them for completeness; the expanded
     * row's chips read the row's fields. */
    data class Bash(
        val command: String,
        val description: String?,
        val cwd: String?,
        val timeoutSeconds: Int?,
        val timeoutDisabled: Boolean,
    ) : ToolPayload

    /** The Glob call's inputs (ticket 122). [pattern] is required — a call without
     * one keeps the generic expansion. [path] (the scope override) is null when unset
     * or blank; [includeIgnored] is the wire's `include_ignored` flag (default false). */
    data class Glob(
        val pattern: String,
        val path: String?,
        val includeIgnored: Boolean,
    ) : ToolPayload

    /** The WebSearch call's input (ticket 122) — the [query] is prose, rendered as
     * prose on the row (no monospace). */
    data class WebSearch(val query: String) : ToolPayload

    /** The FetchURL call's input (ticket 122); the row reformats [url] host-first
     * itself, the expansion shows it in full. */
    data class FetchUrl(val url: String) : ToolPayload
}

/** The payload parser: one variant per known tool, null for anything else —
 * unknown tool, absent args, or a call missing one of its required keys (an
 * unparseable row keeps the generic expansion). */
object ToolPayloads {

    private const val EDIT_TOOL_NAME = "Edit"
    private const val READ_TOOL_NAME = "Read"
    private const val WRITE_TOOL_NAME = "Write"
    private const val BASH_TOOL_NAME = "Bash"
    private const val GLOB_TOOL_NAME = "Glob"
    private const val WEB_SEARCH_TOOL_NAME = "WebSearch"
    private const val FETCH_URL_TOOL_NAME = "FetchURL"

    fun of(toolName: String, args: JsonObject?): ToolPayload? = when (toolName) {
        EDIT_TOOL_NAME -> editOf(args)
        READ_TOOL_NAME -> readOf(args)
        WRITE_TOOL_NAME -> writeOf(args)
        BASH_TOOL_NAME -> bashOf(args)
        GLOB_TOOL_NAME -> globOf(args)
        WEB_SEARCH_TOOL_NAME -> webSearchOf(args)
        FETCH_URL_TOOL_NAME -> fetchUrlOf(args)
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

    private fun bashOf(args: JsonObject?): ToolPayload.Bash? {
        if (args == null) return null
        val command = args.stringOf("command") ?: return null
        val timeout = bashTimeoutOf(args)
        return ToolPayload.Bash(
            command,
            description = args.stringOf("description")?.takeIf { it.isNotBlank() },
            cwd = args.stringOf("cwd")?.takeIf { it.isNotBlank() },
            timeoutSeconds = timeout.seconds,
            timeoutDisabled = timeout.disabled,
        )
    }

    private fun globOf(args: JsonObject?): ToolPayload.Glob? {
        if (args == null) return null
        val pattern = args.stringOf("pattern") ?: return null
        val includeIgnored = (args["include_ignored"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.booleanOrNull ?: false
        return ToolPayload.Glob(
            pattern,
            path = args.stringOf("path")?.takeIf { it.isNotBlank() },
            includeIgnored = includeIgnored,
        )
    }

    private fun webSearchOf(args: JsonObject?): ToolPayload.WebSearch? {
        if (args == null) return null
        val query = args.stringOf("query") ?: return null
        return ToolPayload.WebSearch(query)
    }

    private fun fetchUrlOf(args: JsonObject?): ToolPayload.FetchUrl? {
        if (args == null) return null
        val url = args.stringOf("url") ?: return null
        return ToolPayload.FetchUrl(url)
    }

    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.intOf(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull
}
