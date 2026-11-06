package fr.louissaglio.kimi.android.ui.chat

import android.net.Uri
import android.util.Base64
import fr.louissaglio.kimi.android.data.attachments.AttachmentStore
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.FileMeta
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.data.toUserMessage
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Upload bytes of one staged attachment (ticket 033). A plain holder, NOT a data
 * class, on purpose: [PendingAttachment] sits in UiState, and a raw ByteArray field
 * would drag referential array equality into the state's data-class equals (and
 * megabyte content compares into every StateFlow emission). Identity on this holder
 * is the cheap, correct semantics — staged bytes never mutate; chip updates go
 * through `copy(status = …)`, which keeps the same holder reference.
 */
class AttachmentBytes(val value: ByteArray)

/**
 * One attachment staged in the composer (ticket 033). [bytes] are read eagerly at
 * pick time (size-capped, see [AttachmentStore.MAX_PICK_BYTES]) so the chip can
 * render a thumbnail and the send path uploads them unchanged. [status] reflects
 * the upload driven by [ChatAttachments.uploadAll]: Ready → Uploading → (Failed
 * keeps the chip and blocks the send; the snackbar's Retry re-sends).
 */
data class PendingAttachment(
    val localId: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val bytes: AttachmentBytes,
    val status: Status = Status.Ready,
) {
    enum class Status { Ready, Uploading, Failed }

    val isImage: Boolean get() = mimeType.startsWith("image/")
}

/**
 * The attachments collaborator (ticket 072 — extracted from ChatViewModel): the
 * pick → upload → remove → open orchestration against [AttachmentStore] and
 * [ChatRepository]. A logic holder, NOT a second state source: ChatViewModel's
 * UiState stays the single state holder — intents arrive as calls, state updates
 * report back through [updateState] and one-shot effects through [sendEffect]
 * (the DeltaCoalescer callback pattern, ticket 065).
 *
 * [scope] is a provider, like the coalescer's: the owning ViewModel passes
 * `{ viewModelScope }`, so launched work dies with the ViewModel.
 */
internal class ChatAttachments(
    private val scope: () -> CoroutineScope,
    private val attachmentStore: AttachmentStore,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionId: String,
    private val updateState: ((ChatViewModel.UiState) -> ChatViewModel.UiState) -> Unit,
    private val sendEffect: suspend (ChatViewModel.Effect) -> Unit,
) {

    /** Stages picked files as composer chips (ticket 033): the store resolves each
     * content Uri (size-capped, see [AttachmentStore.MAX_PICK_BYTES]); files that fail
     * the read surface one [ChatViewModel.Effect.AttachmentPickFailed] each and are
     * not staged. */
    fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope().launch {
            for (uri in uris) {
                onRead(withContext(Dispatchers.IO) { attachmentStore.read(uri) })
            }
        }
    }

    /** Handles one store read: stages the chip or surfaces why nothing was staged.
     * Called by [onPicked] and directly by unit tests (no android.net.Uri on the JVM). */
    suspend fun onRead(result: AttachmentStore.ReadResult) {
        when (result) {
            is AttachmentStore.ReadResult.Ok -> {
                val picked = result.picked
                val attachment = PendingAttachment(
                    localId = UUID.randomUUID().toString(),
                    name = picked.name,
                    sizeBytes = picked.sizeBytes,
                    mimeType = picked.mimeType,
                    bytes = AttachmentBytes(picked.bytes),
                )
                updateState { it.copy(attachments = it.attachments + attachment) }
            }
            is AttachmentStore.ReadResult.TooLarge ->
                sendEffect(
                    ChatViewModel.Effect.AttachmentPickFailed(
                        "${result.name} is too large to attach " +
                            "(limit ${AttachmentStore.MAX_PICK_BYTES / (1024 * 1024)} MiB)",
                    ),
                )
            is AttachmentStore.ReadResult.Unreadable ->
                sendEffect(ChatViewModel.Effect.AttachmentPickFailed("Could not read ${result.name}"))
        }
    }

    /** Removes a staged chip before the send; the removed file is never uploaded. */
    fun onRemoved(localId: String) {
        updateState { state ->
            state.copy(attachments = state.attachments.filterNot { it.localId == localId })
        }
    }

    /** Bytes of a history image (ticket 034): decoded from the inline base64, or fetched
     * for a file-referenced source — a `session_media` source (ticket 104) goes through
     * `GET /api/v1/sessions/{id}/media/{file_id}`, a legacy file reference through
     * `GET /api/v1/files/{id}`. Pull-based display data for the thumbnail/full-screen
     * viewer, hence a call rather than state or an effect. */
    suspend fun imageBytes(image: ChatMessage.Part.Image): ByteArray? =
        resolveImageBytes(chatRepository, settingsRepository, sessionId, image)

    /** Downloads a history attachment's bytes, persists them via the store, and emits
     * [ChatViewModel.Effect.OpenFile] for the screen to hand to another app
     * (ticket 034). */
    fun onOpen(attachment: ChatMessage.Part.Attachment) {
        val fileId = attachment.fileId ?: return
        scope().launch {
            val download = chatRepository.downloadFile(settingsRepository.current(), fileId).getOrNull()
            if (download == null) {
                sendEffect(ChatViewModel.Effect.OpenAttachmentFailed("Could not download ${attachment.name}"))
                return@launch
            }
            val uri = withContext(Dispatchers.IO) { attachmentStore.writeToCache(attachment.name, download.bytes) }
            if (uri == null) {
                sendEffect(ChatViewModel.Effect.OpenAttachmentFailed("No app can open ${attachment.name}"))
            } else {
                sendEffect(ChatViewModel.Effect.OpenFile(uri, download.mediaType, attachment.name))
            }
        }
    }

    /** The screen found no viewer for an [ChatViewModel.Effect.OpenFile]
     * (ActivityNotFoundException). */
    fun onOpenFailed(name: String) {
        scope().launch { sendEffect(ChatViewModel.Effect.OpenAttachmentFailed("No app can open $name")) }
    }

    /**
     * Uploads the staged attachments sequentially for one send (`POST /api/v1/files`),
     * marking each chip Uploading → Ready as it lands. A failed upload marks its chip
     * Failed, reports [ChatViewModel.Effect.SendFailed] and aborts the send (returns
     * null): no prompt is posted, and the caller's isSending flag is taken back down
     * so the composer unblocks — the snackbar's Retry re-sends.
     */
    suspend fun uploadAll(settings: ServerSettings, staged: List<PendingAttachment>): List<FileMeta>? {
        val uploaded = mutableListOf<FileMeta>()
        for (attachment in staged) {
            mark(attachment.localId, PendingAttachment.Status.Uploading)
            val result = chatRepository.uploadFile(settings, attachment.name, attachment.mimeType, attachment.bytes.value)
            result
                .onSuccess { meta ->
                    uploaded += meta
                    mark(attachment.localId, PendingAttachment.Status.Ready)
                }
                .onFailure { error ->
                    mark(attachment.localId, PendingAttachment.Status.Failed)
                    updateState { it.copy(isSending = false) }
                    sendEffect(ChatViewModel.Effect.SendFailed(error.toUserMessage()))
                    return null
                }
        }
        return uploaded
    }

    private fun mark(localId: String, status: PendingAttachment.Status) {
        updateState { state ->
            state.copy(
                attachments = state.attachments.map {
                    if (it.localId == localId) it.copy(status = status) else it
                },
            )
        }
    }
}

/** The one image-bytes resolution behind every chat image (ticket 034's history
 * images, ticket 184's tool-result media — the chat's [ChatAttachments.imageBytes] and
 * the sub-agent page's): the inline base64 decoded, else a `session_media` source
 * (ticket 104) fetched through `GET /api/v1/sessions/{id}/media/{file_id}`, a plain
 * file reference through `GET /api/v1/files/{id}`. Null when nothing resolves. */
internal suspend fun resolveImageBytes(
    chatRepository: ChatRepository,
    settingsRepository: SettingsRepository,
    sessionId: String,
    image: ChatMessage.Part.Image,
): ByteArray? =
    image.base64Data?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
        ?: image.fileId?.let { fileId ->
            val settings = settingsRepository.current()
            if (image.isSessionMedia) {
                chatRepository.downloadSessionMedia(settings, sessionId, fileId).getOrNull()?.bytes
            } else {
                chatRepository.downloadFile(settings, fileId).getOrNull()?.bytes
            }
        }
