package fr.louissaglio.kimi.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.data.toUserMessage
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Read-only sub-agent detail page (ticket 045), transcript-backed since ticket 046:
 * the session socket's live tail streams on top of committed history fetched from the
 * per-agent transcript endpoint (docs/server-api.md, Transcript) — the same
 * committed-history + live-tail architecture as the main chat. This ViewModel holds
 * NO socket of its own — the chat screen's collection is the store's only feeder,
 * kept alive while this page is open (the pageOpened/pageClosed pair below feeds the
 * chat's socket gate). A second, page-owned socket would double-feed the store on
 * every cursor replay.
 */
@HiltViewModel
class SubagentViewModel @Inject constructor(
    private val subagentStore: SubagentStreamStore,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])
    private val subagentId: String = checkNotNull(savedStateHandle["subagentId"])

    /** [status] null = the store has no entry for [subagentId] yet (its spawn predates
     * the app's subscription and the transcript fetch has not landed): the screen
     * shows its loading/empty state. */
    data class UiState(
        val subagentId: String,
        /** The subagent type (e.g. "explore"). */
        val name: String? = null,
        val description: String? = null,
        val status: SubagentStatus? = null,
        val items: List<ChatItem> = emptyList(),
        /** True while the initial transcript fetch runs with nothing to show yet. */
        val isLoading: Boolean = false,
        /** The initial transcript fetch failed and there is nothing to display. */
        val error: String? = null,
        /** True while older transcript turns exist (scroll-to-top loads them). */
        val hasMoreHistory: Boolean = false,
        val isLoadingMoreHistory: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState(subagentId = subagentId))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Newest-page transcript loads (initial + completion reconcile); latest wins. */
    private var transcriptJob: Job? = null

    /** In-flight guard of the older-page load (ticket 010's pattern): the Job, not a
     * state flag. */
    private var olderPageJob: Job? = null

    /** Last status the entry collector observed — the reconcile trigger's edge detector. */
    private var lastSeenStatus: SubagentStatus? = null

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
                        hasMoreHistory = entry?.hasMoreTurns == true,
                    )
                }
                maybeReconcile(entry?.status)
            }
        }
        // Cold open: nothing fetched yet for this sub-agent (a reopened page trusts the
        // store — the chat's socket keeps its live tail fresh).
        if (!subagentStore.hasTranscript(sessionId, subagentId)) {
            refreshTranscript(keepLive = true)
        }
    }

    /** A sub-agent reaching a terminal state while the page is watched gets one
     * canonical refetch (ticket 046): the transcript is the committed rendering of the
     * run, the live rows are dropped. Cold opens (the first observed status already
     * terminal) skip it — the initial load IS the canonical fetch. */
    private fun maybeReconcile(status: SubagentStatus?) {
        val previous = lastSeenStatus
        lastSeenStatus = status
        if (previous == null || previous == SubagentStatus.Completed || previous == SubagentStatus.Failed) return
        if (status != SubagentStatus.Completed && status != SubagentStatus.Failed) return
        refreshTranscript(keepLive = false)
    }

    private fun refreshTranscript(keepLive: Boolean) {
        // Latest fetch wins: a stale response must not overwrite a newer one.
        transcriptJob?.cancel()
        // A full refetch resets history to the newest page (ChatViewModel's
        // refreshHistory rule, ported in ticket 061): an in-flight older-page load
        // must not prepend a stale page — contiguous with the OLD committed list,
        // not the fresh one — onto the replacement.
        olderPageJob?.cancel()
        _uiState.update { it.copy(isLoadingMoreHistory = false) }
        transcriptJob = viewModelScope.launch {
            val showLoading = _uiState.value.items.isEmpty() && _uiState.value.status == null
            if (showLoading) _uiState.update { it.copy(isLoading = true) }
            chatRepository.getTranscript(
                settingsRepository.current(), sessionId, subagentId, beforeTurn = null, pageSize = PAGE_SIZE,
            )
                .onSuccess { page ->
                    subagentStore.applyTranscriptPage(sessionId, subagentId, page, keepLive)
                    _uiState.update { it.copy(isLoading = false, error = null) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            // With nothing to show the fetch is the page's only data
                            // source: surface the failure full-screen (retry via onRetry).
                            // Otherwise the live stream keeps rendering and the next
                            // reconcile/refetch heals.
                            error = if (it.items.isEmpty() && it.status == null) error.toUserMessage() else it.error,
                        )
                    }
                }
        }
    }

    /** Scroll-to-top trigger (ticket 043's mechanics on turn cursors): no-op while an
     * older-page load is in flight or the server said `has_more: false`; otherwise
     * fetches the next older page (`before_turn` = oldest loaded turn) and PREPENDS
     * it, deduplicated by turn id in the store's timeline. The newest-page guard
     * ([transcriptJob], ported from ChatViewModel in ticket 061) is the same trap as
     * the in-flight guard: a page fetched against the stale cursor would land on a
     * refetch's fresh page with a hole in between. A failure keeps the loaded
     * history visible and retries on the next trigger. */
    fun onHistoryTopReached() {
        if (olderPageJob?.isActive == true) return
        if (transcriptJob?.isActive == true) return
        if (!_uiState.value.hasMoreHistory) return
        olderPageJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMoreHistory = true) }
            chatRepository.getTranscript(
                settingsRepository.current(), sessionId, subagentId,
                beforeTurn = subagentStore.oldestTurnId(sessionId, subagentId), pageSize = PAGE_SIZE,
            ).onSuccess { page ->
                subagentStore.prependTranscriptPage(sessionId, subagentId, page)
            }
            _uiState.update { it.copy(isLoadingMoreHistory = false) }
        }
    }

    /** Retry of the failed initial load (the error state is the whole screen). */
    fun onRetry() {
        _uiState.update { it.copy(error = null) }
        refreshTranscript(keepLive = true)
    }

    /** Wired to ON_START. */
    fun onScreenStarted() {
        subagentStore.pageOpened(sessionId)
    }

    /** Wired to ON_STOP. */
    fun onScreenStopped() {
        subagentStore.pageClosed(sessionId)
    }

    private companion object {
        /** Transcript page size (server default and max are 20/100): 20 keeps pages
         * small — a turn can hold several large frames. */
        const val PAGE_SIZE = 20
    }
}
