package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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
 * path list, markdown, verbatim content) instead of the generic args table;
 * for [Grep] (ticket 123), the same inline expansion with the result parsed per
 * `output_mode` ([GrepResultParser]);
 * for the cron/task-control calls (ticket 124): [CronCreate] keeps the inline
 * expansion (the scheduled prompt in full — the result is `key: value` prose on
 * the wire, not JSON, so it renders verbatim; docs/server-api.md), [CronDelete]
 * stays a lean generic row, and [TaskOutput]/[TaskStop]/[WaitFor] link their row
 * to the job screen via the carried `task_id` (the background-Bash treatment —
 * linked rows never expand inline); [TaskList] and a task-less [WaitFor] keep the
 * generic expansion.
 * The meta calls (ticket 125) close the arc with lean tuned rows — nothing
 * navigates away: [Skill] previews the invocation, [ReadMedia] carries the crop
 * badge's dimensions, [ExitPlanMode] the expansion's option chips, [CreateGoal]
 * the objective preview, [UpdateGoal] the status badge, [SetGoalBudget] the
 * budget preview. `GetGoal`/`EnterPlanMode` have no payload by design — their
 * rows stay fully generic.
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

    /** The Grep call's inputs (ticket 123). [pattern] is required — a call without
     * one keeps the generic expansion. [path]/[glob] are null when unset or blank;
     * the row's scope chip prefers [path]. [outputMode] is the wire's `output_mode`
     * string, kept as a string: a drifted value still gets the rich row, only the
     * result parse falls back to verbatim. [contextBefore]/[contextAfter] are the
     * wire's `-B`/`-A` knobs — `-C` sets both and wins over them (the tool's arg
     * building, 0.39.1). head_limit/offset/`-n` never earn UI space, so the payload
     * does not carry them at all. */
    data class Grep(
        val pattern: String,
        val path: String?,
        val glob: String?,
        val outputMode: String,
        val caseInsensitive: Boolean,
        val contextBefore: Int?,
        val contextAfter: Int?,
        val multiline: Boolean,
        val includeIgnored: Boolean,
        val type: String?,
    ) : ToolPayload {

        /** The expansion's context chips: `±N` when the before/after context is
         * symmetric, a `+N`/`-N` pair when asymmetric; empty when no context was
         * asked. */
        val contextChips: List<String>
            get() {
                val before = contextBefore ?: 0
                val after = contextAfter ?: 0
                return when {
                    before == after -> if (before > 0) listOf("±$before") else emptyList()
                    else -> buildList {
                        if (after > 0) add("+$after")
                        if (before > 0) add("-$before")
                    }
                }
            }

        /** True when any expansion-chip knob is non-default — the chips row and
         * the row's expandability rule both read it. */
        val hasNonDefaultKnobs: Boolean
            get() = caseInsensitive || contextChips.isNotEmpty() || multiline || includeIgnored || type != null
    }

    /** The CronCreate call's inputs (ticket 124). [cron] is the 5-field expression
     * (the row's monospace preview); [prompt] the scheduled prompt, shown in full on
     * expansion. [recurring] is the wire flag — true by default, so a `false`
     * one-shot earns the row's "one-shot" badge. The call's RESULT stays unparsed:
     * it is `key: value` prose on the wire (verified live 2026-09-01,
     * docs/server-api.md), not the JSON the ticket allowed for. */
    data class CronCreate(
        val cron: String,
        val prompt: String,
        val recurring: Boolean,
    ) : ToolPayload

    /** The CronDelete call's input (ticket 124) — [id] is the row's preview; the
     * row keeps the generic expansion. */
    data class CronDelete(val id: String) : ToolPayload

    /** The TaskOutput call's input (ticket 124): [taskId] links the row to the job
     * screen — the background-Bash row treatment (trailing chevron, no inline
     * expansion). */
    data class TaskOutput(val taskId: String) : ToolPayload

    /** The TaskStop call's input (ticket 124) — the same job-screen link as
     * [TaskOutput]. */
    data class TaskStop(val taskId: String) : ToolPayload

    /** The WaitFor call's input (ticket 124): [taskId] is null for a wait-any call —
     * only a task-linked row gets the job-screen chevron; a wait-any row keeps the
     * generic expansion. */
    data class WaitFor(val taskId: String?) : ToolPayload

    /** The TaskList call's inputs (ticket 124), with the wire defaults
     * (`active_only` true, no limit) — parsed for completeness; the row keeps the
     * generic expansion. */
    data class TaskList(val activeOnly: Boolean, val limit: Int?) : ToolPayload

    /** The Skill call's inputs (ticket 125). [skill] is required — a call without
     * one keeps the generic expansion. [args] is the optional argument string,
     * null when unset or blank; the row preview (ToolPreview) appends it to the
     * skill name. The expansion stays the generic key/value table. */
    data class Skill(val skill: String, val args: String?) : ToolPayload

    /** The ReadMediaFile call's inputs (ticket 125). [path] is required.
     * [regionWidth]/[regionHeight] are the crop's dimensions — the parser is
     * all-or-nothing, so both are null for a half-specified `region` (no badge).
     * The row keeps the inline generic expansion. */
    data class ReadMedia(
        val path: String,
        val regionWidth: Int?,
        val regionHeight: Int?,
    ) : ToolPayload {

        /** The row's crop badge ("crop 100×200"), null without a full region. */
        val cropLabel: String?
            get() = if (regionWidth != null && regionHeight != null) "crop ${regionWidth}×${regionHeight}" else null
    }

    /** The ExitPlanMode call's options (ticket 125): [optionLabels] render as chips
     * above the generic args table in the expansion. Empty for a plain plan
     * approval (the wire's `options` is optional); an entry without a string
     * label is dropped. */
    data class ExitPlanMode(val optionLabels: List<String>) : ToolPayload

    /** The CreateGoal call's inputs (ticket 125). [objective] is required (the
     * row's preview); [completionCriterion] is null when unset or blank and stays
     * in the generic expansion (as does the `replace` flag, never carried). */
    data class CreateGoal(val objective: String, val completionCriterion: String?) : ToolPayload

    /** The UpdateGoal call's input (ticket 125): [status] renders as the row's
     * small badge instead of a preview. Kept as a string (the Grep `output_mode`
     * rule): a drifted value still earns the badge, only the color falls back to
     * neutral. */
    data class UpdateGoal(val status: String) : ToolPayload {

        /** The badge's color role — the wire's three statuses map to success /
         * error / primary. */
        enum class BadgeColor { SUCCESS, ERROR, PRIMARY, NEUTRAL }

        val badgeColor: BadgeColor
            get() = when (status) {
                "complete" -> BadgeColor.SUCCESS
                "blocked" -> BadgeColor.ERROR
                "active" -> BadgeColor.PRIMARY
                else -> BadgeColor.NEUTRAL
            }
    }

    /** The SetGoalBudget call's inputs (ticket 125): the row previews
     * "[value] [unit]" ("30 minutes" — ToolPreview). The wire's `value` is a
     * positive number, fractional allowed (1.5 hours). */
    data class SetGoalBudget(val value: Double, val unit: String) : ToolPayload
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
    private const val GREP_TOOL_NAME = "Grep"
    private const val CRON_CREATE_TOOL_NAME = "CronCreate"
    private const val CRON_DELETE_TOOL_NAME = "CronDelete"
    private const val TASK_OUTPUT_TOOL_NAME = "TaskOutput"
    private const val TASK_STOP_TOOL_NAME = "TaskStop"
    private const val WAIT_FOR_TOOL_NAME = "WaitFor"
    private const val TASK_LIST_TOOL_NAME = "TaskList"
    private const val SKILL_TOOL_NAME = "Skill"
    private const val READ_MEDIA_TOOL_NAME = "ReadMediaFile"
    private const val EXIT_PLAN_MODE_TOOL_NAME = "ExitPlanMode"
    private const val CREATE_GOAL_TOOL_NAME = "CreateGoal"
    private const val UPDATE_GOAL_TOOL_NAME = "UpdateGoal"
    private const val SET_GOAL_BUDGET_TOOL_NAME = "SetGoalBudget"

    fun of(toolName: String, args: JsonObject?): ToolPayload? = when (toolName) {
        EDIT_TOOL_NAME -> editOf(args)
        READ_TOOL_NAME -> readOf(args)
        WRITE_TOOL_NAME -> writeOf(args)
        BASH_TOOL_NAME -> bashOf(args)
        GLOB_TOOL_NAME -> globOf(args)
        WEB_SEARCH_TOOL_NAME -> webSearchOf(args)
        FETCH_URL_TOOL_NAME -> fetchUrlOf(args)
        GREP_TOOL_NAME -> grepOf(args)
        CRON_CREATE_TOOL_NAME -> cronCreateOf(args)
        CRON_DELETE_TOOL_NAME -> cronDeleteOf(args)
        TASK_OUTPUT_TOOL_NAME -> taskOutputOf(args)
        TASK_STOP_TOOL_NAME -> taskStopOf(args)
        WAIT_FOR_TOOL_NAME -> waitForOf(args)
        TASK_LIST_TOOL_NAME -> taskListOf(args)
        SKILL_TOOL_NAME -> skillOf(args)
        READ_MEDIA_TOOL_NAME -> readMediaOf(args)
        EXIT_PLAN_MODE_TOOL_NAME -> exitPlanModeOf(args)
        CREATE_GOAL_TOOL_NAME -> createGoalOf(args)
        UPDATE_GOAL_TOOL_NAME -> updateGoalOf(args)
        SET_GOAL_BUDGET_TOOL_NAME -> setGoalBudgetOf(args)
        else -> null
    }

    private fun editOf(args: JsonObject?): ToolPayload.Edit? {
        if (args == null) return null
        // Empty old/new strings are legitimate (pure insertion/deletion) — only a
        // missing or non-string key rejects the payload.
        val path = args.stringOf("path") ?: return null
        val oldString = args.stringOf("old_string") ?: return null
        val newString = args.stringOf("new_string") ?: return null
        return ToolPayload.Edit(path, oldString, newString, args.flagOf("replace_all"))
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
        return ToolPayload.Glob(
            pattern,
            path = args.stringOf("path")?.takeIf { it.isNotBlank() },
            includeIgnored = args.flagOf("include_ignored"),
        )
    }

    private fun grepOf(args: JsonObject?): ToolPayload.Grep? {
        if (args == null) return null
        val pattern = args.stringOf("pattern") ?: return null
        // `-C` fills both context sides and takes precedence over `-A`/`-B` (the
        // tool's rg arg building, verified on the bundled 0.39.1 source).
        val context = args.intOf("-C")
        return ToolPayload.Grep(
            pattern,
            path = args.stringOf("path")?.takeIf { it.isNotBlank() },
            glob = args.stringOf("glob")?.takeIf { it.isNotBlank() },
            outputMode = args.stringOf("output_mode") ?: GrepResultParser.MODE_FILES_WITH_MATCHES,
            caseInsensitive = args.flagOf("-i"),
            contextBefore = context ?: args.intOf("-B"),
            contextAfter = context ?: args.intOf("-A"),
            multiline = args.flagOf("multiline"),
            includeIgnored = args.flagOf("include_ignored"),
            type = args.stringOf("type")?.takeIf { it.isNotBlank() },
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

    private fun cronCreateOf(args: JsonObject?): ToolPayload.CronCreate? {
        if (args == null) return null
        val cron = args.stringOf("cron") ?: return null
        val prompt = args.stringOf("prompt") ?: return null
        return ToolPayload.CronCreate(cron, prompt, recurring = args.trueByDefaultFlagOf("recurring"))
    }

    private fun cronDeleteOf(args: JsonObject?): ToolPayload.CronDelete? {
        if (args == null) return null
        val id = args.stringOf("id") ?: return null
        return ToolPayload.CronDelete(id)
    }

    private fun taskOutputOf(args: JsonObject?): ToolPayload.TaskOutput? {
        if (args == null) return null
        val taskId = args.stringOf("task_id") ?: return null
        return ToolPayload.TaskOutput(taskId)
    }

    private fun taskStopOf(args: JsonObject?): ToolPayload.TaskStop? {
        if (args == null) return null
        val taskId = args.stringOf("task_id") ?: return null
        return ToolPayload.TaskStop(taskId)
    }

    private fun waitForOf(args: JsonObject?): ToolPayload.WaitFor? {
        if (args == null) return null
        // A wait-any call (no task_id) still parses — the row decides on the id.
        return ToolPayload.WaitFor(args.stringOf("task_id"))
    }

    private fun taskListOf(args: JsonObject?): ToolPayload.TaskList? {
        if (args == null) return null
        return ToolPayload.TaskList(
            activeOnly = args.trueByDefaultFlagOf("active_only"),
            limit = args.intOf("limit"),
        )
    }

    private fun skillOf(args: JsonObject?): ToolPayload.Skill? {
        if (args == null) return null
        val skill = args.stringOf("skill") ?: return null
        return ToolPayload.Skill(skill, args.stringOf("args")?.takeIf { it.isNotBlank() })
    }

    private fun readMediaOf(args: JsonObject?): ToolPayload.ReadMedia? {
        if (args == null) return null
        val path = args.stringOf("path") ?: return null
        // The crop badge needs both dimensions — a half-specified region parses
        // as no region at all (all-or-nothing, so the payload's invariant holds).
        val region = args["region"] as? JsonObject
        val width = region?.intOf("width")
        val height = region?.intOf("height")
        val fullRegion = width != null && height != null
        return ToolPayload.ReadMedia(
            path,
            regionWidth = if (fullRegion) width else null,
            regionHeight = if (fullRegion) height else null,
        )
    }

    private fun exitPlanModeOf(args: JsonObject?): ToolPayload.ExitPlanMode? {
        if (args == null) return null
        // `options` is optional on the wire — a plain plan approval carries none.
        val labels = (args["options"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.stringOf("label") }
            ?: emptyList()
        return ToolPayload.ExitPlanMode(labels)
    }

    private fun createGoalOf(args: JsonObject?): ToolPayload.CreateGoal? {
        if (args == null) return null
        val objective = args.stringOf("objective") ?: return null
        return ToolPayload.CreateGoal(objective, args.stringOf("completionCriterion")?.takeIf { it.isNotBlank() })
    }

    private fun updateGoalOf(args: JsonObject?): ToolPayload.UpdateGoal? {
        if (args == null) return null
        val status = args.stringOf("status") ?: return null
        return ToolPayload.UpdateGoal(status)
    }

    private fun setGoalBudgetOf(args: JsonObject?): ToolPayload.SetGoalBudget? {
        if (args == null) return null
        val value = args.doubleOf("value") ?: return null
        val unit = args.stringOf("unit") ?: return null
        return ToolPayload.SetGoalBudget(value, unit)
    }

    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.intOf(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.doubleOf(key: String): Double? =
        (this[key] as? JsonPrimitive)?.doubleOrNull

    /** A wire boolean flag: a string "true" is not the flag (the Edit
     * `replace_all` rule). */
    private fun JsonObject.flagOf(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false

    /** The ticket-124 flags whose wire default is TRUE (`recurring`,
     * `active_only`): absent means true, a string "false" is not the flag. */
    private fun JsonObject.trueByDefaultFlagOf(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: true
}
