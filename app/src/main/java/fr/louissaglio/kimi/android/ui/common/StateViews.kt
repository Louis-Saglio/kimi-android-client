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
import androidx.compose.ui.unit.dp

/** Full-screen error with a Retry button, shared by the chat and sessions screens.
 * [modifier] slots between the fill and the padding: the sessions screen passes a
 * verticalScroll so the otherwise static state reaches pull-to-refresh's nested scroll. */
@Composable
internal fun ErrorState(message: String, onRetry: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = Modifier.fillMaxSize().then(modifier).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(text = message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onRetry, enabled = enabled) {
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

/** Full-screen centered placeholder for an empty list. */
@Composable
internal fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = Modifier.fillMaxSize().then(modifier).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
    }
}
