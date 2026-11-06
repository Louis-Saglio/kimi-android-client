package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.ui.common.formatElapsed
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * The collapsed jobs widget (ticket 092), pinned by [TodoJobsDock] next to Todos
 * (above the chat composer, and at the sub-agent page's bottom bar):
 * the finished/total background-task count (`Jobs · 2/4` — finished = completed,
 * cancelled, or failed; the Todos done/total polarity, Louis's call after on-device
 * testing of the running/total variant), sticky since ticket 094 — the caller keeps
 * it visible whenever the session has any background task, running or terminal (the
 * sheet is the session's job history and this pill is its only entry point;
 * deliberate divergence from the web dock, whose pills vanish at zero). Same visual
 * recipe as TodosSummaryRow (surfaceContainerHigh, 48.dp target, merged semantics).
 * Terminal jobs don't tick here — the sheet owns the elapsed times.
 */
@Composable
internal fun JobsSummaryRow(
    jobs: List<Job>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val finished = jobs.count { !it.isRunning }
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Jobs, $finished of ${jobs.size} finished, tap to view"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Jobs · $finished/${jobs.size}",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * The jobs sheet (ticket 092): every background task of the session — running first
 * (oldest first), then terminal (newest first) — with its kind badge, plain-English
 * status, and ticking elapsed time (startedAt → endedAt ?: now). Running rows get a
 * trailing Stop button (`task.stop`) when [onCancel] is non-null (null = read-only
 * sheet — the sub-agent page's own task controls are ticket 182's); tapping a
 * sub-agent row opens its page, tapping a bash row opens its job screen (ticket 096)
 * when [onOpenJob] is non-null (null = the row is not tappable). The sub-agent tap
 * reports the whole [Job]: a transcript-healed job (ticket 099) carries no agentId,
 * so the caller resolves it from the timeline when the job itself doesn't — a
 * frame-fed one carries the frame's `child_agent_id`. Same transient-detail pattern
 * as TodosSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JobsSheet(
    jobs: List<Job>,
    onCancel: ((String) -> Unit)?,
    onOpenSubagent: (Job) -> Unit,
    onOpenJob: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    // The ticking clock (the web client's taskClock model): ticks only while the
    // sheet is open; the collapsed pill never ticks (count only, no per-job time).
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = Instant.now()
        }
    }
    val runningJobs = jobs.filter { it.isRunning }.sortedBy { it.startedAt }
    val terminalJobs = jobs.filterNot { it.isRunning }.sortedByDescending { it.endedAt ?: it.startedAt }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                text = "Jobs · ${runningJobs.size} running",
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            (runningJobs + terminalJobs).forEach { job ->
                JobRow(
                    job = job,
                    now = now,
                    onCancel = onCancel,
                    onOpenSubagent = onOpenSubagent,
                    onOpenJob = onOpenJob,
                )
            }
        }
    }
}

private val TASK_ID_PREFIX = "task_id:"

/** Resolves a background sub-agent job's page id from the timeline ([TodoJobsDock]'s
 * jobs-sheet tap navigation) when the job carries no agentId (a transcript-healed
 * job — ticket 099 — has no `child_agent_id`): the spawning `Agent`
 * row's result stub carries a `task_id: <taskId>` line and the row is linked to its
 * sub-agent ([ChatItem.ToolActivity.subagentId]). Null when the row is not among the
 * loaded history pages — the tap is then a no-op. */
internal fun subagentIdForTask(items: List<ChatItem>?, taskId: String): String? =
    items?.filterIsInstance<ChatItem.ToolGroup>()
        ?.flatMap { it.tools }
        ?.firstOrNull { row ->
            row.subagentId != null && row.output.lineSequence().any { line ->
                line.startsWith(TASK_ID_PREFIX) && line.removePrefix(TASK_ID_PREFIX).trim() == taskId
            }
        }
        ?.subagentId

/** One sheet row: description over a "badge · status · elapsed" secondary line,
 * and a trailing Stop for running rows when [onCancel] is non-null. No status glyph —
 * the plain-English status on the secondary line carries it (Louis's call, ticket
 * 094). Sub-agent rows are tappable (the page navigation — the whole [Job] is
 * reported so the caller can resolve a healed row's missing agentId); bash rows
 * are tappable when [onOpenJob] is non-null (ticket 096's job screen). */
@Composable
private fun JobRow(
    job: Job,
    now: Instant,
    onCancel: ((String) -> Unit)?,
    onOpenSubagent: (Job) -> Unit,
    onOpenJob: ((String) -> Unit)?,
) {
    val kindLabel = when (job.kind) {
        Job.Kind.Subagent -> "sub-agent"
        Job.Kind.Bash -> "bash"
    }
    val statusLabel = when {
        job.timedOut -> "timed out"
        else -> when (job.status) {
            Job.Status.Running -> "running"
            Job.Status.Completed -> "completed"
            Job.Status.Failed -> "failed"
            Job.Status.Cancelled -> "cancelled"
        }
    }
    // Null on a transcript-healed job (ticket 099): the task origin carries no
    // timestamps — the row then shows the bare status, never a fake elapsed.
    val elapsed = job.startedAt?.let { formatElapsed(Duration.between(it, job.endedAt ?: now)) }
    // A live-recorded timeout (ticket 097) reads "timed out after <elapsed>" in error
    // color (outcome `timed_out`, which the status collapses to Failed).
    val statusText = when {
        job.timedOut && elapsed != null -> "timed out after $elapsed"
        job.timedOut -> "timed out"
        elapsed != null -> "$statusLabel · $elapsed"
        else -> statusLabel
    }
    val rowDescription = when {
        job.timedOut -> "${job.description}, $kindLabel, $statusText"
        elapsed != null -> "${job.description}, $kindLabel, $statusLabel, elapsed $elapsed"
        else -> "${job.description}, $kindLabel, $statusLabel"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .let {
                when {
                    job.kind == Job.Kind.Subagent ->
                        it.clickable { onOpenSubagent(job) }
                    job.kind == Job.Kind.Bash && onOpenJob != null ->
                        it.clickable { onOpenJob(job.taskId) }
                    else -> it
                }
            }
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = rowDescription
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = job.description.ifBlank { job.taskId },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = kindLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (job.timedOut) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (job.isRunning && onCancel != null) {
            IconButton(onClick = { onCancel(job.taskId) }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Stop ${job.description}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
