package fr.louissaglio.kimi.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class WebSocketEnvelope(
    val type: String,
    val seq: Long? = null,
    val epoch: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val timestamp: String? = null,
    val payload: JsonObject? = null
)

sealed class ServerEvent {
    abstract val sessionId: String?
    abstract val seq: Long?
}

data class MessageCreatedEvent(
    override val sessionId: String?,
    override val seq: Long?,
    val message: Message
) : ServerEvent()

data class MessageUpdatedEvent(
    override val sessionId: String?,
    override val seq: Long?,
    @SerialName("message_id") val messageId: String,
    val content: List<ContentBlock>,
    val status: String? = null
) : ServerEvent()

data class AssistantDeltaEvent(
    override val sessionId: String?,
    override val seq: Long?,
    @SerialName("message_id") val messageId: String,
    @SerialName("content_index") val contentIndex: Int,
    val delta: String
) : ServerEvent()

data class SessionStatusChangedEvent(
    override val sessionId: String?,
    override val seq: Long?,
    val status: String,
    @SerialName("previous_status") val previousStatus: String? = null,
    @SerialName("current_prompt_id") val currentPromptId: String? = null
) : ServerEvent()

data class ToolOutputEvent(
    override val sessionId: String?,
    override val seq: Long?,
    @SerialName("tool_call_id") val toolCallId: String,
    @SerialName("output_chunk") val outputChunk: String,
    val stream: String? = null
) : ServerEvent()

data class ApprovalRequestedEvent(
    override val sessionId: String?,
    override val seq: Long?,
    val approval: Approval
) : ServerEvent()

data class ApprovalResolvedEvent(
    override val sessionId: String?,
    override val seq: Long?,
    @SerialName("approval_id") val approvalId: String,
    val decision: String,
    @SerialName("resolved_at") val resolvedAt: String? = null
) : ServerEvent()

data class QuestionRequestedEvent(
    override val sessionId: String?,
    override val seq: Long?,
    val question: Question
) : ServerEvent()

data class QuestionAnsweredEvent(
    override val sessionId: String?,
    override val seq: Long?,
    @SerialName("question_id") val questionId: String,
    @SerialName("resolved_at") val resolvedAt: String? = null
) : ServerEvent()

data class UnknownEvent(
    override val sessionId: String?,
    override val seq: Long?,
    val rawType: String,
    val payload: JsonObject? = null
) : ServerEvent()

@Serializable
data class Approval(
    @SerialName("approval_id") val approvalId: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("turn_id") val turnId: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
    val action: String? = null,
    val display: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class Question(
    val id: String,
    val question: String,
    val header: String? = null,
    val body: String? = null,
    val options: List<QuestionOption> = emptyList(),
    @SerialName("allow_other") val allowOther: Boolean = false
)

@Serializable
data class QuestionOption(
    val id: String,
    val label: String,
    val description: String? = null
)
