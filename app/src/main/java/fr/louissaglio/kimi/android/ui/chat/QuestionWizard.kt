package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.PendingQuestion
import fr.louissaglio.kimi.android.data.chat.QuestionAnswer
import fr.louissaglio.kimi.android.data.chat.ResolvedQuestionParser
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.data.toUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** True when every item of the head question has an answer (option ticked or other text). */
internal val ChatViewModel.UiState.isQuestionSubmittable: Boolean
    get() = pendingQuestions.firstOrNull()?.items?.all { item ->
        questionOtherTexts[item.id]?.isNotBlank() == true || questionSelections[item.id]?.isNotEmpty() == true
    } == true

/** True when the item at [ChatViewModel.UiState.questionStep] has an answer — gates the wizard's Next. */
internal val ChatViewModel.UiState.isQuestionStepAnswered: Boolean
    get() = pendingQuestions.firstOrNull()?.items?.getOrNull(questionStep)?.let { item ->
        questionOtherTexts[item.id]?.isNotBlank() == true || questionSelections[item.id]?.isNotEmpty() == true
    } == true

/**
 * The question-wizard collaborator (ticket 072 — extracted from ChatViewModel): the
 * pending-question lifecycle (queue, draft answers, wizard step), answer submission
 * and dismissal. A logic holder, NOT a second state source: ChatViewModel's UiState
 * stays the single state holder — intents arrive as calls, state updates report back
 * through [updateState] and one-shot effects through [sendEffect] (the DeltaCoalescer
 * callback pattern, ticket 065).
 *
 * [scope] is a provider, like the coalescer's: the owning ViewModel passes
 * `{ viewModelScope }`, so launched work dies with the ViewModel.
 */
internal class QuestionWizard(
    private val scope: () -> CoroutineScope,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionId: String,
    private val state: () -> ChatViewModel.UiState,
    private val updateState: ((ChatViewModel.UiState) -> ChatViewModel.UiState) -> Unit,
    private val sendEffect: suspend (ChatViewModel.Effect) -> Unit,
) {

    /** Bumped on every live question event (request, end, the ack's reset), so
     * [applyColdStart] can drop a fetch that a newer question event has overtaken. */
    private val seedGuard = SeedGuard()

    /** The head question's drafts as of an ack reset that dropped it (ticket 172): a
     * resumed subscribe carries no reset snapshot, so its reset is empty and the
     * still-pending head only comes back with the re-sent frame a moment later — the
     * stash carries the user's half-filled answers over that gap. Only touched from
     * the ViewModel's event dispatch. */
    private var droppedHeadDrafts: HeadDrafts? = null

    private data class HeadDrafts(
        val questionId: String,
        val selections: Map<String, Set<String>>,
        val otherTexts: Map<String, String>,
        val step: Int,
        val minimized: Boolean,
    )

    /** Picks (or re-picks) the option answering one single-select item of the head question. */
    fun onOptionPicked(itemId: String, optionId: String) {
        updateState { it.copy(questionSelections = it.questionSelections + (itemId to setOf(optionId))) }
    }

    /** Ticks/unticks one option of a multi-select item (ticket 090). Unticking the last
     * option drops the item's entry, which un-answers it unless its Other text is set.
     * No-ops for an option id the item does not carry — the server does not validate
     * ids, so the client is the only guard. */
    fun onOptionToggled(itemId: String, optionId: String) {
        val item = state().pendingQuestions.firstOrNull()?.items?.firstOrNull { it.id == itemId } ?: return
        if (item.options.none { it.id == optionId }) return
        updateState {
            val current = it.questionSelections[itemId].orEmpty()
            val next = if (optionId in current) current - optionId else current + optionId
            it.copy(
                questionSelections =
                    if (next.isEmpty()) it.questionSelections - itemId
                    else it.questionSelections + (itemId to next),
            )
        }
    }

    /** Free-text answer for one item (only rendered when the item allows it). For a
     * single-select item a non-blank text wins over the picked option; for a
     * multi-select item (090) it combines with the ticks into a multi_with_other answer. */
    fun onOtherTextChange(itemId: String, text: String) {
        updateState { it.copy(questionOtherTexts = it.questionOtherTexts + (itemId to text)) }
    }

    /** Advances the question wizard (ticket 035) to the next item. No-ops on the last
     * item or while the current item is unanswered, same gating as the Next button. */
    fun onNext() {
        updateState { state ->
            val lastIndex = state.pendingQuestions.firstOrNull()?.items?.lastIndex ?: return@updateState state
            if (!state.isQuestionStepAnswered || state.questionStep >= lastIndex) state
            else state.copy(questionStep = state.questionStep + 1)
        }
    }

    /** Steps the question wizard back to the previous item; drafted answers are kept. */
    fun onBack() {
        updateState { state ->
            if (state.questionStep > 0) state.copy(questionStep = state.questionStep - 1) else state
        }
    }

    /** Ticket 088: hides the card so the user can read the timeline. Purely local —
     * unlike [onDismiss] nothing is posted; drafts are kept and the pending
     * AskUserQuestion tool row reopens the card via [onReopen]. */
    fun onMinimize() {
        updateState { it.copy(questionCardMinimized = true) }
    }

    /** Ticket 088: brings a minimized card back (the AskUserQuestion tool-row tap). */
    fun onReopen() {
        updateState { it.copy(questionCardMinimized = false) }
    }

    /** Ticket 088: the pending AskUserQuestion tool-row tap toggles the card —
     * minimizes it while open, reopens it while minimized. */
    fun onToggle() {
        updateState { it.copy(questionCardMinimized = !it.questionCardMinimized) }
    }

    /** Ticket 089: any AskUserQuestion tool-row tap. The pending head's row toggles
     * the live card (088) — matched on the row's `toolCallId`, which since server
     * 0.38.0 is distinct from the pending question's id (ticket 106); a resolved
     * row opens its read-only view — or closes it when it is the view's own row.
     * Rows whose input/output don't parse (e.g. background-question plain-text
     * results) no-op. */
    fun onRowTapped(toolCallId: String, input: String, output: String) {
        val current = state()
        if (toolCallId == current.pendingQuestions.firstOrNull()?.toolCallId) {
            // The pending row always surfaces the live card: with the read-only view
            // open (which hides the dock's live card), close the view and unminimize;
            // otherwise toggle (088).
            if (current.resolvedQuestionView != null) {
                updateState {
                    it.copy(resolvedQuestionView = null, resolvedQuestionStep = 0, questionCardMinimized = false)
                }
            } else {
                onToggle()
            }
            return
        }
        if (current.resolvedQuestionView?.toolCallId == toolCallId) {
            onCloseResolvedQuestion()
            return
        }
        val resolved = ResolvedQuestionParser.parse(toolCallId, input, output) ?: return
        updateState { it.copy(resolvedQuestionView = resolved, resolvedQuestionStep = 0) }
    }

    /** Ticket 089: closes the read-only resolved view (header X, system back, or a
     * re-tap of its row). A live pending card waiting behind it reappears. */
    fun onCloseResolvedQuestion() {
        updateState { it.copy(resolvedQuestionView = null, resolvedQuestionStep = 0) }
    }

    /** Advances the read-only resolved view (089) to the next item. No answer gating
     * — unlike the live wizard there is nothing to answer. */
    fun onResolvedNext() {
        updateState { state ->
            val lastIndex = state.resolvedQuestionView?.items?.lastIndex ?: return@updateState state
            if (state.resolvedQuestionStep >= lastIndex) state
            else state.copy(resolvedQuestionStep = state.resolvedQuestionStep + 1)
        }
    }

    /** Steps the read-only resolved view (089) back to the previous item. */
    fun onResolvedBack() {
        updateState { state ->
            if (state.resolvedQuestionStep > 0) state.copy(resolvedQuestionStep = state.resolvedQuestionStep - 1)
            else state
        }
    }

    /** POSTs the drafted answers for the head question
     * (`{"kind":"question","answers":…}` to `.../interactions/{id}` — ticket 172). No-ops
     * while an item is unanswered. The card clears on the server's `ended` interaction
     * frame — for our own answers too — so a failed POST (`40902` already ended, `40404`
     * unknown) leaves the card up and only reports the server's message. */
    fun onSubmit() {
        val current = state()
        val question = current.pendingQuestions.firstOrNull() ?: return
        if (!current.isQuestionSubmittable) return
        val answers = question.items.map { item ->
            val otherText = current.questionOtherTexts[item.id]?.takeIf { it.isNotBlank() }?.trim()
            // The server does not validate option ids (090): only emit ids the item
            // actually carries, in the item's wire order.
            val picks = item.options.map { it.id }.filter { it in current.questionSelections[item.id].orEmpty() }
            when {
                // Multi-select mapping (090): text + ticks → multi_with_other; text
                // alone → other; ticks only → multi. Single-select keeps the old
                // behavior: a non-blank text wins over the picked option.
                item.multiSelect && otherText != null && picks.isNotEmpty() ->
                    QuestionAnswer.MultiWithOther(item.id, picks, otherText)
                otherText != null -> QuestionAnswer.Other(item.id, otherText)
                item.multiSelect -> QuestionAnswer.Multi(item.id, picks)
                else -> QuestionAnswer.OptionPick(item.id, picks.first())
            }
        }
        scope().launch {
            chatRepository.answerQuestion(
                settingsRepository.current(), sessionId, question.questionId, answers,
            ).onFailure { error -> sendEffect(ChatViewModel.Effect.QuestionFailed(error.toUserMessage())) }
        }
    }

    /** POSTs `{"kind":"question","dismissed":true}` for the head question (ticket 172);
     * the card clears on the server's `outcome:"dismissed"` frame, so a failed POST
     * leaves the card up. */
    fun onDismiss() {
        val question = state().pendingQuestions.firstOrNull() ?: return
        scope().launch {
            chatRepository.dismissQuestion(
                settingsRepository.current(), sessionId, question.questionId,
            ).onFailure { error -> sendEffect(ChatViewModel.Effect.QuestionFailed(error.toUserMessage())) }
        }
    }

    /** A pending question `interaction` frame (ticket 172): queues the card, deduped by
     * question id IN PLACE — the post-ack re-send of a question the reset already
     * queued must neither double it nor move it behind newer ones (it may stamp the
     * asking agent the reset's entity lacked). */
    fun onRequested(event: SessionEvent.QuestionRequested) {
        seedGuard.bump()
        val question = event.question
        val stash = droppedHeadDrafts?.takeIf { it.questionId == question.questionId }
        if (stash != null) droppedHeadDrafts = null
        updateState {
            val queue = it.pendingQuestions
            val next = it.copy(
                pendingQuestions = if (queue.any { q -> q.questionId == question.questionId }) {
                    queue.map { q -> if (q.questionId == question.questionId) question else q }
                } else {
                    queue + question
                },
            ).prunedQuestionDrafts(it)
            // The re-send of the head an ack reset dropped: it heads the queue again
            // with the drafts it had.
            if (stash != null && next.pendingQuestions.firstOrNull()?.questionId == question.questionId) {
                val itemIds = question.items.map { item -> item.id }.toSet()
                next.copy(
                    questionSelections = stash.selections.filterKeys { id -> id in itemIds },
                    questionOtherTexts = stash.otherTexts.filterKeys { id -> id in itemIds },
                    questionStep = stash.step.coerceIn(0, maxOf(question.items.lastIndex, 0)),
                    questionCardMinimized = stash.minimized,
                )
            } else {
                next
            }
        }
    }

    /** An `ended` question frame (answered, dismissed — an abort included — or
     * cancelled): the card clears, for our own decisions too, so a failed POST leaves
     * it up. */
    fun onResolved(event: SessionEvent.QuestionResolved) {
        seedGuard.bump()
        if (droppedHeadDrafts?.questionId == event.questionId) droppedHeadDrafts = null
        updateState {
            it.copy(
                pendingQuestions = it.pendingQuestions.filterNot { q -> q.questionId == event.questionId },
            ).prunedQuestionDrafts(it)
        }
    }

    /** An accepted subscribe (ticket 172): the queue becomes the reset snapshots'
     * pending questions before the server's re-sent pending frames land — a question
     * answered elsewhere while disconnected clears. A head the reset keeps keeps its
     * drafts; one it drops stashes them for its re-send ([droppedHeadDrafts]). */
    fun onReset(event: SessionEvent.QuestionsReset) {
        seedGuard.bump()
        val current = state()
        val head = current.pendingQuestions.firstOrNull()
        droppedHeadDrafts = head?.takeIf { h -> event.pending.none { it.questionId == h.questionId } }?.let {
            HeadDrafts(
                questionId = it.questionId,
                selections = current.questionSelections,
                otherTexts = current.questionOtherTexts,
                step = current.questionStep,
                minimized = current.questionCardMinimized,
            )
        }
        updateState {
            it.copy(pendingQuestions = event.pending.distinctBy { q -> q.questionId }).prunedQuestionDrafts(it)
        }
    }

    /** Guard capture for [applyColdStart] (tickets 085/176): taken when the cold-start
     * fetch fires, so a live question event landing mid-fetch drops the stale apply. */
    fun captureEvents(): Int = seedGuard.capture()

    /**
     * Applies the cold start's pending questions (ticket 176 — the pending question
     * interactions of `GET .../transcript`) — the cold-start / resync queue rebuild,
     * guarded: a live question event newer than the fetch ([eventsAtFetch]) wins over
     * the fetched queue.
     */
    fun applyColdStart(questions: List<PendingQuestion>, eventsAtFetch: Int) {
        if (seedGuard.isStale(eventsAtFetch)) return
        updateState { it.copy(pendingQuestions = questions).prunedQuestionDrafts(it) }
    }

    /** Drops draft answers whose item id belongs to no pending question anymore, and
     * resets the wizard step (ticket 035) and the minimized flag (ticket 088) when the
     * queue head changed or shrank past it — a newly surfaced question always shows
     * expanded, never inheriting the previous head's minimized state. A head change
     * also closes the read-only resolved view (089): live input always wins the dock. */
    private fun ChatViewModel.UiState.prunedQuestionDrafts(previous: ChatViewModel.UiState): ChatViewModel.UiState {
        val itemIds = pendingQuestions.flatMap { q -> q.items.map { item -> item.id } }.toSet()
        val head = pendingQuestions.firstOrNull()
        val headChanged =
            head == null || head.questionId != previous.pendingQuestions.firstOrNull()?.questionId
        val step = if (headChanged || questionStep > head.items.lastIndex) 0 else questionStep
        return copy(
            questionSelections = questionSelections.filterKeys { it in itemIds },
            questionOtherTexts = questionOtherTexts.filterKeys { it in itemIds },
            questionStep = step,
            questionCardMinimized = if (headChanged) false else questionCardMinimized,
            resolvedQuestionView = if (headChanged) null else resolvedQuestionView,
            resolvedQuestionStep = if (headChanged) 0 else resolvedQuestionStep,
        )
    }
}
