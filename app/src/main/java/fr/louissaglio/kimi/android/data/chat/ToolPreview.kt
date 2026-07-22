package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The one-line preview shown on a tool-activity row (ticket 022): what the call is
 * doing, derived from its input. Deliberately dumb — a short priority list of
 * identifying argument keys (Bash's `command`, the file tools' `path`), no per-tool
 * framework; anything else falls back to the compact input JSON.
 */
object ToolPreview {

    private val IDENTIFYING_KEYS = listOf("command", "path")
    private val WHITESPACE = Regex("\\s+")

    fun of(input: JsonObject?): String {
        if (input.isNullOrEmpty()) return ""
        for (key in IDENTIFYING_KEYS) {
            val value = (input[key] as? JsonPrimitive)?.contentOrNull
            if (!value.isNullOrBlank()) return oneLine(value)
        }
        return oneLine(input.toString())
    }

    /** The row shows one line: collapse embedded newlines/indentation. */
    private fun oneLine(raw: String): String = raw.replace(WHITESPACE, " ").trim()
}
