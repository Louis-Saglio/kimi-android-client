package fr.louissaglio.kimi.android.data

import fr.louissaglio.kimi.android.data.model.ServerEvent
import fr.louissaglio.kimi.android.data.model.WebSocketEnvelope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit

class WebSocketManager(
    serverUrl: String,
    username: String,
    password: String
) {
    private val wsUrl = serverUrl.trimEnd('/')
        .replace(Regex("^http://"), "ws://")
        .replace(Regex("^https://"), "wss://") + "/api/v1/ws"
    private val credentials = Credentials.basic(username, password)
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val clientId = UUID.randomUUID().toString()
    private var messageId = 0L

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    val events: Flow<ServerEvent> = _events.asSharedFlow()

    private val _errors = Channel<String>(Channel.BUFFERED)
    val errors: Flow<String> = _errors.receiveAsFlow()

    private val _connectionState = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val connectionState: Flow<Boolean> = _connectionState.asSharedFlow()

    private val subscriptions = mutableMapOf<String, Long>()
    private val pendingSubscriptions = mutableListOf<Pair<String, Long>>()

    fun connect() {
        disconnect()
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", credentials)
            .header("Accept", "application/json")
            .build()
        webSocket = client.newWebSocket(request, listener)
    }

    fun disconnect() {
        webSocket?.close(1000, "disconnect")
        webSocket = null
    }

    fun subscribe(sessionId: String, cursor: Long = 0) {
        synchronized(subscriptions) {
            if (webSocket == null) {
                pendingSubscriptions.add(sessionId to cursor)
            } else {
                subscriptions[sessionId] = cursor
                sendSubscribe(listOf(sessionId), mapOf(sessionId to cursor))
            }
        }
    }

    fun unsubscribe(sessionId: String) {
        synchronized(subscriptions) {
            subscriptions.remove(sessionId)
            pendingSubscriptions.removeAll { it.first == sessionId }
        }
    }

    private fun flushPendingSubscriptions() {
        synchronized(subscriptions) {
            for ((sessionId, cursor) in pendingSubscriptions) {
                subscriptions[sessionId] = cursor
            }
            val ids = pendingSubscriptions.map { it.first }
            val cursors = pendingSubscriptions.toMap()
            pendingSubscriptions.clear()
            if (ids.isNotEmpty()) {
                sendSubscribe(ids, cursors)
            }
        }
    }

    private fun sendHello() {
        val subs: List<String>
        val cursors: Map<String, Long>
        synchronized(subscriptions) {
            subs = subscriptions.keys.toList()
            cursors = subscriptions.toMap()
        }
        val payload = buildJsonObject {
            put("client_id", clientId)
            put("subscriptions", buildJsonArray {
                subs.forEach { add(JsonPrimitive(it)) }
            })
            put("cursors", buildJsonObject {
                cursors.forEach { (sessionId, cursor) -> put(sessionId, cursor) }
            })
        }
        send("client_hello", payload)
    }

    private fun sendSubscribe(sessionIds: List<String>, cursors: Map<String, Long>) {
        val payload = buildJsonObject {
            put("session_ids", buildJsonArray {
                sessionIds.forEach { add(JsonPrimitive(it)) }
            })
            put("cursors", buildJsonObject {
                cursors.forEach { (sessionId, cursor) -> put(sessionId, cursor) }
            })
        }
        send("subscribe", payload)
    }

    private fun send(type: String, payload: JsonObject) {
        val id = "ws_${++messageId}"
        val message = buildJsonObject {
            put("type", type)
            put("id", id)
            put("payload", payload)
        }
        val json = kimiJson.encodeToString(JsonObject.serializer(), message)
        webSocket?.send(json)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _connectionState.tryEmit(true)
            sendHello()
            flushPendingSubscriptions()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val envelope = kimiJson.decodeFromString(
                    WebSocketEnvelope.serializer(),
                    text
                )
                val event = envelope.toServerEvent()
                _events.tryEmit(event)
                envelope.sessionId?.let { sessionId ->
                    envelope.seq?.let { seq ->
                        synchronized(subscriptions) {
                            subscriptions[sessionId] = seq
                        }
                    }
                }
            } catch (e: Exception) {
                _errors.trySend("websocket parse error: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _connectionState.tryEmit(false)
            _errors.trySend("websocket error: ${t.message}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _connectionState.tryEmit(false)
        }
    }
}
