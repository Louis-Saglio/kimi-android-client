package fr.louissaglio.kimi.android.data.sessions

import fr.louissaglio.kimi.android.data.network.ApiClient
import fr.louissaglio.kimi.android.data.network.Envelope
import fr.louissaglio.kimi.android.data.network.toResult
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Singleton
class SessionsRepository @Inject constructor(
    private val api: ApiClient,
    private val json: Json,
) {

    /**
     * Fetches one page of the session list from `GET /api/v1/sessions`. Settings are
     * passed per call (same pattern as ConnectionChecker) so saved-settings edits apply
     * immediately. [beforeId] is sent as the `before_id` cursor to page backwards;
     * null fetches the first page. [workspaceId] is sent as the `workspace_id` filter —
     * the grouped view's per-group expansion (ticket 116, web parity). [pageSize]
     * overrides the server default (20): the group expansion pages 5 at a time, like
     * the grouped fetch's first page. This is the
     * error boundary: failures come back as [Result.failure] of
     * [fr.louissaglio.kimi.android.data.DataError].
     */
    suspend fun listSessions(
        settings: ServerSettings,
        beforeId: String? = null,
        workspaceId: String? = null,
        pageSize: Int? = null,
    ): Result<SessionPage> {
        val params = listOfNotNull(
            workspaceId?.let { "workspace_id=$it" },
            pageSize?.let { "page_size=$it" },
            beforeId?.let { "before_id=$it" },
        )
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        return api.get(settings, "/api/v1/sessions$query") { body ->
            json.decodeFromString<Envelope<SessionListData>>(body).toResult().map { data ->
                SessionPage(
                    items = data.items.map { it.toDomain() },
                    hasMore = data.hasMore,
                )
            }
        }
    }

    /**
     * Fetches the workspace-grouped session list from
     * `GET /api/v2/sessions?view=by_workspace&group.page_size=5` (docs/server-api.md —
     * ticket 115): each workspace group carries its 5 most recent sessions and its full
     * count. One request only: the page size counts groups (default 50), which covers
     * Louis's workspaces — a true `has_more` leaves the remaining groups unshown (out
     * of scope). Same error-boundary contract as [listSessions].
     */
    suspend fun listSessionGroups(settings: ServerSettings): Result<List<SessionGroup>> =
        api.get(settings, "/api/v2/sessions?view=by_workspace&group.page_size=5") { body ->
            json.decodeFromString<Envelope<SessionGroupListData>>(body).toResult().map { data ->
                data.groups.map { it.toDomain() }
            }
        }

    /**
     * Fetches one session via `GET /api/v1/sessions/{id}` (docs/server-api.md) — same
     * payload shape as the list items. Used by the chat screen for its top-bar title.
     * Same error-boundary contract as [listSessions].
     */
    suspend fun getSession(settings: ServerSettings, sessionId: String): Result<Session> =
        api.get(settings, "/api/v1/sessions/$sessionId") { body ->
            json.decodeFromString<Envelope<SessionDto>>(body).toResult().map { it.toDomain() }
        }

    /**
     * Creates a session via `POST /api/v1/sessions` (docs/server-api.md). The server
     * derives a workspace from [cwd] and fails (non-zero envelope code) when the
     * directory does not exist. [title] is omitted when null so the server's default
     * ("New Session") applies. Same error-boundary contract as [listSessions].
     */
    suspend fun createSession(settings: ServerSettings, cwd: String, title: String?): Result<Session> {
        val body = api.jsonBody {
            putJsonObject("metadata") { put("cwd", cwd) }
            if (title != null) put("title", title)
        }
        return api.post(settings, "/api/v1/sessions", body) { responseBody ->
            json.decodeFromString<Envelope<SessionDto>>(responseBody).toResult().map { it.toDomain() }
        }
    }

    /**
     * Forks a session via `POST /api/v1/sessions/{id}:fork` (docs/server-api.md —
     * ticket 127): the fork inherits the workspace, the cwd and the full history, and
     * the server defaults the title to "Fork: <source title>", so the body is always
     * the empty object (web parity). A turn running on the source is refused with
     * envelope 40901. Same error-boundary contract as [listSessions].
     */
    suspend fun forkSession(settings: ServerSettings, sessionId: String): Result<Session> =
        api.post(settings, "/api/v1/sessions/$sessionId:fork", api.jsonBody {}) { responseBody ->
            json.decodeFromString<Envelope<SessionDto>>(responseBody).toResult().map { it.toDomain() }
        }

    /**
     * Archives a session via `POST /api/v1/sessions/{id}:archive` (docs/server-api.md —
     * ticket 131). Unlike fork there is no busy guard server-side: a running session
     * archives fine (verified live 2026-09-14 on 0.42.0). Archiving never bumps
     * `updated_at`, so the row keeps its recency position for the undo reinsert.
     * Same error-boundary contract as [listSessions].
     */
    suspend fun archiveSession(settings: ServerSettings, sessionId: String): Result<Boolean> =
        api.post(settings, "/api/v1/sessions/$sessionId:archive", api.jsonBody {}) { responseBody ->
            json.decodeFromString<Envelope<SessionArchiveData>>(responseBody).toResult().map { it.archived }
        }

    /**
     * Restores an archived session via `POST /api/v1/sessions/{id}:restore`
     * (docs/server-api.md — ticket 131): answers the full session envelope
     * (`archived:false`). Restore pushes no WS event, so the list-undo path
     * reinserts its remembered row rather than waiting for a frame. Same
     * error-boundary contract as [listSessions].
     */
    suspend fun restoreSession(settings: ServerSettings, sessionId: String): Result<Session> =
        api.post(settings, "/api/v1/sessions/$sessionId:restore", api.jsonBody {}) { responseBody ->
            json.decodeFromString<Envelope<SessionDto>>(responseBody).toResult().map { it.toDomain() }
        }

    /**
     * Fetches the known workspaces via `GET /api/v1/workspaces` (docs/server-api.md),
     * sorted by `last_opened_at` descending — the server order is not relied upon.
     * Same error-boundary contract as [listSessions].
     */
    suspend fun listWorkspaces(settings: ServerSettings): Result<List<Workspace>> =
        api.get(settings, "/api/v1/workspaces") { body ->
            json.decodeFromString<Envelope<WorkspaceListData>>(body).toResult().map { data ->
                data.items.map { it.toDomain() }.sortedByDescending(Workspace::lastOpenedAt)
            }
        }

    private fun SessionGroupDto.toDomain(): SessionGroup = SessionGroup(
        workspaceId = workspace.id,
        cwd = workspace.cwd,
        sessions = sessions.map { it.toDomain() },
        total = total,
    )

    private fun SessionV2Dto.toDomain(): Session = Session(
        id = id,
        title = meta.title?.takeIf { it.isNotBlank() },
        cwd = workspace.cwd,
        updatedAt = runCatching { Instant.ofEpochMilli(meta.updatedAt) }.getOrDefault(Instant.EPOCH),
        isRunning = activity.status == "running",
        // v2's activity.status carries the pending-interaction state; the badge rule
        // (ticket 087) is the same as v1's — approvals AND questions read as "needs me".
        isAwaitingInteraction = activity.status == "approval" || activity.status == "question",
    )

    private fun WorkspaceDto.toDomain(): Workspace = Workspace(
        id = id,
        root = root,
        name = name,
        lastOpenedAt = runCatching { Instant.parse(lastOpenedAt) }.getOrDefault(Instant.EPOCH),
    )
}
