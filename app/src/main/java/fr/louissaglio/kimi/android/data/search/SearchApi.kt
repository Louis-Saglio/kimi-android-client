package fr.louissaglio.kimi.android.data.search

import fr.louissaglio.kimi.android.data.network.ApiClient
import fr.louissaglio.kimi.android.data.network.Envelope
import fr.louissaglio.kimi.android.data.network.toResult
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put

/**
 * The global message search route (`POST /api/v1/search`, docs/server-api.md, Search —
 * ticket 146): full-text search over user messages, assistant replies and session
 * titles across all sessions. The request sends only `query`, `page_size` and the
 * continuation token: `mode`/`op`/`sort` ride their server defaults (terms/AND/score)
 * and the role/time filters are out of scope. Paging is the 0.33.0 v2 keyset cursor:
 * [pageToken] is opaque and goes back verbatim; a rejected token answers HTTP 200
 * with envelope code 40001, which surfaces as the typed
 * [fr.louissaglio.kimi.android.data.DataError.Api] through the [ApiClient] scaffold —
 * the documented signal to restart the search from page one (the caller's job).
 * Hits of deleted sessions are filtered server-side since 0.43.1, so no client-side
 * filtering is needed.
 */
@Singleton
class SearchApi @Inject constructor(
    private val api: ApiClient,
) {
    /**
     * Runs one page of the search. [pageToken] = null fetches the first page;
     * the response carries the next token only while [SearchPage.hasMore].
     */
    suspend fun search(settings: ServerSettings, query: String, pageToken: String? = null): Result<SearchPage> {
        val body = api.jsonBody {
            put("query", query)
            put("page_size", PAGE_SIZE)
            if (pageToken != null) put("page_token", pageToken)
        }
        return api.post(settings, "/api/v1/search", body) { responseBody ->
            api.json.decodeFromString<Envelope<SearchData>>(responseBody).toResult().map { it.toDomain() }
        }
    }

    private fun SearchData.toDomain(): SearchPage = SearchPage(
        items = items.map { it.toDomain() },
        hasMore = hasMore,
        pageToken = pageToken,
        // stale: the page comes from an index generation known to be behind (a
        // rebuild is running); degraded: the last background refresh failed. Both
        // read as "results may be incomplete" — the screen's one slim banner.
        indexStale = indexState.stale || indexState.degraded != null,
    )

    private fun SearchHitDto.toDomain(): SearchHit = SearchHit(
        sessionId = sessionId,
        sessionTitle = sessionTitle.takeIf { it.isNotBlank() },
        role = when (role) {
            "user" -> SearchHit.Role.User
            "title" -> SearchHit.Role.Title
            // The wire vocabulary is closed; an unexpected value still renders as a
            // reply row rather than failing the whole page.
            else -> SearchHit.Role.Assistant
        },
        snippet = snippet,
        time = runCatching { Instant.ofEpochMilli(time) }.getOrDefault(Instant.EPOCH),
    )

    companion object {
        /** The screen's page size (server range 1–50). */
        const val PAGE_SIZE = 20
    }
}

/** One page of search results. [pageToken] is the opaque keyset cursor for the next
 * page, present only when [hasMore]; [indexStale] flags a page served from a lagging
 * index (`index_state.stale` or `degraded`) — results stay usable. */
data class SearchPage(
    val items: List<SearchHit>,
    val hasMore: Boolean,
    val pageToken: String?,
    val indexStale: Boolean,
)

/** One search hit: a user message, an assistant reply or a session title. The wire's
 * `workspace_id`/`agent_id`/`turn`/`step_id`/`score` have no UI consumer (jump-to-message
 * is out of scope, `score` is 0 in literal mode) and stay DTO-only. */
data class SearchHit(
    val sessionId: String,
    /** Blank server titles map to null; the row falls back to the session id. */
    val sessionTitle: String?,
    val role: Role,
    val snippet: String,
    val time: Instant,
) {
    enum class Role { User, Assistant, Title }
}

// DTOs for POST /api/v1/search (docs/server-api.md). Unknown fields are ignored
// (Json { ignoreUnknownKeys = true }); only what the app consumes is declared.

@Serializable
data class SearchData(
    val items: List<SearchHitDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("page_token") val pageToken: String? = null,
    @SerialName("index_state") val indexState: IndexStateDto = IndexStateDto(),
    val source: String = "",
)

@Serializable
data class SearchHitDto(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("session_title") val sessionTitle: String = "",
    /** "main", a sub-agent id, or "" on title hits. */
    @SerialName("agent_id") val agentId: String = "",
    val role: String = "",
    val snippet: String = "",
    val time: Long = 0,
    /** Absent on title hits. */
    val turn: Int? = null,
    @SerialName("step_id") val stepId: String? = null,
    val score: Double = 0.0,
)

@Serializable
data class IndexStateDto(
    val state: String = "",
    @SerialName("indexed_sessions") val indexedSessions: Int = 0,
    @SerialName("total_sessions") val totalSessions: Int = 0,
    val documents: Int = 0,
    val stale: Boolean = false,
    /** The last background refresh's error, when one failed. */
    val degraded: String? = null,
)
