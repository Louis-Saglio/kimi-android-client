package fr.louissaglio.kimi.android.ui.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.attachments.AttachmentStore
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.data.chat.AGENT_SWARM_TOOL_NAME
import fr.louissaglio.kimi.android.data.chat.AGENT_TOOL_NAME
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.ContextUsage
import fr.louissaglio.kimi.android.data.chat.MAIN_AGENT_ID
import fr.louissaglio.kimi.android.data.chat.MessagePage
import fr.louissaglio.kimi.android.data.chat.PendingQuestion
import fr.louissaglio.kimi.android.data.chat.ResolvedQuestion
import fr.louissaglio.kimi.android.data.chat.ServerModel
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.SessionSnapshot
import fr.louissaglio.kimi.android.data.chat.SocketEvent
import fr.louissaglio.kimi.android.data.chat.Todo
import fr.louissaglio.kimi.android.data.chat.TodoParser
import fr.louissaglio.kimi.android.data.chat.isSubagentScoped
import fr.louissaglio.kimi.android.data.sessions.Session
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.sessions.UnreadSessionsStore
import fr.louissaglio.kimi.android.data.sessions.displayName
import fr.louissaglio.kimi.android.data.sessions.displayNameFor
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.di.StreamCoalesceWindowMillis
import fr.louissaglio.kimi.android.navigation.Chat
import fr.louissaglio.kimi.android.ui.common.formatTokenCount
import javax.inject.Inject
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

@HiltViewModel
class ChatViewModel internal constructor(
    private val chatRepository: ChatRepository,
    private val sessionsRepository: SessionsRepository,
    private val settingsRepository: SettingsRepository,
    private val attachmentStore: AttachmentStore,
    private val subagentStore: SubagentStreamStore,
    private val snapshotSync: SnapshotSync,
    private val routeArgs: Chat,
    private val todoParser: TodoParser,
    private val unreadStore: UnreadSessionsStore,
    // Default mirrors the DI value (AppModule) for direct construction in tests;
    // pass 0 to publish every delta immediately (the pre-conflation behavior).
    @StreamCoalesceWindowMillis
    private val streamCoalesceWindowMillis: Long = 100L,
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
        snapshotSync: SnapshotSync,
        savedStateHandle: SavedStateHandle,
        todoParser: TodoParser,
        unreadStore: UnreadSessionsStore,
        @StreamCoalesceWindowMillis streamCoalesceWindowMillis: Long,
    ) : this(
        chatRepository,
        sessionsRepository,
        settingsRepository,
        attachmentStore,
        subagentStore,
        snapshotSync,
        savedStateHandle.toRoute<Chat>(),
        todoParser,
        unreadStore,
        streamCoalesceWindowMillis,
    )

    private val sessionId: String = routeArgs.sessionId

    // Optional navigation arguments from the session list: they let the top bar show
    // the right label immediately, before [refreshSessionInfo] confirms it.
    private val seedTitle: String? = routeArgs.title
    private val seedCwd: String? = routeArgs.cwd

    /** One pending permission request, surfaced as the approval card above the input row.
     * [agentId] is the asking agent (ticket 139): "main", or the sub-agent's id — the
     * card badges itself with the member's name from [UiState.subagentNames]. */
    data class PendingApproval(
        val approvalId: String,
        val toolName: String,
        val summary: String,
        val agentId: String = MAIN_AGENT_ID,
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
        /** The session's permission mode wire value (manual/yolo/auto — ticket 141):
         * the local pick (persisted), else the /status-seeded server report; null until
         * either lands — the composer's permission chip stays hidden meanwhile. */
        val sessionPermission: String? = null,
        /** Context window fill for the composer's ring (041, moved into the composer
         * in 053); null until the first read carrying a real fill arrives — the
         * snapshot (real usage since 0.38.0), the /status seed, or a live
         * agent.status.updated frame. */
        val contextUsage: ContextUsage? = null,
        /** The agent's latest todo list, mirrored from its TodoList tool calls
         * (ticket 042); null or empty hides the widget above the composer. */
        val todos: List<Todo>? = null,
        /** Background tasks of the session (ticket 092): background sub-agents and
         * bash, fed by the JobsStore collaborator (REST seed + task.* events). The
         * pill above the composer counts the Running ones; terminal jobs linger for
         * the sheet. */
        val jobs: List<Job> = emptyList(),
        /** toolCallId → linked member states for every linked call of the session
         * (ticket 134; the member STATES since 135 — the swarm card's aggregate
         * reads them), live from the store's registry; the swarm card reads its own
         * id and only renders while the list is non-empty. */
        val swarmMembers: Map<String, List<SubagentStreamStore.SubagentState>> = emptyMap(),
        /** subagentId → display name for the approval card's sub-agent badge
         * (ticket 139), live from the store; a request whose record has not landed
         * yet is absent — the card falls back to a plain "Sub-agent" label. */
        val subagentNames: Map<String, String> = emptyMap(),
        /** True while older history exists (scroll-to-top loads it, ticket 043). */
        val hasMoreHistory: Boolean = false,
        /** True while an older history page loads; the list shows a header spinner. */
        val isLoadingMoreHistory: Boolean = false,
        /** True while a compaction runs (ticket 056): set optimistically on the POST
         * ack, cleared by compaction.completed/failed. Gates the menu item — a second
         * compact is a silent no-op server-side, so the client must prevent it. */
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

        /** The permission mode the composer chip shows (ticket 141); an unknown wire
         * value (or none yet) hides the chip. */
        val currentPermissionMode: PermissionMode?
            get() = PermissionMode.fromWire(sessionPermission)
    }

    sealed interface Effect {
        /** Snackbar text — the collector's catch-all branch shows it; the two special
         * cases ([SendFailed]'s Retry, [OpenFile]) handle themselves first (ticket 071). */
        val message: String

        data class RefreshFailed(override val message: String) : Effect

        /** The typed text stays in [UiState.inputText]; the snackbar's Retry re-calls [onSend]. */
        data class SendFailed(override val message: String) : Effect

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
         * message) or a compaction ended with failed/cancelled/blocked. */
        data class CompactionFailed(override val message: String) : Effect

        /** The task-cancel POST failed (ticket 092): the optimistic flip was reverted,
         * the job is still running. */
        data class JobCancelFailed(override val message: String) : Effect

        /** The fork POST failed (ticket 127 — unknown id 40401, busy source 40901):
         * the server's own message; the user stays on the source session. */
        data class ForkFailed(override val message: String) : Effect

        /** Fork succeeded (ticket 127): the collector navigates to the fork's chat. */
        data class SessionForked(val session: Session) : Effect {
            // Never shown: the collector's SessionForked branch navigates instead.
            override val message: String get() = session.displayName
        }
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

    /** The jobs widget's store (ticket 092): background-task list (REST seed +
     * task.* events), task cancellation. Same logic-holder contract as [questions].
     * The raw seed also feeds the sub-agent store (ticket 135): a main-owned
     * subagent-kind entry's cancelled status and model/effort pair heal the member
     * record — the background member's only cold-start carrier, being excluded
     * from the snapshot roster. */
    private val jobsStore = JobsStore(
        scope = { viewModelScope },
        chatRepository = chatRepository,
        settingsRepository = settingsRepository,
        sessionId = sessionId,
        state = { uiState.value },
        updateState = { transform -> uiState.update(transform) },
        sendEffect = { effect -> _effects.send(effect) },
        onTasksSeed = { tasks -> subagentStore.applyTaskList(sessionId, tasks) },
    )

    private val timeline = ChatTimeline(todoParser)

    /** The `before_id` cursor of the next older page (ticket 043): the oldest loaded
     * history message's id, tracked ViewModel-side like ticket 010 — the timeline rows
     * can't provide it (tool rows carry tool-call ids, and a message can flatten to no
     * row). Reset by every newest-page refetch. */
    private var oldestHistoryId: String? = null

    /** Screen visibility as state — one input of the socket gate (see init). */
    private val screenVisible = MutableStateFlow(false)

    init {
        // The socket gate (ticket 045): the session socket is collected while the chat
        // is visible OR a sub-agent page of this session is open. While it runs, this
        // collection is the sub-agent store's feeder — the chatCollectionStarted/
        // Stopped pair (ticket 064) keeps the store's own keep-alive collection off,
        // so exactly one collection per session exists in every state combination (two
        // concurrent ones would double-feed the store on every cursor replay). The
        // gate still outlives navigation to a page, so chat → page → chat costs no
        // reconnect. Gate flips cancel/restart the collection (collectLatest),
        // matching the old explicit start/cancel for the chat-only case.
        //
        // The FIRST opening awaits the session's snapshot cold start (ticket 085)
        // before subscribing: the snapshot apply IS the initial load (no legacy burst
        // of /messages+/status+/questions), and the seeded resume cursor makes the
        // first subscribe stitch gaplessly onto the snapshot's watermark.
        viewModelScope.launch {
            combine(screenVisible, subagentStore.openPageCount(sessionId)) { visible, openPages ->
                visible || openPages > 0
            }.distinctUntilChanged()
                .collectLatest { holdSocket ->
                    if (holdSocket) {
                        ensureColdStart().join()
                        subagentStore.chatCollectionStarted(sessionId)
                        try {
                            collectSessionSocket()
                        } finally {
                            subagentStore.chatCollectionStopped(sessionId)
                        }
                    }
                }
        }
        // The swarm cards' aggregate input (ticket 135): the store's per-call member
        // lists, live — every member status flip republishes them, so the card's bar
        // and counts advance mid-swarm.
        viewModelScope.launch {
            subagentStore.swarmMembersByCall(sessionId).collect { byCall ->
                uiState.update { it.copy(swarmMembers = byCall) }
            }
        }
        // The approval card's sub-agent badge names (ticket 139): live from the store —
        // a member's spawned frame can land AFTER its approval request, and the badge
        // gains its name the moment the record exists.
        viewModelScope.launch {
            subagentStore.displayNames(sessionId).collect { names ->
                uiState.update { it.copy(subagentNames = names) }
            }
        }
    }

    /**
     * The cold-start snapshot sync of this ViewModel instance (ticket 085): launched
     * by the gate's first opening (and re-launched by [onRetry] after a failure),
     * joined by every opening so the first subscribe always runs after the cursor
     * seed. The job lives on viewModelScope (not the gate coroutine): a gate flip
     * mid-sync cancels only the join, never the sync.
     */
    private var coldStartJob: CoroutineJob? = null

    /** True once a cold-start sync completed (fresh apply or already-fresh join) —
     * the gate from which [onScreenStarted] runs the within-process resume set. */
    private var coldStartDone = false

    private fun ensureColdStart(): CoroutineJob {
        val existing = coldStartJob
        if (existing != null && (existing.isActive || coldStartDone)) return existing
        return viewModelScope.launch { runColdStart() }.also { coldStartJob = it }
    }

    /**
     * The one cold start of this chat open: awaits the session's snapshot sync and
     * applies its outcome. A [SnapshotSync.Outcome.Fresh] bundle IS the initial load
     * (history, busy state, pending approvals/questions — a /status re-read follows
     * for the session's bound model/effort, ticket 107; the context ring seeds from
     * the bundle itself, ticket 161); an
     * [SnapshotSync.Outcome.AlreadyFresh] join means the session synced since it was
     * last left (page-on-top restore, or the chat → page → chat round trip) — the
     * within-process resume set (ticket 048) heals instead, and the sub-agent row
     * links re-drive from the store's surviving registry (ticket 091). A failed
     * fetch surfaces the ordinary load-error state — no fallback to the legacy burst
     * (the app runs against the latest server only).
     */
    private suspend fun runColdStart() {
        val guards = captureSeedGuards()
        val outcome = snapshotSync.sync(sessionId).getOrElse { error ->
            if (uiState.value.items.isNullOrEmpty()) {
                uiState.update { it.copy(error = error.toUserMessage()) }
            } else {
                _effects.send(Effect.RefreshFailed(error.toUserMessage()))
            }
            return
        }
        when (outcome) {
            is SnapshotSync.Outcome.Fresh -> applySnapshot(outcome.snapshot, guards)
            is SnapshotSync.Outcome.AlreadyFresh -> {
                // The store kept its records and the session's link registry — only
                // this ViewModel's timeline died with it: re-drive the links.
                applySubagentLinks()
                resyncHistoryAfterDrop()
                questions.seed()
                seedSessionStatus()
            }
        }
        // Background tasks (ticket 092): REST is only the cold-start sync — the
        // socket's task.* events feed the list from here on.
        jobsStore.seed()
        coldStartDone = true
    }

    /** The guard captures for a snapshot apply racing the live stream (the socket is
     * open on the resync/retry paths): an event landing mid-fetch is newer than the
     * snapshot, so its tracked field skips the stale apply. */
    private data class SeedCaptures(
        val turn: Int,
        val contextUsage: Int,
        val approvals: Int,
        val questions: Int,
    )

    private fun captureSeedGuards() = SeedCaptures(
        turn = turnSeedGuard.capture(),
        contextUsage = contextUsageSeedGuard.capture(),
        approvals = approvalsSeedGuard.capture(),
        questions = questions.captureEvents(),
    )

    /**
     * Applies the chat-side snapshot bundle (ticket 085): the cold-start /
     * `resync_required` rebuild. The live tail survives per the snapshot's busy (the
     * same polarity as ticket 048's status check — a finished turn has no legitimate
     * live rows); a turn event landing mid-fetch forces keep-live (it is newer than
     * the snapshot). The in-flight turn's pre-watermark tail seeds from the bundle
     * (ticket 144) — the resume-cursor replay only covers frames after the watermark.
     * The roster and the snapshot's `Agent` calls already reached the
     * store inside [SnapshotSync]'s fetch — here the rebuilt timeline's rows are
     * mined and the store's links re-driven onto them (ticket 091).
     */
    private fun applySnapshot(snapshot: SessionSnapshot, guards: SeedCaptures) {
        val turnStale = turnSeedGuard.isStale(guards.turn)
        // Any coalesced text must apply BEFORE the replace wipes/dedups the live rows
        // (ticket 062; mirrors the history pager's applyNewest).
        deltaCoalescer.flush()
        val keepLive = snapshot.busy || turnStale
        timeline.replaceHistory(snapshot.messages.items, keepLive = keepLive)
        // The in-flight turn's pre-watermark tail (ticket 144): the resume-cursor
        // replay covers only frames AFTER the snapshot's watermark, so without this
        // seed a cold open of a busy session shows a gap between the last committed
        // message and the live stream.
        if (keepLive) snapshot.inFlightTurn?.let(timeline::seedInFlight)
        oldestHistoryId = snapshot.messages.items.firstOrNull()?.id
        mineAgentRowsIntoStore()
        uiState.update {
            it.copy(
                items = timeline.items(),
                todos = timeline.todos(),
                error = null,
                hasMoreHistory = snapshot.messages.hasMore,
                isWorking = if (!turnStale) snapshot.busy else it.isWorking,
                contextUsage =
                    if (!contextUsageSeedGuard.isStale(guards.contextUsage) && snapshot.contextUsage != null) {
                        snapshot.contextUsage
                    } else {
                        it.contextUsage
                    },
                // The snapshot is the first REST read approvals ever had: it REPLACES
                // the queue (a decision missed with the stream heals here), unless a
                // live approval event overtook the fetch.
                pendingApprovals =
                    if (!approvalsSeedGuard.isStale(guards.approvals)) {
                        snapshot.pendingApprovals
                            .map { a -> PendingApproval(a.approvalId, a.toolName, a.summary, a.agentId) }
                            .distinctBy { a -> a.approvalId }
                    } else {
                        it.pendingApprovals
                    },
            )
        }
        questions.applySnapshot(snapshot.pendingQuestions, guards.questions)
        // /status re-read on EVERY cold start: the session's bound model/effort
        // (ticket 107) is served only there. The ring no longer waits on it — the
        // bundle's real usage seeds it directly (ticket 161); the seed's guard
        // drops the late response when a live usage frame overtakes it.
        seedSessionStatus()
        // The subscribe resuming exactly at this watermark needs no resync — this
        // apply already healed everything up to it.
        appliedSnapshotCursor = snapshot.asOfSeq to snapshot.epoch
    }

    /** Re-drives the store's session link registry (ticket 091 — spawned / roster /
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

    /** Bumped on every turn.started/turn.ended event, so [seedSessionStatus] and
     * [resyncHistoryAfterDrop] can drop a response a newer turn event has overtaken. */
    private val turnSeedGuard = SeedGuard()

    /** Bumped on every live context-usage event, so [seedSessionStatus] can drop a
     * /status snapshot a newer agent.status.updated frame has already overtaken. */
    private val contextUsageSeedGuard = SeedGuard()

    /** Bumped on every approval event, so a snapshot apply (ticket 085) racing the
     * live stream drops its stale queue replace. */
    private val approvalsSeedGuard = SeedGuard()

    /** The watermark of the last applied snapshot (ticket 085): a [SocketEvent.Reconnected]
     * whose resume cursor matches it needs no resync — the snapshot apply already
     * healed everything up to that point. */
    private var appliedSnapshotCursor: Pair<Long, String>? = null

    /** Publishes the timeline's current rows+todos — the coalescer's publish point
     * and [applySessionEvent]'s for timeline-bound events. */
    private fun publishTimeline() {
        uiState.update { it.copy(items = timeline.items(), todos = timeline.todos()) }
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
                    todos = timeline.todos(),
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

    /** Wired to ON_START. The FIRST start of this ViewModel lets the gate's cold-start
     * snapshot sync do the initial load (ticket 085) — none of the legacy burst runs
     * here. Later starts are the within-process resume (ticket 048): a status-aware
     * history re-sync (a turn that ended while the app was away takes its ghost live
     * rows with it), the questions seed and the status seed; the socket gate opens the
     * socket. Also fires when the app returns to the foreground. */
    fun onScreenStarted() {
        screenVisible.value = true
        // The web clears a session's unread marker when it becomes the visible session
        // (ticket 145 backstop: a process-death restore lands here without a list tap).
        viewModelScope.launch { unreadStore.clear(settingsRepository.current().serverUrl, sessionId) }
        if (coldStartDone) {
            resyncHistoryAfterDrop()
            questions.seed()
            seedSessionStatus()
        }
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
        // A failed cold start retries the snapshot sync (the gate is already open —
        // this is the only re-trigger); never the legacy burst.
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
            val result = if (state.isWorking) {
                chatRepository.steer(settings, sessionId, text, uploaded)
            } else {
                chatRepository.sendPrompt(settings, sessionId, text, uploaded)
            }
            result
                .onSuccess {
                    // The server bound the session to the sent model/effort/permission
                    // mode: its reports describe the pick from here on and may sync again.
                    pendingModelPick = false
                    pendingEffortPick = false
                    pendingPermissionPick = false
                    // No local echo: the user message arrives inline in the socket's
                    // context.spliced frame (docs/server-api.md), the reply via the
                    // streaming events.
                    uiState.update {
                        it.copy(inputText = "", attachments = emptyList(), isSending = false, isWorking = true)
                    }
                }
                .onFailure { error ->
                    uiState.update { it.copy(isSending = false) }
                    _effects.send(Effect.SendFailed(error.toUserMessage()))
                }
        }
    }

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

    /**
     * POSTs `:compact` (ticket 056). No-ops while a turn runs or a compaction is in
     * progress — the server rejects the former (40910) and silently no-ops the latter,
     * so the guard is the only feedback; the menu item is disabled in both states too.
     * On the ack the compacting state is set optimistically; the socket's
     * compaction.started/completed frames drive the divider and clear it. A failure
     * (empty session, web-started turn) surfaces the server's own message.
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
     * approval.resolved event — for our own decisions too — so a failed POST leaves the
     * card up and only reports the error. */
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

    /** Cancels one running background job; the server's task.terminated confirms it. */
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
                    // Sub-agent frames — transcript frames tagged with their own
                    // agentId, and subagent.* lifecycle — route to the store
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
                            is SocketEvent.Reconnected -> {
                                uiState.update { it.copy(isReconnecting = false) }
                                val applied = appliedSnapshotCursor
                                if (applied != null && applied.first == event.seq && applied.second == event.epoch) {
                                    // The subscribe resumed exactly at the applied
                                    // snapshot's watermark (ticket 085): the apply
                                    // already healed everything up to it — the 048
                                    // resync would be a redundant burst.
                                } else {
                                    // The cursor resume replays missed events; the
                                    // status-aware resync heals whatever the replay cannot
                                    // cover (ticket 048). It also runs here — not only on
                                    // ON_START — so a keep-live refetch can never race and
                                    // clobber the ON_START resync's clear-live decision.
                                    resyncHistoryAfterDrop()
                                    questions.refresh()
                                }
                            }
                            SocketEvent.ResyncRequired -> {
                                // No replay will come: the missed window (a turn.ended
                                // above all) heals from a fresh snapshot (ticket 085) —
                                // the socket connection stays open, subsequent live
                                // frames carry fresh cursors.
                                resyncFromSnapshot()
                            }
                            SocketEvent.Disconnected ->
                                uiState.update { it.copy(isReconnecting = true) }
                            // No-op: the history load validates the URL first and owns the
                            // user-facing error.
                            is SocketEvent.Failed -> Unit
                            is SocketEvent.Event -> Unit // handled above via the coalescer
                        }
                    }
                }
            }
    }

    /** Routes one sub-agent-scoped event into the store (the funnel registers the
     * spawn's row link in the session registry, ticket 091); a spawn then re-drives
     * the registry onto the timeline's `Agent` rows. */
    private suspend fun onSubagentEvent(event: SessionEvent) {
        subagentStore.apply(sessionId, event)
        if (event is SessionEvent.SubagentSpawned) applySubagentLinks()
    }

    /** Mines the timeline's `Agent`/`AgentSwarm` rows into the store (ticket 091;
     * AgentSwarm added in 134 — the store's swarm branch keeps the call's label and,
     * since 136, heals the member states from the settled call's
     * `<agent_swarm_result>`; the members' live state still arrives through their own
     * spawned/roster frames): the row's
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
                uiState.update { it.copy(isWorking = true, stepRetry = null, items = timeline.items(), todos = timeline.todos()) }
            }
            is SessionEvent.TurnEnded -> {
                turnSeedGuard.bump()
                // A turn that ends takes its pending approvals with it (e.g. abort) — but
                // not its questions: a pending question survives turn.ended and is
                // re-derived from the questions endpoint instead of being cleared here.
                uiState.update { it.copy(isWorking = false, stepRetry = null, pendingApprovals = emptyList()) }
                // Replace live rows with the canonical committed turn.
                refreshHistory(clearLive = true)
                questions.refresh()
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
            is SessionEvent.ApprovalRequested -> {
                approvalsSeedGuard.bump()
                uiState.update {
                    // Deduped: a reconnect replay must not queue the same request twice.
                    it.copy(
                        pendingApprovals = it.pendingApprovals
                            .filterNot { a -> a.approvalId == event.approvalId } +
                            PendingApproval(event.approvalId, event.toolName, event.summary, event.agentId),
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
            // Background tasks (ticket 092): the collaborator owns the list, including
            // the core→wire status mapping.
            is SessionEvent.TaskStarted -> {
                jobsStore.onStarted(event.info)
                // A main-owned agent-kind frame still concerns the store when its
                // info.agentId names a member (ticket 138): the detach's task.started
                // is the live carrier of the foreground → background conversion — the
                // record's runInBackground flips off it. The funnel no-ops on
                // everything else.
                subagentStore.apply(sessionId, event)
            }
            is SessionEvent.TaskTerminated -> {
                jobsStore.onTerminated(event.info)
                // A main-owned frame still concerns the store when its info.agentId
                // names a member (ticket 135): a deliberately killed member emits no
                // terminal subagent.* frame, so this is its only live cancelled
                // carrier. The funnel no-ops on everything else.
                subagentStore.apply(sessionId, event)
            }
            is SessionEvent.ContextUsageUpdated -> {
                contextUsageSeedGuard.bump()
                uiState.update { it.copy(contextUsage = event.contextUsage) }
            }
            // Same frame family, session-scope fields: the live model/effort binding.
            is SessionEvent.SessionModelUpdated ->
                syncServerSessionModel(event.model, event.thinkingEffort)
            // Compaction (ticket 056): no turn events fire for it — these frames alone
            // drive the divider and the compacting state. Started also arrives for
            // compactions triggered from another client (the TUI's /compact), so it —
            // not only our POST ack — sets isCompacting.
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
            is SessionEvent.CompactionFailed -> {
                timeline.compactionFailed()
                uiState.update { it.copy(isCompacting = false, items = timeline.items()) }
                _effects.send(Effect.CompactionFailed(event.cause ?: "Compaction failed"))
            }
            is SessionEvent.ToolCallResult -> {
                applyTimelineEvent(event)
                // A background Agent result carries the agent_id marker mid-run: mine
                // right away so the page can open and later frames stop dropping.
                mineAgentRowsIntoStore()
            }
            else -> applyTimelineEvent(event)
        }
    }

    /**
     * Re-sync after a connection gap (ON_START gate reopen, [SocketEvent.Reconnected],
     * [SocketEvent.ResyncRequired] — ticket 048): a turn may have ENDED while the
     * stream was away, and nothing says so when the replay cannot cover the gap (no
     * cursor, or a refused one). A finished turn can have no legitimate live rows, so
     * the history refetch clears them; a running turn keeps the keep-live refetch and
     * the replay fills the gap. A turn event landing while the /status GET is in
     * flight is newer than its snapshot (the [seedSessionStatus] pattern) and
     * downgrades the clear; a failed /status keeps the live rows too. ON_START and
     * the resumed socket's Reconnected fire together, so resyncs are latest-wins:
     * the newer call has the newer connection state (and the newer turn-event guard)
     * and cancels the superseded one before it spends a /status round trip.
     */
    private var resyncJob: CoroutineJob? = null

    private fun resyncHistoryAfterDrop() {
        val turnEventsAtStart = turnSeedGuard.capture()
        resyncJob?.cancel()
        resyncJob = viewModelScope.launch {
            val busy = chatRepository.getSessionStatus(settingsRepository.current(), sessionId).getOrNull()?.busy
            refreshHistory(clearLive = busy == false && !turnSeedGuard.isStale(turnEventsAtStart))
        }
    }

    /**
     * The `resync_required` heal (ticket 085): the server refused the resume cursor,
     * so the missed window is rebuilt from a fresh snapshot — the same apply as the
     * cold start, keep-live still decided by the snapshot's busy. Latest-wins with
     * [resyncHistoryAfterDrop] through the shared job lane. A failed fetch surfaces
     * like a failed history refetch (full-screen while empty, snackbar otherwise).
     */
    private fun resyncFromSnapshot() {
        val guards = captureSeedGuards()
        resyncJob?.cancel()
        resyncJob = viewModelScope.launch {
            snapshotSync.resync(sessionId)
                .onSuccess { applySnapshot(it, guards) }
                .onFailure { error ->
                    if (uiState.value.items.isNullOrEmpty()) {
                        uiState.update { it.copy(error = error.toUserMessage()) }
                    } else {
                        _effects.send(Effect.RefreshFailed(error.toUserMessage()))
                    }
                }
        }
        // No replay will come: task.* events missed in the gap heal from REST too.
        jobsStore.seed()
    }

    /** Newest-page history refetch (initial load, turn-end commit, resync — ticket
     * 048): the shared pager fetches and merges it, latest-wins. [clearLive] is this
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

    /**
     * The server owns the session's model/effort binding (ticket 107): what /status and
     * agent.status.updated report is written through to the per-session store, so the
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
     * The /status permission-mode seed (ticket 141): the report feeds the chip but is
     * NEVER written through to the per-session store — unlike the model binding the
     * server always reports a mode (a fresh session reads the server default), and a
     * stored value stamps `permission_mode` on every prompt, so write-through would
     * turn "follow the server" sessions into stamped ones and pin the first value ever
     * seen. A local pick wins over the report: [pendingPermissionPick] covers the
     * in-flight window, [storedPick] (read by the seed ahead of the GET) the
     * recreation case, where the in-memory flag was lost. The pick is sticky by design
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

    /** Seeds the working indicator when opening a session that is already mid-turn
     * (turn events only fire on transitions), the session's bound model/effort
     * (ticket 107 — served only by /status), and the composer's context-usage ring
     * (ticket 041), all from the same /status snapshot. Failure just leaves all
     * three untouched. A turn event or a live context-usage event landing while the
     * GET is in flight is newer than the seed's snapshot, so the late response is
     * dropped for that field instead of overriding it. */
    private fun seedSessionStatus() {
        val turnEventsAtSeed = turnSeedGuard.capture()
        val contextEventsAtSeed = contextUsageSeedGuard.capture()
        viewModelScope.launch {
            // The stored permission pick seeds the chip ahead of the GET (it shows
            // even when the /status read fails) and shields the server report from
            // clobbering an unsent pick — pendingPermissionPick is per-instance, the
            // store is the record that survives recreation (ticket 141).
            val storedPermissionPick = chatRepository.getSessionPermission(sessionId)
            if (storedPermissionPick != null) {
                uiState.update { it.copy(sessionPermission = storedPermissionPick) }
            }
            chatRepository.getSessionStatus(settingsRepository.current(), sessionId)
                .onSuccess { status ->
                    syncServerSessionModel(status.model, status.thinkingLevel)
                    syncServerSessionPermission(status.permission, storedPermissionPick)
                    uiState.update {
                        it.copy(
                            isWorking = if (!turnSeedGuard.isStale(turnEventsAtSeed)) status.busy else it.isWorking,
                            contextUsage =
                                if (!contextUsageSeedGuard.isStale(contextEventsAtSeed) && status.contextUsage != null) {
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
