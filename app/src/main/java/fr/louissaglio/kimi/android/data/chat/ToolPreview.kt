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
 * Bash rows are the one exception (ticket 095): the call's `description` argument —
 * the human-written label of what the command does — leads the list, matching the
 * CLI and the jobs widget. Scoped to Bash only: adding `description` to the shared
 * list would silently relabel Agent/AgentSwarm rows, whose args carry it too.
 */
object ToolPreview {

    private val IDENTIFYING_KEYS = listOf("command", "path")
    private val BASH_IDENTIFYING_KEYS = listOf("description", "command", "path")
    private val WHITESPACE = Regex("\\s+")

    fun of(toolName: String?, input: JsonObject?): String {
        if (input.isNullOrEmpty()) return ""
        val keys = if (toolName == "Bash") BASH_IDENTIFYING_KEYS else IDENTIFYING_KEYS
        for (key in keys) {
            val value = (input[key] as? JsonPrimitive)?.contentOrNull
            if (!value.isNullOrBlank()) return oneLine(value)
        }
        return oneLine(input.toString())
    }

    /** The row shows one line: collapse embedded newlines/indentation. */
    private fun oneLine(raw: String): String = raw.replace(WHITESPACE, " ").trim()
}
