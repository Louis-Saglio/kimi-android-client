package fr.louissaglio.kimi.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.MAIN_AGENT_ID
import fr.louissaglio.kimi.android.data.chat.TaskControl
import fr.louissaglio.kimi.android.data.chat.TaskOutcome
import fr.louissaglio.kimi.android.data.chat.TaskState
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.navigation.JobDetail
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Background-bash job screen (ticket 096): one task's information. Since server 2.5.0
 * (ticket 179) there is no REST task read: the status, timestamps, description and
 * command come from the task's `task` frames — the session socket's latest state
 * ([ChatRepository.observeTask], recorded from every `task.list`/`task` frame and
 * control reply whichever collection feeds the session). The live output tail is
 * ticket 180's `task.watch`; until then the Output section shows the ended task's
 * `result_summary`/`error` when the frame carries one, else "No output yet".
 *
 * Ticket 097: the timeout budget and the timed-out flag arrive as route arguments
 * (resolved from the chat's state at navigation time) — no frame exposes the budget.
 * The description and command arrive the same way (the tapped row already carries
 * the call input), seeding the title and the Command section before any frame; a
 * frame's non-blank description/command overwrite them (the server's — `Bash:
 * <command>…` when the agent set none). ON_START/ON_STOP also register the page with
 * the socket gate's session page count (the sub-agent page mechanism): the session
 * socket stays collected while this screen is on top, so the task frames keep coming.
 *
 * Ticket 098: TWO data modes, split on the route's `ownerAgentId`. null/"main" is
 * the frame-backed mode above, with Stop (`task.stop`). Anything else is the
 * store-backed mode: a sub-agent-owned task — status/startedAt/completedAt/timedOut
 * come from the owning record's jobs in [SubagentStreamStore] (fed by the child's
 * task frames, healed by transcript task origins), the Output section shows an
 * explicit unavailable placeholder, and Stop is hidden (the sub-agent page's own
 * task controls are ticket 182's).
 */
@HiltViewModel
class JobDetailViewModel internal constructor(
    private val chatRepository: ChatRepository,
    private val subagentStore: SubagentStreamStore,
    routeArgs: JobDetail,
) : ViewModel() {

    // Production entry point (Hilt): the destination's SavedStateHandle is decoded
    // from the typed route (ticket 083). Local unit tests call the primary
    // constructor with the route object directly (the SubagentViewModel pattern) —
    // toRoute's decode path needs a real android.os.Bundle.
    @Inject
    constructor(
        chatRepository: ChatRepository,
        subagentStore: SubagentStreamStore,
        savedStateHandle: SavedStateHandle,
    ) : this(chatRepository, subagentStore, savedStateHandle.toRoute<JobDetail>())

    private val sessionId: String = routeArgs.sessionId
    private val taskId: String = routeArgs.taskId

    /** The store-backed mode split (ticket 098): a sub-agent-owned task id. */
    private val ownerAgentId: String? = routeArgs.ownerAgentId
    private val storeBacked = ownerAgentId != null && ownerAgentId != MAIN_AGENT_ID

    /** [status] null = no task state seen yet. [budgetSeconds]/[budgetIsDefault]/
     * [timedOut] are the ticket-097 route arguments: the effective kill budget (null =
     * no budget UI), whether it is the harness's 600 s default (labeled), and whether
     * the task ended `timed_out` (a frame sets it too). [description]/[command] are
     * seeded from the route arguments as well (the tapped row's call input) so the
     * title and the Command section render before any frame; a frame overwrites both
     * when it carries them. */
    data class UiState(
        val taskId: String,
        val description: String? = null,
        val status: Job.Status? = null,
        val startedAt: Instant? = null,
        val completedAt: Instant? = null,
        val command: String? = null,
        val outputPreview: String? = null,
        val budgetSeconds: Int? = null,
        val budgetIsDefault: Boolean = false,
        val timedOut: Boolean = false,
        /** Store-backed mode (ticket 098): the task is sub-agent-owned — status comes
         * from the SubagentStreamStore's records; no output tail and no Stop. */
        val storeBacked: Boolean = false,
        /** True while the Output section waits for its source (a placeholder). No
         * output channel exists before ticket 180's `task.watch`, so nothing waits
         * yet: always false. */
        val isLoading: Boolean = false,
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

    init {
        if (storeBacked) collectStoreJob() else collectTaskFrames()
    }

    /** Store-backed mode (ticket 098): the owning record's jobs are the ONLY status
     * channel — fed by the child's task frames while the screen's socket-gate
     * registration keeps the session socket collected, and healed on a cold open by
     * the transcript's `task` turn origins (ticket 099 — the sub-agent page's
     * transcript fetch rides the same store). The seeded title/command/budget stay
     * the route's; a screen whose task has neither a frame nor a fetched transcript
     * page yet shows those seeds with an empty status line. */
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

    /** Frame-backed mode (ticket 179): every state the socket records for the main
     * agent's task replaces the screen's task fields. A task this process never saw a
     * frame for (it ended before the subscription — ended tasks are never re-listed)
     * keeps the route seeds with an empty status line. */
    private fun collectTaskFrames() {
        viewModelScope.launch {
            chatRepository.observeTask(sessionId, MAIN_AGENT_ID, taskId).filterNotNull().collect { task ->
                uiState.update { it.withTask(task) }
            }
        }
    }

    private fun UiState.withTask(task: TaskState): UiState = copy(
        description = task.description.ifBlank { null } ?: description,
        status = if (task.isRunning) Job.Status.Running else task.outcome.toJobStatus(),
        startedAt = task.startedAt ?: startedAt,
        completedAt = task.endedAt,
        command = task.command ?: command,
        outputPreview = task.resultSummary ?: task.error,
        timedOut = timedOut || task.outcome == TaskOutcome.TimedOut,
    )

    /** Wired to ON_START. Holds the chat's socket gate open (the sub-agent page
     * mechanism): the task frames keep arriving while this screen is on top. */
    fun onScreenStarted() {
        subagentStore.pageOpened(sessionId)
    }

    /** Wired to ON_STOP. */
    fun onScreenStopped() {
        subagentStore.pageClosed(sessionId)
    }

    /** Stops the running job with the flat `task.stop` control (ticket 179). No
     * optimistic flip — the ended state (the reply's, then the `task` frame) reaches
     * this screen through the socket's task record. An error reply or a dropped socket
     * snackbars. Never in store-backed mode (the UI hides Stop there). */
    fun onStop() {
        if (storeBacked || uiState.value.status != Job.Status.Running) return
        viewModelScope.launch {
            chatRepository.sendTaskControl(sessionId, TaskControl.Stop, taskId)
                .onFailure { error -> _effects.send(Effect.StopFailed(error.toUserMessage())) }
        }
    }
}
