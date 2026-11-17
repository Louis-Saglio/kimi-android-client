package fr.louissaglio.kimi.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Full-screen error with a Retry button, shared by the chat and sessions screens.
 * Retry needs no enabled gate: every ViewModel clears the error synchronously on the
 * tap, so this state swaps out in the same frame (ticket 071). [modifier] slots
 * between the fill and the padding: the sessions screen passes a verticalScroll so the
 * otherwise static state reaches pull-to-refresh's nested scroll. */
@Composable
internal fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = Modifier.fillMaxSize().then(modifier).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onRetry) {
            Text("Retry")
        }
    }
}

/** Full-screen centered spinner while the first load runs. */
@Composable
internal fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Full-screen centered placeholder for an empty list. [supportingText] adds a muted
 * second line under the message (the search screen's disabled-index state). */
@Composable
internal fun EmptyState(message: String, modifier: Modifier = Modifier, supportingText: String? = null) {
    Column(
        modifier = Modifier.fillMaxSize().then(modifier).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
