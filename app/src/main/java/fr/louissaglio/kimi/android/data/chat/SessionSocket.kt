package fr.louissaglio.kimi.android.data.chat

import android.util.Log
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The session WebSocket (`/api/v1/ws`, docs/server-api.md): connection management
 * (reconnect loop, resume cursor, backoff), frame parsing/dispatch, and the
 * subscribe/pong frame builders.
 */
internal class SessionSocket(
    client: OkHttpClient,
    private val json: Json,
    private val mapper: ChatMessageMapper,
) {
    // Protocol-level WS pings keep the socket alive through dead NAT routes: the v2
    // server sends no app-level pings (docs/server-api.md, Heartbeat). The read timeout
    // stays disabled so an idle stream is not cut (the shared client sets 30 s for REST).
    private val socketClient = client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /**
     * Resume cursors, per session, at INSTANCE level (ticket 048): the chat screen's
     * socket gate cancels a collection on ON_STOP and starts a fresh one on ON_START;
     * a flow-local cursor would die with it and the missed window (turn.ended above
     * all) would never replay. In-memory only — process death drops the live rows a
     * cursor could resume into anyway, so there is nothing to persist.
     */
    private val cursors = ConcurrentHashMap<String, Cursor>()

    /**
     * Streams [SocketEvent]s for [sessionId]. One collection = one socket owned here
     * (not by the UI); cancelling the collection closes it. On a drop the loop retries
     * with exponential backoff (1 s doubling to 30 s) and re-subscribes with the last
     * seen cursor, so the server replays missed events; the cursor survives the
     * collection itself (see [cursors]), so a later collection resumes too — any
     * subscribe carrying a cursor emits [SocketEvent.Reconnected], even on its first
     * connection of the collection. When the server refuses the cursor (subscribe
     * ack's `resync_required`), it is dropped and the collector is told via
     * [SocketEvent.ResyncRequired] so it can re-sync from REST.
     */
    fun observe(settings: ServerSettings, sessionId: String): Flow<SocketEvent> = flow {
        val wsUrl = buildWsUrl(settings)
        if (wsUrl == null) {
            // Surfaced as an event, not thrown: the history load validates the URL
            // first and owns the user-facing error.
            emit(SocketEvent.Failed(DataError.InvalidUrl))
            return@flow
        }
        val clientId = "android_${UUID.randomUUID()}"
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                runConnection(
                    url = wsUrl.newBuilder().setQueryParameter("client_id", clientId).build(),
                    sessionId = sessionId,
                    cursor = cursors[sessionId],
                    onOpen = {
                        attempt = 0
                        // A subscribe carrying a stored cursor IS a resume — including
                        // the first connection of a collection after a gate reopen
                        // (ticket 048): the collector heals via the Reconnected path.
                        emit(if (cursors.containsKey(sessionId)) SocketEvent.Reconnected else SocketEvent.Connected)
                    },
                    onCursor = { cursors[sessionId] = it },
                    onResyncRequired = {
                        cursors.remove(sessionId)
                        emit(SocketEvent.ResyncRequired)
                    },
                    onEvent = { emit(SocketEvent.Event(it)) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fall through to the reconnect backoff below.
                Log.w(TAG, "Session socket connection failed; scheduling reconnect", e)
            }
            emit(SocketEvent.Disconnected)
            delay(backoffMillis(attempt))
            attempt = (attempt + 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        }
    }

    private data class Cursor(val seq: Long, val epoch: String)

    private sealed interface Signal {
        data object Opened : Signal
        data class Text(val text: String) : Signal
        data object Closed : Signal
        data class Failed(val cause: Throwable) : Signal
    }

    /**
     * Runs one socket connection until it closes or fails. Listener callbacks (OkHttp
     * threads) feed a channel consumed in this coroutine, so [onOpen]/[onEvent] run
     * sequentially on the collector's context.
     */
    private suspend fun FlowCollector<SocketEvent>.runConnection(
        url: HttpUrl,
        sessionId: String,
        cursor: Cursor?,
        onOpen: suspend () -> Unit,
        onCursor: (Cursor) -> Unit,
        onResyncRequired: suspend () -> Unit,
        onEvent: suspend (SessionEvent) -> Unit,
    ) {
        // UNLIMITED is not a backpressure risk worth bounding: the collector
        // (ChatViewModel) coalesces streaming deltas before any heavy work (UiState
        // emission → full markdown re-parse), so a frame burst can only ever queue
        // cheap unparsed strings here, never unbounded expensive downstream work.
        val signals = Channel<Signal>(Channel.UNLIMITED)
        val webSocket = socketClient.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    signals.trySend(Signal.Opened)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    signals.trySend(Signal.Text(text))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                    signals.trySend(Signal.Closed)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    signals.trySend(Signal.Closed)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    signals.trySend(Signal.Failed(t))
                }
            },
        )
        try {
            while (true) {
                when (val signal = signals.receive()) {
                    Signal.Opened -> {
                        onOpen()
                        webSocket.send(subscribeFrame(sessionId, cursor))
                    }
                    is Signal.Text ->
                        handleFrame(signal.text, webSocket, sessionId, onCursor, onResyncRequired, onEvent)
                    Signal.Closed -> return
                    is Signal.Failed -> throw IOException("WebSocket failed", signal.cause)
                }
            }
        } finally {
            webSocket.cancel()
            signals.close()
        }
    }

    /** One bad frame must not kill the connection: parse failures are dropped. */
    private suspend fun handleFrame(
        text: String,
        webSocket: WebSocket,
        sessionId: String,
        onCursor: (Cursor) -> Unit,
        onResyncRequired: suspend () -> Unit,
        onEvent: suspend (SessionEvent) -> Unit,
    ) {
        val frame = try {
            json.decodeFromString<WsFrame>(text)
        } catch (e: SerializationException) {
            Log.w(TAG, "Dropping malformed frame", e)
            return
        }
        // Volatile frames share the seq of the event they piggyback on (docs/server-api.md):
        // advancing the resume cursor on them could skip the substantive event on replay.
        if (frame.seq != null && frame.epoch != null && !frame.volatile) onCursor(Cursor(frame.seq, frame.epoch))
        // Sub-agents stream their whole turn over the same session channel, tagged with
        // their own agentId (docs/server-api.md, Sub-agents). Transcript frames emit
        // their events tagged with the emitting agent; the collector routes sub-agent
        // events to the sub-agent stream store (SessionEvent.isSubagentScoped, ticket
        // 045) so they never reach the main chat. Interaction families stay untagged:
        // a sub-agent's approval/question is actionable whoever raised it.
        val agentId = frame.agentId()
        when (frame.type) {
            // Legacy protocol: pre-v2 servers closed the connection when app-level pings
            // went unanswered; v2 sends none, answering stays harmless (docs/server-api.md).
            "ping" -> frame.payload
                ?.let {
                    runCatching { json.decodeFromJsonElement(PingPayload.serializer(), it) }
                        .onFailure { e -> Log.w(TAG, "Dropping undecodable 'ping' payload", e) }
                        .getOrNull()
                }
                ?.let { webSocket.send(pongFrame(it.nonce)) }
            "assistant.delta" -> frame.decodePayload<DeltaPayload>()
                ?.let { onEvent(SessionEvent.AssistantDelta(it.delta, agentId)) }
            "thinking.delta" -> frame.decodePayload<DeltaPayload>()
                ?.let { onEvent(SessionEvent.ThinkingDelta(it.delta, agentId)) }
            "tool.call.delta" -> frame.decodePayload<ToolCallPayload>()
                ?.let { onEvent(SessionEvent.ToolCallDelta(it.toolCallId, it.name, agentId)) }
            "tool.call.started" -> frame.decodePayload<ToolCallPayload>()
                ?.let {
                    onEvent(
                        SessionEvent.ToolCallStarted(
                            it.toolCallId, it.name, mapper.preview(it), mapper.formatDetail(it.args), agentId,
                        ),
                    )
                }
            "tool.result" -> frame.decodePayload<ToolResultPayload>()
                ?.let { onEvent(SessionEvent.ToolCallResult(it.toolCallId, it.isError, mapper.formatDetail(it.output), agentId)) }
            // Volatile live stdout streaming (docs/server-api.md); the update kind is
            // passed through as-is — only its text is consumed.
            "tool.progress" -> frame.decodePayload<ToolProgressPayload>()
                ?.let { onEvent(SessionEvent.ToolCallProgress(it.toolCallId, it.update?.text.orEmpty(), agentId)) }
            // Legacy approval family: the server ALSO emits the v2
            // `permission.approval.*` duplicates of these frames (docs/server-api.md,
            // Permissions), which are deliberately ignored here — handling both would
            // double the cards. If a future server drops the legacy family, approvals
            // must switch to the v2 frames.
            "event.approval.requested" -> frame.decodePayload<ApprovalRequestedPayload>()
                ?.let { onEvent(SessionEvent.ApprovalRequested(it.approvalId, it.toolName, mapper.summary(it))) }
            "event.approval.resolved" -> frame.decodePayload<ApprovalResolvedPayload>()
                ?.let { onEvent(SessionEvent.ApprovalResolved(it.approvalId)) }
            "event.question.requested" -> frame.decodePayload<QuestionRequestedPayload>()
                ?.let { onEvent(SessionEvent.QuestionRequested(mapper.toDomain(it))) }
            "event.question.answered", "event.question.dismissed" ->
                frame.decodePayload<QuestionResolvedPayload>()
                    ?.let { onEvent(SessionEvent.QuestionResolved(it.questionId)) }
            "turn.started" -> onEvent(SessionEvent.TurnStarted(agentId))
            "turn.ended" -> onEvent(SessionEvent.TurnEnded(frame.decodePayload<TurnEndedPayload>()?.reason.orEmpty(), agentId))
            // Volatile (docs/server-api.md): the model/usage variant carries the token
            // counts; the phase variant has them null and emits nothing (ticket 041).
            "agent.status.updated" -> frame.decodePayload<AgentStatusUpdatedPayload>()
                ?.let { payload ->
                    val used = payload.contextTokens
                    val max = payload.maxContextTokens
                    if (used != null && max != null) {
                        onEvent(SessionEvent.ContextUsageUpdated(ContextUsage(used, max), agentId))
                    }
                }
            "context.spliced" -> frame.decodePayload<ContextSplicedPayload>()
                ?.let { onEvent(SessionEvent.ContextSpliced(it.messages.map { msg -> mapper.toDomain(msg) }, agentId)) }
            // subagent.* lifecycle (docs/server-api.md, Sub-agents): tagged agentId
            // "main" but scoped to one sub-agent by the payload's subagentId; routed to
            // the sub-agent stream store like the sub-agent's own frames.
            "subagent.spawned" -> frame.decodePayload<SubagentSpawnedPayload>()
                ?.let {
                    onEvent(
                        SessionEvent.SubagentSpawned(
                            it.subagentId, it.subagentName, it.parentToolCallId, it.description, it.runInBackground,
                        ),
                    )
                }
            "subagent.started" -> frame.decodePayload<SubagentLifecyclePayload>()
                ?.let { onEvent(SessionEvent.SubagentStarted(it.subagentId)) }
            "subagent.completed" -> frame.decodePayload<SubagentLifecyclePayload>()
                ?.let { onEvent(SessionEvent.SubagentCompleted(it.subagentId, it.resultSummary.orEmpty())) }
            "subagent.failed" -> frame.decodePayload<SubagentLifecyclePayload>()
                ?.let { onEvent(SessionEvent.SubagentFailed(it.subagentId, it.error.orEmpty())) }
            "subagent.suspended" -> frame.decodePayload<SubagentLifecyclePayload>()
                ?.let { onEvent(SessionEvent.SubagentSuspended(it.subagentId)) }
            // The subscribe ack (docs/server-api.md): a refused resume cursor
            // (resync_required — replay buffer overrun) means no replay will come;
            // the cursor is dropped and the collector re-syncs from REST (ticket 048).
            // accepted/not_found need no handling.
            "ack" -> frame.decodePayload<AckPayload>()
                ?.takeIf { sessionId in it.resyncRequired }
                ?.let { onResyncRequired() }
            // server_hello and events we don't render yet: ignore.
        }
    }

    private inline fun <reified T> WsFrame.decodePayload(): T? =
        payload?.let {
            runCatching { json.decodeFromJsonElement<T>(it) }
                .onFailure { e -> Log.w(TAG, "Dropping undecodable '$type' payload", e) }
                .getOrNull()
        }

    /** The emitting agent of a frame; absent or undecodable reads as the main agent. */
    private fun WsFrame.agentId(): String =
        payload?.let {
            runCatching { json.decodeFromJsonElement<AgentIdPeek>(it) }
                .onFailure { e -> Log.w(TAG, "Dropping undecodable '$type' payload agentId", e) }
                .getOrNull()
        }?.agentId ?: MAIN_AGENT_ID

    private fun buildWsUrl(settings: ServerSettings): HttpUrl? =
        // OkHttp's HttpUrl holds only http/https; newWebSocket derives ws/wss itself.
        settings.serverUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegments("api/v1/ws")
            ?.build()

    private fun subscribeFrame(sessionId: String, cursor: Cursor?): String = buildJsonObject {
        put("type", "subscribe")
        put("id", "c_1")
        putJsonObject("payload") {
            putJsonArray("session_ids") { add(sessionId) }
            if (cursor != null) {
                putJsonObject("cursors") {
                    putJsonObject(sessionId) {
                        put("seq", cursor.seq)
                        put("epoch", cursor.epoch)
                    }
                }
            }
        }
    }.toString()

    private fun pongFrame(nonce: String): String = buildJsonObject {
        put("type", "pong")
        putJsonObject("payload") { put("nonce", nonce) }
    }.toString()

    private companion object {
        const val TAG = "SessionSocket"
        const val MAX_BACKOFF_SHIFT = 5 // 1 s << 5 = 32 s, clamped to 30 s below

        fun backoffMillis(attempt: Int): Long = (1_000L shl attempt).coerceAtMost(30_000L)
    }
}
