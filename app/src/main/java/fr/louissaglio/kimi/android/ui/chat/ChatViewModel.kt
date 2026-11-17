package fr.louissaglio.kimi.android.ui.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.attachments.AttachmentStore
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.data.chat.AGENT_SWARM_TOOL_NAME
import fr.louissaglio.kimi.android.data.chat.AGENT_TOOL_NAME
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.ContextUsage
import fr.louissaglio.kimi.android.data.chat.EffortSwitchConfig
import fr.louissaglio.kimi.android.data.chat.MAIN_AGENT_ID
import fr.louissaglio.kimi.android.data.chat.MANAGED_KIMI_PROVIDER
import fr.louissaglio.kimi.android.data.chat.MessagePage
import fr.louissaglio.kimi.android.data.chat.PendingApprovalRequest
import fr.louissaglio.kimi.android.data.chat.PendingQuestion
import fr.louissaglio.kimi.android.data.chat.ResolvedQuestion
import fr.louissaglio.kimi.android.data.chat.ServerModel
import fr.louissaglio.kimi.android.data.chat.SessionAge
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.SocketEvent
import fr.louissaglio.kimi.android.data.chat.SwitchConfirm
import fr.louissaglio.kimi.android.data.chat.Todo
import fr.louissaglio.kimi.android.data.chat.TranscriptFrame
import fr.louissaglio.kimi.android.data.chat.isSubagentScoped
import fr.louissaglio.kimi.android.data.sessions.Session
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.sessions.UnreadSessionsStore
import fr.louissaglio.kimi.android.data.sessions.displayName
import fr.louissaglio.kimi.android.data.sessions.displayNameFor
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.di.DefaultDispatcher
import fr.louissaglio.kimi.android.di.StreamCoalesceWindowMillis
import fr.louissaglio.kimi.android.navigation.Chat
import fr.louissaglio.kimi.android.ui.common.formatTokenCount
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Cross-process occupancy errors (2.3.0, docs/server-api.md — tickets 168/169). */
private const val SESSION_OCCUPIED_CODE = 40931
private const val SESSION_STALE_CODE = 40932

@HiltViewModel
class ChatViewModel internal constructor(
    private val chatRepository: ChatRepository,
    private val sessionsRepository: SessionsRepository,
    private val settingsRepository: SettingsRepository,
    private val attachmentStore: AttachmentStore,
    private val subagentStore: SubagentStreamStore,
    private val coldStart: TranscriptColdStart,
    private val routeArgs: Chat,
    private val unreadStore: UnreadSessionsStore,
    // Default mirrors the DI value (AppModule) for direct construction in tests;
    // pass 0 to publish every delta immediately (the pre-conflation behavior).
    @StreamCoalesceWindowMillis
    private val streamCoalesceWindowMillis: Long = 100L,
    // Default mirrors the DI value, like the window above.
    @DefaultDispatcher
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // A fresh cache per directly constructed (test) ViewModel; Hilt shares the singleton.
    private val imageBytesCache: ImageBytesCache = ImageBytesCache(),
) : ViewModel() {

    // Production entry point (Hilt): the destination's SavedStateHandle is decoded
    // from the typed route (ticket 083). Local unit tests call the primary
    // constructor with the route object directly instead — toRoute's decode path
    // needs a real android.os.Bundle, and the JVM android.jar in local tests is a
    // stub that silently decodes every argument to null.
    @Inject
    constructor(
        chatRepository: ChatRepository,
        sessionsRepository: SessionsRepository,
        settingsRepository: SettingsRepository,
        attachmentStore: AttachmentStore,
        subagentStore: SubagentStreamStore,
        coldStart: TranscriptColdStart,
        savedStateHandle: SavedStateHandle,
        unreadStore: UnreadSessionsStore,
        @StreamCoalesceWindowMillis streamCoalesceWindowMillis: Long,
        @DefaultDispatcher decodeDispatcher: CoroutineDispatcher,
        imageBytesCache: ImageBytesCache,
    ) : this(
        chatRepository,
        sessionsRepository,
        settingsRepository,
        attachmentStore,
        subagentStore,
        coldStart,
        savedStateHandle.toRoute<Chat>(),
        unreadStore,
        streamCoalesceWindowMillis,
        decodeDispatcher,
        imageBytesCache,
    )

    private val sessionId: String = routeArgs.sessionId

    // Optional navigation arguments from the session list: they let the top bar show
    // the right label immediately, before [refreshSessionInfo] confirms it.
    private val seedTitle: String? = routeArgs.title
    private val seedCwd: String? = routeArgs.cwd

    /** One pending permission request, surfaced as the approval card above the input row.
     * [agentId] is the asking agent (ticket 139): "main", or the sub-agent's id — the
     * card badges itself with the member's name from [UiState.subagentNames]. [plan] is
     * the plan Markdown of an `ExitPlanMode` review (ticket 173): the card renders it in
     * a height-capped scroll area above Allow/Deny; null for every other request. */
    data class PendingApproval(
        val approvalId: String,
        val toolName: String,
        val summary: String,
        val agentId: String = MAIN_AGENT_ID,
        val plan: String? = null,
    )

    /** Transient LLM-retry status of the in-flight step (turn.step.retrying, 0.31.0 —
     * ticket 058): rendered as a status line, never a bubble. Set by the retrying
     * frame, cleared by the replacement turn.step.started (new stepId), the step's
     * completion, or any turn-terminal event. */
    data class StepRetry(
        val nextAttempt: Int,
        val maxAttempts: Int,
        val errorMessage: String,
    )

    /** The agent's live activity reduced for the status line above the composer
     * (ticket 148; derived since 177 — see [UiState.activityStatus]): [text] is the
     * label, [since] the activity's start for the elapsed readout — null when the
     * activity has no meaningful start (waiting on the user, a retry). */
    data class ActivityStatus(
        val text: String,
        val since: Instant? = null,
    )

    /** A model/effort switch held behind the cloud policy's confirmation dialog
     * (tickets 167/170): [target] is the picker value to commit on Continue; the
     * labels, hint and warning are the dialog's copy. */
    data class PendingSwitch(
        val target: String,
        val fromLabel: String,
        val toLabel: String,
        val hint: String? = null,
        val warning: String? = null,
    )

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
        /** The in-flight step's LLM retry, if any (ticket 058); transient status line. */
        val stepRetry: StepRetry? = null,
        /** When the main agent's current busy stretch (or, after a tool settled,
         * its current thinking phase) started — set by the `agent` frame's
         * `status:"running"`, null while `idle` (ticket 177). Gates the status line,
         * see [activityStatus]. */
        val agentActiveSince: Instant? = null,
        /** The most recently started main-agent tool still running (ticket 177): the
         * status line's "Running X…" sub-phase, derived from the transcript's tool
         * frames — the `agent` frame carries no phase. */
        val runningTool: RunningTool? = null,
        val inputText: String = "",
        val isSending: Boolean = false,
        /** Attachments staged above the input, in pick order (ticket 033). */
        val attachments: List<PendingAttachment> = emptyList(),
        /** Approval queue in arrival order; the card shows the head and the size. */
        val pendingApprovals: List<PendingApproval> = emptyList(),
        /** Question queue in arrival order; the card shows the head and the size. */
        val pendingQuestions: List<PendingQuestion> = emptyList(),
        /** Draft answers for the head question: item id → ticked option ids. A
         * single-select item holds a 1-element set; a multi-select item (090) any subset. */
        val questionSelections: Map<String, Set<String>> = emptyMap(),
        /** Draft free-text answers for the head question: item id → typed text. */
        val questionOtherTexts: Map<String, String> = emptyMap(),
        /** Wizard step (ticket 035): index into the head question's items shown by the card. */
        val questionStep: Int = 0,
        /** Ticket 088: true while the user minimized the question card to read the
         * timeline. Purely local (unlike dismiss); drafts are kept, the pending
         * AskUserQuestion tool row reopens the card, and any head-question change
         * resets it. */
        val questionCardMinimized: Boolean = false,
        /** Ticket 089: the resolved (answered/dismissed) question being viewed
         * read-only in the dock, rebuilt from the tapped row's input/output. While
         * set it occupies the card slot — a live pending card waits behind it. */
        val resolvedQuestionView: ResolvedQuestion? = null,
        /** Wizard step of the read-only resolved view (089); separate from the live
         * [questionStep], which keeps the live card's drafts untouched. */
        val resolvedQuestionStep: Int = 0,
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
        /** Picker alias → upstream model id (ticket 167), read from the config's
         * `models` map; used only to match client-config rules. Empty when unknown. */
        val modelAliases: Map<String, String> = emptyMap(),
        /** English descriptions per thinking effort from `effort_switch_confirm`
         * (ticket 170); empty = no descriptions to show. */
        val effortDescriptions: Map<String, String> = emptyMap(),
        /** A model switch awaiting the cloud policy's confirmation (ticket 167); null
         * when no dialog is up. Nothing is persisted until Continue. */
        val pendingModelSwitch: PendingSwitch? = null,
        /** An effort switch awaiting confirmation (ticket 170), same contract. */
        val pendingEffortSwitch: PendingSwitch? = null,
        /** The session's permission mode wire value (manual/yolo/auto — ticket 141):
         * the local pick (persisted), else the server's report (the `agent` frame's
         * `permission_mode`, ticket 177); null until either lands — the composer's
         * permission chip stays hidden meanwhile. */
        val sessionPermission: String? = null,
        /** The composer's swarm chip (ticket 174): seeded from the subscribe's first
         * `agent` frame (`swarm_mode`) and following every later one, unless an unsent
         * local toggle is in force; every prompt carries it as `swarm_mode`. Off until
         * the first frame lands. */
        val swarmMode: Boolean = false,
        /** Context window fill for the composer's ring (041, moved into the composer
         * in 053); null until the first `usage` frame carrying a real limit arrives
         * (the subscribe's seed, then every live change — ticket 177); a frame without
         * a limit leaves it as it is. */
        val contextUsage: ContextUsage? = null,
        /** The main agent's todo list, replaced wholesale by every `todo` frame
         * (ticket 178 — the first precedes the subscribe ack, later ones follow every
         * change, undo included); null (no frame yet) or empty hides the widget above
         * the composer (ticket 042). Never derived from TodoList tool calls. */
        val todos: List<Todo>? = null,
        /** Background tasks of the session (ticket 092): background sub-agents and
         * bash, fed by the JobsStore collaborator (the main agent's `task.list` and
         * `task` frames — ticket 179). The pill above the composer counts the Running
         * ones; terminal jobs linger for the sheet. */
        val jobs: List<Job> = emptyList(),
        /** toolCallId → linked member states for every linked call of the session
         * (ticket 134; the member STATES since 135 — the swarm card's aggregate
         * reads them), live from the store's registry; the swarm card reads its own
         * id and only renders while the list is non-empty. */
        val swarmMembers: Map<String, List<SubagentStreamStore.SubagentState>> = emptyMap(),
        /** subagentId → display name for the approval/question cards' sub-agent badge
         * (tickets 139/156), live from the store; a request whose record has not landed
         * yet is absent — the card falls back to a plain "Sub-agent" label. */
        val subagentNames: Map<String, String> = emptyMap(),
        /** True while older history exists (scroll-to-top loads it, ticket 043). */
        val hasMoreHistory: Boolean = false,
        /** True while an older history page loads; the list shows a header spinner. */
        val isLoadingMoreHistory: Boolean = false,
        /** True while a compaction runs (ticket 056): set optimistically on the POST
         * ack and by the `started` compaction marker, cleared by the `completed` one —
         * or, when that never comes, by the session going idle (ticket 181). Gates the
         * menu item — a second compact is a silent no-op server-side, so the client
         * must prevent it. */
        val isCompacting: Boolean = false,
    ) {
        /** Top-bar label: the loaded title (or cwd basename), falling back to the raw
         * session id — the single source of truth for the fallback, so the screen no
         * longer needs the nav argument for it. */
        val sessionLabel: String
            get() = sessionTitle ?: sessionId

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

        /** The effort the composer chip shows (ticket 108): the session's choice
         * (server-synced, 107), else the current model's `default_effort`; null on
         * effort-less models — the chip then shows the plain model name. */
        val effectiveEffort: String?
            get() = if (supportedEfforts.isEmpty()) null else selectedEffort ?: defaultEffort

        /** The active effort's English description (ticket 170): managed-kimi models
         * only, exactly like upstream; empty descriptions render nothing. */
        val activeEffortDescription: String?
            get() = currentModel?.takeIf { it.provider == MANAGED_KIMI_PROVIDER }
                ?.let { effortDescriptions[effectiveEffort] }

        /** The permission mode the composer chip shows (ticket 141); an unknown wire
         * value (or none yet) hides the chip. */
        val currentPermissionMode: PermissionMode?
            get() = PermissionMode.fromWire(sessionPermission)

        /**
         * The status line above the composer (ticket 148). Since 2.5.0 (ticket 177) the
         * server reports only busy/idle (the `agent` frame), so the sub-phase is derived
         * from the transcript state: shown only while the agent is busy; a pending
         * approval or question = waiting on the user, a step retry = retrying, a
         * running tool frame = "Running X…", a running turn otherwise = thinking — and
         * a busy agent with no turn is background work.
         */
        val activityStatus: ActivityStatus?
            get() {
                val since = agentActiveSince ?: return null
                return when {
                    pendingApprovals.isNotEmpty() -> ActivityStatus("Waiting for approval")
                    pendingQuestions.isNotEmpty() -> ActivityStatus("Waiting for your answer")
                    stepRetry != null -> ActivityStatus("Retrying…")
                    runningTool != null -> ActivityStatus("Running ${runningTool.name}…", runningTool.since)
                    isWorking -> ActivityStatus("Thinking…", since)
                    else -> ActivityStatus("Working in background…", since)
                }
            }
    }

    /** A main-agent tool frame still running (ticket 177): [since] is when this client
     * first saw it — the status line's elapsed readout. */
    data class RunningTool(val name: String, val since: Instant)

    sealed interface Effect {
        /** Snackbar text — the collector's catch-all branch shows it; the two special
         * cases ([SendFailed]'s Retry, [OpenFile]) handle themselves first (ticket 071). */
        val message: String

        data class RefreshFailed(override val message: String) : Effect

        /** The typed text stays in [UiState.inputText]; the snackbar's Retry re-calls [onSend]. */
        data class SendFailed(override val message: String) : Effect

        /** The send was refused with 40931: the session's occupancy lock belongs to
         * another client (2.3.0, tickets 168/169). `canTakeOver` is true only on a
         * SECOND consecutive 40931 — the screen then offers the destructive takeover
         * next to Retry; the first answer is a plain explanation. */
        data class SessionOccupied(
            override val message: String,
            val canTakeOver: Boolean,
        ) : Effect

        /** A 40932 send failure recovered: the session was reloaded and the chat
         * rebuilt — the draft is intact and the user may retry (ticket 168). */
        data class SessionReloaded(override val message: String) : Effect

        /** A recovery step failed (reload, cold-start resync or takeover); the draft is
         * intact and nothing was resubmitted. */
        data class SessionRecoveryFailed(override val message: String) : Effect

        /** The occupancy lock was evicted (ticket 169); the user sends when ready. */
        data class TakeoverSucceeded(override val message: String) : Effect

        /** The turn is still running; the Stop action stays available. */
        data class AbortFailed(override val message: String) : Effect

        /** The card stays up: with no resolved event coming, the request is still pending. */
        data class ApprovalFailed(override val message: String) : Effect

        /** Same contract as approvals: no resolved event, the question card stays up. */
        data class QuestionFailed(override val message: String) : Effect

        /** A picked file was not staged: unreadable, or over [AttachmentStore.MAX_PICK_BYTES]. */
        data class AttachmentPickFailed(override val message: String) : Effect

        /** A history attachment's bytes are downloaded and persisted (ticket 034); the
         * screen hands [contentUri] to another app via ACTION_VIEW and reports a missing
         * viewer back through [onOpenAttachmentFailed]. [mimeType] is the server's
         * response Content-Type — more reliable than the notice-parsed attachment MIME. */
        data class OpenFile(val contentUri: Uri, val mimeType: String, val name: String) : Effect {
            // Never shown: the collector's OpenFile branch handles the effect itself.
            override val message: String get() = name
        }

        /** The download or cache-write of a history attachment failed, or no app could open it. */
        data class OpenAttachmentFailed(override val message: String) : Effect

        /** Confirmation of a finished compaction (ticket 056): the token delta label. */
        data class CompactionFinished(override val message: String) : Effect

        /** The compact POST failed (e.g. the empty-session 40910 — the server's own
         * message). A compaction that starts and then never completes has no failure
         * signal on the wire (ticket 181): its progress row just goes away. */
        data class CompactionFailed(override val message: String) : Effect

        /** The `task.stop` control failed (ticket 179 — an error reply such as
         * `40933`, or no connection): nothing changed, the job is still running. */
        data class JobCancelFailed(override val message: String) : Effect

        /** The fork POST failed (ticket 127 — unknown id 40401, busy source 40901):
         * the server's own message; the user stays on the source session. */
        data class ForkFailed(override val message: String) : Effect

        /** Fork succeeded (ticket 127): the collector navigates to the fork's chat. */
        data class SessionForked(val session: Session) : Effect {
            // Never shown: the collector's SessionForked branch navigates instead.
            override val message: String get() = session.displayName
        }

        /** The "Files changed" tap resolved the turn (ticket 149): the collector
         * navigates to the turn's file-changes screen. */
        data class OpenTurnChanges(val turnId: Int) : Effect {
            // Never shown: the collector's OpenTurnChanges branch navigates instead.
            override val message: String get() = "Turn $turnId"
        }

        /** The turn resolution behind "Files changed" failed (ticket 149) — the
         * transcript fetch errored, or the session has no turn yet. */
        data class TurnChangesFailed(override val message: String) : Effect
    }

    val uiState: StateFlow<UiState>
        field = MutableStateFlow(
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

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** Attachments (ticket 072): pick → upload → remove → open orchestration. A logic
     * holder only — UiState above stays the single state source; the collaborator
     * receives intents and reports state updates/effects back through the callbacks. */
    private val attachments = ChatAttachments(
        scope = { viewModelScope },
        attachmentStore = attachmentStore,
        chatRepository = chatRepository,
        settingsRepository = settingsRepository,
        sessionId = sessionId,
        updateState = { transform -> uiState.update(transform) },
        sendEffect = { effect -> _effects.send(effect) },
        decodeDispatcher = decodeDispatcher,
        imageBytesCache = imageBytesCache,
    )

    /** The question wizard (ticket 072): pending-question lifecycle, answer submission,
     * dismissal. Same logic-holder contract as [attachments]. */
    private val questions = QuestionWizard(
        scope = { viewModelScope },
        chatRepository = chatRepository,
        settingsRepository = settingsRepository,
        sessionId = sessionId,
        state = { uiState.value },
        updateState = { transform -> uiState.update(transform) },
        sendEffect = { effect -> _effects.send(effect) },
    )

    /** The jobs widget's store (ticket 092): background-task list (the main agent's
     * task frames — ticket 179), task stop. Same logic-holder contract as
     * [questions]. */
    private val jobsStore = JobsStore(
        scope = { viewModelScope },
        chatRepository = chatRepository,
        sessionId = sessionId,
        state = { uiState.value },
        updateState = { transform -> uiState.update(transform) },
        sendEffect = { effect -> _effects.send(effect) },
    )

    private val timeline = ChatTimeline()

    /** The `before_id` cursor of the next older page (ticket 043): the oldest loaded
     * history message's id, tracked ViewModel-side like ticket 010 — the timeline rows
     * can't provide it (tool rows carry tool-call ids, and a message can flatten to no
     * row). Reset by every newest-page refetch. */
    private var oldestHistoryId: String? = null

    /** Screen visibility as state — one input of the [socketGate]. */
    private val screenVisible = MutableStateFlow(false)

    init {
        // The swarm cards' aggregate input (ticket 135): the store's per-call member
        // lists, live — every member status flip republishes them, so the card's bar
        // and counts advance mid-swarm.
        viewModelScope.launch {
            subagentStore.swarmMembersByCall(sessionId).collect { byCall ->
                uiState.update { it.copy(swarmMembers = byCall) }
            }
        }
        // The approval card's sub-agent badge names (ticket 139): live from the store —
        // a member's spawn task frame can land AFTER its approval request, and the badge
        // gains its name the moment the record exists.
        viewModelScope.launch {
            subagentStore.displayNames(sessionId).collect { names ->
                uiState.update { it.copy(subagentNames = names) }
            }
        }
    }

    /**
     * The cold-start sync of this ViewModel instance (tickets 085/176): launched by the
     * gate's first opening (and re-launched by [onRetry] after a failure), joined by
     * every opening so the first subscribe always runs after the watermark seed. The
     * job lives on viewModelScope (not the gate coroutine): a gate flip mid-sync
     * cancels only the join, never the sync.
     */
    private var coldStartJob: CoroutineJob? = null

    /** True once a cold-start sync completed — the gate from which [onScreenStarted]
     * runs the within-process resume set. */
    private var coldStartDone = false

    private fun ensureColdStart(): CoroutineJob {
        val existing = coldStartJob
        if (existing != null && (existing.isActive || coldStartDone)) return existing
        return viewModelScope.launch { runColdStart() }.also { coldStartJob = it }
    }

    /**
     * The one cold start of this chat open: awaits the session's transcript cold start
     * and applies its bundle — history, the running turn's live rows, the working
     * state, the pending approvals/questions and the queued prompts (the bound
     * model/effort, permission, busy state and ring ride the subscribe's `agent`/`usage`
     * frames instead — ticket 177). An
     * [TranscriptColdStart.Outcome.AlreadyFresh] join means another collection (the
     * store's keep-alive restoring a sub-agent page on top) fetched first: its bundle is
     * gone and the socket may have folded past it, while this timeline is still empty
     * — so the chat takes a fresh fetch of its own ([TranscriptColdStart.resync]). A
     * failed fetch surfaces the ordinary load-error state — no fallback to any legacy
     * route (the app runs against the latest server only).
     */
    private suspend fun runColdStart() {
        val guards = captureSeedGuards()
        val outcome = coldStart.sync(sessionId).getOrElse { error ->
            reportLoadFailure(error)
            return
        }
        val bundle = when (outcome) {
            is TranscriptColdStart.Outcome.Fresh -> outcome.bundle
            is TranscriptColdStart.Outcome.AlreadyFresh ->
                coldStart.resync(sessionId).getOrElse { error ->
                    reportLoadFailure(error)
                    return
                }
        }
        applyColdStart(bundle, guards)
        coldStartDone = true
    }

    /** A failed load: full-screen while nothing renders, a snackbar otherwise. */
    private suspend fun reportLoadFailure(error: Throwable) {
        if (uiState.value.items.isNullOrEmpty()) {
            uiState.update { it.copy(error = error.toUserMessage()) }
        } else {
            _effects.send(Effect.RefreshFailed(error.toUserMessage()))
        }
    }

    // The one approval-card mapping, for both carriers (the transcript's and the
    // reset's pending requests, the live request frame).
    private fun PendingApprovalRequest.toPendingApproval() =
        PendingApproval(approvalId, toolName, summary, agentId, plan)

    private fun SessionEvent.ApprovalRequested.toPendingApproval() =
        PendingApproval(approvalId, toolName, summary, agentId, plan)

    /** The guard captures for a cold-start apply racing the live stream (the socket is
     * open on the resync paths): an event landing mid-fetch is newer than the
     * fetched state, so its tracked field skips the stale apply. */
    private data class SeedCaptures(
        val turn: Int,
        val approvals: Int,
        val questions: Int,
    )

    private fun captureSeedGuards() = SeedCaptures(
        turn = turnSeedGuard.capture(),
        approvals = approvalsSeedGuard.capture(),
        questions = questions.captureEvents(),
    )

    /**
     * Applies the chat-side cold-start bundle (ticket 176): the cold start and every
     * rebuild (40932 recovery, stale journal). History replaces the committed rows and
     * the live tail is rebuilt from the transcript's running turn
     * ([ChatTimeline.seedRunningTurn]) — the socket fold was seeded from the same page,
     * so the replayed/live ops after its watermark extend exactly these rows. A turn
     * event landing mid-fetch (a resync with the socket open) is newer than the page:
     * the live tail is then kept as-is and the working state untouched. Waiting
     * prompts render as queued bubbles; pending approvals/questions replace the cards'
     * queues unless a live event overtook the fetch. The roster and the `Agent` calls
     * already reached the store inside [TranscriptColdStart]'s fetch — here the rebuilt
     * timeline's rows are mined and the store's links re-driven onto them (ticket 091).
     */
    private fun applyColdStart(bundle: TranscriptColdStart.Bundle, guards: SeedCaptures) {
        val turnStale = turnSeedGuard.isStale(guards.turn)
        // Any coalesced text must apply BEFORE the replace wipes/dedups the live rows
        // (ticket 062; mirrors the history pager's applyNewest).
        deltaCoalescer.flush()
        if (turnStale) {
            timeline.replaceHistory(bundle.messages.items, keepLive = true)
        } else {
            timeline.replaceHistory(bundle.messages.items, keepLive = false)
            bundle.runningTurn?.let(timeline::seedRunningTurn)
        }
        timeline.seedQueuedPrompts(bundle.queue.queued.map { it.message })
        if (!turnStale) seedRunningTools(bundle)
        oldestHistoryId = bundle.messages.items.firstOrNull()?.id
        mineAgentRowsIntoStore()
        uiState.update {
            it.copy(
                items = timeline.items(),
                error = null,
                hasMoreHistory = bundle.messages.hasMore,
                isWorking = if (!turnStale) bundle.busy else it.isWorking,
                runningTool = runningTools.values.lastOrNull(),
                // The transcript's pending interactions are the only REST read of the
                // approvals: they REPLACE the queue (a decision missed with the stream
                // heals here), unless a live approval event overtook the fetch.
                pendingApprovals =
                    if (!approvalsSeedGuard.isStale(guards.approvals)) {
                        bundle.transcript.pendingApprovals
                            .map { a -> a.toPendingApproval() }
                            .distinctBy { a -> a.approvalId }
                    } else {
                        it.pendingApprovals
                    },
            )
        }
        questions.applyColdStart(bundle.transcript.pendingQuestions.distinctBy { it.questionId }, guards.questions)
        // The bound model/effort, the permission mode, the busy state and the context
        // ring are NOT part of the cold start: the subscribe's `agent`/`usage` frames
        // seed them (they precede the ack) and every change re-sends them (ticket 177).
    }

    /** The main agent's running tool frames, by tool call id in start order (ticket
     * 177): the status line's "Running X…" source. Fed by the live tool events, cleared
     * by a turn end or the agent going idle, re-seeded by every cold start from the
     * running turn's still-running tool frames. */
    private val runningTools = LinkedHashMap<String, RunningTool>()

    private fun seedRunningTools(bundle: TranscriptColdStart.Bundle) {
        runningTools.clear()
        val now = Instant.now()
        bundle.runningTurn?.steps.orEmpty()
            .flatMap { it.frames }
            .filterIsInstance<TranscriptFrame.Tool>()
            .filter { it.running }
            .forEach { runningTools[it.toolCallId] = RunningTool(it.name, now) }
    }

    /**
     * The resume check (ticket 176, replacing 048's status-aware REST resync): every
     * re-subscribe carries the main agent's watermark, so the server REPLAYS what the
     * gap missed — a turn that ended while away arrives as its own `turn.upsert` and
     * takes the ghost live rows with it. The replay is only as good as the journal,
     * though: this probe asks `GET .../transcript/ops` whether it still covers the
     * watermark, and a stale answer (`complete:false`, or a restarted server's journal
     * behind the watermark) rebuilds the chat from a fresh transcript cold start
     * instead of trusting a partial replay. Runs on every gate reopen (ON_START) and on
     * every `Reconnected`; latest-wins. A failed probe proves nothing — the replay
     * stays the resume.
     */
    private var resumeCheckJob: CoroutineJob? = null

    private fun verifyResume() {
        val since = chatRepository.transcriptWatermark(sessionId, MAIN_AGENT_ID) ?: return
        resumeCheckJob?.cancel()
        resumeCheckJob = viewModelScope.launch {
            chatRepository.getTranscriptCatchUp(settingsRepository.current(), sessionId, MAIN_AGENT_ID, since)
                .onSuccess { catchUp -> if (catchUp.isStale) rebuildFromTranscript() }
        }
    }

    /** The full refresh of a stale journal (ticket 176): one fresh transcript cold
     * start — the socket fold and watermark restart from its page — applied over the
     * timeline. One rebuild at a time: a second stale signal while one is in flight
     * is already covered by it. */
    private var rebuildJob: CoroutineJob? = null

    private fun rebuildFromTranscript() {
        if (rebuildJob?.isActive == true) return
        val guards = captureSeedGuards()
        rebuildJob = viewModelScope.launch {
            coldStart.resync(sessionId)
                .onSuccess { bundle -> applyColdStart(bundle, guards) }
                .onFailure { error -> _effects.send(Effect.RefreshFailed(error.toUserMessage())) }
        }
    }

    /** Re-drives the store's session link registry (ticket 091 — spawn task frame /
     * result marker, one owner; one-to-many since 134, a swarm's members sharing one
     * toolCallId) into the timeline's rows. Runs after every store-affecting apply:
     * the timeline's own healing keeps links across refetches, but only the store's
     * registry survives this ViewModel. The cards' member lists ride the store-flow
     * collection in init (ticket 135). Idempotent and cheap. */
    private fun applySubagentLinks() {
        val links = subagentStore.linksFor(sessionId)
        links.forEach { (toolCallId, subagentIds) ->
            subagentIds.forEach { subagentId -> timeline.linkSubagentTool(toolCallId, subagentId) }
        }
        uiState.update {
            it.copy(items = timeline.items())
        }
    }

    /** Bumped on every turn.started/turn.ended event, so a cold-start rebuild
     * ([applyColdStart]) can drop a response a newer turn event has overtaken. */
    private val turnSeedGuard = SeedGuard()

    /** Bumped on every approval event (request, end, the ack's reset), so a cold-start
     * apply racing the live stream drops its stale queue replace. */
    private val approvalsSeedGuard = SeedGuard()

    /** Publishes the timeline's current rows — the coalescer's publish point
     * and [applySessionEvent]'s for timeline-bound events. */
    private fun publishTimeline() {
        uiState.update { it.copy(items = timeline.items()) }
    }

    /** The delta-coalescing state machine (ticket 065): every timeline-bound socket
     * event routes through it, so the flush-before-apply invariant can't be forgotten
     * at a call site. Confined to Main: the socket collect, the flush loop, and
     * onScreenStopped all run on viewModelScope's dispatcher (or are called from it). */
    private val deltaCoalescer = DeltaCoalescer(
        windowMillis = streamCoalesceWindowMillis,
        scope = { viewModelScope },
        applyDelta = timeline::apply,
        applyEvent = ::applySessionEvent,
        publish = ::publishTimeline,
    )

    /** The paged-history loader (ticket 074), shared with the sub-agent page: it owns
     * the fetch jobs and their guards; the callbacks are this screen's
     * parameterization — message-history backing, the coalescer flush before every
     * replace (ticket 062), sub-agent row seeding on a fresh page, and the
     * error/loading state of this UiState. */
    private val historyPager = HistoryPager<MessagePage>(
        scope = { viewModelScope },
        fetch = { beforeId ->
            chatRepository.getMessages(settingsRepository.current(), sessionId, beforeId = beforeId)
        },
        // No loading flag to set: this screen's loading state is items == null.
        onNewestStart = { },
        applyNewest = { page, keepLive ->
            oldestHistoryId = page.items.firstOrNull()?.id
            // Any coalesced text must apply BEFORE the replace wipes/dedups
            // the live rows — the REST path bypasses the socket dispatcher's
            // flush (ticket 062; mirrors SubagentStreamStore.applyTranscriptPage).
            deltaCoalescer.flush()
            timeline.replaceHistory(page.items, keepLive = keepLive)
            uiState.update {
                it.copy(
                    items = timeline.items(),
                    error = null,
                    hasMoreHistory = page.hasMore,
                )
            }
            // History is where cold links come from (the app subscribed after
            // the spawn): mine the page's Agent rows into the store.
            mineAgentRowsIntoStore()
        },
        applyOlder = { page ->
            oldestHistoryId = page.items.firstOrNull()?.id ?: oldestHistoryId
            timeline.prependHistory(page.items)
            uiState.update {
                it.copy(
                    items = timeline.items(),
                    hasMoreHistory = page.hasMore,
                )
            }
            // Rows the links predate (loaded after a spawn/roster link was learned)
            // gain their open affordance from the store's registry (ticket 091).
            mineAgentRowsIntoStore()
        },
        onNewestError = { error ->
            if (uiState.value.items.isNullOrEmpty()) {
                uiState.update { it.copy(error = error.toUserMessage()) }
            } else {
                _effects.send(Effect.RefreshFailed(error.toUserMessage()))
            }
        },
        hasMore = { uiState.value.hasMoreHistory },
        cursor = { oldestHistoryId },
        setLoadingMore = { loading -> uiState.update { it.copy(isLoadingMoreHistory = loading) } },
    )

    /** Wired to ON_START. The FIRST start of this ViewModel lets the gate's transcript
     * cold start do the initial load (ticket 176). Later starts are the within-process
     * resume (ticket 048): the socket gate re-subscribes with the resume watermark (the
     * server replays the missed window — a turn that ended while the app was away
     * takes its ghost live rows with it, and its `agent`/`usage` frames re-seed the
     * model, permission, busy and context state — ticket 177), [verifyResume] checks
     * the journal covered it (the re-subscribe's ack resets the approval/question
     * cards and the re-sent pending frames refill them — tickets 171/172). Every start
     * re-applies the stored permission pick. Also fires when the app returns to the
     * foreground. */
    fun onScreenStarted() {
        socketGate.start()
        screenVisible.value = true
        // The web clears a session's unread marker when it becomes the visible session
        // (ticket 145 backstop: a process-death restore lands here without a list tap).
        viewModelScope.launch { unreadStore.clear(settingsRepository.current().serverUrl, sessionId) }
        if (coldStartDone) verifyResume()
        seedStoredPermissionPick()
        refreshSessionInfo()
        loadModels()
    }

    /** Wired to ON_STOP: the gate closes the socket unless a sub-agent page of this
     * session (or the job screen, ticket 097 — same page count) is open. Coalesced
     * deltas flush first, so no received text is ever held back (or lost with the
     * timer). */
    fun onScreenStopped() {
        deltaCoalescer.flush()
        screenVisible.value = false
    }

    /** The session is being left for good (back to the session list): its sub-agent
     * streams go with it. */
    override fun onCleared() {
        subagentStore.release(sessionId)
    }

    fun onRetry() {
        uiState.update { it.copy(error = null) }
        // A failed cold start retries the transcript cold start (the gate is already
        // open — this is the only re-trigger).
        if (!coldStartDone) ensureColdStart()
        onScreenStarted()
    }

    fun onInputChange(value: String) {
        uiState.update { it.copy(inputText = value) }
    }

    // --- Attachments (ticket 072): the logic lives in [ChatAttachments] — these are
    // the screen-facing intents, kept as one-line delegates. --------------------------

    fun onAttachmentsPicked(uris: List<Uri>) = attachments.onPicked(uris)

    /** Internal because unit tests have no android.net.Uri on the JVM — they drive this
     * directly instead of going through [onAttachmentsPicked]. */
    internal suspend fun onAttachmentRead(result: AttachmentStore.ReadResult) = attachments.onRead(result)

    fun onAttachmentRemoved(localId: String) = attachments.onRemoved(localId)

    suspend fun imageBytes(image: ChatMessage.Part.Image): ByteArray? = attachments.imageBytes(image)

    fun onOpenAttachment(attachment: ChatMessage.Part.Attachment) = attachments.onOpen(attachment)

    fun onOpenAttachmentFailed(name: String) = attachments.onOpenFailed(name)

    /**
     * POSTs the current input: a new prompt while the session is idle, a steer into the
     * running turn while it is busy (docs/server-api.md, Steer). No-ops when text and
     * attachments are both empty, or while a send is in flight (which covers the whole
     * upload phase — a second tap cannot double-send). Staged attachments upload
     * sequentially first via [ChatAttachments.uploadAll]; a failed upload marks its
     * chip, posts no prompt, and surfaces [Effect.SendFailed]. On failure the text
     * stays in the field and [Effect.SendFailed] drives a snackbar with Retry.
     */
    fun onSend() {
        val state = uiState.value
        val text = state.inputText.trim()
        if ((text.isEmpty() && state.attachments.isEmpty()) || state.isSending) return
        uiState.update { it.copy(isSending = true) }
        viewModelScope.launch {
            val settings = settingsRepository.current()
            // Upload phase: null = a chip failed its upload, the send aborts here.
            val uploaded = attachments.uploadAll(settings, state.attachments) ?: return@launch
            // The chip's value at send time (ticket 174): sent true AND false — an
            // omitted swarm_mode would leave the server's mode unchanged.
            val swarmMode = uiState.value.swarmMode
            val result = if (state.isWorking) {
                chatRepository.steer(settings, sessionId, text, uploaded, swarmMode)
            } else {
                chatRepository.sendPrompt(settings, sessionId, text, uploaded, swarmMode)
            }
            result
                .onSuccess { promptId ->
                    // The server bound the session to the sent model/effort/permission
                    // mode: its reports describe the pick from here on and may sync again.
                    pendingModelPick = false
                    pendingEffortPick = false
                    pendingPermissionPick = false
                    // Same for swarm mode — unless the chip was toggled again while
                    // the POST was in flight: that newer pick is still unsent.
                    if (uiState.value.swarmMode == swarmMode) pendingSwarmPick = false
                    occupiedFailureStreak = 0
                    // The optimistic local echo (ticket 154): the bubble shows at once
                    // — for a prompt queued while the agent is busy, the context.spliced
                    // echo arrives only when the queued turn starts. The splice heals
                    // the row in place (the ACK's prompt id == the message id); a
                    // refetch replaces it once the message commits. The reply streams
                    // as before.
                    timeline.appendLocalEcho(
                        promptId,
                        text,
                        uploaded.filterNot { it.isImage }
                            .map { ChatMessage.Part.Attachment(it.name, it.mediaType, it.sizeBytes, it.id) },
                        uploaded.filter { it.isImage }
                            .map { ChatMessage.Part.Image(base64Data = null, fileId = it.id, name = it.name) },
                    )
                    uiState.update {
                        it.copy(
                            inputText = "",
                            attachments = emptyList(),
                            isSending = false,
                            isWorking = true,
                            items = timeline.items(),
                        )
                    }
                }
                .onFailure { error ->
                    uiState.update { it.copy(isSending = false) }
                    handleSendFailure(error)
                }
        }
    }

    /** Consecutive 40931 answers on the composer's send path (ticket 169): only the
     * SECOND one offers the takeover, so the flag resets on a successful send, on any
     * other failure, and after a takeover attempt. */
    private var occupiedFailureStreak = 0

    /**
     * The send failure's recovery ladder (tickets 168/169). The composer draft and
     * attachments are never touched here, and no path resubmits the prompt — the
     * user's explicit Retry is always the resubmission.
     *
     * - 40931 `SESSION_OCCUPIED`: explain the conflict, naming the holder's
     *   `clientDisplayName` when the envelope carries it (fallback: the server's own
     *   message). Never reload, never take over automatically; the second consecutive
     *   answer offers the takeover.
     * - 40932 `SESSION_STALE`: reload the session, rebuild from a fresh transcript cold
     *   start, then tell the user to retry. A failed step reports itself and keeps the draft.
     * - Anything else keeps the existing [Effect.SendFailed] Retry snackbar.
     */
    private suspend fun handleSendFailure(error: Throwable) {
        val api = error as? DataError.Api
        when (api?.code) {
            SESSION_OCCUPIED_CODE -> {
                occupiedFailureStreak++
                _effects.send(
                    Effect.SessionOccupied(
                        message = occupiedMessage(api),
                        canTakeOver = occupiedFailureStreak >= 2,
                    ),
                )
            }
            SESSION_STALE_CODE -> {
                occupiedFailureStreak = 0
                recoverStaleSession()
            }
            else -> {
                occupiedFailureStreak = 0
                _effects.send(Effect.SendFailed(error.toUserMessage()))
            }
        }
    }

    /** "This session is in use by Kimi Code CLI." — the holder's display name from
     * the 40931 `details.holder`, else the server's own sentence (ticket 169). */
    private fun occupiedMessage(error: DataError.Api): String {
        val holder = error.details
            ?.get("holder")
            ?.let { it as? JsonObject }
            ?.get("clientDisplayName")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        return if (holder != null) {
            "This session is in use by $holder. Close it there, then retry."
        } else {
            error.toUserMessage()
        }
    }

    /** The 40932 recovery (ticket 168): one `:reload`, then one fresh cold-start apply.
     * The prompt is NOT resubmitted; a failure at either step reports itself and the
     * draft stays in the composer. */
    private suspend fun recoverStaleSession() {
        val settings = settingsRepository.current()
        sessionsRepository.reloadSession(settings, sessionId)
            .onFailure { error ->
                _effects.send(
                    Effect.SessionRecoveryFailed(
                        "The session changed elsewhere and could not be reloaded: ${error.toUserMessage()}",
                    ),
                )
            }
            .onSuccess {
                val guards = captureSeedGuards()
                coldStart.resync(sessionId)
                    .onSuccess { bundle ->
                        applyColdStart(bundle, guards)
                        _effects.send(
                            Effect.SessionReloaded("The session was reloaded. Review the chat, then retry your message."),
                        )
                    }
                    .onFailure { error ->
                        _effects.send(
                            Effect.SessionRecoveryFailed(
                                "The session was reloaded, but the chat could not resync: ${error.toUserMessage()}",
                            ),
                        )
                    }
            }
    }

    /**
     * Ticket 169's explicit takeover: one `sessions/{id}:takeover` POST, never a prompt
     * POST. Success only clears the lock; the user decides when to retry. Failure is
     * surfaced and the draft stays put.
     */
    fun onTakeOverSession() {
        if (takeoverInFlight) return
        takeoverInFlight = true
        viewModelScope.launch {
            try {
                sessionsRepository.takeoverSession(settingsRepository.current(), sessionId)
                    .onSuccess {
                        occupiedFailureStreak = 0
                        _effects.send(Effect.TakeoverSucceeded("The session lock was released. Send when ready."))
                    }
                    .onFailure { error ->
                        _effects.send(
                            Effect.SessionRecoveryFailed("Could not take over the session: ${error.toUserMessage()}"),
                        )
                    }
            } finally {
                takeoverInFlight = false
            }
        }
    }

    private var takeoverInFlight = false

    /** POSTs `:abort` for the running turn. On success nothing is cleared locally: the
     * server's turn.ended frame (docs/server-api.md) drives isWorking=false and the
     * history refetch that commits the partial reply, so a dropped socket just retries. */
    fun onStop() {
        if (!uiState.value.isWorking) return
        viewModelScope.launch {
            chatRepository.abortPrompt(settingsRepository.current(), sessionId)
                .onFailure { error -> _effects.send(Effect.AbortFailed(error.toUserMessage())) }
        }
    }

    /** The stream went idle (`agent` idle / `work_changed` busy:false — ticket 181)
     * while still compacting: the completed marker never came (the server has no
     * failure marker), so the progress row goes and the menu item re-enables. After a
     * completed marker this is a no-op — isCompacting is already false. */
    private fun settleDroppedCompaction() {
        if (!uiState.value.isCompacting) return
        timeline.compactionAbandoned()
        uiState.update { it.copy(isCompacting = false, items = timeline.items()) }
    }

    /**
     * POSTs `:compact` (ticket 056). No-ops while a turn runs or a compaction is in
     * progress — the server rejects the former (40910) and silently no-ops the latter,
     * so the guard is the only feedback; the menu item is disabled in both states too.
     * On the ack the compacting state is set optimistically; the socket's
     * `compaction` transcript markers (started/completed, ticket 181) drive the
     * divider and clear it. A failure (empty session, web-started turn) surfaces the
     * server's own message and never enters the compacting state.
     */
    fun onCompact() {
        val state = uiState.value
        if (state.isWorking || state.isCompacting) return
        viewModelScope.launch {
            chatRepository.compact(settingsRepository.current(), sessionId)
                .onSuccess { uiState.update { it.copy(isCompacting = true) } }
                .onFailure { error -> _effects.send(Effect.CompactionFailed(error.toUserMessage())) }
        }
    }

    /**
     * The overflow's "Files changed" (ticket 149): opens the file-changes screen for
     * the session's NEWEST main turn — the phone's answer to "what did the agent
     * just touch?". The turn id is resolved at tap time from the main agent's
     * transcript (its ordinals ARE the file-history `turn_id` numbering — there is
     * no second id): message history carries no turn ids, and live `turn.ended`
     * tracking would leave a cold-opened session without any. A turn with nothing
     * recorded is not an error — the screen's empty state covers it. A fresh
     * session (no turns at all) snackbars instead of navigating.
     */
    fun onFilesChanged() {
        viewModelScope.launch {
            chatRepository.getTranscript(settingsRepository.current(), sessionId, MAIN_AGENT_ID, beforeTurn = null, pageSize = 5)
                .onSuccess { page ->
                    val turn = page.turns.lastOrNull()
                    if (turn == null) {
                        _effects.send(Effect.TurnChangesFailed("No turns yet"))
                    } else {
                        _effects.send(Effect.OpenTurnChanges(turn.ordinal))
                    }
                }
                .onFailure { error -> _effects.send(Effect.TurnChangesFailed(error.toUserMessage())) }
        }
    }

    /**
     * POSTs `:fork` (ticket 127) and reports the fork through [Effect.SessionForked]
     * for the screen to navigate to. No-ops while a turn runs — the server rejects a
     * busy fork with 40901, so the guard (and the disabled menu item) is the only
     * feedback. A failure surfaces the server's own message; the user stays on the
     * source session.
     */
    fun onFork() {
        if (uiState.value.isWorking) return
        viewModelScope.launch {
            sessionsRepository.forkSession(settingsRepository.current(), sessionId)
                .onSuccess { fork -> _effects.send(Effect.SessionForked(fork)) }
                .onFailure { error -> _effects.send(Effect.ForkFailed(error.toUserMessage())) }
        }
    }

    fun onAllow() = postDecision(approved = true)

    fun onDeny() = postDecision(approved = false)

    /** Decides the head of the queue (arrival order). The card clears on the server's
     * `ended` interaction frame — for our own decisions too (ticket 171) — so a failed
     * POST leaves the card up and only reports the error. */
    private fun postDecision(approved: Boolean) {
        val approval = uiState.value.pendingApprovals.firstOrNull() ?: return
        viewModelScope.launch {
            chatRepository.postApprovalDecision(
                settingsRepository.current(), sessionId, approval.approvalId, approved,
            ).onFailure { error -> _effects.send(Effect.ApprovalFailed(error.toUserMessage())) }
        }
    }

    // --- Question wizard (ticket 072): the logic lives in [QuestionWizard] — these are
    // the screen-facing intents, kept as one-line delegates. --------------------------

    fun onQuestionOptionPicked(itemId: String, optionId: String) = questions.onOptionPicked(itemId, optionId)

    fun onQuestionOptionToggled(itemId: String, optionId: String) = questions.onOptionToggled(itemId, optionId)

    fun onQuestionOtherTextChange(itemId: String, text: String) = questions.onOtherTextChange(itemId, text)

    fun onQuestionNext() = questions.onNext()

    fun onQuestionBack() = questions.onBack()

    fun onSubmitQuestion() = questions.onSubmit()

    fun onDismissQuestion() = questions.onDismiss()

    fun onMinimizeQuestion() = questions.onMinimize()

    fun onReopenQuestion() = questions.onReopen()

    fun onToggleQuestionCard() = questions.onToggle()

    fun onQuestionRowTapped(toolCallId: String, input: String, output: String) =
        questions.onRowTapped(toolCallId, input, output)

    fun onCloseResolvedQuestion() = questions.onCloseResolvedQuestion()

    fun onResolvedQuestionNext() = questions.onResolvedNext()

    fun onResolvedQuestionBack() = questions.onResolvedBack()

    // --- Jobs widget (ticket 092): the logic lives in [JobsStore] — this is the
    // screen-facing intent, kept as a one-line delegate. ----------------------------

    /** Stops one running background job (`task.stop`); its ended state flips the row. */
    fun onCancelJob(taskId: String) = jobsStore.cancel(taskId)

    /**
     * Collects the session socket (one run per socket-gate opening; cancelled when the
     * gate closes). Main-agent session events route through [deltaCoalescer] (ticket
     * 065): it conflates the streaming deltas and flushes-before-apply for anything
     * else, so no substantive event is ever delayed behind coalesced text. Sub-agent
     * frames go to the store, which coalesces per sub-agent stream.
     */
    private suspend fun collectSessionSocket() {
        chatRepository.observeSession(settingsRepository.current(), sessionId)
            .collect { event ->
                val sessionEvent = (event as? SocketEvent.Event)?.event
                when {
                    // Sub-agent frames — every agent-scoped event a child emits
                    // (its ops, reset signal, agent/todo/task frames) — route to the store
                    // (ticket 045): they must never touch the main timeline, the
                    // working state, or an open main delta burst (ticket 044).
                    sessionEvent != null && sessionEvent.isSubagentScoped() ->
                        onSubagentEvent(sessionEvent)
                    sessionEvent != null -> deltaCoalescer.onEvent(sessionEvent)
                    else -> {
                        // Connection lifecycle frames mutate no timeline rows, but the
                        // flush is owed all the same: no event waits behind coalesced
                        // text.
                        deltaCoalescer.flush()
                        when (event) {
                            SocketEvent.Connected ->
                                uiState.update { it.copy(isReconnecting = false) }
                            SocketEvent.Reconnected -> {
                                uiState.update { it.copy(isReconnecting = false) }
                                // The re-subscribe carried the resume watermark: the
                                // server replays the window the drop missed (a turn
                                // end above all, ticket 176). Verify the journal could
                                // cover it; the ack's resets and the re-sent pending
                                // frames rebuild the approval/question cards (171/172).
                                verifyResume()
                            }
                            SocketEvent.Disconnected ->
                                uiState.update { it.copy(isReconnecting = true) }
                            // An invalid URL: no-op — the history load validates it first
                            // and owns the user-facing error. A session the server does
                            // not know (ticket 175) surfaces like a failed load:
                            // full-screen while nothing renders, a snackbar otherwise.
                            is SocketEvent.Failed -> if (event.error is DataError.SessionNotFound) {
                                uiState.update { it.copy(isReconnecting = false) }
                                reportLoadFailure(event.error)
                            }
                            is SocketEvent.Event -> Unit // handled above via the coalescer
                        }
                    }
                }
            }
    }

    /** Routes one sub-agent-scoped event (a child agent's own frames) into the store.
     * The spawn's row link comes from the MAIN-owned spawn task frame instead
     * ([applySessionEvent]'s task branches, ticket 182). */
    private suspend fun onSubagentEvent(event: SessionEvent) {
        subagentStore.apply(sessionId, event)
    }

    /** Mines the timeline's `Agent`/`AgentSwarm` rows into the store (ticket 091;
     * AgentSwarm added in 134 — the store's swarm branch keeps the call's label and,
     * since 136, heals the member states from the settled call's
     * `<agent_swarm_result>`; the members' live state still arrives through their own
     * spawn task frames): the row's
     * args/result carry the identity, task prompt, lifecycle status and result
     * summary — the store resolves the sub-agent through its link registry or the
     * result's `agent_id:` marker, healing existing records per its precedence
     * table. Then the store's links re-drive onto the rows (a row the link
     * predates — e.g. one a history page just loaded — gains its open affordance).
     * Idempotent and cheap — such rows are few. */
    private fun mineAgentRowsIntoStore() {
        timeline.items()
            .flatMap { (it as? ChatItem.ToolGroup)?.tools.orEmpty() }
            .filter { it.name == AGENT_TOOL_NAME || it.name == AGENT_SWARM_TOOL_NAME }
            .forEach { row ->
                subagentStore.applyAgentCall(
                    sessionId,
                    toolCallId = row.id,
                    toolName = row.name,
                    argsJson = row.input,
                    resultText = row.output.ifBlank { null },
                    rowStatus = row.status,
                )
            }
        applySubagentLinks()
    }

    /** One event applied+published on its own — the [applySessionEvent] path for
     * timeline-bound events. Deltas never come through here: the coalescer applies
     * them via its own sink. */
    private fun applyTimelineEvent(event: SessionEvent) {
        timeline.apply(event)
        publishTimeline()
    }

    private suspend fun applySessionEvent(event: SessionEvent) {
        when (event) {
            is SessionEvent.TurnStarted -> {
                turnSeedGuard.bump()
                timeline.apply(event)
                uiState.update { it.copy(isWorking = true, stepRetry = null, items = timeline.items()) }
            }
            is SessionEvent.TurnEnded -> {
                turnSeedGuard.bump()
                // Pending approvals are NOT cleared here (ticket 171): an abort ends them
                // with their own `cancelled` interaction frame, and a background
                // sub-agent's approval outlives the main turn. Pending questions stay too
                // (ticket 172): an abort ends them with an `outcome:"dismissed"` frame,
                // and a background question outlives its turn.
                // The early-stop footer (ticket 155) lands now; the refetch below
                // re-appends it over the canonical rows.
                timeline.onTurnEnded(event.interruptReason, event.errorMessage)
                runningTools.clear()
                uiState.update {
                    it.copy(
                        isWorking = false,
                        stepRetry = null,
                        // The idle `agent` frame follows the turn's ops; one still
                        // running (background work) re-opens the line.
                        agentActiveSince = null,
                        runningTool = null,
                        items = timeline.items(),
                    )
                }
                // Replace live rows with the canonical committed turn.
                refreshHistory(clearLive = true)
            }
            // LLM retry (ticket 058): a transient status line on the in-flight turn —
            // the retrying frame opens it, the replacement step.started (new stepId),
            // the step's completion, or a turn end closes it. Never a timeline row.
            is SessionEvent.StepRetrying ->
                uiState.update {
                    it.copy(stepRetry = StepRetry(event.nextAttempt, event.maxAttempts, event.errorMessage))
                }
            is SessionEvent.StepStarted, is SessionEvent.StepCompleted ->
                uiState.update { it.copy(stepRetry = null) }
            // The subscribe's watermark could not be covered (ticket 176): the socket
            // reset the fold to the new baseline — rebuild the rows from REST rather
            // than render a partial replay.
            is SessionEvent.TranscriptStale -> rebuildFromTranscript()
            is SessionEvent.ApprovalRequested -> {
                approvalsSeedGuard.bump()
                val approval = event.toPendingApproval()
                uiState.update {
                    // Deduped by interaction id, in place: the post-ack re-send of a
                    // request the reset already queued must neither double it nor move
                    // it behind newer ones.
                    val queue = it.pendingApprovals
                    it.copy(
                        pendingApprovals = if (queue.any { a -> a.approvalId == event.approvalId }) {
                            queue.map { a -> if (a.approvalId == event.approvalId) approval else a }
                        } else {
                            queue + approval
                        },
                    )
                }
            }
            // An accepted subscribe (ticket 171): the queue becomes the reset snapshots'
            // pending approvals before the server's re-sent pending frames land.
            is SessionEvent.ApprovalsReset -> {
                approvalsSeedGuard.bump()
                uiState.update {
                    it.copy(
                        pendingApprovals = event.pending
                            .map { a -> a.toPendingApproval() }
                            .distinctBy { a -> a.approvalId },
                    )
                }
            }
            is SessionEvent.ApprovalResolved -> {
                approvalsSeedGuard.bump()
                uiState.update {
                    it.copy(pendingApprovals = it.pendingApprovals.filterNot { a -> a.approvalId == event.approvalId })
                }
            }
            // Question queue, drafts and wizard step: the collaborator owns the whole
            // lifecycle, including the seed guard's bump (ticket 072).
            is SessionEvent.QuestionRequested -> questions.onRequested(event)
            is SessionEvent.QuestionResolved -> questions.onResolved(event)
            is SessionEvent.QuestionsReset -> questions.onReset(event)
            // Background tasks (tickets 092/179): the collaborator owns the list,
            // including the frame → job mapping. A main-owned subagent-kind task is
            // also the spawn record of the child it names (ticket 182): the store
            // creates/updates the record (task id, row link, background flag,
            // model/effort, status). The registry it just learned then re-drives onto
            // the `Agent` rows, and the rows' args (subagent type, task prompt) mine
            // into the fresh record. The funnel no-ops on shell/other tasks.
            is SessionEvent.TaskListed -> {
                jobsStore.onListed(event.tasks)
                subagentStore.apply(sessionId, event)
                mineAgentRowsIntoStore()
            }
            is SessionEvent.TaskUpdated -> {
                jobsStore.onUpdated(event.task)
                subagentStore.apply(sessionId, event)
                mineAgentRowsIntoStore()
            }
            // The `todo` frame (ticket 178): the full list, applied wholesale — `[]`
            // clears the widget. The subscribe's first one is the cold-start seed; an
            // undo that rewinds a TodoList call re-sends the restored list.
            is SessionEvent.TodoUpdated ->
                uiState.update { it.copy(todos = event.items) }
            // The `usage` frame (ticket 177): the subscribe's first one seeds the ring,
            // later ones grow it live. A frame without a usable limit never gets here.
            is SessionEvent.ContextUsageUpdated ->
                uiState.update { it.copy(contextUsage = event.contextUsage) }
            // The `agent` frame's status (ticket 177): gates the derived status line.
            // A re-sent `running` (every re-subscribe) keeps the stretch's start.
            is SessionEvent.AgentBusyUpdated ->
                if (event.running) {
                    uiState.update { it.copy(agentActiveSince = it.agentActiveSince ?: Instant.now()) }
                } else {
                    runningTools.clear()
                    uiState.update { it.copy(agentActiveSince = null, runningTool = null) }
                    settleDroppedCompaction()
                }
            // The global busy push for this session (ticket 181): only its idle edge
            // matters here — it ends a compaction whose completed marker never came.
            is SessionEvent.SessionWorkChanged ->
                if (!event.busy) settleDroppedCompaction()
            // The `agent` frame's binding (ticket 177, after 107): the first frame is the
            // seed, later ones follow bindings made from any client.
            is SessionEvent.SessionModelUpdated ->
                syncServerSessionModel(event.model, event.thinkingEffort)
            // The `agent` frame's `permission_mode` (ticket 177, after 165): re-sent the
            // moment the mode changes, including a profile write from another client.
            // A stored pick stays displayed and the per-prompt stamp re-asserts it
            // (ticket 141); the store read is the recreation-proof half of that guard.
            is SessionEvent.SessionPermissionUpdated ->
                syncServerSessionPermission(event.permission, chatRepository.getSessionPermission(sessionId))
            // The `agent` frame's `swarm_mode` (ticket 174): the first frame seeds the
            // chip, later ones follow the server — the change a sent prompt applied or
            // a profile write from another client.
            is SessionEvent.SessionSwarmModeUpdated ->
                if (!pendingSwarmPick) uiState.update { it.copy(swarmMode = event.swarmMode) }
            // Compaction (ticket 056; transcript markers since ticket 181): no turn ops
            // fire for it — the `compaction` markers alone drive the divider and the
            // compacting state, the `undo` marker between them is a plain splice (kept
            // messages dedup, the summary is dropped: this divider owns the trace).
            // Started also arrives for compactions triggered from another client (the
            // TUI's /compact), so it — not only our POST ack — sets isCompacting.
            is SessionEvent.CompactionStarted -> {
                timeline.compactionStarted()
                uiState.update { it.copy(isCompacting = true, items = timeline.items()) }
            }
            is SessionEvent.CompactionCompleted -> {
                timeline.compactionCompleted(event.summary, event.tokensBefore, event.tokensAfter)
                uiState.update { it.copy(isCompacting = false, items = timeline.items()) }
                _effects.send(
                    Effect.CompactionFinished(
                        "Context compacted: ${formatTokenCount(event.tokensBefore)} → " +
                            "${formatTokenCount(event.tokensAfter)} tokens",
                    ),
                )
            }
            is SessionEvent.ToolCallStarted -> {
                // First sight only: a re-emit (args/display now known) keeps the start.
                runningTools.getOrPut(event.toolCallId) { RunningTool(event.name, Instant.now()) }
                applyTimelineEvent(event)
                uiState.update { it.copy(runningTool = runningTools.values.lastOrNull()) }
            }
            is SessionEvent.ToolCallResult -> {
                if (runningTools.remove(event.toolCallId) != null && runningTools.isEmpty()) {
                    // The agent is back to thinking: the line's elapsed restarts.
                    uiState.update { it.copy(agentActiveSince = it.agentActiveSince?.let { Instant.now() }) }
                }
                uiState.update { it.copy(runningTool = runningTools.values.lastOrNull()) }
                applyTimelineEvent(event)
                // A background Agent result carries the agent_id marker mid-run: mine
                // right away so the page can open and later frames stop dropping.
                mineAgentRowsIntoStore()
            }
            else -> applyTimelineEvent(event)
        }
    }

    /** Newest-page history refetch (the turn-end commit): the shared pager fetches and
     * merges it, latest-wins. [clearLive] is this
     * screen's polarity of the pager's keepLive: a finished turn has no legitimate
     * live rows, a running one keeps its tail. */
    private fun refreshHistory(clearLive: Boolean) {
        historyPager.refresh(keepLive = !clearLive)
    }

    /** Scroll-to-top trigger (ticket 043): the shared pager fetches the next older
     * page (`before_id` = oldest loaded message) and prepends it, deduplicated by
     * message id in the timeline. */
    fun onHistoryTopReached() = historyPager.loadOlder()

    /**
     * A model/effort picked here is stored at once but only reaches the server with the
     * next prompt: until that send succeeds, server-reported bindings (the ticket 107
     * write-through) still describe the PREVIOUS prompt and must not clobber the pick.
     */
    private var pendingModelPick = false
    private var pendingEffortPick = false

    /** Same lifecycle as [pendingModelPick], for the permission mode (ticket 141). The
     * seed sync additionally checks the store itself (see [syncServerSessionPermission])
     * because the server always reports a mode — unlike the model binding, a fresh
     * session's report would otherwise clobber an unsent pick after a recreation. */
    private var pendingPermissionPick = false

    /** An unsent swarm-chip toggle (ticket 174): the server learns it only from the
     * next prompt's `swarm_mode`, so until that send succeeds `agent` frames still
     * report the previous mode and must not flip the chip back. In memory only — a
     * recreated ViewModel re-seeds from the subscribe's `agent` frame, which mirrors
     * the server. */
    private var pendingSwarmPick = false

    /**
     * Loads the model picker's data (ticket 031): the server's model list, its default,
     * and this session's stored choice. Lazy — first chat-screen open, cached in the
     * repository afterwards. A failed models fetch hides the chip and changes nothing
     * else: sending keeps the 017 behavior and is never blocked by this feature.
     */
    private fun loadModels() {
        viewModelScope.launch {
            val settings = settingsRepository.current()
            // The cloud switch policies (tickets 167/170) are fetched FIRST, so the
            // model sheet never shows a picker whose gates are still unknown — once
            // modelsAvailable flips, the rules and aliases are in place too. Failures
            // and null configs all mean "no gate": selection stays immediate, exactly
            // like a server without the feature.
            chatRepository.getServerConfig(settings).onSuccess { config ->
                uiState.update { it.copy(modelAliases = config.modelAliases) }
            }
            modelSwitchConfirm = chatRepository.getModelSwitchConfirm(settings).getOrNull()
            effortSwitchConfig = chatRepository.getEffortSwitchConfig(settings).getOrNull().also { config ->
                uiState.update { it.copy(effortDescriptions = config?.descriptions.orEmpty()) }
            }
            chatRepository.getModels(settings)
                .onSuccess { models ->
                    uiState.update {
                        it.copy(
                            availableModels = models,
                            modelsAvailable = true,
                            serverDefaultModel = chatRepository.getDefaultModel(settings),
                            // A server sync may have landed while the models fetch was
                            // in flight; its value is newer than this store read.
                            sessionModel = it.sessionModel ?: chatRepository.getSessionModel(sessionId),
                            sessionEffort = it.sessionEffort ?: chatRepository.getSessionEffort(sessionId),
                        )
                    }
                }
                .onFailure {
                    uiState.update { it.copy(modelsAvailable = false) }
                }
        }
    }

    /** The cloud switch policies fetched with the model data (tickets 167/170); null
     * means no gate — an unregistered name, an absent/disabled config, or a fetch or
     * decode failure. */
    private var modelSwitchConfirm: SwitchConfirm? = null
    private var effortSwitchConfig: EffortSwitchConfig? = null

    /**
     * The picker's model intent: commits immediately unless the cloud's
     * `model_switch_confirm` policy gates this switch (ticket 167) — a different
     * model, an idle chat, known aliases on both sides, and a matching enabled rule.
     * A gate only raises the confirmation dialog; [onModelSwitchConfirmed] is the
     * commit, [onModelSwitchDismissed] leaves every preference untouched.
     */
    fun onModelPicked(model: String) {
        val state = uiState.value
        val currentId = state.sessionModel ?: state.serverDefaultModel
        val rule = if (state.isWorking || model == currentId) {
            null
        } else {
            modelSwitchConfirm?.firstMatch(
                upstreamModelId(currentId),
                upstreamModelId(model),
                sessionAge(),
            )
        }
        if (rule == null) {
            onModelSelected(model)
            return
        }
        uiState.update {
            it.copy(
                pendingModelSwitch = PendingSwitch(
                    target = model,
                    fromLabel = state.currentModelLabel ?: currentId ?: "Current model",
                    toLabel = state.availableModels.firstOrNull { candidate -> candidate.model == model }
                        ?.displayName?.takeIf { label -> label.isNotBlank() } ?: model,
                    hint = rule.hint,
                    warning = rule.warning,
                ),
            )
        }
    }

    /** Continue on the model-switch dialog: the commit goes through the existing,
     * unchanged selection path. */
    fun onModelSwitchConfirmed() {
        val pending = uiState.value.pendingModelSwitch ?: return
        uiState.update { it.copy(pendingModelSwitch = null) }
        onModelSelected(pending.target)
    }

    fun onModelSwitchDismissed() {
        uiState.update { it.copy(pendingModelSwitch = null) }
    }

    /** The effort picker's intent (ticket 170): same gate shape as [onModelPicked],
     * over `effort_switch_confirm`'s rules and effort values. */
    fun onEffortPicked(effort: String) {
        val state = uiState.value
        val current = state.effectiveEffort
        val rule = if (state.isWorking || effort == current) {
            null
        } else {
            effortSwitchConfig?.confirm?.firstMatch(current, effort, sessionAge())
        }
        if (rule == null) {
            onEffortSelected(effort)
            return
        }
        uiState.update {
            it.copy(
                pendingEffortSwitch = PendingSwitch(
                    target = effort,
                    fromLabel = current ?: "Default",
                    toLabel = effort,
                    hint = rule.hint,
                    warning = rule.warning,
                ),
            )
        }
    }

    fun onEffortSwitchConfirmed() {
        val pending = uiState.value.pendingEffortSwitch ?: return
        uiState.update { it.copy(pendingEffortSwitch = null) }
        onEffortSelected(pending.target)
    }

    fun onEffortSwitchDismissed() {
        uiState.update { it.copy(pendingEffortSwitch = null) }
    }

    /** Upstream `old` once the conversation has at least one real user turn, `new`
     * otherwise — the client-config rules' session typing (tickets 167/170). The
     * gate only runs while idle, so an optimistic echo cannot be pending here. */
    private fun sessionAge(): SessionAge =
        if (uiState.value.items.orEmpty().any { it is ChatItem.UserMessage }) SessionAge.Old else SessionAge.New

    /** The upstream id a picker alias resolves to (ticket 167); unknown aliases mean
     * the switch cannot be matched and therefore stays immediate. */
    private fun upstreamModelId(alias: String?): String? =
        alias?.let { uiState.value.modelAliases[it] }

    /** Persists the picked model for this session; subsequent prompts carry it. */
    fun onModelSelected(model: String) {
        pendingModelPick = true
        uiState.update { it.copy(sessionModel = model) }
        viewModelScope.launch { chatRepository.setSessionModel(sessionId, model) }
    }

    /** Persists the picked effort for this session; subsequent prompts carry it. */
    fun onEffortSelected(effort: String) {
        pendingEffortPick = true
        uiState.update { it.copy(sessionEffort = effort) }
        viewModelScope.launch { chatRepository.setSessionEffort(sessionId, effort) }
    }

    /** Persists the picked permission mode for this session (ticket 141); subsequent
     * prompts carry the `permission_mode` stamp. */
    fun onPermissionModeSelected(mode: PermissionMode) {
        pendingPermissionPick = true
        uiState.update { it.copy(sessionPermission = mode.wire) }
        viewModelScope.launch { chatRepository.setSessionPermission(sessionId, mode.wire) }
    }

    /** Flips the composer's swarm chip (ticket 174). Nothing is written here: the
     * next prompt carries the new value as `swarm_mode` (no profile call). */
    fun onSwarmModeToggled() {
        pendingSwarmPick = true
        uiState.update { it.copy(swarmMode = !it.swarmMode) }
    }

    /**
     * The server owns the session's model/effort binding (ticket 107): what the `agent`
     * frame reports (ticket 177) is written through to the per-session store, so the
     * chip and the send path (both store-fed) follow bindings made from any client.
     * Null reports mean the server said nothing (fresh session) and never clobber; a
     * local pick awaiting its first prompt wins over a report (see [pendingModelPick]).
     */
    private fun syncServerSessionModel(model: String?, effort: String?) {
        if (model != null && !pendingModelPick) {
            uiState.update { it.copy(sessionModel = model) }
            viewModelScope.launch { chatRepository.setSessionModel(sessionId, model) }
        }
        if (effort != null && !pendingEffortPick) {
            uiState.update { it.copy(sessionEffort = effort) }
            viewModelScope.launch { chatRepository.setSessionEffort(sessionId, effort) }
        }
    }

    /**
     * The server's permission mode (ticket 141; the `agent` frame's `permission_mode`
     * since 177): the report feeds the chip but is NEVER written through to the
     * per-session store — unlike the model binding the
     * server always reports a mode (a fresh session reads the server default), and a
     * stored value stamps `permission_mode` on every prompt, so write-through would
     * turn "follow the server" sessions into stamped ones and pin the first value ever
     * seen. A local pick wins over the report: [pendingPermissionPick] covers the
     * in-flight window, [storedPick] (read from the store per frame) the recreation
     * case, where the in-memory flag was lost. The pick is sticky by design
     * — per-prompt stamping re-asserts it on every send (the session-profile endpoint
     * stays unused, see ticket 141).
     */
    private fun syncServerSessionPermission(permission: String?, storedPick: String?) {
        if (permission != null && !pendingPermissionPick && storedPick == null) {
            uiState.update { it.copy(sessionPermission = permission) }
        }
    }

    /**
     * Loads the session's title/cwd for the top bar. A failure (or a missing session)
     * just leaves the fields null: the UI keeps falling back to the raw session id.
     */
    private fun refreshSessionInfo() {
        viewModelScope.launch {
            sessionsRepository.getSession(settingsRepository.current(), sessionId)
                .onSuccess { session ->
                    uiState.update { it.copy(sessionTitle = session.displayName, sessionCwd = session.cwd) }
                }
        }
    }

    /** Shows the stored permission pick (ticket 141) on every start: it displays even
     * before the subscribe's `agent` frame lands, and that frame's report then leaves
     * it in place (see [syncServerSessionPermission]) — pendingPermissionPick is
     * per-instance, the store is the record that survives recreation. */
    private fun seedStoredPermissionPick() {
        viewModelScope.launch {
            val storedPermissionPick = chatRepository.getSessionPermission(sessionId)
            if (storedPermissionPick != null) {
                uiState.update { it.copy(sessionPermission = storedPermissionPick) }
            }
        }
    }

    /**
     * The socket gate (ticket 045): the session socket is collected while the chat is
     * visible OR a sub-agent page of this session is open. While it runs, this
     * collection is the sub-agent store's feeder — the chatCollectionStarted/Stopped
     * pair (ticket 064) keeps the store's own keep-alive collection off, so exactly one
     * collection per session exists in every state combination (two concurrent ones
     * would double-feed the store on every cursor replay). The gate still outlives
     * navigation to a page, so chat → page → chat costs no reconnect. Gate flips
     * cancel/restart the collection (collectLatest), matching the old explicit
     * start/cancel for the chat-only case.
     *
     * The FIRST opening awaits the session's transcript cold start (ticket 176) before
     * subscribing: the cold-start apply IS the initial load, and the seeded resume
     * watermark makes the first subscribe replay from exactly the fetched transcript
     * page (`transcript_since`), gapless. The chat claims the feeder role BEFORE the
     * cold start: the store's keep-alive must not keep folding the session socket while
     * this chat rebuilds its rows.
     *
     * Started by the first [onScreenStarted], never during construction: with a page
     * already open (the restored-page hand-over) the gate opens at once and, on
     * Main.immediate, runs its cold start inline — before a property initializer
     * declared after a constructor-time start would have run.
     */
    private val socketGate: CoroutineJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
        combine(screenVisible, subagentStore.openPageCount(sessionId)) { visible, openPages ->
            visible || openPages > 0
        }.distinctUntilChanged()
            .collectLatest { holdSocket ->
                if (holdSocket) {
                    subagentStore.chatCollectionStarted(sessionId)
                    try {
                        ensureColdStart().join()
                        collectSessionSocket()
                    } finally {
                        subagentStore.chatCollectionStopped(sessionId)
                    }
                }
            }
    }
}
