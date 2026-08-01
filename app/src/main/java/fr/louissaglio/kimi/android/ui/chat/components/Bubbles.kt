package fr.louissaglio.kimi.android.ui.chat

import android.content.ClipData
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.ui.common.formatTimestamp
import fr.louissaglio.kimi.android.ui.theme.successColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Copy-menu idiom shared by the message bubbles (ticket 039): a long-press — via
 * [longPressable], so the markdown renderer's link taps and the attachment/image
 * clickables inside [content] keep working — or the TalkBack long-click opens a
 * dropdown with a Copy entry. Copy places the raw message text on the clipboard and
 * flips [copied] for 1.5s so the caller can show a brief "✓ Copied" confirmation. */
@Composable
private fun CopyableMessage(
    text: String,
    copied: Boolean,
    onCopiedChange: (Boolean) -> Unit,
    content: @Composable (longPress: Modifier) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            onCopiedChange(false)
        }
    }
    Box {
        content(
            Modifier
                .longPressable { menuExpanded = true }
                .semantics {
                    onLongClick(label = "Message options") {
                        menuExpanded = true
                        true
                    }
                },
        )
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Copy") },
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(text, text)))
                        onCopiedChange(true)
                        menuExpanded = false
                    }
                },
            )
        }
    }
}

/** User prompt bubble (ticket 039): the web's hover actions become a long-press →
 * dropdown menu (M3 touch idiom) with a Copy entry — [CopyableMessage] — and the server
 * timestamp shows as a subtle caption under the bubble, right-aligned with it. The
 * caption swaps to a green "✓ Copied" for a moment after a copy; [AssistantBubble]
 * shares the idiom. */
@Composable
internal fun UserBubble(
    message: ChatItem.UserMessage,
    onImageClick: (ChatMessage.Part.Image) -> Unit = {},
    onOpenAttachment: (ChatMessage.Part.Attachment) -> Unit = {},
    loadImageBytes: suspend (ChatMessage.Part.Image) -> ByteArray? = { null },
) {
    var copied by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(horizontalAlignment = Alignment.End) {
            CopyableMessage(message.text, copied, onCopiedChange = { copied = it }) { longPress ->
                // Web parity: tighter trailing corner on the user bubble.
                val bubbleShape = MaterialTheme.shapes.large.copy(bottomEnd = CornerSize(4.dp))
                Surface(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clip(bubbleShape)
                        .then(longPress),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = bubbleShape,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (message.text.isNotBlank()) MarkdownBody(text = message.text)
                        message.images.forEach { image ->
                            HistoryImageThumbnail(
                                image = image,
                                loadImageBytes = loadImageBytes,
                                onClick = { onImageClick(image) },
                            )
                        }
                        message.attachments.forEach { attachment ->
                            AttachmentChipRow(attachment = attachment, onClick = { onOpenAttachment(attachment) })
                        }
                    }
                }
            }
            val caption = when {
                copied -> "✓ Copied"
                message.createdAt != null -> formatTimestamp(message.createdAt)
                else -> null
            }
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (copied) MaterialTheme.successColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, end = 4.dp),
                )
            }
        }
    }
}

/** Web-parity assistant row (ticket 024): full-width plain markdown on the chat
 * background — no bubble. Live and final messages share this path, so streaming
 * deltas get the same layout. Thinking stays above the reply, dimmed. Copy uses the
 * same long-press → menu idiom as the user bubble (ticket 039 follow-up); the raw
 * markdown source goes to the clipboard and a brief "✓ Copied" caption confirms. */
@Composable
internal fun AssistantBubble(message: ChatItem.AssistantMessage) {
    var copied by remember { mutableStateOf(false) }
    CopyableMessage(message.text, copied, onCopiedChange = { copied = it }) { longPress ->
        Column(
            Modifier
                .fillMaxWidth()
                .then(longPress),
        ) {
            if (message.thinking.isNotBlank()) {
                Text(
                    text = message.thinking,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message.text.isNotBlank()) Spacer(Modifier.size(6.dp))
            }
            if (message.text.isNotBlank()) MarkdownBody(text = message.text)
            if (copied) {
                Text(
                    text = "✓ Copied",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.successColor,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** material-icons-core has no copy icon: two overlapping outlined sheets, the
 * front one filled with the chat background so the strokes do not cross. */
@Composable
internal fun CopyGlyph(tint: Color, background: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.2.dp.toPx())
        val radius = CornerRadius(2.dp.toPx())
        val sheet = Size(size.width * 0.62f, size.height * 0.62f)
        // Back sheet, top-right.
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width - sheet.width, 0f),
            size = sheet,
            cornerRadius = radius,
            style = stroke,
        )
        // Front sheet, bottom-left, filled to punch out the back sheet's stroke.
        drawRoundRect(
            color = background,
            topLeft = Offset(0f, size.height - sheet.height),
            size = sheet,
            cornerRadius = radius,
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, size.height - sheet.height),
            size = sheet,
            cornerRadius = radius,
            style = stroke,
        )
    }
}

/** Long-press detector for the message rows' copy menu (ticket 039). Unlike
 * `combinedClickable`, it never consumes the down event: the markdown renderer's
 * link taps and the attachment/image clickables watch the stream without consuming
 * either, so a consuming parent would silently cancel them. This watches the
 * Initial pass — taps, scrolls and drags pass through to the children untouched;
 * only a finger held still past the long-press timeout fires, and the release of
 * that long-press is swallowed so the child under the finger does not also fire. */
private fun Modifier.longPressable(onLongPress: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        while (true) {
            val event = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                awaitPointerEvent(PointerEventPass.Initial)
            }
            if (event == null) {
                onLongPress()
                awaitPointerEvent(PointerEventPass.Initial).changes
                    .forEach { if (it.changedToUpIgnoreConsumed()) it.consume() }
                break
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break // released in time: a tap for the children
            if ((change.position - down.position).getDistance() > slop) break // scroll or drag
        }
    }
}
