package fr.louissaglio.kimi.android.ui.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.FileContent
import fr.louissaglio.kimi.android.data.chat.FilePhase
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.navigation.TurnFileContent
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One file's before/after viewer for a turn (ticket 150): both phases
 * (`start`/`end`) are fetched in parallel on open, so the Before/After toggle
 * switches instantly; each side carries its own error state with a per-side retry.
 * The data is committed history — like the changes list, there is no poll and no
 * socket interest.
 */
@HiltViewModel
class TurnFileContentViewModel internal constructor(
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    routeArgs: TurnFileContent,
) : ViewModel() {

    // Production entry point (Hilt): the destination's SavedStateHandle is decoded
    // from the typed route (ticket 083). Local unit tests call the primary
    // constructor with the route object directly (the JobDetailViewModel pattern).
    @Inject
    constructor(
        chatRepository: ChatRepository,
        settingsRepository: SettingsRepository,
        savedStateHandle: SavedStateHandle,
    ) : this(chatRepository, settingsRepository, savedStateHandle.toRoute<TurnFileContent>())

    private val sessionId: String = routeArgs.sessionId
    val turnId: Int = routeArgs.turnId
    val path: String = routeArgs.path

    /** The viewer's tabs: [Before] is the file as the turn found it (the wire's
     * `start` phase), [After] as the turn left it (`end`) — the default, the turn's
     * result being what a tap usually means to see. */
    enum class Tab(val label: String, val phase: FilePhase) {
        Before("Before", FilePhase.Start),
        After("After", FilePhase.End),
    }

    /** One side's fetch state: [resolved] false while the request runs; after it,
     * [snapshot] null means the file did not exist at that phase (an added file's
     * Before, a deleted file's After) — the screen renders its placeholder. */
    data class Side(
        val resolved: Boolean = false,
        val snapshot: FileContent? = null,
        val error: String? = null,
    )

    data class UiState(
        val path: String,
        val tab: Tab = Tab.After,
        val before: Side = Side(),
        val after: Side = Side(),
    )

    val uiState: StateFlow<UiState>
        field = MutableStateFlow(UiState(path))

    init {
        // Both sides on open (ticket 150): the toggle never waits on the network.
        fetch(Tab.Before)
        fetch(Tab.After)
    }

    fun onTabSelected(tab: Tab) {
        uiState.update { it.copy(tab = tab) }
    }

    /** Retry of one side's failed fetch — the other side is left untouched. */
    fun onRetry(tab: Tab) {
        updateSide(tab) { Side() }
        fetch(tab)
    }

    private fun fetch(tab: Tab) {
        viewModelScope.launch {
            chatRepository.getFileContent(settingsRepository.current(), sessionId, turnId, path, tab.phase)
                .onSuccess { snapshot ->
                    // The snapshot version is a debugging aid (ticket 150) — logged,
                    // displayed nowhere.
                    Log.d(TAG, "${tab.phase.wire} snapshot of $path at turn $turnId: version ${snapshot?.version}")
                    updateSide(tab) { it.copy(resolved = true, snapshot = snapshot, error = null) }
                }
                .onFailure { error ->
                    updateSide(tab) { it.copy(resolved = true, error = error.toUserMessage()) }
                }
        }
    }

    private fun updateSide(tab: Tab, transform: (Side) -> Side) {
        uiState.update {
            when (tab) {
                Tab.Before -> it.copy(before = transform(it.before))
                Tab.After -> it.copy(after = transform(it.after))
            }
        }
    }

    private companion object {
        const val TAG = "TurnFileContent"
    }
}
