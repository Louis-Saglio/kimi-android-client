package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.data.chat.ServerModel
import fr.louissaglio.kimi.android.ui.common.formatTokenCount

/** Model picker bottom sheet (ticket 031): every server model with its context size,
 * a "Default" label on the server-wide default, and a check on the effective one
 * (the session's choice, else the default). Below it, the thinking-effort section
 * (ticket 032) lists the current model's efforts — hidden entirely for models without
 * `support_efforts` — with a check on the stored choice, or a "Default" hint on the
 * model's `default_effort` when nothing is stored. A tap persists and dismisses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    availableModels: List<ServerModel>,
    /** The effective model: the session's choice, else the server default. */
    currentModelId: String?,
    serverDefaultModel: String?,
    supportedEfforts: List<String>,
    selectedEffort: String?,
    /** The current model's server default effort, for the "Default" hint. */
    defaultEffort: String?,
    onModelSelected: (String) -> Unit,
    onEffortSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            availableModels.forEach { model ->
                SheetCheckRow(
                    title = model.displayName.ifBlank { model.model },
                    subtitle = formatContextSize(model.maxContextSize),
                    badge = if (model.model == serverDefaultModel) "Default" else null,
                    checked = model.model == currentModelId,
                    onClick = {
                        onModelSelected(model.model)
                        onDismiss()
                    },
                )
            }
            if (supportedEfforts.isNotEmpty()) {
                Text(
                    text = "Thinking effort",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                supportedEfforts.forEach { effort ->
                    SheetCheckRow(
                        title = effort,
                        badge = if (selectedEffort == null && effort == defaultEffort) "Default" else null,
                        checked = effort == selectedEffort,
                        onClick = {
                            onEffortSelected(effort)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/** Context window for the picker rows: "1M context", "262.1k context". */
private fun formatContextSize(size: Long): String =
    if (size >= 1_000) "${formatTokenCount(size)} context" else "$size tokens"
