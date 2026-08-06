package fr.louissaglio.kimi.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.chat.BackgroundTask
import fr.louissaglio.kimi.android.data.chat.ChatRepository
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
        fetch()
    }

    /** One task read. A failed poll keeps the last state (the next tick retries); the
     * entry fetch is the screen's only data source, so its failure goes full-screen. */
    private fun fetch() {
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

    /** The output poll runs exactly while (job running AND screen visible). */
    private fun syncPolling() {
        val shouldPoll = screenActive && uiState.value.status == BackgroundTask.Status.Running
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

    /** Retry of the failed entry fetch (the error state is the whole screen). */
    fun onRetry() {
        uiState.update { it.copy(isLoading = true, error = null) }
        fetch()
    }

    /** Stops the running job (`POST .../tasks/{id}:cancel`, ticket 092's endpoint):
     * 40904 = already-terminal success; any other failure snackbars. No optimistic
     * flip — the screen's own poll sees the terminal status. */
    fun onStop() {
        if (uiState.value.status != BackgroundTask.Status.Running) return
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
