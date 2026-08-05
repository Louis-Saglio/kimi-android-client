package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.ui.theme.successColor

/** Tool-status glyph for the group header and the rows (tickets 022/037): spinner
 * while running, green check on success, red cross on error. [runningDescription]
 * feeds the spinner's semantics ("Tool running" for a row, "Tools running" for a
 * group header). */
@Composable
private fun ToolStatusIcon(status: ToolStatus, runningDescription: String, modifier: Modifier = Modifier) {
    when (status) {
        ToolStatus.Running -> CircularProgressIndicator(
            modifier = modifier.semantics { contentDescription = runningDescription },
            strokeWidth = 1.5.dp,
        )
        // No success slot in the M3 scheme: a literal green, readable on both themes.
        ToolStatus.Done -> Text(
            text = "✓",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.successColor,
        )
        ToolStatus.Error -> Text(
            text = "✗",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** A run of consecutive tool calls as one card (ticket 037), mirroring the web
 * client's collapsed group: a header with the aggregate status — spinner while any
 * call runs, error mark if any failed, success check when all are done — then the
 * rows themselves, each keeping its own expand/collapse.
 *
 * M3 outlined card, matching the web's "lighter fill + thin grey border": the fill is
 * `surfaceContainerLow` (a step above the chat background in both themes), the border
 * the default 1dp `outlineVariant` hairline — no elevation, the chat surface is flat. */
@Composable
internal fun ToolGroupCard(
    group: ChatItem.ToolGroup,
    onOpenSubagent: (String) -> Unit = {},
    onOpenJob: (String) -> Unit = {},
    onQuestionRowTapped: (toolCallId: String, input: String, output: String) -> Unit = { _, _, _ -> },
) {
    val status = when {
        group.tools.any { it.status == ToolStatus.Running } -> ToolStatus.Running
        group.tools.any { it.status == ToolStatus.Error } -> ToolStatus.Error
        else -> ToolStatus.Done
    }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToolStatusIcon(status, runningDescription = "Tools running", modifier = Modifier.size(12.dp))
                Text(
                    text = buildString {
                        append(group.tools.size)
                        append(if (group.tools.size == 1) " tool call · " else " tool calls · ")
                        append(
                            when (status) {
                                ToolStatus.Running -> "running"
                                ToolStatus.Done -> "done"
                                ToolStatus.Error -> "error"
                            },
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            group.tools.forEachIndexed { index, tool ->
                // 1dp outlineVariant hairline between rows, like the web's row separators.
                if (index > 0) HorizontalDivider()
                ToolRow(tool, onOpenSubagent, onOpenJob, onQuestionRowTapped)
            }
        }
    }
}

/** Web-style compact tool row (ticket 022): leading tool icon, name, one-line
 * monospace preview of the input with end-ellipsis, and the status as a glyph —
 * spinner while running, green check on success, red cross on error.
 *
 * Tapping a row that has details expands it (ticket 023, chevron rotates per web):
 * the full call input as pretty-printed JSON, then the result output — monospace,
 * preformatted, in a capped-height region that scrolls internally so a huge test
 * log does not tunnel the timeline. Error output sits on an error-tinted surface.
 * The top-anchored chat list keeps the tapped header in place, so the details
 * unfold downward.
 *
 * An `Agent` row linked to its sub-agent (ticket 045) never expands: the sub-agent
 * page already shows everything the details would, so tapping the row navigates to
 * that page, as does the trailing open button (chevron-right, own tap target).
 *
 * A background `Bash` row linked to its job (ticket 096, [ChatItem.ToolActivity.bashTaskId])
 * behaves the same way: its result is only the `task_id:` stub — the command's real
 * output lives on the job screen, so tapping the row or its trailing "Open job"
 * chevron navigates there instead of expanding.
 *
 * `AskUserQuestion` rows (tickets 088/089) never expand either and show no chevron:
 * the raw JSON is superseded by the card UI. The tap is forwarded as
 * [onQuestionRowTapped] with the row's id (= tool call id = question id), input and
 * output — the collaborator decides: the pending row toggles the live card, a
 * resolved row opens/closes its read-only view. */
@Composable
internal fun ToolRow(
    activity: ChatItem.ToolActivity,
    onOpenSubagent: (String) -> Unit = {},
    onOpenJob: (String) -> Unit = {},
    onQuestionRowTapped: (toolCallId: String, input: String, output: String) -> Unit = { _, _, _ -> },
) {
    val detailColor = MaterialTheme.colorScheme.onSurfaceVariant
    val linkedSubagentId = activity.subagentId
    val linkedBashTaskId = activity.bashTaskId
    val isQuestionRow = activity.name == "AskUserQuestion"
    val expandable = !isQuestionRow && linkedSubagentId == null && linkedBashTaskId == null &&
        (activity.input.isNotBlank() || activity.output.isNotBlank())
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // M3: the whole row is the tap target, at least 48dp tall.
                .heightIn(min = 48.dp)
                .clickable(enabled = expandable || linkedSubagentId != null || linkedBashTaskId != null || isQuestionRow) {
                    if (isQuestionRow) onQuestionRowTapped(activity.id, activity.input, activity.output)
                    else if (expandable) expanded = !expanded
                    else if (linkedSubagentId != null) onOpenSubagent(linkedSubagentId)
                    else linkedBashTaskId?.let(onOpenJob)
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Terminal glyph for shell calls (material-icons-core has no terminal icon),
            // a generic tool icon otherwise.
            if (activity.name == "Bash") {
                Text(
                    text = ">_",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = detailColor,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Build,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = detailColor,
                )
            }
            Text(
                text = activity.name.ifBlank { "tool" },
                style = MaterialTheme.typography.labelMedium,
                color = detailColor,
            )
            if (activity.preview.isNotBlank()) {
                Text(
                    text = activity.preview,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = detailColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            ToolStatusIcon(activity.status, runningDescription = "Tool running", modifier = Modifier.size(12.dp))
            if (expandable) {
                val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse tool details" else "Expand tool details",
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(chevronRotation),
                    tint = detailColor,
                )
            }
            if (linkedSubagentId != null) {
                IconButton(
                    onClick = { onOpenSubagent(linkedSubagentId) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open sub-agent",
                        modifier = Modifier.size(16.dp),
                        tint = detailColor,
                    )
                }
            }
            if (linkedBashTaskId != null) {
                IconButton(
                    onClick = { onOpenJob(linkedBashTaskId) },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open job",
                        modifier = Modifier.size(16.dp),
                        tint = detailColor,
                    )
                }
            }
        }
        if (expanded && expandable) {
            Column(
                modifier = Modifier.padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (activity.input.isNotBlank()) {
                    ToolDetailBlock(text = activity.input, color = MaterialTheme.colorScheme.surfaceVariant)
                }
                if (activity.output.isNotBlank()) {
                    ToolDetailBlock(
                        text = activity.output,
                        color = if (activity.status == ToolStatus.Error) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        // Live tool.progress chunks (ticket 025): keep the tail in view.
                        pinnedToBottom = activity.status == ToolStatus.Running,
                    )
                }
            }
        }
    }
}

/** One preformatted monospace block of the expanded tool row. The height is capped
 * and scrolls internally, so a very long output never stretches the timeline.
 * [pinnedToBottom] (a running tool's live output, ticket 025) re-scrolls to the tail
 * every time the text grows. */
@Composable
private fun ToolDetailBlock(text: String, color: Color, pinnedToBottom: Boolean = false) {
    val scrollState = rememberScrollState()
    if (pinnedToBottom) {
        LaunchedEffect(text) { scrollState.scrollTo(scrollState.maxValue) }
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
