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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    /** [preview] is derived from the started payload's display/args (ToolPreview), so the
     * live row shows what the call is doing from the start, not only after a refetch.
     * [input] is the full arguments as pretty-printed JSON, for the expanded row. */
    data class ToolCallStarted(
        val toolCallId: String,
        val name: String,
        val preview: String = "",
        val input: String = "",
    ) : SessionEvent

    /** [output] is the full result text, shown by the expanded row (ticket 023). */
    data class ToolCallResult(val toolCallId: String, val isError: Boolean, val output: String = "") : SessionEvent

    /** One live output chunk of a running tool (tool.progress, volatile — ticket 025).
     * Never creates a timeline row on its own; the definitive tool.result output
     * replaces the accumulated chunks wholesale. */
    data class ToolCallProgress(val toolCallId: String, val chunk: String) : SessionEvent
    data object PromptSubmitted : SessionEvent

    /** Messages spliced into the context inline (0.27.0): carries the just-sent user
     * message, which replaced prompt.submitted as the sent-prompt echo. */
    data class ContextSpliced(val messages: List<ChatMessage>) : SessionEvent

    /** A tool call waits on the user's decision. [summary] is the compact argument
     * preview for the approval card (the command for shell calls, else the action). */
    data class ApprovalRequested(val approvalId: String, val toolName: String, val summary: String) : SessionEvent

    /** The request was decided — by this client or another one (event.approval.resolved). */
    data class ApprovalResolved(val approvalId: String) : SessionEvent

    /** The agent asks the user a structured question (event.question.requested); the
     * payload carries the full question inline — there is no detail endpoint to fetch. */
    data class QuestionRequested(val question: PendingQuestion) : SessionEvent

    /** The question was answered or dismissed — by this client or another one
     * (event.question.answered / event.question.dismissed share the consumed shape). */
    data class QuestionResolved(val questionId: String) : SessionEvent
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
            postPrompt(settings, sessionId, text, defaultModel(settings)).map { }
        }

    /**
     * Steers a message into the running turn (docs/server-api.md, Steer): the prompt is
     * posted as usual — while busy the server queues it — then `prompts:steer` injects the
     * queued prompt into the active turn. The steered user message echoes over the
     * WebSocket (context.spliced) like a regular one, so success carries no payload.
     */
    suspend fun steer(settings: ServerSettings, sessionId: String, text: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val promptId = postPrompt(settings, sessionId, text, defaultModel(settings))
                .getOrElse { return@withContext Result.failure(it) }
            postSteer(settings, sessionId, promptId)
        }

    /**
     * The model to send with prompts: 0.28.0+ no longer falls back to the server's
     * `default_model` for sessions with an empty `agent_config.model` (the turn fails
     * with `model.not_configured`), so the app passes the server default explicitly
     * (`GET /api/v1/config`). Resolved values — including "server has no default" — are
     * cached per server URL (a settings change refetches); fetch failures are not
     * cached and degrade to the pre-0.28 behavior: the prompt goes out without a model.
     */
    @Volatile
    private var cachedDefaultModel: Pair<String, String?>? = null

    private fun defaultModel(settings: ServerSettings): String? {
        val baseUrl = settings.serverUrl.trim().trimEnd('/')
        cachedDefaultModel?.let { if (it.first == baseUrl) return it.second }
        val result = fetchDefaultModel(settings)
        result.onSuccess { cachedDefaultModel = baseUrl to it }
        return result.getOrNull()
    }

    /** GET /api/v1/config → `data.default_model` (null when the server has none). */
    private fun fetchDefaultModel(settings: ServerSettings): Result<String?> {
        val request = try {
            Request.Builder()
                .url(settings.serverUrl.trim().trimEnd('/') + "/api/v1/config")
                .build()
        } catch (e: IllegalArgumentException) {
            return Result.failure(DataError.InvalidUrl)
        }
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> Result.failure(DataError.Auth)
                    !response.isSuccessful -> Result.failure(DataError.Http(response.code))
                    else -> try {
                        val envelope = json.decodeFromString<Envelope<ConfigData>>(
                            response.body?.string().orEmpty(),
                        )
                        when {
                            envelope.code != 0 -> Result.failure(DataError.Api(envelope.code, envelope.msg))
                            else -> Result.success(envelope.data?.defaultModel?.takeIf { it.isNotBlank() })
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

    /** POSTs the prompt and returns its id (queued while busy, running otherwise). */
    private fun postPrompt(
        settings: ServerSettings,
        sessionId: String,
        text: String,
        model: String?,
    ): Result<String> {
        val body = buildJsonObject {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
            if (model != null) put("model", model)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = try {
            Request.Builder()
                .url(settings.serverUrl.trim().trimEnd('/') + "/api/v1/sessions/$sessionId/prompts")
                .post(body)
                .build()
        } catch (e: IllegalArgumentException) {
            return Result.failure(DataError.InvalidUrl)
        }
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> Result.failure(DataError.Auth)
                    !response.isSuccessful -> Result.failure(DataError.Http(response.code))
                    else -> parsePromptAck(response.body?.string().orEmpty())
                }
            }
        } catch (e: IOException) {
            Result.failure(DataError.Network(e))
        }
    }

    private fun postSteer(settings: ServerSettings, sessionId: String, promptId: String): Result<Unit> {
        val body = buildJsonObject {
            putJsonArray("prompt_ids") { add(promptId) }
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = try {
            Request.Builder()
                .url(settings.serverUrl.trim().trimEnd('/') + "/api/v1/sessions/$sessionId/prompts:steer")
                .post(body)
                .build()
        } catch (e: IllegalArgumentException) {
            return Result.failure(DataError.InvalidUrl)
        }
        return try {
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

    /**
     * Aborts the running turn (`POST /api/v1/sessions/{id}:abort`, docs/server-api.md).
     * The ack only confirms the request; the teardown (prompt.aborted → turn.ended)
     * arrives over the WebSocket, so success carries no payload.
     */
    suspend fun abortPrompt(settings: ServerSettings, sessionId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val body = "{}".toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = try {
                Request.Builder()
                    .url(settings.serverUrl.trim().trimEnd('/') + "/api/v1/sessions/$sessionId:abort")
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

    /**
     * Posts the user's decision for one approval request
     * (`POST /api/v1/sessions/{id}/approvals/{approvalId}`, docs/server-api.md). The ack
     * only confirms the decision was registered; the pending UI clears when the
     * `event.approval.resolved` frame arrives over the WebSocket.
     */
    suspend fun postApprovalDecision(
        settings: ServerSettings,
        sessionId: String,
        approvalId: String,
        approved: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("decision", if (approved) "approved" else "rejected")
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = try {
            Request.Builder()
                .url(settings.serverUrl.trim().trimEnd('/') + "/api/v1/sessions/$sessionId/approvals/$approvalId")
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

    /**
     * Polls the pending questions of the session
     * (`GET /api/v1/sessions/{id}/questions?status=pending`, docs/server-api.md) — the
     * source of truth used to re-derive the pending question after a reconnect or a
     * history refetch, since `GET .../questions/{qid}` does not exist.
     */
    suspend fun getPendingQuestions(settings: ServerSettings, sessionId: String): Result<List<PendingQuestion>> =
        withContext(Dispatchers.IO) {
            val request = try {
                Request.Builder()
                    .url(
                        settings.serverUrl.trim().trimEnd('/') +
                            "/api/v1/sessions/$sessionId/questions?status=pending",
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
                        else -> parsePendingQuestions(response.body?.string().orEmpty())
                    }
                }
            } catch (e: IOException) {
                Result.failure(DataError.Network(e))
            }
        }

    /**
     * Posts the user's answers for one question
     * (`POST /api/v1/sessions/{id}/questions/{questionId}`, docs/server-api.md): keys are
     * the per-question item ids, values carry `kind` plus `option_id` or `text`. The ack
     * only confirms the answers were registered; the pending UI clears when the
     * `event.question.answered` frame arrives over the WebSocket.
     */
    suspend fun answerQuestion(
        settings: ServerSettings,
        sessionId: String,
        questionId: String,
        answers: List<QuestionAnswer>,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("answers") {
                for (answer in answers) {
                    putJsonObject(answer.itemId) {
                        when (answer) {
                            is QuestionAnswer.OptionPick -> {
                                put("kind", "single")
                                put("option_id", answer.optionId)
                            }
                            is QuestionAnswer.Other -> {
                                put("kind", "other")
                                put("text", answer.text)
                            }
                        }
                    }
                }
            }
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = try {
            Request.Builder()
                .url(settings.serverUrl.trim().trimEnd('/') + "/api/v1/sessions/$sessionId/questions/$questionId")
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

    /**
     * Dismisses one pending question (`POST /api/v1/sessions/{id}/questions/{questionId}:dismiss`,
     * docs/server-api.md). Beware: a successful dismiss comes back with the non-zero
     * envelope code 40909 — success is `data.dismissed`, not `code == 0`. The pending UI
     * clears when the `event.question.dismissed` frame arrives over the WebSocket.
     */
    suspend fun dismissQuestion(
        settings: ServerSettings,
        sessionId: String,
        questionId: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val body = "{}".toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = try {
            Request.Builder()
                .url(
                    settings.serverUrl.trim().trimEnd('/') +
                        "/api/v1/sessions/$sessionId/questions/$questionId:dismiss",
                )
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
                    else -> parseDismissAck(response.body?.string().orEmpty())
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
                                else -> Result.success(envelope.data?.busy == true)
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
                ?.let { onEvent(SessionEvent.ToolCallStarted(it.toolCallId, it.name, it.preview(), formatDetail(it.args))) }
            "tool.result" -> frame.decodePayload<ToolResultPayload>()
                ?.let { onEvent(SessionEvent.ToolCallResult(it.toolCallId, it.isError, formatDetail(it.output))) }
            // Volatile live stdout streaming (docs/server-api.md); the update kind is
            // passed through as-is — only its text is consumed.
            "tool.progress" -> frame.decodePayload<ToolProgressPayload>()
                ?.let { onEvent(SessionEvent.ToolCallProgress(it.toolCallId, it.update?.text.orEmpty())) }
            "event.approval.requested" -> frame.decodePayload<ApprovalRequestedPayload>()
                ?.let { onEvent(SessionEvent.ApprovalRequested(it.approvalId, it.toolName, it.summary())) }
            "event.approval.resolved" -> frame.decodePayload<ApprovalResolvedPayload>()
                ?.let { onEvent(SessionEvent.ApprovalResolved(it.approvalId)) }
            "event.question.requested" -> frame.decodePayload<QuestionRequestedPayload>()
                ?.let { onEvent(SessionEvent.QuestionRequested(it.toDomain())) }
            "event.question.answered", "event.question.dismissed" ->
                frame.decodePayload<QuestionResolvedPayload>()
                    ?.let { onEvent(SessionEvent.QuestionResolved(it.questionId)) }
            "turn.started" -> onEvent(SessionEvent.TurnStarted)
            "turn.ended" -> onEvent(SessionEvent.TurnEnded(frame.decodePayload<TurnEndedPayload>()?.reason.orEmpty()))
            "prompt.submitted" -> onEvent(SessionEvent.PromptSubmitted)
            "context.spliced" -> frame.decodePayload<ContextSplicedPayload>()
                ?.let { onEvent(SessionEvent.ContextSpliced(it.messages.map { msg -> msg.toDomain() })) }
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

    /** Dismiss acks succeed with envelope code 40909 (docs/server-api.md): gate on `data.dismissed`. */
    private fun parseDismissAck(body: String): Result<Unit> = try {
        val envelope = json.decodeFromString<Envelope<JsonObject>>(body)
        val dismissed = envelope.data?.get("dismissed")?.jsonPrimitive?.booleanOrNull == true
        when {
            envelope.code == 0 || dismissed -> Result.success(Unit)
            else -> Result.failure(DataError.Api(envelope.code, envelope.msg))
        }
    } catch (e: SerializationException) {
        Result.failure(DataError.MalformedResponse(e))
    }

    private fun parsePendingQuestions(body: String): Result<List<PendingQuestion>> = try {
        val envelope = json.decodeFromString<Envelope<QuestionListData>>(body)
        when {
            envelope.code != 0 -> Result.failure(DataError.Api(envelope.code, envelope.msg))
            envelope.data == null -> Result.failure(DataError.Api(envelope.code, "missing data"))
            else -> Result.success(envelope.data.items.map { it.toDomain() })
        }
    } catch (e: SerializationException) {
        Result.failure(DataError.MalformedResponse(e))
    }

    private fun parsePromptAck(body: String): Result<String> = try {
        val envelope = json.decodeFromString<Envelope<PromptData>>(body)
        when {
            envelope.code != 0 -> Result.failure(DataError.Api(envelope.code, envelope.msg))
            envelope.data == null -> Result.failure(DataError.Api(envelope.code, "missing data"))
            else -> Result.success(envelope.data.promptId)
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

    private fun ApprovalRequestedPayload.summary(): String =
        toolInputDisplay?.command?.takeIf { it.isNotBlank() } ?: action

    /** The row preview for a started call: the server's display command when it sent
     * one, else derived from the arguments (ToolPreview). */
    private fun ToolCallPayload.preview(): String =
        display?.command?.takeIf { it.isNotBlank() } ?: ToolPreview.of(args)

    // Pretty-printing for the expanded tool row (ticket 023): strings render as-is,
    // JSON values (tool inputs, structured outputs) render indented.
    private val prettyJson = Json(json) { prettyPrint = true }

    /** Renders a wire JSON value for the expanded tool row: a plain string comes out as
     * its raw text (shell output stays untouched), anything else as pretty-printed JSON. */
    private fun formatDetail(element: JsonElement?): String = when {
        element == null || element is JsonNull -> ""
        element is JsonPrimitive && element.isString -> element.content
        else -> prettyJson.encodeToString(JsonElement.serializer(), element)
    }

    private fun QuestionRequestedPayload.toDomain(): PendingQuestion = PendingQuestion(
        questionId = questionId,
        items = questions.map { item ->
            PendingQuestion.Item(
                id = item.id,
                header = item.header,
                text = item.question,
                options = item.options.map { PendingQuestion.Option(it.id, it.label, it.description) },
                allowOther = item.allowOther,
            )
        },
    )

    private fun MessageDto.toDomain(): ChatMessage {
        val domainRole = when (role) {
            "user" -> ChatMessage.Role.User
            "assistant" -> ChatMessage.Role.Assistant
            "tool" -> ChatMessage.Role.Tool
            else -> ChatMessage.Role.Other
        }
        return ChatMessage(
            id = id,
            role = domainRole,
            parts = content.mapNotNull { it.toDomain(stripEnvelopes = domainRole == ChatMessage.Role.User) },
        )
    }

    private fun ContentPartDto.toDomain(stripEnvelopes: Boolean): ChatMessage.Part? = when (type) {
        // Internal envelopes are stripped here — the single domain boundary every
        // ingestion path (history, context.spliced echoes) flows through. A part
        // left blank is dropped so ChatTimeline never sees an empty bubble's text.
        "text" -> text
            ?.let { if (stripEnvelopes) InternalEnvelopes.strip(it) else it }
            ?.takeIf { it.isNotBlank() }
            ?.let { ChatMessage.Part.Text(it) }
        "thinking" -> thinking?.let { ChatMessage.Part.Thinking(it) }
        "tool_use" -> toolCallId?.let {
            ChatMessage.Part.ToolUse(it, toolName.orEmpty(), ToolPreview.of(input), formatDetail(input))
        }
        "tool_result" -> toolCallId?.let { ChatMessage.Part.ToolResult(it, isError ?: false, formatDetail(output)) }
        else -> null
    }

    private companion object {
        const val MAX_BACKOFF_SHIFT = 5 // 1 s << 5 = 32 s, clamped to 30 s below

        fun backoffMillis(attempt: Int): Long = (1_000L shl attempt).coerceAtMost(30_000L)
    }
}
