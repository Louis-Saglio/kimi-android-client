package fr.louissaglio.kimi.android.data.sessions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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

// DTOs for GET /api/v2/sessions?view=by_workspace (docs/server-api.md — ticket 115).
// v2 differs from v1 wire-wise: epoch-ms timestamps and present-but-null unset fields,
// so these are deliberately not shared with the v1 DTOs above.

@Serializable
data class SessionGroupListData(
    val groups: List<SessionGroupDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_page_token") val nextPageToken: String? = null,
    val total: Int = 0,
)

@Serializable
data class SessionGroupDto(
    val workspace: SessionV2WorkspaceDto,
    val sessions: List<SessionV2Dto> = emptyList(),
    /** The workspace's full session count server-side — [sessions] holds only the first page. */
    val total: Int = 0,
)

@Serializable
data class SessionV2Dto(
    val id: String,
    val workspace: SessionV2WorkspaceDto,
    val meta: SessionV2MetaDto = SessionV2MetaDto(),
    val activity: SessionV2ActivityDto = SessionV2ActivityDto(),
)

@Serializable
data class SessionV2WorkspaceDto(
    val id: String,
    val cwd: String,
)

@Serializable
data class SessionV2MetaDto(
    val title: String? = null,
    /** Epoch milliseconds, unlike v1's ISO strings. */
    @SerialName("updated_at") val updatedAt: Long = 0,
)

@Serializable
data class SessionV2ActivityDto(
    /** "idle" | "running" | "approval" | "question" (docs/server-api.md). */
    val status: String = "idle",
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

// DTOs for the sessions-list WebSocket (docs/server-api.md — ticket 087). Only the
// consumed event types plus the foreign-session filter key are declared; everything
// else on the envelope is ignored.

@Serializable
data class SessionListWsFrame(
    val type: String = "",
    // The emitting session, on event frames only. 0.31.0 also broadcasts events for
    // sessions this connection never subscribed to, so the socket drops any frame
    // whose sessionId is not in the subscribed set.
    @SerialName("session_id") val sessionId: String? = null,
    val payload: JsonObject? = null,
)

@Serializable
data class WorkChangedPayload(
    val busy: Boolean = false,
    /** Server runtime state: "none" | "approval" | "question" (docs/server-api.md). */
    @SerialName("pending_interaction") val pendingInteraction: String = "none",
)

/** `session.meta.updated`: the title rides at the payload top level (the `patch`
 * object repeats it alongside `isCustomTitle`/`lastPrompt`, which the row ignores). */
@Serializable
data class SessionMetaUpdatedPayload(
    val title: String? = null,
)

/** `event.session.archived` (0.38.0): a global frame — the envelope `session_id` is
 * the `__global__` watermark, so the archived id is read from the payload's camelCase
 * `sessionId`. No default: a payload without it fails to decode and is dropped. */
@Serializable
data class SessionArchivedPayload(
    val sessionId: String,
)
