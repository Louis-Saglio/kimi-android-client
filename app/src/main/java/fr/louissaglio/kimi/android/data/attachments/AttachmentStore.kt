package fr.louissaglio.kimi.android.data.attachments

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Platform attachment I/O behind the chat screen (tickets 033/034): reads picked
 * content Uris into upload-ready bytes and persists downloaded bytes as FileProvider
 * Uris other apps can open. An interface so ViewModel unit tests can fake it — the
 * implementation needs a Context, which plain JVM tests do not have.
 */
interface AttachmentStore {

    /** Reads a picked content Uri, at most [MAX_PICK_BYTES] of bytes. Blocking: call
     * off the main thread. */
    fun read(uri: Uri): ReadResult

    /** Writes downloaded bytes to the cache dir and returns a FileProvider content Uri
     * other apps may read (ticket 034); null on any failure. Blocking. */
    fun writeToCache(name: String, bytes: ByteArray): Uri?

    /** One picked document resolved to upload-ready bytes (ticket 033). The display
     * name/size come from the provider's OpenableColumns, the MIME type from the
     * ContentResolver (the server re-sniffs it from the bytes anyway). */
    class Picked(
        val name: String,
        val sizeBytes: Long,
        val mimeType: String,
        val bytes: ByteArray,
    )

    sealed interface ReadResult {
        data class Ok(val picked: Picked) : ReadResult

        /** The file is over [MAX_PICK_BYTES]; nothing was staged. */
        data class TooLarge(val name: String) : ReadResult

        data class Unreadable(val name: String) : ReadResult
    }

    companion object {
        /** Pick-time size cap: the APP's own guard rail (ticket 059), not the server's
         * — server 0.31.0 removed its upload cap entirely (docs/server-api.md, Files;
         * uploads stream to disk, 413/41301 no longer occurs on /files). 256 MiB sits
         * comfortably above realistic phone picks while keeping a multi-GB video from
         * being copied into the cache and held in UiState memory. */
        const val MAX_PICK_BYTES = 256L * 1024 * 1024
    }
}

@Singleton
class AndroidAttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : AttachmentStore {

    override fun read(uri: Uri): AttachmentStore.ReadResult {
        val resolver = context.contentResolver
        var name: String? = null
        var size: Long = -1
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }
                    ?.let { name = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }
                    ?.let { size = cursor.getLong(it) }
            }
        }
        val displayName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "attachment"
        return readPicked(displayName, size, resolver.getType(uri)) {
            resolver.openInputStream(uri)
        }
    }

    override fun writeToCache(name: String, bytes: ByteArray): Uri? = runCatching {
        val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "attachment" }
        val file = File(dir, safeName).apply { writeBytes(bytes) }
        // The authority and the cache path match the manifest provider and
        // res/xml/file_paths.xml (ticket 034).
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}

/** The pick-read pipeline minus its ContentResolver plumbing, extracted so JVM
 * tests can drive it (ticket 063): [advertisedSize] is the provider's
 * OpenableColumns.SIZE hint (-1 when omitted — providers lie, so the capped
 * stream read stays the real guard). [max] is injectable so tests can exercise
 * the overflow path without streaming a real 256 MiB. */
internal fun readPicked(
    displayName: String,
    advertisedSize: Long,
    mimeType: String?,
    max: Long = AttachmentStore.MAX_PICK_BYTES,
    openStream: () -> InputStream?,
): AttachmentStore.ReadResult {
    // The advertised size is a hint only: the stream read below is capped too.
    if (advertisedSize > max) return AttachmentStore.ReadResult.TooLarge(displayName)
    val stream = runCatching { openStream() }.getOrNull()
        ?: return AttachmentStore.ReadResult.Unreadable(displayName)
    return when (val result = stream.use { it.readCapped(max) }) {
        is CappedRead.Ok -> AttachmentStore.ReadResult.Ok(
            AttachmentStore.Picked(
                name = displayName,
                sizeBytes = if (advertisedSize >= 0) advertisedSize else result.bytes.size.toLong(),
                mimeType = mimeType ?: "application/octet-stream",
                bytes = result.bytes,
            ),
        )
        CappedRead.Overflow -> AttachmentStore.ReadResult.TooLarge(displayName)
        CappedRead.Failed -> AttachmentStore.ReadResult.Unreadable(displayName)
    }
}

/** Outcome of a capped stream read (ticket 063): overflow reaches the caller
 * apart from I/O failure — the first maps to TooLarge, the second to
 * Unreadable. */
internal sealed interface CappedRead {
    class Ok(val bytes: ByteArray) : CappedRead
    data object Overflow : CappedRead
    data object Failed : CappedRead
}

/** Reads the whole stream, giving up ([CappedRead.Overflow]) as soon as it
 * exceeds [max] bytes; [CappedRead.Failed] when the stream breaks mid-read.
 * Internal for the JVM boundary test (tickets 059/063). */
internal fun InputStream.readCapped(max: Long): CappedRead {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    try {
        while (true) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), max + 1 - total).toInt())
            if (read < 0) return CappedRead.Ok(out.toByteArray())
            total += read
            if (total > max) return CappedRead.Overflow
            out.write(buffer, 0, read)
        }
    } catch (_: IOException) {
        return CappedRead.Failed
    }
}
