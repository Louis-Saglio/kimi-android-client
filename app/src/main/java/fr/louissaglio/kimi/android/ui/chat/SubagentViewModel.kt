package fr.louissaglio.kimi.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Read-only sub-agent detail page (ticket 045): renders the session-scoped store entry
 * accumulated from the session socket. This ViewModel holds NO socket of its own — the
 * chat screen's collection is the store's only feeder, kept alive while this page is
 * open (the pageOpened/pageClosed pair below feeds the chat's socket gate). A second,
 * page-owned socket would double-feed the store on every cursor replay.
 */
@HiltViewModel
class SubagentViewModel @Inject constructor(
    private val subagentStore: SubagentStreamStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])
    private val subagentId: String = checkNotNull(savedStateHandle["subagentId"])

    /** [status] null = the store has no entry for [subagentId] (its spawn predates the
     * app's subscription): the screen shows the empty state. */
    data class UiState(
        val subagentId: String,
        /** The subagent type (e.g. "explore"). */
        val name: String? = null,
        val description: String? = null,
        val status: SubagentStatus? = null,
        val items: List<ChatItem> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState(subagentId = subagentId))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // The store entry arrives whenever the spawn did (typically while the chat was
        // visible): collect from init, not from ON_START.
        viewModelScope.launch {
            subagentStore.entry(sessionId, subagentId).collect { entry ->
                _uiState.update {
                    it.copy(
                        name = entry?.name,
                        description = entry?.description,
                        status = entry?.status,
                        items = entry?.items.orEmpty(),
                    )
                }
            }
        }
    }

    /** Wired to ON_START. */
    fun onScreenStarted() {
        subagentStore.pageOpened(sessionId)
    }

    /** Wired to ON_STOP. */
    fun onScreenStopped() {
        subagentStore.pageClosed(sessionId)
    }
}
