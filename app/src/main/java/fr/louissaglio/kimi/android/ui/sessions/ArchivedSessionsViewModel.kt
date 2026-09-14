package fr.louissaglio.kimi.android.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.sessions.ArchivedSession
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.data.toUserMessage
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The archived sessions screen's state holder (ticket 132). Transient by design: no
 * socket, no disk cache — the screen loads on entry (one fresh ViewModel per
 * navigation, so init IS the per-entry load) and heals via pull-to-refresh only;
 * restore pushes no WS event, so nothing live-updates this list.
 */
@HiltViewModel
class ArchivedSessionsViewModel @Inject constructor(
    private val sessionsRepository: SessionsRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** [sessions] is null until the first successful load; [error] is shown only when
     * there is no list to display. Same contract as the main list's UiState. */
    data class UiState(
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val sessions: List<ArchivedSession>? = null,
        val error: String? = null,
        val hasMore: Boolean = false,
        val isLoadingMore: Boolean = false,
        /** Set when a next-page load failed; shown inline at the bottom of the list. */
        val loadMoreError: String? = null,
    )

    sealed interface Effect {
        /** A refresh with content on screen failed: the list stays, the message shows in a snackbar. */
        data class RefreshFailed(val message: String) : Effect

        /** Restore succeeded: the row is already removed; the collector shows the plain
         * snackbar and flags the main list for its return-heal refresh (ticket 132). */
        data object SessionRestored : Effect

        /** The restore POST failed: the row stays, the server's message shows. */
        data class RestoreFailed(val message: String) : Effect
    }

    val uiState: StateFlow<UiState>
        field = MutableStateFlow(UiState())

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null

    init {
        reload()
    }

    fun onRetry() = reload()

    fun onPullToRefresh() = reload()

    /**
     * Loads the next page when the list scrolls near its end. Same guards as the main
     * list (ticket 010): no-op while any load is in flight or once the server reported
     * no more pages; the job (not the state flag) is the re-entrancy guard.
     */
    fun onEndReached() {
        val state = uiState.value
        if (state.isLoading || state.isRefreshing || !state.hasMore) return
        if (loadJob?.isActive == true || loadMoreJob?.isActive == true) return
        val beforeId = state.sessions?.lastOrNull()?.id ?: return
        loadMoreJob = viewModelScope.launch {
            uiState.update { it.copy(isLoadingMore = true, loadMoreError = null) }
            sessionsRepository.listArchivedSessions(settingsRepository.current(), beforeId = beforeId)
                .onSuccess { page ->
                    uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            sessions = (it.sessions.orEmpty() + page.items).distinctBy(ArchivedSession::id),
                            hasMore = page.hasMore,
                        )
                    }
                }
                .onFailure { error ->
                    // The loaded list stays visible; the footer offers a retry.
                    uiState.update { it.copy(isLoadingMore = false, loadMoreError = error.toUserMessage()) }
                }
        }
    }

    /**
     * Restores [sessionId] (ticket 132). On success the row drops out and the plain
     * [Effect.SessionRestored] snackbar is emitted — no undo: re-archiving is one
     * gesture away in the main list. On failure the row stays and the server's message
     * shows. Restore pushes no WS event, so the removal here and the nav-result flag
     * (the collector's job) are the only ways the two lists learn about it.
     */
    fun onRestoreSession(sessionId: String) {
        viewModelScope.launch {
            sessionsRepository.restoreSession(settingsRepository.current(), sessionId)
                .onSuccess {
                    // Same guard as the main list's create (ticket 086): cancel
                    // in-flight loads so a stale page landing after the removal
                    // cannot resurrect the row.
                    loadJob?.cancel()
                    loadMoreJob?.cancel()
                    uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            loadMoreError = null,
                            sessions = state.sessions?.filterNot { it.id == sessionId },
                        )
                    }
                    _effects.send(Effect.SessionRestored)
                }
                .onFailure { error ->
                    _effects.send(Effect.RestoreFailed(error.toUserMessage()))
                }
        }
    }

    private fun reload() {
        // Cancel rather than skip so a pull-to-refresh during an in-flight load still completes.
        loadJob?.cancel()
        loadMoreJob?.cancel()
        loadJob = viewModelScope.launch {
            val hadContent = uiState.value.sessions != null
            uiState.update {
                it.copy(
                    isLoading = !hadContent,
                    isRefreshing = hadContent,
                    error = null,
                    isLoadingMore = false,
                    loadMoreError = null,
                )
            }
            sessionsRepository.listArchivedSessions(settingsRepository.current())
                .onSuccess { page ->
                    uiState.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            sessions = page.items,
                            hasMore = page.hasMore,
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    val message = error.toUserMessage()
                    if (hadContent) {
                        // Same split as the main list: with rows on screen a failed
                        // refresh is a snackbar, not the full-screen error.
                        uiState.update { it.copy(isLoading = false, isRefreshing = false) }
                        _effects.send(Effect.RefreshFailed(message))
                    } else {
                        uiState.update { it.copy(isLoading = false, isRefreshing = false, error = message) }
                    }
                }
        }
    }
}
