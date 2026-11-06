package fr.louissaglio.kimi.android.data.chat

import android.util.Log
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.network.SocketStallWatchdog
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
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
 * session's [TranscriptFold] (server 2.5.0, ticket 175), the main agent's flat `agent`/
 * `usage` state frames mapped to its model/permission/busy/context events (ticket 177),
 * the flat `interaction` frame mapped to the approval card's and question wizard's
 * events and the ack-time pending-interaction resets (tickets 171/172), the flat
 * `task.list`/`task` frames mapped to [SessionEvent.TaskListed]/[SessionEvent.TaskUpdated]
 * and the flat task controls with their `response` replies (ticket 179), the
 * `task.watch`/`task.unwatch` output stream and its `task.output` chunks (ticket 180)
 * — plus the per-agent resume watermarks every subscribe sends back as
 * `transcript_since` (ticket 176).
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
     * The resume watermarks, per session then per agent (ticket 176): the op-batch
     * `seq` the matching [folds] entry has folded up to. Seeded for the main agent by
     * the cold start's REST transcript page ([seedTranscript]) and advanced by every
     * `transcript.ops` batch and `transcript.reset` of any agent — so a child seen
     * live keeps its own. Every `subscribe_v2` sends the whole map as
     * `transcript_since`: the server replays the batches after each watermark before
     * the ack instead of a fresh baseline, and the fold continues exactly where it
     * stopped. Instance-level for the same reason as the fold. An agent without a
     * watermark (a child never seen on this process) gets the server's baseline
     * `transcript.reset`; its history pages in over REST after the subscribe, so no
     * window is missed.
     */
    private val watermarks = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    /**
     * The latest task state per session, keyed by (owning agent, task id) (ticket 179):
     * every `task` frame and code-0 control reply replaces its entry, every `task.list`
     * upserts its running tasks and drops the agent's running entries it no longer
     * lists (they ended unseen — their outcome is unknown). Ended entries stay: the
     * server never re-sends them. Instance-level like [folds], so whichever collection
     * feeds the session (the chat's or the sub-agent store's keep-alive) keeps it
     * current — the job screen reads it through [observeTask].
     */
    private val tasks = ConcurrentHashMap<String, MutableStateFlow<Map<TaskKey, TaskState>>>()

    private data class TaskKey(val agentId: String, val taskId: String)

    private fun tasksOf(sessionId: String): MutableStateFlow<Map<TaskKey, TaskState>> =
        tasks.getOrPut(sessionId) { MutableStateFlow(emptyMap()) }

    /** The session's open connection, while one is open (one collection per session,
     * ticket 064): the channel [sendTaskControl] writes to and awaits replies on. */
    private val connections = ConcurrentHashMap<String, LiveConnection>()

    private val requestIds = AtomicLong()

    private val connectionIds = AtomicLong()

    /** The id of the session's open connection, null while none is open (ticket 180):
     * a task watch does not survive a disconnect, so a watcher re-sends `task.watch`
     * whenever a new id appears. */
    private val openConnections = ConcurrentHashMap<String, MutableStateFlow<Long?>>()

    private fun openConnectionOf(sessionId: String): MutableStateFlow<Long?> =
        openConnections.getOrPut(sessionId) { MutableStateFlow(null) }

    /** The `task.output` chunks per session (ticket 180). Hot and replay-less: a
     * watcher subscribes BEFORE it sends `task.watch`, so the tail chunk that follows
     * the reply is never missed; chunks nobody collects are dropped. */
    private val taskOutputs = ConcurrentHashMap<String, MutableSharedFlow<TaskOutputChunk>>()

    private fun taskOutputsOf(sessionId: String): MutableSharedFlow<TaskOutputChunk> =
        taskOutputs.getOrPut(sessionId) { MutableSharedFlow(extraBufferCapacity = OUTPUT_BUFFER) }

    /** One open socket and its in-flight task controls by `request_id`. A control's
     * owning agent rides along: the reply carries no `agent_id`, but its task state
     * is emitted as that agent's [SessionEvent.TaskUpdated]. */
    private class LiveConnection(val webSocket: WebSocket, val id: Long) {
        val pending = ConcurrentHashMap<String, PendingControl>()
    }

    private class PendingControl(val agentId: String, val reply: CompletableDeferred<TaskControlResponse>)

    private fun foldOf(sessionId: String): TranscriptFold =
        folds.getOrPut(sessionId) { TranscriptFold(json, mapper) }

    private fun watermarksOf(sessionId: String): ConcurrentHashMap<String, Long> =
        watermarks.getOrPut(sessionId) { ConcurrentHashMap() }

    /**
     * Seeds [page]'s agent from a REST transcript page (the cold start, ticket 176):
     * the fold is reset to the page ([TranscriptFold.seed]) and the page's `seq`
     * becomes the agent's watermark — the next subscribe resumes right after it. A page
     * without `seq` (a session that is not live) drops the watermark: the subscribe
     * then takes the server's baseline. Call it while no collection folds the session
     * (the cold start runs before the subscribe), or accept that ops folded between the
     * page's capture and this call are forgotten — their frames' final upserts heal.
     */
    fun seedTranscript(sessionId: String, page: TranscriptPage) {
        foldOf(sessionId).seed(page.agentId, page)
        val seq = page.seq
        if (seq != null) watermarksOf(sessionId)[page.agentId] = seq else watermarksOf(sessionId).remove(page.agentId)
    }

    /** The agent's resume watermark, null when none is known (ticket 176). */
    fun watermark(sessionId: String, agentId: String): Long? = watermarks[sessionId]?.get(agentId)

    /** The latest state of [agentId]'s task [taskId] this process has seen (ticket
     * 179): null until a `task.list`/`task` frame or a control reply names it. */
    fun observeTask(sessionId: String, agentId: String, taskId: String): Flow<TaskState?> =
        tasksOf(sessionId).map { it[TaskKey(agentId, taskId)] }.distinctUntilChanged()

    /**
     * Sends one flat task control for [agentId]'s task [taskId] on the session's open
     * connection and awaits its `response` (ticket 179; docs/server-api.md → WebSocket →
     * Control frames): `{type, request_id, session_id, agent_id, task_id}`. A code-0
     * reply's task state is emitted to the collector as a [SessionEvent.TaskUpdated]
     * (and recorded) BEFORE this returns it, so every consumer applies it like any task
     * frame. A non-zero code fails with [DataError.Api] (`40933` not movable to the
     * background, `40406` no such task, …) and applies nothing; no open connection, a
     * connection dropped mid-request or no reply within [CONTROL_TIMEOUT_MILLIS] fail
     * with [DataError.Network].
     */
    suspend fun sendTaskControl(
        sessionId: String,
        control: TaskControl,
        agentId: String,
        taskId: String,
    ): Result<TaskState?> = sendControl(sessionId, control.wire, agentId, taskId)

    /**
     * Starts streaming [agentId]'s task [taskId]'s output on the session's open
     * connection (ticket 180; docs/server-api.md → Task frames and controls): the flat
     * `{type:"task.watch",request_id,session_id,agent_id,task_id,since_offset?,tail_bytes?}`.
     * The code-0 reply's task state is applied like [sendTaskControl]'s; the server then
     * sends one [TaskOutputChunk] holding the last [tailBytes] after [sinceOffset] and
     * every live chunk after it, all on [observeTaskOutput] — subscribe there FIRST. The
     * watch dies with the connection ([observeConnection]); re-send it with
     * [sinceOffset] = the end of what is already held. Errors as [sendTaskControl]
     * (`40406` unknown task, `50003` output unreadable).
     */
    suspend fun watchTask(
        sessionId: String,
        agentId: String,
        taskId: String,
        sinceOffset: Long?,
        tailBytes: Int?,
    ): Result<TaskState?> = sendControl(sessionId, WATCH, agentId, taskId) {
        if (sinceOffset != null) put("since_offset", sinceOffset)
        if (tailBytes != null) put("tail_bytes", tailBytes)
    }

    /** Stops [agentId]'s task [taskId]'s output stream (`task.unwatch`, ticket 180).
     * Fire-and-forget — the screen leaving cannot wait for the reply, which is dropped
     * as an unknown request; false when no connection is open (a watch died with it). */
    fun unwatchTask(sessionId: String, agentId: String, taskId: String): Boolean {
        val connection = connections[sessionId] ?: return false
        val requestId = "t_${requestIds.incrementAndGet()}"
        return connection.webSocket.send(taskControlFrame(UNWATCH, requestId, sessionId, agentId, taskId))
    }

    /** [agentId]'s task [taskId]'s `task.output` chunks while it is watched (ticket 180). */
    fun observeTaskOutput(sessionId: String, agentId: String, taskId: String): Flow<TaskOutputChunk> =
        taskOutputsOf(sessionId).filter { it.agentId == agentId && it.taskId == taskId }

    /** The id of the session's open connection, null while none is open (ticket 180):
     * every new id is a connection without the previous one's task watches. */
    fun observeConnection(sessionId: String): StateFlow<Long?> = openConnectionOf(sessionId).asStateFlow()

    private suspend fun sendControl(
        sessionId: String,
        wire: String,
        agentId: String,
        taskId: String,
        extra: JsonObjectBuilder.() -> Unit = {},
    ): Result<TaskState?> {
        val connection = connections[sessionId]
            ?: return Result.failure(DataError.Network(IOException("The session socket is not connected")))
        val requestId = "t_${requestIds.incrementAndGet()}"
        val pending = PendingControl(agentId, CompletableDeferred())
        connection.pending[requestId] = pending
        return try {
            if (!connection.webSocket.send(taskControlFrame(wire, requestId, sessionId, agentId, taskId, extra))) {
                return Result.failure(DataError.Network(IOException("The session socket is closing")))
            }
            val response = withTimeoutOrNull(CONTROL_TIMEOUT_MILLIS) { pending.reply.await() }
                ?: return Result.failure(DataError.Network(TimeoutException("No reply to $wire")))
            if (response.code != 0) {
                Result.failure(DataError.Api(response.code, response.msg?.takeIf { it.isNotBlank() } ?: "$wire failed"))
            } else {
                Result.success(response.task?.toTaskState())
            }
        } catch (e: IOException) {
            Result.failure(DataError.Network(e))
        } finally {
            connection.pending.remove(requestId)
        }
    }

    private fun recordTask(sessionId: String, agentId: String, task: TaskState) =
        tasksOf(sessionId).update { it + (TaskKey(agentId, task.taskId) to task) }

    private fun recordTaskList(sessionId: String, agentId: String, listed: List<TaskState>) =
        tasksOf(sessionId).update { current ->
            val listedIds = listed.mapTo(HashSet()) { it.taskId }
            current.filter { (key, task) -> key.agentId != agentId || !task.isRunning || key.taskId in listedIds } +
                listed.associateBy { TaskKey(agentId, it.taskId) }
        }

    /**
     * Streams [SocketEvent]s for [sessionId]. One collection = one socket owned here
     * (not by the UI); cancelling the collection closes it. Each connection sends one
     * `subscribe_v2` (grade `delta` for every agent — the server auto-subscribes the
     * session's sub-agents, whose ops carry their own `agent_id`). On a drop the loop
     * retries with exponential backoff (1 s doubling to 30 s) and re-subscribes — the
     * subscription does not survive a disconnect — emitting [SocketEvent.Reconnected].
     * Every subscribe carries the session's resume watermarks (`transcript_since`,
     * ticket 176), so a re-subscribe replays the missed batches; an agent whose journal
     * cannot cover its watermark answers with a fresh `transcript.reset`, surfaced as
     * [SessionEvent.TranscriptStale] after the agent's fold is reset. A subscribe acked
     * `not_found` ends the flow with [SocketEvent.Failed] ([DataError.SessionNotFound]).
     */
    fun observe(settings: ServerSettings, sessionId: String): Flow<SocketEvent> = flow {
        val wsUrl = buildWsUrl(settings)
        if (wsUrl == null) {
            // Surfaced as an event, not thrown: the history load validates the URL
            // first and owns the user-facing error.
            emit(SocketEvent.Failed(DataError.InvalidUrl))
            return@flow
        }
        val fold = foldOf(sessionId)
        val sessionWatermarks = watermarksOf(sessionId)
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
                    watermarks = sessionWatermarks,
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
        watermarks: MutableMap<String, Long>,
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
        val connection = LiveConnection(webSocket, connectionIds.incrementAndGet())
        // Rebuilt at open: the agents this connection's subscribe asked to resume — a
        // reset for one of them means its journal could not cover the watermark.
        var context = FrameContext(sessionId, subscribeId, fold, watermarks, resumed = emptySet(), watchdog, connection)
        try {
            while (true) {
                when (val signal = watchdog.receive(signals)) {
                    Signal.Opened -> {
                        watchdog.onOpen()
                        onOpen()
                        val since = watermarks.toMap()
                        context = FrameContext(sessionId, subscribeId, fold, watermarks, resumed = since.keys, watchdog, connection)
                        connections[sessionId] = connection
                        openConnectionOf(sessionId).value = connection.id
                        webSocket.send(subscribeFrame(subscribeId, sessionId, since))
                    }
                    is Signal.Text ->
                        if (!handleFrame(webSocket, signal.text, context, onEvent)) {
                            return ConnectionEnd.SessionNotFound
                        }
                    Signal.Closed -> return ConnectionEnd.Closed
                    is Signal.Failed -> throw IOException("WebSocket failed", signal.cause)
                }
            }
        } finally {
            connections.remove(sessionId, connection)
            openConnectionOf(sessionId).compareAndSet(connection.id, null)
            // A control in flight on this socket gets no reply any more: fail it now
            // instead of waiting out the timeout.
            connection.pending.values.forEach {
                it.reply.completeExceptionally(IOException("The session socket closed before the reply"))
            }
            webSocket.cancel()
            signals.close()
        }
    }

    /** The per-connection state [handleFrame] reads; [resumed] are the agents the
     * connection's subscribe sent a watermark for. [resetApprovals]/[resetQuestions]
     * collect the pending approvals/questions of this connection's `transcript.reset`
     * snapshots (tickets 171/172), by interaction id — the ack's
     * [SessionEvent.ApprovalsReset]/[SessionEvent.QuestionsReset]. [connection] holds
     * the task controls awaiting a `response` (ticket 179). */
    private class FrameContext(
        val sessionId: String,
        val subscribeId: String,
        val fold: TranscriptFold,
        val watermarks: MutableMap<String, Long>,
        val resumed: Set<String>,
        val watchdog: SocketStallWatchdog,
        val connection: LiveConnection,
    ) {
        val resetApprovals = LinkedHashMap<String, PendingApprovalRequest>()
        val resetQuestions = LinkedHashMap<String, PendingQuestion>()
    }

    /** One bad frame must not kill the connection: parse failures are dropped. Returns
     * false when the subscribe ack reports the session `not_found` — the connection
     * ends and the flow fails. */
    private suspend fun handleFrame(
        webSocket: WebSocket,
        text: String,
        context: FrameContext,
        onEvent: suspend (SessionEvent) -> Unit,
    ): Boolean {
        val sessionId = context.sessionId
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
            // Each batch advances the agent's resume watermark (ticket 176) once its
            // events are delivered: a collection cancelled mid-batch resumes by
            // replaying it, and the fold dedups what was already emitted.
            "transcript.ops" -> frame.decodePayload<TranscriptOpsPayload>()
                ?.let { payload ->
                    context.fold.fold(payload.agentId, payload.ops).forEach { onEvent(it) }
                    context.watermarks[payload.agentId] = payload.seq
                }
            // The baseline of an agent subscribed WITHOUT a watermark (a child never
            // seen, a session that was not live): its items are always empty (history
            // pages in over REST), so it renders nothing and must not wipe what the chat
            // shows; its seq becomes the agent's watermark. For an agent the subscribe
            // asked to RESUME, a reset instead of the replay means the journal could not
            // cover the watermark (ticket 176): the fold restarts from the new baseline
            // and the collector rebuilds the agent's rows from REST — never a partial
            // replay.
            // Its snapshot's interactions (the session's full entity list, ended ones
            // included) hold the pending approvals and questions the ack's resets carry
            // (tickets 171/172).
            "transcript.reset" -> frame.decodePayload<TranscriptResetPayload>()
                ?.let { reset ->
                    reset.snapshot?.interactions?.let { entities ->
                        mapper.pendingApprovals(entities).forEach { context.resetApprovals.putIfAbsent(it.approvalId, it) }
                        mapper.pendingQuestions(entities).forEach { context.resetQuestions.putIfAbsent(it.questionId, it) }
                    }
                    context.watermarks[reset.agentId] = reset.seq
                    if (reset.agentId in context.resumed) {
                        Log.w(TAG, "Journal of agent '${reset.agentId}' could not cover the resume; resetting")
                        context.fold.reset(reset.agentId)
                        onEvent(SessionEvent.TranscriptStale(reset.agentId))
                    } else {
                        Log.d(TAG, "Transcript reset for agent '${reset.agentId}' at seq ${reset.seq}")
                    }
                }
            // The subscribe_v2 ack (docs/server-api.md, Control frames): an unknown
            // session answers code 0 with its id in not_found — a load failure, never
            // silence. A malformed subscribe answers code 1; there is nothing to retry.
            // An accepted subscribe resets the approval and question queues to the
            // reset snapshots' pending interactions (tickets 171/172): the server
            // re-sends every still-pending interaction as a standalone frame right
            // after the ack, so a decision missed while away clears and the re-sent
            // frames dedup into the queues.
            "ack" -> if (frame.id == context.subscribeId) {
                val ack = frame.decodePayload<AckPayload>()
                if (frame.code != 0) {
                    Log.w(TAG, "subscribe_v2 refused: ${frame.code} ${frame.msg}")
                } else if (ack?.notFound?.contains(sessionId) == true) {
                    Log.w(TAG, "subscribe_v2: session $sessionId not found")
                    return false
                } else if (ack?.accepted?.contains(sessionId) == true) {
                    onEvent(SessionEvent.ApprovalsReset(context.resetApprovals.values.toList()))
                    onEvent(SessionEvent.QuestionsReset(context.resetQuestions.values.toList()))
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
                ?.heartbeatMs?.let { context.watchdog.onServerHello(it) }
            // The flat agent/usage state frames (docs/server-api.md, Session frames —
            // ticket 177): sent before the subscribe ack (the chat's seed) and again on
            // every change. Their fields sit beside `type`, so the frame text itself is
            // decoded. Every agent's `agent` frame is emitted, tagged with its agent id:
            // main's feeds the chat, a child's — part of its announcement when it
            // joins the connection — routes to the sub-agent store (ticket 182). Only
            // main's `usage` is consumed (the page has no context ring).
            "agent" -> decodeFlat<AgentStateFrame>(frame.type, text)
                ?.toSessionEvents()
                ?.forEach { onEvent(it) }
            "usage" -> decodeFlat<UsageFrame>(frame.type, text)
                ?.takeIf { it.agentId == MAIN_AGENT_ID }
                ?.toSessionEvent()
                ?.let { onEvent(it) }
            // The flat interaction frame (docs/server-api.md, Interactions — tickets
            // 171/172): approvals and questions of every agent, pending and ended;
            // never journaled, re-sent for the pending ones after each accepted
            // subscribe.
            "interaction" -> decodeFlat<InteractionFrame>(frame.type, text)
                ?.let(mapper::toInteractionEvent)
                ?.let { onEvent(it) }
            // The flat todo frame (docs/server-api.md, Todos — ticket 178): the agent's
            // full list, sent before the subscribe ack (the cold-start seed) and on
            // every change, undo and cold restore included. Every agent's is emitted,
            // tagged with its agent id: the chat applies main's, the sub-agent store a
            // child's (SessionEvent.isSubagentScoped).
            "todo" -> decodeFlat<TodoFrame>(frame.type, text)
                ?.let { onEvent(SessionEvent.TodoUpdated(it.toTodos(), it.agentId)) }
            // The flat task frames (docs/server-api.md, Sub-agents → Task frames and
            // controls — ticket 179), every agent's, tagged with the OWNING agent: the
            // chat's jobs widget takes main's, the sub-agent store a child's
            // (SessionEvent.isSubagentScoped). `task.list` (before the ack, on every
            // connection) holds only the running tasks; `task` is one task's full state
            // on every change. An undecodable entry (blank id, unknown status) is
            // dropped alone.
            "task.list" -> decodeFlat<TaskListFrame>(frame.type, text)
                ?.let { list ->
                    val listed = list.tasks.mapNotNull { it.toTaskState() }
                    recordTaskList(sessionId, list.agentId, listed)
                    onEvent(SessionEvent.TaskListed(listed, list.agentId))
                }
            "task" -> {
                val owner = decodeFlat<TaskFrame>(frame.type, text)?.agentId
                val task = decodeFlat<TaskStateDto>(frame.type, text)?.toTaskState()
                if (owner != null && task != null) {
                    recordTask(sessionId, owner, task)
                    onEvent(SessionEvent.TaskUpdated(task, owner))
                }
            }
            // A task control's reply (ticket 179), matched by request_id to the
            // control sent on THIS connection. A code-0 reply's state applies like any
            // task frame — emitted before the caller resumes; an error reply applies
            // nothing (its state, when present, is the unchanged one).
            "response" -> decodeFlat<TaskControlResponse>(frame.type, text)
                ?.let { response ->
                    val pending = response.requestId?.let { context.connection.pending[it] }
                    if (pending == null) {
                        Log.d(TAG, "Dropping 'response' for unknown request '${response.requestId}'")
                        return@let
                    }
                    val task = response.task?.toTaskState()
                    if (response.code == 0 && task != null) {
                        recordTask(sessionId, pending.agentId, task)
                        onEvent(SessionEvent.TaskUpdated(task, pending.agentId))
                    }
                    pending.reply.complete(response)
                }
            // A watched task's output chunk (ticket 180): only ever sent to a
            // connection that sent task.watch; the watcher assembles them by offset.
            "task.output" -> decodeFlat<TaskOutputFrame>(frame.type, text)
                ?.let { taskOutputsOf(sessionId).emit(TaskOutputChunk(it.agentId, it.taskId, it.offset, it.text)) }
            // The global busy push (docs/server-api.md, Global events — ticket 181),
            // already filtered to this session above. Only `busy` is read: its idle
            // edge ends a compaction whose completed marker never came.
            "event.session.work_changed" -> frame.decodePayload<ChatWorkChangedPayload>()
                ?.busy?.let { onEvent(SessionEvent.SessionWorkChanged(it)) }
            // The other flat state frames (goal) are out of scope: ignored, but logged
            // so a server rename shows up during development.
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

    private inline fun <reified T> decodeFlat(type: String, text: String): T? =
        runCatching { json.decodeFromString<T>(text) }
            .onFailure { e -> Log.w(TAG, "Dropping undecodable '$type' frame", e) }
            .getOrNull()

    private fun buildWsUrl(settings: ServerSettings): HttpUrl? =
        // OkHttp's HttpUrl holds only http/https; newWebSocket derives ws/wss itself.
        settings.serverUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegments("api/v1/ws")
            ?.build()

    /** `{"type":"subscribe_v2","id":…,"payload":{"session_id":…,"transcript":{"*":"delta"},
     * "transcript_since":{"main":<seq>,…}}}` — one frame per session (there is no
     * multi-session subscribe); grade `delta` streams every agent's text/thinking as
     * `append` chunks. [since] are the resume watermarks (ticket 176), omitted when
     * none is known — the server then sends every agent's baseline reset. */
    private fun subscribeFrame(id: String, sessionId: String, since: Map<String, Long>): String = buildJsonObject {
        put("type", "subscribe_v2")
        put("id", id)
        putJsonObject("payload") {
            put("session_id", sessionId)
            putJsonObject("transcript") { put("*", "delta") }
            if (since.isNotEmpty()) {
                putJsonObject("transcript_since") { since.forEach { (agentId, seq) -> put(agentId, seq) } }
            }
        }
    }.toString()

    /** A flat task control (ticket 179): its fields sit beside `type` and `request_id`
     * replaces `id` — a frame without a readable `request_id` closes the socket (1008).
     * [extra] adds a control's own fields (`task.watch`'s offsets — ticket 180). */
    private fun taskControlFrame(
        wire: String,
        requestId: String,
        sessionId: String,
        agentId: String,
        taskId: String,
        extra: JsonObjectBuilder.() -> Unit = {},
    ): String = buildJsonObject {
        put("type", wire)
        put("request_id", requestId)
        put("session_id", sessionId)
        put("agent_id", agentId)
        put("task_id", taskId)
        extra()
    }.toString()

    private fun pongFrame(nonce: String): String = buildJsonObject {
        put("type", "pong")
        putJsonObject("payload") { put("nonce", nonce) }
    }.toString()

    private companion object {
        const val TAG = "SessionSocket"
        const val MAX_BACKOFF_SHIFT = 5 // 1 s << 5 = 32 s, clamped to 30 s below

        /** How long a task control waits for its `response` before failing. */
        const val CONTROL_TIMEOUT_MILLIS = 15_000L

        const val WATCH = "task.watch"
        const val UNWATCH = "task.unwatch"

        /** Output chunks buffered per session before the frame loop waits on a slow
         * watcher. */
        const val OUTPUT_BUFFER = 64

        fun backoffMillis(attempt: Int): Long = (1_000L shl attempt).coerceAtMost(30_000L)
    }
}

/**
 * The `agent` state frame → the chat's events (ticket 177): the model/effort binding
 * when the frame names either (blank or absent = "nothing said" — a model-less session
 * omits both), the effective permission mode, the swarm mode (ticket 174; absent =
 * nothing said), and the busy state (`running`/`idle`; an unknown status emits
 * nothing). `plan_mode`/`tower_mode` are not mapped.
 */
internal fun AgentStateFrame.toSessionEvents(): List<SessionEvent> = buildList {
    val boundModel = model?.takeIf { it.isNotBlank() }
    val boundEffort = thinking?.takeIf { it.isNotBlank() }
    if (boundModel != null || boundEffort != null) {
        add(SessionEvent.SessionModelUpdated(boundModel, boundEffort, agentId))
    }
    permissionMode?.takeIf { it.isNotBlank() }?.let { add(SessionEvent.SessionPermissionUpdated(it, agentId)) }
    swarmMode?.let { add(SessionEvent.SessionSwarmModeUpdated(it, agentId)) }
    when (status) {
        "running" -> add(SessionEvent.AgentBusyUpdated(running = true, agentId = agentId))
        "idle" -> add(SessionEvent.AgentBusyUpdated(running = false, agentId = agentId))
    }
}

/** The `usage` state frame → [SessionEvent.ContextUsageUpdated] (ticket 177), only
 * with the used tokens AND a positive limit: an absent/zero `max_context_tokens` is
 * "unknown" and must never render a 0/0 ring. */
internal fun UsageFrame.toSessionEvent(): SessionEvent? {
    val used = contextTokens ?: return null
    val max = maxContextTokens?.takeIf { it > 0 } ?: return null
    return SessionEvent.ContextUsageUpdated(ContextUsage(used, max), agentId)
}
