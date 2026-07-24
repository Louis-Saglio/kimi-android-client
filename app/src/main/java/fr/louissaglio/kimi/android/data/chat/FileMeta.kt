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
