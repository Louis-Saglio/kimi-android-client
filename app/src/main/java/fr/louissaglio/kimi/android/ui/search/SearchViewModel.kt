package fr.louissaglio.kimi.android.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.search.SearchHit
import fr.louissaglio.kimi.android.data.search.SearchRepository
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.navigation.Search
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The message search screen's state holder (ticket 146; scoped variant ticket 147):
 * `POST /api/v1/search` over user messages, assistant replies and session titles —
 * across all sessions, or only [routeArgs]' session when it carries one
 * (`container.session_id`, docs/server-api.md, Search). One query at a time:
 * submitting cancels any in-flight page load and drops the old page token, so a
 * stale page can never land on a new
 * search and a token never crosses queries (the server would reject it — 40001 —
 * anyway). Paging appends while the server reports has_more, the opaque v2 keyset
 * token passed back verbatim; the keyset cursor pins the index generation, so pages
 * cannot overlap and no client-side dedup exists.
 */
@HiltViewModel
class SearchViewModel internal constructor(
    private val searchRepository: SearchRepository,
    private val settingsRepository: SettingsRepository,
    private val routeArgs: Search,
) : ViewModel() {

    // Production entry point (Hilt): the destination's SavedStateHandle is decoded
    // from the typed route — ChatViewModel's construction pattern. Unit tests call
    // the primary constructor with the route object directly (toRoute's decode path
    // needs a real android.os.Bundle).
    @Inject
    constructor(
        searchRepository: SearchRepository,
        settingsRepository: SettingsRepository,
        savedStateHandle: SavedStateHandle,
    ) : this(searchRepository, settingsRepository, savedStateHandle.toRoute<Search>())

    /** The session the search is scoped to (ticket 147); null = the global search. */
    private val scopeSessionId: String? = routeArgs.sessionId

    /** [submittedQuery] null = no search submitted yet (the screen shows its prompt).
     * [results] null = the submitted query's first page has not landed (loading, or
     * failed — [error] is then the full-screen state; [loadMoreError] only the
     * footer's). [indexStale] carries the latest page's
     * `index_state.stale`/`degraded` flag — the slim banner; results stay usable. */
    data class UiState(
        val query: String = "",
        val submittedQuery: String? = null,
        val results: List<SearchHit>? = null,
        val isLoading: Boolean = false,
        val error: String? = null,
        val hasMore: Boolean = false,
        val isLoadingMore: Boolean = false,
        val loadMoreError: String? = null,
        val indexStale: Boolean = false,
        /** The scoped session's name (ticket 147); null on the global search. */
        val scopeTitle: String? = null,
    )

    val uiState: StateFlow<UiState>
        field = MutableStateFlow(UiState(scopeTitle = routeArgs.sessionTitle))

    /** The current query's opaque continuation token — deliberately not in UiState:
     * it is not renderable and must go back verbatim. */
    private var pageToken: String? = null
    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null

    fun onQueryChange(query: String) {
        uiState.update { it.copy(query = query) }
    }

    /** IME-submit of the query field; a blank query is a no-op (the server would
     * answer a 40001 validation error anyway). */
    fun onSearch() {
        val query = uiState.value.query.trim()
        if (query.isEmpty()) return
        search(query)
    }

    /** Retry of the failed first page (the error state is the whole body). */
    fun onRetry() {
        val query = uiState.value.submittedQuery ?: return
        search(query)
    }

    /**
     * Loads the next page when the list scrolls near its end. Same guards as the
     * session lists (ticket 010): no-op without a submitted query, while any load is
     * in flight, or once the server reported no more pages; the job (not the state
     * flag) is the re-entrancy guard.
     */
    fun onEndReached() {
        val state = uiState.value
        val query = state.submittedQuery ?: return
        if (state.isLoading || !state.hasMore) return
        if (searchJob?.isActive == true || loadMoreJob?.isActive == true) return
        val token = pageToken ?: return
        loadMoreJob = viewModelScope.launch {
            uiState.update { it.copy(isLoadingMore = true, loadMoreError = null) }
            searchRepository.search(settingsRepository.current(), query, token, scopeSessionId)
                .onSuccess { page ->
                    pageToken = page.pageToken
                    uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            results = it.results.orEmpty() + page.items,
                            hasMore = page.hasMore,
                            indexStale = page.indexStale,
                        )
                    }
                }
                .onFailure { error ->
                    if ((error as? DataError.Api)?.code == PAGE_TOKEN_INVALID_CODE) {
                        restartFromFirstPage(query)
                    } else {
                        // The loaded results stay visible; the footer offers a retry.
                        uiState.update { it.copy(isLoadingMore = false, loadMoreError = error.toUserMessage()) }
                    }
                }
        }
    }

    private fun search(query: String) {
        // Cancel rather than skip so a resubmit during an in-flight load still
        // completes; a new query starts from page one with no token.
        searchJob?.cancel()
        loadMoreJob?.cancel()
        pageToken = null
        searchJob = viewModelScope.launch {
            uiState.update {
                it.copy(
                    submittedQuery = query,
                    results = null,
                    isLoading = true,
                    error = null,
                    hasMore = false,
                    isLoadingMore = false,
                    loadMoreError = null,
                    indexStale = false,
                )
            }
            searchRepository.search(settingsRepository.current(), query, sessionId = scopeSessionId)
                .onSuccess { page ->
                    pageToken = page.pageToken
                    uiState.update {
                        it.copy(
                            isLoading = false,
                            results = page.items,
                            hasMore = page.hasMore,
                            indexStale = page.indexStale,
                        )
                    }
                }
                .onFailure { error ->
                    uiState.update { it.copy(isLoading = false, error = error.toUserMessage()) }
                }
        }
    }

    /**
     * docs/server-api.md, Search: a rejected page token (envelope 40001 — malformed,
     * an older index generation, or changed query conditions) is the documented
     * signal to restart the search from page one. The loaded results stay on screen
     * while the fresh first page loads, then are REPLACED by it.
     */
    private fun restartFromFirstPage(query: String) {
        pageToken = null
        loadMoreJob = viewModelScope.launch {
            searchRepository.search(settingsRepository.current(), query, sessionId = scopeSessionId)
                .onSuccess { page ->
                    pageToken = page.pageToken
                    uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            results = page.items,
                            hasMore = page.hasMore,
                            indexStale = page.indexStale,
                        )
                    }
                }
                .onFailure { error ->
                    uiState.update { it.copy(isLoadingMore = false, loadMoreError = error.toUserMessage()) }
                }
        }
    }

    private companion object {
        /** Envelope code of a rejected page token (docs/server-api.md, Search). */
        const val PAGE_TOKEN_INVALID_CODE = 40001
    }
}
