package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.data.chat.Todo

/**
 * The todos + jobs pills and their sheets, shared by the chat screen (above the
 * composer — tickets 042/092) and the read-only sub-agent page (its bottom bar): the
 * AnimatedVisibility pill row, the sheet-open state, and the sheets themselves
 * ([TodosSummaryRow]/[JobsSummaryRow], [TodosSheet]/[JobsSheet]). The caller only
 * supplies the data and the navigation/cancel intents.
 *
 * [items] (the caller's timeline rows) resolve a REST-seeded sub-agent job's missing
 * agentId when its sheet row is tapped (see [subagentIdForTask]); an unresolvable tap
 * is a no-op and keeps the sheet open. [onOpenJob] and [onCancelJob] are optional:
 * null hides the affordance — the sub-agent page passes null for both because REST
 * `/tasks` (the job screen's fetch, and the cancel endpoint) serves only the main
 * agent's task store, so a sub-agent's bash job has neither a job screen nor a Stop
 * (docs/server-api.md, REST /tasks).
 */
@Composable
internal fun TodoJobsDock(
    todos: List<Todo>?,
    jobs: List<Job>,
    items: List<ChatItem>?,
    onOpenSubagent: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenJob: ((String) -> Unit)? = null,
    onCancelJob: ((String) -> Unit)? = null,
) {
    var showTodos by rememberSaveable { mutableStateOf(false) }
    var showJobs by rememberSaveable { mutableStateOf(false) }
    // The agent's todo list (ticket 042) and the background-jobs counter (ticket 092)
    // share one row. The jobs pill is sticky (ticket 094): visible whenever there is
    // ANY background task, running or terminal — the sheet is the job history, and
    // the pill is its only entry point.
    val todosVisible = !todos.isNullOrEmpty()
    val jobsVisible = jobs.isNotEmpty()
    AnimatedVisibility(
        visible = todosVisible || jobsVisible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (todosVisible) {
                TodosSummaryRow(
                    todos = todos.orEmpty(),
                    onClick = { showTodos = true },
                )
            }
            if (jobsVisible) {
                JobsSummaryRow(
                    jobs = jobs,
                    onClick = { showJobs = true },
                )
            }
        }
    }
    // Re-derived from the state at open time: a clearing TodoList call while the sheet
    // is up simply closes it (there is nothing left to show). The open flag resets on
    // that transition too — otherwise the next non-empty list would pop the sheet back
    // with no tap.
    LaunchedEffect(todos.isNullOrEmpty()) {
        if (todos.isNullOrEmpty()) showTodos = false
    }
    if (showTodos && !todos.isNullOrEmpty()) {
        TodosSheet(todos = todos, onDismiss = { showTodos = false })
    }
    // The jobs sheet stays open while jobs flip terminal — recently finished jobs
    // linger in the list by design. A row tap closes it before navigating.
    if (showJobs && jobs.isNotEmpty()) {
        JobsSheet(
            jobs = jobs,
            onCancel = onCancelJob,
            onOpenSubagent = { job ->
                // A REST-seeded sub-agent job carries no agentId (REST /tasks omits
                // it): the spawning `Agent` row's result stub links task id to
                // sub-agent id, so the timeline fills the gap.
                val agentId = job.agentId ?: subagentIdForTask(items, job.taskId)
                if (agentId != null) {
                    showJobs = false
                    onOpenSubagent(agentId)
                }
            },
            onOpenJob = onOpenJob?.let { openJob ->
                { taskId ->
                    showJobs = false
                    openJob(taskId)
                }
            },
            onDismiss = { showJobs = false },
        )
    }
}
