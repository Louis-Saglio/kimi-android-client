package fr.louissaglio.kimi.android.data

import fr.louissaglio.kimi.android.data.model.FileBlock
import fr.louissaglio.kimi.android.data.model.FileUploadResponse

class UploadRepository(private val api: KimiApi) {

    suspend fun uploadFile(name: String, bytes: ByteArray, mediaType: String): FileUploadResponse {
        return api.uploadFile(name, bytes, mediaType)
    }

    fun toContentBlock(upload: FileUploadResponse): FileBlock {
        return FileBlock(
            fileId = upload.id,
            name = upload.name,
            mediaType = upload.mediaType,
            size = upload.size
        )
    }
}
