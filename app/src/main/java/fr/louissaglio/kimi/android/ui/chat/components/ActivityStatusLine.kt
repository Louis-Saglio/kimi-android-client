package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.ui.common.formatElapsed
import java.time.Duration
import java.time.Instant

/**
 * The live activity status line directly above the composer (ticket 148): the agent's
 * current phase — "Thinking…", "Running Bash…", "Waiting for approval" — with a small
 * spinner as the leading icon and the phase's elapsed time (`mm:ss`, the shared
 * [formatElapsed]) behind it, state-driven by the caller's 1 s [now] (the JobStatusLine
 * idiom). A null [status] (idle, ended, interrupted, unknown) renders nothing.
 */
@Composable
internal fun ActivityStatusLine(status: ChatViewModel.ActivityStatus?, now: Instant) {
    if (status == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            text = activityStatusLabel(status, now),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Running Bash… · 00:12"; a phase without a start shows its bare label. */
internal fun activityStatusLabel(status: ChatViewModel.ActivityStatus, now: Instant): String {
    val since = status.since ?: return status.text
    return "${status.text} · ${formatElapsed(Duration.between(since, now))}"
}
