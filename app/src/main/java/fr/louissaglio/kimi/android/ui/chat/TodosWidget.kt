package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.data.chat.Todo

/**
 * The collapsed todo widget (ticket 042), pinned directly above the composer: count
 * only — the user's explicit choice over showing the in-progress task title. Reads as
 * attached to the composer via surfaceContainerHigh, like the approval/question cards.
 * Wraps its content instead of filling the width: future composer widgets (Bash- or
 * subagent-style counters) will share the row.
 */
@Composable
internal fun TodosSummaryRow(
    todos: List<Todo>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val done = todos.count { it.status == Todo.Status.Done }
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Todos, $done of ${todos.size} done, tap to view"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.List,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Todos · $done/${todos.size}",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Read-only detail sheet for the todo widget (ticket 042): same transient-detail
 * pattern as the question wizard and the model picker. No toggling or reordering —
 * the list is agent-owned. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodosSheet(
    todos: List<Todo>,
    onDismiss: () -> Unit,
) {
    val done = todos.count { it.status == Todo.Status.Done }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                text = "Todos · $done/${todos.size}",
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            todos.forEach { todo ->
                SheetCheckRow(
                    title = todo.title,
                    checked = false,
                    onClick = null,
                    titleColor = if (todo.status == Todo.Status.Done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    titleDecoration = if (todo.status == Todo.Status.Done) TextDecoration.LineThrough else null,
                    leading = { TodoStatusIcon(todo.status) },
                )
            }
        }
    }
}

/** Status glyph for a sheet row: a checked circle for done, a dotted ring for
 * in-progress, a plain ring for pending — drawn, since material-icons-core has no
 * half-filled circle. The description feeds the merged row semantics. */
@Composable
private fun TodoStatusIcon(status: Todo.Status) {
    when (status) {
        Todo.Status.Done -> Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Done",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Todo.Status.InProgress -> Box(
            modifier = Modifier
                .size(20.dp)
                .semantics { contentDescription = "In progress" }
                .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                .padding(5.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Todo.Status.Pending -> Box(
            modifier = Modifier
                .size(20.dp)
                .semantics { contentDescription = "Pending" }
                .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
        )
    }
}
