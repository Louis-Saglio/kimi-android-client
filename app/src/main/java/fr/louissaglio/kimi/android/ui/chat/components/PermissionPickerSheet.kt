package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.ui.common.icons.BoltIcon
import fr.louissaglio.kimi.android.ui.common.icons.HandIcon
import fr.louissaglio.kimi.android.ui.common.icons.ShieldIcon

/**
 * The session's permission mode (ticket 141), keyed by its WIRE value. The declaration
 * order is the sheet's row order — Always Ask, Ask When Needed, Never Ask: the wire
 * enum is NOT positional (yolo maps to the middle label because the dangerous-command
 * guard still asks in yolo while auto gates nothing), so this mapping is hardcoded and
 * must never be sorted by name. Labels/descriptions mirror the 0.42.0 web bundle (en).
 * Public like ToolStatus: ChatViewModel's UiState exposes it.
 */
enum class PermissionMode(
    val wire: String,
    val label: String,
    val description: String,
    val icon: ImageVector,
) {
    AlwaysAsk(
        "manual",
        "Always Ask",
        "Auto-read only; everything else needs your approval first",
        HandIcon,
    ),
    AskWhenNeeded(
        "yolo",
        "Ask When Needed",
        "Routine edits and commands run automatically; risky actions, questions, and plans still ask",
        ShieldIcon,
    ),
    NeverAsk(
        "auto",
        "Never Ask",
        "Never interrupts you; everything runs and is decided automatically",
        BoltIcon,
    ),
    ;

    companion object {
        /** The mode for a wire value; null when the server reports one we don't know. */
        fun fromWire(wire: String?): PermissionMode? = entries.firstOrNull { it.wire == wire }
    }
}

/** The risk tint of a mode (ticket 141): Always Ask stays the composer's neutral
 * content color, Ask When Needed wears tertiary, and Never Ask — the one dangerous
 * choice — wears the scheme's error, on the chip and on its sheet row alike. */
internal val PermissionMode.tint: Color
    @Composable get() = when (this) {
        PermissionMode.AlwaysAsk -> MaterialTheme.colorScheme.onSurfaceVariant
        PermissionMode.AskWhenNeeded -> MaterialTheme.colorScheme.tertiary
        PermissionMode.NeverAsk -> MaterialTheme.colorScheme.error
    }

/** Permission-mode picker bottom sheet (ticket 141), the ModelPickerSheet pattern
 * reduced to the server's three static modes: one row per mode — icon, label,
 * description — with a check on the current value (the local pick, else the
 * /status-seeded server mode). In the chat a tap selects and dismisses; there is no
 * confirmation even for Never Ask (web parity — the error tint carries the risk
 * signal). Settings reuses the sheet (ticket 142) to edit the SERVER default: a tap
 * persists through the ViewModel and the caller keeps the sheet open, the check
 * moving on success — the ModelPickerSheet's settings contract. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PermissionPickerSheet(
    current: PermissionMode?,
    onSelected: (PermissionMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            PermissionMode.entries.forEach { mode ->
                val tint = mode.tint
                SheetCheckRow(
                    title = mode.label,
                    subtitle = mode.description,
                    titleColor = if (mode == PermissionMode.NeverAsk) tint else Color.Unspecified,
                    checked = mode == current,
                    onClick = { onSelected(mode) },
                    leading = {
                        Icon(
                            imageVector = mode.icon,
                            contentDescription = null,
                            tint = tint,
                        )
                    },
                )
            }
        }
    }
}
