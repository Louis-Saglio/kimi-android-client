package fr.louissaglio.kimi.android.ui.chat

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import fr.louissaglio.kimi.android.data.chat.EditDiffs
import fr.louissaglio.kimi.android.ui.common.EmptyState
import fr.louissaglio.kimi.android.ui.theme.diffColors
import fr.louissaglio.kimi.android.ui.theme.successColor

/**
 * The Edit call's unified diff screen (ticket 119): the title row carries the
 * filename and the "+a −b" line stats, the full path goes underneath, and the body
 * is the line-level diff of the call's old/new strings — removed lines on an
 * error-tinted container with a "−" gutter, added lines on a success-tinted
 * container with "+", monospace throughout; runs of unchanged context beyond
 * [EditDiffs.COLLAPSE_THRESHOLD] fold into a tappable "N unchanged lines" expander.
 * An edit with identical old/new shows "No changes". The content is staged in the
 * [ToolContentStore] by the navigating row tap — a store miss (process death with
 * this screen on top) renders the missing-content state; back is the way out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDiffScreen(
    onNavigateBack: () -> Unit,
    viewModel: EditDiffViewModel = hiltViewModel(),
) {
    val payload = viewModel.payload
    val diff = viewModel.diff
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (payload != null && diff != null) {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = payload.path.substringAfterLast('/'),
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "+${diff.addedCount}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.successColor,
                                )
                                Text(
                                    text = "−${diff.removedCount}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Text(
                                text = payload.path,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            text = viewModel.toolCallId,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        // Body width capped and centered on large screens (the JobDetailScreen recipe).
        Box(
            Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                payload == null || diff == null -> MissingContentState(onNavigateBack)
                !diff.hasChanges -> EmptyState("No changes")
                else -> DiffContent(diff)
            }
        }
    }
}

@Composable
private fun DiffContent(diff: EditDiffs.Model) {
    val animationsEnabled = rememberAnimationsEnabled()
    val diffColors = MaterialTheme.diffColors
    LazyColumn(
        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        // Static content (only the expanders toggle): positional keys are fine, and
        // each Collapsed row's rememberSaveable rides the item key.
        items(diff.rows.size) { index ->
            when (val row = diff.rows[index]) {
                is EditDiffs.Row.Added ->
                    DiffLineRow("+", row.text, diffColors.addedContainer, diffColors.addedContent)
                is EditDiffs.Row.Removed ->
                    DiffLineRow("−", row.text, diffColors.removedContainer, diffColors.removedContent)
                is EditDiffs.Row.Context ->
                    DiffLineRow(" ", row.text, container = null, content = Color.Unspecified)
                is EditDiffs.Row.Collapsed -> CollapsedContextRow(row, animationsEnabled)
            }
        }
    }
}

/** One diff line: a fixed-width gutter glyph (−/+/blank) ahead of the monospace
 * content, the removed/added rows tinted by their [container]. */
@Composable
private fun DiffLineRow(gutter: String, text: String, container: Color?, content: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (container != null) Modifier.background(container) else Modifier)
            .padding(horizontal = 8.dp, vertical = 1.dp),
    ) {
        Text(
            text = gutter,
            modifier = Modifier.width(20.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (content == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else content,
            textAlign = TextAlign.Center,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = content,
        )
    }
}

/** The folded run of unchanged context (ticket 119): one row reading
 * "N unchanged lines", tapping it reveals the lines in place (rememberSaveable —
 * the state survives scroll-out and recomposition). The reveal animation honors
 * the system's reduced-motion setting. */
@Composable
private fun CollapsedContextRow(row: EditDiffs.Row.Collapsed, animationsEnabled: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = if (animationsEnabled) tween() else snap(),
                label = "contextChevron",
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) {
                    "Collapse ${row.count} unchanged lines"
                } else {
                    "Expand ${row.count} unchanged lines"
                },
                modifier = Modifier.size(16.dp).rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${row.count} unchanged lines",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (animationsEnabled) {
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column { row.lines.forEach { DiffLineRow(" ", it, container = null, content = Color.Unspecified) } }
            }
        } else if (expanded) {
            Column { row.lines.forEach { DiffLineRow(" ", it, container = null, content = Color.Unspecified) } }
        }
    }
}

/** The store-miss state (process death with this screen on top): the job screen's
 * error-state shape, with Back as the only action — there is nothing to retry,
 * re-tapping the chat row re-stages the payload. */
@Composable
private fun MissingContentState(onNavigateBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "This diff is no longer available — reopen it from the chat.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onNavigateBack) {
            Text("Back")
        }
    }
}

/** Reduced motion: the system animator scale at 0 (the accessibility/developer
 * toggle) swaps the expander reveal for an instant swap. */
@Composable
private fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}
