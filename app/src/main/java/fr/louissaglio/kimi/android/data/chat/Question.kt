package fr.louissaglio.kimi.android.data.chat

/**
 * Domain model of a pending agent question (the `AskUserQuestion` tool,
 * docs/server-api.md, Interactions). [questionId] is the `question_<uuid>`
 * interaction id, distinct from [toolCallId]: answer/dismiss route by [questionId]
 * (`POST .../interactions/{id}` — ticket 172), while [toolCallId] links the
 * request to its transcript tool row. One request carries one or more structured
 * items to answer.
 *
 * [agentId] is the ASKING agent (2.0.2 — ticket 156): "main", or the sub-agent's
 * id — the card's sub-agent badge reads it exactly like an approval's (139's idiom).
 */
data class PendingQuestion(
    val questionId: String,
    val toolCallId: String,
    val items: List<Item>,
    val agentId: String = MAIN_AGENT_ID,
) {
    data class Item(
        val id: String,
        val header: String,
        val text: String,
        val options: List<Option>,
        val allowOther: Boolean,
        /** True when several options may be ticked (checkboxes; ticket 090). Absent on the wire = false. */
        val multiSelect: Boolean = false,
    )

    data class Option(
        val id: String,
        val label: String,
        val description: String?,
    )
}

/** One per-question-item answer of the `POST .../interactions/{question_id}` body's
 * `answers` map (ticket 172). */
sealed interface QuestionAnswer {
    val itemId: String

    /** The user picked a structured option (`{"kind":"single","option_id":...}`). */
    data class OptionPick(override val itemId: String, val optionId: String) : QuestionAnswer

    /** Free text, only when the item allows it (`{"kind":"other","text":...}`). */
    data class Other(override val itemId: String, val text: String) : QuestionAnswer

    /** Several ticked options of a multi-select item (`{"kind":"multi","option_ids":[...]}`;
     * ticket 090). The server refuses an empty [optionIds], client-side gating prevents it. */
    data class Multi(override val itemId: String, val optionIds: List<String>) : QuestionAnswer

    /** Ticked options plus free text of a multi-select item
     * (`{"kind":"multi_with_other","option_ids":[...],"other_text":...}`; ticket 090). */
    data class MultiWithOther(
        override val itemId: String,
        val optionIds: List<String>,
        val otherText: String,
    ) : QuestionAnswer
}
