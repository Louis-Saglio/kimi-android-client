package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.AGENT_TOOL_NAME
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.SessionSnapshot
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
 * subscribe — whichever collection opens first (the chat's socket gate or the
 * store's keep-alive) awaits the same fetch here.
 *
 * Freshness (ticket 091): the fetch is cached per session only until the session is
 * LEFT — [SubagentStreamStore.release] calls [forget], so the next entry RE-FETCHES
 * instead of joining a process-lifetime cache of mutable server state (the web
 * client's model: one cheap GET per entry; a fresh roster heals through the store's
 * precedence funnel instead of being skipped as 085's AlreadyFresh did). The cache
 * still serves the within-process paths with zero added fetches: chat → page → chat
 * never releases (the ViewModel stays in the back stack), and process death starts
 * with an empty map by construction.
 *
 * On success the fetch applies the session-scope state itself — the socket resume
 * cursor (the first subscribe stitches gaplessly onto the watermark), the sub-agent
 * roster (identity + phase + summary through the store's precedence funnel), and
 * the snapshot's `Agent`-call mining (the messages page and
 * `in_flight_turn.running_tools` — chat-INDEPENDENT, so the page-on-top cold
 * restore recovers prompts and links too) — and hands the chat-side bundle to the
 * CALLER that created the fetch ([Outcome.Fresh]). Callers joining an in-flight or
 * completed fetch get [Outcome.AlreadyFresh]: no chat-side bundle to re-apply (the
 * within-process resume machinery, ticket 048, owns their heal); the sub-agent row
 * links re-drive from the store's registry, which survived.
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

        /** The session already synced since it was last left: nothing to re-apply
         * (the within-process resume machinery owns the heal; the sub-agent store
         * kept its records and links). */
        data object AlreadyFresh : Outcome
    }

    // Lazy, like the store's: JVM tests that never open a socket path have no Main
    // dispatcher to reference.
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    // sessionId → the cold-start fetch (in-flight or done), valid until the session
    // is left ([forget]) or a `resync_required` lands ([resync]). The shared
    // Deferred lives on [scope]: a caller's cancellation (a socket-gate flip) never
    // cancels the fetch for the others. A FAILED fetch is removed — it never counts
    // as fresh, so the next call retries. All map access is Main-confined.
    private val coldStarts = mutableMapOf<String, Deferred<Result<SessionSnapshot>>>()

    /** The cold-start entry point: the first caller per session (since it was last
     * left) fetches; concurrent and later callers join the same fetch. */
    suspend fun sync(sessionId: String): Result<Outcome> {
        val running = coldStarts[sessionId]
        if (running != null) {
            val result = running.await()
            if (result.isFailure && coldStarts[sessionId] === running) coldStarts.remove(sessionId)
            return result.map { Outcome.AlreadyFresh }
        }
        val deferred = scope.async { fetchAndApply(sessionId) }
        coldStarts[sessionId] = deferred
        val result = deferred.await()
        if (result.isFailure && coldStarts[sessionId] === deferred) coldStarts.remove(sessionId)
        return result.map { Outcome.Fresh(it) }
    }

    /** Forgets the session's cold start (ticket 091): called from
     * [SubagentStreamStore.release] — the session-left signal — so the next entry
     * re-fetches `/snapshot` instead of reusing a roster that predates everything
     * that happened while the session was away. */
    fun forget(sessionId: String) {
        coldStarts.remove(sessionId)
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
     * subscribe resumes at the watermark), then the roster and the `Agent`-call
     * mining (the store records exist before any subscribe, so live frames
     * accumulate from the first one — and the page-on-top cold restore recovers
     * prompts/links without any ChatViewModel, ticket 091). */
    private suspend fun fetchAndApply(sessionId: String): Result<SessionSnapshot> {
        val result = chatRepository.getSnapshot(settingsRepository.current(), sessionId)
        result.onSuccess { snapshot ->
            chatRepository.seedSessionCursor(sessionId, snapshot.asOfSeq, snapshot.epoch)
            val store = subagentStore.get()
            store.applyRoster(sessionId, snapshot.subagents)
            mineAgentCalls(snapshot).forEach { call ->
                store.applyAgentCall(sessionId, call.toolCallId, call.argsJson, call.resultText, call.rowStatus)
            }
        }
        return result
    }

    /** One mined `Agent` call: [argsJson] verbatim from the carrier, [resultText]
     * null while no result exists (running), [rowStatus] the row-level outcome. */
    private data class MinedAgentCall(
        val toolCallId: String,
        val argsJson: String,
        val resultText: String?,
        val rowStatus: ToolStatus,
    )

    /** The snapshot's `Agent` calls (ticket 091): the newest messages page carries
     * the calls and their committed results; `in_flight_turn.running_tools` the
     * still-running ones' verbatim args. Older pages are deliberately NOT mined: a
     * call that scrolled past the newest page belongs to a finished run whose
     * result marker already carries the metadata. */
    private fun mineAgentCalls(snapshot: SessionSnapshot): List<MinedAgentCall> {
        val results = mutableMapOf<String, ChatMessage.Part.ToolResult>()
        snapshot.messages.items.forEach { message ->
            message.parts.filterIsInstance<ChatMessage.Part.ToolResult>().forEach { results[it.toolCallId] = it }
        }
        val calls = mutableListOf<MinedAgentCall>()
        snapshot.messages.items.forEach { message ->
            message.parts.filterIsInstance<ChatMessage.Part.ToolUse>()
                .filter { it.toolName == AGENT_TOOL_NAME }
                .forEach { use ->
                    val result = results[use.toolCallId]
                    calls += MinedAgentCall(
                        toolCallId = use.toolCallId,
                        argsJson = use.input,
                        resultText = result?.output?.ifBlank { null },
                        rowStatus = when {
                            result == null -> ToolStatus.Running
                            result.isError -> ToolStatus.Error
                            else -> ToolStatus.Done
                        },
                    )
                }
        }
        snapshot.inFlightTurn?.runningTools.orEmpty()
            .filter { it.name == AGENT_TOOL_NAME }
            .forEach { tool ->
                calls += MinedAgentCall(
                    toolCallId = tool.toolCallId,
                    argsJson = tool.args?.toString().orEmpty(),
                    resultText = null,
                    rowStatus = ToolStatus.Running,
                )
            }
        return calls
    }
}
