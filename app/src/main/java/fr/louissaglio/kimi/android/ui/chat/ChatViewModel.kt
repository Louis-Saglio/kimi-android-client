package fr.louissaglio.kimi.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.PendingQuestion
import fr.louissaglio.kimi.android.data.chat.QuestionAnswer
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.SocketEvent
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    /** One pending permission request, surfaced as the approval card above the input row. */
    data class PendingApproval(
        val approvalId: String,
        val toolName: String,
        val summary: String,
    )

    /** [items] is empty until the first load; [error] is shown only when there is nothing to display. */
    data class UiState(
        val isLoading: Boolean = true,
        val items: List<ChatItem> = emptyList(),
        val error: String? = null,
        val isWorking: Boolean = false,
        val isReconnecting: Boolean = false,
        val inputText: String = "",
        val isSending: Boolean = false,
        /** Approval queue in arrival order; the card shows the head and the size. */
        val pendingApprovals: List<PendingApproval> = emptyList(),
        /** Question queue in arrival order; the card shows the head and the size. */
        val pendingQuestions: List<PendingQuestion> = emptyList(),
        /** Draft answers for the head question: item id → picked option id. */
        val questionSelections: Map<String, String> = emptyMap(),
        /** Draft free-text answers for the head question: item id → typed text. */
        val questionOtherTexts: Map<String, String> = emptyMap(),
    ) {
        /** True when every item of the head question has an answer (option picked or other text). */
        val isQuestionSubmittable: Boolean
            get() = pendingQuestions.firstOrNull()?.items?.all { item ->
                questionOtherTexts[item.id]?.isNotBlank() == true || questionSelections.containsKey(item.id)
            } == true
    }

    sealed interface Effect {
        data class RefreshFailed(val message: String) : Effect

        /** The typed text stays in [UiState.inputText]; the snackbar's Retry re-calls [onSend]. */
        data class SendFailed(val message: String) : Effect

        /** The turn is still running; the Stop action stays available. */
        data class AbortFailed(val message: String) : Effect

        /** The card stays up: with no resolved event coming, the request is still pending. */
        data class ApprovalFailed(val message: String) : Effect

        /** Same contract as approvals: no resolved event, the question card stays up. */
        data class QuestionFailed(val message: String) : Effect
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val timeline = ChatTimeline()
    private var socketJob: Job? = null
    private var historyJob: Job? = null

    /** Wired to ON_START: (re)loads history and opens the socket. Also fires when the
     * app returns to the foreground, which is the re-sync path after an OS-level drop. */
    fun onScreenStarted() {
        refreshHistory(clearLive = false)
        refreshPendingQuestions()
        seedWorkingState()
        startSocket()
    }

    /** Wired to ON_STOP: the socket is only held while the screen is visible. */
    fun onScreenStopped() {
        socketJob?.cancel()
    }

    fun onRetry() {
        _uiState.update { it.copy(isLoading = it.items.isEmpty(), error = null) }
        onScreenStarted()
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(inputText = value) }
    }

    /** POSTs the current input: a new prompt while the session is idle, a steer into the
     * running turn while it is busy (docs/server-api.md, Steer). No-ops on blank text or
     * while a send is in flight. On failure the text stays in the field and
     * [Effect.SendFailed] drives a snackbar with Retry. */
    fun onSend() {
        val state = _uiState.value
        val text = state.inputText.trim()
        if (text.isEmpty() || state.isSending) return
        _uiState.update { it.copy(isSending = true) }
        viewModelScope.launch {
            val settings = settingsRepository.current()
            val result = if (state.isWorking) {
                chatRepository.steer(settings, sessionId, text)
            } else {
                chatRepository.sendPrompt(settings, sessionId, text)
            }
            result
                .onSuccess {
                    // No local echo: the user message arrives inline in the socket's
                    // context.spliced frame (0.27.0; prompt.submitted on older servers),
                    // the reply via the streaming events.
                    _uiState.update { it.copy(inputText = "", isSending = false, isWorking = true) }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isSending = false) }
                    _effects.send(Effect.SendFailed(error.toUserMessage()))
                }
        }
    }

    /** POSTs `:abort` for the running turn. On success nothing is cleared locally: the
     * server's turn.ended frame (docs/server-api.md) drives isWorking=false and the
     * history refetch that commits the partial reply, so a dropped socket just retries. */
    fun onStop() {
        if (!_uiState.value.isWorking) return
        viewModelScope.launch {
            chatRepository.abortPrompt(settingsRepository.current(), sessionId)
                .onFailure { error -> _effects.send(Effect.AbortFailed(error.toUserMessage())) }
        }
    }

    fun onAllow() = postDecision(approved = true)

    fun onDeny() = postDecision(approved = false)

    /** Decides the head of the queue (arrival order). The card clears on the server's
     * approval.resolved event — for our own decisions too — so a failed POST leaves the
     * card up and only reports the error. */
    private fun postDecision(approved: Boolean) {
        val approval = _uiState.value.pendingApprovals.firstOrNull() ?: return
        viewModelScope.launch {
            chatRepository.postApprovalDecision(
                settingsRepository.current(), sessionId, approval.approvalId, approved,
            ).onFailure { error -> _effects.send(Effect.ApprovalFailed(error.toUserMessage())) }
        }
    }

    /** Picks (or re-picks) the option answering one item of the head question. */
    fun onQuestionOptionPicked(itemId: String, optionId: String) {
        _uiState.update { it.copy(questionSelections = it.questionSelections + (itemId to optionId)) }
    }

    /** Free-text answer for one item (only rendered when the item allows it). A non-blank
     * text wins over a picked option for that item. */
    fun onQuestionOtherTextChange(itemId: String, text: String) {
        _uiState.update { it.copy(questionOtherTexts = it.questionOtherTexts + (itemId to text)) }
    }

    /** POSTs the drafted answers for the head question. No-ops while an item is
     * unanswered. The card clears on the server's question.answered event — for our own
     * answers too — so a failed POST leaves the card up and only reports the error. */
    fun onSubmitQuestion() {
        val state = _uiState.value
        val question = state.pendingQuestions.firstOrNull() ?: return
        if (!state.isQuestionSubmittable) return
        val answers = question.items.map { item ->
            state.questionOtherTexts[item.id]?.takeIf { it.isNotBlank() }
                ?.let { QuestionAnswer.Other(item.id, it.trim()) }
                ?: QuestionAnswer.OptionPick(item.id, state.questionSelections.getValue(item.id))
        }
        viewModelScope.launch {
            chatRepository.answerQuestion(
                settingsRepository.current(), sessionId, question.questionId, answers,
            ).onFailure { error -> _effects.send(Effect.QuestionFailed(error.toUserMessage())) }
        }
    }

    /** POSTs `:dismiss` for the head question; the card clears on the server's
     * question.dismissed event, so a failed POST leaves the card up. */
    fun onDismissQuestion() {
        val question = _uiState.value.pendingQuestions.firstOrNull() ?: return
        viewModelScope.launch {
            chatRepository.dismissQuestion(
                settingsRepository.current(), sessionId, question.questionId,
            ).onFailure { error -> _effects.send(Effect.QuestionFailed(error.toUserMessage())) }
        }
    }

    private fun startSocket() {
        socketJob?.cancel()
        socketJob = viewModelScope.launch {
            try {
                chatRepository.observeSession(settingsRepository.current(), sessionId)
                    .collect { event ->
                        when (event) {
                            SocketEvent.Connected ->
                                _uiState.update { it.copy(isReconnecting = false) }
                            SocketEvent.Reconnected -> {
                                _uiState.update { it.copy(isReconnecting = false) }
                                // The cursor resume replays missed events; the refetch
                                // heals anything beyond the server's replay buffer.
                                refreshHistory(clearLive = false)
                                refreshPendingQuestions()
                            }
                            SocketEvent.Disconnected ->
                                _uiState.update { it.copy(isReconnecting = true) }
                            is SocketEvent.Event -> applySessionEvent(event.event)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DataError.InvalidUrl) {
                // Surfaced by the history load, which validates the URL first.
            }
        }
    }

    private fun applySessionEvent(event: SessionEvent) {
        when (event) {
            SessionEvent.TurnStarted -> {
                timeline.apply(event)
                _uiState.update { it.copy(isWorking = true, items = timeline.items()) }
            }
            is SessionEvent.TurnEnded -> {
                // A turn that ends takes its pending approvals with it (e.g. abort) — but
                // not its questions: a pending question survives turn.ended and is
                // re-derived from the questions endpoint instead of being cleared here.
                _uiState.update { it.copy(isWorking = false, pendingApprovals = emptyList()) }
                // Replace live rows with the canonical committed turn.
                refreshHistory(clearLive = true)
                refreshPendingQuestions()
            }
            SessionEvent.PromptSubmitted ->
                // Pick up the new user message (the event carries no text).
                refreshHistory(clearLive = false)
            is SessionEvent.ApprovalRequested ->
                _uiState.update {
                    // Deduped: a reconnect replay must not queue the same request twice.
                    it.copy(
                        pendingApprovals = it.pendingApprovals
                            .filterNot { a -> a.approvalId == event.approvalId } +
                            PendingApproval(event.approvalId, event.toolName, event.summary),
                    )
                }
            is SessionEvent.ApprovalResolved ->
                _uiState.update {
                    it.copy(pendingApprovals = it.pendingApprovals.filterNot { a -> a.approvalId == event.approvalId })
                }
            is SessionEvent.QuestionRequested ->
                _uiState.update {
                    // Deduped: a reconnect replay must not queue the same request twice.
                    it.copy(
                        pendingQuestions = it.pendingQuestions
                            .filterNot { q -> q.questionId == event.question.questionId } +
                            event.question,
                    ).prunedQuestionDrafts()
                }
            is SessionEvent.QuestionResolved ->
                _uiState.update {
                    it.copy(
                        pendingQuestions = it.pendingQuestions.filterNot { q -> q.questionId == event.questionId },
                    ).prunedQuestionDrafts()
                }
            else -> {
                timeline.apply(event)
                _uiState.update { it.copy(items = timeline.items()) }
            }
        }
    }

    private fun refreshHistory(clearLive: Boolean) {
        // Latest fetch wins: a stale response must not overwrite a newer one.
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            chatRepository.getMessages(settingsRepository.current(), sessionId)
                .onSuccess { messages ->
                    timeline.replaceHistory(messages, keepLive = !clearLive)
                    _uiState.update { it.copy(isLoading = false, items = timeline.items(), error = null) }
                }
                .onFailure { error ->
                    if (_uiState.value.items.isEmpty()) {
                        _uiState.update { it.copy(isLoading = false, error = error.toUserMessage()) }
                    } else {
                        _effects.send(Effect.RefreshFailed(error.toUserMessage()))
                    }
                }
        }
    }

    /**
     * Re-derives the pending questions from the questions endpoint (the documented
     * source of truth, docs/server-api.md): runs on screen start, reconnect, and
     * turn.ended so the card survives history refetches and heals after an abort.
     * A failure leaves the current state untouched.
     */
    private fun refreshPendingQuestions() {
        viewModelScope.launch {
            chatRepository.getPendingQuestions(settingsRepository.current(), sessionId)
                .onSuccess { questions ->
                    _uiState.update { it.copy(pendingQuestions = questions).prunedQuestionDrafts() }
                }
        }
    }

    /** Drops draft answers whose item id belongs to no pending question anymore. */
    private fun UiState.prunedQuestionDrafts(): UiState {
        val itemIds = pendingQuestions.flatMap { q -> q.items.map { item -> item.id } }.toSet()
        return copy(
            questionSelections = questionSelections.filterKeys { it in itemIds },
            questionOtherTexts = questionOtherTexts.filterKeys { it in itemIds },
        )
    }

    /** Seeds the working indicator when opening a session that is already mid-turn
     * (turn events only fire on transitions). Failure just leaves the indicator off. */
    private fun seedWorkingState() {
        viewModelScope.launch {
            chatRepository.isSessionRunning(settingsRepository.current(), sessionId)
                .onSuccess { running -> _uiState.update { it.copy(isWorking = running) } }
        }
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is DataError.InvalidUrl -> "Invalid server URL — check Settings"
        is DataError.Auth -> "Authentication failed — check Settings"
        is DataError.Network -> "Server unreachable"
        is DataError.Http -> "Unexpected server response (HTTP $code)"
        is DataError.Api -> serverMessage.ifBlank { "Server error $code" }
        is DataError.MalformedResponse -> "Malformed server response"
        else -> message ?: "Unknown error"
    }
}
