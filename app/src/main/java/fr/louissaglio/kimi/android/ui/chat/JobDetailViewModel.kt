package fr.louissaglio.kimi.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.chat.BackgroundTask
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.MAIN_AGENT_ID
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.navigation.JobDetail
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background-bash job screen (ticket 096): one task's information and its live output
 * tail, fetched from `GET .../tasks/{id}?with_output=true` — there are NO
 * `tool.progress` frames for background bash (docs/server-api.md, REST /tasks), so
 * polling this REST endpoint is the only live-output channel. The ~2 s poll runs only
 * while the job is running AND the screen is visible: ticket 092's no-polling
 * doctrine covered the jobs *list*; here it is scoped to screen visibility via the
 * ON_START/ON_STOP pair (the SubagentViewModel precedent). The WS `task.terminated`
 * reconciles the composer pill/sheet; this screen's own poll sees the terminal
 * status.
 *
 * Ticket 097: the timeout budget and the timed-out flag arrive as route arguments
 * (resolved from the chat's state at navigation time) — REST never exposes the
 * timeout and collapses `timed_out` to `failed`, so this screen's poll alone could
 * show neither. The description and command arrive the same way (the tapped row
 * already carries the call input), seeding the title and the Command section before
 * the entry fetch answers; the fetch stays authoritative — REST's description is
 * the server's (`Bash: <command>…` invented when the agent set none) — and
 * overwrites both. ON_START/ON_STOP also register the page with the socket gate's
 * session page count (the sub-agent page mechanism): the chat socket stays collected
 * while this screen is on top, so a live `task.terminated` still reaches the jobs
 * store — the sheet (and the next entry here) learn the timed-out truth.
 *
 * Ticket 098: TWO data modes, split on the route's `ownerAgentId`. null/"main" is
 * the REST-backed mode above. Anything else is the store-backed mode: a
 * sub-agent-owned task is live-only by protocol — REST `/tasks` resolves only the
 * main agent's task service (get and `:cancel` alike answer 40406), and no output
 * channel exists (docs/server-api.md, Background lifecycle) — so status/startedAt/
 * completedAt/timedOut come from the owning record's live jobs in
 * [SubagentStreamStore] (fed by `task.started`/`task.terminated`), collected while
 * the screen lives; the screen NEVER calls getTask/cancelTask in this mode, the
 * Output section shows an explicit unavailable placeholder, and Stop is hidden.
 * The socket-gate registration stays: it is what keeps the live `task.terminated`
 * frames reaching the store while the screen is on top.
 */
@HiltViewModel
class JobDetailViewModel internal constructor(
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val subagentStore: SubagentStreamStore,
    routeArgs: JobDetail,
    private val pollIntervalMillis: Long = POLL_INTERVAL_MILLIS,
    private val pollDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {

    // Production entry point (Hilt): the destination's SavedStateHandle is decoded
    // from the typed route (ticket 083). Local unit tests call the primary
    // constructor with the route object directly (the SubagentViewModel pattern) —
    // toRoute's decode path needs a real android.os.Bundle. They also inject a short
    // interval and a real-time dispatcher: delay on the test Main dispatcher never
    // fires outside runTest (verified on-device of the test harness).
    @Inject
    constructor(
        chatRepository: ChatRepository,
        settingsRepository: SettingsRepository,
        subagentStore: SubagentStreamStore,
        savedStateHandle: SavedStateHandle,
    ) : this(chatRepository, settingsRepository, subagentStore, savedStateHandle.toRoute<JobDetail>())

    private val sessionId: String = routeArgs.sessionId
    private val taskId: String = routeArgs.taskId

    /** The store-backed mode split (ticket 098): a sub-agent-owned task id — REST
     * `/tasks` 40406s those (docs/server-api.md, Background lifecycle), so getTask/
     * cancelTask must never run for them. */
    private val ownerAgentId: String? = routeArgs.ownerAgentId
    private val storeBacked = ownerAgentId != null && ownerAgentId != MAIN_AGENT_ID

    /** [status] null = nothing fetched yet (the entry fetch is running, or failed).
     * [budgetSeconds]/[budgetIsDefault]/[timedOut] are the ticket-097 route
     * arguments: the effective kill budget (null = no budget UI), whether it is the
     * harness's 600 s default (labeled), and whether a live `task.terminated`
     * recorded the terminal cause as a timeout. [description]/[command] are seeded
     * from the route arguments as well (the tapped row's call input) so the title
     * and the Command section render before the entry fetch; the fetch overwrites
     * both — REST is authoritative. */
    data class UiState(
        val taskId: String,
        val description: String? = null,
        val status: BackgroundTask.Status? = null,
        val startedAt: Instant? = null,
        val completedAt: Instant? = null,
        val command: String? = null,
        val outputPreview: String? = null,
        val budgetSeconds: Int? = null,
        val budgetIsDefault: Boolean = false,
        val timedOut: Boolean = false,
        /** Store-backed mode (ticket 098): the task is sub-agent-owned — status comes
         * from the SubagentStreamStore's live frames; no output tail and no Stop
         * exist for it (no wire channel does). */
        val storeBacked: Boolean = false,
        /** True while the entry fetch runs — only the Output section waits on it
         * (a placeholder); the seeded title/command render already. */
        val isLoading: Boolean = true,
        /** The entry fetch failed (unknown task / fetch failure) — full-screen error. */
        val error: String? = null,
    )

    val uiState: StateFlow<UiState>
        field = MutableStateFlow(
            UiState(
                taskId = taskId,
                budgetSeconds = routeArgs.budgetSeconds,
                budgetIsDefault = routeArgs.budgetIsDefault,
                timedOut = routeArgs.timedOut,
                description = routeArgs.description,
                command = routeArgs.command,
                storeBacked = storeBacked,
                // The store-backed mode has no entry fetch: nothing ever loads.
                isLoading = !storeBacked,
            ),
        )

    /** One-shot snackbars (the ChatViewModel.Effect pattern). */
    sealed interface Effect {
        val message: String

        /** The Stop request failed — the job is presumably still running. */
        data class StopFailed(override val message: String) : Effect
    }

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private var screenActive = false
    private var pollJob: Job? = null

    init {
        if (storeBacked) collectStoreJob() else fetch()
    }

    /** Store-backed mode (ticket 098): the owning record's live jobs are the ONLY
     * status channel — fed by `task.started`/`task.terminated` while the screen's
     * socket-gate registration keeps the session socket collected, and healed on a
     * cold open by the transcript's `task` turn origins (ticket 099 — the sub-agent
     * page's transcript fetch rides the same store). The seeded title/command/budget
     * stay the route's; a screen whose task has neither a live frame nor a fetched
     * transcript page yet shows those seeds with an empty status line — never a
     * full-screen error. */
    private fun collectStoreJob() {
        val owner = ownerAgentId ?: return
        viewModelScope.launch {
            subagentStore.entry(sessionId, owner).collect { entry ->
                val job = entry?.jobs?.firstOrNull { it.taskId == taskId } ?: return@collect
                uiState.update {
                    it.copy(
                        status = job.status,
                        startedAt = job.startedAt,
                        completedAt = job.endedAt,
                        timedOut = it.timedOut || job.timedOut,
                    )
                }
            }
        }
    }

    /** One task read. A failed poll keeps the last state (the next tick retries); the
     * entry fetch is the screen's only data source, so its failure goes full-screen. */
    private fun fetch() {
        // A sub-agent-owned task is never fetched: REST /tasks 40406s it.
        if (storeBacked) return
        viewModelScope.launch {
            chatRepository.getTask(settingsRepository.current(), sessionId, taskId)
                .onSuccess { task ->
                    uiState.update {
                        it.copy(
                            description = task.description,
                            status = task.status,
                            startedAt = task.startedAt,
                            completedAt = task.completedAt,
                            command = task.command,
                            outputPreview = task.outputPreview,
                            isLoading = false,
                            error = null,
                        )
                    }
                    syncPolling()
                }
                .onFailure { error ->
                    if (uiState.value.status == null) {
                        uiState.update { it.copy(isLoading = false, error = error.toUserMessage()) }
                    }
                }
        }
    }

    /** Wired to ON_START. Also holds the chat's socket gate open (the sub-agent page
     * mechanism): a live `task.terminated` keeps reaching the jobs store while this
     * screen is on top, so the session learns a timeout kill's truth. */
    fun onScreenStarted() {
        subagentStore.pageOpened(sessionId)
        screenActive = true
        syncPolling()
    }

    /** Wired to ON_STOP. */
    fun onScreenStopped() {
        subagentStore.pageClosed(sessionId)
        screenActive = false
        syncPolling()
    }

    /** The output poll runs exactly while (job running AND screen visible); the
     * store-backed mode never polls — it has no output endpoint. */
    private fun syncPolling() {
        val shouldPoll = !storeBacked && screenActive && uiState.value.status == BackgroundTask.Status.Running
        if (shouldPoll && pollJob?.isActive != true) {
            pollJob = viewModelScope.launch(pollDispatcher) {
                while (isActive) {
                    delay(pollIntervalMillis)
                    fetch()
                }
            }
        } else if (!shouldPoll) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    /** Retry of the failed entry fetch (the error state is the whole screen). The
     * store-backed mode has no fetch to retry — and never enters the error state. */
    fun onRetry() {
        if (storeBacked) return
        uiState.update { it.copy(isLoading = true, error = null) }
        fetch()
    }

    /** Stops the running job (`POST .../tasks/{id}:cancel`, ticket 092's endpoint):
     * 40904 = already-terminal success; any other failure snackbars. No optimistic
     * flip — the screen's own poll sees the terminal status. Never in store-backed
     * mode: REST `:cancel` 40406s a sub-agent-owned task (the UI hides Stop there). */
    fun onStop() {
        if (storeBacked || uiState.value.status != BackgroundTask.Status.Running) return
        viewModelScope.launch {
            chatRepository.cancelTask(settingsRepository.current(), sessionId, taskId)
                .onFailure { error ->
                    if ((error as? DataError.Api)?.code == TASK_ALREADY_TERMINAL_CODE) return@onFailure
                    _effects.send(Effect.StopFailed(error.toUserMessage()))
                }
        }
    }

    private companion object {
        /** The output poll cadence (ticket 096). */
        const val POLL_INTERVAL_MILLIS = 2_000L

        /** Envelope code of a cancel aimed at an already-terminal task
         * (docs/server-api.md, REST /tasks) — tolerated as success. */
        const val TASK_ALREADY_TERMINAL_CODE = 40904
    }
}
