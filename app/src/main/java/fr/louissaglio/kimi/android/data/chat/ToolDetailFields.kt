package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The generic key/value detail table of a tool call (ticket 118): every top-level
 * argument of the call's raw `args` object as one label/value row, in wire order.
 * Deliberately dumb — no per-tool knowledge; per-tool tuning (which fields earn UI
 * space) is the follow-up tickets' job (119–125). Strings render raw and one-lined
 * (like [ToolPreview.oneLine]), numbers/booleans literal, nulls are dropped, and
 * nested objects/arrays render as compact one-line JSON.
 */
object ToolDetailFields {

    data class Field(val label: String, val value: String)

    fun of(args: JsonObject?): List<Field> {
        if (args.isNullOrEmpty()) return emptyList()
        return args.entries.mapNotNull { (label, element) ->
            when (element) {
                is JsonNull -> null
                is JsonPrimitive -> Field(
                    label,
                    if (element.isString) ToolPreview.oneLine(element.content) else element.content,
                )
                // JsonObject/JsonArray: toString() is the compact (single-line) JSON.
                else -> Field(label, element.toString())
            }
        }
    }
}
