package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.network.Envelope
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Events of one session, parsed from the WebSocket stream (docs/server-api.md). */
sealed interface SessionEvent {
    data object TurnStarted : SessionEvent
    data class TurnEnded(val reason: String) : SessionEvent
    data class AssistantDelta(val delta: String) : SessionEvent
    data class ThinkingDelta(val delta: String) : SessionEvent
    data class ToolCallDelta(val toolCallId: String, val name: String) : SessionEvent
    data class ToolCallStarted(val toolCallId: String, val name: String) : SessionEvent
    data class ToolCallResult(val toolCallId: String, val isError: Boolean) : SessionEvent
    data object PromptSubmitted : SessionEvent
}

/** What the socket reports to the collector: connection lifecycle + session events. */
sealed interface SocketEvent {
    /** First connection of this collection. */
    data object Connected : SocketEvent

    /** Re-connected after a drop; the server replays events newer than the resume cursor. */
    data object Reconnected : SocketEvent

    /** Connection lost (or never established); a retry with backoff is scheduled. */
    data object Disconnected : SocketEvent

    data class Event(val event: SessionEvent) : SocketEvent
}

@Singleton
class ChatRepository @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    // The app-level ping/pong keeps aliveness; disable the read timeout so an idle
    // stream is not cut (the shared client sets 30 s for REST).
    private val socketClient = client.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()

    /**
     * Fetches one page of history from `GET /api/v1/sessions/{id}/messages` (pagination
     * is out of scope for v1), mapped to domain messages oldest-first (the wire order is
     * newest-first). This is the error boundary: failures come back as [Result.failure]
     * of [DataError].
     */
    suspend fun getMessages(settings: ServerSettings, sessionId: String): Result<List<ChatMessage>> =
        withContext(Dispatchers.IO) {
            val request = try {
                Request.Builder()
                    .url(
                        settings.serverUrl.trim().trimEnd('/') +
                            "/api/v1/sessions/$sessionId/messages?page_size=100",
                    )
                    .build()
            } catch (e: IllegalArgumentException) {
                return@withContext Result.failure(DataError.InvalidUrl)
            }
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 -> Result.failure(DataError.Auth)
                        !response.isSuccessful -> Result.failure(DataError.Http(response.code))
                        else -> parseMessages(response.body?.string().orEmpty())
                    }
                }
            } catch (e: IOException) {
                Result.failure(DataError.Network(e))
            }
        }

    /**
     * Sends a user prompt (`POST /api/v1/sessions/{id}/prompts`). The response only
     * acknowledges the prompt: the user message and the agent's reply arrive over the
     * WebSocket (docs/server-api.md), so success carries no payload.
     */
    suspend fun sendPrompt(settings: ServerSettings, sessionId: String, text: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = try {
                Request.Builder()
                    .url(settings.serverUrl.trim().trimEnd('/') + "/api/v1/sessions/$sessionId/prompts")
                    .post(body)
                    .build()
            } catch (e: IllegalArgumentException) {
                return@withContext Result.failure(DataError.InvalidUrl)
            }
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 -> Result.failure(DataError.Auth)
                        !response.isSuccessful -> Result.failure(DataError.Http(response.code))
                        else -> parseAck(response.body?.string().orEmpty())
                    }
                }
            } catch (e: IOException) {
                Result.failure(DataError.Network(e))
            }
        }

    /** Lightweight live status (`GET /api/v1/sessions/{id}/status`): true when the session is mid-turn. */
    suspend fun isSessionRunning(settings: ServerSettings, sessionId: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            val request = try {
                Request.Builder()
                    .url(settings.serverUrl.trim().trimEnd('/') + "/api/v1/sessions/$sessionId/status")
                    .build()
            } catch (e: IllegalArgumentException) {
                return@withContext Result.failure(DataError.InvalidUrl)
            }
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 -> Result.failure(DataError.Auth)
                        !response.isSuccessful -> Result.failure(DataError.Http(response.code))
                        else -> try {
                            val envelope = json.decodeFromString<Envelope<SessionStatusData>>(
                                response.body?.string().orEmpty(),
                            )
                            when {
                                envelope.code != 0 -> Result.failure(DataError.Api(envelope.code, envelope.msg))
                                else -> Result.success(envelope.data?.status == "running")
                            }
                        } catch (e: SerializationException) {
                            Result.failure(DataError.MalformedResponse(e))
                        }
                    }
                }
            } catch (e: IOException) {
                Result.failure(DataError.Network(e))
            }
        }

    /**
     * Streams [SocketEvent]s for [sessionId]. One collection = one socket owned here
     * (not by the UI); cancelling the collection closes it. On a drop the loop retries
     * with exponential backoff (1 s doubling to 30 s) and re-subscribes with the last
     * seen cursor, so the server replays missed events; the collector is told via
     * [SocketEvent.Reconnected] so it can also refetch history.
     */
    fun observeSession(settings: ServerSettings, sessionId: String): Flow<SocketEvent> = flow {
        val wsUrl = buildWsUrl(settings) ?: throw DataError.InvalidUrl
        val clientId = "android_${UUID.randomUUID()}"
        var cursor: Cursor? = null
        var everConnected = false
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            try {
                runConnection(
                    url = wsUrl.newBuilder().setQueryParameter("client_id", clientId).build(),
                    sessionId = sessionId,
                    cursor = cursor,
                    onOpen = {
                        attempt = 0
                        emit(if (everConnected) SocketEvent.Reconnected else SocketEvent.Connected)
                        everConnected = true
                    },
                    onCursor = { cursor = it },
                    onEvent = { emit(SocketEvent.Event(it)) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fall through to the reconnect backoff below.
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
        onEvent: suspend (SessionEvent) -> Unit,
    ) {
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
                    is Signal.Text -> handleFrame(signal.text, webSocket, onCursor, onEvent)
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
        onCursor: (Cursor) -> Unit,
        onEvent: suspend (SessionEvent) -> Unit,
    ) {
        val frame = try {
            json.decodeFromString<WsFrame>(text)
        } catch (e: SerializationException) {
            return
        }
        if (frame.seq != null && frame.epoch != null) onCursor(Cursor(frame.seq, frame.epoch))
        when (frame.type) {
            // The server closes the connection if app-level pings go unanswered.
            "ping" -> frame.payload
                ?.let { runCatching { json.decodeFromJsonElement(PingPayload.serializer(), it) }.getOrNull() }
                ?.let { webSocket.send(pongFrame(it.nonce)) }
            "assistant.delta" -> frame.decodePayload<DeltaPayload>()
                ?.let { onEvent(SessionEvent.AssistantDelta(it.delta)) }
            "thinking.delta" -> frame.decodePayload<DeltaPayload>()
                ?.let { onEvent(SessionEvent.ThinkingDelta(it.delta)) }
            "tool.call.delta" -> frame.decodePayload<ToolCallPayload>()
                ?.let { onEvent(SessionEvent.ToolCallDelta(it.toolCallId, it.name)) }
            "tool.call.started" -> frame.decodePayload<ToolCallPayload>()
                ?.let { onEvent(SessionEvent.ToolCallStarted(it.toolCallId, it.name)) }
            "tool.result" -> frame.decodePayload<ToolResultPayload>()
                ?.let { onEvent(SessionEvent.ToolCallResult(it.toolCallId, it.isError)) }
            "turn.started" -> onEvent(SessionEvent.TurnStarted)
            "turn.ended" -> onEvent(SessionEvent.TurnEnded(frame.decodePayload<TurnEndedPayload>()?.reason.orEmpty()))
            "prompt.submitted" -> onEvent(SessionEvent.PromptSubmitted)
            // server_hello, ack, and events we don't render yet: ignore.
        }
    }

    private inline fun <reified T> WsFrame.decodePayload(): T? =
        payload?.let { runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull() }

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

    private fun parseAck(body: String): Result<Unit> = try {
        val envelope = json.decodeFromString<Envelope<JsonObject>>(body)
        when {
            envelope.code != 0 -> Result.failure(DataError.Api(envelope.code, envelope.msg))
            else -> Result.success(Unit)
        }
    } catch (e: SerializationException) {
        Result.failure(DataError.MalformedResponse(e))
    }

    private fun parseMessages(body: String): Result<List<ChatMessage>> = try {
        val envelope = json.decodeFromString<Envelope<MessageListData>>(body)
        when {
            envelope.code != 0 -> Result.failure(DataError.Api(envelope.code, envelope.msg))
            envelope.data == null -> Result.failure(DataError.Api(envelope.code, "missing data"))
            // Wire order is newest-first; domain messages are oldest-first.
            else -> Result.success(envelope.data.items.asReversed().map { it.toDomain() })
        }
    } catch (e: SerializationException) {
        Result.failure(DataError.MalformedResponse(e))
    }

    private fun MessageDto.toDomain(): ChatMessage = ChatMessage(
        id = id,
        role = when (role) {
            "user" -> ChatMessage.Role.User
            "assistant" -> ChatMessage.Role.Assistant
            "tool" -> ChatMessage.Role.Tool
            else -> ChatMessage.Role.Other
        },
        parts = content.mapNotNull { it.toDomain() },
    )

    private fun ContentPartDto.toDomain(): ChatMessage.Part? = when (type) {
        "text" -> text?.let { ChatMessage.Part.Text(it) }
        "thinking" -> thinking?.let { ChatMessage.Part.Thinking(it) }
        "tool_use" -> toolCallId?.let { ChatMessage.Part.ToolUse(it, toolName.orEmpty()) }
        "tool_result" -> toolCallId?.let { ChatMessage.Part.ToolResult(it, isError ?: false) }
        else -> null
    }

    private companion object {
        const val MAX_BACKOFF_SHIFT = 5 // 1 s << 5 = 32 s, clamped to 30 s below

        fun backoffMillis(attempt: Int): Long = (1_000L shl attempt).coerceAtMost(30_000L)
    }
}
