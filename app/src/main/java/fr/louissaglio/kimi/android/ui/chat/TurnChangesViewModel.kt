package fr.louissaglio.kimi.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.FileChange
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.navigation.TurnChanges
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One turn's file-changes list (ticket 149): a plain one-shot fetch of
 * `GET .../file-history/changes?turn_id=N` — the data is committed history, so
 * there is no poll and no socket interest (unlike the job screen). A turn with
 * nothing recorded (`recorded: false`, changes empty) renders the empty state.
 */
@HiltViewModel
class TurnChangesViewModel internal constructor(
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    routeArgs: TurnChanges,
) : ViewModel() {

    // Production entry point (Hilt): the destination's SavedStateHandle is decoded
    // from the typed route (ticket 083). Local unit tests call the primary
    // constructor with the route object directly (the JobDetailViewModel pattern).
    @Inject
    constructor(
        chatRepository: ChatRepository,
        settingsRepository: SettingsRepository,
        savedStateHandle: SavedStateHandle,
    ) : this(chatRepository, settingsRepository, savedStateHandle.toRoute<TurnChanges>())

    private val sessionId: String = routeArgs.sessionId
    val turnId: Int = routeArgs.turnId

    /** [changes] null = nothing fetched yet (loading, or failed). */
    data class UiState(
        val turnId: Int,
        val changes: List<FileChange>? = null,
        val error: String? = null,
    )

    val uiState: StateFlow<UiState>
        field = MutableStateFlow(UiState(turnId))

    init {
        fetch()
    }

    private fun fetch() {
        viewModelScope.launch {
            chatRepository.getFileChanges(settingsRepository.current(), sessionId, turnId)
                .onSuccess { page -> uiState.update { it.copy(changes = page.changes, error = null) } }
                .onFailure { error -> uiState.update { it.copy(error = error.toUserMessage()) } }
        }
    }

    /** Retry of the failed fetch (the error state is the whole screen). */
    fun onRetry() {
        uiState.update { it.copy(changes = null, error = null) }
        fetch()
    }
}
