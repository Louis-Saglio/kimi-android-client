package fr.louissaglio.kimi.android.data.chat

/**
 * A media reference carried by a tool result (ticket 184 — server 2.5.1,
 * docs/server-api.md, Messages → History): the tool's `output` is then an ARRAY of
 * content parts, and every `{type:"image"|"video", source:{kind:"session_media",
 * file_id}}` part becomes one of these. The bytes are session media, fetched through
 * `GET /api/v1/sessions/{id}/media/{file_id}` (`ChatApi.downloadSessionMedia`).
 * [name] is the part's optional original file name (absent on `ReadMediaFile`'s
 * captured results).
 */
data class ToolMedia(
    val fileId: String,
    val kind: Kind,
    val name: String? = null,
) {
    enum class Kind { Image, Video }

    /** The image part the chat's existing thumbnail/viewer path renders (ticket 104's
     * session-media fetch); null for a video — it is never decoded as an image. */
    fun toImagePart(): ChatMessage.Part.Image? =
        if (kind == Kind.Image) {
            ChatMessage.Part.Image(base64Data = null, fileId = fileId, isSessionMedia = true, name = name)
        } else {
            null
        }
}

/** A tool result's `output` split into the row's [text] and its [media] references
 * (`ChatMessageMapper.toolOutput`). A plain-string output is all [text]. */
data class ToolOutput(
    val text: String,
    val media: List<ToolMedia> = emptyList(),
)
