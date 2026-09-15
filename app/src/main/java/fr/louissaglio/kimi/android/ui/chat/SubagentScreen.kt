package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.louissaglio.kimi.android.data.chat.BashTimeout
import fr.louissaglio.kimi.android.data.chat.ToolPayload
import fr.louissaglio.kimi.android.data.chat.bashCommandOf
import fr.louissaglio.kimi.android.data.chat.bashDescriptionOf
import fr.louissaglio.kimi.android.ui.common.EmptyState
import fr.louissaglio.kimi.android.ui.common.ErrorState
import fr.louissaglio.kimi.android.ui.common.LoadingState

/**
 * Read-only sub-agent page (ticket 045), transcript-backed since ticket 046: the
 * sub-agent's committed history (fetched per-agent from the transcript endpoint) plus
 * its live stream — spliced prompts as user bubbles, assistant text and thinking,
 * tool rows — with no composer, no approval cards and no question wizard (a sub-agent
 * cannot be talked to; its questions and approvals stay in the main chat). The list
 * reuses the chat's [MessageList], auto-scroll and history pagination included.
 * The bottom bar hosts the shared todos/jobs dock (the chat composer's widgets):
 * the sub-agent's own todo list and its own background tasks. Background `Bash` rows
 * — timeline and jobs sheet alike — open the job screen (ticket 098), which runs
 * store-backed for these sub-agent-owned tasks; the sheet stays without a Stop
 * (REST `/tasks` serves only the main agent's task store — docs/server-api.md).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubagentScreen(
    onNavigateBack: () -> Unit,
    /** Nested sub-agents (ticket 071): a linked `Agent` row inside this transcript
     * navigates one page deeper. Invoked with the nested subagentId. */
    onOpenSubagent: (String) -> Unit = {},
    /** Background `Bash` rows (ticket 098) open their job screen. Same resolution as
     * the chat's (ChatScreen): the budget/description/command resolve from the tapped
     * row's call input, the timed-out flag from the jobs list. */
    onNavigateToJob: (
        sessionId: String,
        taskId: String,
        budgetSeconds: Int?,
        budgetIsDefault: Boolean,
        timedOut: Boolean,
        description: String?,
        command: String?,
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    /** Ticket 119: a parsed `Edit` row opens the unified diff screen, exactly like
     * the chat's rows — the payload rides along for the ToolContentStore hand-off. */
    onNavigateToEditDiff: (toolCallId: String, payload: ToolPayload.Edit) -> Unit = { _, _ -> },
    /** Ticket 120: a parsed `Read`/`Write` row opens the file content screen, exactly
     * like the chat's rows — payload and output snapshot ride along. */
    onNavigateToFileContent: (toolCallId: String, payload: ToolPayload, output: String) -> Unit = { _, _, _ -> },
    viewModel: SubagentViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Registers the page while visible (ON_START/ON_STOP pair): the registration keeps
    // the store fed for as long as a page of the session is open — by the chat's socket
    // gate when it exists, by the store's own keep-alive collection otherwise (064).
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onScreenStarted() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onScreenStopped() }
    // Linked background `Bash` rows open their job screen (ticket 098) — the chat's
    // ticket-096 resolution: the timeout budget comes from the matching timeline row
    // (the call input is the only wire location of the timeout), the timed-out flag
    // from the jobs list, and the call input's description/command seed the job
    // screen's title and Command section; an unknown row navigates budget-less.
    val sessionId = uiState.sessionId
    val timelineItems = uiState.items
    val jobs = uiState.jobs
    val onOpenJob = remember(onNavigateToJob, sessionId, timelineItems, jobs) {
        { taskId: String ->
            val row = timelineItems.filterIsInstance<ChatItem.ToolGroup>()
                .flatMap { it.tools }
                .firstOrNull { it.bashTaskId == taskId }
            val timeout = row?.let { BashTimeout(it.timeoutSeconds, it.timeoutDisabled) }
            val job = jobs.firstOrNull { it.taskId == taskId }
            onNavigateToJob(
                sessionId,
                taskId,
                timeout?.effectiveSeconds,
                timeout?.isDefault ?: false,
                job?.timedOut == true,
                row?.let { bashDescriptionOf(it.name, it.input) } ?: job?.description,
                row?.let { bashCommandOf(it.name, it.input) },
            )
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.description?.ifBlank { null } ?: uiState.subagentId,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        uiState.name?.takeIf { it.isNotBlank() }?.let { name ->
                            Text(
                                text = name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    uiState.status?.let { status ->
                        SubagentStatusChip(status, Modifier.padding(end = 12.dp))
                    }
                },
            )
        },
        bottomBar = {
            // The sub-agent's own todos/jobs — the chat's dock, minus the affordance
            // REST `/tasks` cannot back for a sub-agent (cancel).
            TodoJobsDock(
                todos = uiState.todos,
                jobs = uiState.jobs,
                items = uiState.items,
                onOpenSubagent = onOpenSubagent,
                onOpenJob = onOpenJob,
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            val error = uiState.error
            when {
                // The transcript fetch is the page's only data source here (no spawn,
                // no seed, no stream): its failure gets the full-screen retry.
                error != null && uiState.items.isEmpty() ->
                    ErrorState(error, onRetry = viewModel::onRetry)
                uiState.status == null && uiState.isLoading -> LoadingState()
                // Neither a live stream nor a transcript entry exists.
                uiState.status == null -> EmptyState("No transcript for this sub-agent.")
                uiState.items.isEmpty() -> EmptyState("No activity yet")
                else -> MessageList(
                    uiState.items,
                    onOpenSubagent = onOpenSubagent,
                    onOpenJob = onOpenJob,
                    onOpenEditDiff = onNavigateToEditDiff,
                    onOpenFileContent = onNavigateToFileContent,
                    isLoadingMoreHistory = uiState.isLoadingMoreHistory,
                    onHistoryTopReached = viewModel::onHistoryTopReached,
                )
            }
        }
    }
}

@Composable
internal fun SubagentStatusChip(status: SubagentStatus, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        SubagentStatus.Queued -> "Queued" to MaterialTheme.colorScheme.surfaceVariant
        SubagentStatus.Working -> "Working" to MaterialTheme.colorScheme.primaryContainer
        SubagentStatus.Suspended -> "Suspended" to MaterialTheme.colorScheme.tertiaryContainer
        SubagentStatus.Completed -> "Completed" to MaterialTheme.colorScheme.secondaryContainer
        SubagentStatus.Failed -> "Failed" to MaterialTheme.colorScheme.errorContainer
    }
    Surface(color = color, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
