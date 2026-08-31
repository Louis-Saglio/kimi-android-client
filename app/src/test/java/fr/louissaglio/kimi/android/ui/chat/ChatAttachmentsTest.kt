package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.attachments.AttachmentStore
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attachments coverage (the [ChatAttachments] collaborator, ticket 072): pick → stage
 * → upload → send and the failure paths, driven end-to-end through the ViewModel on
 * the shared [ChatViewModelTestBase] harness.
 */
class ChatAttachmentsTest : ChatViewModelTestBase() {

    @Test
    fun `attachment send uploads the file then posts the prompt with its content item`() = runBlocking {
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        stagePicked("a.txt", 5, "text/plain", "hello".toByteArray())
        viewModel.onInputChange("see attached")
        viewModel.onSend()

        val upload = awaitRequest("/api/v1/files")
        assertEquals("POST", upload.method)
        val prompt = awaitRequest("/prompts")
        assertEquals("POST", prompt.method)
        assertEquals("/api/v1/sessions/session_1/prompts", prompt.target)
        assertEquals(
            """{"content":[""" +
                """{"type":"file","file_id":"f_1","name":"a.txt","media_type":"text/plain","size":5},""" +
                """{"type":"text","text":"see attached"}],"model":"kimi-code/k3"}""",
            prompt.body!!.utf8(),
        )
        // Chips and text clear on success, like a text-only send.
        awaitState(viewModel.uiState) { it.attachments.isEmpty() && it.inputText.isEmpty() && !it.isSending }
    }

    @Test
    fun `send is a no-op while an attachment upload is in flight`() = runBlocking {
        val gate = CountDownLatch(1)
        filesResponseGate = gate
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        stagePicked("a.txt", 5, "text/plain", "hello".toByteArray())
        viewModel.onSend()
        awaitState(viewModel.uiState) {
            it.isSending && it.attachments.single().status == PendingAttachment.Status.Uploading
        }

        // A second tap while the upload is parked server-side must not send again.
        viewModel.onSend()
        gate.countDown()

        awaitRequest("/api/v1/files")
        // Attachments alone are sendable: the prompt goes out without a text item.
        val prompt = awaitRequest("/prompts")
        assertEquals(
            """{"content":[{"type":"file","file_id":"f_1","name":"a.txt","media_type":"text/plain","size":5}],""" +
                """"model":"kimi-code/k3"}""",
            prompt.body!!.utf8(),
        )
        awaitState(viewModel.uiState) { it.attachments.isEmpty() && !it.isSending }
        // Exactly one upload and one prompt: the second tap sent nothing.
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `failed upload blocks the send and posts no prompt`() = runBlocking {
        uploadsSucceed = false
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            stagePicked("a.txt", 5, "text/plain", "hello".toByteArray())
            viewModel.onSend()
            awaitRequest("/api/v1/files")
            awaitCondition { effects.isNotEmpty() }

            assertEquals(
                ChatViewModel.Effect.SendFailed("unsupported media type"),
                effects.single(),
            )
            // The failed chip stays staged and marked; no prompt was posted.
            val attachment = viewModel.uiState.value.attachments.single()
            assertEquals(PendingAttachment.Status.Failed, attachment.status)
            assertTrue(!viewModel.uiState.value.isSending)
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `removing a chip excludes the file from the send`() = runBlocking {
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        stagePicked("a.txt", 5, "text/plain", "hello".toByteArray())
        val localId = viewModel.uiState.value.attachments.single().localId
        viewModel.onAttachmentRemoved(localId)
        assertEquals(emptyList<PendingAttachment>(), viewModel.uiState.value.attachments)

        viewModel.onInputChange("just text")
        viewModel.onSend()

        val prompt = awaitRequest("/prompts")
        assertEquals(
            """{"content":[{"type":"text","text":"just text"}],"model":"kimi-code/k3"}""",
            prompt.body!!.utf8(),
        )
        // No upload happened for the removed chip.
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `over-cap and unreadable picks emit AttachmentPickFailed and stage nothing`() = runBlocking {
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onAttachmentRead(AttachmentStore.ReadResult.TooLarge("big.bin"))
            viewModel.onAttachmentRead(AttachmentStore.ReadResult.Unreadable("broken.bin"))
            awaitCondition { effects.size >= 2 }

            assertEquals(
                listOf(
                    ChatViewModel.Effect.AttachmentPickFailed("big.bin is too large to attach (limit 256 MiB)"),
                    ChatViewModel.Effect.AttachmentPickFailed("Could not read broken.bin"),
                ),
                effects,
            )
            assertEquals(emptyList<PendingAttachment>(), viewModel.uiState.value.attachments)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `imageBytes of a session media source fetches through the session media endpoint`() = runBlocking {
        // Ticket 104: a session_media image id is session-scoped; the fetch must hit
        // GET /api/v1/sessions/{id}/media/{file_id}, not /api/v1/files/{id}.
        val bytes = viewModel.imageBytes(
            ChatMessage.Part.Image(base64Data = null, fileId = "f_img", isSessionMedia = true),
        )

        val request = awaitRequest("/media/")
        assertEquals("GET", request.method)
        assertEquals("/api/v1/sessions/session_1/media/f_img", request.target)
        assertEquals("session-media-bytes", bytes?.let(::String))
    }

    /** Stages a composer chip the way the pick flow does, without an android.net.Uri
     * (unavailable on JVM unit tests). */
    private suspend fun stagePicked(name: String, sizeBytes: Long, mimeType: String, bytes: ByteArray) =
        viewModel.onAttachmentRead(AttachmentStore.ReadResult.Ok(AttachmentStore.Picked(name, sizeBytes, mimeType, bytes)))
}
