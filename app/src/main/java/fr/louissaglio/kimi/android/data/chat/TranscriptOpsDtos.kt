package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// --- The subscribe_v2 transcript stream (docs/server-api.md, WebSocket → Transcript
// protocol — server 2.5.0, ticket 175) --------------------------------------------
// Two frame types ride a subscribed session: `transcript.reset` (the baseline at
// subscribe) and `transcript.ops` (one incremental op batch). Both put the emitting
// agent and the batch watermark under `payload`, snake_case, next to camelCase
// entity bodies (the upstream `packages/transcript` contract). Every field stays
// optional: the decoder must survive schema drift one op at a time.

/** `payload` of a `transcript.reset` frame. `snapshot.items` is always empty by design
 * (history pages in over REST), so a reset never carries rows to render — it must not
 * wipe what the chat already shows. [seq] is the agent's op-batch watermark. */
@Serializable
data class TranscriptResetPayload(
    @SerialName("agent_id") val agentId: String = MAIN_AGENT_ID,
    val snapshot: TranscriptSnapshotDto? = null,
    @SerialName("has_more_older") val hasMoreOlder: Boolean = false,
    val seq: Long = 0,
)

/** The reset's baseline. [prompts] are the prompt entities (all statuses),
 * [interactions] the interactions in entity form; [items] and [attachments] are kept
 * raw. Nothing here is consumed: the cold start reads the same state from
 * `GET .../transcript` (ticket 176) — a reset only matters as a resume signal. */
@Serializable
data class TranscriptSnapshotDto(
    val items: List<JsonElement> = emptyList(),
    val interactions: List<InteractionEntityDto> = emptyList(),
    val attachments: List<JsonObject> = emptyList(),
    val prompts: List<PromptEntityDto> = emptyList(),
    val hasMoreOlder: Boolean = false,
)

/** `payload` of a `transcript.ops` frame. [ops] stay raw here: the socket decodes them
 * one by one into [TranscriptOpDto], so one malformed op drops alone, never its batch.
 * [seq] is strictly increasing per agent (gaps when a grade filters a batch). */
@Serializable
data class TranscriptOpsPayload(
    @SerialName("agent_id") val agentId: String = MAIN_AGENT_ID,
    val ops: List<JsonObject> = emptyList(),
    val seq: Long = 0,
)

/**
 * One op of the transcript union, flat: [op] is the discriminator and each variant fills
 * only its own fields (the repo's flat-DTO convention — TranscriptItemDto):
 *
 * - `prompt.upsert` → [prompt]
 * - `turn.upsert` → [turn]
 * - `step.upsert` → [turnId] + [step]
 * - `frame.upsert` → [turnId] + [stepId] + [frame]
 * - `append` → [target] + [offset] + [text]
 * - `marker.upsert` → [item] (+ optional [beforeTurn])
 * - `interaction.upsert` → [interaction]
 * - `attachment.upsert` → [attachment]
 * - `items.remove` → [ids]
 */
@Serializable
data class TranscriptOpDto(
    val op: String = "",
    val prompt: PromptEntityDto? = null,
    val turn: TurnHeaderDto? = null,
    val turnId: String? = null,
    val stepId: String? = null,
    val step: StepHeaderDto? = null,
    val frame: TranscriptFrameDto? = null,
    val target: AppendTargetDto? = null,
    val offset: Int = 0,
    val text: String = "",
    val item: MarkerItemDto? = null,
    val beforeTurn: String? = null,
    val interaction: InteractionEntityDto? = null,
    val attachment: JsonObject? = null,
    val ids: List<String> = emptyList(),
)

/** A prompt entity (`prompt.upsert`, `transcript.reset.snapshot.prompts`). [status] is
 * `running`/`queued`/`completed`/`aborted`/`blocked`/`failed`; [steeredAt] marks a
 * steered prompt (both sides of a steer carry it). [promptId] equals the user message
 * id the prompt POST acked — the local echo's row id. */
@Serializable
data class PromptEntityDto(
    val promptId: String = "",
    val status: String = "",
    val userMessageId: String? = null,
    val content: List<ContentPartDto> = emptyList(),
    val createdAt: String? = null,
    val finishedAt: String? = null,
    val steeredAt: String? = null,
)

/** A turn header (`turn.upsert` — no `steps`, frames ride `frame.upsert`). [state] is
 * `running`/`completed`/`cancelled`/`failed`; a failed turn carries [error] — kept raw
 * (a message string, or an object with one), read by [errorMessage]. */
@Serializable
data class TurnHeaderDto(
    val turnId: String = "",
    val ordinal: Int = 0,
    val state: String = "",
    val triggerPromptId: String? = null,
    val error: JsonElement? = null,
)

/** A step header (`step.upsert`). [state] is `running`/`completed`/`interrupted`; an
 * interrupted step with [endReason] `error` is a failed provider call, [endMessage]
 * its text. [retry] is schema-level only (never captured live) — decoded leniently by
 * [StepRetryDto], an unreadable one is ignored. */
@Serializable
data class StepHeaderDto(
    val stepId: String = "",
    val turnId: String = "",
    val ordinal: Int = 0,
    val state: String = "",
    val retry: JsonObject? = null,
    val endReason: String? = null,
    val endMessage: String? = null,
)

/** The `retry` object of a running step (schema-level — not observed live). The field
 * names follow the removed `turn.step.retrying` payload; a shape that does not decode
 * to a positive [maxAttempts] emits no retry status. */
@Serializable
data class StepRetryDto(
    val attempt: Int = 0,
    val nextAttempt: Int = 0,
    val maxAttempts: Int = 0,
    val errorMessage: String? = null,
    val message: String? = null,
)

/** The `append` op's target: one frame of one step of one turn. */
@Serializable
data class AppendTargetDto(
    val type: String = "",
    val turnId: String = "",
    val stepId: String = "",
    val frameId: String = "",
)

/** A transcript marker (`marker.upsert`). [marker] `undo` carries a context splice in
 * [payload] (`{agentId,start,deleteCount,messages,tokens?}` — [UndoMarkerPayload]);
 * `compaction` and `swarm.*` markers are other tickets' business. */
@Serializable
data class MarkerItemDto(
    val markerId: String = "",
    val marker: String = "",
    val payload: JsonObject? = null,
    val at: String? = null,
)

/** The `undo` marker's splice. [messages] are full message objects in the history
 * MessageDto shape (top-level `origin`, ids on user messages; injections carry
 * `origin.kind:"injection"`). */
@Serializable
data class UndoMarkerPayload(
    val start: Int = 0,
    val deleteCount: Int = 0,
    val messages: List<MessageDto> = emptyList(),
)

/** An interaction entity (`interaction.upsert`, `transcript.reset.snapshot.interactions`,
 * the REST transcript's `interactions`). The cold start (ticket 176) maps the pending
 * ones of `GET .../transcript` to the approval/question cards; live, approvals and
 * questions ride the flat `interaction` frame (tickets 171/172). [request] stays raw:
 * its shape differs per [interactionKind] (camelCase approval, snake_case question). */
@Serializable
data class InteractionEntityDto(
    val interactionId: String = "",
    val interactionKind: String = "",
    val toolCallId: String? = null,
    val state: String = "",
    val request: JsonObject? = null,
    val response: JsonObject? = null,
)
