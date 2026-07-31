package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Shared read-only dialog (ticket 071): a title, caller-supplied content and a single
 * OK that dismisses — the compaction summary and the context-usage details both were
 * hand-rolled copies of this. */
@Composable
internal fun InfoDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
        title = { Text(title) },
        text = content,
    )
}
