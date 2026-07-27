package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Model picker bottom sheet (ticket 031): every server model with its context size,
 * a "Default" label on the server-wide default, and a check on the effective one
 * (the session's choice, else the default). Below it, the thinking-effort section
 * (ticket 032) lists the current model's efforts — hidden entirely for models without
 * `support_efforts` — with a check on the stored choice, or a "Default" hint on the
 * model's `default_effort` when nothing is stored. A tap persists and dismisses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    uiState: ChatViewModel.UiState,
    onModelSelected: (String) -> Unit,
    onEffortSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentModelId = uiState.sessionModel ?: uiState.serverDefaultModel
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            uiState.availableModels.forEach { model ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable {
                            onModelSelected(model.model)
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = model.displayName.ifBlank { model.model },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (model.model == uiState.serverDefaultModel) {
                                Text(
                                    text = "Default",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Text(
                            text = formatContextSize(model.maxContextSize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (model.model == currentModelId) {
                        Text(
                            text = "✓",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (uiState.supportedEfforts.isNotEmpty()) {
                Text(
                    text = "Thinking effort",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                uiState.supportedEfforts.forEach { effort ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                onEffortSelected(effort)
                                onDismiss()
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = effort,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (uiState.selectedEffort == null && effort == uiState.defaultEffort) {
                                Text(
                                    text = "Default",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (effort == uiState.selectedEffort) {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Context window for the picker rows: "1M context", "262k context". */
private fun formatContextSize(size: Long): String = when {
    size >= 1_000_000 -> "${size / 1_000_000}M context"
    size >= 1_000 -> "${size / 1_000}k context"
    else -> "$size tokens"
}
