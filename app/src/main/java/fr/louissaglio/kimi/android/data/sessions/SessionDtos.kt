package fr.louissaglio.kimi.android.data.sessions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DTOs for GET /api/v1/sessions (docs/server-api.md). Unknown fields are ignored
// (Json { ignoreUnknownKeys = true }); only what the app consumes is declared.

@Serializable
data class SessionListData(
    val items: List<SessionDto> = emptyList(),
)

@Serializable
data class SessionDto(
    val id: String,
    val title: String? = null,
    @SerialName("updated_at") val updatedAt: String = "",
    val status: String = "",
    val metadata: SessionMetadataDto? = null,
)

@Serializable
data class SessionMetadataDto(
    val cwd: String? = null,
)
