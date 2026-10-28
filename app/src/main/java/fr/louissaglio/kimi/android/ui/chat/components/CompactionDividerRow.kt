package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.ui.common.formatTokenCount

/**
 * The compaction divider row (ticket 056), web parity: "Compacting context…" with a
 * small spinner while the server summarizes, then "Context compacted" — with the
 * " (26.5k → 386 tokens)" delta when the completion event carried the numbers (they
 * are not persisted, so history-sourced rows go without). A done row with a summary
 * is tappable and opens the "Compaction summary" screen (ticket 130); a trailing
 * chevron marks that affordance, the tool rows' navigation cue.
 */
@Composable
internal fun CompactionDividerRow(
    item: ChatItem.CompactionDivider,
    onOpenSummary: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.summary != null) { onOpenSummary() }
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
        if (item.summary != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open compaction summary",
                modifier = Modifier.size(16.dp).padding(start = 4.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
