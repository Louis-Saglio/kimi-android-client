package fr.louissaglio.kimi.android.ui.chat

import android.util.Log
import fr.louissaglio.kimi.android.data.chat.AGENT_SWARM_TOOL_NAME
import fr.louissaglio.kimi.android.data.chat.AGENT_TOOL_NAME
import fr.louissaglio.kimi.android.data.chat.AgentScopedEvent
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.MAIN_AGENT_ID
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.SocketEvent
import fr.louissaglio.kimi.android.data.chat.SubagentLifecycleEvent
import fr.louissaglio.kimi.android.data.chat.SubagentRosterEntry
import fr.louissaglio.kimi.android.data.chat.SubagentRosterPhase
import fr.louissaglio.kimi.android.data.chat.SwarmResultParser
import fr.louissaglio.kimi.android.data.chat.TaskOutcome
import fr.louissaglio.kimi.android.data.chat.TaskState
import fr.louissaglio.kimi.android.data.chat.Todo
import fr.louissaglio.kimi.android.data.chat.TranscriptPage
import fr.louissaglio.kimi.android.data.chat.TranscriptState
import fr.louissaglio.kimi.android.data.chat.TranscriptTurn
import fr.louissaglio.kimi.android.data.chat.agentNextStepOf
import fr.louissaglio.kimi.android.data.chat.agentResumeCallOf
import fr.louissaglio.kimi.android.data.chat.agentResumeHintOf
import fr.louissaglio.kimi.android.data.chat.bashDescriptionOf
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.di.StreamCoalesceWindowMillis
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Lifecycle of one sub-agent (docs/server-api.md, Sub-agents): Queued between spawned
 * and started, Working while its turn streams (also after a resume), Suspended/Completed/
 * Failed per the matching lifecycle frame. Cancelled (ticket 135) is a deliberate stop —
 * it arrives as `subagent.cancelled` (schema-level), as the member's ended `task` frame
 * with `outcome:"killed"` (ticket 179), or as the roster's `cancelled` phase — and
 * never counts as failed in the swarm aggregate (web parity). */
enum class SubagentStatus { Queued, Working, Suspended, Completed, Failed, Cancelled }

/**
 * Session-scoped store of every sub-agent's state (ticket 045, reimplemented in 091),
 * fed by exactly ONE session-socket collection at a time (ticket 064): the chat
 * screen's while its socket gate holds the session (signaled via [chatCollectionStarted] /
 * [chatCollectionStopped]), otherwise the store's own keep-alive collection while a
 * sub-agent page of the session is open ([pageOpened] / [pageClosed]). The keep-alive
 * ownership lives here — not in the chat's gate — because after process death with a
 * page on top the chat ViewModel is never recreated, so a gate-owned keep-alive would
 * never start and live streaming would be silently absent on the restored page. Two
 * concurrent collections would double-feed the store on every cursor replay, so the
 * keep-alive never runs while a chat collection does; [reconcileKeepAlive] runs on the
 * caller's thread (all entry points are Main-confined), so the keep-alive's cancel and
 * the chat collection's start can never interleave.
 *
 * The model (ticket 091, the root fix for the vanishing-prompt / lost-row-link bugs):
 *
 * 1. One [SubagentRecord] per sub-agent: metadata (identity, task prompt, row link,
 * status) are first-class FIELDS, never rows in a replaceable list — the prompt
 * survives the transcript fetch that replaces the timeline wholesale precisely
 * because it is not timeline data.
 * 2. Every source reaches the record through ONE funnel method per source kind
 * ([apply] for live frames, [applyRoster] for the session roster, [applyAgentCall]
 * for `Agent` call args/results from any carrier, [applyTranscriptPage]/
 * [prependTranscriptPage] for transcript pages). Each writes only the fields it is
 * authoritative for, per the precedence table on [SubagentRecord.statusSource]:
 * late canonical data HEALS instead of bouncing off a never-overwrite guard, and
 * re-applying the same source is a no-op by content, not by refusal.
 * 3. The session's `toolCallId → subagentIds` link registry lives here (not in a
 * per-ViewModel timeline): populated by the funnel (spawned / roster / result
 * marker), read by ChatViewModel via [linksFor] to re-drive the timeline's row
 * links after every store-affecting apply. One-to-many since ticket 134: every
 * member of an `AgentSwarm` call shares the call's toolCallId.
 *
 * Each record owns a [ChatTimeline] in non-history-backed mode (turn.started seals the
 * finished internal turn's live rows; ticket 046's transcript pages feed the committed
 * side via [applyTranscriptPage]/[prependTranscriptPage]) and a [DeltaCoalescer] — the
 * same streaming-delta conflation ChatViewModel delegates to (ticket 065): a
 * published state update costs a full MarkdownBody re-parse per sub-agent page, so
 * deltas publish at most once per window during a burst. All mutations are confined
 * to the Main dispatcher (ViewModel callers and the flush jobs alike), keeping the
 * reducer single-threaded.
 */
@Singleton
class SubagentStreamStore @Inject constructor(
    private val json: Json,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    /** The transcript cold-start owner (ticket 176, 085's role): the keep-alive
     * collection awaits the session's sync before its first subscribe — the roster
     * seed and the resume watermark land first, so a cold-restored page streams
     * gaplessly from frame one. [release] calls [TranscriptColdStart.forget]: the
     * session's next entry re-fetches instead of joining a process-lifetime cache of
     * mutable server state (091). */
    private val coldStart: TranscriptColdStart,
    // No default: an all-default @Inject constructor makes Kotlin generate a second,
    // no-arg synthetic constructor, which Dagger rejects (two injected constructors).
    @StreamCoalesceWindowMillis
    private val streamCoalesceWindowMillis: Long,
) {

    /** Immutable snapshot of one sub-agent's state, as rendered by the sub-agent page. */
    data class SubagentState(
        val id: String,
        /** The subagent type (e.g. "explore"), from subagent.spawned — or the roster /
         * Agent-call seed on a cold open (tickets 085/045). */
        val name: String,
        val description: String,
        val runInBackground: Boolean,
        val status: SubagentStatus,
        val resultSummary: String? = null,
        val error: String? = null,
        /** True while older transcript turns exist beyond the loaded ones (ticket 046). */
        val hasMoreTurns: Boolean = false,
        val items: List<ChatItem> = emptyList(),
        /** The sub-agent's own todo list: its agent-tagged `todo` frame, applied
         * wholesale like the chat's (ticket 178); null until the first frame. */
        val todos: List<Todo>? = null,
        /** The sub-agent's own background tasks (nested background bash/sub-agents),
         * fed live by its agent-tagged `task.list`/`task` frames (ticket 179) and
         * healed on a cold open by the transcript's `task` turn origins (ticket 099)
         * — an ended task is never re-listed. */
        val jobs: List<Job> = emptyList(),
        /** The member's position inside its `AgentSwarm` call (schema-only upstream —
         * ordering hint for the swarm roster, ticket 134); null for plain `Agent` spawns. */
        val swarmIndex: Int? = null,
        /** The member's task id (`agent-…`): the spawn task frame's (ticket 179 —
         * foreground members have one too) or the spawning call's; null from the
         * transcript roster, which carries no such field. */
        val taskId: String? = null,
        /** The run's bound model/effort (ticket 135): blank-filled from the spawn
         * task frame's `model`/`thinking` (ticket 179) and the roster; the swarm
         * aggregate's shared-model line reads them. */
        val model: String? = null,
        val thinkingEffort: String? = null,
        /** The live suspend cause (ticket 136): set by `subagent.suspended`, cleared
         * when the run resumes or settles. Null from every other source. */
        val suspendReason: String? = null,
        /** A backfilled `not_started` swarm member (ticket 136): it never spawned, so
         * no sub-agent page exists for it — the roster row is not clickable. */
        val ghost: Boolean = false,
        /** The `resume_hint:` block of the run's `Agent` result (ticket 158 —
         * 0.43.0 foreground results, 0.31.0 background stubs; docs/server-api.md,
         * Sub-agents): the orchestrator's continuation copy, verbatim. Null when
         * the result carried none — the page and the spawn row then render
         * exactly as before. */
        val resumeHint: String? = null,
        /** The `next_step:` block of a background spawn's result stub (ticket 158)
         * — what happens next automatically. Null on foreground results. */
        val nextStep: String? = null,
    ) {

        /** True while the swarm roster row offers its stop action (ticket 137): the
         * member still runs (Queued/Working/Suspended), its task id — the
         * `task.stop` target — is known, and it is
         * no ghost row (a backfilled `not_started` member runs nothing). Terminal
         * members and ghosts never show it. */
        val stoppable: Boolean
            get() = !ghost && taskId != null &&
                (
                    status == SubagentStatus.Queued ||
                        status == SubagentStatus.Working ||
                        status == SubagentStatus.Suspended
                    )

        /** True while the sub-agent page's overflow offers "To background" (ticket
         * 138): the member still runs IN THE FOREGROUND — a background run needs no
         * conversion — with its task id (the `task.background` target) known. Terminal
         * members, background members and ghosts never show it. */
        val detachable: Boolean
            get() = stoppable && !runInBackground

        /** The composer prefill behind the "Next step" section's Continue action
         * (ticket 158): the hint's suggested `Agent(resume="agent-N", prompt="…")`
         * call text, verbatim. Null when the hint carries no recognizable call —
         * the Continue action then never shows. */
        val resumePrefill: String?
            get() = resumeHint?.let(::agentResumeCallOf)

        /** The swarm roster row's one-line activity (ticket 136), null when the row
         * shows nothing: while Working, the last non-blank line of the live
         * timeline's last assistant text; while Suspended, the suspend cause; when
         * terminal, the first non-blank line of [resultSummary] (or of [error] —
         * a failed run's cause, a parsed result's stop_reason). */
        val activityLine: String?
            get() = when (status) {
                SubagentStatus.Working -> items.filterIsInstance<ChatItem.AssistantMessage>()
                    .lastOrNull { it.text.isNotBlank() }
                    ?.text?.lineSequence()?.lastOrNull { it.isNotBlank() }
                SubagentStatus.Suspended -> suspendReason?.takeIf { it.isNotBlank() }
                SubagentStatus.Completed, SubagentStatus.Failed, SubagentStatus.Cancelled ->
                    (resultSummary ?: error)?.lineSequence()?.firstOrNull { it.isNotBlank() }
                SubagentStatus.Queued -> null
            }
    }

    /** Which source a record's status came from — the precedence order among status
     * sources (ticket 091): a live lifecycle frame pins (nothing else writes),
     * otherwise a higher source overwrites a lower one and a lower one never touches
     * a higher one; same-source re-application is a content no-op. */
    private enum class StatusSource { Live, Roster, Result, Transcript }

    /**
     * One sub-agent's full state (the 091 replacement for the old `Stream`): metadata
     * are durable FIELDS — never inside the timeline — so the canonical replaces
     * (transcript page, history refetch) cannot wipe them.
     */
    private inner class SubagentRecord(
        val id: String,
        val sessionId: String,
    ) {
        // Identity: first non-blank writer wins (spawned → roster → call args/result →
        // transcript placeholder), never cleared once set.
        var name: String = ""
        var description: String = ""
        var runInBackground: Boolean = false

        /** The task prompt, rendered by [toState] as the page's first row: the
         * transcript never carries it (a sub-agent's turns are system-triggered —
         * docs/server-api.md), so it lives OUTSIDE the timeline. First writer wins
         * (the task-turn splice or the call's args — same content modulo the splice's
         * git-context prefix), never cleared. */
        var prompt: String? = null

        /** The spawning `Agent`/`AgentSwarm` tool call — the row link (first writer wins). */
        var parentToolCallId: String? = null

        /** The call the member was latest spawned or roster-listed under (LAST writer
         * wins — unlike [parentToolCallId], the stable row link): a member resumed
         * under a new `AgentSwarm` call moves to it, which is what keeps an OLD call's
         * re-mined `<agent_swarm_result>` from settling the record again (the ticket
         * 137 foreground-stop heal demotes only pins this call owns). */
        var latestToolCallId: String? = null

        /** The member's position inside its `AgentSwarm` call (first writer wins —
         * spawned frame or snapshot roster; ticket 134). */
        var swarmIndex: Int? = null

        /** The member's task id (first writer wins): the spawn task frame
         * (ticket 179) or the spawning call carries it — the roster has no such
         * field. */
        var taskId: String? = null

        /** The run's bound model/effort (ticket 135): the spawned frame overwrites
         * when it carries them (the highest identity source), the roster and the
         * spawn task frame fill blanks only. */
        var model: String? = null
        var thinkingEffort: String? = null

        var status: SubagentStatus = SubagentStatus.Queued
        var statusSource: StatusSource? = null

        /** True once a live lifecycle frame set the status (the 085 `sawLifecycle`):
         * roster/result/transcript statuses never touch a live-pinned record. */
        val statusPinnedByLive: Boolean get() = statusSource == StatusSource.Live

        var resultSummary: String? = null
        var error: String? = null

        /** The live suspend cause (ticket 136): written by `subagent.suspended`,
         * cleared when the run resumes or settles — stale reasons never survive a
         * status change. */
        var suspendReason: String? = null

        /** A backfilled `not_started` swarm member (ticket 136): the record exists
         * only so the roster row can render — no page, no transcript, a synthetic id. */
        var ghost: Boolean = false

        /** The run's `Agent` result's `resume_hint:`/`next_step:` blocks (ticket 158):
         *  the call's result is their only carrier, so they land from
         *  [applyAgentCall] regardless of the status precedence (a live-pinned
         *  record still gets them); a newer result's parse overwrites. */
        var resumeHint: String? = null
        var nextStep: String? = null

        /** The sub-agent's own background tasks, keyed by task id (from the child's
         * own `task.list`/`task` frames — ticket 179; a transcript `task` origin heals
         * the terminal status on a cold open, ticket 099). Insertion-ordered; the jobs
         * sheet owns the running-first/terminal-newest display sort. */
        private val jobs = LinkedHashMap<String, Job>()

        /** Task ids whose live ended frame was observed (ticket 099): the live frame is
         * the terminal truth — it carries `endedAt`/the timeout outcome the transcript
         * origin lacks — so the transcript heal never regresses one (first-writer-wins
         * against live frames). Process-lifetime by construction: after process death
         * the set is empty and the transcript heals, which is exactly the cold-open
         * case. */
        private val liveTerminatedJobIds = mutableSetOf<String>()

        /** One `task` frame's upsert — the full state, so it replaces the entry; the
         * page link survives a later frame that omits it (the JobsStore merge rule). A
         * terminal frame additionally pins the task against the transcript heal. */
        fun upsertJob(job: Job) {
            if (!job.isRunning) liveTerminatedJobIds += job.taskId
            val existing = jobs[job.taskId]
            jobs[job.taskId] = job.copy(agentId = job.agentId ?: existing?.agentId)
        }

        /** The child's `task.list` (ticket 179): the JobsStore merge — listed jobs
         * upsert, a still-Running job the list no longer names ended unseen and is
         * dropped, terminal jobs stay. */
        fun applyListedJobs(listed: List<Job>) {
            val listedIds = listed.mapTo(HashSet()) { it.taskId }
            jobs.entries.removeAll { (taskId, job) -> job.isRunning && taskId !in listedIds }
            listed.forEach(::upsertJob)
        }

        /** The transcript `task`-origin upsert (ticket 099): heals a job's terminal
         * status from canonical history. Everything the origin lacks (timestamps,
         * description, page link, timed-out flag) keeps the existing entry's values —
         * a live-observed running frame's job gets its status flipped but
         * keeps its real `startedAt`. A live-terminated task is skipped: the live
         * frame stays authoritative. */
        fun upsertJobFromTranscript(job: Job) {
            if (job.taskId in liveTerminatedJobIds) return
            val existing = jobs[job.taskId]
            jobs[job.taskId] = if (existing == null) {
                job
            } else {
                job.copy(
                    description = job.description.ifBlank { existing.description },
                    startedAt = job.startedAt ?: existing.startedAt,
                    endedAt = job.endedAt ?: existing.endedAt,
                    agentId = job.agentId ?: existing.agentId,
                    timedOut = job.timedOut || existing.timedOut,
                )
            }
        }

        /** Fills blank job descriptions from the timeline's Bash call rows (the 098
         * enrichment already linked each row to its task id; the call input's
         * `description` argument is what the row and the job screen show). A
         * transcript-healed job (ticket 099) starts blank — its call's turn can sit
         * on a page landing after the notification turn's. A live-fed job already
         * carries the frame's description and is untouched. */
        fun backfillJobDescriptions() {
            val rows = timeline.items().filterIsInstance<ChatItem.ToolGroup>().flatMap { it.tools }
            for (taskId in jobs.keys) {
                val job = jobs[taskId] ?: continue
                if (job.description.isNotBlank()) continue
                val description = rows.firstOrNull { it.bashTaskId == taskId }
                    ?.let { bashDescriptionOf(it.name, it.input) }
                if (!description.isNullOrBlank()) jobs[taskId] = job.copy(description = description)
            }
        }

        val timeline = ChatTimeline(historyBacked = false)

        /** The latest `todo` frame's list (ticket 178) — live state, outside the
         * timeline so no transcript replace touches it. */
        var todos: List<Todo>? = null

        /** The shared delta-coalescing state machine (ticket 065); every
         * timeline-bound transcript frame routes through it. */
        val coalescer = DeltaCoalescer(
            windowMillis = streamCoalesceWindowMillis,
            // Deferred like the store's scope: JVM tests run with window = 0 (no flush
            // job ever launches) and have no Main dispatcher to reference.
            scope = { scope },
            applyDelta = timeline::apply,
            applyEvent = { event ->
                timeline.apply(event)
                publish(sessionId)
            },
            publish = { publish(sessionId) },
        )

        /** Live turn.started frames observed — the task-turn splice is the first
         * splice of the FIRST turn only (later splices are the result-summary nudge
         * or steers and stay ordinary timeline rows). */
        var liveTurnsStarted = 0

        /** True once the task-turn splice question was settled (a splice was seen,
         * or a transcript page proved the task turn long past): no later splice can
         * be mistaken for the task prompt. */
        var taskSpliceHandled = false

        // --- Transcript backing (ticket 046) -----------------------------------------
        /** True once a transcript page landed; false for seed/live-only records. */
        var transcriptLoaded = false

        /** Oldest loaded turn id — the `before_turn` cursor of the next older page. */
        var oldestTurnId: String? = null
        var hasMoreTurns = false

        /** The status write funnel (the precedence table's enforcement point):
         * a live pin blocks every NON-live source (later live frames always
         * transition); a lower source never touches a higher one; [summary]/[error]
         * travel with their status. */
        fun applyStatus(
            new: SubagentStatus,
            source: StatusSource,
            summary: String? = null,
            error: String? = null,
        ) {
            if (source != StatusSource.Live) {
                val current = statusSource
                if (current == StatusSource.Live) return
                if (current != null && source.ordinal > current.ordinal) return
            }
            status = new
            statusSource = source
            if (summary != null) resultSummary = summary
            if (error != null) this.error = error
        }

        fun toState(): SubagentState {
            val promptBubble = prompt?.takeIf { it.isNotBlank() }
                ?.let { listOf(ChatItem.UserMessage(SEEDED_PROMPT_ID, it)) }
                .orEmpty()
            return SubagentState(
                id, name, description, runInBackground, status, resultSummary, error,
                hasMoreTurns, promptBubble + timeline.items(), todos, jobs.values.toList(),
                swarmIndex, taskId, model, thinkingEffort, suspendReason, ghost, resumeHint, nextStep,
            )
        }
    }

    // Lazy: JVM unit tests run with window = 0, which never launches a flush job, and
    // have no Main dispatcher to reference.
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    // sessionId → subagentId → record.
    private val records = mutableMapOf<String, MutableMap<String, SubagentRecord>>()
    private val states = MutableStateFlow<Map<String, Map<String, SubagentState>>>(emptyMap())

    // sessionId → (toolCallId → subagentIds): the session-scoped link registry
    // (ticket 091). Learned by the funnel (spawned / roster / result marker), it
    // survives the per-ViewModel timelines; ChatViewModel re-drives it into its
    // timeline after every store-affecting apply. Ordered one-to-many since ticket
    // 134: a swarm's members share the AgentSwarm call's toolCallId and register in
    // spawn-arrival order.
    private val links = mutableMapOf<String, MutableMap<String, MutableList<String>>>()

    // sessionId → (toolCallId → description): the AgentSwarm call's own label (its
    // `description` arg), mined by applyAgentCall's swarm branch (ticket 134). The
    // roster screen's supporting line; first writer wins.
    private val swarmDescriptions = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())

    // sessionId → number of open sub-agent pages. One of the two inputs of
    // [reconcileKeepAlive]: a page needs the store fed from the socket.
    private val openPages = MutableStateFlow<Map<String, Int>>(emptyMap())

    // Sessions whose chat-screen socket collection is currently running (the other
    // [reconcileKeepAlive] input): while the chat collects it is the feeder, and the
    // store's keep-alive must stay off — two concurrent collections would double-apply
    // replayed events.
    private val chatCollecting = MutableStateFlow<Set<String>>(emptySet())

    // sessionId → the store-owned keep-alive collection (ticket 064); present only
    // while a page is open AND no chat collection runs.
    private val keepAliveJobs = mutableMapOf<String, CoroutineJob>()

    /** The live state of one sub-agent; stays null when nothing ever reached the
     * funnel for it (the page shows its empty state). */
    fun entry(sessionId: String, subagentId: String): Flow<SubagentState?> =
        states.map { it[sessionId]?.get(subagentId) }.distinctUntilChanged()

    /** subagentId → display name (the record's subagent name, else its description)
     * for every known member of the session (ticket 139): the approval and question
     * cards' sub-agent badges resolve the asking member from here. Records with
     * neither field are absent — the badge falls back to a plain "Sub-agent". Live:
     * a name landing after the request (the spawned frame can lag it) republishes. */
    fun displayNames(sessionId: String): Flow<Map<String, String>> =
        states.map { bySession ->
            bySession[sessionId].orEmpty()
                .mapValues { (_, state) -> state.name.ifBlank { state.description } }
                .filterValues { it.isNotBlank() }
        }.distinctUntilChanged()

    /** The session's `Agent`/`AgentSwarm` tool-call → sub-agent links learned so far
     * (spawned, roster, result marker), in spawn-arrival order. ChatViewModel
     * re-drives them into its timeline's rows after every store-affecting apply —
     * one owner, explicit rebuild. */
    fun linksFor(sessionId: String): Map<String, List<String>> =
        links[sessionId]?.mapValues { it.value.toList() }.orEmpty()

    /** The members of one `AgentSwarm` call (ticket 134): the linked sub-agent states
     * sorted by [SubagentState.swarmIndex], members without an index keeping their
     * spawn-arrival order at the end (sortedBy is stable). Live: re-fires on every
     * publish of the session's records. */
    fun swarmMembers(sessionId: String, toolCallId: String): Flow<List<SubagentState>> =
        swarmMembersByCall(sessionId).map { it[toolCallId].orEmpty() }.distinctUntilChanged()

    /** toolCallId → member states for every linked call of the session (ticket 135),
     * each list sorted like [swarmMembers]: the chat's swarm cards read their
     * aggregate input from here, one ViewModel-side collection covering all cards. */
    fun swarmMembersByCall(sessionId: String): Flow<Map<String, List<SubagentState>>> =
        states.map { bySession ->
            links[sessionId].orEmpty().mapValues { (_, ids) ->
                ids.mapNotNull { bySession[sessionId]?.get(it) }
                    .sortedBy { it.swarmIndex ?: Int.MAX_VALUE }
            }
        }.distinctUntilChanged()

    /** The `AgentSwarm` call's own label (its `description` arg), mined by
     * [applyAgentCall]'s swarm branch; null until the call's args reach the store. */
    fun swarmDescription(sessionId: String, toolCallId: String): Flow<String?> =
        swarmDescriptions.map { it[sessionId]?.get(toolCallId) }.distinctUntilChanged()

    /** How many sub-agent pages of [sessionId] are currently open. */
    fun openPageCount(sessionId: String): Flow<Int> =
        openPages.map { it[sessionId] ?: 0 }.distinctUntilChanged()

    fun pageOpened(sessionId: String) {
        openPages.update { it + (sessionId to (it[sessionId] ?: 0) + 1) }
        reconcileKeepAlive(sessionId)
    }

    fun pageClosed(sessionId: String) {
        openPages.update { counts ->
            val count = (counts[sessionId] ?: 1) - 1
            if (count <= 0) counts - sessionId else counts + (sessionId to count)
        }
        reconcileKeepAlive(sessionId)
    }

    /** The chat screen's socket collection of [sessionId] started: it is the feeder
     * now, so the store's keep-alive collection (if any) stops first. Called by
     * ChatViewModel's socket gate before it starts collecting. */
    fun chatCollectionStarted(sessionId: String) {
        chatCollecting.update { it + sessionId }
        reconcileKeepAlive(sessionId)
    }

    /** The chat screen's socket collection of [sessionId] ended (its gate closed);
     * the keep-alive takes over when a page is still open. */
    fun chatCollectionStopped(sessionId: String) {
        chatCollecting.update { it - sessionId }
        reconcileKeepAlive(sessionId)
    }

    /** The single-feeder arbiter (ticket 064): the store collects the session socket
     * itself exactly while a page is open AND no chat collection runs. Runs on the
     * caller's thread — all callers are Main-confined, and [CoroutineJob.cancel] is
     * immediate, so a cancelled keep-alive collector never applies another event once
     * the chat's collection starts. */
    private fun reconcileKeepAlive(sessionId: String) {
        val needed = (openPages.value[sessionId] ?: 0) > 0 && sessionId !in chatCollecting.value
        val running = keepAliveJobs[sessionId]
        if (!needed) {
            running?.cancel()
            keepAliveJobs.remove(sessionId)
        } else if (running?.isActive != true) {
            keepAliveJobs[sessionId] = scope.launch { runKeepAliveCollection(sessionId) }
        }
    }

    /** The store-owned feeder: awaits the session's transcript cold start (tickets
     * 085/176) BEFORE subscribing — the roster/watermark seed and the cold start's
     * `Agent`-call mining land first, so a page restored after process death starts gapless (no
     * "spawn missed" drop) with the prompt already backfilled — then routes every
     * socket event through [apply]. A failed sync changes nothing: the page's
     * transcript fetch owns its error state. Main-agent events are ignored here (the
     * chat heals its own side from REST when it next opens). Connection lifecycle
     * events carry nothing the page renders. */
    private suspend fun runKeepAliveCollection(sessionId: String) {
        coldStart.sync(sessionId)
        chatRepository.observeSession(settingsRepository.current(), sessionId).collect { event ->
            (event as? SocketEvent.Event)?.event?.let { apply(sessionId, it) }
        }
    }

    /** The session's keep-alive collection, for tests (joining it in tearDown keeps a
     * leaked socket coroutine from touching Main after resetMain — ticket 049). */
    internal fun keepAliveJob(sessionId: String): CoroutineJob? = keepAliveJobs[sessionId]

    /** The live funnel: every sub-agent-scoped socket event reaches the record here. */
    suspend fun apply(sessionId: String, event: SessionEvent) {
        when (event) {
            is SessionEvent.SubagentSpawned -> spawn(sessionId, event)
            is SubagentLifecycleEvent -> onLifecycle(sessionId, event)
            // Task frames (ticket 179), tagged with the OWNING agent: a sub-agent's own
            // tasks feed its record's jobs list; any subagent-kind task (main-owned
            // spawns above all) merges into the record its child_agent_id names. The
            // main-owned job listing itself is the chat's JobsStore business.
            is SessionEvent.TaskListed -> onTaskListed(sessionId, event.agentId, event.tasks)
            is SessionEvent.TaskUpdated -> onTaskUpdated(sessionId, event.agentId, event.task)
            is AgentScopedEvent -> if (event.agentId != MAIN_AGENT_ID) onTranscript(sessionId, event.agentId, event)
            else -> Unit
        }
    }

    /** Releases the session's records when the whole session is left (the chat
     * ViewModel is cleared), cancelling any open coalescing bursts and the keep-alive
     * collection — and forgetting the session's cold start (ticket 091): the next
     * entry rebuilds from a FRESH transcript cold start, not a process-lifetime cache. */
    fun release(sessionId: String) {
        records.remove(sessionId)?.values?.forEach { it.coalescer.cancel() }
        links.remove(sessionId)
        swarmDescriptions.update { it - sessionId }
        keepAliveJobs.remove(sessionId)?.cancel()
        states.update { it - sessionId }
        openPages.update { it - sessionId }
        chatCollecting.update { it - sessionId }
        coldStart.forget(sessionId)
    }

    private fun spawn(sessionId: String, event: SessionEvent.SubagentSpawned) {
        val record = records.getOrPut(sessionId) { mutableMapOf() }
            .getOrPut(event.subagentId) { SubagentRecord(event.subagentId, sessionId) }
        // The highest identity source: fills blanks on seed-created records, no-ops
        // on a duplicate spawned (same content), never touches status or timeline.
        if (event.name.isNotBlank()) record.name = event.name
        if (event.description.isNotBlank()) record.description = event.description
        record.runInBackground = event.runInBackground
        if (record.parentToolCallId == null && event.parentToolCallId.isNotBlank()) {
            record.parentToolCallId = event.parentToolCallId
        }
        if (event.parentToolCallId.isNotBlank()) record.latestToolCallId = event.parentToolCallId
        if (record.swarmIndex == null) record.swarmIndex = event.swarmIndex
        if (record.taskId == null) record.taskId = event.taskId
        if (!event.model.isNullOrBlank()) record.model = event.model
        if (!event.thinkingEffort.isNullOrBlank()) record.thinkingEffort = event.thinkingEffort
        if (event.parentToolCallId.isNotBlank()) registerLink(sessionId, event.parentToolCallId, event.subagentId)
        publish(sessionId)
    }

    /**
     * The roster funnel (ticket 085, re-applicable since 091): a FRESH roster is
     * authoritative for every record not live-pinned — it creates missing records
     * (identity, plus the lifecycle phase and terminal `output_preview` when the entry
     * carries them — the 2.5.0 cold-start feeder, the transcript `agents` roster
     * (ticket 176), carries identity only, so its entries just create records the
     * live ops accumulate into), and
     * on later entries HEALS stale roster/result/transcript-derived statuses instead
     * of being skipped (the 085 "never re-seed" rule died with the process-lifetime
     * cache: a fresh roster is never stale). Never touches `prompt` (the roster has
     * none) or timelines.
     */
    fun applyRoster(sessionId: String, roster: List<SubagentRosterEntry>) {
        var changed = false
        for (entry in roster) {
            if (entry.id.isBlank()) continue
            val record = records.getOrPut(sessionId) { mutableMapOf() }
                .getOrPut(entry.id) { SubagentRecord(entry.id, sessionId).also { changed = true } }
            if (record.name.isBlank()) record.name = entry.subagentType
            if (record.description.isBlank()) record.description = entry.description
            if (record.parentToolCallId == null && entry.parentToolCallId.isNotBlank()) {
                record.parentToolCallId = entry.parentToolCallId
            }
            if (entry.parentToolCallId.isNotBlank()) record.latestToolCallId = entry.parentToolCallId
            if (record.swarmIndex == null) record.swarmIndex = entry.swarmIndex
            if (record.model == null) record.model = entry.model
            if (record.thinkingEffort == null) record.thinkingEffort = entry.thinkingEffort
            if (entry.parentToolCallId.isNotBlank()) registerLink(sessionId, entry.parentToolCallId, entry.id)
            val status = when (entry.phase) {
                SubagentRosterPhase.Queued -> SubagentStatus.Queued
                SubagentRosterPhase.Working -> SubagentStatus.Working
                SubagentRosterPhase.Suspended -> SubagentStatus.Suspended
                SubagentRosterPhase.Completed -> SubagentStatus.Completed
                SubagentRosterPhase.Failed -> SubagentStatus.Failed
                SubagentRosterPhase.Cancelled -> SubagentStatus.Cancelled
                // A phase this app predates: no status write — the transcript-derived
                // status (when it lands) is a better guess than a roster lie.
                SubagentRosterPhase.Unknown -> null
            }
            if (status != null) {
                record.applyStatus(status, StatusSource.Roster, summary = entry.outputPreview)
                changed = true
            }
        }
        if (changed) publish(sessionId)
    }

    /**
     * The `Agent`-call funnel (the 091 replacement for row seeding): the metadata
     * miner for `Agent` calls from ANY carrier — history rows, the cold start's
     * messages page, the running transcript turn's tool frames. The sub-agent id resolves from
     * the link registry (spawned/roster) or the result's `agent_id:` marker (the
     * version-pinned wire format); without either there is nothing to learn and the
     * call is skipped. A registry hit takes the FIRST linked id — right for a plain
     * `Agent` call, and swarm calls never reach this path (below).
     *
     * The `AgentSwarm` branch (ticket 134) is a different shape: the args carry no
     * `subagent_type`/`prompt` (one `prompt_template` + `items[]` fan the members
     * out), so the call's own `description` arg is kept as the swarm's label (the
     * roster screen's supporting line) and the members are fed by their own
     * spawned/roster frames. The settled call's `<agent_swarm_result>` text names
     * many ids at once: [applySwarmResult] (ticket 136) heals them member by member.
     *
     * A missing record is created from the args/result (identity, task prompt,
     * status, the `[summary]` section standing in as the final assistant bubble until
     * the transcript lands — ticket 045). An existing record is HEALED, never
     * replaced: `prompt` (when unset) and blank identity fields backfill from the
     * args, the result text feeds link/status/summary per the precedence table — and
     * its timeline is never touched.
     */
    fun applyAgentCall(
        sessionId: String,
        toolCallId: String,
        argsJson: String,
        resultText: String?,
        rowStatus: ToolStatus,
        // [AGENT_SWARM_TOOL_NAME] takes the swarm branch above, anything else the
        // plain-`Agent` path below. Defaults to `Agent`: the callers mining
        // plain-Agent history (TranscriptColdStart, most tests) pass nothing.
        toolName: String = AGENT_TOOL_NAME,
    ) {
        if (toolName == AGENT_SWARM_TOOL_NAME) {
            val label = agentArg(argsJson, "description")
            if (label != null) {
                swarmDescriptions.update { bySession ->
                    val sessionLabels = bySession[sessionId].orEmpty()
                    if (sessionLabels.containsKey(toolCallId)) {
                        bySession
                    } else {
                        bySession + (sessionId to sessionLabels + (toolCallId to label))
                    }
                }
            }
            if (resultText != null) applySwarmResult(sessionId, toolCallId, resultText)
            return
        }
        val subagentId = links[sessionId]?.get(toolCallId)?.firstOrNull()
            ?: resultText?.let { agentResultSubagentId(it) }
            ?: return
        registerLink(sessionId, toolCallId, subagentId)
        val sessionRecords = records.getOrPut(sessionId) { mutableMapOf() }
        val existing = sessionRecords[subagentId]
        if (existing == null) {
            val record = SubagentRecord(subagentId, sessionId)
            record.name = resultText?.let { resultField(it, "actual_subagent_type") }
                ?: agentArg(argsJson, "subagent_type").orEmpty()
            record.description = agentArg(argsJson, "description")
                ?: resultText?.let { resultField(it, "description") }.orEmpty()
            record.runInBackground = agentRunInBackground(argsJson)
            record.prompt = agentArg(argsJson, "prompt")
            record.parentToolCallId = toolCallId
            record.resumeHint = resultText?.let { agentResumeHintOf(AGENT_TOOL_NAME, it) }
            record.nextStep = resultText?.let { agentNextStepOf(AGENT_TOOL_NAME, it) }
            val summary = resultText?.let { resultSummarySection(it) }
            record.applyStatus(statusFromResult(resultText, rowStatus), StatusSource.Result, summary = summary)
            // The result summary stands in as the final assistant bubble until the
            // transcript lands with the real turns.
            if (summary != null) {
                record.timeline.replaceHistory(
                    listOf(ChatMessage(SEEDED_SUMMARY_ID, ChatMessage.Role.Assistant, listOf(ChatMessage.Part.Text(summary)))),
                    keepLive = false,
                )
            }
            sessionRecords[subagentId] = record
        } else {
            if (existing.prompt == null) existing.prompt = agentArg(argsJson, "prompt")
            if (existing.name.isBlank()) {
                existing.name = resultText?.let { resultField(it, "actual_subagent_type") }
                    ?: agentArg(argsJson, "subagent_type").orEmpty()
            }
            if (existing.description.isBlank()) {
                existing.description = agentArg(argsJson, "description")
                    ?: resultText?.let { resultField(it, "description") }.orEmpty()
            }
            if (existing.parentToolCallId == null) existing.parentToolCallId = toolCallId
            if (resultText != null) {
                // The continuation blocks (ticket 158) land independently of the
                // status precedence: the call's result is their only carrier, and
                // it arrives even when a live lifecycle frame already pinned the
                // status (the watched run settles before the main transcript's
                // tool.result is mined).
                agentResumeHintOf(AGENT_TOOL_NAME, resultText)?.let { existing.resumeHint = it }
                agentNextStepOf(AGENT_TOOL_NAME, resultText)?.let { existing.nextStep = it }
                existing.applyStatus(
                    statusFromResult(resultText, rowStatus),
                    StatusSource.Result,
                    summary = resultSummarySection(resultText),
                )
            }
        }
        publish(sessionId)
    }

    /**
     * The `<agent_swarm_result>` heal (ticket 136): the settled `AgentSwarm` call's
     * result text is the ONLY persisted per-member record of a swarm — `subagent.*`
     * events never replay on cursor resume and the snapshot roster clears when the
     * main agent starts its next turn (docs/server-api.md), so a swarm reopened
     * from history rebuilds its member states here. Members match records by
     * `agent_id`: outcome completed → Completed, failed → Failed, aborted →
     * Cancelled (the deliberate-stop state, ticket 135); the member's stop_reason
     * rides along as the record's error line (the roster row's terminal activity).
     * A member no record names is created — blank identity but the `item` excerpt
     * as the label — and linked under the call.
     *
     * Precedence (the [StatusSource] table's, refined by ticket 137): TERMINAL pins
     * keep winning — a live-settled record or ticket 135's killed-task Cancelled is
     * never rewritten by the parse, and a fresh roster's terminal phase holds too.
     * A NON-TERMINAL pin (live or roster) the member's own call owns is stale by
     * construction, though: the result only exists once every member settled
     * server-side, and a cancelled FOREGROUND member gets no live terminal frame at
     * all (docs/server-api.md — no `subagent.completed`/`subagent.failed`; only a
     * deliberate stop's `killed` task frame), so this parse is its terminal carrier; the pin is
     * demoted to the Result tier and the parse heals it. The guard is
     * [SubagentRecord.latestToolCallId]: only the member's LATEST call's result may
     * settle it, so an old call's re-mined result never flips a member since resumed
     * under a new call. Every status the parse writes is terminal, so it never
     * demotes a record OUT of a terminal state either.
     *
     * Members with `state="not_started"` never spawned: no live frame, roster
     * entry, or real id will ever name them. They backfill as GHOST records —
     * Cancelled, the `item` excerpt as the label, a synthetic id, [SubagentRecord.ghost]
     * set (no page to open) — but only while the call's linked members are fewer
     * than the result's member total: the members live sources already registered
     * fill the earlier slots, and re-mining the same result is a no-op.
     */
    private fun applySwarmResult(sessionId: String, toolCallId: String, resultText: String) {
        val result = SwarmResultParser.of(resultText) ?: return
        var changed = false
        val sessionRecords = records.getOrPut(sessionId) { mutableMapOf() }
        for (member in result.members) {
            if (member.notStarted) continue // the ghost backfill below owns those
            val agentId = member.agentId ?: continue
            val record = sessionRecords.getOrPut(agentId) {
                SubagentRecord(agentId, sessionId).also { changed = true }
            }
            if (record.description.isBlank() && member.item != null) record.description = member.item
            if (record.parentToolCallId == null) record.parentToolCallId = toolCallId
            registerLink(sessionId, toolCallId, agentId)
            // Ticket 137: a non-terminal pin this call owns is stale — the call
            // settled, and a cancelled foreground member gets no live terminal
            // frame, so this parse is its only terminal carrier. Demote the pin so
            // the heal below lands; terminal pins and other calls' members are
            // untouched (the KDoc's precedence rule).
            if (
                record.status != SubagentStatus.Completed &&
                record.status != SubagentStatus.Failed &&
                record.status != SubagentStatus.Cancelled &&
                record.statusSource != null &&
                record.latestToolCallId == toolCallId
            ) {
                record.statusSource = StatusSource.Result
            }
            record.applyStatus(
                when (member.outcome) {
                    SwarmResultParser.Outcome.Completed -> SubagentStatus.Completed
                    SwarmResultParser.Outcome.Failed -> SubagentStatus.Failed
                    SwarmResultParser.Outcome.Aborted -> SubagentStatus.Cancelled
                },
                StatusSource.Result,
                error = member.stopReason,
            )
            changed = true
        }
        val deficit = result.members.size - links[sessionId]?.get(toolCallId).orEmpty().size
        if (deficit > 0) {
            result.members
                .mapIndexedNotNull { index, member -> if (member.notStarted) index to member else null }
                .take(deficit)
                .forEach { (index, member) ->
                    val ghostId = "$toolCallId:ghost:$index"
                    if (sessionRecords.containsKey(ghostId)) return@forEach
                    val ghost = SubagentRecord(ghostId, sessionId)
                    ghost.description = member.item.orEmpty()
                    ghost.ghost = true
                    ghost.applyStatus(SubagentStatus.Cancelled, StatusSource.Result, error = member.stopReason)
                    sessionRecords[ghostId] = ghost
                    registerLink(sessionId, toolCallId, ghostId)
                    changed = true
                }
        }
        if (changed) publish(sessionId)
    }

    /** True once a transcript page landed for [subagentId] (ticket 046) — the page's
     * cue that a cold-open fetch is unnecessary (the socket keeps the record fresh). */
    fun hasTranscript(sessionId: String, subagentId: String): Boolean =
        record(sessionId, subagentId)?.transcriptLoaded == true

    /** The `before_turn` cursor for the next older page; null when no transcript loaded. */
    fun oldestTurnId(sessionId: String, subagentId: String): String? =
        record(sessionId, subagentId)?.oldestTurnId

    /**
     * Applies the newest transcript page (ticket 046): committed rows are replaced,
     * the in-flight live tail survives per [keepLive] (the completion reconcile passes
     * false — history is the canonical rendering of the finished run). Creates the
     * record when nothing seeded it (deep state loss: the page's transcript fetch is
     * then its only data source) — the identity fields stay blank, the top bar falls
     * back to the sub-agent id. Status comes from the turn states per the precedence
     * table (never over a live pin, roster, or result). Metadata-safe by
     * construction: the prompt and link are record FIELDS, not timeline rows.
     */
    fun applyTranscriptPage(sessionId: String, subagentId: String, page: TranscriptPage, keepLive: Boolean) {
        val existing = record(sessionId, subagentId)
        // An empty page for an unknown id (the server answers one — not an error)
        // creates nothing: the page keeps its "no transcript" empty state.
        if (existing == null && page.turns.isEmpty()) return
        val record = existing ?: records.getOrPut(sessionId) { mutableMapOf() }.getOrPut(subagentId) {
            SubagentRecord(subagentId, sessionId)
        }
        // Any coalesced text must apply BEFORE the replace wipes/dedups the live rows.
        record.coalescer.flush()
        record.timeline.replaceTranscript(page.turns, keepLive)
        record.transcriptLoaded = true
        // Canonical history exists now: no live splice can still be the task-turn one.
        record.taskSpliceHandled = true
        record.oldestTurnId = page.turns.firstOrNull()?.id
        record.hasMoreTurns = page.hasMore
        if (page.turns.isNotEmpty()) {
            // The cold-status source (ticket 085): the real turn states
            // (cancelled/failed are on the wire since 0.31.1), not a
            // blanket-Completed for anything non-Running.
            record.applyStatus(
                when (page.turns.lastOrNull()?.state) {
                    TranscriptState.Running -> SubagentStatus.Working
                    TranscriptState.Failed -> SubagentStatus.Failed
                    else -> SubagentStatus.Completed
                },
                StatusSource.Transcript,
            )
        }
        // Cold-open job heal (ticket 099): the page's task-completion notification
        // turns upsert the record's jobs — live-only otherwise.
        mineTaskOrigins(record, page.turns)
        publish(sessionId)
    }

    /** Prepends an older transcript page (ticket 046 pagination); the turn-id dedup in
     * the timeline makes a racing [applyTranscriptPage] harmless. An older page mines
     * task origins too (ticket 099) — the notification turn can sit beyond the newest
     * page. */
    fun prependTranscriptPage(sessionId: String, subagentId: String, page: TranscriptPage) {
        val record = record(sessionId, subagentId) ?: return
        if (page.turns.isNotEmpty()) {
            record.timeline.prependTranscript(page.turns)
            record.oldestTurnId = page.turns.first().id
        }
        record.hasMoreTurns = page.hasMore
        mineTaskOrigins(record, page.turns)
        publish(sessionId)
    }

    /** A `task.list` (ticket 179): each listed task merges into its spawn's record
     * ([mergeSpawnTask]); a sub-agent's OWN list replaces the running part of its
     * record's jobs (the JobsStore merge rule). */
    private fun onTaskListed(sessionId: String, agentId: String, tasks: List<TaskState>) {
        var changed = false
        for (task in tasks) changed = mergeSpawnTask(sessionId, task) || changed
        if (agentId != MAIN_AGENT_ID) {
            val record = record(sessionId, agentId)
            if (record == null) {
                Log.d(TAG, "Dropping task list for unknown sub-agent '$agentId' (spawn missed)")
            } else {
                record.applyListedJobs(tasks.mapNotNull { it.toBackgroundJob() })
                changed = true
            }
        }
        if (changed) publish(sessionId)
    }

    /** One `task` frame (ticket 179): the spawn merge ([mergeSpawnTask]), then — for a
     * task OWNED by a sub-agent (the frame's agent_id) — the owning record's jobs
     * upsert, the sub-agent page's jobs widget (background tasks only, the shared
     * [toBackgroundJob] mapping). */
    private fun onTaskUpdated(sessionId: String, agentId: String, task: TaskState) {
        var changed = mergeSpawnTask(sessionId, task)
        if (agentId != MAIN_AGENT_ID) {
            val record = record(sessionId, agentId)
            val job = task.toBackgroundJob()
            if (record == null) {
                Log.d(TAG, "Dropping task frame for unknown sub-agent '$agentId' (spawn missed)")
            } else if (job != null) {
                record.upsertJob(job)
                changed = true
            }
        }
        if (changed) publish(sessionId)
    }

    /**
     * A subagent-kind task whose `child_agent_id` names an EXISTING record (no duplicate
     * creation — the record itself comes from the roster or the spawning call; the
     * rest of the record's task-frame derivation is ticket 182's) merges what the
     * member's controls and badges need:
     * - the task id (first writer wins) — the handle `task.stop`/`task.background`
     *   address (tickets 137/138); foreground members have one too since 2.5.0;
     * - `detached:true` flips `runInBackground` (ticket 138: a detach's replied/frame
     *   state is the conversion's live carrier, which hides "To background");
     * - the model/effort pair, blank-filled (ticket 135);
     * - an ended `killed` outcome pins the member Cancelled (ticket 135 — a stop is
     *   no failure, and no other carrier says so).
     * Returns whether the record changed.
     */
    private fun mergeSpawnTask(sessionId: String, task: TaskState): Boolean {
        if (task.kind != TaskState.Kind.Subagent) return false
        val member = task.childAgentId?.let { record(sessionId, it) } ?: return false
        var changed = false
        if (member.taskId == null) {
            member.taskId = task.taskId
            changed = true
        }
        if (task.detached && !member.runInBackground) {
            member.runInBackground = true
            changed = true
        }
        if (member.model == null && task.model != null) {
            member.model = task.model
            changed = true
        }
        if (member.thinkingEffort == null && task.thinking != null) {
            member.thinkingEffort = task.thinking
            changed = true
        }
        if (task.outcome == TaskOutcome.Killed) {
            member.applyStatus(SubagentStatus.Cancelled, StatusSource.Live)
            changed = true
        }
        return changed
    }

    /** The ticket-099 cold-open heal: a transcript page's task-completion notification
     * turns upsert the named jobs' terminal status into the record's jobs — the same
     * "canonical history heals live-only state" pattern the snapshot roster and the
     * turn states already follow. The origin carries no kind/timestamps/description:
     * the kind derives from the task-id prefix (`bash-`/`agent-` — the only two on
     * the wire), timestamps stay unknown (null) on a newly created entry, and the
     * description backfills from the Bash call's timeline row (blank until the page
     * holding the call lands). */
    private fun mineTaskOrigins(record: SubagentRecord, turns: List<TranscriptTurn>) {
        for (turn in turns) {
            val origin = turn.taskOrigin ?: continue
            // A notification turn only exists once the task is terminal; a "running"
            // (or unknown) origin maps to null and says nothing the live stream
            // doesn't know better.
            val status = origin.status?.toJobStatus() ?: continue
            val kind = when {
                origin.taskId.startsWith("bash-") -> Job.Kind.Bash
                origin.taskId.startsWith("agent-") -> Job.Kind.Subagent
                else -> continue
            }
            record.upsertJobFromTranscript(
                Job(
                    origin.taskId,
                    kind,
                    description = "",
                    status,
                    startedAt = null,
                    timedOut = origin.status == TaskOutcome.TimedOut,
                ),
            )
        }
        record.backfillJobDescriptions()
    }

    private fun onLifecycle(sessionId: String, event: SubagentLifecycleEvent) {
        val record = record(sessionId, event.subagentId) ?: return
        // A lifecycle transition freezes the stream exactly: publish the coalesced
        // text before the status flips.
        record.coalescer.flush()
        when (event) {
            is SessionEvent.SubagentSpawned -> Unit // handled by spawn()
            is SessionEvent.SubagentStarted -> {
                // A resume: the suspend cause is spent (ticket 136).
                record.suspendReason = null
                record.applyStatus(SubagentStatus.Working, StatusSource.Live)
            }
            is SessionEvent.SubagentSuspended -> {
                record.suspendReason = event.reason.ifBlank { null }
                record.applyStatus(SubagentStatus.Suspended, StatusSource.Live)
            }
            is SessionEvent.SubagentCompleted -> {
                record.suspendReason = null
                record.applyStatus(
                    SubagentStatus.Completed,
                    StatusSource.Live,
                    summary = event.resultSummary.ifBlank { null },
                )
            }
            is SessionEvent.SubagentFailed -> {
                record.suspendReason = null
                record.applyStatus(
                    SubagentStatus.Failed,
                    StatusSource.Live,
                    error = event.error.ifBlank { null },
                )
            }
            is SessionEvent.SubagentCancelled -> {
                record.suspendReason = null
                record.applyStatus(SubagentStatus.Cancelled, StatusSource.Live)
            }
        }
        publish(sessionId)
    }

    private suspend fun onTranscript(sessionId: String, agentId: String, event: SessionEvent) {
        val record = record(sessionId, agentId)
        if (record == null) {
            // The spawn predates the app's subscription: the page shows the empty
            // state and these frames have nowhere to go.
            Log.d(TAG, "Dropping frame for unknown sub-agent '$agentId' (spawn missed)")
            return
        }
        if (event is SessionEvent.ContextSpliced && !record.taskSpliceHandled) {
            record.taskSpliceHandled = true
            // The task-turn splice (docs/server-api.md, Sub-agents — the sub-agent's
            // first internal user message IS the task prompt, with its optional
            // git-context prefix): the transcript never carries it, so it is lifted
            // into the record's prompt FIELD (first writer wins — the call args carry
            // the same text) and never becomes a timeline row a transcript replace
            // could wipe (ticket 091, bug 1). Only the first turn's first splice,
            // while nothing else has produced rows — later splices (the
            // result-summary nudge turn, steers) flow to the timeline as user
            // bubbles exactly as before.
            if (record.liveTurnsStarted <= 1 && record.timeline.items().isEmpty()) {
                val taskPrompt = event.messages
                    .firstOrNull { it.role == ChatMessage.Role.User }
                    ?.parts?.filterIsInstance<ChatMessage.Part.Text>()
                    ?.joinToString("\n") { it.text }
                    ?.takeIf { it.isNotBlank() }
                if (taskPrompt != null) {
                    if (record.prompt == null) record.prompt = taskPrompt
                    publish(sessionId)
                    return
                }
            }
        }
        if (event is SessionEvent.TodoUpdated) {
            // The child's `todo` frame (ticket 178): the dock's list, replaced
            // wholesale — not a timeline event.
            record.todos = event.items
            publish(sessionId)
            return
        }
        if (event is SessionEvent.TurnStarted) record.liveTurnsStarted++
        // The coalescer conflates deltas and flushes-before-apply for anything else
        // (ticket 065).
        record.coalescer.onEvent(event)
    }

    private fun registerLink(sessionId: String, toolCallId: String, subagentId: String) {
        // Ordered one-to-many (ticket 134): distinct ids in arrival order — spawned,
        // roster and result marker all name the same pairs, and a swarm's members
        // share one toolCallId.
        val sessionLinks = links.getOrPut(sessionId) { mutableMapOf() }
        val ids = sessionLinks.getOrPut(toolCallId) { mutableListOf() }
        if (subagentId !in ids) ids += subagentId
    }

    private fun record(sessionId: String, subagentId: String): SubagentRecord? = records[sessionId]?.get(subagentId)

    private fun publish(sessionId: String) {
        val sessionRecords = records[sessionId] ?: return
        states.update { it + (sessionId to sessionRecords.mapValues { entry -> entry.value.toState() }) }
    }

    /** The status an `Agent` call's result text (version-pinned format) implies;
     * absent a result the call is still running (or failed at the row level). */
    private fun statusFromResult(resultText: String?, rowStatus: ToolStatus): SubagentStatus =
        when (resultText?.let { resultField(it, "status") }) {
            "completed" -> SubagentStatus.Completed
            "failed" -> SubagentStatus.Failed
            "running" -> SubagentStatus.Working
            else -> if (rowStatus == ToolStatus.Error) SubagentStatus.Failed else SubagentStatus.Working
        }

    /** The `agent_id:` linkage marker of an `Agent` result text (version-pinned wire
     * format — first line for foreground runs, after the task fields for background). */
    private fun agentResultSubagentId(output: String): String? =
        output.lineSequence()
            .firstOrNull { it.startsWith(AGENT_ID_PREFIX) }
            ?.removePrefix(AGENT_ID_PREFIX)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /** `key: value` line of an `Agent` result text (version-pinned wire format). */
    private fun resultField(output: String, key: String): String? =
        output.lineSequence()
            .firstOrNull { it.startsWith("$key: ") }
            ?.substringAfter(": ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /** The `[summary]` section of a completed run's result text; absent while running.
     *  The section ends at the trailing continuation blocks (0.43.0's `resume_hint:`,
     *  0.31.0's `next_step:` — ticket 158): they are parsed separately and never
     *  render as part of the summary. */
    private fun resultSummarySection(output: String): String? {
        val lines = output.lines()
        val marker = lines.indexOf("[summary]")
        if (marker < 0) return null
        return lines.drop(marker + 1)
            .takeWhile { !it.startsWith("resume_hint: ") && !it.startsWith("next_step: ") }
            .joinToString("\n").trim().takeIf { it.isNotBlank() }
    }

    /** One string argument of the `Agent` call's args JSON. */
    private fun agentArg(argsJson: String, key: String): String? =
        runCatching { json.parseToJsonElement(argsJson).jsonObject[key]?.jsonPrimitive?.content }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun agentRunInBackground(argsJson: String): Boolean =
        runCatching {
            json.parseToJsonElement(argsJson).jsonObject["run_in_background"]?.jsonPrimitive?.booleanOrNull == true
        }.getOrDefault(false)

    private companion object {
        const val TAG = "SubagentStreamStore"

        /** Line prefix of the linkage marker in an `Agent` call's result text. */
        const val AGENT_ID_PREFIX = "agent_id: "

        /** Message ids of the seeded prompt/summary bubbles (LazyColumn keys). */
        const val SEEDED_PROMPT_ID = "seeded_prompt"
        const val SEEDED_SUMMARY_ID = "seeded_summary"
    }
}
