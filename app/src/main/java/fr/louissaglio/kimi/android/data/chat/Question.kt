package fr.louissaglio.kimi.android.data.chat

/**
 * Domain model of a pending agent question (the `AskUserQuestion` tool,
 * docs/server-api.md, Questions). The question id is the tool call id; one
 * request carries one or more structured items to answer.
 */
data class PendingQuestion(
    val questionId: String,
    val items: List<Item>,
) {
    data class Item(
        val id: String,
        val header: String,
        val text: String,
        val options: List<Option>,
        val allowOther: Boolean,
    )

    data class Option(
        val id: String,
        val label: String,
        val description: String?,
    )
}

/** One per-question-item answer of the `POST .../questions/{qid}` body. */
sealed interface QuestionAnswer {
    val itemId: String

    /** The user picked a structured option (`{"kind":"single","option_id":...}`). */
    data class OptionPick(override val itemId: String, val optionId: String) : QuestionAnswer

    /** Free text, only when the item allows it (`{"kind":"other","text":...}`). */
    data class Other(override val itemId: String, val text: String) : QuestionAnswer
}
