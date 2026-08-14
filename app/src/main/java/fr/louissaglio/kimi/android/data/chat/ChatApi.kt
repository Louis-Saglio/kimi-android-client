package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.network.ApiClient
import fr.louissaglio.kimi.android.data.network.Envelope
import fr.louissaglio.kimi.android.data.network.toAckResult
import fr.louissaglio.kimi.android.data.network.toNullableResult
import fr.louissaglio.kimi.android.data.network.toResult
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull

/**
 * The session REST verbs (docs/server-api.md). Every call goes through the
 * [ApiClient] scaffold, which owns the error boundary: failures come back as
 * [Result.failure] of [DataError].
 */
@Singleton
class ChatApi @Inject constructor(
    private val api: ApiClient,
    private val mapper: ChatMessageMapper,
) {
    /**
     * Fetches one page of history from `GET /api/v1/sessions/{id}/messages`, mapped to
     * domain messages oldest-first (the wire order is newest-first — paged responses
     * too). [beforeId] is sent as the `before_id` cursor to page backwards (ticket
     * 043); null fetches the newest page.
     */
    suspend fun messages(settings: ServerSettings, sessionId: String, beforeId: String? = null): Result<MessagePage> {
        val cursor = beforeId?.let { "&before_id=$it" }.orEmpty()
        return api.get(settings, "/api/v1/sessions/$sessionId/messages?page_size=100$cursor") { body ->
            api.json.decodeFromString<Envelope<MessageListData>>(body).toResult().map { data ->
                MessagePage(
                    // Wire order is newest-first; domain messages are oldest-first.
                    items = data.items.asReversed().map { mapper.toDomain(it) },
                    hasMore = data.hasMore,
                )
            }
        }
    }

    /**
     * Fetches one page of an agent's turn-granular transcript
     * (`GET /api/v1/sessions/{id}/transcript`, docs/server-api.md, Transcript) — the
     * history backing of the sub-agent page (ticket 046). [beforeTurn] = null fetches
     * the newest page; turns come back ascending within the page and stay so.
     */
    suspend fun transcript(
        settings: ServerSettings,
        sessionId: String,
        agentId: String,
        beforeTurn: String?,
        pageSize: Int,
    ): Result<TranscriptPage> {
        val cursor = beforeTurn?.let { "&before_turn=$it" }.orEmpty()
        return api.get(settings, "/api/v1/sessions/$sessionId/transcript?agent_id=$agentId&page_size=$pageSize$cursor") { body ->
            api.json.decodeFromString<Envelope<TranscriptData>>(body).toResult().map(mapper::toDomain)
        }
    }

    /**
     * Downloads one uploaded file (`GET /api/v1/files/{id}`, docs/server-api.md) —
     * attachment chips and image viewers fetch their bytes on demand (ticket 034).
     * The bearer token rides on the shared client.
     */
    suspend fun downloadFile(settings: ServerSettings, fileId: String): Result<FileDownload> =
        api.getResponse(settings, "/api/v1/files/$fileId") { response ->
            Result.success(
                FileDownload(
                    bytes = response.body?.bytes() ?: ByteArray(0),
                    mediaType = response.body?.contentType()?.toString()
                        ?: "application/octet-stream",
                ),
            )
        }

    /**
     * Uploads one file (`POST /api/v1/files`, multipart, docs/server-api.md, Files) and
     * returns its server metadata. The returned [FileMeta.mediaType] is sniffed from the
     * bytes server-side, so it — not the declared [mediaType] — decides the prompt part
     * kind in [postPrompt]. One file per request; the caller uploads sequentially.
     */
    suspend fun uploadFile(
        settings: ServerSettings,
        name: String,
        mediaType: String,
        bytes: ByteArray,
    ): Result<FileMeta> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                name,
                bytes.toRequestBody(mediaType.toMediaTypeOrNull()),
            )
            .addFormDataPart("name", name)
            .build()
        return api.post(settings, "/api/v1/files", body) { responseBody ->
            api.json.decodeFromString<Envelope<FileUploadData>>(responseBody).toResult().map { data ->
                FileMeta(id = data.id, name = data.name, mediaType = data.mediaType, sizeBytes = data.size)
            }
        }
    }

    /** POSTs the prompt and returns its id (queued while busy, running otherwise).
     * [thinking] is the verified per-prompt effort override (a plain string,
     * docs/server-api.md); null leaves the field out. [attachments] become content items
     * before the text item (ticket 033): an `image` part for image MIME types, a `file`
     * part otherwise (docs/server-api.md, Prompts). A blank [text] adds no text item — the
     * caller guarantees text and attachments are not both empty. */
    suspend fun postPrompt(
        settings: ServerSettings,
        sessionId: String,
        text: String,
        attachments: List<FileMeta>,
        model: String?,
        thinking: String?,
    ): Result<String> {
        val body = api.jsonBody {
            putJsonArray("content") {
                for (attachment in attachments) {
                    if (attachment.isImage) {
                        addJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("kind", "file")
                                put("file_id", attachment.id)
                            }
                        }
                    } else {
                        addJsonObject {
                            put("type", "file")
                            put("file_id", attachment.id)
                            put("name", attachment.name)
                            put("media_type", attachment.mediaType)
                            put("size", attachment.sizeBytes)
                        }
                    }
                }
                if (text.isNotBlank()) {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
            }
            if (model != null) put("model", model)
            if (thinking != null) put("thinking", thinking)
        }
        return api.post(settings, "/api/v1/sessions/$sessionId/prompts", body) { responseBody ->
            api.json.decodeFromString<Envelope<PromptData>>(responseBody).toResult().map { it.promptId }
        }
    }

    /** `POST /api/v1/sessions/{id}/prompts:steer`: injects the queued prompt into the
     * active turn (docs/server-api.md, Steer). */
    suspend fun postSteer(settings: ServerSettings, sessionId: String, promptId: String): Result<Unit> {
        val body = api.jsonBody {
            putJsonArray("prompt_ids") { add(promptId) }
        }
        return api.post(settings, "/api/v1/sessions/$sessionId/prompts:steer", body) { responseBody ->
            api.json.decodeFromString<Envelope<JsonObject>>(responseBody).toAckResult()
        }
    }

    /**
     * Aborts the running turn (`POST /api/v1/sessions/{id}:abort`, docs/server-api.md).
     * The ack only confirms the request; the teardown (prompt.aborted → turn.ended)
     * arrives over the WebSocket, so success carries no payload.
     */
    suspend fun abortPrompt(settings: ServerSettings, sessionId: String): Result<Unit> =
        api.post(settings, "/api/v1/sessions/$sessionId:abort", api.jsonBody {}) { body ->
            api.json.decodeFromString<Envelope<JsonObject>>(body).toAckResult()
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
    ): Result<Unit> {
        val body = api.jsonBody {
            put("decision", if (approved) "approved" else "rejected")
        }
        return api.post(settings, "/api/v1/sessions/$sessionId/approvals/$approvalId", body) { responseBody ->
            api.json.decodeFromString<Envelope<JsonObject>>(responseBody).toAckResult()
        }
    }

    /**
     * Polls the pending questions of the session
     * (`GET /api/v1/sessions/{id}/questions?status=pending`, docs/server-api.md) — the
     * source of truth used to re-derive the pending question after a reconnect or a
     * history refetch, since `GET .../questions/{qid}` does not exist.
     */
    suspend fun pendingQuestions(settings: ServerSettings, sessionId: String): Result<List<PendingQuestion>> =
        api.get(settings, "/api/v1/sessions/$sessionId/questions?status=pending") { body ->
            api.json.decodeFromString<Envelope<QuestionListData>>(body).toResult().map { data ->
                data.items.map { mapper.toDomain(it) }
            }
        }

    /**
     * Posts the user's answers for one question
     * (`POST /api/v1/sessions/{id}/questions/{questionId}`, docs/server-api.md): keys are
     * the per-question item ids, values are a discriminated union on `kind` (`single` /
     * `other` / `multi` / `multi_with_other`, the last two for multi-select items —
     * ticket 090). The ack
     * only confirms the answers were registered; the pending UI clears when the
     * `event.question.answered` frame arrives over the WebSocket.
     */
    suspend fun answerQuestion(
        settings: ServerSettings,
        sessionId: String,
        questionId: String,
        answers: List<QuestionAnswer>,
    ): Result<Unit> {
        val body = api.jsonBody {
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
                            is QuestionAnswer.Multi -> {
                                put("kind", "multi")
                                putJsonArray("option_ids") { answer.optionIds.forEach { add(it) } }
                            }
                            is QuestionAnswer.MultiWithOther -> {
                                put("kind", "multi_with_other")
                                putJsonArray("option_ids") { answer.optionIds.forEach { add(it) } }
                                put("other_text", answer.otherText)
                            }
                        }
                    }
                }
            }
        }
        return api.post(settings, "/api/v1/sessions/$sessionId/questions/$questionId", body) { responseBody ->
            api.json.decodeFromString<Envelope<JsonObject>>(responseBody).toAckResult()
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
    ): Result<Unit> =
        api.post(settings, "/api/v1/sessions/$sessionId/questions/$questionId:dismiss", api.jsonBody {}) { body ->
            // Dismiss acks succeed with envelope code 40909 (docs/server-api.md):
            // gate on `data.dismissed`.
            val envelope = api.json.decodeFromString<Envelope<JsonObject>>(body)
            val dismissed = envelope.data?.get("dismissed")?.jsonPrimitive?.booleanOrNull == true
            when {
                envelope.code == 0 || dismissed -> Result.success(Unit)
                else -> Result.failure(DataError.Api(envelope.code, envelope.msg))
            }
        }

    /**
     * Starts a compaction (`POST /api/v1/sessions/{id}:compact`, docs/server-api.md,
     * Compact — ticket 056). Fire-and-forget: the ack only confirms the request; the
     * progress (`compaction.started` → `compaction.completed`) arrives over the
     * WebSocket, so success carries no payload. The non-zero envelope `msg` (40910
     * empty session / turn active, 40401 unknown session) rides DataError.Api's
     * serverMessage to the snackbar unchanged.
     */
    suspend fun compact(settings: ServerSettings, sessionId: String): Result<Unit> =
        api.post(settings, "/api/v1/sessions/$sessionId:compact", api.jsonBody {}) { body ->
            api.json.decodeFromString<Envelope<JsonObject>>(body).toAckResult()
        }

    /**
     * Fetches the session snapshot (`GET /api/v1/sessions/{id}/snapshot`,
     * docs/server-api.md, Session snapshot — ticket 085): the whole session state as
     * one atomic bundle at an event-stream watermark — the single cold-start/resync
     * entry point. Everything the event stream never replays (sub-agent roster,
     * pending approvals/questions) or never persisted arrives here.
     */
    suspend fun snapshot(settings: ServerSettings, sessionId: String): Result<SessionSnapshot> =
        api.get(settings, "/api/v1/sessions/$sessionId/snapshot") { body ->
            api.json.decodeFromString<Envelope<SnapshotData>>(body).toResult().map(mapper::toDomain)
        }

    /** Lightweight live status (`GET /api/v1/sessions/{id}/status`): the mid-turn flag
     * and the context window fill (ticket 041). A data-less success envelope degrades
     * to "not busy, fill unknown" (see SessionStatus). */
    suspend fun sessionStatus(settings: ServerSettings, sessionId: String): Result<SessionStatus> =
        api.get(settings, "/api/v1/sessions/$sessionId/status") { body ->
            api.json.decodeFromString<Envelope<SessionStatusData>>(body).toNullableResult().map { data ->
                SessionStatus(
                    busy = data?.busy == true,
                    // 0.34.0: max_context_tokens is omittable, 0 decodes as the unknown
                    // marker — drop the usage instead of leaking a phantom 0/0 fill
                    // (same rule as the snapshot guard, ChatMessageMapper).
                    contextUsage = data?.takeIf { it.maxContextTokens > 0 }
                        ?.let { ContextUsage(it.contextTokens, it.maxContextTokens) },
                )
            }
        }

    /**
     * Lists the session's background tasks (`GET /api/v1/sessions/{id}/tasks`,
     * docs/server-api.md, REST /tasks — ticket 092): background sub-agents and bash;
     * question flows (kind `tool`) are filtered out by the mapper. Terminal tasks stay
     * listed. [status] is the optional wire filter (`running|completed|failed|
     * cancelled`); null fetches unfiltered — the jobs sheet's cold-start seed, so
     * recently finished jobs linger in the list.
     */
    suspend fun listTasks(settings: ServerSettings, sessionId: String, status: String? = null): Result<List<BackgroundTask>> {
        val filter = status?.let { "?status=$it" }.orEmpty()
        return api.get(settings, "/api/v1/sessions/$sessionId/tasks$filter") { body ->
            api.json.decodeFromString<Envelope<TaskListData>>(body).toResult().map { data ->
                data.items.mapNotNull(mapper::toDomain)
            }
        }
    }

    /**
     * Fetches one background task (`GET /api/v1/sessions/{id}/tasks/{taskId}`,
     * docs/server-api.md, REST /tasks — ticket 096): the job screen's data source.
     * [withOutput] adds `output_preview` (the stored stdout/stderr tail — live while
     * a bash task runs, growing between polls; no `tool.progress` frames exist for
     * background bash) and `output_bytes`. An unknown task answers envelope 40406,
     * which surfaces as the typed [DataError.Api].
     */
    suspend fun getTask(
        settings: ServerSettings,
        sessionId: String,
        taskId: String,
        withOutput: Boolean = true,
    ): Result<BackgroundTask> {
        val query = if (withOutput) "?with_output=true" else ""
        return api.get(settings, "/api/v1/sessions/$sessionId/tasks/$taskId$query") { body ->
            api.json.decodeFromString<Envelope<TaskDto>>(body).toResult().mapCatching { dto ->
                // A kind/status/timestamp the mapper cannot place drops the entry on
                // list reads; on a single read that IS the response — fail typed.
                mapper.toDomain(dto)
                    ?: throw DataError.MalformedResponse(IllegalStateException("Unusable task '${dto.id}'"))
            }
        }
    }

    /**
     * Cancels one background task (`POST /api/v1/sessions/{id}/tasks/{taskId}:cancel`,
     * docs/server-api.md — ticket 092). A running task acks `{"cancelled":true}`; a
     * terminal one answers envelope code 40904, which surfaces as the typed
     * [DataError.Api] so the caller can treat it as already-terminal success. The
     * teardown (`task.terminated`) arrives over the WebSocket, so success carries no
     * payload.
     */
    suspend fun cancelTask(settings: ServerSettings, sessionId: String, taskId: String): Result<Unit> =
        api.post(settings, "/api/v1/sessions/$sessionId/tasks/$taskId:cancel", api.jsonBody {}) { body ->
            api.json.decodeFromString<Envelope<TaskCancelData>>(body).toAckResult()
        }
}
