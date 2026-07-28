package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// DTOs for GET /api/v1/sessions/{id}/messages and the /api/v1/ws frames
// (docs/server-api.md). Unknown fields are ignored (Json { ignoreUnknownKeys = true });
// only what the app consumes is declared.

@Serializable
data class MessageListData(
    val items: List<MessageDto> = emptyList(),
    // Parsed so the hardcoded page_size=100 cap in ChatApi.messages is detectable;
    // not acted on yet (pagination: todo/043).
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class MessageDto(
    // The steer echo splices the steered user message without an id (docs/server-api.md).
    val id: String = "",
    val role: String = "",
    val content: List<ContentPartDto> = emptyList(),
    // ISO-8601 UTC instant ("2026-07-23T03:27:03.317Z"); absent on splice echoes.
    @SerialName("created_at") val createdAt: String? = null,
)

/** Flat part shape: each `type` fills only its own fields, the rest stay null. */
@Serializable
data class ContentPartDto(
    val type: String = "",
    val text: String? = null,
    val thinking: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
    // Raw tool arguments on tool_use parts ({"command": "..."} for Bash); the
    // one-line row preview is derived from it (ToolPreview).
    val input: JsonObject? = null,
    // tool_result parts: the full output text (may be a JSON value for structured
    // tools like AskUserQuestion); rendered by the expanded tool row (ticket 023).
    val output: JsonElement? = null,
    @SerialName("is_error") val isError: Boolean? = null,
    // image parts (ticket 034): the inline source. History carries a data-url,
    // the prompt ACK echo raw base64, a file reference a file_id (docs/server-api.md).
    val source: MediaSourceDto? = null,
)

/** `source` of an image content part; each `kind` fills only its own fields. */
@Serializable
data class MediaSourceDto(
    val kind: String = "",
    @SerialName("media_type") val mediaType: String? = null,
    val data: String? = null,
    val url: String? = null,
    @SerialName("file_id") val fileId: String? = null,
)

@Serializable
data class SessionStatusData(
    val busy: Boolean = false,
    // Context window fill (ticket 041); absent on older servers.
    @SerialName("context_tokens") val contextTokens: Long? = null,
    @SerialName("max_context_tokens") val maxContextTokens: Long? = null,
)

/**
 * `agent.status.updated` payload (docs/server-api.md, volatile). Two variants share the
 * event type: the model/usage one carries [contextTokens]/[maxContextTokens], the phase
 * one carries `phase` — its null token fields must not clobber the stored values.
 */
@Serializable
data class AgentStatusUpdatedPayload(
    val contextTokens: Long? = null,
    val maxContextTokens: Long? = null,
)

/** `data` of `GET /api/v1/config`; only the server-wide default model is consumed. */
@Serializable
data class ConfigData(
    @SerialName("default_model") val defaultModel: String? = null,
)

/** `data` of `GET /api/v1/models` (docs/server-api.md). */
@Serializable
data class ModelListData(
    val items: List<ModelDto> = emptyList(),
)

@Serializable
data class ModelDto(
    val provider: String = "",
    val model: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("max_context_size") val maxContextSize: Long = 0,
    // Only on models with thinking-effort levels (K3 on 0.29.0); absent elsewhere.
    @SerialName("support_efforts") val supportEfforts: List<String>? = null,
    @SerialName("default_effort") val defaultEffort: String? = null,
)

/** `data` of the POST /prompts ack; on 0.27.0 `prompt_id` == `user_message_id`. */
@Serializable
data class PromptData(
    @SerialName("prompt_id") val promptId: String = "",
)

/** `data` of `POST /api/v1/files` (docs/server-api.md, Files). [mediaType] is sniffed
 * from the bytes server-side — the `name` field and extension are ignored there. */
@Serializable
data class FileUploadData(
    val id: String = "",
    val name: String = "",
    @SerialName("media_type") val mediaType: String = "",
    val size: Long = 0,
)

/** Envelope of one WebSocket frame; [payload] is decoded per [type] by the repository. */
@Serializable
data class WsFrame(
    val type: String = "",
    val seq: Long? = null,
    val epoch: String? = null,
    // Only on high-frequency deltas; absent otherwise (docs/server-api.md). Volatile
    // frames share the seq of the event they piggyback on, so they must not move the
    // resume cursor.
    val volatile: Boolean = false,
    val payload: JsonObject? = null,
)

@Serializable
data class PingPayload(val nonce: String = "")

/** Peek at the emitter tag every event payload carries (docs/server-api.md,
 * Sub-agents): `"main"` for the main agent, the sub-agent's id for its own frames.
 * Absent on older captures — reads as main. */
@Serializable
data class AgentIdPeek(val agentId: String = "main")

@Serializable
data class DeltaPayload(val delta: String = "")

@Serializable
data class ToolCallPayload(
    @SerialName("toolCallId") val toolCallId: String = "",
    val name: String = "",
    // tool.call.started only (docs/server-api.md): the full arguments, plus the
    // server's display rendering — both feed the live row preview.
    val args: JsonObject? = null,
    val display: ToolInputDisplayDto? = null,
)

@Serializable
data class ToolResultPayload(
    @SerialName("toolCallId") val toolCallId: String = "",
    // String for shell-style tools, a JSON value for structured ones (docs/server-api.md).
    val output: JsonElement? = null,
    @SerialName("is_error") val isError: Boolean = false,
)

/** `tool.progress` payload (docs/server-api.md, volatile live tool output):
 * `update` carries one output chunk (`kind` "stdout", possibly others later). */
@Serializable
data class ToolProgressPayload(
    @SerialName("toolCallId") val toolCallId: String = "",
    val update: ToolProgressUpdateDto? = null,
)

@Serializable
data class ToolProgressUpdateDto(
    val kind: String = "",
    val text: String = "",
)

@Serializable
data class TurnEndedPayload(val reason: String = "")

/** `context.spliced` payload: full message objects, decoded with the history MessageDto
 * (its extra fields — toolCalls, origin — are ignored). */
@Serializable
data class ContextSplicedPayload(
    val messages: List<MessageDto> = emptyList(),
)

@Serializable
data class ApprovalRequestedPayload(
    @SerialName("approval_id") val approvalId: String = "",
    @SerialName("tool_name") val toolName: String = "",
    val action: String = "",
    @SerialName("tool_input_display") val toolInputDisplay: ToolInputDisplayDto? = null,
)

/** `tool_input_display` of an approval request; only `command` (kind "command") is consumed. */
@Serializable
data class ToolInputDisplayDto(
    val kind: String = "",
    val command: String? = null,
)

@Serializable
data class ApprovalResolvedPayload(
    @SerialName("approval_id") val approvalId: String = "",
)

/**
 * `event.question.requested` payload (docs/server-api.md, Questions); the same
 * shape comes back as items of `GET .../questions?status=pending`. `question_id`
 * == `tool_call_id`; there is no v2 camelCase duplicate of this event family.
 */
@Serializable
data class QuestionRequestedPayload(
    @SerialName("question_id") val questionId: String = "",
    val questions: List<QuestionItemDto> = emptyList(),
)

@Serializable
data class QuestionItemDto(
    val id: String = "",
    val question: String = "",
    val header: String = "",
    val options: List<QuestionOptionDto> = emptyList(),
    @SerialName("allow_other") val allowOther: Boolean = false,
)

@Serializable
data class QuestionOptionDto(
    val id: String = "",
    val label: String = "",
    // Omitted when the option has none.
    val description: String? = null,
)

/** Shared shape of `event.question.answered` and `event.question.dismissed`: only the id is consumed. */
@Serializable
data class QuestionResolvedPayload(
    @SerialName("question_id") val questionId: String = "",
)

/** `data` of `GET .../questions?status=pending`. */
@Serializable
data class QuestionListData(
    val items: List<QuestionRequestedPayload> = emptyList(),
)

/** `subagent.spawned` payload (docs/server-api.md, Sub-agents): the only frame carrying
 * a sub-agent's identity metadata. `parentToolCallUuid`/`swarmIndex` exist upstream but
 * are not consumed. The frame itself is tagged `agentId:"main"`. */
@Serializable
data class SubagentSpawnedPayload(
    val subagentId: String = "",
    val subagentName: String = "",
    val parentToolCallId: String = "",
    val description: String = "",
    val runInBackground: Boolean = false,
)

/** Shared shape of `subagent.started`/`completed`/`failed`/`suspended`: every variant
 * carries [subagentId], plus only its own extra field (resultSummary, error, reason —
 * the last one not consumed). */
@Serializable
data class SubagentLifecyclePayload(
    val subagentId: String = "",
    val resultSummary: String? = null,
    val error: String? = null,
)
