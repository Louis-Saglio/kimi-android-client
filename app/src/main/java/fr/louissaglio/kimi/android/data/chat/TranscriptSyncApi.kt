package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.network.ApiClient
import fr.louissaglio.kimi.android.data.network.Envelope
import fr.louissaglio.kimi.android.data.network.toResult
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two REST reads of the transcript cold start that are not history pages (server
 * 2.5.0 — ticket 176, the replacement of the removed `/snapshot`): the main agent's
 * prompt queue (`GET .../prompts`, docs/server-api.md, Prompts → Queue snapshot) and
 * the op-journal catch-up probe (`GET .../transcript/ops`, Transcript catch-up). The
 * transcript page itself stays [ChatApi.transcript].
 */
@Singleton
class TranscriptSyncApi @Inject constructor(
    private val api: ApiClient,
    private val mapper: ChatMessageMapper,
) {
    /** `GET /api/v1/sessions/{id}/prompts`: the running prompt and the queued ones, in
     * queue order. Each prompt's submitted content maps through the history message
     * mapper (a user message keyed by the prompt's user message id), so a seeded queued
     * bubble renders exactly like the local echo of a prompt this client sent. */
    suspend fun promptQueue(settings: ServerSettings, sessionId: String): Result<PromptQueue> =
        api.get(settings, "/api/v1/sessions/$sessionId/prompts") { body ->
            api.json.decodeFromString<Envelope<PromptQueueData>>(body).toResult().map { data ->
                PromptQueue(
                    active = data.active?.let(::toDomain),
                    queued = data.queued.map(::toDomain),
                )
            }
        }

    /** `GET /api/v1/sessions/{id}/transcript/ops?agent_id=…&since_seq=…`: whether the
     * agent's journal still covers every batch after [sinceSeq]. The batches are not
     * decoded — the WebSocket's `transcript_since` replays them; this read only
     * verifies the resume. */
    suspend fun catchUp(
        settings: ServerSettings,
        sessionId: String,
        agentId: String,
        sinceSeq: Long,
    ): Result<TranscriptCatchUp> =
        api.get(settings, "/api/v1/sessions/$sessionId/transcript/ops?agent_id=$agentId&since_seq=$sinceSeq") { body ->
            api.json.decodeFromString<Envelope<TranscriptCatchUpData>>(body).toResult().map { data ->
                TranscriptCatchUp(
                    complete = data.complete,
                    latestSeq = data.latestSeq,
                    sinceSeq = sinceSeq,
                )
            }
        }

    private fun toDomain(dto: QueuePromptDto): QueuedPrompt {
        val messageId = dto.userMessageId?.takeIf { it.isNotBlank() } ?: dto.promptId
        return QueuedPrompt(
            promptId = dto.promptId,
            message = mapper.toDomain(
                MessageDto(id = messageId, role = "user", content = dto.content, createdAt = dto.createdAt),
            ),
        )
    }
}

/** The main agent's prompt queue (`GET .../prompts`): [active] is the running prompt,
 * [queued] the waiting ones in queue order. */
data class PromptQueue(
    val active: QueuedPrompt?,
    val queued: List<QueuedPrompt>,
) {
    companion object {
        val Empty = PromptQueue(active = null, queued = emptyList())
    }
}

/** One prompt of the queue: [message] is the submitted content as a user message whose
 * id is the prompt's user message id (equal to [promptId] on every captured prompt —
 * the id the turn-start splice and the history commit will carry). */
data class QueuedPrompt(
    val promptId: String,
    val message: ChatMessage,
)

/** The journal coverage of one agent after [sinceSeq]. [isStale] is the full-refresh
 * signal: `complete:false` (the journal cannot reach back, or the session is not
 * live), or a latest seq BELOW the watermark — a restarted server's fresh journal,
 * which would otherwise answer a future cursor with silence. */
data class TranscriptCatchUp(
    val complete: Boolean,
    val latestSeq: Long,
    val sinceSeq: Long,
) {
    val isStale: Boolean
        get() = !complete || latestSeq < sinceSeq
}
