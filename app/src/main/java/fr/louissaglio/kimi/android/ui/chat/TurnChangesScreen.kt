package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.louissaglio.kimi.android.data.chat.FileChange
import fr.louissaglio.kimi.android.ui.common.EmptyState
import fr.louissaglio.kimi.android.ui.common.ErrorState
import fr.louissaglio.kimi.android.ui.common.LoadingState
import fr.louissaglio.kimi.android.ui.theme.diffColors
import fr.louissaglio.kimi.android.ui.theme.successColor

/** Stateful entry point of the turn file-changes screen (ticket 149): collects the
 * ViewModel and hands the state to the stateless [TurnChangesScreen] — the
 * FileContent route/screen split, so tests drive the body without Hilt. */
@Composable
fun TurnChangesRoute(
    onNavigateBack: () -> Unit,
    onOpenFile: (FileChange) -> Unit = {},
    viewModel: TurnChangesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    TurnChangesScreen(uiState, onNavigateBack, onRetry = viewModel::onRetry, onOpenFile = onOpenFile)
}

/**
 * One turn's changed files (ticket 149): basename + parent path rows with a status
 * badge (added/modified/deleted) and the `+a −b` line counts of the Edit diff
 * screen's stat idiom (ticket 119). A `binary`/`oversize` row shows a note instead
 * of the counts and is not clickable — there is no comparable content for it. A
 * turn with nothing recorded (`recorded: false` included) shows the empty state.
 * Tapping a normal row reports through [onOpenFile] — the before/after viewer's
 * hook (ticket 150); until that screen exists the hook is a no-op.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TurnChangesScreen(
    uiState: TurnChangesViewModel.UiState,
    onNavigateBack: () -> Unit,
    onRetry: () -> Unit = {},
    onOpenFile: (FileChange) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = "Files changed", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            text = "Turn ${uiState.turnId}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        // Body width capped and centered on large screens (the EditDiffScreen recipe).
        Box(
            Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            val changes = uiState.changes
            when {
                uiState.error != null -> ErrorState(uiState.error, onRetry)
                changes == null -> LoadingState()
                changes.isEmpty() -> EmptyState("No file changes recorded for this turn")
                else -> LazyColumn(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(changes, key = FileChange::path) { change ->
                        FileChangeRow(change, onOpenFile)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileChangeRow(change: FileChange, onOpenFile: (FileChange) -> Unit) {
    val note = when {
        change.binary && change.oversize -> "Binary file · Oversize file"
        change.binary -> "Binary file"
        change.oversize -> "Oversize file"
        else -> null
    }
    Column(
        Modifier
            .fillMaxWidth()
            // Binary/oversize rows have no comparable content: no tap (ticket 149).
            .then(if (note == null) Modifier.clickable { onOpenFile(change) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = change.path.substringAfterLast('/'),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusBadge(change.status)
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // The ticket-119 diff-stat idiom: success/error text, monospace.
                Text(
                    text = "+${change.additions}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.successColor,
                )
                Text(
                    text = "−${change.deletions}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        val parent = change.path.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isNotBlank()) {
            Text(
                text = parent,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The change-status chip (the tool rows' badge idiom): added rides the diff's
 * added container pair, deleted the removed pair, anything else the neutral
 * secondary pair. */
@Composable
private fun StatusBadge(status: FileChange.Status) {
    val (container, content, label) = when (status) {
        FileChange.Status.Added ->
            Triple(MaterialTheme.diffColors.addedContainer, MaterialTheme.diffColors.addedContent, "added")
        FileChange.Status.Deleted ->
            Triple(MaterialTheme.diffColors.removedContainer, MaterialTheme.diffColors.removedContent, "deleted")
        FileChange.Status.Modified ->
            Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, "modified")
        FileChange.Status.Other ->
            Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, "changed")
    }
    Surface(color = container, shape = MaterialTheme.shapes.small) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}
