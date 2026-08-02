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
 * Session-scoped store of every sub-agent's live stream (ticket 045), fed by exactly
 * ONE session-socket collection at a time (ticket 064): the chat screen's while its
 * socket gate holds the session (signaled via [chatCollectionStarted] /
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
 * Each entry owns a [ChatTimeline] in non-history-backed mode (turn.started seals the
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
     * cursor land first, so a cold-restored page streams gaplessly from frame one. */
    private val snapshotSync: SnapshotSync,
    // No default: an all-default @Inject constructor makes Kotlin generate a second,
    // no-arg synthetic constructor, which Dagger rejects (two injected constructors).
    @StreamCoalesceWindowMillis
    private val streamCoalesceWindowMillis: Long,
) {

    /** Immutable snapshot of one sub-agent's stream, as rendered by the sub-agent page. */
    data class SubagentState(
        val id: String,
        /** The subagent type (e.g. "explore"), from subagent.spawned — or the snapshot
         * roster / Agent-row seed on a cold open (tickets 085/045). */
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

    private inner class Stream(
        val id: String,
        val sessionId: String,
        val name: String,
        val description: String,
        val runInBackground: Boolean,
        todoParser: TodoParser,
        var status: SubagentStatus = SubagentStatus.Queued,
    ) {
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

        /** Set by any started/suspended/completed/failed frame: transcript-derived
         * status (ticket 046) applies only while no live lifecycle frame was seen. */
        var sawLifecycle = false

        // --- Transcript backing (ticket 046) -----------------------------------------
        /** True once a transcript page landed; false for seed/live-only entries. */
        var transcriptLoaded = false

        /** Oldest loaded turn id — the `before_turn` cursor of the next older page. */
        var oldestTurnId: String? = null
        var hasMoreTurns = false

        /** The task prompt from the `Agent` row's args (cold-open seed, ticket 045):
         * the transcript never carries it (a sub-agent's turns are system-triggered),
         * so it lives OUTSIDE the timeline — a refetch must not wipe the page's only
         * user bubble. Rendered as the page's first row by [toState]. */
        var seededPrompt: String? = null

        fun toState(): SubagentState {
            val promptBubble = seededPrompt?.takeIf { it.isNotBlank() }
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

    // sessionId → subagentId → stream.
    private val streams = mutableMapOf<String, MutableMap<String, Stream>>()
    private val states = MutableStateFlow<Map<String, Map<String, SubagentState>>>(emptyMap())

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

    /** The live entry of one sub-agent; stays null when its `subagent.spawned` was never
     * seen (the app subscribed after the spawn — the page shows its empty state). */
    fun entry(sessionId: String, subagentId: String): Flow<SubagentState?> =
        states.map { it[sessionId]?.get(subagentId) }.distinctUntilChanged()

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
     * BEFORE subscribing — the roster seed and the resume cursor land first, so a
     * page restored after process death starts gapless (no "spawn missed" drop) —
     * then routes every socket event through [apply]. A failed sync changes nothing:
     * the page's transcript fetch owns its error state. Main-agent events are ignored
     * here (the chat heals its own side from REST when it next opens). Connection
     * lifecycle events carry nothing the page renders. */
    private suspend fun runKeepAliveCollection(sessionId: String) {
        snapshotSync.sync(sessionId)
        chatRepository.observeSession(settingsRepository.current(), sessionId).collect { event ->
            (event as? SocketEvent.Event)?.event?.let { apply(sessionId, it) }
        }
    }

    /** The session's keep-alive collection, for tests (joining it in tearDown keeps a
     * leaked socket coroutine from touching Main after resetMain — ticket 049). */
    internal fun keepAliveJob(sessionId: String): Job? = keepAliveJobs[sessionId]

    suspend fun apply(sessionId: String, event: SessionEvent) {
        when (event) {
            is SessionEvent.SubagentSpawned -> spawn(sessionId, event)
            is SubagentLifecycleEvent -> onLifecycle(sessionId, event)
            is AgentScopedEvent -> if (event.agentId != MAIN_AGENT_ID) onTranscript(sessionId, event.agentId, event)
            else -> Unit
        }
    }

    /** Releases the session's streams when the whole session is left (the chat
     * ViewModel is cleared), cancelling any open coalescing bursts and the keep-alive
     * collection. */
    fun release(sessionId: String) {
        streams.remove(sessionId)?.values?.forEach { it.coalescer.cancel() }
        keepAliveJobs.remove(sessionId)?.cancel()
        states.update { it - sessionId }
        openPages.update { it - sessionId }
        chatCollecting.update { it - sessionId }
    }

    private fun spawn(sessionId: String, event: SessionEvent.SubagentSpawned) {
        val sessionStreams = streams.getOrPut(sessionId) { mutableMapOf() }
        // A duplicate spawned frame must not wipe the accumulated stream.
        if (sessionStreams.containsKey(event.subagentId)) return
        sessionStreams[event.subagentId] = Stream(
            id = event.subagentId,
            sessionId = sessionId,
            name = event.name,
            description = event.description,
            runInBackground = event.runInBackground,
            todoParser = todoParser,
        )
        publish(sessionId)
    }

    /**
     * Seeds an entry from a linked `Agent` row of the main timeline — the cold-open
     * case (ticket 045): the spawn, and possibly the whole run, predates the app's
     * subscription, so no `subagent.*` frame will ever arrive for it. The row still
     * carries everything the page needs (docs/server-api.md, Sub-agents — the
     * version-pinned result format): the call's args give the identity (description,
     * subagent type), the result's `status:` line the lifecycle, and its `[summary]`
     * section the final report, seeded as the timeline's last assistant bubble.
     *
     * Also useful mid-run: a background `Agent` result returns immediately
     * (`status: running`), so seeding it lets frames from the subscription point
     * accumulate instead of hitting the unknown-sub-agent drop. Never overwrites an
     * existing entry — live data always wins.
     */
    fun seedFromAgentRow(sessionId: String, row: ChatItem.ToolActivity) {
        val subagentId = row.subagentId ?: return
        if (row.name != AGENT_TOOL_NAME) return
        if (stream(sessionId, subagentId) != null) return
        val summary = resultSummarySection(row.output)
        val status = when (resultField(row.output, "status")) {
            "completed" -> SubagentStatus.Completed
            "failed" -> SubagentStatus.Failed
            else -> if (row.status == ToolStatus.Error) SubagentStatus.Failed else SubagentStatus.Working
        }
        val stream = Stream(
            id = subagentId,
            sessionId = sessionId,
            name = resultField(row.output, "actual_subagent_type") ?: agentArg(row.input, "subagent_type").orEmpty(),
            description = agentArg(row.input, "description") ?: resultField(row.output, "description").orEmpty(),
            runInBackground = agentRunInBackground(row.input),
            todoParser = todoParser,
            status = status,
        )
        stream.resultSummary = summary
        // The task prompt lives outside the timeline (Stream.seededPrompt): a later
        // transcript page (ticket 046) replaces timeline rows wholesale and would take
        // the page's only user bubble with it.
        stream.seededPrompt = agentArg(row.input, "prompt")
        // The result summary stands in as the final assistant bubble until the
        // transcript lands with the real turns.
        if (summary != null) {
            stream.timeline.replaceHistory(
                listOf(ChatMessage(SEEDED_SUMMARY_ID, ChatMessage.Role.Assistant, listOf(ChatMessage.Part.Text(summary)))),
                keepLive = false,
            )
        }
        streams.getOrPut(sessionId) { mutableMapOf() }[subagentId] = stream
        publish(sessionId)
    }

    /**
     * Seeds entries from the session snapshot's sub-agent roster (ticket 085) — the
     * cold-start linkage source the wire never replays: identity ([SubagentRosterEntry]
     * fields) and the precise lifecycle phase (the roster IS authoritative lifecycle,
     * so the entry counts as lifecycle-seen and a later transcript page can never
     * regress it). A `completed` entry's [SubagentRosterEntry.outputPreview] becomes
     * the result summary. Because the entry exists before the first subscribe, live
     * frames accumulate from the start instead of hitting the unknown-sub-agent drop.
     * Never overwrites an existing entry — live-fed (spawn/event) data always wins.
     */
    fun seedFromSnapshot(sessionId: String, roster: List<SubagentRosterEntry>) {
        var changed = false
        for (entry in roster) {
            if (entry.id.isBlank() || stream(sessionId, entry.id) != null) continue
            val stream = Stream(
                id = entry.id,
                sessionId = sessionId,
                name = entry.subagentType,
                description = entry.description,
                runInBackground = entry.runInBackground,
                todoParser = todoParser,
                status = when (entry.phase) {
                    SubagentRosterPhase.Queued -> SubagentStatus.Queued
                    SubagentRosterPhase.Working -> SubagentStatus.Working
                    SubagentRosterPhase.Suspended -> SubagentStatus.Suspended
                    SubagentRosterPhase.Completed -> SubagentStatus.Completed
                    SubagentRosterPhase.Failed -> SubagentStatus.Failed
                    // A phase this app predates: the transcript-derived status (now
                    // precise, below) is a better guess than a roster lie.
                    SubagentRosterPhase.Unknown -> SubagentStatus.Queued
                },
            )
            stream.sawLifecycle = entry.phase != SubagentRosterPhase.Unknown
            stream.resultSummary = entry.outputPreview
            streams.getOrPut(sessionId) { mutableMapOf() }[entry.id] = stream
            changed = true
        }
        if (changed) publish(sessionId)
    }

    /** True once a transcript page landed for [subagentId] (ticket 046) — the page's
     * cue that a cold-open fetch is unnecessary (the socket keeps the entry fresh). */
    fun hasTranscript(sessionId: String, subagentId: String): Boolean =
        stream(sessionId, subagentId)?.transcriptLoaded == true

    /** The `before_turn` cursor for the next older page; null when no transcript loaded. */
    fun oldestTurnId(sessionId: String, subagentId: String): String? =
        stream(sessionId, subagentId)?.oldestTurnId

    /**
     * Applies the newest transcript page (ticket 046): committed rows are replaced,
     * the in-flight live tail survives per [keepLive] (the completion reconcile passes
     * false — history is the canonical rendering of the finished run). Creates the
     * entry when nothing seeded it (deep state loss: the page's transcript fetch is
     * then its only data source) — the identity fields stay blank, the top bar falls
     * back to the sub-agent id. Status comes from the turn states while no live
     * lifecycle frame was seen.
     */
    fun applyTranscriptPage(sessionId: String, subagentId: String, page: TranscriptPage, keepLive: Boolean) {
        val existing = stream(sessionId, subagentId)
        // An empty page for an unknown id (the server answers one — not an error)
        // creates nothing: the page keeps its "no transcript" empty state.
        if (existing == null && page.turns.isEmpty()) return
        val stream = existing ?: streams.getOrPut(sessionId) { mutableMapOf() }.getOrPut(subagentId) {
            Stream(id = subagentId, sessionId = sessionId, name = "", description = "", runInBackground = false, todoParser = todoParser)
        }
        // Any coalesced text must apply BEFORE the replace wipes/dedups the live rows.
        stream.coalescer.flush()
        stream.timeline.replaceTranscript(page.turns, keepLive)
        stream.transcriptLoaded = true
        stream.oldestTurnId = page.turns.firstOrNull()?.id
        stream.hasMoreTurns = page.hasMore
        if (!stream.sawLifecycle && page.turns.isNotEmpty()) {
            // The cold-status source when no roster/lifecycle frame can help (ticket
            // 085): the real turn states (cancelled/failed are on the wire since
            // 0.31.1), not a blanket-Completed for anything non-Running.
            stream.status = when (page.turns.lastOrNull()?.state) {
                TranscriptState.Running -> SubagentStatus.Working
                TranscriptState.Failed -> SubagentStatus.Failed
                else -> SubagentStatus.Completed
            }
        }
        publish(sessionId)
    }

    /** Prepends an older transcript page (ticket 046 pagination); the turn-id dedup in
     * the timeline makes a racing [applyTranscriptPage] harmless. */
    fun prependTranscriptPage(sessionId: String, subagentId: String, page: TranscriptPage) {
        val stream = stream(sessionId, subagentId) ?: return
        if (page.turns.isNotEmpty()) {
            stream.timeline.prependTranscript(page.turns)
            stream.oldestTurnId = page.turns.first().id
        }
        stream.hasMoreTurns = page.hasMore
        publish(sessionId)
    }

    private fun onLifecycle(sessionId: String, event: SubagentLifecycleEvent) {
        val stream = stream(sessionId, event.subagentId) ?: return
        // A lifecycle transition freezes the stream exactly: publish the coalesced
        // text before the status flips.
        stream.coalescer.flush()
        when (event) {
            is SessionEvent.SubagentSpawned -> Unit // handled by spawn()
            is SessionEvent.SubagentStarted -> {
                stream.sawLifecycle = true
                stream.status = SubagentStatus.Working
            }
            is SessionEvent.SubagentSuspended -> {
                stream.sawLifecycle = true
                stream.status = SubagentStatus.Suspended
            }
            is SessionEvent.SubagentCompleted -> {
                stream.sawLifecycle = true
                stream.status = SubagentStatus.Completed
                stream.resultSummary = event.resultSummary.ifBlank { null }
            }
            is SessionEvent.SubagentFailed -> {
                stream.sawLifecycle = true
                stream.status = SubagentStatus.Failed
                stream.error = event.error.ifBlank { null }
            }
        }
        publish(sessionId)
    }

    private suspend fun onTranscript(sessionId: String, agentId: String, event: SessionEvent) {
        val stream = stream(sessionId, agentId)
        if (stream == null) {
            // The spawn predates the app's subscription: the page shows the empty
            // state and these frames have nowhere to go.
            Log.d(TAG, "Dropping frame for unknown sub-agent '$agentId' (spawn missed)")
            return
        }
        // The coalescer conflates deltas and flushes-before-apply for anything else
        // (ticket 065).
        stream.coalescer.onEvent(event)
    }

    private fun stream(sessionId: String, subagentId: String): Stream? = streams[sessionId]?.get(subagentId)

    private fun publish(sessionId: String) {
        val sessionStreams = streams[sessionId] ?: return
        states.update { it + (sessionId to sessionStreams.mapValues { entry -> entry.value.toState() }) }
    }

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

    /** One string argument of the `Agent` call's args (the row's pretty-printed JSON input). */
    private fun agentArg(input: String, key: String): String? =
        runCatching { json.parseToJsonElement(input).jsonObject[key]?.jsonPrimitive?.content }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun agentRunInBackground(input: String): Boolean =
        runCatching {
            json.parseToJsonElement(input).jsonObject["run_in_background"]?.jsonPrimitive?.booleanOrNull == true
        }.getOrDefault(false)

    private companion object {
        const val TAG = "SubagentStreamStore"

        /** Message ids of the seeded prompt/summary bubbles (LazyColumn keys). */
        const val SEEDED_PROMPT_ID = "seeded_prompt"
        const val SEEDED_SUMMARY_ID = "seeded_summary"
    }
}
