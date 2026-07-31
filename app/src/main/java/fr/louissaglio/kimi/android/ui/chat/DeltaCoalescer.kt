package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The streaming-delta conflation state machine shared by ChatViewModel and
 * SubagentStreamStore (ticket 065 — one implementation replacing two verbatim
 * copies). Streaming deltas (assistant/thinking) are conflated here so a published
 * update — and with it MarkdownBody, which re-parses the whole streaming bubble per
 * emission — happens at most once per [windowMillis] during a burst instead of once
 * per socket frame.
 *
 * Semantics (window > 0):
 * - The FIRST delta of a burst applies+publishes immediately: the bubble starts
 *   rendering without waiting out a window.
 * - Later deltas accumulate in [pendingDeltas] (consecutive same-kind deltas merge
 *   into one append) and publish once per window from [runFlushLoop]; a full quiet
 *   window closes the burst, re-arming the leading edge.
 * - ANY non-delta event routed through [onEvent] flushes the pending text first, so
 *   no substantive event is ever delayed behind coalesced text and the pre-event
 *   state is exact. No delta is lost and order is preserved: a flush replays the
 *   buffered events in arrival order. The flush-before-apply invariant lives in this
 *   structure, not at call sites — ticket 062 proved it gets forgotten (the main
 *   chat's REST refetch path missed the flush its socket twin had).
 *
 * With window = 0 every delta publishes on arrival (the pre-conflation behavior).
 *
 * All methods are confined to the Main dispatcher: the owners' socket collects, the
 * flush loop, and the lifecycle/REST callers all run on — or are called from —
 * Main, keeping the state machine single-threaded.
 *
 * [scope] is a provider, not a value: SubagentStreamStore's JVM unit tests run with
 * window = 0 (no flush job ever launches) and have no Main dispatcher to reference,
 * so the provider must only be invoked when a burst actually opens.
 */
internal class DeltaCoalescer(
    private val windowMillis: Long,
    private val scope: () -> CoroutineScope,
    /** Applies one stream delta to the timeline WITHOUT publishing — the coalescer
     * owns every publish point. Only ever receives deltas. */
    private val applyDelta: (SessionEvent) -> Unit,
    /** Handles a non-delta event INCLUDING its publication; called after the flush. */
    private val applyEvent: suspend (SessionEvent) -> Unit,
    /** Publishes the timeline's current state. */
    private val publish: () -> Unit,
) {

    /** Deltas received but not yet published; null = no burst open. Holds only
     * [SessionEvent.AssistantDelta]/[SessionEvent.ThinkingDelta], consecutive
     * same-kind entries merged into one. */
    private var pendingDeltas: MutableList<SessionEvent>? = null

    /** The trailing-flush timer of an open burst; null when no burst is open. */
    private var flushJob: Job? = null

    /** The single entry point for timeline-bound events: stream deltas coalesce; ANY
     * other event flushes the pending deltas first, then applies — callers cannot
     * forget the invariant. */
    suspend fun onEvent(event: SessionEvent) {
        if (event.isStreamDelta()) {
            onStreamDelta(event)
        } else {
            flush()
            applyEvent(event)
        }
    }

    /** Applies any coalesced deltas NOW, in arrival order, publishes once, and closes
     * the burst. Runs before every non-delta event (via [onEvent]) and directly from
     * the paths that mutate the timeline off the socket dispatch: the REST history/
     * transcript refetches (ticket 062's fix), lifecycle transitions, and ON_STOP.
     * No-op outside a burst. */
    fun flush() {
        flushJob?.cancel()
        flushJob = null
        val pending = pendingDeltas ?: return
        pendingDeltas = null
        if (pending.isEmpty()) return
        pending.forEach(applyDelta)
        publish()
    }

    /** Drops an open burst WITHOUT applying it — the owner is going away (session
     * released), so the buffered text has nowhere to render. */
    fun cancel() {
        flushJob?.cancel()
        flushJob = null
        pendingDeltas = null
    }

    /** One delta applied+published on its own — the leading edge, and every delta
     * when the window is 0. */
    private fun onStreamDelta(event: SessionEvent) {
        if (windowMillis <= 0L) {
            applyDelta(event)
            publish()
            return
        }
        val pending = pendingDeltas
        if (pending == null) {
            // Leading edge of a burst: publish immediately, then coalesce until the
            // stream pauses for a full window.
            applyDelta(event)
            publish()
            pendingDeltas = mutableListOf()
            flushJob = scope().launch { runFlushLoop() }
        } else {
            pending.mergeDelta(event)
        }
    }

    /** Trailing flush of an open burst: publishes the accumulated deltas once per
     * window; a window with nothing new closes the burst so the next delta renders
     * on its leading edge again. */
    private suspend fun runFlushLoop() {
        while (true) {
            delay(windowMillis)
            val pending = pendingDeltas ?: return
            if (pending.isEmpty()) {
                pendingDeltas = null
                flushJob = null
                return
            }
            pending.forEach(applyDelta)
            pending.clear()
            publish()
        }
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

    private fun SessionEvent.isStreamDelta(): Boolean =
        this is SessionEvent.AssistantDelta || this is SessionEvent.ThinkingDelta
}
