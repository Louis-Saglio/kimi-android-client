package fr.louissaglio.kimi.android.data.sessions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DTOs for GET /api/v1/sessions (docs/server-api.md). Unknown fields are ignored
// (Json { ignoreUnknownKeys = true }); only what the app consumes is declared.

@Serializable
data class SessionListData(
    val items: List<SessionDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class SessionDto(
    val id: String,
    val title: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val busy: Boolean = false,
    /** Server runtime state: "none" | "approval" | "question" (docs/server-api.md). */
    @SerialName("pending_interaction") val pendingInteraction: String = "none",
    val metadata: SessionMetadataDto? = null,
)

@Serializable
data class SessionMetadataDto(
    val cwd: String? = null,
)

// DTOs for GET /api/v1/workspaces (docs/server-api.md); same conventions as above.

@Serializable
data class WorkspaceListData(
    val items: List<WorkspaceDto> = emptyList(),
)

@Serializable
data class WorkspaceDto(
    val id: String,
    val root: String,
    val name: String = "",
    @SerialName("last_opened_at") val lastOpenedAt: String = "",
)
