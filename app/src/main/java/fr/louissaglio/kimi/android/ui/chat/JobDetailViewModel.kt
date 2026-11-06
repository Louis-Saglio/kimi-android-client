package fr.louissaglio.kimi.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.MAIN_AGENT_ID
import fr.louissaglio.kimi.android.data.chat.TaskControl
import fr.louissaglio.kimi.android.data.chat.TaskOutcome
import fr.louissaglio.kimi.android.data.chat.TaskOutputTail
import fr.louissaglio.kimi.android.data.chat.TaskState
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.navigation.JobDetail
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineStart
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
 * control reply whichever collection feeds the session).
 *
 * Ticket 180: the Output section streams the task's output with `task.watch` — no
 * poll. While the screen is started (ON_START..ON_STOP) and the session socket has an
 * open connection, ONE watch is sent per connection (`tail_bytes` [TAIL_BYTES]; once
 * output is held, `since_offset` = the UTF-8 byte end of it, so a reconnect or a
 * re-entry neither loses nor duplicates output). Every `task.output` chunk is appended
 * at its byte offset into a bounded [TaskOutputTail] (duplicates ignored, gaps shown
 * as a marker). ON_STOP sends `task.unwatch`; so does the task's end, once the watch's
 * first chunk (the final tail of an already-ended task) arrived — an ended task keeps
 * its last output and is never re-watched. A watch error (`40406` unknown task,
 * `50003` output unreadable) ends the watching: the Output section shows the error
 * and falls back to the task frame's `result_summary`/`error`, like a task without
 * any streamed output.
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
 * task controls are not wired — no ticket owns them yet).
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
        /** The streamed output tail (ticket 180), else the ended task frame's
         * `result_summary`/`error`; null = nothing to show. */
        val output: String? = null,
        /** The watch's error reply (`40406`/`50003`), shown above the fallback. */
        val outputError: String? = null,
        val budgetSeconds: Int? = null,
        val budgetIsDefault: Boolean = false,
        val timedOut: Boolean = false,
        /** Store-backed mode (ticket 098): the task is sub-agent-owned — status comes
         * from the SubagentStreamStore's records; no output tail and no Stop. */
        val storeBacked: Boolean = false,
        /** True while a `task.watch` waits for its first chunk with no output held
         * yet (the Output section's placeholder). */
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

    // The output watch (ticket 180) — main-thread state, mutated from viewModelScope.
    private var tail = TaskOutputTail()
    private var task: TaskState? = null
    private var started = false
    private var connectionId: Long? = null

    /** The connection the live watch was sent on; null = no watch. A newer
     * [connectionId] means the watch died with its connection. */
    private var watchedOn: Long? = null

    /** Bumped per watch so a stale reply (an unwatched or superseded watch) is ignored. */
    private var watchGeneration = 0

    /** The current watch's first chunk arrived (an ended task's final tail is in). */
    private var tailArrived = false

    /** No more watching: the ended task's output is complete, or the server refused. */
    private var watchDone = false

    init {
        if (storeBacked) {
            collectStoreJob()
        } else {
            collectTaskOutput()
            collectTaskFrames()
            collectConnection()
        }
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
                this@JobDetailViewModel.task = task
                uiState.update { it.withTask(task) }
                finishEndedWatch()
            }
        }
    }

    /** Subscribed UNDISPATCHED, before any watch can be sent: the tail chunk that
     * follows the watch reply must find this collector. */
    private fun collectTaskOutput() {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            chatRepository.observeTaskOutput(sessionId, taskId).collect { chunk ->
                tail = tail.append(chunk)
                tailArrived = true
                uiState.update { it.copy(output = outputText(), isLoading = false) }
                finishEndedWatch()
            }
        }
    }

    /** A new connection has none of the previous one's watches: watch again. */
    private fun collectConnection() {
        viewModelScope.launch {
            chatRepository.observeSocketConnection(sessionId).collect { id ->
                connectionId = id
                if (id == null) {
                    watchedOn = null
                    uiState.update { it.copy(isLoading = false) }
                } else {
                    startWatch()
                }
            }
        }
    }

    private fun UiState.withTask(task: TaskState): UiState = copy(
        description = task.description.ifBlank { null } ?: description,
        status = if (task.isRunning) Job.Status.Running else task.outcome.toJobStatus(),
        startedAt = task.startedAt ?: startedAt,
        completedAt = task.endedAt,
        command = task.command ?: command,
        output = outputText(task),
        timedOut = timedOut || task.outcome == TaskOutcome.TimedOut,
    )

    /** The streamed tail once it holds text, else the ended frame's summary/error. */
    private fun outputText(task: TaskState? = this.task): String? =
        tail.render().ifEmpty { null } ?: task?.resultSummary ?: task?.error

    /** Sends `task.watch` when the screen is started, a connection is open and it has
     * no live watch yet. The first watch asks for the last [TAIL_BYTES]; a re-watch
     * resumes at the held tail's byte end. */
    private fun startWatch() {
        val connection = connectionId
        if (!started || watchDone || connection == null || watchedOn == connection) return
        watchedOn = connection
        tailArrived = false
        val generation = ++watchGeneration
        uiState.update { it.copy(isLoading = tail.isEmpty) }
        viewModelScope.launch {
            val result = chatRepository.watchTaskOutput(sessionId, taskId, sinceOffset = tail.endOffset, tailBytes = TAIL_BYTES)
            if (generation != watchGeneration) return@launch
            result.onFailure { error ->
                if (watchedOn == connection) watchedOn = null
                if (error is DataError.Api) {
                    // 40406 unknown task, 50003 output unreadable: retrying cannot help.
                    watchDone = true
                    uiState.update { it.copy(isLoading = false, outputError = error.toUserMessage()) }
                } else {
                    // The connection dropped mid-request: the next one re-watches.
                    uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    /** An ended task streams nothing more: once the watch's first chunk is in (the
     * final tail), stop it for good. */
    private fun finishEndedWatch() {
        if (task?.isRunning != false || watchedOn == null || !tailArrived) return
        stopWatch()
        watchDone = true
    }

    private fun stopWatch() {
        if (watchedOn == null) return
        watchedOn = null
        watchGeneration++
        chatRepository.unwatchTaskOutput(sessionId, taskId)
        uiState.update { it.copy(isLoading = false) }
    }

    /** Wired to ON_START. Holds the chat's socket gate open (the sub-agent page
     * mechanism): the task frames keep arriving while this screen is on top. In
     * frame-backed mode it also (re-)starts the output watch (ticket 180). */
    fun onScreenStarted() {
        subagentStore.pageOpened(sessionId)
        if (storeBacked) return
        started = true
        startWatch()
    }

    /** Wired to ON_STOP: `task.unwatch` first, while the connection is still held. */
    fun onScreenStopped() {
        if (!storeBacked) {
            started = false
            stopWatch()
        }
        subagentStore.pageClosed(sessionId)
    }

    override fun onCleared() {
        stopWatch()
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

    internal companion object {
        /** The first chunk a watch asks for: the old `output_preview` cap. */
        const val TAIL_BYTES = 32 * 1024
    }
}
