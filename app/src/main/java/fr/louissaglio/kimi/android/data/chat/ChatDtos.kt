package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    val id: String,
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
    @SerialName("is_error") val isError: Boolean? = null,
)

@Serializable
data class SessionStatusData(
    val status: String = "",
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
)

@Serializable
data class ToolResultPayload(
    @SerialName("toolCallId") val toolCallId: String = "",
    @SerialName("is_error") val isError: Boolean = false,
)

@Serializable
data class TurnEndedPayload(val reason: String = "")
