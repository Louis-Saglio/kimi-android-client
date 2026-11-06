package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.AGENT_TOOL_NAME
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.MAIN_AGENT_ID
import fr.louissaglio.kimi.android.data.chat.MessagePage
import fr.louissaglio.kimi.android.data.chat.PromptQueue
import fr.louissaglio.kimi.android.data.chat.TranscriptFrame
import fr.louissaglio.kimi.android.data.chat.TranscriptPage
import fr.louissaglio.kimi.android.data.chat.TranscriptTurn
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The session-scoped owner of the chat's cold start (ticket 176 — the server 2.5.0
 * replacement of 085's snapshot sync; next to [SubagentStreamStore], NOT inside
 * ChatViewModel, because the sub-agent page's cold path never recreates one, ticket
 * 064). One cold start is three parallel REST reads, taken BEFORE the session's first
 * socket subscribe — whichever collection opens first (the chat's socket gate or the
 * store's keep-alive) awaits the same fetch here:
 *
 * - `GET .../messages` — the newest history page (the unchanged bubble source);
 * - `GET .../transcript?agent_id=main` — the newest turn (a running turn ships its
 *   live steps/frames) plus the session's global state: pending interactions, prompt
 *   entities, the agent roster and the `seq` op watermark;
 * - `GET .../prompts` — the prompt queue, so a queued prompt shows on entry.
 *
 * Freshness (ticket 091): the fetch is cached per session only until the session is
 * LEFT — [SubagentStreamStore.release] calls [forget], so the next entry RE-FETCHES
 * instead of joining a process-lifetime cache of mutable server state (one cheap round
 * per entry). The cache still serves the within-process paths with zero added
 * fetches: chat → page → chat never releases (the ViewModel stays in the back stack),
 * and process death starts with an empty map by construction.
 *
 * On success the fetch applies the session-scope state itself — the main agent's fold
 * and resume watermark on the session socket (the next subscribe sends
 * `transcript_since` from the page's `seq`, so the live ops stitch gaplessly onto the
 * page), the sub-agent roster (the transcript `agents`, identity only) and the
 * `Agent`-call mining (the messages page and the running turn's tool frames —
 * chat-INDEPENDENT, so the page-on-top cold restore recovers prompts and links too) —
 * and hands the chat-side [Bundle] to the CALLER that created the fetch
 * ([Outcome.Fresh]). Callers joining an in-flight or completed fetch get
 * [Outcome.AlreadyFresh]: no bundle — the socket may have folded past it since.
 *
 * The forced re-fetch ([resync]) serves the 40932 SESSION_STALE recovery, a chat that
 * joined someone else's fetch, and a stale journal (the resume could not be covered —
 * a `transcript/ops` probe answering `complete:false`, or a `transcript.reset`
 * answering the subscribe's watermark).
 *
 * Failure handling: none beyond the ordinary load-error path — a failed fetch (any of
 * the three reads) is forgotten, so the next [sync] call retries; there is
 * deliberately no fallback to the removed `/snapshot` or a burst of ad-hoc REST calls.
 */
@Singleton
class TranscriptColdStart @Inject constructor(
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    // Provider-lazy: the store takes the cold start directly (its keep-alive awaits
    // it), so the reverse edge must resolve at runtime, not at graph construction.
    private val subagentStore: Provider<SubagentStreamStore>,
) {

    /** The chat-side cold-start state: the history page, the main transcript page (its
     * [TranscriptPage.runningTurn], pending interactions and prompt entities) and the
     * prompt queue. */
    data class Bundle(
        val messages: MessagePage,
        val transcript: TranscriptPage,
        val queue: PromptQueue,
    ) {
        /** The main agent's in-flight turn, seeded as live rows until live ops take over. */
        val runningTurn: TranscriptTurn?
            get() = transcript.runningTurn

        /** Mid-turn: a running turn, or a prompt the queue reports active. */
        val busy: Boolean
            get() = runningTurn != null || queue.active != null
    }

    /** The outcome of [sync]; [resync] always fetches and always returns the bundle. */
    sealed interface Outcome {
        /** This call created the fetch: the caller (a ChatViewModel) applies the bundle. */
        data class Fresh(val bundle: Bundle) : Outcome

        /** The session already synced since it was last left: nothing to re-apply here
         * (the sub-agent store kept its records and links; a chat that needs rows
         * calls [resync]). */
        data object AlreadyFresh : Outcome
    }

    // Lazy, like the store's: JVM tests that never open a socket path have no Main
    // dispatcher to reference.
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    // sessionId → the cold-start fetch (in-flight or done), valid until the session
    // is left ([forget]) or a forced re-fetch lands ([resync]). The shared
    // Deferred lives on [scope]: a caller's cancellation (a socket-gate flip) never
    // cancels the fetch for the others. A FAILED fetch is removed — it never counts
    // as fresh, so the next call retries. All map access is Main-confined.
    private val coldStarts = mutableMapOf<String, Deferred<Result<Bundle>>>()

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
     * re-fetches instead of reusing a state that predates everything that happened
     * while the session was away. */
    fun forget(sessionId: String) {
        coldStarts.remove(sessionId)
    }

    /** The forced re-fetch: always fetches and becomes the session's cold-start state. */
    suspend fun resync(sessionId: String): Result<Bundle> {
        val deferred = scope.async { fetchAndApply(sessionId) }
        coldStarts[sessionId] = deferred
        val result = deferred.await()
        if (result.isFailure && coldStarts[sessionId] === deferred) coldStarts.remove(sessionId)
        return result
    }

    /** Awaits every cold start still in flight, for tests: a fetch lives on this
     * singleton's own scope, so a ViewModel's cancellation never stops it — joining it
     * in tearDown keeps a late response from resuming on Main after resetMain (the
     * ticket-049 leak class, like [SubagentStreamStore.keepAliveJob]). */
    internal suspend fun awaitInFlight() {
        coldStarts.values.toList().forEach { it.await() }
    }

    /** The three reads in parallel, then the session-scope apply shared by both
     * fetches: the socket seed, the roster and the `Agent`-call mining (the store
     * records exist before any subscribe, so live frames accumulate from the first one
     * — and the page-on-top cold restore recovers prompts/links without any
     * ChatViewModel, ticket 091). */
    private suspend fun fetchAndApply(sessionId: String): Result<Bundle> {
        val settings = settingsRepository.current()
        val result = coroutineScope {
            val messages = async { chatRepository.getMessages(settings, sessionId) }
            val transcript = async {
                chatRepository.getTranscript(settings, sessionId, MAIN_AGENT_ID, beforeTurn = null, pageSize = TRANSCRIPT_TURNS)
            }
            val queue = async { chatRepository.getPromptQueue(settings, sessionId) }
            // The repositories never throw (their failures are Result values): the
            // first failed read fails the whole cold start.
            val page = messages.await().getOrElse { return@coroutineScope Result.failure(it) }
            val turns = transcript.await().getOrElse { return@coroutineScope Result.failure(it) }
            val prompts = queue.await().getOrElse { return@coroutineScope Result.failure(it) }
            Result.success(Bundle(page, turns, prompts))
        }
        result.onSuccess { bundle ->
            chatRepository.seedTranscript(sessionId, bundle.transcript)
            val store = subagentStore.get()
            store.applyRoster(sessionId, bundle.transcript.agents)
            mineAgentCalls(bundle).forEach { call ->
                // The miner filters on AGENT_TOOL_NAME, so the call is always a plain
                // `Agent` one — AgentSwarm calls are mined by ChatViewModel only
                // (ticket 134).
                store.applyAgentCall(
                    sessionId, call.toolCallId, call.argsJson, call.resultText, call.rowStatus,
                    toolName = AGENT_TOOL_NAME,
                )
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

    /** The cold start's `Agent` calls (ticket 091, carriers moved by 176): the newest
     * messages page carries the calls and their committed results; the running turn's
     * tool frames the in-flight ones (verbatim args, a settled frame's output). Older
     * pages are deliberately NOT mined: a call that scrolled past the newest page
     * belongs to a finished run whose result marker already carries the metadata. */
    private fun mineAgentCalls(bundle: Bundle): List<MinedAgentCall> {
        val results = mutableMapOf<String, ChatMessage.Part.ToolResult>()
        bundle.messages.items.forEach { message ->
            message.parts.filterIsInstance<ChatMessage.Part.ToolResult>().forEach { results[it.toolCallId] = it }
        }
        val calls = mutableListOf<MinedAgentCall>()
        bundle.messages.items.forEach { message ->
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
        val mined = calls.mapTo(HashSet()) { it.toolCallId }
        bundle.runningTurn?.steps.orEmpty()
            .flatMap { it.frames }
            .filterIsInstance<TranscriptFrame.Tool>()
            .filter { it.name == AGENT_TOOL_NAME && it.toolCallId !in mined }
            .forEach { tool ->
                calls += MinedAgentCall(
                    toolCallId = tool.toolCallId,
                    argsJson = tool.args?.toString().orEmpty(),
                    resultText = if (tool.running) null else tool.output.ifBlank { null },
                    rowStatus = when {
                        tool.running -> ToolStatus.Running
                        tool.failed -> ToolStatus.Error
                        else -> ToolStatus.Done
                    },
                )
            }
        return calls
    }

    private companion object {
        /** Turns of the cold start's transcript page: only the newest one is read (its
         * running state, steps and frames); the global state ships with any page. */
        const val TRANSCRIPT_TURNS = 1
    }
}
