package fr.louissaglio.kimi.android.data.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import fr.louissaglio.kimi.android.data.network.ApiClient
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Thin public facade of the chat data layer: delegates the REST verbs to [ChatApi],
 * the WebSocket stream to [SessionSocket], the per-session model/effort prefs to
 * [SessionPrefsStore] and the models/config fetches to [ModelsRepository], keeping
 * only the send-path glue (model/effort resolution) here. This is the error
 * boundary: failures come back as [Result.failure] of
 * [fr.louissaglio.kimi.android.data.DataError].
 */
@Singleton
class ChatRepository @Inject constructor(
    client: OkHttpClient,
    json: Json,
    dataStore: DataStore<Preferences>,
) {
    private val api = ApiClient(client, json)
    private val mapper = ChatMessageMapper(json)
    private val chatApi = ChatApi(api, mapper)
    private val models = ModelsRepository(api)
    private val prefs = SessionPrefsStore(dataStore)
    private val socket = SessionSocket(client, json, mapper)

    /**
     * Fetches one page of history from `GET /api/v1/sessions/{id}/messages` (the
     * hardcoded page cap is tracked in todo/043), mapped to domain messages
     * oldest-first (the wire order is newest-first).
     */
    suspend fun getMessages(settings: ServerSettings, sessionId: String): Result<List<ChatMessage>> =
        chatApi.messages(settings, sessionId)

    /** Downloads one uploaded file (`GET /api/v1/files/{id}`) — attachment chips and
     * image viewers fetch their bytes on demand (ticket 034). */
    suspend fun downloadFile(settings: ServerSettings, fileId: String): Result<FileDownload> =
        chatApi.downloadFile(settings, fileId)

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
     * text item; [text] may be blank when attachments are present.
     */
    suspend fun sendPrompt(
        settings: ServerSettings,
        sessionId: String,
        text: String,
        attachments: List<FileMeta> = emptyList(),
    ): Result<Unit> {
        val model = promptModel(settings, sessionId)
        return chatApi.postPrompt(settings, sessionId, text, attachments, model, promptThinking(settings, sessionId, model))
            .map { }
    }

    /**
     * Steers a message into the running turn (docs/server-api.md, Steer): the prompt is
     * posted as usual — while busy the server queues it — then `prompts:steer` injects the
     * queued prompt into the active turn. The steered user message echoes over the
     * WebSocket (context.spliced) like a regular one, so success carries no payload.
     */
    suspend fun steer(
        settings: ServerSettings,
        sessionId: String,
        text: String,
        attachments: List<FileMeta> = emptyList(),
    ): Result<Unit> {
        val model = promptModel(settings, sessionId)
        val promptId = chatApi.postPrompt(settings, sessionId, text, attachments, model, promptThinking(settings, sessionId, model))
            .getOrElse { return Result.failure(it) }
        return chatApi.postSteer(settings, sessionId, promptId)
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

    /** The session's stored model choice, null when the session follows the server default. */
    suspend fun getSessionModel(sessionId: String): String? = prefs.model(sessionId)

    /** Persists the session's model choice; subsequent prompts carry it (see [promptModel]). */
    suspend fun setSessionModel(sessionId: String, model: String) = prefs.setModel(sessionId, model)

    /** The session's stored effort choice, null when the session follows the server default. */
    suspend fun getSessionEffort(sessionId: String): String? = prefs.effort(sessionId)

    /** Persists the session's effort choice; subsequent prompts carry it (see [promptThinking]). */
    suspend fun setSessionEffort(sessionId: String, effort: String) = prefs.setEffort(sessionId, effort)

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

    /** Lightweight live status (`GET /api/v1/sessions/{id}/status`): the mid-turn flag
     * plus the context window fill when the server sends token counts (ticket 041). */
    suspend fun getSessionStatus(settings: ServerSettings, sessionId: String): Result<SessionStatus> =
        chatApi.sessionStatus(settings, sessionId)

    /**
     * Streams [SocketEvent]s for [sessionId]. One collection = one socket owned here
     * (not by the UI); cancelling the collection closes it. On a drop the stream
     * retries with exponential backoff and re-subscribes with the last seen cursor, so
     * the server replays missed events; the collector is told via
     * [SocketEvent.Reconnected] so it can also refetch history.
     */
    fun observeSession(settings: ServerSettings, sessionId: String): Flow<SocketEvent> =
        socket.observe(settings, sessionId)
}
