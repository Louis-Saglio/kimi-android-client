package fr.louissaglio.kimi.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.SocketEvent
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])

    /** [items] is empty until the first load; [error] is shown only when there is nothing to display. */
    data class UiState(
        val isLoading: Boolean = true,
        val items: List<ChatItem> = emptyList(),
        val error: String? = null,
        val isWorking: Boolean = false,
        val isReconnecting: Boolean = false,
    )

    sealed interface Effect {
        data class RefreshFailed(val message: String) : Effect
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val timeline = ChatTimeline()
    private var socketJob: Job? = null
    private var historyJob: Job? = null

    /** Wired to ON_START: (re)loads history and opens the socket. Also fires when the
     * app returns to the foreground, which is the re-sync path after an OS-level drop. */
    fun onScreenStarted() {
        refreshHistory(clearLive = false)
        seedWorkingState()
        startSocket()
    }

    /** Wired to ON_STOP: the socket is only held while the screen is visible. */
    fun onScreenStopped() {
        socketJob?.cancel()
    }

    fun onRetry() {
        _uiState.update { it.copy(isLoading = it.items.isEmpty(), error = null) }
        onScreenStarted()
    }

    private fun startSocket() {
        socketJob?.cancel()
        socketJob = viewModelScope.launch {
            try {
                chatRepository.observeSession(settingsRepository.current(), sessionId)
                    .collect { event ->
                        when (event) {
                            SocketEvent.Connected ->
                                _uiState.update { it.copy(isReconnecting = false) }
                            SocketEvent.Reconnected -> {
                                _uiState.update { it.copy(isReconnecting = false) }
                                // The cursor resume replays missed events; the refetch
                                // heals anything beyond the server's replay buffer.
                                refreshHistory(clearLive = false)
                            }
                            SocketEvent.Disconnected ->
                                _uiState.update { it.copy(isReconnecting = true) }
                            is SocketEvent.Event -> applySessionEvent(event.event)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DataError.InvalidUrl) {
                // Surfaced by the history load, which validates the URL first.
            }
        }
    }

    private fun applySessionEvent(event: SessionEvent) {
        when (event) {
            SessionEvent.TurnStarted -> {
                timeline.apply(event)
                _uiState.update { it.copy(isWorking = true, items = timeline.items()) }
            }
            is SessionEvent.TurnEnded -> {
                _uiState.update { it.copy(isWorking = false) }
                // Replace live rows with the canonical committed turn.
                refreshHistory(clearLive = true)
            }
            SessionEvent.PromptSubmitted ->
                // Pick up the new user message (the event carries no text).
                refreshHistory(clearLive = false)
            else -> {
                timeline.apply(event)
                _uiState.update { it.copy(items = timeline.items()) }
            }
        }
    }

    private fun refreshHistory(clearLive: Boolean) {
        // Latest fetch wins: a stale response must not overwrite a newer one.
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            chatRepository.getMessages(settingsRepository.current(), sessionId)
                .onSuccess { messages ->
                    timeline.replaceHistory(messages, keepLive = !clearLive)
                    _uiState.update { it.copy(isLoading = false, items = timeline.items(), error = null) }
                }
                .onFailure { error ->
                    if (_uiState.value.items.isEmpty()) {
                        _uiState.update { it.copy(isLoading = false, error = error.toUserMessage()) }
                    } else {
                        _effects.send(Effect.RefreshFailed(error.toUserMessage()))
                    }
                }
        }
    }

    /** Seeds the working indicator when opening a session that is already mid-turn
     * (turn events only fire on transitions). Failure just leaves the indicator off. */
    private fun seedWorkingState() {
        viewModelScope.launch {
            chatRepository.isSessionRunning(settingsRepository.current(), sessionId)
                .onSuccess { running -> _uiState.update { it.copy(isWorking = running) } }
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
