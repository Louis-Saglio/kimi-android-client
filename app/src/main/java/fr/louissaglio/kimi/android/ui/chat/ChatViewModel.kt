package fr.louissaglio.kimi.android.ui.chat

import android.net.Uri
import android.util.Base64
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.attachments.AttachmentStore
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.ContextUsage
import fr.louissaglio.kimi.android.data.chat.FileMeta
import fr.louissaglio.kimi.android.data.chat.PendingQuestion
import fr.louissaglio.kimi.android.data.chat.QuestionAnswer
import fr.louissaglio.kimi.android.data.chat.ServerModel
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.SocketEvent
import fr.louissaglio.kimi.android.data.chat.Todo
import fr.louissaglio.kimi.android.data.chat.TodoParser
import fr.louissaglio.kimi.android.data.chat.isSubagentScoped
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.sessions.displayName
import fr.louissaglio.kimi.android.data.sessions.displayNameFor
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.di.StreamCoalesceWindowMillis
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val sessionsRepository: SessionsRepository,
    private val settingsRepository: SettingsRepository,
    private val attachmentStore: AttachmentStore,
    private val subagentStore: SubagentStreamStore,
    savedStateHandle: SavedStateHandle,
    // Default mirrors the DI Json for direct construction in tests.
    private val todoParser: TodoParser = TodoParser(Json { ignoreUnknownKeys = true }),
    // Default mirrors the DI value (AppModule) for direct construction in tests;
    // pass 0 to publish every delta immediately (the pre-conflation behavior).
    @StreamCoalesceWindowMillis
    private val streamCoalesceWindowMillis: Long = 100L,
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
     * Upload bytes of one staged attachment (ticket 033). A plain holder, NOT a data
     * class, on purpose: [PendingAttachment] sits in UiState, and a raw ByteArray field
     * would drag referential array equality into the state's data-class equals (and
     * megabyte content compares into every StateFlow emission). Identity on this holder
     * is the cheap, correct semantics — staged bytes never mutate; chip updates go
     * through `copy(status = …)`, which keeps the same holder reference.
     */
    class AttachmentBytes(val value: ByteArray)

    /**
     * One attachment staged in the composer (ticket 033). [bytes] are read eagerly at
     * pick time (size-capped, see [AttachmentStore.MAX_PICK_BYTES]) so the chip can
     * render a thumbnail and the send path uploads them unchanged. [status] reflects
     * the upload driven by [onSend]: Ready → Uploading → (Failed keeps the chip and
     * blocks the send; the snackbar's Retry re-sends).
     */
    data class PendingAttachment(
        val localId: String,
        val name: String,
        val sizeBytes: Long,
        val mimeType: String,
        val bytes: AttachmentBytes,
        val status: Status = Status.Ready,
    ) {
        enum class Status { Ready, Uploading, Failed }

        val isImage: Boolean get() = mimeType.startsWith("image/")
    }

    /** [items] is null until the first load lands; [error] is shown only when there is nothing to display. */
    data class UiState(
        /** The session this screen shows; [sessionLabel] falls back to it until the title loads. */
        val sessionId: String,
        val items: List<ChatItem>? = null,
        val error: String? = null,
        /** Top-bar label source (title → cwd basename); null until loaded, see [sessionLabel]. */
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
        /** The agent's latest todo list, mirrored from its TodoList tool calls
         * (ticket 042); null or empty hides the widget above the composer. */
        val todos: List<Todo>? = null,
        /** True while older history exists (scroll-to-top loads it, ticket 043). */
        val hasMoreHistory: Boolean = false,
        /** True while an older history page loads; the list shows a header spinner. */
        val isLoadingMoreHistory: Boolean = false,
    ) {
        /** Top-bar label: the loaded title (or cwd basename), falling back to the raw
         * session id — the single source of truth for the fallback, so the screen no
         * longer needs the nav argument for it. */
        val sessionLabel: String
            get() = sessionTitle ?: sessionId

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

        /** A picked file was not staged: unreadable, or over [AttachmentStore.MAX_PICK_BYTES]. */
        data class AttachmentPickFailed(val message: String) : Effect

        /** A history attachment's bytes are downloaded and persisted (ticket 034); the
         * screen hands [contentUri] to another app via ACTION_VIEW and reports a missing
         * viewer back through [onOpenAttachmentFailed]. [mimeType] is the server's
         * response Content-Type — more reliable than the notice-parsed attachment MIME. */
        data class OpenFile(val contentUri: Uri, val mimeType: String, val name: String) : Effect

        /** The download or cache-write of a history attachment failed, or no app could open it. */
        data class OpenAttachmentFailed(val message: String) : Effect
    }

    private val _uiState = MutableStateFlow(
        UiState(
            sessionId = sessionId,
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

    private val timeline = ChatTimeline(todoParser)
    private var historyJob: Job? = null

    /** In-flight guard of the older-page load (ticket 010's pattern): the Job, not a
     * state flag. */
    private var olderHistoryJob: Job? = null

    /** The `before_id` cursor of the next older page (ticket 043): the oldest loaded
     * history message's id, tracked ViewModel-side like ticket 010 — the timeline rows
     * can't provide it (tool rows carry tool-call ids, and a message can flatten to no
     * row). Reset by every newest-page refetch. */
    private var oldestHistoryId: String? = null

    /** Screen visibility as state — one input of the socket gate (see init). */
    private val screenVisible = MutableStateFlow(false)

    init {
        // The socket gate (ticket 045): the session socket is collected while the chat
        // is visible OR a sub-agent page of this session is open. The chat's collection
        // is the store's only feeder, so it must outlive navigation to a page — a
        // second, page-owned socket would double-feed the store on every cursor replay.
        // Gate flips cancel/restart the collection (collectLatest), matching the old
        // explicit start/cancel for the chat-only case.
        viewModelScope.launch {
            combine(screenVisible, subagentStore.openPageCount(sessionId)) { visible, openPages ->
                visible || openPages > 0
            }.distinctUntilChanged()
                .collectLatest { holdSocket ->
                    if (holdSocket) collectSessionSocket()
                }
        }
    }

    /** Bumped on every turn.started/turn.ended event, so [seedSessionStatus] can drop a
     * response that a newer turn event has already overtaken. Only touched on Main. */
    private var turnEventCount = 0

    /** Bumped on every question.requested/question.resolved event, so [seedPendingQuestions]
     * can drop a response that a newer question event has already overtaken. */
    private var questionEventCount = 0

    /** Bumped on every live context-usage event, so [seedSessionStatus] can drop a
     * /status snapshot a newer agent.status.updated frame has already overtaken. */
    private var contextUsageEventCount = 0

    // --- Streaming-delta conflation (see collectSessionSocket) -----------------------
    // All three are confined to Main: the socket collect, the flush loop, and
    // onScreenStopped all run on viewModelScope's dispatcher (or are called from it).

    /** Deltas received but not yet published; null = no burst open. Holds only
     * [SessionEvent.AssistantDelta]/[SessionEvent.ThinkingDelta], consecutive
     * same-kind entries merged into one. */
    private var pendingDeltas: MutableList<SessionEvent>? = null

    /** The trailing-flush timer of an open burst; null when no burst is open. */
    private var deltaFlushJob: Job? = null

    /** Wired to ON_START: (re)loads history; the socket gate opens the socket. Also
     * fires when the app returns to the foreground — the re-sync path after an OS-level
     * drop. */
    fun onScreenStarted() {
        screenVisible.value = true
        refreshHistory(clearLive = false)
        seedPendingQuestions()
        refreshSessionInfo()
        seedSessionStatus()
        loadModels()
    }

    /** Wired to ON_STOP: the gate closes the socket unless a sub-agent page of this
     * session is open. Coalesced deltas flush first, so no received text is ever held
     * back (or lost with the timer). */
    fun onScreenStopped() {
        flushPendingDeltas()
        screenVisible.value = false
    }

    /** The session is being left for good (back to the session list): its sub-agent
     * streams go with it. */
    override fun onCleared() {
        subagentStore.release(sessionId)
    }

    fun onRetry() {
        _uiState.update { it.copy(error = null) }
        onScreenStarted()
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(inputText = value) }
    }

    /** Stages picked files as composer chips (ticket 033): the store resolves each
     * content Uri (size-capped, see [AttachmentStore.MAX_PICK_BYTES]); files that fail
     * the read surface one [Effect.AttachmentPickFailed] each and are not staged. */
    fun onAttachmentsPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            for (uri in uris) {
                onAttachmentRead(withContext(Dispatchers.IO) { attachmentStore.read(uri) })
            }
        }
    }

    /** Handles one store read: stages the chip or surfaces why nothing was staged.
     * Internal because unit tests have no android.net.Uri on the JVM — they drive this
     * directly instead of going through [onAttachmentsPicked]. */
    internal suspend fun onAttachmentRead(result: AttachmentStore.ReadResult) {
        when (result) {
            is AttachmentStore.ReadResult.Ok -> {
                val picked = result.picked
                val attachment = PendingAttachment(
                    localId = UUID.randomUUID().toString(),
                    name = picked.name,
                    sizeBytes = picked.sizeBytes,
                    mimeType = picked.mimeType,
                    bytes = AttachmentBytes(picked.bytes),
                )
                _uiState.update { it.copy(attachments = it.attachments + attachment) }
            }
            is AttachmentStore.ReadResult.TooLarge ->
                _effects.send(Effect.AttachmentPickFailed("${result.name} exceeds the 50 MiB upload limit"))
            is AttachmentStore.ReadResult.Unreadable ->
                _effects.send(Effect.AttachmentPickFailed("Could not read ${result.name}"))
        }
    }

    /** Removes a staged chip before the send; the removed file is never uploaded. */
    fun onAttachmentRemoved(localId: String) {
        _uiState.update { state ->
            state.copy(attachments = state.attachments.filterNot { it.localId == localId })
        }
    }

    /** Bytes of a history image (ticket 034): decoded from the inline base64, or fetched
     * via `GET /api/v1/files/{id}` for a file-referenced source. Pull-based display data
     * for the thumbnail/full-screen viewer, hence a call rather than state or an effect. */
    suspend fun imageBytes(image: ChatMessage.Part.Image): ByteArray? =
        image.base64Data?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
            ?: image.fileId?.let { fileId ->
                chatRepository.downloadFile(settingsRepository.current(), fileId).getOrNull()?.bytes
            }

    /** Downloads a history attachment's bytes, persists them via the store, and emits
     * [Effect.OpenFile] for the screen to hand to another app (ticket 034). */
    fun onOpenAttachment(attachment: ChatMessage.Part.Attachment) {
        val fileId = attachment.fileId ?: return
        viewModelScope.launch {
            val download = chatRepository.downloadFile(settingsRepository.current(), fileId).getOrNull()
            if (download == null) {
                _effects.send(Effect.OpenAttachmentFailed("Could not download ${attachment.name}"))
                return@launch
            }
            val uri = withContext(Dispatchers.IO) { attachmentStore.writeToCache(attachment.name, download.bytes) }
            if (uri == null) {
                _effects.send(Effect.OpenAttachmentFailed("No app can open ${attachment.name}"))
            } else {
                _effects.send(Effect.OpenFile(uri, download.mediaType, attachment.name))
            }
        }
    }

    /** The screen found no viewer for an [Effect.OpenFile] (ActivityNotFoundException). */
    fun onOpenAttachmentFailed(name: String) {
        viewModelScope.launch { _effects.send(Effect.OpenAttachmentFailed("No app can open $name")) }
    }

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
                chatRepository.uploadFile(settings, attachment.name, attachment.mimeType, attachment.bytes.value)
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

    /**
     * Collects the session socket (one run per socket-gate opening; cancelled when the
     * gate closes). Streaming deltas (assistant/thinking) are conflated
     * here, at the ViewModel layer, so the UiState — and with it MarkdownBody, which
     * re-parses the whole streaming bubble per emission — updates at most once per
     * [streamCoalesceWindowMillis] during a burst instead of once per socket frame.
     *
     * Semantics (window > 0):
     * - The FIRST delta of a burst applies immediately: the bubble starts rendering
     *   without waiting out a window.
     * - Later deltas accumulate in [pendingDeltas] (consecutive same-kind deltas merge
     *   into one append) and publish once per window from [runDeltaFlushLoop]; a full
     *   quiet window closes the burst, re-arming the leading edge.
     * - ANY other socket event — turn/approval/question/tool/status frames and the
     *   connection lifecycle alike — flushes the pending text first, so no substantive
     *   event is ever delayed behind coalesced text and the pre-completion state is
     *   exact. No delta is lost and order is preserved: a flush replays the buffered
     *   events through the timeline in arrival order.
     *
     * With window = 0 every delta publishes on arrival (the pre-conflation behavior).
     */
    private suspend fun collectSessionSocket() {
        chatRepository.observeSession(settingsRepository.current(), sessionId)
            .collect { event ->
                val sessionEvent = (event as? SocketEvent.Event)?.event
                when {
                    // Sub-agent frames — transcript frames tagged with their own
                    // agentId, and subagent.* lifecycle — route to the store
                    // (ticket 045): they must never touch the main timeline, the
                    // working state, or an open main delta burst (ticket 044).
                    sessionEvent != null && sessionEvent.isSubagentScoped() ->
                        onSubagentEvent(sessionEvent)
                    sessionEvent != null && sessionEvent.isStreamDelta() -> {
                        onStreamDelta(sessionEvent)
                    }
                    else -> {
                        flushPendingDeltas()
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
                            // No-op: the history load validates the URL first and owns the
                            // user-facing error.
                            is SocketEvent.Failed -> Unit
                            is SocketEvent.Event -> applySessionEvent(event.event)
                        }
                    }
                }
            }
    }

    /** Routes one sub-agent-scoped event into the store; a spawn additionally links the
     * spawning `Agent` tool row to its sub-agent's page (ticket 045). */
    private fun onSubagentEvent(event: SessionEvent) {
        if (event is SessionEvent.SubagentSpawned) {
            timeline.linkSubagentTool(event.parentToolCallId, event.subagentId)
            _uiState.update { it.copy(items = timeline.items()) }
        }
        subagentStore.apply(sessionId, event)
    }

    /** Seeds store entries from linked `Agent` rows (cold-open, ticket 045): the row's
     * args/result carry the identity, lifecycle status and result summary. Idempotent
     * (the store never overwrites an existing entry) and cheap — linked rows are few. */
    private fun seedSubagentStoreFromLinkedRows() {
        timeline.items()
            .flatMap { (it as? ChatItem.ToolGroup)?.tools.orEmpty() }
            .filter { it.subagentId != null }
            .forEach { subagentStore.seedFromAgentRow(sessionId, it) }
    }

    private fun SessionEvent.isStreamDelta(): Boolean =
        this is SessionEvent.AssistantDelta || this is SessionEvent.ThinkingDelta

    /** One delta applied+published on its own — the leading edge, and every delta
     * when the window is 0. */
    private fun applyTimelineEvent(event: SessionEvent) {
        timeline.apply(event)
        _uiState.update { it.copy(items = timeline.items(), todos = timeline.todos()) }
    }

    private fun onStreamDelta(event: SessionEvent) {
        if (streamCoalesceWindowMillis <= 0L) {
            applyTimelineEvent(event)
            return
        }
        val pending = pendingDeltas
        if (pending == null) {
            // Leading edge of a burst: publish immediately, then coalesce until the
            // stream pauses for a full window.
            applyTimelineEvent(event)
            pendingDeltas = mutableListOf()
            deltaFlushJob = viewModelScope.launch { runDeltaFlushLoop() }
        } else {
            pending.mergeDelta(event)
        }
    }

    /** Trailing flush of an open burst: publishes the accumulated deltas once per
     * window; a window with nothing new closes the burst so the next delta renders
     * on its leading edge again. */
    private suspend fun runDeltaFlushLoop() {
        while (true) {
            delay(streamCoalesceWindowMillis)
            val pending = pendingDeltas ?: return
            if (pending.isEmpty()) {
                pendingDeltas = null
                deltaFlushJob = null
                return
            }
            pending.forEach(timeline::apply)
            pending.clear()
            _uiState.update { it.copy(items = timeline.items(), todos = timeline.todos()) }
        }
    }

    /** Publishes any coalesced deltas NOW, in arrival order, and closes the burst.
     * Runs before every non-delta socket event and on ON_STOP; no-op outside a burst. */
    private fun flushPendingDeltas() {
        deltaFlushJob?.cancel()
        deltaFlushJob = null
        val pending = pendingDeltas ?: return
        pendingDeltas = null
        if (pending.isEmpty()) return
        pending.forEach(timeline::apply)
        _uiState.update { it.copy(items = timeline.items(), todos = timeline.todos()) }
    }

    /** Consecutive same-kind deltas merge into one, so a flush costs one append per
     * text stream. Assistant and thinking deltas never merge across kinds; each kind
     * appends to its own bubble field, so keeping them as separate list entries
     * preserves the observable arrival order. */
    private fun MutableList<SessionEvent>.mergeDelta(event: SessionEvent) {
        val last = lastOrNull()
        when {
            last is SessionEvent.AssistantDelta && event is SessionEvent.AssistantDelta ->
                set(lastIndex, last.copy(delta = last.delta + event.delta))
            last is SessionEvent.ThinkingDelta && event is SessionEvent.ThinkingDelta ->
                set(lastIndex, last.copy(delta = last.delta + event.delta))
            else -> add(event)
        }
    }

    private fun applySessionEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.TurnStarted -> {
                turnEventCount++
                timeline.apply(event)
                _uiState.update { it.copy(isWorking = true, items = timeline.items(), todos = timeline.todos()) }
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
            is SessionEvent.ToolCallResult -> {
                applyTimelineEvent(event)
                // A background Agent result carries the agent_id marker mid-run: seed
                // right away so the page can open and later frames stop dropping.
                seedSubagentStoreFromLinkedRows()
            }
            else -> applyTimelineEvent(event)
        }
    }

    private fun refreshHistory(clearLive: Boolean) {
        // Latest fetch wins: a stale response must not overwrite a newer one.
        historyJob?.cancel()
        // A full refetch resets history to the newest page (ticket 043): an in-flight
        // older-page load must not prepend a stale page — contiguous with the OLD
        // committed list, not the fresh one — onto the replacement (010's
        // reload-cancels-loadMore trap, backwards).
        olderHistoryJob?.cancel()
        _uiState.update { it.copy(isLoadingMoreHistory = false) }
        historyJob = viewModelScope.launch {
            chatRepository.getMessages(settingsRepository.current(), sessionId)
                .onSuccess { page ->
                    oldestHistoryId = page.items.firstOrNull()?.id
                    timeline.replaceHistory(page.items, keepLive = !clearLive)
                    _uiState.update {
                        it.copy(
                            items = timeline.items(),
                            todos = timeline.todos(),
                            error = null,
                            hasMoreHistory = page.hasMore,
                        )
                    }
                    // History is where cold links come from (the app subscribed after
                    // the spawn): seed the store from the linked Agent rows.
                    seedSubagentStoreFromLinkedRows()
                }
                .onFailure { error ->
                    if (_uiState.value.items.isNullOrEmpty()) {
                        _uiState.update { it.copy(error = error.toUserMessage()) }
                    } else {
                        _effects.send(Effect.RefreshFailed(error.toUserMessage()))
                    }
                }
        }
    }

    /**
     * Scroll-to-top trigger (ticket 043): no-op while any history load is in flight or
     * once the server reported `has_more: false`; otherwise fetches the next older page
     * (`before_id` = oldest loaded message) and PREPENDS it, deduplicated by message id
     * in the timeline. The newest-page guard ([historyJob]) is the same trap as the
     * in-flight guard: a page fetched against the stale cursor would land on a refetch's
     * fresh page with a hole in between. A failure keeps the loaded history visible and
     * retries on the next trigger.
     */
    fun onHistoryTopReached() {
        if (olderHistoryJob?.isActive == true) return
        if (historyJob?.isActive == true) return
        if (!_uiState.value.hasMoreHistory) return
        val beforeId = oldestHistoryId ?: return
        olderHistoryJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMoreHistory = true) }
            chatRepository.getMessages(settingsRepository.current(), sessionId, beforeId = beforeId)
                .onSuccess { page ->
                    oldestHistoryId = page.items.firstOrNull()?.id ?: oldestHistoryId
                    timeline.prependHistory(page.items)
                    _uiState.update {
                        it.copy(
                            items = timeline.items(),
                            hasMoreHistory = page.hasMore,
                            isLoadingMoreHistory = false,
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isLoadingMoreHistory = false) }
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
}
