package fr.louissaglio.kimi.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.Todo
import fr.louissaglio.kimi.android.data.chat.TranscriptPage
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.navigation.Subagent
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Read-only sub-agent detail page (ticket 045), transcript-backed since ticket 046:
 * the session socket's live tail streams on top of committed history fetched from the
 * per-agent transcript endpoint (docs/server-api.md, Transcript) — the same
 * committed-history + live-tail architecture as the main chat. This ViewModel holds
 * NO socket of its own — the store is fed by exactly one collection (ticket 064):
 * the chat's while its gate holds the session, otherwise the store's own keep-alive
 * collection, which the pageOpened/pageClosed pair below drives. The keep-alive is
 * what keeps this page streaming after process death with the page on top (the chat
 * ViewModel is never recreated then); a second, page-owned socket would double-feed
 * the store on every cursor replay.
 */
@HiltViewModel
class SubagentViewModel internal constructor(
    private val subagentStore: SubagentStreamStore,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val routeArgs: Subagent,
) : ViewModel() {

    // Production entry point (Hilt): the destination's SavedStateHandle is decoded
    // from the typed route (ticket 083). Local unit tests call the primary
    // constructor with the route object directly instead — toRoute's decode path
    // needs a real android.os.Bundle, and the JVM android.jar in local tests is a
    // stub that silently decodes every argument to null.
    @Inject
    constructor(
        subagentStore: SubagentStreamStore,
        chatRepository: ChatRepository,
        settingsRepository: SettingsRepository,
        savedStateHandle: SavedStateHandle,
    ) : this(subagentStore, chatRepository, settingsRepository, savedStateHandle.toRoute<Subagent>())

    private val sessionId: String = routeArgs.sessionId
    private val subagentId: String = routeArgs.subagentId

    /** [status] null = the store has no entry for [subagentId] yet (its spawn predates
     * the app's subscription and the transcript fetch has not landed): the screen
     * shows its loading/empty state. [sessionId] rides along so the screen's
     * navigation callbacks (the job screen, ticket 098) can name it. */
    data class UiState(
        val subagentId: String,
        val sessionId: String,
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
        /** The sub-agent's own todo list (its TodoList calls) and its own background
         * tasks — the dock at the page's bottom bar, same widgets as the chat's. */
        val todos: List<Todo>? = null,
        val jobs: List<Job> = emptyList(),
    )

    val uiState: StateFlow<UiState>
        field = MutableStateFlow(UiState(subagentId = subagentId, sessionId = sessionId))

    /** Last status the entry collector observed — the reconcile trigger's edge detector. */
    private var lastSeenStatus: SubagentStatus? = null

    /** The paged-history loader (ticket 074), shared with the main chat: it owns the
     * fetch jobs and their guards (newest-page latest-wins, the 061 race guards); the
     * callbacks are this page's parameterization — transcript backing, the store-owned
     * merge (the entry collector republishes it here), and the loading/error state of
     * this UiState. */
    private val historyPager = HistoryPager<TranscriptPage>(
        scope = { viewModelScope },
        fetch = { beforeTurn ->
            chatRepository.getTranscript(
                settingsRepository.current(), sessionId, subagentId, beforeTurn = beforeTurn, pageSize = PAGE_SIZE,
            )
        },
        onNewestStart = {
            val showLoading = uiState.value.items.isEmpty() && uiState.value.status == null
            if (showLoading) uiState.update { it.copy(isLoading = true) }
        },
        applyNewest = { page, keepLive ->
            subagentStore.applyTranscriptPage(sessionId, subagentId, page, keepLive)
            uiState.update { it.copy(isLoading = false, error = null) }
        },
        applyOlder = { page ->
            subagentStore.prependTranscriptPage(sessionId, subagentId, page)
        },
        onNewestError = { error ->
            uiState.update {
                it.copy(
                    isLoading = false,
                    // With nothing to show the fetch is the page's only data
                    // source: surface the failure full-screen (retry via onRetry).
                    // Otherwise the live stream keeps rendering and the next
                    // reconcile/refetch heals.
                    error = if (it.items.isEmpty() && it.status == null) error.toUserMessage() else it.error,
                )
            }
        },
        hasMore = { uiState.value.hasMoreHistory },
        cursor = { subagentStore.oldestTurnId(sessionId, subagentId) },
        setLoadingMore = { loading -> uiState.update { it.copy(isLoadingMoreHistory = loading) } },
    )

    init {
        // The store entry arrives whenever the spawn did (typically while the chat was
        // visible): collect from init, not from ON_START.
        viewModelScope.launch {
            subagentStore.entry(sessionId, subagentId).collect { entry ->
                uiState.update {
                    it.copy(
                        name = entry?.name,
                        description = entry?.description,
                        status = entry?.status,
                        items = entry?.items.orEmpty(),
                        hasMoreHistory = entry?.hasMoreTurns == true,
                        todos = entry?.todos,
                        jobs = entry?.jobs.orEmpty(),
                    )
                }
                maybeReconcile(entry?.status)
            }
        }
        // Cold open: nothing fetched yet for this sub-agent (a reopened page trusts the
        // store — the chat's socket keeps its live tail fresh).
        if (!subagentStore.hasTranscript(sessionId, subagentId)) {
            historyPager.refresh(keepLive = true)
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
        historyPager.refresh(keepLive = false)
    }

    /** Scroll-to-top trigger (ticket 043's mechanics on turn cursors): the shared
     * pager fetches the next older page (`before_turn` = oldest loaded turn) and
     * prepends it, deduplicated by turn id in the store's timeline. */
    fun onHistoryTopReached() = historyPager.loadOlder()

    /** Retry of the failed initial load (the error state is the whole screen). */
    fun onRetry() {
        uiState.update { it.copy(error = null) }
        historyPager.refresh(keepLive = true)
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
