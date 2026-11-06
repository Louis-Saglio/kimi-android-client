package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.TaskControl
import fr.louissaglio.kimi.android.data.chat.TaskOutcome
import fr.louissaglio.kimi.android.data.chat.TaskState
import fr.louissaglio.kimi.android.data.toUserMessage
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One background job as the jobs widget renders it (ticket 092): a background
 * sub-agent or shell task — mapped from the task frame by [toBackgroundJob] (ticket
 * 179). [agentId] links a sub-agent job to its page (the frame's `child_agent_id`);
 * null on bash jobs. [timedOut] retains that the terminal cause was the kill budget
 * (ticket 097 — outcome `timed_out`, which [status] collapses to Failed). Terminal
 * jobs linger until the session is left — no eviction. [startedAt] is null when the
 * frame carried none, and on a job healed from a transcript `task` origin (ticket
 * 099): unknown stays unknown instead of a sentinel. */
data class Job(
    val taskId: String,
    val kind: Kind,
    val description: String,
    val status: Status,
    val startedAt: Instant?,
    val endedAt: Instant? = null,
    val agentId: String? = null,
    val timedOut: Boolean = false,
) {
    /** `shell` → [Bash], `subagent` → [Subagent]; `other` tasks are never jobs. */
    enum class Kind { Subagent, Bash }

    /** `running` → [Running]; `ended` + outcome `completed` → [Completed], `killed` →
     * [Cancelled], `failed`/`timed_out`/`lost` (or a missing outcome) → [Failed]. */
    enum class Status { Running, Completed, Failed, Cancelled }

    val isRunning: Boolean get() = status == Status.Running
}

/**
 * The jobs-widget collaborator (ticket 092 — the 065/072 pattern): the background-task
 * list backing the composer pill and its sheet. A logic holder, NOT a second state
 * source: [ChatViewModel.UiState.jobs] stays the single state holder — intents arrive
 * as calls, updates report back through [updateState] and one-shot effects through
 * [sendEffect] (the DeltaCoalescer callback pattern).
 *
 * Event-driven, NO polling, no REST (ticket 179 — server 2.5.0 removed `/tasks`): fed
 * by the main agent's `task.list` (every connection's running tasks, before the
 * subscribe ack — the cold-start seed and the reconnect heal, [onListed]) and its
 * full-state `task` frames ([onUpdated]), a task control's replied state included.
 * Only background tasks are jobs ([toBackgroundJob]): a foreground task's tool call
 * is its UI home until `task.background` detaches it.
 */
internal class JobsStore(
    private val scope: () -> CoroutineScope,
    private val chatRepository: ChatRepository,
    private val sessionId: String,
    private val state: () -> ChatViewModel.UiState,
    private val updateState: ((ChatViewModel.UiState) -> ChatViewModel.UiState) -> Unit,
    private val sendEffect: suspend (ChatViewModel.Effect) -> Unit,
) {

    /** A main-agent `task.list`: authoritative for RUNNING tasks only — merged, never a
     * replace. Listed jobs upsert; a job still Running here but absent from the list
     * ended while no connection watched it (the server never re-sends an ended task),
     * so its outcome is unknown and it is dropped rather than shown as running forever
     * or with an invented outcome. Terminal jobs from earlier frames stay. */
    fun onListed(tasks: List<TaskState>) {
        val listed = tasks.mapNotNull { it.toBackgroundJob() }
        val listedIds = listed.mapTo(HashSet()) { it.taskId }
        updateState { current ->
            val kept = current.jobs.filter { !it.isRunning || it.taskId in listedIds }
            current.copy(jobs = listed.fold(kept, ::upsert))
        }
    }

    /** A main-agent `task` frame: the job's full state, upserted in place (a new job
     * appends). A non-job frame (`other` kind, a foreground task) changes nothing. */
    fun onUpdated(task: TaskState) {
        val job = task.toBackgroundJob() ?: return
        updateState { current -> current.copy(jobs = upsert(current.jobs, job)) }
    }

    /**
     * Stops one running job with the flat `task.stop` control (docs/server-api.md →
     * WebSocket → Control frames). No optimistic flip: the job turns Cancelled when the
     * ended state lands — the code-0 reply's (`outcome:"killed"`) or the `task` frame
     * that follows, both through [onUpdated]. An error reply (`40933`, `40406`, …) or
     * a dropped socket changes nothing and surfaces a snackbar effect.
     */
    fun cancel(taskId: String) {
        val job = state().jobs.firstOrNull { it.taskId == taskId } ?: return
        if (!job.isRunning) return
        scope().launch {
            chatRepository.sendTaskControl(sessionId, TaskControl.Stop, taskId)
                .onFailure { error -> sendEffect(ChatViewModel.Effect.JobCancelFailed(error.toUserMessage())) }
        }
    }

    /** Replaces [job]'s entry, keeping its position; the sub-agent page link survives a
     * later frame that omits it. */
    private fun upsert(jobs: List<Job>, job: Job): List<Job> {
        val index = jobs.indexOfFirst { it.taskId == job.taskId }
        if (index < 0) return jobs + job
        return jobs.mapIndexed { i, existing ->
            if (i == index) job.copy(agentId = job.agentId ?: existing.agentId) else existing
        }
    }
}

/** The task frame → the jobs widget's [Job] (ticket 179), or null when the task is no
 * job: an `other`-kind task (e.g. a background question — its card is the UI) or a
 * foreground (`detached:false`) one, whose waiting tool call already renders it.
 * Shared with SubagentStreamStore, whose records hold the sub-agent-owned jobs. */
internal fun TaskState.toBackgroundJob(): Job? {
    val kind = when (kind) {
        TaskState.Kind.Shell -> Job.Kind.Bash
        TaskState.Kind.Subagent -> Job.Kind.Subagent
        TaskState.Kind.Other -> return null
    }
    if (!detached) return null
    return Job(
        taskId = taskId,
        kind = kind,
        description = description,
        status = if (isRunning) Job.Status.Running else outcome.toJobStatus(),
        startedAt = startedAt,
        endedAt = endedAt,
        agentId = childAgentId,
        timedOut = outcome == TaskOutcome.TimedOut,
    )
}

/** An ended task's outcome → the job's terminal status: `killed` → Cancelled,
 * `completed` → Completed, everything else (`failed`, `timed_out`, `lost`, or an
 * outcome this build does not know) → Failed. Shared with SubagentStreamStore, whose
 * transcript task-origin healing (ticket 099) maps the origin's outcome through it. */
internal fun TaskOutcome?.toJobStatus(): Job.Status = when (this) {
    TaskOutcome.Completed -> Job.Status.Completed
    TaskOutcome.Killed -> Job.Status.Cancelled
    TaskOutcome.Failed, TaskOutcome.TimedOut, TaskOutcome.Lost, null -> Job.Status.Failed
}
