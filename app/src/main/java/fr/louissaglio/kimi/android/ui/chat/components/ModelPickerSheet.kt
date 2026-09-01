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

/** Model picker bottom sheet (ticket 031, redesigned 108): every server model, grouped
 * by provider — one section per distinct [ServerModel.provider] headed by the raw
 * provider id, the server's ordering kept inside each group. A row shows the display
 * name, a "Default" badge on the server-wide default and a check on the effective one
 * (the session's choice, else the default); models sharing a display name across
 * providers get their model id appended to the subtitle ("1M context ·
 * kimi-code/k3-256k") so the rows stay distinguishable. Below, the thinking-effort
 * section (ticket 032) lists the current model's efforts — hidden entirely for models
 * without `support_efforts` — with a check on the stored choice, or a "Default" hint
 * on the model's `default_effort` when nothing is stored. A tap moves the check and
 * persists but keeps the sheet open (108): model and effort are settable in one visit
 * and the effort section visibly follows a model change; dismissal is by scrim/swipe.
 * Settings reuses the sheet (109) to edit the SERVER default: there `currentModelId`
 * is the server default and `serverDefaultModel` is null — the check alone marks the
 * current value, the "Default" badge would be noise. */
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
            // Display names collide across providers (two "K3-256k" rows on Louis's
            // server, 108) — colliding names get the model id in the subtitle.
            val collidingNames = availableModels.groupingBy { it.displayName }.eachCount()
            // groupBy keeps encounter order: providers and their models stay server-ordered.
            availableModels.groupBy { it.provider }.forEach { (provider, models) ->
                SheetSectionHeader(provider)
                models.forEach { model ->
                    SheetCheckRow(
                        title = model.displayName.ifBlank { model.model },
                        subtitle = formatContextSize(model.maxContextSize) +
                            if ((collidingNames[model.displayName] ?: 0) > 1) " · ${model.model}" else "",
                        badge = if (model.model == serverDefaultModel) "Default" else null,
                        checked = model.model == currentModelId,
                        onClick = { onModelSelected(model.model) },
                    )
                }
            }
            if (supportedEfforts.isNotEmpty()) {
                SheetSectionHeader("Thinking effort")
                supportedEfforts.forEach { effort ->
                    SheetCheckRow(
                        title = effort,
                        badge = if (selectedEffort == null && effort == defaultEffort) "Default" else null,
                        checked = effort == selectedEffort,
                        onClick = { onEffortSelected(effort) },
                    )
                }
            }
        }
    }
}

/** Header of a sheet section: the "Thinking effort" styling (032), reused for the
 * provider groups (108). */
@Composable
private fun SheetSectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Context window for the picker rows: "1M context", "262.1k context". */
private fun formatContextSize(size: Long): String =
    if (size >= 1_000) "${formatTokenCount(size)} context" else "$size tokens"
