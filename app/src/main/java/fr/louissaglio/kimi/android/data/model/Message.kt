package fr.louissaglio.kimi.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Message(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    val role: String,
    val content: List<ContentBlock> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("prompt_id") val promptId: String? = null,
    @SerialName("parent_message_id") val parentMessageId: String? = null,
    val metadata: JsonObject? = null,
    val status: String? = null
)

@Serializable
data class MessageListResponse(
    val items: List<Message> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false
)
