package fr.louissaglio.kimi.android.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.sessions.Session
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val sessionsRepository: SessionsRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** [sessions] is null until the first successful load; [error] is shown only when there is no list to display. */
    data class UiState(
        val isLoading: Boolean = false,
        val isRefreshing: Boolean = false,
        val sessions: List<Session>? = null,
        val error: String? = null,
        val hasMore: Boolean = false,
        val isLoadingMore: Boolean = false,
        /** Set when a next-page load failed; shown inline at the bottom of the list. */
        val loadMoreError: String? = null,
        val showCreateDialog: Boolean = false,
        val newSessionCwd: String = "",
        val newSessionTitle: String = "",
        val newSessionCwdError: String? = null,
        val isCreating: Boolean = false,
    )

    sealed interface Effect {
        data class RefreshFailed(val message: String) : Effect
        data class CreateFailed(val message: String) : Effect
        data class SessionCreated(val sessionId: String) : Effect
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null

    fun onScreenStarted() = reload()

    fun onRetry() = reload()

    fun onPullToRefresh() = reload()

    /**
     * Loads the next page when the list scrolls near its end. No-op while any load is
     * in flight or once the server reported no more pages. The job check (not the
     * state flag) is the re-entrancy guard: the UI calls this on every recomposition
     * near the end, and state updates are not visible synchronously across dispatches.
     */
    fun onEndReached() {
        val state = _uiState.value
        if (state.isLoading || state.isRefreshing || !state.hasMore) return
        if (loadMoreJob?.isActive == true) return
        val beforeId = state.sessions?.lastOrNull()?.id ?: return
        loadMoreJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, loadMoreError = null) }
            sessionsRepository.listSessions(settingsRepository.current(), beforeId = beforeId)
                .onSuccess { page ->
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            sessions = (it.sessions.orEmpty() + page.items).distinctBy(Session::id),
                            hasMore = page.hasMore,
                        )
                    }
                }
                .onFailure { error ->
                    // The loaded list stays visible; the footer offers a retry.
                    _uiState.update { it.copy(isLoadingMore = false, loadMoreError = error.toUserMessage()) }
                }
        }
    }

    fun onNewSessionClick() {
        _uiState.update {
            it.copy(
                showCreateDialog = true,
                newSessionCwd = DEFAULT_NEW_SESSION_CWD,
                newSessionTitle = "",
                newSessionCwdError = null,
            )
        }
    }

    fun onCreateDialogDismiss() = _uiState.update { it.copy(showCreateDialog = false) }

    fun onNewSessionCwdChange(value: String) =
        _uiState.update { it.copy(newSessionCwd = value, newSessionCwdError = null) }

    fun onNewSessionTitleChange(value: String) =
        _uiState.update { it.copy(newSessionTitle = value) }

    fun onCreateSessionConfirm() {
        val cwd = _uiState.value.newSessionCwd.trim()
        val validationError = when {
            cwd.isEmpty() -> "Enter a working directory"
            !cwd.startsWith("/") -> "Path must be absolute"
            else -> null
        }
        if (validationError != null) {
            _uiState.update { it.copy(newSessionCwdError = validationError) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true) }
            sessionsRepository.createSession(
                settings = settingsRepository.current(),
                cwd = cwd,
                title = _uiState.value.newSessionTitle.trim().ifBlank { null },
            ).onSuccess { session ->
                _uiState.update { it.copy(isCreating = false, showCreateDialog = false) }
                _effects.send(Effect.SessionCreated(session.id))
            }.onFailure { error ->
                // Inputs are kept so the user can fix and retry after the snackbar.
                _uiState.update { it.copy(isCreating = false) }
                _effects.send(Effect.CreateFailed(error.toUserMessage()))
            }
        }
    }

    private fun reload() {
        // Cancel rather than skip so a pull-to-refresh during an in-flight load still completes.
        loadJob?.cancel()
        loadMoreJob?.cancel()
        loadJob = viewModelScope.launch {
            val hadSessions = _uiState.value.sessions != null
            _uiState.update {
                it.copy(
                    isLoading = !hadSessions,
                    isRefreshing = hadSessions,
                    error = null,
                    isLoadingMore = false,
                    loadMoreError = null,
                )
            }
            sessionsRepository.listSessions(settingsRepository.current())
                .onSuccess { page ->
                    _uiState.update {
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
                    if (hadSessions) {
                        _uiState.update { it.copy(isLoading = false, isRefreshing = false) }
                        _effects.send(Effect.RefreshFailed(message))
                    } else {
                        _uiState.update { it.copy(isLoading = false, isRefreshing = false, error = message) }
                    }
                }
        }
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is DataError.InvalidUrl -> "Invalid server URL — check Settings"
        is DataError.Auth -> "Authentication failed — check Settings"
        is DataError.Network -> "Server unreachable"
        is DataError.Http -> "Unexpected server response (HTTP $code)"
        is DataError.Api -> serverMessage.ifBlank { "Server error $code" }
        is DataError.MalformedResponse -> "Malformed server response"
        else -> message ?: "Unknown error"
    }

    private companion object {
        const val DEFAULT_NEW_SESSION_CWD = "/home/ubuntu"
    }
}
