package fr.louissaglio.kimi.android.data.sessions

import android.util.Log
import fr.louissaglio.kimi.android.data.network.SocketStallWatchdog
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
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
 * OkHttp [SessionListSocket] (ticket 087). The connection machinery (listener →
 * channel, reconnect loop with 1 s → 30 s exponential backoff) copies
 * [fr.louissaglio.kimi.android.data.chat.SessionSocket]'s shape; the frame parsing is
 * the list's own: exactly `event.session.work_changed`, `session.meta.updated`, the
 * global `event.session.archived` (0.38.0 — ticket 105) and `event.session.deleted`
 * (0.42.0 — ticket 126), the broadcast `event.session.created` (ticket 129) and the
 * global `event.config.changed`/`event.model_catalog.changed` (0.39.1 — ticket 110),
 * and the subscribe ack.
 * SessionSocket stays single-session/chat-specific — patching it for multi-session
 * use was rejected in the ticket.
 */
@Singleton
class OkHttpSessionListSocket @Inject constructor(
    client: OkHttpClient,
    private val json: Json,
) : SessionListSocket {

    // Same rationale as SessionSocket: protocol pings keep the socket alive through
    // dead NAT routes; the 0.35.0 app-level heartbeat (docs/server-api.md, Heartbeat)
    // is answered in handleFrame. The read timeout stays disabled so an idle
    // connection is not cut — a silently-stalled socket is the stall watchdog's job
    // (ticket 152, see runConnection).
    private val socketClient = client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun observe(settings: ServerSettings, sessionIds: Flow<Set<String>>): Flow<SessionListEvent> = flow {
        val wsUrl = buildWsUrl(settings)
        if (wsUrl == null) {
            // Logged, not surfaced: the REST load validates the URL first and owns the
            // user-facing error (same split as SessionSocket).
            Log.w(TAG, "Invalid server URL; the list socket will not connect")
            return@flow
        }
        val clientId = "android_${UUID.randomUUID()}"
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                runConnection(
                    url = wsUrl.newBuilder().setQueryParameter("client_id", clientId).build(),
                    sessionIds = sessionIds,
                    onOpen = {
                        // attempt > 0 means this open follows a drop (or a failed first
                        // attempt), so the connection's event window has a gap: no
                        // cursors are ever sent, the collector heals via REST instead.
                        if (attempt > 0) emit(SessionListEvent.Reconnected)
                        attempt = 0
                    },
                    onEvent = { emit(it) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fall through to the reconnect backoff below.
                Log.w(TAG, "Session-list socket connection failed; scheduling reconnect", e)
            }
            delay(backoffMillis(attempt))
            attempt = (attempt + 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        }
    }

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
    private suspend fun FlowCollector<SessionListEvent>.runConnection(
        url: HttpUrl,
        sessionIds: Flow<Set<String>>,
        onOpen: suspend () -> Unit,
        onEvent: suspend (SessionListEvent) -> Unit,
    ) {
        val signals = Channel<Signal>(Channel.UNLIMITED)
        // One watchdog per connection (ticket 152): the timer and the server_hello
        // heartbeat value reset across reconnects by construction.
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
            coroutineScope {
                // Ids subscribed on THIS connection; doubles as the foreign-frame
                // filter (0.31.0 unsolicited-broadcast caveat, docs/server-api.md).
                // Grown BEFORE the send so an event racing its subscribe is accepted.
                val subscribed = mutableSetOf<String>()
                var subscribeCount = 0
                // Collects the loaded ids for the life of the connection; cancelled
                // when the signal loop exits — coroutineScope waits for children, so
                // a lingering collector would wedge the reconnect loop on a drop.
                var idJob: Job? = null
                try {
                    while (true) {
                        when (val signal = watchdog.receive(signals)) {
                            Signal.Opened -> {
                                watchdog.onOpen()
                                onOpen()
                                // Follow the loaded ids for the life of this connection:
                                // the first emission subscribes the current set in ONE
                                // frame; later ones (new pages) subscribe only the delta.
                                // Started on open so sends always hit an open socket.
                                idJob = launch {
                                    sessionIds.collect { ids ->
                                        val newIds = ids - subscribed
                                        if (newIds.isNotEmpty()) {
                                            subscribed += newIds
                                            webSocket.send(subscribeFrame(newIds, "c_${++subscribeCount}"))
                                        }
                                    }
                                }
                            }
                            is Signal.Text -> handleFrame(webSocket, signal.text, subscribed, watchdog, onEvent)
                            Signal.Closed -> return@coroutineScope
                            is Signal.Failed -> throw IOException("WebSocket failed", signal.cause)
                        }
                    }
                } finally {
                    idJob?.cancel()
                }
            }
        } finally {
            webSocket.cancel()
            signals.close()
        }
    }

    /** One bad frame must not kill the connection: parse failures are dropped. */
    private suspend fun handleFrame(
        webSocket: WebSocket,
        text: String,
        subscribed: Set<String>,
        watchdog: SocketStallWatchdog,
        onEvent: suspend (SessionListEvent) -> Unit,
    ) {
        val frame = try {
            json.decodeFromString<SessionListWsFrame>(text)
        } catch (e: SerializationException) {
            Log.w(TAG, "Dropping malformed frame", e)
            return
        }
        // 0.38.0 global event (docs/server-api.md — ticket 105): parsed ahead of the
        // unsubscribed filter below because the envelope session_id is the __global__
        // watermark, never a subscribed id; the archived id rides in the payload as
        // camelCase sessionId.
        if (frame.type == "event.session.archived") {
            frame.decodePayload<SessionArchivedPayload>()?.let { payload ->
                onEvent(SessionListEvent.SessionArchived(payload.sessionId))
            }
            return
        }
        // 0.42.0 global event, same shape and same ahead-of-the-filter placement as
        // the archived frame (ticket 126): a delete from any client, the envelope
        // session_id being the __global__ watermark.
        if (frame.type == "event.session.deleted") {
            frame.decodePayload<SessionDeletedPayload>()?.let { payload ->
                onEvent(SessionListEvent.SessionDeleted(payload.sessionId))
            }
            return
        }
        // Verified on 0.42.0 (docs/server-api.md — ticket 129): a create from any
        // client. Same ahead-of-the-filter placement as the archived frame, for a
        // different reason: the frame is broadcast on the NEW session's own stream,
        // so the envelope session_id is the created session — never __global__ and
        // never a subscribed id. The payload session is the snake_case REST shape.
        if (frame.type == "event.session.created") {
            frame.decodePayload<SessionCreatedPayload>()?.let { payload ->
                onEvent(SessionListEvent.SessionCreated(payload.session.toDomain()))
            }
            return
        }
        // 0.39.1 global events (docs/server-api.md — ticket 110): a config write or a
        // provider model refresh, from any client. Same __global__ envelope as the
        // archived event; the payload (the full config projection) is never decoded —
        // the frame type alone tells the collector to invalidate the models/config
        // caches.
        if (frame.type == "event.config.changed") {
            onEvent(SessionListEvent.ConfigChanged)
            return
        }
        if (frame.type == "event.model_catalog.changed") {
            onEvent(SessionListEvent.ModelCatalogChanged)
            return
        }
        // 0.31.0 caveat (docs/server-api.md): the server also sends events for sessions
        // this connection never subscribed to — drop them on the envelope session_id.
        // Untagged frames fail open (control frames carry no session_id); an event
        // without one cannot patch a row and is ignored below.
        if (frame.sessionId != null && frame.sessionId !in subscribed) {
            Log.w(TAG, "Dropping '${frame.type}' frame for unsubscribed session ${frame.sessionId}")
            return
        }
        when (frame.type) {
            "event.session.work_changed" -> frame.decodePayload<WorkChangedPayload>()?.let { payload ->
                frame.sessionId?.let { sessionId ->
                    onEvent(
                        SessionListEvent.WorkChanged(
                            sessionId,
                            payload.busy,
                            payload.pendingInteraction,
                            payload.lastTurnReason,
                        ),
                    )
                }
            }
            "session.meta.updated" -> frame.decodePayload<SessionMetaUpdatedPayload>()?.let { payload ->
                frame.sessionId?.let { sessionId ->
                    onEvent(SessionListEvent.MetaUpdated(sessionId, payload.title))
                }
            }
            // The subscribe ack (accepted / not_found): no cursors are ever sent, so
            // there is nothing to reconcile; live removal of not_found rows is
            // deferred (ticket 087, out of scope).
            "ack" -> Unit
            // App-level heartbeat (0.35.0 — docs/server-api.md, Heartbeat): the server
            // pings every 10 s and closes the socket after ~20 s of client silence, so
            // the nonce is echoed back in a pong. A ping without a decodable nonce is
            // ignored like any other undecodable payload.
            "ping" -> frame.payload?.get("nonce")?.jsonPrimitive?.contentOrNull
                ?.let { webSocket.send(pongFrame(it)) }
                ?: Log.w(TAG, "Dropping 'ping' frame without a nonce")
            // server_hello (docs/server-api.md, WebSocket): heartbeat_ms sets the stall
            // watchdog's threshold (3× — ticket 152); the rest of the payload is unused.
            "server_hello" -> frame.decodePayload<ServerHelloPayload>()
                ?.heartbeatMs?.let { watchdog.onServerHello(it) }
            // Every other event type: ignored, but logged so a server rename shows up
            // during development.
            else -> Log.d(TAG, "Ignoring '${frame.type}' frame")
        }
    }

    private inline fun <reified T> SessionListWsFrame.decodePayload(): T? =
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

    private fun subscribeFrame(sessionIds: Set<String>, id: String): String = buildJsonObject {
        put("type", "subscribe")
        put("id", id)
        putJsonObject("payload") {
            putJsonArray("session_ids") { sessionIds.forEach(::add) }
        }
    }.toString()

    private fun pongFrame(nonce: String): String = buildJsonObject {
        put("type", "pong")
        putJsonObject("payload") { put("nonce", nonce) }
    }.toString()

    private companion object {
        const val TAG = "SessionListSocket"
        const val MAX_BACKOFF_SHIFT = 5 // 1 s << 5 = 32 s, clamped to 30 s below

        fun backoffMillis(attempt: Int): Long = (1_000L shl attempt).coerceAtMost(30_000L)
    }
}
