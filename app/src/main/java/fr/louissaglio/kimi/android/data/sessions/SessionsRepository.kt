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
import okhttp3.OkHttpClient

@Singleton
class SessionsRepository @Inject constructor(
    client: OkHttpClient,
    private val json: Json,
) {
    private val api = ApiClient(client, json)

    /**
     * Fetches one page of the session list from `GET /api/v1/sessions`. Settings are
     * passed per call (same pattern as ConnectionChecker) so saved-settings edits apply
     * immediately. [beforeId] is sent as the `before_id` cursor to page backwards;
     * null fetches the first page. This is the error boundary: failures come back as
     * [Result.failure] of [fr.louissaglio.kimi.android.data.DataError].
     */
    suspend fun listSessions(settings: ServerSettings, beforeId: String? = null): Result<SessionPage> =
        api.get(settings, "/api/v1/sessions" + (beforeId?.let { "?before_id=$it" } ?: "")) { body ->
            json.decodeFromString<Envelope<SessionListData>>(body).toResult().map { data ->
                SessionPage(
                    items = data.items.map { it.toDomain() },
                    hasMore = data.hasMore,
                )
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

    private fun SessionDto.toDomain(): Session = Session(
        id = id,
        title = title?.takeIf { it.isNotBlank() },
        cwd = metadata?.cwd,
        updatedAt = runCatching { Instant.parse(updatedAt) }.getOrDefault(Instant.EPOCH),
        isRunning = busy,
        isAwaitingApproval = pendingInteraction == "approval",
    )

    private fun WorkspaceDto.toDomain(): Workspace = Workspace(
        id = id,
        root = root,
        name = name,
        lastOpenedAt = runCatching { Instant.parse(lastOpenedAt) }.getOrDefault(Instant.EPOCH),
    )
}
