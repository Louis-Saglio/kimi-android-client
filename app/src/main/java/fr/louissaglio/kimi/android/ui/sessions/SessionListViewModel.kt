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
    )

    sealed interface Effect {
        data class RefreshFailed(val message: String) : Effect
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var loadJob: Job? = null

    fun onScreenStarted() = reload()

    fun onRetry() = reload()

    fun onPullToRefresh() = reload()

    private fun reload() {
        // Cancel rather than skip so a pull-to-refresh during an in-flight load still completes.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val hadSessions = _uiState.value.sessions != null
            _uiState.update { it.copy(isLoading = !hadSessions, isRefreshing = hadSessions, error = null) }
            sessionsRepository.listSessions(settingsRepository.current())
                .onSuccess { sessions ->
                    _uiState.update {
                        it.copy(isLoading = false, isRefreshing = false, sessions = sessions, error = null)
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
}
