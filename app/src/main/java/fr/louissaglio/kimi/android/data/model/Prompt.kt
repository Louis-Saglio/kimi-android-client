package fr.louissaglio.kimi.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PromptRequest(
    val content: List<ContentBlock>,
    val metadata: JsonObject? = null,
    @SerialName("agent_id") val agentId: String? = null,
    val model: String? = null,
    val thinking: String? = null,
    @SerialName("permission_mode") val permissionMode: String? = null,
    @SerialName("plan_mode") val planMode: Boolean? = null
)

@Serializable
data class PromptResponse(
    @SerialName("prompt_id") val promptId: String,
    @SerialName("user_message_id") val userMessageId: String,
    val status: String,
    val content: List<ContentBlock> = emptyList(),
    @SerialName("created_at") val createdAt: String
)
