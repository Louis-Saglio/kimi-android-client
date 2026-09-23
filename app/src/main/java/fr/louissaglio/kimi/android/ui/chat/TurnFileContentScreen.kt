package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.louissaglio.kimi.android.ui.common.EmptyState
import fr.louissaglio.kimi.android.ui.common.ErrorState
import fr.louissaglio.kimi.android.ui.common.LoadingState

/** Stateful entry point of the turn file before/after viewer (ticket 150): collects
 * the ViewModel and hands the state to the stateless [TurnFileContentScreen] — the
 * TurnChanges route/screen split, so tests drive the body without Hilt. */
@Composable
fun TurnFileContentRoute(
    onNavigateBack: () -> Unit,
    viewModel: TurnFileContentViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    TurnFileContentScreen(
        uiState,
        onNavigateBack,
        onTabSelected = viewModel::onTabSelected,
        onRetry = viewModel::onRetry,
    )
}

/**
 * One file's before/after content for a turn (ticket 150), opened from a tap on the
 * turn file-changes list (149): the file's basename and full path in the top bar, a
 * Before/After segmented toggle (the ModelPickerSheet idiom; After is the default)
 * and the visible side below. A text side renders in the monospace content block of
 * 120's read screen ([SelectableCodeBlock]); a null side (the file did not exist at
 * that phase) renders "File did not exist yet" on Before / "File deleted" on After;
 * a `binary: true` side renders a placeholder — no content fetch beyond the one
 * already made. Each side fetches and retries independently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TurnFileContentScreen(
    uiState: TurnFileContentViewModel.UiState,
    onNavigateBack: () -> Unit,
    onTabSelected: (TurnFileContentViewModel.Tab) -> Unit = {},
    onRetry: (TurnFileContentViewModel.Tab) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.path.substringAfterLast('/'),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = uiState.path,
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
            Column(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val tabs = TurnFileContentViewModel.Tab.entries
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    tabs.forEachIndexed { index, tab ->
                        SegmentedButton(
                            selected = tab == uiState.tab,
                            onClick = { onTabSelected(tab) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                            // No check glyph: the highlight alone marks the selection.
                            icon = {},
                        ) {
                            Text(tab.label)
                        }
                    }
                }
                val tab = uiState.tab
                val side = when (tab) {
                    TurnFileContentViewModel.Tab.Before -> uiState.before
                    TurnFileContentViewModel.Tab.After -> uiState.after
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        side.error != null -> ErrorState(side.error, onRetry = { onRetry(tab) })
                        !side.resolved -> LoadingState()
                        side.snapshot == null -> EmptyState(
                            when (tab) {
                                TurnFileContentViewModel.Tab.Before -> "File did not exist yet"
                                TurnFileContentViewModel.Tab.After -> "File deleted"
                            },
                        )
                        side.snapshot.binary -> EmptyState("Binary file")
                        else -> SelectableCodeBlock(side.snapshot.content.orEmpty(), Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
