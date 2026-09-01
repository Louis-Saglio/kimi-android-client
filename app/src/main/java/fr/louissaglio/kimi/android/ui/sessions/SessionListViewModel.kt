package fr.louissaglio.kimi.android.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.chat.ModelsRepository
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.data.sessions.Session
import fr.louissaglio.kimi.android.data.sessions.SessionActivityStore
import fr.louissaglio.kimi.android.data.sessions.SessionListCache
import fr.louissaglio.kimi.android.data.sessions.SessionListEvent
import fr.louissaglio.kimi.android.data.sessions.SessionListSocket
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.sessions.Workspace
import fr.louissaglio.kimi.android.data.sessions.isAwaitingInteraction
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val sessionsRepository: SessionsRepository,
    private val settingsRepository: SettingsRepository,
    private val activityStore: SessionActivityStore,
    private val sessionListSocket: SessionListSocket,
    private val sessionListCache: SessionListCache,
    private val modelsRepository: ModelsRepository,
) : ViewModel() {

    /** [sessions] is null until the disk cache or the first successful load populates it; [error] is shown only when there is no list to display. */
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
    private var socketJob: Job? = null

    init {
        // Cold start renders the last-known list from disk before any network traffic
        // (ticket 111): a warm cache never shows the sessions == null spinner, and with
        // the list populated ON_START fetches nothing (ticket 112).
        viewModelScope.launch {
            val cached = sessionListCache.load(settingsRepository.current().serverUrl) ?: return@launch
            uiState.update { state ->
                // A network page that somehow landed first always wins over the disk.
                if (state.sessions == null) state.copy(sessions = cached.sessions, hasMore = cached.hasMore) else state
            }
        }
        // The one writer covering every mutation path (initial load, refresh, paging,
        // socket patches, archive drops, activity bumps, create-insert): debounced so
        // bursts collapse into a single write, collected on IO so disk writes never
        // touch the main thread.
        viewModelScope.launch(Dispatchers.IO) {
            uiState
                .map { it.sessions to it.hasMore }
                .distinctUntilChanged()
                .debounce(CACHE_WRITE_DEBOUNCE_MS)
                .collect { (sessions, hasMore) ->
                    if (sessions == null) return@collect
                    sessionListCache.save(settingsRepository.current().serverUrl, sessions, hasMore)
                }
        }
    }

    fun onScreenStarted() {
        bumpPendingActivity()
        // The list never refreshes itself (ticket 112): with rows on screen the 087
        // socket patches and the bump above keep it current, and staleness from other
        // clients is accepted until the next manual refresh. sessions == null means
        // no cache for this server — that is the visible initial load, not a refresh.
        if (uiState.value.sessions == null) reload()
        startSocket()
    }

    /** ON_STOP (chat opened, app backgrounded): the live connection closes with the
     * screen — it only ever runs while the list is visible (ticket 087). */
    fun onScreenStopped() {
        socketJob?.cancel()
        socketJob = null
    }

    /**
     * Collects the sessions-list socket for the life of the screen (ticket 087): rows
     * patch in place on work_changed / meta.updated, the archived row drops out on the
     * global event.session.archived (0.38.0 — ticket 105), the global
     * config/model-catalog changed events drop the ModelsRepository caches (0.39.1 —
     * ticket 110), a reconnect heals via one silent refresh. The loaded ids ride a
     * derived flow, so page loads while connected re-subscribe on their own.
     * Restart-safe: ON_START/ON_STOP pair up, but a repeat start never opens a second
     * collection.
     */
    private fun startSocket() {
        if (socketJob?.isActive == true) return
        val sessionIds = uiState
            .map { state -> state.sessions?.mapTo(LinkedHashSet(), Session::id).orEmpty() }
            .distinctUntilChanged()
        socketJob = viewModelScope.launch {
            sessionListSocket.observe(settingsRepository.current(), sessionIds).collect(::onSocketEvent)
        }
    }

    private suspend fun onSocketEvent(event: SessionListEvent) {
        when (event) {
            // The connection dropped and came back: events in the gap are lost, so
            // re-sync the whole list once — the only automatic refresh left (ticket 112).
            SessionListEvent.Reconnected -> silentRefresh()
            is SessionListEvent.WorkChanged -> patchRow(event.sessionId) {
                it.copy(
                    isRunning = event.busy,
                    isAwaitingInteraction = isAwaitingInteraction(event.pendingInteraction),
                )
            }
            is SessionListEvent.MetaUpdated -> patchRow(event.sessionId) {
                // Same blank-title rule as the REST mapping: fall back to the cwd.
                it.copy(title = event.title?.takeIf(String::isNotBlank))
            }
            // Archived from any client (0.38.0): the row leaves the list. An unknown
            // id is ignored, like in patchRow; the next refresh reconciles.
            is SessionListEvent.SessionArchived -> uiState.update { state ->
                val sessions = state.sessions ?: return@update state
                if (sessions.none { it.id == event.sessionId }) return@update state
                state.copy(sessions = sessions.filterNot { it.id == event.sessionId })
            }
            // A config write or model-catalog refresh from any client (0.39.1 —
            // ticket 110): drop the models/config caches, so the next read (chat
            // model sheet, Settings models section) refetches.
            SessionListEvent.ConfigChanged,
            SessionListEvent.ModelCatalogChanged,
            -> modelsRepository.invalidate(settingsRepository.current())
        }
    }

    /**
     * Applies [transform] to the row of [sessionId] in place (ticket 087): the row
     * never moves — ordering stays owned by full list (re)loads. An unknown id is
     * ignored; the next (re)load reconciles.
     */
    private inline fun patchRow(sessionId: String, transform: (Session) -> Session) {
        uiState.update { state ->
            val sessions = state.sessions ?: return@update state
            val index = sessions.indexOfFirst { it.id == sessionId }
            if (index < 0) return@update state
            state.copy(sessions = sessions.toMutableList().apply { this[index] = transform(sessions[index]) })
        }
    }

    fun onRetry() = reload()

    fun onPullToRefresh() = reload()

    /**
     * Applies the recorded chat activity synchronously on return from chat (ticket
     * 086): messaged sessions move to the top, most recent activity first, with
     * [Session.updatedAt] set to the recorded instant — matching the server-side
     * `updated_at` bump a sent message causes, so the next manual refresh lands with
     * the same ordering (no automatic refresh follows anymore, ticket 112). Entries
     * outside the loaded page are dropped: the next load places them via server
     * ordering. Nothing recorded → the list is untouched.
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
     * [loadJob] is checked too: the reconnect's silent refresh (087) sets no flag.
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
     * Silent re-sync, called only from the socket-reconnect heal (the ON_START call
     * went away with ticket 112): never sets [UiState.isLoading]/[UiState.isRefreshing];
     * on success replaces the list; on failure the old list stays and no
     * [Effect.RefreshFailed] is emitted — no snackbar from a reconnect while offline.
     * Pull-to-refresh keeps the visible behavior via [reload].
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
        const val CACHE_WRITE_DEBOUNCE_MS = 500L
    }
}
