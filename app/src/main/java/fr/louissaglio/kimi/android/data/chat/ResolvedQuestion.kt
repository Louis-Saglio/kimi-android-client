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
 * Answer values resolve to options by label match; anything unmatched is the free-text
 * ("Other") answer. Single-select items (`multi_select: false`) match the whole string.
 * Multi-select items (ticket 090) come back flattened as labels joined with `", "`
 * (`"Red, Blue"`, free text appended the same way for `multi_with_other`): the value is
 * split on `", "`, segments matching an option label mark it selected, unmatched
 * segments rejoin as the Other text.
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
        /** True when the item was asked multi-select (090); absent in the input = false. */
        val multiSelect: Boolean,
        /** The matched option labels of the answer, in answer order; empty when none matched. */
        val selectedLabels: List<String>,
        /** The free-text answer (the unmatched `", "`-joined segments for a multi item);
         * null when every segment matched an option. */
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
    val selections: Map<String, Set<String>>,
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
                multiSelect = item.multiSelect,
            )
        },
    )
    val selections = items.mapIndexedNotNull { i, item ->
        item.selectedLabels.takeIf { it.isNotEmpty() }?.let { labels ->
            val ids = labels.mapNotNull { label ->
                item.options.indexOfFirst { it.label == label }
                    .takeIf { it >= 0 }
                    ?.let { "opt_${i}_$it" }
            }
            if (ids.isEmpty()) null else "q_$i" to ids.toSet()
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
            val multiSelect = q["multi_select"]?.jsonPrimitive?.contentOrNull == "true"
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
            if (multiSelect) {
                // Multi answers come back flattened: labels joined with ", ", the
                // multi_with_other free text appended the same way (090). Segments
                // matching an option label select it; the rest rejoins as Other.
                val segments = value?.split(", ")?.filter { it.isNotEmpty() }.orEmpty()
                val (matched, unmatched) = segments.partition { s -> options.any { it.label == s } }
                ResolvedQuestion.Item(
                    header = header,
                    text = text,
                    options = options,
                    multiSelect = true,
                    selectedLabels = matched,
                    otherText = unmatched.takeIf { it.isNotEmpty() }?.joinToString(", "),
                )
            } else {
                val selectedLabel = value?.takeIf { v -> options.any { it.label == v } }
                ResolvedQuestion.Item(
                    header = header,
                    text = text,
                    options = options,
                    multiSelect = false,
                    selectedLabels = listOfNotNull(selectedLabel),
                    otherText = value?.takeIf { selectedLabel == null },
                )
            }
        }
        return ResolvedQuestion(toolCallId, items, note)
    }
}
