package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.chat.BackgroundTask
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.TaskInfo
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.data.toUserMessage
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One background job as the jobs widget renders it (ticket 092): a background
 * sub-agent or bash task. [status] and [kind] use the REST wire vocabulary (the
 * store maps the WS core one — `killed`→Cancelled, `timed_out`/`lost`→Failed,
 * `agent`→Subagent, `process`→Bash). [agentId] links a sub-agent job to its page;
 * null on bash jobs and on REST-seeded jobs whose `task.started` predates the
 * subscription. [timedOut] retains that the terminal cause was the kill budget
 * (ticket 097): REST collapses `timed_out` to `failed`, so only a live
 * `task.terminated` sets it — REST-seeded jobs stay plain Failed (documented
 * limitation). Terminal jobs linger until the session is left — no eviction.
 * [startedAt] is null on a job healed from a transcript `task` origin (ticket
 * 099): the origin carries no timestamps, so unknown stays unknown instead of a
 * sentinel. */
data class Job(
    val taskId: String,
    val kind: BackgroundTask.Kind,
    val description: String,
    val status: BackgroundTask.Status,
    val startedAt: Instant?,
    val endedAt: Instant? = null,
    val agentId: String? = null,
    val timedOut: Boolean = false,
) {
    val isRunning: Boolean get() = status == BackgroundTask.Status.Running
}

/**
 * The jobs-widget collaborator (ticket 092 — the 065/072 pattern): the background-task
 * list backing the composer pill and its sheet. A logic holder, NOT a second state
 * source: [ChatViewModel.UiState.jobs] stays the single state holder — intents arrive
 * as calls, updates report back through [updateState] and one-shot effects through
 * [sendEffect] (the DeltaCoalescer callback pattern).
 *
 * Event-driven, NO polling (a deliberate divergence from the web client's 1 s
 * `useTaskPoller.ts`): fed by (a) the cold-start REST seed —
 * unfiltered, so already-terminal jobs linger in the sheet — (b) live
 * `task.started`/`task.terminated` events, (c) [cancel]'s optimistic flip. The seed is
 * guarded like the questions seed: a live task event landing while the fetch is in
 * flight is newer than its snapshot, so the stale replace is dropped.
 */
internal class JobsStore(
    private val scope: () -> CoroutineScope,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionId: String,
    private val state: () -> ChatViewModel.UiState,
    private val updateState: ((ChatViewModel.UiState) -> ChatViewModel.UiState) -> Unit,
    private val sendEffect: suspend (ChatViewModel.Effect) -> Unit,
    /** Every successful [seed]'s RAW task list (ticket 135), before the jobs filter —
     * the sub-agent store heals member cancelled states and model/effort pairs from
     * the subagent-kind entries the widget itself has no use for. Fed even when the
     * guarded apply goes stale: the store's writes are precedence-gated, so the
     * live event that made the seed stale (and which the store saw too) stays
     * authoritative. */
    private val onTasksSeed: (List<BackgroundTask>) -> Unit = {},
) {

    /** Bumped on every live task event, so [seed] drops a response a newer
     * task.started/task.terminated has already overtaken. */
    private val seedGuard = SeedGuard()

    /** The cold-start REST sync (unfiltered — terminal tasks stay
     * listed, so recently finished jobs linger in the sheet). REST is authoritative
     * for what it returns; a live event landing mid-fetch makes the whole apply
     * stale (SeedGuard) instead of clobbering the event's upsert. A failure leaves
     * the current list untouched — the live stream still feeds it. */
    fun seed() {
        val eventsAtSeed = seedGuard.capture()
        scope().launch {
            chatRepository.getTasks(settingsRepository.current(), sessionId, status = null)
                .onSuccess { tasks ->
                    onTasksSeed(tasks)
                    if (seedGuard.isStale(eventsAtSeed)) return@onSuccess
                    updateState { current ->
                        current.copy(
                            jobs = tasks
                                // run_in_background rides every task since 0.39.0
                                // (ticket 162): a listed foreground sub-agent carries
                                // false — never a job row (the sub-agent roster/page
                                // is its UI home).
                                .filter { it.runInBackground }
                                .map { task ->
                                    val existing = current.jobs.firstOrNull { it.taskId == task.id }
                                    // Keep the page link and the timed-out flag a live
                                    // event already recorded: REST collapses timed_out
                                    // to failed and may omit agent_id.
                                    task.toJob(
                                        agentId = task.agentId ?: existing?.agentId,
                                        timedOut = existing?.timedOut == true,
                                    )
                                },
                        )
                    }
                }
        }
    }

    /** A live `task.started`: upserts the job (a replay or a missed REST seed heals
     * here). [TaskInfo.agentId] links the REST task id to the WS sub-agent id — the
     * sheet's navigation handle; first writer wins. */
    fun onStarted(info: TaskInfo) {
        seedGuard.bump()
        upsert(info.toJob())
    }

    /** A live `task.terminated`: flips the job to its terminal status — mapped from
     * the CORE vocabulary (`killed`→Cancelled, `timed_out`/`lost`→Failed) — with its
     * [TaskInfo.endedAt]. A core `timed_out` additionally retains [Job.timedOut]
     * (ticket 097): the REST collapse to `failed` is unrecoverable, so the live
     * event is the only carrier of the timeout truth. An unknown task id still
     * upserts (the start was missed). */
    fun onTerminated(info: TaskInfo) {
        seedGuard.bump()
        upsert(info.toJob())
    }

    /**
     * Cancels one running job (`POST .../tasks/{id}:cancel`): optimistic flip to
     * Cancelled — the server's `task.terminated` confirms it. A 40904 (task already
     * terminal) is already-terminal success (web parity — useWorkspaceState.ts):
     * the optimistic flip stands. Any other failure reverts to Running and surfaces
     * a snackbar effect.
     */
    fun cancel(taskId: String) {
        val job = state().jobs.firstOrNull { it.taskId == taskId } ?: return
        if (!job.isRunning) return
        updateState { current ->
            current.copy(
                jobs = current.jobs.map { if (it.taskId == taskId) it.copy(status = BackgroundTask.Status.Cancelled) else it },
            )
        }
        scope().launch {
            chatRepository.cancelTask(settingsRepository.current(), sessionId, taskId)
                .onFailure { error ->
                    if ((error as? DataError.Api)?.code == TASK_ALREADY_TERMINAL_CODE) return@onFailure
                    updateState { current ->
                        current.copy(
                            jobs = current.jobs.map { if (it.taskId == taskId) it.copy(status = job.status) else it },
                        )
                    }
                    sendEffect(ChatViewModel.Effect.JobCancelFailed(error.toUserMessage()))
                }
        }
    }

    private fun upsert(job: Job) = updateState { current ->
        val index = current.jobs.indexOfFirst { it.taskId == job.taskId }
        if (index < 0) {
            current.copy(jobs = current.jobs + job)
        } else {
            current.copy(
                jobs = current.jobs.mapIndexed { i, existing ->
                    // The sub-agent page link and the timed-out flag survive every
                    // upsert (a live event heals a REST-seeded job, never the reverse).
                    if (i == index) {
                        job.copy(
                            agentId = job.agentId ?: existing.agentId,
                            timedOut = job.timedOut || existing.timedOut,
                        )
                    } else {
                        existing
                    }
                },
            )
        }
    }

    private companion object {
        /** Envelope code of a cancel aimed at an already-terminal task
         * (docs/server-api.md, REST /tasks). */
        const val TASK_ALREADY_TERMINAL_CODE = 40904
    }
}

private fun BackgroundTask.toJob(agentId: String?, timedOut: Boolean): Job =
    Job(id, kind, description, status, startedAt, completedAt, agentId, timedOut)

/** The live-event [Job] (ticket 092): the CORE→wire kind/status mappings, and the
 * [Job.timedOut] flag — only a `task.terminated` carrying core `timed_out` sets it
 * (REST collapses it to `failed`; a `task.started` reads Running, hence false).
 * Shared with SubagentStreamStore, whose records hold the sub-agent-owned jobs. */
internal fun TaskInfo.toJob(): Job =
    Job(
        taskId = taskId,
        kind = kind.toWireKind(),
        description = description,
        status = status.toWireStatus(),
        startedAt = startedAt,
        endedAt = endedAt,
        agentId = agentId,
        timedOut = status == TaskInfo.Status.TimedOut,
    )

/** Core→wire kind mapping (docs/server-api.md): `agent`→subagent, `process`→bash. */
private fun TaskInfo.Kind.toWireKind(): BackgroundTask.Kind = when (this) {
    TaskInfo.Kind.Agent -> BackgroundTask.Kind.Subagent
    TaskInfo.Kind.Process -> BackgroundTask.Kind.Bash
}

/** Core→wire status mapping (docs/server-api.md): `killed`→cancelled,
 * `timed_out`/`lost`→failed. Shared with SubagentStreamStore, whose transcript
 * task-origin healing (ticket 099) maps the origin's core status through it. */
internal fun TaskInfo.Status.toWireStatus(): BackgroundTask.Status = when (this) {
    TaskInfo.Status.Running -> BackgroundTask.Status.Running
    TaskInfo.Status.Completed -> BackgroundTask.Status.Completed
    TaskInfo.Status.Failed -> BackgroundTask.Status.Failed
    TaskInfo.Status.TimedOut -> BackgroundTask.Status.Failed
    TaskInfo.Status.Killed -> BackgroundTask.Status.Cancelled
    TaskInfo.Status.Lost -> BackgroundTask.Status.Failed
}
