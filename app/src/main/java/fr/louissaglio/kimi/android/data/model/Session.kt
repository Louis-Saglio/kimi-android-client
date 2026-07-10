package fr.louissaglio.kimi.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Session(
    val id: String,
    val title: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val status: String,
    val archived: Boolean = false,
    @SerialName("current_prompt_id") val currentPromptId: String? = null,
    @SerialName("last_prompt") val lastPrompt: String? = null,
    val metadata: SessionMetadata = SessionMetadata(),
    @SerialName("agent_config") val agentConfig: AgentConfig = AgentConfig(),
    val usage: SessionUsage = SessionUsage(),
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("last_seq") val lastSeq: Long = 0,
    @SerialName("workspace_id") val workspaceId: String? = null
)

@Serializable
data class SessionMetadata(
    val cwd: String? = null,
    @SerialName("parent_session_id") val parentSessionId: String? = null
)

@Serializable
data class AgentConfig(
    val model: String = ""
)

@Serializable
data class SessionUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Int = 0,
    @SerialName("cache_creation_tokens") val cacheCreationTokens: Int = 0,
    @SerialName("total_cost_usd") val totalCostUsd: Double = 0.0,
    @SerialName("context_tokens") val contextTokens: Int = 0,
    @SerialName("context_limit") val contextLimit: Int = 0,
    @SerialName("turn_count") val turnCount: Int = 0
)

@Serializable
data class SessionListResponse(
    val items: List<Session> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false
)
