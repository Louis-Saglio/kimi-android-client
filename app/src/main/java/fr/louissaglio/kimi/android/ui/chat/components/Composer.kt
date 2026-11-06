package fr.louissaglio.kimi.android.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.data.chat.ContextUsage
import fr.louissaglio.kimi.android.ui.common.formatFileSize
import fr.louissaglio.kimi.android.ui.common.icons.SparklesIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ChatInputBar(
    text: String,
    isSending: Boolean,
    isWorking: Boolean,
    attachments: List<PendingAttachment>,
    modelLabel: String?,
    /** The chip's effective effort (ticket 108); null on effort-less models. */
    modelEffort: String?,
    /** The session's effective permission mode (ticket 141); null hides the chip
     * until the `agent` frame or a stored pick lands. */
    permissionMode: PermissionMode?,
    /** The composer's swarm chip (ticket 174): on = the next prompt carries
     * `swarm_mode:true`, off = `false`. */
    swarmMode: Boolean,
    contextUsage: ContextUsage?,
    compactEnabled: Boolean,
    onCompact: () -> Unit,
    onModelClick: () -> Unit,
    onPermissionClick: () -> Unit,
    onSwarmModeToggle: () -> Unit,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
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
                    items(attachments, key = PendingAttachment::localId) { attachment ->
                        AttachmentChip(attachment = attachment, onRemove = { onAttachmentRemoved(attachment.localId) })
                    }
                }
            }
            // One rounded container echoes the web composer pill (ticket 038): the field
            // spans the full width on top, an action row below holds attach, the model
            // chip, the context ring, the permission chip (ticket 141), the swarm chip
            // (ticket 174) and stop/send (ticket 053). The field carries no
            // outline or fill of its own — the container is the boundary.
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
                        // Attach and stop/send are pinned to the row's ends; the selectors
                        // and the ring sit in this weighted middle zone, where the model
                        // chip ellipsizes under pressure instead of pushing the turn
                        // controls off screen — with all five elements present the row
                        // overflowed and the send button was clipped.
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            modelLabel?.let { label ->
                                ModelChip(
                                    model = label,
                                    effort = modelEffort,
                                    onClick = onModelClick,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                            // Context usage right of the model chip (ticket 053): model-scoped
                            // info next to the model it belongs to; hidden until known. The
                            // ring's dialog carries the compact affordance at ≥80% (057).
                            contextUsage?.let {
                                ContextUsageRing(it, compactEnabled = compactEnabled, onCompact = onCompact)
                            }
                            permissionMode?.let { PermissionModeChip(mode = it, onClick = onPermissionClick) }
                            SwarmModeChip(selected = swarmMode, onToggle = onSwarmModeToggle)
                        }
                        if (isWorking) {
                            // Stop sits with the turn controls (ticket 053), for app- and
                            // web-started turns alike. The filled M3 error/onError pair —
                            // not the tonal errorContainer/error one, which inverts in dark
                            // theme (dark circle, pale glyph): here the glyph stays darker
                            // than the circle. No stop glyph in material-icons-core: a
                            // plain filled square.
                            FilledIconButton(
                                onClick = onStop,
                                modifier = Modifier.semantics { contentDescription = "Stop" },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                            ) {
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .clip(MaterialTheme.shapes.extraSmall)
                                        .background(MaterialTheme.colorScheme.onError),
                                )
                            }
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
 * chevron, opening the ModelPickerSheet. The label carries the effective effort next
 * to the model name (ticket 108 — revisiting 032's sheet-only call), so a glance tells
 * both. The caller caps the chip's width in the composer's weighted middle zone: under
 * pressure the label ellipsizes so the pinned turn controls keep their space. The
 * whole row keeps the ≥ 48dp M3 touch target the web's compact hit area lacks. */
@Composable
private fun ModelChip(model: String, effort: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(start = 4.dp)
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (effort != null) "Model: $model, effort $effort" else "Model: $model"
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (effort != null) "$model · $effort" else model,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Permission-mode selector after the context ring (ticket 141): icon-only, the mode's
 * own glyph (Hand/Shield/Bolt) in the mode's risk tint plus a chevron, opening the
 * PermissionPickerSheet. The mode changes rarely, so the chip's resting job is the
 * risk signal, which the glyph and tint carry alone; the label lives in the
 * contentDescription and the sheet. Dropping the text keeps the composer row from
 * overflowing with all five elements present. Same 48dp touch-target idiom as the
 * model chip. */
@Composable
private fun PermissionModeChip(mode: PermissionMode, onClick: () -> Unit) {
    val tint = mode.tint
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Permission mode: ${mode.label}" }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = mode.icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
    }
}

/** Swarm-mode toggle after the permission chip (ticket 174, the web's composer
 * "Swarm" chip): the sparkles glyph plus a "Swarm" label, on the selected
 * (secondaryContainer) fill when on, transparent when off. Its state rides every
 * prompt as `swarm_mode`. The label ellipsizes rather than clipping when the
 * middle zone runs short (a running turn's Stop + Steer); 48dp touch target like
 * the other chips. */
@Composable
private fun SwarmModeChip(selected: Boolean, onToggle: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val content = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.large)
            .toggleable(value = selected, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { contentDescription = "Toggle swarm mode" }
            .testTag(SWARM_MODE_CHIP_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(container)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = SparklesIcon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = content,
            )
            Text(
                text = "Swarm",
                style = MaterialTheme.typography.labelMedium,
                color = content,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Stable test tag of the composer's swarm chip (ticket 174). */
internal const val SWARM_MODE_CHIP_TAG = "swarmModeChip"

/** One staged attachment above the input (ticket 033): a bounded thumbnail for images,
 * a generic sheet glyph otherwise, then name + size. A spinner marks the in-flight
 * upload, an error tint a failed one (the send snackbar's Retry re-tries); the ×
 * removes the chip before the send. */
@Composable
private fun AttachmentChip(
    attachment: PendingAttachment,
    onRemove: () -> Unit,
) {
    val failed = attachment.status == PendingAttachment.Status.Failed
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
            if (attachment.status == PendingAttachment.Status.Uploading) {
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
private fun AttachmentThumbnail(attachment: PendingAttachment) {
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
