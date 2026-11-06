package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.data.chat.MAIN_AGENT_ID
import fr.louissaglio.kimi.android.data.chat.PendingQuestion

/** Test tag of the approval card's scrollable plan body (ticket 173). */
internal const val APPROVAL_PLAN_TAG = "approval_plan"

/** The plan body's cap: about a dozen lines of Markdown, leaving the card's header and
 * Allow/Deny row on screen above the composer even on a small phone. */
private val PLAN_MAX_HEIGHT = 240.dp

/** The head of the approval queue, docked above the composer. A `plan_review` request
 * (ticket 173) adds the plan Markdown in a scrollable, height-capped body; every other
 * request shows the tool name and its compact summary only. */
@Composable
internal fun ApprovalCard(
    approval: ChatViewModel.PendingApproval,
    queueSize: Int,
    subagentName: String? = null,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = buildString {
                    append("Permission request")
                    if (queueSize > 1) append(" — 1 of $queueSize")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            // Ticket 139 (web parity): a sub-agent's request names the asking member —
            // the record's name/description from the store, or the plain fallback while
            // the record has not landed (the request can race ahead of subagent.spawned).
            if (approval.agentId != MAIN_AGENT_ID) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        text = if (subagentName != null) "Sub-agent · $subagentName" else "Sub-agent",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Text(text = approval.toolName, style = MaterialTheme.typography.titleSmall)
            if (approval.summary.isNotBlank()) {
                Text(
                    text = approval.summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Ticket 173: an `ExitPlanMode` review shows the plan it asks to approve. The
            // body scrolls inside a height-capped inset, so a long plan never pushes
            // Allow/Deny out of the bottom dock.
            approval.plan?.let { plan ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = PLAN_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .testTag(APPROVAL_PLAN_TAG),
                    ) {
                        MarkdownBody(plan)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onDeny) { Text("Deny") }
                Button(onClick = onAllow) { Text("Allow") }
            }
        }
    }
}

/** One item per step (ticket 035): stacking every item pushed the card past the screen
 * for multi-item questions, clipping the top options. The wizard paginates the items —
 * Back/Next walk them, Submit replaces Next on the last one — and the scrollable,
 * height-capped item area is the safety net for a single item with a long option list.
 *
 * Read-only mode (ticket 089): a resolved question viewed from its timeline row —
 * radios/checkboxes disabled with the given answers checked, the free-text answer in a
 * disabled field, no Dismiss/Submit (Back/Next paginate without gating), the header's
 * button becomes a close (X) and its label states the outcome. [note] carries the
 * dismissal note for dismissed questions.
 *
 * Multi-select items (ticket 090) render Checkbox rows toggling the item's selection
 * set; single-select items keep RadioButton rows. Same row layout, labels and
 * descriptions either way. */
@Composable
internal fun QuestionCard(
    question: PendingQuestion,
    queueSize: Int,
    subagentName: String? = null,
    step: Int,
    selections: Map<String, Set<String>>,
    otherTexts: Map<String, String>,
    isStepAnswered: Boolean,
    isSubmittable: Boolean,
    onOptionPicked: (itemId: String, optionId: String) -> Unit,
    onOptionToggled: (itemId: String, optionId: String) -> Unit,
    onOtherTextChange: (itemId: String, text: String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    onMinimize: () -> Unit,
    readOnly: Boolean = false,
    note: String? = null,
    onClose: () -> Unit = {},
) {
    val item = question.items.getOrNull(step) ?: return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        append("Question from Kimi")
                        if (readOnly) append(if (note != null) " · dismissed" else " · answered")
                        if (queueSize > 1) append(" — 1 of $queueSize")
                        if (question.items.size > 1) append(" · item ${step + 1} of ${question.items.size}")
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (readOnly) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                } else {
                    // Ticket 088: minimize hides the card (drafts kept); the pending
                    // AskUserQuestion tool row in the timeline reopens it. Unlike Dismiss
                    // this is purely local.
                    IconButton(onClick = onMinimize) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Minimize question",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            // Ticket 156 (the approval badge's idiom, 139 — byte-identical): a
            // sub-agent's question names the asking member — the record's
            // name/description from the store, or the plain fallback while the record
            // has not landed (the request can race ahead of subagent.spawned).
            if (question.agentId != MAIN_AGENT_ID) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        text = if (subagentName != null) "Sub-agent · $subagentName" else "Sub-agent",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.header.isNotBlank()) {
                    Text(text = item.header, style = MaterialTheme.typography.labelMedium)
                }
                Text(text = item.text, style = MaterialTheme.typography.titleSmall)
                item.options.forEach { option ->
                    val toggle = { onOptionToggled(item.id, option.id) }
                    val pick = { onOptionPicked(item.id, option.id) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable(enabled = !readOnly) { if (item.multiSelect) toggle() else pick() },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.multiSelect) {
                            Checkbox(
                                checked = option.id in selections[item.id].orEmpty(),
                                onCheckedChange = { toggle() },
                                // Keep the handler (null drops the toggleable semantics in
                                // current M3); read-only means disabled, still announced.
                                enabled = !readOnly,
                            )
                        } else {
                            RadioButton(
                                selected = option.id in selections[item.id].orEmpty(),
                                onClick = if (readOnly) null else pick,
                            )
                        }
                        Column {
                            Text(text = option.label, style = MaterialTheme.typography.bodyMedium)
                            option.description?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (item.allowOther) {
                    OutlinedTextField(
                        value = otherTexts[item.id].orEmpty(),
                        onValueChange = { onOtherTextChange(item.id, it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Other answer…") },
                        singleLine = true,
                        enabled = !readOnly,
                    )
                }
                note?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (readOnly) {
                    // Read-only (089): pagination only — nothing to submit or dismiss.
                    if (step > 0) {
                        OutlinedButton(onClick = onBack) { Text("‹ Back") }
                    }
                    if (step < question.items.lastIndex) {
                        Button(onClick = onNext) { Text("Next ›") }
                    }
                } else {
                    OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
                    if (step > 0) {
                        OutlinedButton(onClick = onBack) { Text("‹ Back") }
                    }
                    if (step < question.items.lastIndex) {
                        Button(onClick = onNext, enabled = isStepAnswered) { Text("Next ›") }
                    } else {
                        Button(onClick = onSubmit, enabled = isSubmittable) { Text("Submit") }
                    }
                }
            }
        }
    }
}
