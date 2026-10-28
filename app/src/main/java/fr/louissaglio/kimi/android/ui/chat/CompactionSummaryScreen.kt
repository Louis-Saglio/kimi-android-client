package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.ui.common.formatTokenCount

/**
 * The compaction summary screen (ticket 130): a static snapshot fed by the
 * [CompactionSummaryStore] entry staged at divider-tap time — no ViewModel, the
 * file-content screen's precedent. The summary is model-generated markdown, rendered
 * by the chat's [MarkdownBody]; the top bar carries the token delta when the divider
 * knew it (live only — the numbers are not persisted server-side). A store miss
 * (process death with this screen on top) renders the missing-content state, with Back
 * as the way out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactionSummaryScreen(
    entry: CompactionSummaryStore.Entry?,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Compaction summary",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val before = entry?.tokensBefore
                        val after = entry?.tokensAfter
                        if (before != null && after != null) {
                            Text(
                                text = "${formatTokenCount(before)} → ${formatTokenCount(after)} tokens",
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
            )
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            if (entry == null) {
                MissingSummaryState(onNavigateBack)
            } else {
                // Same body shape as FileContentScreen: capped and centered on large
                // screens, selectable content scrolling vertically.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 840.dp)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            MarkdownBody(entry.summary)
                        }
                    }
                }
            }
        }
    }
}

/** The store-miss state (process death with this screen on top): the file-content
 * screen's error-state shape, Back the only action — re-tapping the divider re-stages
 * the entry. */
@Composable
private fun MissingSummaryState(onNavigateBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "This compaction summary is no longer available — reopen it from the chat.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onNavigateBack) {
            Text("Back")
        }
    }
}
