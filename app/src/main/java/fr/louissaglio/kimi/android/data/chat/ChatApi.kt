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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
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
     * history backing of the sub-agent page (ticket 046) and, since 2.5.0, the main
     * chat's cold-start read model (ticket 176: the page's running turn, pending
     * interactions, prompt entities, agent roster and `seq` watermark). [beforeTurn] =
     * null fetches the newest page; turns come back ascending within the page and stay
     * so.
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
     * Downloads one session-owned media (`GET /api/v1/sessions/{id}/media/{file_id}`,
     * docs/server-api.md, Files → Session media — ticket 104): the canonical route for
     * a `session_media` image source. Same binary semantics as [downloadFile]; errors
     * come back as plain HTTP 404, handled by the [ApiClient] scaffold like any status.
     */
    suspend fun downloadSessionMedia(settings: ServerSettings, sessionId: String, fileId: String): Result<FileDownload> =
        api.getResponse(settings, "/api/v1/sessions/$sessionId/media/$fileId") { response ->
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
     * docs/server-api.md); null leaves the field out. [permissionMode] is the
     * per-prompt permission-mode stamp (ticket 141, manual/yolo/auto): it binds the
     * session for its remaining turns; null leaves the field out (the session follows
     * the server). [attachments] become content items
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
        permissionMode: String?,
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
            if (permissionMode != null) put("permission_mode", permissionMode)
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
     * Posts the user's decision for one approval interaction
     * (`POST /api/v1/sessions/{id}/interactions/{interactionId}`, docs/server-api.md →
     * Interactions — ticket 171) with `{"kind":"approval","decision":"approved"|"rejected"}`.
     * Success is `code:0` with `data:{outcome, ended_at}`, which only confirms the
     * decision: the card clears on the `ended` `interaction` frame every subscriber
     * receives. `40404` (unknown interaction) and `40902` (already ended) are
     * [fr.louissaglio.kimi.android.data.DataError.Api] failures like any other code.
     */
    suspend fun postApprovalDecision(
        settings: ServerSettings,
        sessionId: String,
        approvalId: String,
        approved: Boolean,
    ): Result<Unit> {
        val body = api.jsonBody {
            put("kind", "approval")
            put("decision", if (approved) "approved" else "rejected")
        }
        return postInteraction(settings, sessionId, approvalId, body)
    }

    /** The one response route of both interaction kinds
     * (`POST /api/v1/sessions/{id}/interactions/{interactionId}`): any non-zero code is
     * a [DataError.Api] failure. */
    private suspend fun postInteraction(
        settings: ServerSettings,
        sessionId: String,
        interactionId: String,
        body: RequestBody,
    ): Result<Unit> =
        api.post(settings, "/api/v1/sessions/$sessionId/interactions/$interactionId", body) { responseBody ->
            api.json.decodeFromString<Envelope<JsonObject>>(responseBody).toAckResult()
        }

    /**
     * Posts the user's answers for one question interaction
     * (`POST /api/v1/sessions/{id}/interactions/{questionId}`, docs/server-api.md →
     * Interactions — ticket 172) with `{"kind":"question","answers":{…}}`: keys are the
     * per-question item ids, values are a discriminated union on `kind` (`single` /
     * `other` / `multi` / `multi_with_other`, the last two for multi-select items —
     * ticket 090). Success (`data:{outcome, ended_at}`) only confirms the answers: the
     * card clears on the `ended` `interaction` frame. `40404` (unknown interaction) and
     * `40902` (already ended) are [DataError.Api] failures like any other code.
     */
    suspend fun answerQuestion(
        settings: ServerSettings,
        sessionId: String,
        questionId: String,
        answers: List<QuestionAnswer>,
    ): Result<Unit> {
        val body = api.jsonBody {
            put("kind", "question")
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
        return postInteraction(settings, sessionId, questionId, body)
    }

    /**
     * Dismisses one pending question interaction
     * (`POST /api/v1/sessions/{id}/interactions/{questionId}` with
     * `{"kind":"question","dismissed":true}`, docs/server-api.md → Interactions —
     * ticket 172). Success is the ordinary `code:0` with `outcome:"dismissed"`; the card
     * clears on the `ended` `interaction` frame.
     */
    suspend fun dismissQuestion(
        settings: ServerSettings,
        sessionId: String,
        questionId: String,
    ): Result<Unit> =
        postInteraction(
            settings, sessionId, questionId,
            api.jsonBody {
                put("kind", "question")
                put("dismissed", true)
            },
        )

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
}
