package fr.louissaglio.kimi.android.data.search

import fr.louissaglio.kimi.android.data.DataError
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
import kotlinx.serialization.json.putJsonObject

/**
 * The global message search route (`POST /api/v1/search`, docs/server-api.md, Search —
 * ticket 146): full-text search over user messages, assistant replies and session
 * titles across all sessions; ticket 147's [sessionId] scopes it to one session
 * (`container.session_id` — the only container filter, `container.agent_id` being
 * ignored server-side and never sent; the answer's `source` is then "live"). The
 * request sends only `query`, `page_size` and the continuation token: `mode`/`op`/`sort`
 * ride their server defaults (terms/AND/score) and the role/time filters are out of
 * scope. Paging is the 0.33.0 v2 keyset cursor:
 * [pageToken] is opaque and goes back verbatim; a rejected token answers HTTP 200
 * with envelope code 40001, which surfaces as the typed
 * [DataError.Api] through the [ApiClient] scaffold —
 * the documented signal to restart the search from page one (the caller's job).
 * Hits of deleted sessions are filtered server-side since 0.43.1, so no client-side
 * filtering is needed. A server with its global index off answers every call with
 * one specific 50001 ([isGlobalSearchDisabled]), mapped here to
 * [DataError.SearchDisabled] (ticket 183).
 */
@Singleton
class SearchApi @Inject constructor(
    private val api: ApiClient,
) {
    /**
     * Runs one page of the search. [pageToken] = null fetches the first page;
     * the response carries the next token only while [SearchPage.hasMore].
     * [sessionId] scopes the search to that one session (ticket 147).
     */
    suspend fun search(
        settings: ServerSettings,
        query: String,
        pageToken: String? = null,
        sessionId: String? = null,
    ): Result<SearchPage> {
        val body = api.jsonBody {
            put("query", query)
            put("page_size", PAGE_SIZE)
            if (pageToken != null) put("page_token", pageToken)
            if (sessionId != null) {
                putJsonObject("container") { put("session_id", sessionId) }
            }
        }
        val result = api.post(settings, "/api/v1/search", body) { responseBody ->
            api.json.decodeFromString<Envelope<SearchData>>(responseBody).toResult().map { it.toDomain() }
        }
        return if (result.exceptionOrNull()?.isGlobalSearchDisabled() == true) {
            Result.failure(DataError.SearchDisabled)
        } else {
            result
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

/**
 * docs/server-api.md, Search (2.5.0): with the global search index off — the
 * server default — every `POST /api/v1/search` answers envelope 50001 with this
 * message prefix, before any validation. Deliberately narrow (code AND exact
 * prefix): every other 50001 stays a plain [DataError.Api].
 */
internal fun Throwable.isGlobalSearchDisabled(): Boolean =
    this is DataError.Api &&
        code == GLOBAL_SEARCH_DISABLED_CODE &&
        serverMessage.startsWith(GLOBAL_SEARCH_DISABLED_PREFIX)

private const val GLOBAL_SEARCH_DISABLED_CODE = 50001
private const val GLOBAL_SEARCH_DISABLED_PREFIX = "global search is disabled"
