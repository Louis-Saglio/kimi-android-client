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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubagentScreen(
    onNavigateBack: () -> Unit,
    /** Nested sub-agents (ticket 071): a linked `Agent` row inside this transcript
     * navigates one page deeper. Invoked with the nested subagentId. */
    onOpenSubagent: (String) -> Unit = {},
    viewModel: SubagentViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Registers the page while visible (ON_START/ON_STOP pair): the registration keeps
    // the store fed for as long as a page of the session is open — by the chat's socket
    // gate when it exists, by the store's own keep-alive collection otherwise (064).
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onScreenStarted() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onScreenStopped() }
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
                    isLoadingMoreHistory = uiState.isLoadingMoreHistory,
                    onHistoryTopReached = viewModel::onHistoryTopReached,
                )
            }
        }
    }
}

@Composable
private fun SubagentStatusChip(status: SubagentStatus, modifier: Modifier = Modifier) {
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
