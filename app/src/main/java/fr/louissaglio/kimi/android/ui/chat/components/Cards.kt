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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.data.chat.PendingQuestion

@Composable
internal fun ApprovalCard(
    approval: ChatViewModel.PendingApproval,
    queueSize: Int,
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
            Text(text = approval.toolName, style = MaterialTheme.typography.titleSmall)
            if (approval.summary.isNotBlank()) {
                Text(
                    text = approval.summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
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
 * height-capped item area is the safety net for a single item with a long option list. */
@Composable
internal fun QuestionCard(
    question: PendingQuestion,
    queueSize: Int,
    step: Int,
    selections: Map<String, String>,
    otherTexts: Map<String, String>,
    isStepAnswered: Boolean,
    isSubmittable: Boolean,
    onOptionPicked: (itemId: String, optionId: String) -> Unit,
    onOtherTextChange: (itemId: String, text: String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
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
            Text(
                text = buildString {
                    append("Question from Kimi")
                    if (queueSize > 1) append(" — 1 of $queueSize")
                    if (question.items.size > 1) append(" · item ${step + 1} of ${question.items.size}")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onOptionPicked(item.id, option.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selections[item.id] == option.id,
                            onClick = { onOptionPicked(item.id, option.id) },
                        )
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
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
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
