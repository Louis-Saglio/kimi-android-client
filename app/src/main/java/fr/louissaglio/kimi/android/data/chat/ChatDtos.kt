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
)

@Serializable
data class MessageDto(
    // The steer echo splices the steered user message without an id (docs/server-api.md).
    val id: String = "",
    val role: String = "",
    val content: List<ContentPartDto> = emptyList(),
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
)

@Serializable
data class SessionStatusData(
    val busy: Boolean = false,
)

/** `data` of `GET /api/v1/config`; only the server-wide default model is consumed. */
@Serializable
data class ConfigData(
    @SerialName("default_model") val defaultModel: String? = null,
)

/** `data` of the POST /prompts ack; on 0.27.0 `prompt_id` == `user_message_id`. */
@Serializable
data class PromptData(
    @SerialName("prompt_id") val promptId: String = "",
)

/** Envelope of one WebSocket frame; [payload] is decoded per [type] by the repository. */
@Serializable
data class WsFrame(
    val type: String = "",
    val seq: Long? = null,
    val epoch: String? = null,
    val payload: JsonObject? = null,
)

@Serializable
data class PingPayload(val nonce: String = "")

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
