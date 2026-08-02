package fr.louissaglio.kimi.android.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.data.sessions.Session
import fr.louissaglio.kimi.android.data.sessions.SessionActivityStore
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.sessions.Workspace
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val sessionsRepository: SessionsRepository,
    private val settingsRepository: SettingsRepository,
    private val activityStore: SessionActivityStore,
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
        /** Null while unknown/failed/empty — the dialog then falls back to a plain text field. */
        val workspaces: List<Workspace>? = null,
    )

    sealed interface Effect {
        data class RefreshFailed(val message: String) : Effect
        data class CreateFailed(val message: String) : Effect
        data class SessionCreated(val session: Session) : Effect
    }

    val uiState: StateFlow<UiState>
        field = MutableStateFlow(UiState())

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null

    fun onScreenStarted() {
        bumpPendingActivity()
        // With a list already loaded the refresh runs silently — no spinner, no
        // visible disturbance (ticket 086); otherwise this is the visible initial load.
        if (uiState.value.sessions != null) silentRefresh() else reload()
    }

    fun onRetry() = reload()

    fun onPullToRefresh() = reload()

    /**
     * Applies the recorded chat activity synchronously, before the refresh fires
     * (ticket 086): messaged sessions move to the top, most recent activity first,
     * with [Session.updatedAt] set to the recorded instant — matching the server-side
     * `updated_at` bump a sent message causes, so the refresh lands with the same
     * ordering. Entries outside the loaded page are dropped: the refresh places them
     * via server ordering. Nothing recorded → the list is untouched.
     */
    private fun bumpPendingActivity() {
        val pending = activityStore.consumeAll()
        if (pending.isEmpty()) return
        uiState.update { state ->
            val sessions = state.sessions ?: return@update state
            val bumped = pending.entries
                .sortedByDescending { (_, at) -> at }
                .mapNotNull { (id, at) -> sessions.find { it.id == id }?.copy(updatedAt = at) }
            if (bumped.isEmpty()) return@update state
            val bumpedIds = bumped.mapTo(HashSet()) { it.id }
            state.copy(sessions = bumped + sessions.filter { it.id !in bumpedIds })
        }
    }

    /**
     * Loads the next page when the list scrolls near its end. No-op while any load is
     * in flight or once the server reported no more pages. The job check (not the
     * state flag) is the re-entrancy guard: the UI calls this on every recomposition
     * near the end, and state updates are not visible synchronously across dispatches.
     * [loadJob] is checked too: the silent refresh (ticket 086) sets no flag.
     */
    fun onEndReached() {
        val state = uiState.value
        if (state.isLoading || state.isRefreshing || !state.hasMore) return
        if (loadJob?.isActive == true || loadMoreJob?.isActive == true) return
        val beforeId = state.sessions?.lastOrNull()?.id ?: return
        loadMoreJob = viewModelScope.launch {
            uiState.update { it.copy(isLoadingMore = true, loadMoreError = null) }
            sessionsRepository.listSessions(settingsRepository.current(), beforeId = beforeId)
                .onSuccess { page ->
                    uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            sessions = (it.sessions.orEmpty() + page.items).distinctBy(Session::id),
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

    fun onNewSessionClick() {
        uiState.update {
            it.copy(
                showCreateDialog = true,
                newSessionCwd = DEFAULT_NEW_SESSION_CWD,
                newSessionTitle = "",
                newSessionCwdError = null,
                workspaces = null,
            )
        }
        // Best-effort: on failure or an empty list the dialog keeps the plain text
        // field with the default cwd; no error is surfaced.
        viewModelScope.launch {
            sessionsRepository.listWorkspaces(settingsRepository.current())
                .onSuccess { workspaces ->
                    if (workspaces.isEmpty()) return@onSuccess
                    uiState.update {
                        if (!it.showCreateDialog) return@update it
                        it.copy(
                            workspaces = workspaces,
                            // Don't clobber a path the user already typed.
                            newSessionCwd =
                                if (it.newSessionCwd == DEFAULT_NEW_SESSION_CWD) {
                                    workspaces.first().root
                                } else {
                                    it.newSessionCwd
                                },
                        )
                    }
                }
        }
    }

    fun onCreateDialogDismiss() = uiState.update { it.copy(showCreateDialog = false) }

    fun onNewSessionCwdChange(value: String) =
        uiState.update { it.copy(newSessionCwd = value, newSessionCwdError = null) }

    fun onNewSessionTitleChange(value: String) =
        uiState.update { it.copy(newSessionTitle = value) }

    fun onCreateSessionConfirm() {
        val cwd = uiState.value.newSessionCwd.trim()
        val validationError = when {
            cwd.isEmpty() -> "Enter a working directory"
            !cwd.startsWith("/") -> "Path must be absolute"
            else -> null
        }
        if (validationError != null) {
            uiState.update { it.copy(newSessionCwdError = validationError) }
            return
        }
        viewModelScope.launch {
            uiState.update { it.copy(isCreating = true) }
            sessionsRepository.createSession(
                settings = settingsRepository.current(),
                cwd = cwd,
                title = uiState.value.newSessionTitle.trim().ifBlank { null },
            ).onSuccess { session ->
                // Cancel in-flight loads first so a stale page landing after the
                // insert cannot drop the new row (ticket 086).
                loadJob?.cancel()
                loadMoreJob?.cancel()
                uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        loadMoreError = null,
                        isCreating = false,
                        showCreateDialog = false,
                        sessions = listOf(session) + it.sessions.orEmpty().filter { s -> s.id != session.id },
                    )
                }
                _effects.send(Effect.SessionCreated(session))
            }.onFailure { error ->
                // Inputs are kept so the user can fix and retry after the snackbar.
                uiState.update { it.copy(isCreating = false) }
                _effects.send(Effect.CreateFailed(error.toUserMessage()))
            }
        }
    }

    /**
     * ON_START refresh with a list already loaded (ticket 086): never sets
     * [UiState.isLoading]/[UiState.isRefreshing]; on success replaces the list; on
     * failure the old list stays and no [Effect.RefreshFailed] is emitted — no
     * snackbar on every return while offline. Pull-to-refresh keeps the visible
     * behavior via [reload].
     */
    private fun silentRefresh() {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        loadJob = viewModelScope.launch {
            uiState.update { it.copy(isLoadingMore = false, loadMoreError = null) }
            sessionsRepository.listSessions(settingsRepository.current())
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
        }
    }

    private fun reload() {
        // Cancel rather than skip so a pull-to-refresh during an in-flight load still completes.
        loadJob?.cancel()
        loadMoreJob?.cancel()
        loadJob = viewModelScope.launch {
            val hadSessions = uiState.value.sessions != null
            uiState.update {
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
                    if (hadSessions) {
                        uiState.update { it.copy(isLoading = false, isRefreshing = false) }
                        _effects.send(Effect.RefreshFailed(message))
                    } else {
                        uiState.update { it.copy(isLoading = false, isRefreshing = false, error = message) }
                    }
                }
        }
    }

    private companion object {
        const val DEFAULT_NEW_SESSION_CWD = "/home/ubuntu"
    }
}
