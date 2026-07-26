package fr.louissaglio.kimi.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.ContextUsage
import fr.louissaglio.kimi.android.data.chat.FileDownload
import fr.louissaglio.kimi.android.data.chat.FileMeta
import fr.louissaglio.kimi.android.data.chat.PendingQuestion
import fr.louissaglio.kimi.android.data.chat.QuestionAnswer
import fr.louissaglio.kimi.android.data.chat.ServerModel
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.SocketEvent
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.sessions.displayName
import fr.louissaglio.kimi.android.data.sessions.displayNameFor
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import java.util.UUID
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
    private val sessionsRepository: SessionsRepository,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    // Optional navigation arguments from the session list: they let the top bar show
    // the right label immediately, before [refreshSessionInfo] confirms it.
    private val seedTitle: String? = savedStateHandle["title"]
    private val seedCwd: String? = savedStateHandle["cwd"]

    /** One pending permission request, surfaced as the approval card above the input row. */
    data class PendingApproval(
        val approvalId: String,
        val toolName: String,
        val summary: String,
    )

    /**
     * One attachment staged in the composer (ticket 033). [bytes] are read eagerly at
     * pick time so the chip can render a thumbnail and the send path uploads them
     * unchanged. [status] reflects the upload driven by [onSend]: Ready → Uploading →
     * (Failed keeps the chip and blocks the send; the snackbar's Retry re-sends).
     */
    data class PendingAttachment(
        val localId: String,
        val name: String,
        val sizeBytes: Long,
        val mimeType: String,
        val bytes: ByteArray,
        val status: Status = Status.Ready,
    ) {
        enum class Status { Ready, Uploading, Failed }

        val isImage: Boolean get() = mimeType.startsWith("image/")
    }

    /** [items] is empty until the first load; [error] is shown only when there is nothing to display. */
    data class UiState(
        val isLoading: Boolean = true,
        val items: List<ChatItem> = emptyList(),
        val error: String? = null,
        /** Top-bar label (title → cwd basename); null until loaded, the UI falls back to the id. */
        val sessionTitle: String? = null,
        /** Top-bar subtitle; null when the server sent no cwd. */
        val sessionCwd: String? = null,
        val isWorking: Boolean = false,
        val isReconnecting: Boolean = false,
        val inputText: String = "",
        val isSending: Boolean = false,
        /** Attachments staged above the input, in pick order (ticket 033). */
        val attachments: List<PendingAttachment> = emptyList(),
        /** Approval queue in arrival order; the card shows the head and the size. */
        val pendingApprovals: List<PendingApproval> = emptyList(),
        /** Question queue in arrival order; the card shows the head and the size. */
        val pendingQuestions: List<PendingQuestion> = emptyList(),
        /** Draft answers for the head question: item id → picked option id. */
        val questionSelections: Map<String, String> = emptyMap(),
        /** Draft free-text answers for the head question: item id → typed text. */
        val questionOtherTexts: Map<String, String> = emptyMap(),
        /** Wizard step (ticket 035): index into the head question's items shown by the card. */
        val questionStep: Int = 0,
        /** Models the server offers; empty until the fetch lands (or after it failed). */
        val availableModels: List<ServerModel> = emptyList(),
        /** True once the models fetch succeeded — false while loading and after a
         * failure, which hides the model chip entirely (ticket 031). */
        val modelsAvailable: Boolean = false,
        /** The server-wide default model id; null when unknown. */
        val serverDefaultModel: String? = null,
        /** The model chosen for this session; null = follow the server default. */
        val sessionModel: String? = null,
        /** The thinking effort chosen for this session; null = follow the server default. */
        val sessionEffort: String? = null,
        /** Context window fill for the top-bar ring (ticket 041); null until the first
         * /status response or live agent.status.updated event arrives. */
        val contextUsage: ContextUsage? = null,
    ) {
        /** True when every item of the head question has an answer (option picked or other text). */
        val isQuestionSubmittable: Boolean
            get() = pendingQuestions.firstOrNull()?.items?.all { item ->
                questionOtherTexts[item.id]?.isNotBlank() == true || questionSelections.containsKey(item.id)
            } == true

        /** True when the item at [questionStep] has an answer — gates the wizard's Next. */
        val isQuestionStepAnswered: Boolean
            get() = pendingQuestions.firstOrNull()?.items?.getOrNull(questionStep)?.let { item ->
                questionOtherTexts[item.id]?.isNotBlank() == true || questionSelections.containsKey(item.id)
            } == true

        /** Top-bar chip label: the session choice or the server default, by display name
         * when the models list knows it, else the raw id. Null hides the chip. */
        val currentModelLabel: String?
            get() {
                if (!modelsAvailable) return null
                val id = sessionModel ?: serverDefaultModel ?: return null
                return availableModels.firstOrNull { it.model == id }
                    ?.displayName?.takeIf { it.isNotBlank() } ?: id
            }

        /** The model the session effectively runs on: the session choice, else the server default. */
        val currentModel: ServerModel?
            get() = availableModels.firstOrNull { it.model == (sessionModel ?: serverDefaultModel) }

        /** Efforts the sheet may offer (ticket 032); empty hides the effort section —
         * models without `support_efforts` expose no effort UI at all. */
        val supportedEfforts: List<String>
            get() = currentModel?.supportEfforts.orEmpty()

        /** The effort shown as selected in the sheet; null when nothing is stored or the
         * stored value is incompatible with the current model (such a stale value is not
         * sent either — ChatRepository.promptThinking drops it). */
        val selectedEffort: String?
            get() = sessionEffort?.takeIf { it in supportedEfforts }

        /** The current model's server default effort, for the sheet's "Default" hint. */
        val defaultEffort: String?
            get() = currentModel?.defaultEffort
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

    private val _uiState = MutableStateFlow(
        UiState(
            sessionTitle = if (seedTitle != null || seedCwd != null) {
                displayNameFor(sessionId, seedTitle, seedCwd)
            } else {
                null
            },
            sessionCwd = seedCwd,
        ),
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val timeline = ChatTimeline()
    private var socketJob: Job? = null
    private var historyJob: Job? = null

    /** Bumped on every turn.started/turn.ended event, so [seedSessionStatus] can drop a
     * response that a newer turn event has already overtaken. Only touched on Main. */
    private var turnEventCount = 0

    /** Bumped on every question.requested/question.resolved event, so [seedPendingQuestions]
     * can drop a response that a newer question event has already overtaken. */
    private var questionEventCount = 0

    /** Bumped on every live context-usage event, so [seedSessionStatus] can drop a
     * /status snapshot a newer agent.status.updated frame has already overtaken. */
    private var contextUsageEventCount = 0

    /** Wired to ON_START: (re)loads history and opens the socket. Also fires when the
     * app returns to the foreground, which is the re-sync path after an OS-level drop. */
    fun onScreenStarted() {
        refreshHistory(clearLive = false)
        seedPendingQuestions()
        refreshSessionInfo()
        seedSessionStatus()
        loadModels()
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

    /** Stages a picked file as a composer chip (ticket 033). The screen has already
     * resolved the content Uri into name/size/mime/bytes via the ContentResolver. */
    fun onAttachmentPicked(name: String, sizeBytes: Long, mimeType: String, bytes: ByteArray) {
        val attachment = PendingAttachment(
            localId = UUID.randomUUID().toString(),
            name = name,
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            bytes = bytes,
        )
        _uiState.update { it.copy(attachments = it.attachments + attachment) }
    }

    /** Removes a staged chip before the send; the removed file is never uploaded. */
    fun onAttachmentRemoved(localId: String) {
        _uiState.update { state ->
            state.copy(attachments = state.attachments.filterNot { it.localId == localId })
        }
    }

    /** Bytes of one uploaded file (`GET /api/v1/files/{id}`) — history attachment chips
     * and image viewers fetch their content on demand (ticket 034). */
    suspend fun downloadFile(fileId: String): Result<FileDownload> =
        chatRepository.downloadFile(settingsRepository.current(), fileId)

    private fun markAttachment(localId: String, status: PendingAttachment.Status) {
        _uiState.update { state ->
            state.copy(
                attachments = state.attachments.map {
                    if (it.localId == localId) it.copy(status = status) else it
                },
            )
        }
    }

    /**
     * POSTs the current input: a new prompt while the session is idle, a steer into the
     * running turn while it is busy (docs/server-api.md, Steer). No-ops when text and
     * attachments are both empty, or while a send is in flight (which covers the whole
     * upload phase — a second tap cannot double-send). Staged attachments are uploaded
     * sequentially first (`POST /api/v1/files`); a failed upload marks its chip, posts
     * no prompt, and surfaces [Effect.SendFailed]. On failure the text stays in the
     * field and [Effect.SendFailed] drives a snackbar with Retry.
     */
    fun onSend() {
        val state = _uiState.value
        val text = state.inputText.trim()
        if ((text.isEmpty() && state.attachments.isEmpty()) || state.isSending) return
        _uiState.update { it.copy(isSending = true) }
        viewModelScope.launch {
            val settings = settingsRepository.current()
            val uploaded = mutableListOf<FileMeta>()
            for (attachment in state.attachments) {
                markAttachment(attachment.localId, PendingAttachment.Status.Uploading)
                chatRepository.uploadFile(settings, attachment.name, attachment.mimeType, attachment.bytes)
                    .onSuccess { meta ->
                        uploaded += meta
                        markAttachment(attachment.localId, PendingAttachment.Status.Ready)
                    }
                    .onFailure { error ->
                        markAttachment(attachment.localId, PendingAttachment.Status.Failed)
                        _uiState.update { it.copy(isSending = false) }
                        _effects.send(Effect.SendFailed(error.toUserMessage()))
                        return@launch
                    }
            }
            val result = if (state.isWorking) {
                chatRepository.steer(settings, sessionId, text, uploaded)
            } else {
                chatRepository.sendPrompt(settings, sessionId, text, uploaded)
            }
            result
                .onSuccess {
                    // No local echo: the user message arrives inline in the socket's
                    // context.spliced frame (0.27.0; prompt.submitted on older servers),
                    // the reply via the streaming events.
                    _uiState.update {
                        it.copy(inputText = "", attachments = emptyList(), isSending = false, isWorking = true)
                    }
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

    /** Advances the question wizard (ticket 035) to the next item. No-ops on the last
     * item or while the current item is unanswered, same gating as the Next button. */
    fun onQuestionNext() {
        _uiState.update { state ->
            val lastIndex = state.pendingQuestions.firstOrNull()?.items?.lastIndex ?: return@update state
            if (!state.isQuestionStepAnswered || state.questionStep >= lastIndex) state
            else state.copy(questionStep = state.questionStep + 1)
        }
    }

    /** Steps the question wizard back to the previous item; drafted answers are kept. */
    fun onQuestionBack() {
        _uiState.update { state ->
            if (state.questionStep > 0) state.copy(questionStep = state.questionStep - 1) else state
        }
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
                turnEventCount++
                timeline.apply(event)
                _uiState.update { it.copy(isWorking = true, items = timeline.items()) }
            }
            is SessionEvent.TurnEnded -> {
                turnEventCount++
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
            is SessionEvent.QuestionRequested -> {
                questionEventCount++
                _uiState.update {
                    // Deduped: a reconnect replay must not queue the same request twice.
                    it.copy(
                        pendingQuestions = it.pendingQuestions
                            .filterNot { q -> q.questionId == event.question.questionId } +
                            event.question,
                    ).prunedQuestionDrafts(it)
                }
            }
            is SessionEvent.QuestionResolved -> {
                questionEventCount++
                _uiState.update {
                    it.copy(
                        pendingQuestions = it.pendingQuestions.filterNot { q -> q.questionId == event.questionId },
                    ).prunedQuestionDrafts(it)
                }
            }
            is SessionEvent.ContextUsageUpdated -> {
                contextUsageEventCount++
                _uiState.update { it.copy(contextUsage = event.contextUsage) }
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
     * source of truth, docs/server-api.md): runs on reconnect and turn.ended so the
     * card survives history refetches and heals after an abort. A failure leaves the
     * current state untouched.
     */
    private fun refreshPendingQuestions() {
        viewModelScope.launch {
            chatRepository.getPendingQuestions(settingsRepository.current(), sessionId)
                .onSuccess { questions ->
                    _uiState.update { it.copy(pendingQuestions = questions).prunedQuestionDrafts(it) }
                }
        }
    }

    /**
     * Screen-start variant of [refreshPendingQuestions]: the socket streams live events
     * from connect on, so a question.requested/question.resolved landing while this poll
     * is in flight is newer than its snapshot — the late response is dropped instead of
     * clearing (or resurrecting) a card. The reconnect and turn.ended polls stay
     * unconditional: their snapshots are fresh and re-derivation is the point there.
     */
    private fun seedPendingQuestions() {
        val eventsAtSeed = questionEventCount
        viewModelScope.launch {
            chatRepository.getPendingQuestions(settingsRepository.current(), sessionId)
                .onSuccess { questions ->
                    if (questionEventCount == eventsAtSeed) {
                        _uiState.update { it.copy(pendingQuestions = questions).prunedQuestionDrafts(it) }
                    }
                }
        }
    }

    /** Drops draft answers whose item id belongs to no pending question anymore, and
     * resets the wizard step (ticket 035) when the queue head changed or shrank past it. */
    private fun UiState.prunedQuestionDrafts(previous: UiState): UiState {
        val itemIds = pendingQuestions.flatMap { q -> q.items.map { item -> item.id } }.toSet()
        val head = pendingQuestions.firstOrNull()
        val step =
            if (head == null || head.questionId != previous.pendingQuestions.firstOrNull()?.questionId ||
                questionStep > head.items.lastIndex
            ) {
                0
            } else {
                questionStep
            }
        return copy(
            questionSelections = questionSelections.filterKeys { it in itemIds },
            questionOtherTexts = questionOtherTexts.filterKeys { it in itemIds },
            questionStep = step,
        )
    }

    /**
     * Loads the model picker's data (ticket 031): the server's model list, its default,
     * and this session's stored choice. Lazy — first chat-screen open, cached in the
     * repository afterwards. A failed models fetch hides the chip and changes nothing
     * else: sending keeps the 017 behavior and is never blocked by this feature.
     */
    private fun loadModels() {
        viewModelScope.launch {
            val settings = settingsRepository.current()
            chatRepository.getModels(settings)
                .onSuccess { models ->
                    _uiState.update {
                        it.copy(
                            availableModels = models,
                            modelsAvailable = true,
                            serverDefaultModel = chatRepository.getDefaultModel(settings),
                            sessionModel = chatRepository.getSessionModel(sessionId),
                            sessionEffort = chatRepository.getSessionEffort(sessionId),
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(modelsAvailable = false) }
                }
        }
    }

    /** Persists the picked model for this session; subsequent prompts carry it. */
    fun onModelSelected(model: String) {
        _uiState.update { it.copy(sessionModel = model) }
        viewModelScope.launch { chatRepository.setSessionModel(sessionId, model) }
    }

    /** Persists the picked effort for this session; subsequent prompts carry it. */
    fun onEffortSelected(effort: String) {
        _uiState.update { it.copy(sessionEffort = effort) }
        viewModelScope.launch { chatRepository.setSessionEffort(sessionId, effort) }
    }

    /**
     * Loads the session's title/cwd for the top bar. A failure (or a missing session)
     * just leaves the fields null: the UI keeps falling back to the raw session id.
     */
    private fun refreshSessionInfo() {
        viewModelScope.launch {
            sessionsRepository.getSession(settingsRepository.current(), sessionId)
                .onSuccess { session ->
                    _uiState.update { it.copy(sessionTitle = session.displayName, sessionCwd = session.cwd) }
                }
        }
    }

    /** Seeds the working indicator when opening a session that is already mid-turn
     * (turn events only fire on transitions), and the top-bar context-usage ring
     * (ticket 041) from the same /status snapshot. Failure just leaves both untouched.
     * A turn event or a live context-usage event landing while the GET is in flight is
     * newer than the seed's snapshot, so the late response is dropped for that field
     * instead of overriding it. */
    private fun seedSessionStatus() {
        val turnEventsAtSeed = turnEventCount
        val contextEventsAtSeed = contextUsageEventCount
        viewModelScope.launch {
            chatRepository.getSessionStatus(settingsRepository.current(), sessionId)
                .onSuccess { status ->
                    _uiState.update {
                        it.copy(
                            isWorking = if (turnEventCount == turnEventsAtSeed) status.busy else it.isWorking,
                            contextUsage =
                                if (contextUsageEventCount == contextEventsAtSeed && status.contextUsage != null) {
                                    status.contextUsage
                                } else {
                                    it.contextUsage
                                },
                        )
                    }
                }
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
