package fr.louissaglio.kimi.android.data.attachments

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Boundary of the pick cap (ticket 059): the server removed its own 50 MiB upload
 * limit in 0.31.0, so the remaining guard is the app's own 256 MiB pick cap. The
 * platform read path (ContentResolver) needs a Context the JVM does not have; what
 * is pinned here is the streaming guard every read funnels through, at the cap,
 * and (ticket 063) the overflow-vs-unreadable split above it.
 */
class AttachmentStoreTest {

    @Test
    fun `a pick over the old 50 MiB server limit streams in full under the new cap`() {
        val bytes = ByteArray(51 * 1024 * 1024) { (it % 251).toByte() }
        val read = ByteArrayInputStream(bytes).readCapped(AttachmentStore.MAX_PICK_BYTES)
        assertArrayEquals(bytes, (read as CappedRead.Ok).bytes)
    }

    @Test
    fun `the cap is the app's own 256 MiB pick limit`() {
        assertEquals(256L * 1024 * 1024, AttachmentStore.MAX_PICK_BYTES)
    }

    @Test
    fun `a stream over the cap is refused without its bytes`() {
        // One byte past the limit: the guard gives up instead of staging the pick.
        val over = ByteArrayInputStream(ByteArray(8))
        assertEquals(CappedRead.Overflow, over.readCapped(7))
        // Exactly at the limit still reads.
        val at = ByteArrayInputStream(ByteArray(7) { 1 })
        assertEquals(7, (at.readCapped(7) as CappedRead.Ok).bytes.size)
    }

    @Test
    fun `an oversize pick with no advertised size reports TooLarge`() {
        // size = -1: the provider omitted OpenableColumns.SIZE, so the
        // advertised-size guard passes and only the streamed read sees the overflow.
        val result = readPicked("video.mp4", advertisedSize = -1, mimeType = "video/mp4", max = 7) {
            ByteArrayInputStream(ByteArray(8))
        }
        assertEquals(AttachmentStore.ReadResult.TooLarge("video.mp4"), result)
    }

    @Test
    fun `an oversize pick with a lying advertised size reports TooLarge`() {
        // The provider advertises 4 bytes; the stream carries more than the cap.
        val result = readPicked("video.mp4", advertisedSize = 4, mimeType = "video/mp4", max = 7) {
            ByteArrayInputStream(ByteArray(8))
        }
        assertEquals(AttachmentStore.ReadResult.TooLarge("video.mp4"), result)
    }

    @Test
    fun `a pick whose stream breaks mid-read still reports Unreadable`() {
        val result = readPicked("broken.bin", advertisedSize = -1, mimeType = null, max = 7) {
            object : InputStream() {
                override fun read(): Int = throw IOException("boom")

                override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("boom")
            }
        }
        assertEquals(AttachmentStore.ReadResult.Unreadable("broken.bin"), result)
    }

    @Test
    fun `a pick whose stream cannot be opened still reports Unreadable`() {
        val result = readPicked("gone.bin", advertisedSize = -1, mimeType = null, max = 7) { null }
        assertEquals(AttachmentStore.ReadResult.Unreadable("gone.bin"), result)
    }
}
