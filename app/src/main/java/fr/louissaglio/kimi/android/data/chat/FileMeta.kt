package fr.louissaglio.kimi.android.data.chat

/**
 * A file uploaded to `POST /api/v1/files` (docs/server-api.md, Files), referenced by
 * id in a prompt's `content` items. [mediaType] is the server-sniffed MIME type: it
 * decides the prompt part kind — an image MIME type becomes an `image` part, anything
 * else a `file` part (ticket 033).
 */
data class FileMeta(
    val id: String,
    val name: String,
    val mediaType: String,
    val sizeBytes: Long,
) {
    val isImage: Boolean get() = mediaType.startsWith("image/")
}

/** The bytes of `GET /api/v1/files/{id}` (ticket 034): attachment chips and image
 * viewers fetch their content on demand. [mediaType] is the response Content-Type. */
data class FileDownload(
    val bytes: ByteArray,
    val mediaType: String,
) {
    override fun equals(other: Any?): Boolean =
        other is FileDownload && bytes.contentEquals(other.bytes) && mediaType == other.mediaType

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mediaType.hashCode()
}
