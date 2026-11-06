package fr.louissaglio.kimi.android.data.chat

import android.util.Log
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.network.SocketStallWatchdog
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The session WebSocket (`/api/v1/ws`, docs/server-api.md → WebSocket): connection
 * management (reconnect loop, backoff, stall watchdog), the `subscribe_v2` frame, and
 * frame dispatch — `transcript.ops` batches folded into [SessionEvent]s by the
 * session's [TranscriptFold] (server 2.5.0, ticket 175).
 */
@Singleton
class SessionSocket @Inject constructor(
    client: OkHttpClient,
    private val json: Json,
    private val mapper: ChatMessageMapper,
) {
    // Protocol-level WS pings keep the socket alive through dead NAT routes; the
    // app-level heartbeat (docs/server-api.md, Heartbeat) is answered in handleFrame.
    // The read timeout stays disabled so an idle stream is not cut (the shared client
    // sets 30 s for REST) — a silently-stalled socket is the stall watchdog's job
    // (ticket 152, see runConnection).
    private val socketClient = client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /**
     * The transcript fold, per session, at INSTANCE level (the ticket-048 rationale the
     * old resume cursor had): the chat screen's socket gate cancels a collection on
     * ON_STOP and starts a fresh one on ON_START; a flow-local fold would forget which
     * frame text the timeline already shows, and the re-subscribe's ops would re-emit
     * it. Exactly one collection feeds a session at a time (ticket 064), so the fold is
     * never shared between two live connections.
     */
    private val folds = ConcurrentHashMap<String, TranscriptFold>()

    /**
     * Streams [SocketEvent]s for [sessionId]. One collection = one socket owned here
     * (not by the UI); cancelling the collection closes it. Each connection sends one
     * `subscribe_v2` (grade `delta` for every agent — the server auto-subscribes the
     * session's sub-agents, whose ops carry their own `agent_id`). On a drop the loop
     * retries with exponential backoff (1 s doubling to 30 s) and re-subscribes — the
     * subscription does not survive a disconnect — emitting [SocketEvent.Reconnected]
     * so the collector re-syncs from REST. A subscribe acked `not_found` ends the flow
     * with [SocketEvent.Failed] ([DataError.SessionNotFound]).
     */
    fun observe(settings: ServerSettings, sessionId: String): Flow<SocketEvent> = flow {
        val wsUrl = buildWsUrl(settings)
        if (wsUrl == null) {
            // Surfaced as an event, not thrown: the history load validates the URL
            // first and owns the user-facing error.
            emit(SocketEvent.Failed(DataError.InvalidUrl))
            return@flow
        }
        val fold = folds.getOrPut(sessionId) { TranscriptFold(json, mapper) }
        val clientId = "android_${UUID.randomUUID()}"
        var attempt = 0
        var connections = 0
        while (currentCoroutineContext().isActive) {
            try {
                val end = runConnection(
                    url = wsUrl.newBuilder().setQueryParameter("client_id", clientId).build(),
                    sessionId = sessionId,
                    subscribeId = "c_${connections + 1}",
                    fold = fold,
                    onOpen = {
                        attempt = 0
                        emit(if (connections++ == 0) SocketEvent.Connected else SocketEvent.Reconnected)
                    },
                    onEvent = { emit(SocketEvent.Event(it)) },
                )
                if (end == ConnectionEnd.SessionNotFound) {
                    emit(SocketEvent.Failed(DataError.SessionNotFound))
                    return@flow
                }
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

    private enum class ConnectionEnd { Closed, SessionNotFound }

    private sealed interface Signal {
        data object Opened : Signal
        data class Text(val text: String) : Signal
        data object Closed : Signal
        data class Failed(val cause: Throwable) : Signal
    }

    /**
     * Runs one socket connection until it closes, fails, or the subscribe is acked
     * `not_found`. Listener callbacks (OkHttp threads) feed a channel consumed in this
     * coroutine, so [onOpen]/[onEvent] run sequentially on the collector's context. The
     * stall watchdog (ticket 152) rides the same channel: inbound silence past 3×
     * `heartbeat_ms` unwinds the connection as an ordinary failure, so the reconnect
     * loop (backoff, re-subscribe, the collector's REST resync) treats a stalled socket
     * exactly like a dropped one.
     */
    private suspend fun runConnection(
        url: HttpUrl,
        sessionId: String,
        subscribeId: String,
        fold: TranscriptFold,
        onOpen: suspend () -> Unit,
        onEvent: suspend (SessionEvent) -> Unit,
    ): ConnectionEnd {
        // UNLIMITED is not a backpressure risk worth bounding: the collector
        // (ChatViewModel) coalesces streaming deltas before any heavy work (UiState
        // emission → full markdown re-parse), so a frame burst can only ever queue
        // cheap unparsed strings here, never unbounded expensive downstream work.
        val signals = Channel<Signal>(Channel.UNLIMITED)
        val watchdog = SocketStallWatchdog(TAG)
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
                when (val signal = watchdog.receive(signals)) {
                    Signal.Opened -> {
                        watchdog.onOpen()
                        onOpen()
                        webSocket.send(subscribeFrame(subscribeId, sessionId))
                    }
                    is Signal.Text ->
                        if (!handleFrame(webSocket, signal.text, sessionId, subscribeId, fold, watchdog, onEvent)) {
                            return ConnectionEnd.SessionNotFound
                        }
                    Signal.Closed -> return ConnectionEnd.Closed
                    is Signal.Failed -> throw IOException("WebSocket failed", signal.cause)
                }
            }
        } finally {
            webSocket.cancel()
            signals.close()
        }
    }

    /** One bad frame must not kill the connection: parse failures are dropped. Returns
     * false when the subscribe ack reports the session `not_found` — the connection
     * ends and the flow fails. */
    private suspend fun handleFrame(
        webSocket: WebSocket,
        text: String,
        sessionId: String,
        subscribeId: String,
        fold: TranscriptFold,
        watchdog: SocketStallWatchdog,
        onEvent: suspend (SessionEvent) -> Unit,
    ): Boolean {
        val frame = try {
            json.decodeFromString<WsFrame>(text)
        } catch (e: SerializationException) {
            Log.w(TAG, "Dropping malformed frame", e)
            return true
        }
        // Global events reach every connection (docs/server-api.md, Global events),
        // stamped with another session's id or `__global__`: a foreign frame must never
        // touch the open chat. Fail open when session_id is absent: control frames
        // (ack, server_hello, ping) carry none, and doc drift must not brick the socket.
        if (frame.sessionId != null && frame.sessionId != sessionId) {
            Log.d(TAG, "Dropping '${frame.type}' frame for session ${frame.sessionId}")
            return true
        }
        when (frame.type) {
            // The transcript stream (docs/server-api.md, Transcript protocol): ops fold
            // into the domain events, tagged with the batch's agent — "main" feeds the
            // chat, any other id (an auto-subscribed sub-agent) the sub-agent stream
            // store (SessionEvent.isSubagentScoped).
            "transcript.ops" -> frame.decodePayload<TranscriptOpsPayload>()
                ?.let { payload -> fold.fold(payload.agentId, payload.ops).forEach { onEvent(it) } }
            // The baseline at subscribe — and again after every reconnect. Its items are
            // always empty (history pages in over REST), so it renders nothing and must
            // not wipe what the chat shows; the cold start (ticket 176) owns its
            // prompts/interactions.
            "transcript.reset" -> frame.decodePayload<TranscriptResetPayload>()
                ?.let { Log.d(TAG, "Transcript reset for agent '${it.agentId}' at seq ${it.seq}") }
            // The subscribe_v2 ack (docs/server-api.md, Control frames): an unknown
            // session answers code 0 with its id in not_found — a load failure, never
            // silence. A malformed subscribe answers code 1; there is nothing to retry.
            "ack" -> if (frame.id == subscribeId) {
                if (frame.code != 0) {
                    Log.w(TAG, "subscribe_v2 refused: ${frame.code} ${frame.msg}")
                } else if (frame.decodePayload<AckPayload>()?.notFound?.contains(sessionId) == true) {
                    Log.w(TAG, "subscribe_v2: session $sessionId not found")
                    return false
                }
            }
            // App-level heartbeat (docs/server-api.md, Heartbeat): the server pings every
            // 10 s and closes the socket after ~20 s of client silence, so the nonce is
            // echoed back in a pong. A ping without a decodable nonce is ignored like any
            // other undecodable payload.
            "ping" -> frame.payload?.get("nonce")?.jsonPrimitive?.contentOrNull
                ?.let { webSocket.send(pongFrame(it)) }
                ?: Log.w(TAG, "Dropping 'ping' frame without a nonce")
            // server_hello (docs/server-api.md, WebSocket): heartbeat_ms sets the stall
            // watchdog's threshold (3× — ticket 152); the rest of the payload is unused.
            "server_hello" -> frame.decodePayload<ServerHelloPayload>()
                ?.heartbeatMs?.let { watchdog.onServerHello(it) }
            // The flat state frames (agent, usage, goal, todo, task.list, task,
            // interaction) are tickets 171/172/177–182: ignored, but logged so a server
            // rename shows up during development.
            else -> Log.d(TAG, "Ignoring '${frame.type}' frame")
        }
        return true
    }

    private inline fun <reified T> WsFrame.decodePayload(): T? =
        payload?.let {
            runCatching { json.decodeFromJsonElement<T>(it) }
                .onFailure { e -> Log.w(TAG, "Dropping undecodable '$type' payload", e) }
                .getOrNull()
        }

    private fun buildWsUrl(settings: ServerSettings): HttpUrl? =
        // OkHttp's HttpUrl holds only http/https; newWebSocket derives ws/wss itself.
        settings.serverUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegments("api/v1/ws")
            ?.build()

    /** `{"type":"subscribe_v2","id":…,"payload":{"session_id":…,"transcript":{"*":"delta"}}}`
     * — one frame per session (there is no multi-session subscribe); grade `delta`
     * streams every agent's text/thinking as `append` chunks. */
    private fun subscribeFrame(id: String, sessionId: String): String = buildJsonObject {
        put("type", "subscribe_v2")
        put("id", id)
        putJsonObject("payload") {
            put("session_id", sessionId)
            putJsonObject("transcript") { put("*", "delta") }
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
