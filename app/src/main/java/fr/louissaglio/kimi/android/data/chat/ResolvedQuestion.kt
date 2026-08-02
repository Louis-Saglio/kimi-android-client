package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A resolved (answered or dismissed) `AskUserQuestion` call, rebuilt locally from
 * the tool row's own strings (ticket 089) — no server round-trip:
 *
 * - the call args (`input`): `{"questions":[{"question","header","options":
 *   [{"label","description"}],"multi_select":false}]}` — the tool schema carries
 *   NO ids (option order is the wire order) and no `allow_other`;
 * - the tool result (`output`): `{"answers":{"<question text>":"<option label or
 *   free text>"}}`, or `{"answers":{},"note":"..."}` when dismissed (shapes per
 *   docs/server-api.md, Questions).
 *
 * Answer values resolve to an option by whole-string label match; anything
 * unmatched is the free-text ("Other") answer. Multi-select results (comma-joined
 * labels) are not resolved into multiple selections — they render as free text
 * (out of scope for 089; our flow never submits them).
 */
data class ResolvedQuestion(
    val toolCallId: String,
    val items: List<Item>,
    /** The dismissal note ("User dismissed the question without answering."); null when answered. */
    val note: String? = null,
) {
    data class Item(
        val header: String,
        val text: String,
        val options: List<Option>,
        /** The matched option label when the answer was an option pick; null otherwise. */
        val selectedLabel: String?,
        /** The free-text answer when no option label matched; null otherwise. */
        val otherText: String?,
    )

    data class Option(val label: String, val description: String?)

    val dismissed: Boolean get() = note != null
}

/** The read-only card reuses the live `QuestionCard` (ticket 089): this adapts a
 * [ResolvedQuestion] to the card's model — synthesized item/option ids in the
 * server's own `q_<i>` / `opt_<i>_<o>` convention, and the card's draft maps
 * prefilled with the given answers. */
data class ResolvedQuestionCardModel(
    val question: PendingQuestion,
    val selections: Map<String, String>,
    val otherTexts: Map<String, String>,
)

fun ResolvedQuestion.asCardModel(): ResolvedQuestionCardModel {
    val question = PendingQuestion(
        questionId = toolCallId,
        items = items.mapIndexed { i, item ->
            PendingQuestion.Item(
                id = "q_$i",
                header = item.header,
                text = item.text,
                options = item.options.mapIndexed { o, option ->
                    PendingQuestion.Option("opt_${i}_$o", option.label, option.description)
                },
                allowOther = item.otherText != null,
            )
        },
    )
    val selections = items.mapIndexedNotNull { i, item ->
        item.selectedLabel?.let { label ->
            "q_$i" to "opt_${i}_${item.options.indexOfFirst { it.label == label }}"
        }
    }.toMap()
    val otherTexts = items.mapIndexedNotNull { i, item ->
        item.otherText?.let { "q_$i" to it }
    }.toMap()
    return ResolvedQuestionCardModel(question, selections, otherTexts)
}

object ResolvedQuestionParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses a resolved row's [input]/[output]; null when either string does not
     * carry the expected shape (the row tap then no-ops — e.g. background-question
     * plain-text results or error outputs). */
    fun parse(toolCallId: String, input: String, output: String): ResolvedQuestion? {
        val inputObject = runCatching { json.parseToJsonElement(input).jsonObject }.getOrNull() ?: return null
        val questions = (inputObject["questions"] as? JsonArray) ?: return null
        val outputObject = runCatching { json.parseToJsonElement(output).jsonObject }.getOrNull() ?: return null
        val answers = outputObject["answers"] as? JsonObject ?: return null
        val note = outputObject["note"]?.jsonPrimitive?.contentOrNull

        val items = questions.map { element ->
            val q = (element as? JsonObject) ?: return null
            val text = q["question"]?.jsonPrimitive?.contentOrNull ?: return null
            val header = q["header"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val options = (q["options"] as? JsonArray)?.map { optionElement ->
                val option = (optionElement as? JsonObject) ?: return null
                ResolvedQuestion.Option(
                    label = option["label"]?.jsonPrimitive?.contentOrNull ?: return null,
                    description = option["description"]?.jsonPrimitive?.contentOrNull,
                )
            } ?: return null
            // The answer key is the question text on current transcripts; legacy
            // transcripts keyed answers by the synthesized q_<index> id (same
            // fallback as the web client's askUserToolParse).
            val value = answers[text]?.jsonPrimitive?.contentOrNull
                ?: answers["q_${questions.indexOf(element)}"]?.jsonPrimitive?.contentOrNull
            val selectedLabel = value?.takeIf { v -> options.any { it.label == v } }
            ResolvedQuestion.Item(
                header = header,
                text = text,
                options = options,
                selectedLabel = selectedLabel,
                otherText = value?.takeIf { selectedLabel == null },
            )
        }
        return ResolvedQuestion(toolCallId, items, note)
    }
}
