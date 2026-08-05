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
import fr.louissaglio.kimi.android.data.chat.BackgroundTask
import fr.louissaglio.kimi.android.ui.common.formatElapsed
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * The collapsed jobs widget (ticket 092), pinned in the composer row next to Todos:
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
 * status, and ticking elapsed time (startedAt → endedAt ?: now). Running rows get a trailing
 * Stop button; tapping a sub-agent row with a known agentId opens its page, tapping a
 * bash row opens its job screen (ticket 096). Same transient-detail pattern as TodosSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JobsSheet(
    jobs: List<Job>,
    onCancel: (String) -> Unit,
    onOpenSubagent: (String) -> Unit,
    onOpenJob: (String) -> Unit = {},
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

/** One sheet row: description over a "badge · status · elapsed" secondary line,
 * and a trailing Stop for running rows. No status glyph — the plain-English status
 * on the secondary line carries it (Louis's call, ticket 094). Sub-agent rows with
 * a known [Job.agentId] are tappable (the page navigation); bash rows are tappable
 * too (ticket 096's job screen — [Job.taskId] is always known). */
@Composable
private fun JobRow(
    job: Job,
    now: Instant,
    onCancel: (String) -> Unit,
    onOpenSubagent: (String) -> Unit,
    onOpenJob: (String) -> Unit,
) {
    val kindLabel = when (job.kind) {
        BackgroundTask.Kind.Subagent -> "sub-agent"
        BackgroundTask.Kind.Bash -> "bash"
    }
    val statusLabel = when {
        job.timedOut -> "timed out"
        else -> when (job.status) {
            BackgroundTask.Status.Running -> "running"
            BackgroundTask.Status.Completed -> "completed"
            BackgroundTask.Status.Failed -> "failed"
            BackgroundTask.Status.Cancelled -> "cancelled"
        }
    }
    val elapsed = formatElapsed(Duration.between(job.startedAt, job.endedAt ?: now))
    // A live-recorded timeout (ticket 097) reads "timed out after <elapsed>" in error
    // color; a REST-seeded job can't tell timeout from crash and stays plain "failed".
    val statusText = if (job.timedOut) "timed out after $elapsed" else "$statusLabel · $elapsed"
    val rowDescription = if (job.timedOut) {
        "${job.description}, $kindLabel, $statusText"
    } else {
        "${job.description}, $kindLabel, $statusLabel, elapsed $elapsed"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .let {
                when {
                    job.kind == BackgroundTask.Kind.Subagent && job.agentId != null ->
                        it.clickable { onOpenSubagent(job.agentId) }
                    job.kind == BackgroundTask.Kind.Bash ->
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
        if (job.isRunning) {
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
