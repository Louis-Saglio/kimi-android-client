package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.data.chat.ServerModel
import fr.louissaglio.kimi.android.ui.common.formatTokenCount
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Model picker bottom sheet (ticket 031, redesigned 108): every server model, grouped
 * by provider — one collapsible section per distinct [ServerModel.provider] headed by the
 * raw provider id, the server's ordering kept inside each group. The sheet opens compact:
 * only the group holding the effective model starts expanded (fallback: the server
 * default's group, then the first); headers toggle the groups, and the expansion state is
 * saveable so it survives dismissal within the visit. A row shows the display name, a
 * "Default" badge on the server-wide default and a check on the effective one (the
 * session's choice, else the default); models sharing a display name across providers get
 * their model id appended to the subtitle ("1M context · kimi-code/k3-256k") so the rows
 * stay distinguishable. The thinking effort (ticket 032) sits pinned below the groups as
 * one compact row — a "Thinking" label plus one segment per supported effort, like the
 * web picker — hidden entirely for models without `support_efforts`; the selected segment
 * is the EFFECTIVE effort (the stored choice, else the model's `default_effort`). A tap
 * persists but keeps the sheet open (108): model and effort are settable in one visit and
 * the effort row visibly follows a model change; dismissal is by scrim/swipe. Settings
 * reuses the sheet (109) to edit the SERVER default: there `currentModelId` is the server
 * default and `serverDefaultModel` is null — the check alone marks the current value, the
 * "Default" badge would be noise. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    availableModels: List<ServerModel>,
    /** The effective model: the session's choice, else the server default. */
    currentModelId: String?,
    serverDefaultModel: String?,
    supportedEfforts: List<String>,
    selectedEffort: String?,
    /** The current model's server default effort, used when nothing is stored. */
    defaultEffort: String?,
    /** Ticket 170: the active effort's English description (managed provider only,
     * already gated by the caller); null renders nothing. */
    effortDescription: String? = null,
    onModelSelected: (String) -> Unit,
    onEffortSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // The upgrade guide is a read-only read of catalog metadata (ticket 166): opening
    // it never selects a model or touches the stored/session preference.
    var upgradeGuide by remember { mutableStateOf<ServerModel?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            // Display names collide across providers (two "K3-256k" rows on Louis's
            // server, 108) — colliding names get the model id in the subtitle.
            val collidingNames = availableModels.groupingBy { it.displayName }.eachCount()
            // groupBy keeps encounter order: providers and their models stay server-ordered.
            val groups = availableModels.groupBy { it.provider }
            var expandedProviders by rememberSaveable {
                mutableStateOf(listOfNotNull(groups.keys.firstOrNull { provider ->
                    groups[provider].orEmpty().any { it.model == currentModelId }
                } ?: groups.keys.firstOrNull { provider ->
                    groups[provider].orEmpty().any { it.model == serverDefaultModel }
                } ?: groups.keys.firstOrNull()))
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                groups.forEach { (provider, models) ->
                    val expanded = provider in expandedProviders
                    ProviderGroupHeader(
                        provider = provider,
                        expanded = expanded,
                        onToggle = {
                            expandedProviders =
                                if (expanded) expandedProviders - provider else expandedProviders + provider
                        },
                    )
                    if (expanded) {
                        models.forEach { model ->
                            val upgrade = model.upgrade
                            SheetCheckRow(
                                title = model.displayName.ifBlank { model.model },
                                subtitle = buildString {
                                    append(formatContextSize(model.maxContextSize))
                                    if ((collidingNames[model.displayName] ?: 0) > 1) append(" · ${model.model}")
                                    if (upgrade != null) {
                                        append(" · Upgrade to ${upgrade.model}")
                                        upgrade.retirementAt?.let { append(" · retires ${formatRetirement(it)}") }
                                    }
                                },
                                badge = listOfNotNull(
                                    "Default".takeIf { model.model == serverDefaultModel },
                                    "Upgrade".takeIf { upgrade != null },
                                ).joinToString(" · ").ifEmpty { null },
                                checked = model.model == currentModelId,
                                onClick = { onModelSelected(model.model) },
                            )
                            if (upgrade != null) {
                                // Outside the check row on purpose: the row tap selects
                                // the model, the guide is a read-only detour (ticket 166).
                                TextButton(
                                    onClick = { upgradeGuide = model },
                                    modifier = Modifier.padding(start = 16.dp),
                                ) {
                                    Text("Guide")
                                }
                            }
                        }
                    }
                }
            }
            if (supportedEfforts.isNotEmpty()) {
                HorizontalDivider()
                ThinkingEffortRow(
                    supportedEfforts = supportedEfforts,
                    effectiveEffort = selectedEffort ?: defaultEffort,
                    onEffortSelected = onEffortSelected,
                )
            }
        }
    }
}

/** Collapsible header of a provider group: the old section-header styling (032/108) with
 * a chevron marking the expansion state. */
@Composable
private fun ProviderGroupHeader(provider: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = provider,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            modifier = Modifier.size(16.dp).rotate(chevronRotation),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One-row thinking-effort picker, web-picker style: label left, one segment per effort
 * right; the selected segment is the effective effort. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinkingEffortRow(
    supportedEfforts: List<String>,
    effectiveEffort: String?,
    onEffortSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Thinking",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            supportedEfforts.forEachIndexed { index, effort ->
                SegmentedButton(
                    selected = effort == effectiveEffort,
                    onClick = { onEffortSelected(effort) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = supportedEfforts.size),
                    // No check glyph: the highlight alone marks the selection, like the web picker.
                    icon = {},
                ) {
                    Text(effort)
                }
            }
        }
    }
}

/** Context window for the picker rows: "1M context", "262.1k context". */
private fun formatContextSize(size: Long): String =
    if (size >= 1_000) "${formatTokenCount(size)} context" else "$size tokens"

/** The upgrade's retirement day: "1 Dec 2026" (UTC — the server's stamp is an
 * absolute instant). */
private val retirementFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC)

private fun formatRetirement(instant: java.time.Instant): String =
    retirementFormatter.format(instant)
