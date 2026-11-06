package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.louissaglio.kimi.android.ui.common.formatElapsed
import fr.louissaglio.kimi.android.ui.theme.successColor
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * Background-bash job screen (ticket 096): the job's description in the title, a
 * status line (glyph + label + elapsed — ticking every second while running), the
 * command, and the Output section (the `task.watch` stream's live tail — ticket 180 —
 * pinned to the newest output; the ended task's summary when nothing streamed). Stop lives in the app bar while
 * running: killing a runaway job is the natural reaction while watching it. Entry
 * points: a linked chat row or the jobs sheet's bash rows — the chat row itself
 * stays free of the log. No full-screen loading gate: the title and the Command
 * section render immediately from the route arguments (the tapped row's call
 * input); the status line waits for the task's frame (it carries started_at).
 *
 * Store-backed mode (ticket 098 — a sub-agent-owned task, opened from a sub-agent
 * page): no output (an explicit placeholder instead), no Stop; the status line
 * follows the sub-agent store's record of the child's task frames.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: JobDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect -> snackbarHostState.showSnackbar(effect.message) }
    }
    // The socket-gate registration is scoped to visibility (the SubagentScreen
    // precedent): the session socket stays collected while this screen is on top.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onScreenStarted() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onScreenStopped() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.description?.ifBlank { null } ?: uiState.taskId,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // No Stop in store-backed mode (ticket 098): the sub-agent's own
                    // task controls are ticket 182's.
                    if (!uiState.storeBacked && uiState.status == Job.Status.Running) {
                        TextButton(onClick = viewModel::onStop) {
                            Text("Stop", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            // No loading gate: the shell renders from the route-seeded state until
            // the task's frame lands.
            JobDetailContent(uiState)
        }
    }
}

@Composable
private fun JobDetailContent(uiState: JobDetailViewModel.UiState) {
    // The 1 s elapsed ticker (the jobs sheet's taskClock model): ticks only while the
    // job runs; a terminal job's elapsed stays frozen at completed_at.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(uiState.status) {
        if (uiState.status == Job.Status.Running) {
            while (true) {
                delay(1_000)
                now = Instant.now()
            }
        }
    }
    // Body width capped and centered on large screens (M3's content-width guidance).
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            JobStatusLine(uiState, now)
            uiState.command?.takeIf { it.isNotBlank() }?.let { command ->
                JobSection(title = "Command") {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Text(
                            text = command,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            JobSection(title = "Output", modifier = Modifier.weight(1f)) {
                JobOutputBlock(uiState, Modifier.weight(1f))
            }
        }
    }
}

/** Glyph + plain-English label + elapsed (`mm:ss`, `h:mm:ss` past an hour — the
 * shared [formatElapsed]). While running with a known kill budget (ticket 097) the
 * line reads `running · <elapsed> / <budget>` — `· default` appended when the budget
 * is the harness's 600 s fallback — and a determinate bar fills toward the budget,
 * state-driven by the 1 s ticker (no infinite animation; the fraction coerces to
 * 0..1, a job can outlive a stale default). A live-recorded timeout reads
 * "timed out after <budget-or-elapsed>" in place of "failed" (outcome `timed_out`).
 * Internal for the semantics tests. */
@Composable
internal fun JobStatusLine(uiState: JobDetailViewModel.UiState, now: Instant) {
    val status = uiState.status ?: return
    val label = when (status) {
        Job.Status.Running -> "running"
        Job.Status.Completed -> "completed"
        Job.Status.Failed -> if (uiState.timedOut) "timed out" else "failed"
        Job.Status.Cancelled -> "cancelled"
    }
    val glyphDescription = "Job $label"
    val elapsedDuration = uiState.startedAt?.let { Duration.between(it, uiState.completedAt ?: now) }
    val elapsed = elapsedDuration?.let(::formatElapsed)
    val budget = uiState.budgetSeconds?.let { formatElapsed(Duration.ofSeconds(it.toLong())) }
    val text = when {
        status == Job.Status.Failed && uiState.timedOut -> {
            val after = budget ?: elapsed
            if (after != null) "timed out after $after" else "timed out"
        }
        status == Job.Status.Running && budget != null && elapsed != null ->
            "$label · $elapsed / $budget" + if (uiState.budgetIsDefault) " · default" else ""
        elapsed != null -> "$label · $elapsed"
        else -> label
    }
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (status) {
                Job.Status.Running -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp).semantics { contentDescription = glyphDescription },
                    strokeWidth = 1.5.dp,
                )
                // No success slot in the M3 scheme: a literal green, readable on both themes.
                Job.Status.Completed -> Text(
                    text = "✓",
                    modifier = Modifier.semantics { contentDescription = glyphDescription },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.successColor,
                )
                Job.Status.Failed -> Text(
                    text = "✗",
                    modifier = Modifier.semantics { contentDescription = glyphDescription },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Job.Status.Cancelled -> Text(
                    text = "✗",
                    modifier = Modifier.semantics { contentDescription = glyphDescription },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = if (uiState.timedOut && status == Job.Status.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    Color.Unspecified
                },
            )
        }
        if (status == Job.Status.Running && uiState.budgetSeconds != null && elapsedDuration != null) {
            val fraction = (elapsedDuration.toMillis() / (uiState.budgetSeconds * 1_000f)).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

/** One titled block of the body; the title is a TalkBack heading. */
@Composable
private fun JobSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
        )
        content()
    }
}

/** The output text (the ToolDetailBlock recipe): one monospace block scrolling
 * internally, re-pinned to the tail each time it grows while the job runs. While
 * [JobDetailViewModel.UiState.isLoading] (a watch awaiting its first chunk) a small
 * spinner stands in for the block — the rest of the shell already renders from the
 * route-seeded state. A watch error (ticket 180) reads "Output unavailable: …" above
 * the fallback block. In store-backed mode (ticket 098) an explicit placeholder
 * replaces the block: no output channel is wired for a sub-agent-owned task.
 * Internal for the semantics tests. */
@Composable
internal fun JobOutputBlock(uiState: JobDetailViewModel.UiState, modifier: Modifier = Modifier) {
    if (uiState.storeBacked) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Output not available for sub-agent tasks",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    uiState.outputError?.let { error ->
        Text(
            text = "Output unavailable: $error",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (uiState.isLoading && uiState.output == null) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.padding(24.dp).semantics { contentDescription = "Loading output" },
            )
        }
        return
    }
    val output = uiState.output.orEmpty()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        if (output.isBlank()) {
            Text(
                text = "No output yet",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val scrollState = rememberScrollState()
            if (uiState.status == Job.Status.Running) {
                LaunchedEffect(output) { scrollState.scrollTo(scrollState.maxValue) }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = output,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
