package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.sessions.SessionActivityStore
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Thin public facade of the chat data layer: delegates the REST verbs to [ChatApi],
 * the WebSocket stream to [SessionSocket], the per-session model/effort/permission
 * prefs to
 * [SessionPrefsStore], the models/config fetches to [ModelsRepository] and the
 * user-message activity recording to [SessionActivityStore] (ticket 086), keeping
 * only the send-path glue (model/effort/permission resolution) here. The collaborators come
 * from the Hilt graph (ticket 067). This is the error boundary: failures come back
 * as [Result.failure] of
 * [fr.louissaglio.kimi.android.data.DataError].
 */
@Singleton
class ChatRepository @Inject constructor(
    private val chatApi: ChatApi,
    private val models: ModelsRepository,
    private val prefs: SessionPrefsStore,
    private val socket: SessionSocket,
    private val activity: SessionActivityStore,
) {

    /**
     * Fetches one page of history from `GET /api/v1/sessions/{id}/messages`, mapped to
     * domain messages oldest-first (the wire order is newest-first). [beforeId] is the
     * `before_id` cursor for paging backwards (ticket 043); null fetches the newest page.
     */
    suspend fun getMessages(settings: ServerSettings, sessionId: String, beforeId: String? = null): Result<MessagePage> =
        chatApi.messages(settings, sessionId, beforeId)

    /** One page of an agent's turn-granular transcript — the sub-agent page's history
     * backing (ticket 046). [beforeTurn] = null fetches the newest page. */
    suspend fun getTranscript(
        settings: ServerSettings,
        sessionId: String,
        agentId: String,
        beforeTurn: String?,
        pageSize: Int,
    ): Result<TranscriptPage> = chatApi.transcript(settings, sessionId, agentId, beforeTurn, pageSize)

    /** Downloads one uploaded file (`GET /api/v1/files/{id}`) — attachment chips and
     * image viewers fetch their bytes on demand (ticket 034). */
    suspend fun downloadFile(settings: ServerSettings, fileId: String): Result<FileDownload> =
        chatApi.downloadFile(settings, fileId)

    /** Downloads one session-owned media (`GET /api/v1/sessions/{id}/media/{file_id}`)
     * — the canonical route for a `session_media` image source (ticket 104). */
    suspend fun downloadSessionMedia(settings: ServerSettings, sessionId: String, fileId: String): Result<FileDownload> =
        chatApi.downloadSessionMedia(settings, sessionId, fileId)

    /** Uploads one file (`POST /api/v1/files`, multipart) and returns its server
     * metadata (ticket 033). One file per request; the caller uploads sequentially. */
    suspend fun uploadFile(
        settings: ServerSettings,
        name: String,
        mediaType: String,
        bytes: ByteArray,
    ): Result<FileMeta> = chatApi.uploadFile(settings, name, mediaType, bytes)

    /**
     * Sends a user prompt (`POST /api/v1/sessions/{id}/prompts`). The response only
     * acknowledges the prompt: the user message and the agent's reply arrive over the
     * WebSocket (docs/server-api.md), so success carries no payload.
     * [attachments] (already uploaded, ticket 033) go into the content array before the
     * text item; [text] may be blank when attachments are present. Success records
     * [sessionId] in the [SessionActivityStore] so the session list bumps its row.
     */
    suspend fun sendPrompt(
        settings: ServerSettings,
        sessionId: String,
        text: String,
        attachments: List<FileMeta> = emptyList(),
    ): Result<Unit> {
        val model = promptModel(settings, sessionId)
        return chatApi.postPrompt(
            settings,
            sessionId,
            text,
            attachments,
            model,
            promptThinking(settings, sessionId, model),
            promptPermissionMode(sessionId),
        )
            .map { }
            .onSuccess { activity.record(sessionId) }
    }

    /**
     * Steers a message into the running turn (docs/server-api.md, Steer): the prompt is
     * posted as usual — while busy the server queues it — then `prompts:steer` injects the
     * queued prompt into the active turn. The steered user message echoes over the
     * WebSocket (context.spliced) like a regular one, so success carries no payload.
     * Success records [sessionId] in the [SessionActivityStore] like [sendPrompt].
     */
    suspend fun steer(
        settings: ServerSettings,
        sessionId: String,
        text: String,
        attachments: List<FileMeta> = emptyList(),
    ): Result<Unit> {
        val model = promptModel(settings, sessionId)
        val promptId = chatApi.postPrompt(
            settings,
            sessionId,
            text,
            attachments,
            model,
            promptThinking(settings, sessionId, model),
            promptPermissionMode(sessionId),
        )
            .getOrElse { return Result.failure(it) }
        return chatApi.postSteer(settings, sessionId, promptId)
            .onSuccess { activity.record(sessionId) }
    }

    /**
     * The model stamped on every prompt (ticket 031): the session's own choice when one
     * was picked, else the server default from [ModelsRepository.defaultModel] (the 017
     * behavior), else no model field at all. This resolution feeds [ChatApi.postPrompt],
     * the single choke point both [sendPrompt] and [steer] go through.
     */
    private suspend fun promptModel(settings: ServerSettings, sessionId: String): String? =
        prefs.model(sessionId) ?: models.defaultModel(settings)

    /**
     * The thinking effort stamped on a prompt (ticket 032): the session's stored choice,
     * but only when the models cache confirms the effective [model] supports it — a stale
     * choice (e.g. picked on K3, then the session switched to K2.7) is dropped instead of
     * sent. No stored value, an uncached models list, or a model without effort levels all
     * mean no `thinking` field (the server default applies).
     */
    private suspend fun promptThinking(settings: ServerSettings, sessionId: String, model: String?): String? {
        val effort = prefs.effort(sessionId) ?: return null
        val supportEfforts = models.cachedSupportEfforts(settings, model) ?: return null
        return effort.takeIf { it in supportEfforts }
    }

    /**
     * The permission mode stamped on every prompt (ticket 141): the session's stored
     * pick, else no `permission_mode` field at all — unlike [promptModel] there is no
     * server-side fallback to resolve here (absent = follow the server). The per-prompt
     * stamp binds the session for its remaining turns, so a stored pick self-heals on
     * every send. This resolution feeds [ChatApi.postPrompt], the single choke point
     * both [sendPrompt] and [steer] go through.
     */
    private suspend fun promptPermissionMode(sessionId: String): String? =
        prefs.permission(sessionId)

    /** The session's stored model choice, null when the session follows the server default. */
    suspend fun getSessionModel(sessionId: String): String? = prefs.model(sessionId)

    /** Persists the session's model choice; subsequent prompts carry it (see [promptModel]). */
    suspend fun setSessionModel(sessionId: String, model: String) = prefs.setModel(sessionId, model)

    /** The session's stored effort choice, null when the session follows the server default. */
    suspend fun getSessionEffort(sessionId: String): String? = prefs.effort(sessionId)

    /** Persists the session's effort choice; subsequent prompts carry it (see [promptThinking]). */
    suspend fun setSessionEffort(sessionId: String, effort: String) = prefs.setEffort(sessionId, effort)

    /** The session's stored permission-mode pick (ticket 141), null when the session
     * follows the server. */
    suspend fun getSessionPermission(sessionId: String): String? = prefs.permission(sessionId)

    /** Persists the session's permission-mode pick; subsequent prompts carry it (see
     * [promptPermissionMode]). */
    suspend fun setSessionPermission(sessionId: String, permission: String) = prefs.setPermission(sessionId, permission)

    /** The models the server offers (`GET /api/v1/models`), for the chat screen's model
     * picker. Cached per server URL; fetch failures are not cached. */
    suspend fun getModels(settings: ServerSettings): Result<List<ServerModel>> = models.models(settings)

    /** The server-wide default model id; null when unknown — fetch failures degrade to
     * null instead of blocking the caller. */
    suspend fun getDefaultModel(settings: ServerSettings): String? = models.defaultModel(settings)

    /** Aborts the running turn (`POST /api/v1/sessions/{id}:abort`); the teardown
     * arrives over the WebSocket, so success carries no payload. */
    suspend fun abortPrompt(settings: ServerSettings, sessionId: String): Result<Unit> =
        chatApi.abortPrompt(settings, sessionId)

    /** Posts the user's decision for one approval request; the pending UI clears when
     * the `event.approval.resolved` frame arrives over the WebSocket. */
    suspend fun postApprovalDecision(
        settings: ServerSettings,
        sessionId: String,
        approvalId: String,
        approved: Boolean,
    ): Result<Unit> = chatApi.postApprovalDecision(settings, sessionId, approvalId, approved)

    /** Polls the pending questions of the session — the source of truth used to
     * re-derive the pending question after a reconnect or a history refetch. */
    suspend fun getPendingQuestions(settings: ServerSettings, sessionId: String): Result<List<PendingQuestion>> =
        chatApi.pendingQuestions(settings, sessionId)

    /** Posts the user's answers for one question; the pending UI clears when the
     * `event.question.answered` frame arrives over the WebSocket. */
    suspend fun answerQuestion(
        settings: ServerSettings,
        sessionId: String,
        questionId: String,
        answers: List<QuestionAnswer>,
    ): Result<Unit> = chatApi.answerQuestion(settings, sessionId, questionId, answers)

    /** Dismisses one pending question; the pending UI clears when the
     * `event.question.dismissed` frame arrives over the WebSocket. */
    suspend fun dismissQuestion(
        settings: ServerSettings,
        sessionId: String,
        questionId: String,
    ): Result<Unit> = chatApi.dismissQuestion(settings, sessionId, questionId)

    /** Starts a compaction (`POST /api/v1/sessions/{id}:compact`, docs/server-api.md,
     * Compact — ticket 056). Fire-and-forget: the progress arrives over the WebSocket
     * (compaction.started/completed), so success carries no payload. */
    suspend fun compact(settings: ServerSettings, sessionId: String): Result<Unit> =
        chatApi.compact(settings, sessionId)

    /** Lightweight live status (`GET /api/v1/sessions/{id}/status`): the mid-turn flag
     * and the context window fill (ticket 041). */
    suspend fun getSessionStatus(settings: ServerSettings, sessionId: String): Result<SessionStatus> =
        chatApi.sessionStatus(settings, sessionId)

    /** The session snapshot (`GET /api/v1/sessions/{id}/snapshot` — ticket 085): the
     * cold-start/resync bundle at an event-stream watermark. */
    suspend fun getSnapshot(settings: ServerSettings, sessionId: String): Result<SessionSnapshot> =
        chatApi.snapshot(settings, sessionId)

    /** The session's background tasks (`GET .../tasks` — ticket 092): background
     * sub-agents and bash, terminal ones included. [status] is the optional wire
     * filter; null fetches unfiltered. */
    suspend fun getTasks(settings: ServerSettings, sessionId: String, status: String? = null): Result<List<BackgroundTask>> =
        chatApi.listTasks(settings, sessionId, status)

    /** One background task with its stored output tail (`GET .../tasks/{taskId}` —
     * ticket 096's job screen): the bash preview grows between polls while running.
     * An unknown task surfaces as the typed DataError.Api(40406). */
    suspend fun getTask(settings: ServerSettings, sessionId: String, taskId: String): Result<BackgroundTask> =
        chatApi.getTask(settings, sessionId, taskId)

    /** Cancels one background task (`POST .../tasks/{taskId}:cancel` — ticket 092);
     * the `task.terminated` teardown arrives over the WebSocket. A terminal task
     * surfaces as the typed DataError.Api(40904). */
    suspend fun cancelTask(settings: ServerSettings, sessionId: String, taskId: String): Result<Unit> =
        chatApi.cancelTask(settings, sessionId, taskId)

    /** Seeds the socket's resume cursor from a snapshot watermark (ticket 085), so the
     * session's next subscribe resumes exactly where the bundle was taken. */
    fun seedSessionCursor(sessionId: String, seq: Long, epoch: String) =
        socket.seedCursor(sessionId, seq, epoch)

    /**
     * Streams [SocketEvent]s for [sessionId]. One collection = one socket owned here
     * (not by the UI); cancelling the collection closes it. On a drop the stream
     * retries with exponential backoff and re-subscribes with the last seen cursor —
     * which survives the collection itself (ticket 048), so a later collection
     * resumes too — and the server replays missed events. The collector is told via
     * [SocketEvent.Reconnected] (any subscribe carrying a cursor) so it can also
     * refetch history, and via [SocketEvent.ResyncRequired] when the server refuses
     * the cursor and no replay will come.
     */
    fun observeSession(settings: ServerSettings, sessionId: String): Flow<SocketEvent> =
        socket.observe(settings, sessionId)
}
