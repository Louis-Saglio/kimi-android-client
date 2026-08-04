package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
 * the RUNNING background-task count only — hidden entirely when zero (web dock
 * parity; a permanent "Jobs · 0" clutters the composer). Same visual recipe as
 * TodosSummaryRow (surfaceContainerHigh, 48.dp target, merged semantics). Terminal
 * jobs don't tick here — the sheet owns the elapsed times.
 */
@Composable
internal fun JobsSummaryRow(
    jobs: List<Job>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = jobs.count { it.isRunning }
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Jobs, $running running, tap to view"
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
            text = "Jobs · $running",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * The jobs sheet (ticket 092): every background task of the session — running first
 * (oldest first), then terminal (newest first) — with its kind badge, status glyph,
 * and ticking elapsed time (startedAt → endedAt ?: now). Running rows get a trailing
 * Stop button; tapping a sub-agent row with a known agentId opens its page. Same
 * transient-detail pattern as TodosSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JobsSheet(
    jobs: List<Job>,
    onCancel: (String) -> Unit,
    onOpenSubagent: (String) -> Unit,
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
                )
            }
        }
    }
}

/** One sheet row: status glyph, description over a "badge · status · elapsed"
 * secondary line, and a trailing Stop for running rows. Sub-agent rows with a known
 * [Job.agentId] are tappable (the page navigation). */
@Composable
private fun JobRow(
    job: Job,
    now: Instant,
    onCancel: (String) -> Unit,
    onOpenSubagent: (String) -> Unit,
) {
    val kindLabel = when (job.kind) {
        BackgroundTask.Kind.Subagent -> "sub-agent"
        BackgroundTask.Kind.Bash -> "bash"
    }
    val statusLabel = when (job.status) {
        BackgroundTask.Status.Running -> "running"
        BackgroundTask.Status.Completed -> "completed"
        BackgroundTask.Status.Failed -> "failed"
        BackgroundTask.Status.Cancelled -> "cancelled"
    }
    val elapsed = formatElapsed(Duration.between(job.startedAt, job.endedAt ?: now))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .let {
                if (job.kind == BackgroundTask.Kind.Subagent && job.agentId != null) {
                    it.clickable { onOpenSubagent(job.agentId) }
                } else {
                    it
                }
            }
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${job.description}, $kindLabel, $statusLabel, elapsed $elapsed"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        JobStatusIcon(job.status)
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
                    text = "$statusLabel · $elapsed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** Status glyph for a sheet row: a filled primary dot for running, a muted ring for
 * terminal — drawn, like TodoStatusIcon's rings. The description feeds the merged
 * row semantics. */
@Composable
private fun JobStatusIcon(status: BackgroundTask.Status) {
    when (status) {
        BackgroundTask.Status.Running -> Box(
            modifier = Modifier
                .size(20.dp)
                .semantics { contentDescription = "Running" }
                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                .padding(5.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        else -> Box(
            modifier = Modifier
                .size(20.dp)
                .semantics { contentDescription = "Finished" }
                .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
        )
    }
}
