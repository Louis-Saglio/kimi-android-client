package fr.louissaglio.kimi.android.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.chat.ModelsRepository
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.data.sessions.Session
import fr.louissaglio.kimi.android.data.sessions.SessionActivityStore
import fr.louissaglio.kimi.android.data.sessions.SessionGroup
import fr.louissaglio.kimi.android.data.sessions.SessionListCache
import fr.louissaglio.kimi.android.data.sessions.SessionListEvent
import fr.louissaglio.kimi.android.data.sessions.SessionListSocket
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.sessions.Workspace
import fr.louissaglio.kimi.android.data.sessions.isAwaitingInteraction
import fr.louissaglio.kimi.android.data.sessions.workspaceLabelsFor
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
        /** The flat/grouped toggle (ticket 115), persisted in SettingsRepository; flat default. */
        val groupedView: Boolean = false,
        /** Workspace groups in grouped mode; null until the first successful grouped load. */
        val groups: List<SessionGroup>? = null,
        /** Per-group show-more footer state (ticket 116), keyed by workspace id — an entry
         * exists only while that group's next-page load is in flight or its last attempt failed. */
        val groupExpansions: Map<String, GroupExpansion> = emptyMap(),
        /** Workspace ids whose groups are folded (rows hidden) in grouped mode. Survives
         * refreshes; not persisted — a restart comes back with everything unfolded. */
        val foldedGroupIds: Set<String> = emptySet(),
        val showCreateDialog: Boolean = false,
        val newSessionCwd: String = "",
        val newSessionTitle: String = "",
        val newSessionCwdError: String? = null,
        val isCreating: Boolean = false,
        /** Null while unknown/failed/empty — the dialog then falls back to a plain text field. */
        val workspaces: List<Workspace>? = null,
    ) {
        /**
         * cwd → disambiguated workspace label for the row supporting line (ticket 114).
         * Lives on the state, not on [Session]: a label depends on the other rows'
         * cwds. Derived per state instance — every list mutation copies the state, so
         * a page bringing in a colliding cwd relabels on its own (labels just gain a
         * parent segment).
         */
        val workspaceLabels: Map<String, String> by lazy {
            workspaceLabelsFor(sessions.orEmpty().map(Session::cwd))
        }

        /** cwd → disambiguated label for the group headers (ticket 115) — the 114 rule over the groups' cwds. */
        val groupLabels: Map<String, String> by lazy {
            workspaceLabelsFor(groups.orEmpty().map(SessionGroup::cwd))
        }

        /** True once the active mode has its list on screen — the 112 "rows on screen" check is mode-aware. */
        val hasContent: Boolean
            get() = if (groupedView) groups != null else sessions != null
    }

    /** One group's show-more footer state (ticket 116): the in-flight next-page load, or the last attempt's error. */
    data class GroupExpansion(
        val isLoading: Boolean = false,
        val error: String? = null,
    )

    sealed interface Effect {
        data class RefreshFailed(val message: String) : Effect
        data class CreateFailed(val message: String) : Effect
        data class SessionCreated(val session: Session) : Effect

        /** The fork POST failed (ticket 128 — stale id 40401, busy source 40901): the server's own message; the list is untouched. */
        data class ForkFailed(val message: String) : Effect

        /** Fork succeeded (ticket 128): the row is already inserted; the collector shows a confirmation snackbar. */
        data class SessionForked(val session: Session) : Effect
    }

    val uiState: StateFlow<UiState>
        field = MutableStateFlow(UiState())

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null
    private var socketJob: Job? = null
    private val groupLoadJobs = mutableMapOf<String, Job>()

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
        viewModelScope.launch {
            // The persisted flat/grouped toggle (ticket 115) is re-read on every screen
            // start, before the content check, so the first load hits the active mode's
            // endpoint. Re-reading also heals a mode change made in another session.
            val grouped = settingsRepository.isSessionListGrouped()
            if (grouped != uiState.value.groupedView) uiState.update { it.copy(groupedView = grouped) }
            // The list never refreshes itself (ticket 112): with rows on screen the 087
            // socket patches and the bump above keep it current, and staleness from other
            // clients is accepted until the next manual refresh. No content in the active
            // mode (no cache, or a flat cache that cannot serve the grouped view) means
            // this is the visible initial load, not a refresh.
            if (!uiState.value.hasContent) reload()
        }
        startSocket()
    }

    /**
     * The top-bar flat ↔ grouped toggle (ticket 115): the choice persists to DataStore
     * and switching to a mode with nothing to show fires its initial load. Already
     * loaded modes survive the switch, so toggling back is free.
     */
    fun onViewModeToggle() {
        val grouped = !uiState.value.groupedView
        uiState.update { it.copy(groupedView = grouped) }
        viewModelScope.launch { settingsRepository.setSessionListGrouped(grouped) }
        if (!uiState.value.hasContent) reload()
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
     * global event.session.archived (0.38.0 — ticket 105), a session created from any
     * client is upserted on event.session.created (ticket 129), the global
     * config/model-catalog changed events drop the ModelsRepository caches (0.39.1 —
     * ticket 110), a reconnect heals via one silent refresh. The loaded ids ride a
     * derived flow, so page loads while connected re-subscribe on their own.
     * Restart-safe: ON_START/ON_STOP pair up, but a repeat start never opens a second
     * collection.
     */
    private fun startSocket() {
        if (socketJob?.isActive == true) return
        // The subscribed ids follow the active mode: the flat page's rows, or every
        // group's rows in grouped mode.
        val sessionIds = uiState
            .map { state ->
                if (state.groupedView) {
                    state.groups.orEmpty().flatMapTo(LinkedHashSet()) { group -> group.sessions.map(Session::id) }
                } else {
                    state.sessions?.mapTo(LinkedHashSet(), Session::id).orEmpty()
                }
            }
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
                val sessions = state.sessions
                val groups = state.groups
                val inFlat = sessions?.any { it.id == event.sessionId } == true
                val inGroups = groups?.any { group -> group.sessions.any { it.id == event.sessionId } } == true
                if (!inFlat && !inGroups) return@update state
                state.copy(
                    sessions = if (inFlat) sessions!!.filterNot { it.id == event.sessionId } else sessions,
                    groups = if (inGroups) {
                        groups!!.map { group -> group.copy(sessions = group.sessions.filterNot { it.id == event.sessionId }) }
                    } else {
                        groups
                    },
                )
            }
            // Created from any client (verified on 0.42.0 — ticket 129): upsert by id
            // at the row's updated_at position, in the flat list and in the group
            // matching its cwd — the create/fork placement rule, so the app's own
            // create echo is a no-op (insertSorted replaces by id, never duplicates).
            // A workspace with no group on screen is left out, same as create; the
            // next grouped load reconciles.
            is SessionListEvent.SessionCreated -> uiState.update { state ->
                val session = event.session
                state.copy(
                    // Same guard as create/fork: in grouped mode a never-loaded flat
                    // list stays null — growing it to one row would clobber the cache.
                    sessions = if (state.groupedView && state.sessions == null) {
                        null
                    } else {
                        state.sessions?.insertSorted(session)
                    },
                    groups = state.groups?.map { group ->
                        if (group.cwd == session.cwd) {
                            group.copy(sessions = group.sessions.insertSorted(session))
                        } else {
                            group
                        }
                    },
                )
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
     * never moves — ordering stays owned by full list (re)loads. Mode-agnostic (115):
     * every loaded list is patched, so the row updates inside its group too. An
     * unknown id is ignored; the next (re)load reconciles.
     */
    private inline fun patchRow(sessionId: String, transform: (Session) -> Session) {
        uiState.update { state ->
            val sessions = state.sessions
            val groups = state.groups
            val inFlat = sessions?.any { it.id == sessionId } == true
            val inGroups = groups?.any { group -> group.sessions.any { it.id == sessionId } } == true
            if (!inFlat && !inGroups) return@update state
            state.copy(
                sessions = if (inFlat) sessions!!.map { if (it.id == sessionId) transform(it) else it } else sessions,
                groups = if (inGroups) {
                    groups!!.map { group ->
                        if (group.sessions.none { it.id == sessionId }) {
                            group
                        } else {
                            group.copy(sessions = group.sessions.map { if (it.id == sessionId) transform(it) else it })
                        }
                    }
                } else {
                    groups
                },
            )
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
     * ordering. Nothing recorded → the list is untouched. In grouped mode (115) the
     * same bump moves the session to the top of ITS group only — groups never reorder.
     */
    private fun bumpPendingActivity() {
        val pending = activityStore.consumeAll()
        if (pending.isEmpty()) return
        uiState.update { state ->
            var newState = state
            state.sessions?.let { sessions ->
                val bumped = pending.entries
                    .sortedByDescending { (_, at) -> at }
                    .mapNotNull { (id, at) -> sessions.find { it.id == id }?.copy(updatedAt = at) }
                if (bumped.isNotEmpty()) {
                    val bumpedIds = bumped.mapTo(HashSet()) { it.id }
                    newState = newState.copy(sessions = bumped + sessions.filter { it.id !in bumpedIds })
                }
            }
            state.groups?.let { groups ->
                newState = newState.copy(
                    groups = groups.map { group ->
                        val bumped = group.sessions
                            .filter { it.id in pending }
                            .sortedByDescending { pending.getValue(it.id) }
                            .map { it.copy(updatedAt = pending.getValue(it.id)) }
                        if (bumped.isEmpty()) {
                            group
                        } else {
                            group.copy(sessions = bumped + group.sessions.filter { it.id !in pending })
                        }
                    },
                )
            }
            newState
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
        // The grouped view is one unpaged request (ticket 115) — there is no next page.
        if (state.groupedView) return
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

    /**
     * Loads the next page of one group (ticket 116): the grouped fetch serves only each
     * workspace's first 5 sessions, so the show-more footer pages the rest through the
     * v1 list filtered by workspace (web parity), 5 at a time like the first page. The
     * per-group job (not a state flag) is the re-entrancy guard — same reasoning as
     * [onEndReached]: the footer stays tappable while the request is in flight, and
     * state updates are not visible synchronously across dispatches.
     */
    fun onGroupShowMore(workspaceId: String) {
        if (groupLoadJobs[workspaceId]?.isActive == true) return
        val group = uiState.value.groups?.find { it.workspaceId == workspaceId } ?: return
        // The footer only exists while the group has more sessions server-side.
        if (group.sessions.size >= group.total) return
        val beforeId = group.sessions.lastOrNull()?.id ?: return
        groupLoadJobs[workspaceId] = viewModelScope.launch {
            uiState.update { state ->
                state.copy(groupExpansions = state.groupExpansions + (workspaceId to GroupExpansion(isLoading = true)))
            }
            sessionsRepository.listSessions(
                settingsRepository.current(),
                beforeId = beforeId,
                workspaceId = workspaceId,
                pageSize = GROUP_PAGE_SIZE,
            )
                .onSuccess { page ->
                    uiState.update { state ->
                        state.copy(
                            groupExpansions = state.groupExpansions - workspaceId,
                            groups = state.groups?.map { current ->
                                if (current.workspaceId != workspaceId) {
                                    current
                                } else {
                                    current.copy(sessions = (current.sessions + page.items).distinctBy(Session::id))
                                }
                            },
                        )
                    }
                }
                .onFailure { error ->
                    // The loaded rows stay as they are; the footer offers a retry.
                    uiState.update { state ->
                        state.copy(
                            groupExpansions = state.groupExpansions +
                                (workspaceId to GroupExpansion(error = error.toUserMessage())),
                        )
                    }
                }
        }
    }

    /** Folds/unfolds one group in grouped mode: the header stays, its rows and
     * show-more footer hide. Expansion loads in flight keep running. */
    fun onGroupFoldToggle(workspaceId: String) = uiState.update { state ->
        state.copy(
            foldedGroupIds =
                if (workspaceId in state.foldedGroupIds) {
                    state.foldedGroupIds - workspaceId
                } else {
                    state.foldedGroupIds + workspaceId
                },
        )
    }

    /**
     * Hides the last [GROUP_PAGE_SIZE] sessions of one group (web parity with the
     * show-less button): loaded < total again, so the show-more footer comes back.
     * An expansion in flight is cancelled first so its page cannot re-append rows
     * the user just hid.
     */
    fun onGroupShowLess(workspaceId: String) {
        groupLoadJobs.remove(workspaceId)?.cancel()
        uiState.update { state ->
            state.copy(
                groupExpansions = state.groupExpansions - workspaceId,
                groups = state.groups?.map { group ->
                    if (group.workspaceId != workspaceId || group.sessions.size <= GROUP_PAGE_SIZE) {
                        group
                    } else {
                        group.copy(sessions = group.sessions.dropLast(GROUP_PAGE_SIZE))
                    }
                },
            )
        }
    }

    /**
     * Opens the create-session dialog. [cwd] pre-selects a workspace (the grouped
     * view's per-group new-session button); null falls back to the default root.
     */
    fun onNewSessionClick(cwd: String? = null) {
        uiState.update {
            it.copy(
                showCreateDialog = true,
                newSessionCwd = cwd ?: DEFAULT_NEW_SESSION_CWD,
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
                            // Don't clobber a path the user already typed or a
                            // pre-selected group cwd.
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
                        // In grouped mode a never-loaded flat list stays null: growing
                        // it to one row would clobber the disk cache with a partial list.
                        sessions = if (it.groupedView && it.sessions == null) {
                            null
                        } else {
                            listOf(session) + it.sessions.orEmpty().filter { s -> s.id != session.id }
                        },
                        // 115: the row lands at the top of the group matching the new
                        // session's cwd. Group totals are server counts and a workspace
                        // with no group yet is left out — both reconcile on the next
                        // grouped load.
                        groups = it.groups?.map { group ->
                            if (group.cwd == session.cwd) {
                                group.copy(sessions = listOf(session) + group.sessions.filter { s -> s.id != session.id })
                            } else {
                                group
                            }
                        },
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
     * Forks [session] from the long-press row dialog (ticket 128). On success the
     * fork — which inherits the source's workspace, cwd and `updated_at` (ticket
     * 127) — lands at its sorted position in both the flat list and the matching
     * workspace group, and a confirmation snackbar is emitted; the user stays in
     * the list (unlike the chat-screen fork, which navigates into the fork). On
     * failure the server's message is shown and the list is untouched. No-ops on a
     * running session: the server rejects a busy fork with 40901, and the dialog
     * action is disabled anyway.
     */
    fun onForkSession(session: Session) {
        if (session.isRunning) return
        viewModelScope.launch {
            sessionsRepository.forkSession(settingsRepository.current(), session.id)
                .onSuccess { fork ->
                    // Same guard as create (ticket 086): a stale page landing after
                    // the insert cannot drop the new row.
                    loadJob?.cancel()
                    loadMoreJob?.cancel()
                    uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            isRefreshing = false,
                            isLoadingMore = false,
                            loadMoreError = null,
                            // In grouped mode a never-loaded flat list stays null:
                            // growing it to one row would clobber the disk cache.
                            sessions = if (state.groupedView && state.sessions == null) {
                                null
                            } else {
                                state.sessions?.insertSorted(fork)
                            },
                            // The fork lands in the group matching its (inherited) cwd.
                            // Group totals are server counts and a workspace with no
                            // group yet is left out — both reconcile on the next
                            // grouped load, same as create.
                            groups = state.groups?.map { group ->
                                if (group.cwd == fork.cwd) {
                                    group.copy(sessions = group.sessions.insertSorted(fork))
                                } else {
                                    group
                                }
                            },
                        )
                    }
                    _effects.send(Effect.SessionForked(fork))
                }
                .onFailure { error ->
                    _effects.send(Effect.ForkFailed(error.toUserMessage()))
                }
        }
    }

    /**
     * Sorted insert shared by the fork (ticket 128) and the event.session.created
     * upsert (ticket 129): the lists are updated_at-descending and the fork inherits
     * the source's `updated_at`, so landing it behind the rows at least as recent puts
     * it right next to its source — not at the top like create. An id already present
     * is replaced, not duplicated — the app's own create echo is thereby a no-op.
     */
    private fun List<Session>.insertSorted(session: Session): List<Session> {
        val rest = filter { it.id != session.id }
        val index = rest.indexOfFirst { it.updatedAt < session.updatedAt }.takeIf { it >= 0 } ?: rest.size
        return rest.subList(0, index) + session + rest.subList(index, rest.size)
    }

    /**
     * Silent re-sync, called only from the socket-reconnect heal (the ON_START call
     * went away with ticket 112): never sets [UiState.isLoading]/[UiState.isRefreshing];
     * on success replaces the active mode's list (115); on failure the old list stays
     * and no [Effect.RefreshFailed] is emitted — no snackbar from a reconnect while
     * offline. Pull-to-refresh keeps the visible behavior via [reload].
     */
    private fun silentRefresh() {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        loadJob = viewModelScope.launch {
            uiState.update { it.copy(isLoadingMore = false, loadMoreError = null, groupExpansions = emptyMap()) }
            if (uiState.value.groupedView) {
                sessionsRepository.listSessionGroups(settingsRepository.current())
                    .onSuccess { groups ->
                        uiState.update {
                            it.copy(isLoading = false, isRefreshing = false, groups = groups, error = null)
                        }
                    }
            } else {
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
    }

    private fun reload() {
        // Cancel rather than skip so a pull-to-refresh during an in-flight load still completes.
        loadJob?.cancel()
        loadMoreJob?.cancel()
        loadJob = viewModelScope.launch {
            val hadContent = uiState.value.hasContent
            uiState.update {
                it.copy(
                    isLoading = !hadContent,
                    isRefreshing = hadContent,
                    error = null,
                    isLoadingMore = false,
                    loadMoreError = null,
                    groupExpansions = emptyMap(),
                )
            }
            // The fetch follows the active mode (115): the grouped view re-fetches the
            // groups, the flat list its first page.
            if (uiState.value.groupedView) {
                sessionsRepository.listSessionGroups(settingsRepository.current())
                    .onSuccess { groups ->
                        uiState.update {
                            it.copy(isLoading = false, isRefreshing = false, groups = groups, error = null)
                        }
                    }
                    .onFailure { error -> onReloadFailure(error, hadContent) }
            } else {
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
                    .onFailure { error -> onReloadFailure(error, hadContent) }
            }
        }
    }

    private suspend fun onReloadFailure(error: Throwable, hadContent: Boolean) {
        val message = error.toUserMessage()
        if (hadContent) {
            uiState.update { it.copy(isLoading = false, isRefreshing = false) }
            _effects.send(Effect.RefreshFailed(message))
        } else {
            uiState.update { it.copy(isLoading = false, isRefreshing = false, error = message) }
        }
    }

    companion object {
        private const val DEFAULT_NEW_SESSION_CWD = "/home/ubuntu"
        private const val CACHE_WRITE_DEBOUNCE_MS = 500L

        /** Sessions per group page: the grouped fetch's group.page_size and each
         * show-more expansion (ticket 116). Internal so the screen can place the
         * show-less threshold on the same number. */
        internal const val GROUP_PAGE_SIZE = 5
    }
}
