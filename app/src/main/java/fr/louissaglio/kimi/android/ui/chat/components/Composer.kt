package fr.louissaglio.kimi.android.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ChatInputBar(
    text: String,
    isSending: Boolean,
    isWorking: Boolean,
    attachments: List<ChatViewModel.PendingAttachment>,
    modelLabel: String?,
    onModelClick: () -> Unit,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onAttachmentRemoved: (String) -> Unit,
) {
    // The composer stays live mid-turn: while the agent works, the send action steers the
    // message into the running turn instead of starting a new prompt (docs/server-api.md,
    // Steer) — the button swaps to a Steer label so the user knows which one will fire.
    // Attachments alone are sendable (ticket 033); text alone unchanged.
    val canSend = (text.isNotBlank() || attachments.isNotEmpty()) && !isSending
    // imePadding docks the bar above the keyboard (the manifest sets adjustResize).
    Surface(modifier = Modifier.imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (attachments.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(attachments, key = ChatViewModel.PendingAttachment::localId) { attachment ->
                        AttachmentChip(attachment = attachment, onRemove = { onAttachmentRemoved(attachment.localId) })
                    }
                }
            }
            // One rounded container echoes the web composer pill (ticket 038): the field
            // spans the full width on top, an action row below holds attach, the model
            // chip and send. The field carries no outline or fill of its own — the
            // container is the boundary.
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column {
                    TextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Message input" },
                        placeholder = { Text(if (isWorking) "Steer the running turn…" else "Message Kimi…") },
                        // Grows with content up to 5 lines, then scrolls internally.
                        maxLines = 5,
                        // No ImeAction.Send: on a soft keyboard it would replace the return
                        // key and block newlines — a real loss for multi-line prompts (code,
                        // logs). The on-screen send/steer button below is the only send path.
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = onAttach,
                            enabled = !isSending,
                            modifier = Modifier.semantics { contentDescription = "Attach a file" },
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                        }
                        modelLabel?.let { label -> ModelChip(label = label, onClick = onModelClick) }
                        Spacer(Modifier.weight(1f))
                        if (isWorking) {
                            Button(
                                onClick = onSend,
                                enabled = canSend,
                                // M3 Buttons default to 40dp tall; the composer row
                                // keeps every touch target at 48dp.
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                Text("Steer")
                            }
                        } else {
                            FilledIconButton(
                                onClick = onSend,
                                enabled = canSend,
                                modifier = Modifier.semantics { contentDescription = "Send" },
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Model/effort selector inside the composer (ticket 038): a low-key row with a
 * chevron, opening the same ModelPickerSheet the top-bar chip used to. The whole
 * row keeps the ≥ 48dp M3 touch target the web's compact hit area lacks. */
@Composable
private fun ModelChip(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(start = 4.dp)
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Model: $label" }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One staged attachment above the input (ticket 033): a bounded thumbnail for images,
 * a generic sheet glyph otherwise, then name + size. A spinner marks the in-flight
 * upload, an error tint a failed one (the send snackbar's Retry re-tries); the ×
 * removes the chip before the send. */
@Composable
private fun AttachmentChip(
    attachment: ChatViewModel.PendingAttachment,
    onRemove: () -> Unit,
) {
    val failed = attachment.status == ChatViewModel.PendingAttachment.Status.Failed
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AttachmentThumbnail(attachment)
            Column {
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp),
                )
                Text(
                    text = formatFileSize(attachment.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (attachment.status == ChatViewModel.PendingAttachment.Status.Uploading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(14.dp)
                        .semantics { contentDescription = "Uploading ${attachment.name}" },
                    strokeWidth = 1.5.dp,
                )
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onRemove)
                    .semantics { contentDescription = "Remove ${attachment.name}" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Image chips decode a bounded thumbnail off the main thread; other types get the
 * same generic sheet glyph the timeline uses for copy actions. */
@Composable
private fun AttachmentThumbnail(attachment: ChatViewModel.PendingAttachment) {
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, attachment.bytes) {
        if (attachment.isImage) {
            value = withContext(Dispatchers.Default) { decodeSampledThumbnail(attachment.bytes.value, THUMBNAIL_PX) }
        }
    }
    val bitmap = thumbnail
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop,
        )
    } else {
        CopyGlyph(
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            background = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .size(36.dp)
                .padding(8.dp),
        )
    }
}

internal const val THUMBNAIL_PX = 128

/** Two-pass decode: bounds first to pick a power-of-two sample size, then the pixels —
 * a 12 MP photo never lands in memory at full size for a 36dp chip. */
internal fun decodeSampledThumbnail(bytes: ByteArray, targetPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

/** "340 kB", "1.2 MB" — decimal units, like the web client's chip labels. */
internal fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
