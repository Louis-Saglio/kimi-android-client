package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The compaction divider row (ticket 056), web parity: "Compacting context…" with a
 * small spinner while the server summarizes, then "Context compacted" — with the
 * " (26.5k → 386 tokens)" delta when the completion event carried the numbers (they
 * are not persisted, so history-sourced rows go without). A done row with a summary
 * is tappable and opens it in a "Compaction summary" dialog.
 */
@Composable
internal fun CompactionDividerRow(item: ChatItem.CompactionDivider) {
    var showSummary by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.summary != null) { showSummary = true }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.pending) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = dividerLabel(item),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val summary = item.summary
    if (showSummary && summary != null) {
        AlertDialog(
            onDismissRequest = { showSummary = false },
            confirmButton = {
                TextButton(onClick = { showSummary = false }) { Text("OK") }
            },
            title = { Text("Compaction summary") },
            text = { Text(summary) },
        )
    }
}

internal fun dividerLabel(item: ChatItem.CompactionDivider): String {
    if (item.pending) return "Compacting context…"
    val before = item.tokensBefore
    val after = item.tokensAfter
    return if (before != null && after != null) {
        "Context compacted (${formatTokenCount(before)} → ${formatTokenCount(after)} tokens)"
    } else {
        "Context compacted"
    }
}
