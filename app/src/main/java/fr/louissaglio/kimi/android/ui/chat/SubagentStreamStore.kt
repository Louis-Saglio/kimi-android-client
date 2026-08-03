package fr.louissaglio.kimi.android.ui.chat

import android.util.Log
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
import fr.louissaglio.kimi.android.data.chat.TodoParser
import fr.louissaglio.kimi.android.data.chat.TranscriptPage
import fr.louissaglio.kimi.android.data.chat.TranscriptState
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.di.StreamCoalesceWindowMillis
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Lifecycle of one sub-agent (docs/server-api.md, Sub-agents): Queued between spawned
 * and started, Working while its turn streams (also after a resume), Suspended/Completed/
 * Failed per the matching lifecycle frame. */
enum class SubagentStatus { Queued, Working, Suspended, Completed, Failed }

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
 * ([apply] for live frames, [applyRoster] for the snapshot roster, [applyAgentCall]
 * for `Agent` call args/results from any carrier, [applyTranscriptPage]/
 * [prependTranscriptPage] for transcript pages). Each writes only the fields it is
 * authoritative for, per the precedence table on [SubagentRecord.statusSource]:
 * late canonical data HEALS instead of bouncing off a never-overwrite guard, and
 * re-applying the same source is a no-op by content, not by refusal.
 * 3. The session's `toolCallId → subagentId` link registry lives here (not in a
 * per-ViewModel timeline): populated by the funnel (spawned / roster / result
 * marker), read by ChatViewModel via [linksFor] to re-drive the timeline's row
 * links after every store-affecting apply.
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
    private val todoParser: TodoParser,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    /** The snapshot cold-start owner (ticket 085): the keep-alive collection awaits
     * the session's sync before its first subscribe — the roster seed and the resume
     * cursor land first, so a cold-restored page streams gaplessly from frame one.
     * [release] calls [SnapshotSync.forget]: the session's next entry re-fetches
     * instead of joining a process-lifetime cache of mutable server state (091). */
    private val snapshotSync: SnapshotSync,
    // No default: an all-default @Inject constructor makes Kotlin generate a second,
    // no-arg synthetic constructor, which Dagger rejects (two injected constructors).
    @StreamCoalesceWindowMillis
    private val streamCoalesceWindowMillis: Long,
) {

    /** Immutable snapshot of one sub-agent's state, as rendered by the sub-agent page. */
    data class SubagentState(
        val id: String,
        /** The subagent type (e.g. "explore"), from subagent.spawned — or the snapshot
         * roster / Agent-call seed on a cold open (tickets 085/045). */
        val name: String,
        val description: String,
        val runInBackground: Boolean,
        val status: SubagentStatus,
        val resultSummary: String? = null,
        val error: String? = null,
        /** True while older transcript turns exist beyond the loaded ones (ticket 046). */
        val hasMoreTurns: Boolean = false,
        val items: List<ChatItem> = emptyList(),
    )

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
        todoParser: TodoParser,
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

        /** The spawning `Agent` tool call — the row link (first writer wins). */
        var parentToolCallId: String? = null

        var status: SubagentStatus = SubagentStatus.Queued
        var statusSource: StatusSource? = null

        /** True once a live lifecycle frame set the status (the 085 `sawLifecycle`):
         * roster/result/transcript statuses never touch a live-pinned record. */
        val statusPinnedByLive: Boolean get() = statusSource == StatusSource.Live

        var resultSummary: String? = null
        var error: String? = null

        val timeline = ChatTimeline(todoParser, historyBacked = false)

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
                hasMoreTurns, promptBubble + timeline.items(),
            )
        }
    }

    // Lazy: JVM unit tests run with window = 0, which never launches a flush job, and
    // have no Main dispatcher to reference.
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    // sessionId → subagentId → record.
    private val records = mutableMapOf<String, MutableMap<String, SubagentRecord>>()
    private val states = MutableStateFlow<Map<String, Map<String, SubagentState>>>(emptyMap())

    // sessionId → (toolCallId → subagentId): the session-scoped link registry
    // (ticket 091). Learned by the funnel (spawned / roster / result marker), it
    // survives the per-ViewModel timelines; ChatViewModel re-drives it into its
    // timeline after every store-affecting apply.
    private val links = mutableMapOf<String, MutableMap<String, String>>()

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
    private val keepAliveJobs = mutableMapOf<String, Job>()

    /** The live state of one sub-agent; stays null when nothing ever reached the
     * funnel for it (the page shows its empty state). */
    fun entry(sessionId: String, subagentId: String): Flow<SubagentState?> =
        states.map { it[sessionId]?.get(subagentId) }.distinctUntilChanged()

    /** The session's `Agent` tool-call → sub-agent links learned so far (spawned,
     * roster, result marker). ChatViewModel re-drives them into its timeline's rows
     * after every store-affecting apply — one owner, explicit rebuild. */
    fun linksFor(sessionId: String): Map<String, String> = links[sessionId]?.toMap().orEmpty()

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
     * caller's thread — all callers are Main-confined, and [Job.cancel] is immediate,
     * so a cancelled keep-alive collector never applies another event once the chat's
     * collection starts. */
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

    /** The store-owned feeder: awaits the session's snapshot cold start (ticket 085)
     * BEFORE subscribing — the roster/cursor seed and the snapshot's `Agent`-call
     * mining land first, so a page restored after process death starts gapless (no
     * "spawn missed" drop) with the prompt already backfilled — then routes every
     * socket event through [apply]. A failed sync changes nothing: the page's
     * transcript fetch owns its error state. Main-agent events are ignored here (the
     * chat heals its own side from REST when it next opens). Connection lifecycle
     * events carry nothing the page renders. */
    private suspend fun runKeepAliveCollection(sessionId: String) {
        snapshotSync.sync(sessionId)
        chatRepository.observeSession(settingsRepository.current(), sessionId).collect { event ->
            (event as? SocketEvent.Event)?.event?.let { apply(sessionId, it) }
        }
    }

    /** The session's keep-alive collection, for tests (joining it in tearDown keeps a
     * leaked socket coroutine from touching Main after resetMain — ticket 049). */
    internal fun keepAliveJob(sessionId: String): Job? = keepAliveJobs[sessionId]

    /** The live funnel: every sub-agent-scoped socket event reaches the record here. */
    suspend fun apply(sessionId: String, event: SessionEvent) {
        when (event) {
            is SessionEvent.SubagentSpawned -> spawn(sessionId, event)
            is SubagentLifecycleEvent -> onLifecycle(sessionId, event)
            is AgentScopedEvent -> if (event.agentId != MAIN_AGENT_ID) onTranscript(sessionId, event.agentId, event)
            else -> Unit
        }
    }

    /** Releases the session's records when the whole session is left (the chat
     * ViewModel is cleared), cancelling any open coalescing bursts and the keep-alive
     * collection — and forgetting the session's snapshot sync (ticket 091): the next
     * entry rebuilds from a FRESH snapshot, not a process-lifetime cache. */
    fun release(sessionId: String) {
        records.remove(sessionId)?.values?.forEach { it.coalescer.cancel() }
        links.remove(sessionId)
        keepAliveJobs.remove(sessionId)?.cancel()
        states.update { it - sessionId }
        openPages.update { it - sessionId }
        chatCollecting.update { it - sessionId }
        snapshotSync.forget(sessionId)
    }

    private fun spawn(sessionId: String, event: SessionEvent.SubagentSpawned) {
        val record = records.getOrPut(sessionId) { mutableMapOf() }
            .getOrPut(event.subagentId) { SubagentRecord(event.subagentId, sessionId, todoParser) }
        // The highest identity source: fills blanks on seed-created records, no-ops
        // on a duplicate spawned (same content), never touches status or timeline.
        if (event.name.isNotBlank()) record.name = event.name
        if (event.description.isNotBlank()) record.description = event.description
        record.runInBackground = event.runInBackground
        if (record.parentToolCallId == null && event.parentToolCallId.isNotBlank()) {
            record.parentToolCallId = event.parentToolCallId
        }
        if (event.parentToolCallId.isNotBlank()) registerLink(sessionId, event.parentToolCallId, event.subagentId)
        publish(sessionId)
    }

    /**
     * The roster funnel (ticket 085, re-applicable since 091): a FRESH snapshot
     * roster is authoritative for every record not live-pinned — it creates missing
     * records (identity + precise lifecycle phase + terminal `output_preview`), and
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
                .getOrPut(entry.id) { SubagentRecord(entry.id, sessionId, todoParser).also { changed = true } }
            if (record.name.isBlank()) record.name = entry.subagentType
            if (record.description.isBlank()) record.description = entry.description
            if (record.parentToolCallId == null && entry.parentToolCallId.isNotBlank()) {
                record.parentToolCallId = entry.parentToolCallId
            }
            if (entry.parentToolCallId.isNotBlank()) registerLink(sessionId, entry.parentToolCallId, entry.id)
            val status = when (entry.phase) {
                SubagentRosterPhase.Queued -> SubagentStatus.Queued
                SubagentRosterPhase.Working -> SubagentStatus.Working
                SubagentRosterPhase.Suspended -> SubagentStatus.Suspended
                SubagentRosterPhase.Completed -> SubagentStatus.Completed
                SubagentRosterPhase.Failed -> SubagentStatus.Failed
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
     * miner for `Agent` calls from ANY carrier — history rows, the snapshot's
     * messages page, `in_flight_turn.running_tools`. The sub-agent id resolves from
     * the link registry (spawned/roster) or the result's `agent_id:` marker (the
     * version-pinned wire format); without either there is nothing to learn and the
     * call is skipped.
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
    ) {
        val subagentId = links[sessionId]?.get(toolCallId)
            ?: resultText?.let { agentResultSubagentId(it) }
            ?: return
        registerLink(sessionId, toolCallId, subagentId)
        val sessionRecords = records.getOrPut(sessionId) { mutableMapOf() }
        val existing = sessionRecords[subagentId]
        if (existing == null) {
            val record = SubagentRecord(subagentId, sessionId, todoParser)
            record.name = resultText?.let { resultField(it, "actual_subagent_type") }
                ?: agentArg(argsJson, "subagent_type").orEmpty()
            record.description = agentArg(argsJson, "description")
                ?: resultText?.let { resultField(it, "description") }.orEmpty()
            record.runInBackground = agentRunInBackground(argsJson)
            record.prompt = agentArg(argsJson, "prompt")
            record.parentToolCallId = toolCallId
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
                existing.applyStatus(
                    statusFromResult(resultText, rowStatus),
                    StatusSource.Result,
                    summary = resultSummarySection(resultText),
                )
            }
        }
        publish(sessionId)
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
            SubagentRecord(subagentId, sessionId, todoParser)
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
        publish(sessionId)
    }

    /** Prepends an older transcript page (ticket 046 pagination); the turn-id dedup in
     * the timeline makes a racing [applyTranscriptPage] harmless. */
    fun prependTranscriptPage(sessionId: String, subagentId: String, page: TranscriptPage) {
        val record = record(sessionId, subagentId) ?: return
        if (page.turns.isNotEmpty()) {
            record.timeline.prependTranscript(page.turns)
            record.oldestTurnId = page.turns.first().id
        }
        record.hasMoreTurns = page.hasMore
        publish(sessionId)
    }

    private fun onLifecycle(sessionId: String, event: SubagentLifecycleEvent) {
        val record = record(sessionId, event.subagentId) ?: return
        // A lifecycle transition freezes the stream exactly: publish the coalesced
        // text before the status flips.
        record.coalescer.flush()
        when (event) {
            is SessionEvent.SubagentSpawned -> Unit // handled by spawn()
            is SessionEvent.SubagentStarted ->
                record.applyStatus(SubagentStatus.Working, StatusSource.Live)
            is SessionEvent.SubagentSuspended ->
                record.applyStatus(SubagentStatus.Suspended, StatusSource.Live)
            is SessionEvent.SubagentCompleted ->
                record.applyStatus(
                    SubagentStatus.Completed,
                    StatusSource.Live,
                    summary = event.resultSummary.ifBlank { null },
                )
            is SessionEvent.SubagentFailed ->
                record.applyStatus(
                    SubagentStatus.Failed,
                    StatusSource.Live,
                    error = event.error.ifBlank { null },
                )
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
        if (event is SessionEvent.TurnStarted) record.liveTurnsStarted++
        // The coalescer conflates deltas and flushes-before-apply for anything else
        // (ticket 065).
        record.coalescer.onEvent(event)
    }

    private fun registerLink(sessionId: String, toolCallId: String, subagentId: String) {
        // First writer wins (spawned → roster → result marker all name the same pair).
        links.getOrPut(sessionId) { mutableMapOf() }.putIfAbsent(toolCallId, subagentId)
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

    /** The `[summary]` section of a completed run's result text; absent while running. */
    private fun resultSummarySection(output: String): String? {
        val lines = output.lines()
        val marker = lines.indexOf("[summary]")
        if (marker < 0) return null
        return lines.drop(marker + 1).joinToString("\n").trim().takeIf { it.isNotBlank() }
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
