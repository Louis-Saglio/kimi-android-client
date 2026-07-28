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

/**
 * Read-only sub-agent page (ticket 045): the sub-agent's own stream — spliced prompts
 * as user bubbles, assistant text and thinking, tool rows — with no composer, no
 * approval cards and no question wizard (a sub-agent cannot be talked to; its
 * questions and approvals stay in the main chat). The list reuses the chat's
 * [MessageList], auto-scroll included.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubagentScreen(
    onNavigateBack: () -> Unit,
    viewModel: SubagentViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Registers the page while visible (ON_START/ON_STOP pair): the chat's socket
    // gate keeps the store fed for as long as a page of the session is open.
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
                        uiState.name?.let { name ->
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
            when {
                // The spawn predates the app's subscription (deep-ish state loss):
                // transcript REST backing lands with ticket 046.
                uiState.status == null -> EmptyState(
                    "No stream for this sub-agent — the app connected after it started. " +
                        "Its result appears on the Agent tool row in the chat.",
                )
                uiState.items.isEmpty() -> EmptyState("No activity yet")
                else -> MessageList(uiState.items)
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
