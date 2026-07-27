package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An image of a history user message (ticket 034): a bounded-decode thumbnail —
 * the bytes come from the inline base64 or, for file-referenced sources, from a
 * /files fetch. A failed resolve falls back to the generic sheet glyph; a tap opens
 * the full-screen viewer. */
@Composable
internal fun HistoryImageThumbnail(
    image: ChatMessage.Part.Image,
    loadImageBytes: suspend (ChatMessage.Part.Image) -> ByteArray?,
    onClick: () -> Unit,
) {
    var resolved by remember(image) { mutableStateOf(false) }
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, image) {
        val bytes = loadImageBytes(image)
        if (bytes != null) {
            value = withContext(Dispatchers.Default) { decodeSampledThumbnail(bytes, THUMBNAIL_PX) }
        }
        resolved = true
    }
    val bitmap = thumbnail
    val shape = MaterialTheme.shapes.small
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "Attached image",
            modifier = Modifier
                .size(120.dp)
                .clip(shape)
                .clickable(onClick = onClick),
            contentScale = ContentScale.Crop,
        )
    } else if (resolved) {
        Box(
            Modifier
                .size(120.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            CopyGlyph(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                background = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    } else {
        Box(
            Modifier
                .size(120.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/** A non-image attachment inside a history bubble (ticket 034): generic sheet glyph,
 * name and size. Tappable only when the fileId was recovered from the notice — the
 * tap downloads the bytes and opens them in another app. */
@Composable
internal fun AttachmentChipRow(attachment: ChatMessage.Part.Attachment, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .clip(shape)
            .clickable(enabled = attachment.fileId != null, onClick = onClick)
            .semantics { contentDescription = "Attachment ${attachment.name}" },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CopyGlyph(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                background = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Column {
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                Text(
                    text = formatFileSize(attachment.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Tap-to-dismiss full-screen image viewer (ticket 034): the bytes resolve like the
 * thumbnail's, decoded bounded so a megapixel photo never lands in memory at full size. */
@Composable
internal fun FullScreenImageViewer(
    image: ChatMessage.Part.Image,
    loadImageBytes: suspend (ChatMessage.Part.Image) -> ByteArray?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap by produceState<ImageBitmap?>(initialValue = null, image) {
                val bytes = loadImageBytes(image)
                if (bytes != null) {
                    value = withContext(Dispatchers.Default) { decodeSampledThumbnail(bytes, FULL_IMAGE_PX) }
                }
            }
            val resolved = bitmap
            if (resolved != null) {
                Image(
                    bitmap = resolved,
                    contentDescription = "Attached image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                CircularProgressIndicator()
            }
        }
    }
}

private const val FULL_IMAGE_PX = 2048
