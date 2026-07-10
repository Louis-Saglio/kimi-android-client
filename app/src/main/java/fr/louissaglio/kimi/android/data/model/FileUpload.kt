package fr.louissaglio.kimi.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FileUploadResponse(
    val id: String,
    val name: String,
    @SerialName("media_type") val mediaType: String,
    val size: Long
)
