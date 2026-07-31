package fr.louissaglio.kimi.android.ui.chat

/**
 * Generation guard for REST seeds racing the event stream (ticket 066 — one
 * implementation replacing the hand-rolled per-field counters of tickets 028/029
 * and their ticket-041 sibling).
 *
 * A screen-start seed GETs a snapshot and applies the response some time later; an
 * event landing while the GET is in flight is newer than that snapshot, so the late
 * response must be dropped for the tracked field instead of clobbering (or
 * resurrecting) the state the event established. The seed [capture]s the generation
 * at fire time and applies the response only while it is still warm ([isStale]);
 * the event dispatcher [bump]s the guard on every event that moves the tracked
 * state — on BOTH of its edges (turn.started AND turn.ended): ticket 028 showed a
 * one-directional guard only protects one direction (a stale "busy: true" landing
 * after a quick abort's turn.ended would wrongly re-light the indicator).
 *
 * Only screen-start seeds are guarded. The reconnect and turn.ended polls keep
 * their unconditional poll-wins semantics and never capture: their snapshots are
 * taken at the trigger point, and on reconnect a replayed event bumping the
 * generation must not cancel the heal that covers gaps beyond the server's replay
 * buffer (ticket 029).
 *
 * Not thread-safe on purpose — every method is confined to Main: the owners' socket
 * collects and REST callbacks all run on viewModelScope's dispatcher.
 */
internal class SeedGuard {

    private var generation = 0

    /** Snapshots the generation at the moment the seed request is fired. */
    fun capture(): Int = generation

    /** True when [bump] ran since [snapshot] — an event overtook the in-flight
     * seed, so its response is stale and must not be applied. */
    fun isStale(snapshot: Int): Boolean = generation != snapshot

    /** Advances the generation. Called from the event dispatcher on EVERY event
     * that moves the tracked state, on both edges of it. */
    fun bump() {
        generation++
    }
}
