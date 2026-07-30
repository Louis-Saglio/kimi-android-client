package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.data.chat.ContextUsage

/**
 * The chat composer's context-usage indicator (ticket 041, moved from the top bar into
 * the composer in 053): a small ring, filled proportionally to
 * [ContextUsage.usedTokens] / [ContextUsage.maxTokens], with no text — a tap opens the
 * exact numbers in a dialog. At ≥80% ([HIGH_CONTEXT_USAGE_PERCENT]) the arc (and the
 * dialog percentage) shifts to the error color and the dialog gains a "Compact session"
 * button (ticket 057) — the action sits next to the numbers that motivate it, sharing
 * the overflow menu's entry point and enabled rules (056). The caller hides the ring
 * entirely while the usage is still unknown (no fake empty ring).
 */
@Composable
internal fun ContextUsageRing(
    usage: ContextUsage,
    compactEnabled: Boolean,
    onCompact: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pct = contextUsagePercent(usage.usedTokens, usage.maxTokens)
    val details = "${formatTokenCount(usage.usedTokens)} / ${formatTokenCount(usage.maxTokens)} tokens · $pct%"
    val description =
        "Context usage: ${formatTokenCount(usage.usedTokens)} of ${formatTokenCount(usage.maxTokens)} tokens, $pct%"
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val isHigh = isHighContextUsage(pct)
    val arcColor = if (isHigh) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        // A 20dp arc inside the standard 48dp touch target, pinned to the start so it
        // visually attaches to the model chip it trails (ticket 053) instead of floating
        // ~26dp away from it; the tap target stays the full box.
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable { showDetails = true }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.padding(start = 2.dp).size(20.dp)) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            drawCircle(
                color = trackColor,
                radius = (size.minDimension - stroke.width) / 2,
                style = stroke,
            )
            if (pct > 0) {
                drawArc(
                    color = arcColor,
                    startAngle = -90f,
                    sweepAngle = pct * 3.6f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - stroke.width, size.height - stroke.width),
                    style = stroke,
                )
            }
        }
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) { Text("OK") }
            },
            title = { Text("Context usage") },
            text = {
                Column {
                    Text(
                        text = details,
                        color = if (isHigh) MaterialTheme.colorScheme.error else Color.Unspecified,
                    )
                    // Ticket 057: at high usage the dialog itself carries the compact
                    // action, right under the numbers that motivate it — same entry
                    // point and enabled rules as the 056 overflow menu item. The tap
                    // dismisses; the timeline's compacting divider takes over the
                    // feedback, so no extra snackbar from here.
                    if (isHigh) {
                        Button(
                            onClick = {
                                showDetails = false
                                onCompact()
                            },
                            enabled = compactEnabled,
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text("Compact session")
                        }
                    }
                }
            },
        )
    }
}

/** Usage percentage at which the ring shifts to the error color (041) and the details
 * dialog offers "Compact session" (057) — one shared constant so the two rules cannot
 * drift apart. */
internal const val HIGH_CONTEXT_USAGE_PERCENT = 80

internal fun isHighContextUsage(pct: Int): Boolean = pct >= HIGH_CONTEXT_USAGE_PERCENT

/** Fill percentage, web parity (ticket 041): ceil, so sub-0.5% usage still shows a
 * sliver, clamped to 0–100 so momentary over-estimates don't break the arc. */
internal fun contextUsagePercent(used: Long, max: Long): Int {
    if (max <= 0 || used <= 0) return 0
    return ((used * 100 + max - 1) / max).coerceIn(0, 100).toInt()
}

/** Compact token count in the style of the model picker's `formatContextSize`:
 * "25.5k", "1M" — one truncated decimal, dropped when zero. */
internal fun formatTokenCount(count: Long): String = when {
    count >= 1_000_000 -> scaled(count, 1_000_000, "M")
    count >= 1_000 -> scaled(count, 1_000, "k")
    else -> "$count"
}

private fun scaled(count: Long, divisor: Long, suffix: String): String {
    val whole = count / divisor
    val tenth = count % divisor * 10 / divisor
    return if (tenth == 0L) "$whole$suffix" else "$whole.$tenth$suffix"
}
