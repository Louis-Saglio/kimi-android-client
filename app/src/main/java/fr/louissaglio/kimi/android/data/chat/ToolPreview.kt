package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The one-line preview shown on a tool-activity row (ticket 022): what the call is
 * doing, derived from its input. Deliberately dumb — a short priority list of
 * identifying argument keys (Bash's `command`, the file tools' `path`), no per-tool
 * framework; anything else falls back to the compact input JSON.
 *
 * Bash rows are one exception (ticket 095): the call's `description` argument —
 * the human-written label of what the command does — leads the list, matching the
 * CLI and the jobs widget. Agent/AgentSwarm rows get the same treatment: their
 * `description` is the sub-agent's short label, far more readable on the row than
 * the compact JSON of the whole prompt payload.
 *
 * The lookup tools (ticket 122) identify by their one query argument: Glob's
 * `pattern`, WebSearch's `query`, FetchURL's `url` (the raw URL — the row's
 * host-first reformatting of it lives in ToolRow, not here). Grep (ticket 123)
 * identifies by `pattern` too; the row renders it slash-delimited.
 *
 * The cron tools (ticket 124) identify by their scheduling argument:
 * CronCreate's `cron` expression, CronDelete's job `id`. CronList carries no
 * arguments at all — its "list schedules" preview is the row's own label.
 *
 * The meta tools (ticket 125): a Skill call previews the invocation — the `skill`
 * name with the `args` string appended when present; a SetGoalBudget call
 * previews both arguments together ("30 minutes"); CreateGoal identifies by its
 * `objective`. UpdateGoal shows no preview — the row's status badge replaces it
 * (the row owns that, like ExitPlanMode's fixed "Plan ready for review" label).
 * ReadMediaFile's `path` is already a generic identifying key.
 */
object ToolPreview {

    private val IDENTIFYING_KEYS = listOf("command", "path")
    private val BASH_IDENTIFYING_KEYS = listOf("description", "command", "path")
    private val AGENT_IDENTIFYING_KEYS = listOf("description")
    private val GLOB_IDENTIFYING_KEYS = listOf("pattern")
    private val WEB_SEARCH_IDENTIFYING_KEYS = listOf("query")
    private val FETCH_URL_IDENTIFYING_KEYS = listOf("url")
    private val GREP_IDENTIFYING_KEYS = listOf("pattern")
    private val CRON_CREATE_IDENTIFYING_KEYS = listOf("cron")
    private val CRON_DELETE_IDENTIFYING_KEYS = listOf("id")
    private val CREATE_GOAL_IDENTIFYING_KEYS = listOf("objective")
    private val WHITESPACE = Regex("\\s+")

    fun of(toolName: String?, input: JsonObject?): String {
        if (input.isNullOrEmpty()) return ""
        when (toolName) {
            // Ticket 125: a Skill row reads as the invocation — the skill name,
            // its argument string appended when present.
            "Skill" -> {
                val skill = (input["skill"] as? JsonPrimitive)?.contentOrNull
                if (!skill.isNullOrBlank()) {
                    val skillArgs = (input["args"] as? JsonPrimitive)?.contentOrNull
                    return oneLine(if (skillArgs.isNullOrBlank()) skill else "$skill $skillArgs")
                }
            }
            // Ticket 125: the budget call previews "value unit" ("30 minutes") —
            // both arguments together; the primitive's own content keeps 30 as
            // "30", never "30.0".
            "SetGoalBudget" -> {
                val value = (input["value"] as? JsonPrimitive)?.contentOrNull
                val unit = (input["unit"] as? JsonPrimitive)?.contentOrNull
                if (!value.isNullOrBlank() && !unit.isNullOrBlank()) return oneLine("$value $unit")
            }
        }
        val keys = when (toolName) {
            "Bash" -> BASH_IDENTIFYING_KEYS
            "Agent", "AgentSwarm" -> AGENT_IDENTIFYING_KEYS
            "Glob" -> GLOB_IDENTIFYING_KEYS
            "WebSearch" -> WEB_SEARCH_IDENTIFYING_KEYS
            "FetchURL" -> FETCH_URL_IDENTIFYING_KEYS
            "Grep" -> GREP_IDENTIFYING_KEYS
            "CronCreate" -> CRON_CREATE_IDENTIFYING_KEYS
            "CronDelete" -> CRON_DELETE_IDENTIFYING_KEYS
            "CreateGoal" -> CREATE_GOAL_IDENTIFYING_KEYS
            else -> IDENTIFYING_KEYS
        }
        for (key in keys) {
            val value = (input[key] as? JsonPrimitive)?.contentOrNull
            if (!value.isNullOrBlank()) return oneLine(value)
        }
        return oneLine(input.toString())
    }

    /** One line only: collapse embedded newlines/indentation. Shared with
     * ToolDetailFields (the expanded row's arg values get the same treatment). */
    internal fun oneLine(raw: String): String = raw.replace(WHITESPACE, " ").trim()
}
