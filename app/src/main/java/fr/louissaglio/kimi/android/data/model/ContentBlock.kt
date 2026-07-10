package fr.louissaglio.kimi.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
sealed class ContentBlock

@Serializable
@SerialName("text")
data class TextBlock(val text: String) : ContentBlock()

@Serializable
@SerialName("thinking")
data class ThinkingBlock(val thinking: String) : ContentBlock()

@Serializable
@SerialName("tool_use")
data class ToolUseBlock(
    @SerialName("tool_call_id") val toolCallId: String,
    @SerialName("tool_name") val toolName: String,
    val input: JsonElement? = null
) : ContentBlock()

@Serializable
@SerialName("tool_result")
data class ToolResultBlock(
    @SerialName("tool_call_id") val toolCallId: String,
    val output: String,
    @SerialName("is_error") val isError: Boolean = false
) : ContentBlock()

@Serializable
@SerialName("file")
data class FileBlock(
    @SerialName("file_id") val fileId: String,
    val name: String,
    @SerialName("media_type") val mediaType: String? = null,
    val size: Long = 0
) : ContentBlock()

@Serializable
@SerialName("image")
data class ImageBlock(val source: MediaSource) : ContentBlock()

@Serializable
@SerialName("video")
data class VideoBlock(val source: MediaSource) : ContentBlock()

@Serializable
@SerialName("unknown")
data class UnknownBlock(val raw: JsonElement? = null) : ContentBlock()

@Serializable
data class MediaSource(
    val kind: String,
    @SerialName("media_type") val mediaType: String? = null,
    val data: String? = null,
    @SerialName("file_id") val fileId: String? = null,
    val url: String? = null
)
