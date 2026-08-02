package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.SessionSnapshot
import fr.louissaglio.kimi.android.data.chat.SubagentRosterEntry
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * The session-scoped owner of the snapshot cold start (ticket 085 — next to
 * [SubagentStreamStore], NOT inside ChatViewModel, because the sub-agent page's cold
 * path never recreates one, ticket 064): `GET .../snapshot` is the single
 * cold-start/resync entry point, fetched BEFORE the session's first socket
 * subscribe, once per session per process — whichever collection opens first (the
 * chat's socket gate or the store's keep-alive) awaits the same fetch here.
 *
 * On success the fetch applies the session-scope state itself — the socket resume
 * cursor (the first subscribe stitches gaplessly onto the watermark) and the
 * sub-agent roster seed (identity + precise phase + summary, closing the "spawn
 * missed" frame-drop window) — and hands the chat-side bundle to the CALLER that
 * created the fetch ([Outcome.Fresh]). Callers joining an in-flight or completed
 * fetch get [Outcome.AlreadyFresh]: no chat-side bundle to re-apply (the
 * within-process resume machinery, ticket 048, owns their heal) and no roster
 * re-seed — a process-lifetime cached roster would re-seed a released store with
 * stale phases a transcript can then never regress (sawLifecycle), so the roster
 * rides along only for the chat timeline's idempotent row links.
 *
 * `resync_required` re-runs the fetch ([resync]).
 *
 * Failure handling: none beyond the ordinary load-error path — a failed fetch is
 * forgotten, so the next [sync] call retries; there is deliberately no fallback to
 * the pre-085 burst of ad-hoc REST calls.
 */
@Singleton
class SnapshotSync @Inject constructor(
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    // Provider-lazy: the store takes SnapshotSync directly (its keep-alive awaits the
    // sync), so the reverse edge must resolve at runtime, not at graph construction.
    private val subagentStore: Provider<SubagentStreamStore>,
) {

    /** The outcome of [sync]; [resync] always fetches and always returns the bundle. */
    sealed interface Outcome {
        /** This call created the fetch: the caller (a ChatViewModel) applies the bundle. */
        data class Fresh(val snapshot: SessionSnapshot) : Outcome

        /** The session already synced in this process: nothing to re-apply; [roster]
         * rides along for the chat timeline's idempotent `Agent` row links only. */
        data class AlreadyFresh(val roster: List<SubagentRosterEntry>) : Outcome
    }

    // Lazy, like the store's: JVM tests that never open a socket path have no Main
    // dispatcher to reference.
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    // sessionId → the process-lifetime cold-start fetch (in-flight or done). The
    // shared Deferred lives on [scope]: a caller's cancellation (a socket-gate flip)
    // never cancels the fetch for the others. A FAILED fetch is removed — it never
    // counts as fresh, so the next call retries. All map access is Main-confined.
    private val coldStarts = mutableMapOf<String, Deferred<Result<SessionSnapshot>>>()

    /** The cold-start entry point: the first caller per session per process fetches;
     * concurrent and later callers join the same fetch. */
    suspend fun sync(sessionId: String): Result<Outcome> {
        val running = coldStarts[sessionId]
        if (running != null) {
            val result = running.await()
            if (result.isFailure && coldStarts[sessionId] === running) coldStarts.remove(sessionId)
            return result.map { Outcome.AlreadyFresh(it.subagents) }
        }
        val deferred = scope.async { fetchAndApply(sessionId) }
        coldStarts[sessionId] = deferred
        val result = deferred.await()
        if (result.isFailure && coldStarts[sessionId] === deferred) coldStarts.remove(sessionId)
        return result.map { Outcome.Fresh(it) }
    }

    /** The `resync_required` re-fetch (ticket 085): the server refused the resume
     * cursor, so no replay will come — the missed window heals from a fresh snapshot.
     * Always fetches and becomes the session's cold-start state. */
    suspend fun resync(sessionId: String): Result<SessionSnapshot> {
        val deferred = scope.async { fetchAndApply(sessionId) }
        coldStarts[sessionId] = deferred
        val result = deferred.await()
        if (result.isFailure && coldStarts[sessionId] === deferred) coldStarts.remove(sessionId)
        return result
    }

    /** The session-scope apply shared by both fetches: cursor first (the next
     * subscribe resumes at the watermark), then the roster seed (the store entries
     * exist before any subscribe, so live frames accumulate from the first one). */
    private suspend fun fetchAndApply(sessionId: String): Result<SessionSnapshot> {
        val result = chatRepository.getSnapshot(settingsRepository.current(), sessionId)
        result.onSuccess { snapshot ->
            chatRepository.seedSessionCursor(sessionId, snapshot.asOfSeq, snapshot.epoch)
            subagentStore.get().seedFromSnapshot(sessionId, snapshot.subagents)
        }
        return result
    }
}
