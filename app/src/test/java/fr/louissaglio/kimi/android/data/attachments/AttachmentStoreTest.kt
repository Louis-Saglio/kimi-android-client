package fr.louissaglio.kimi.android.data.attachments

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Boundary of the pick cap (ticket 059): the server removed its own 50 MiB upload
 * limit in 0.31.0, so the remaining guard is the app's own 256 MiB pick cap. The
 * platform read path (ContentResolver) needs a Context the JVM does not have; what
 * is pinned here is the streaming guard every read funnels through, at the cap.
 */
class AttachmentStoreTest {

    @Test
    fun `a pick over the old 50 MiB server limit streams in full under the new cap`() {
        val bytes = ByteArray(51 * 1024 * 1024) { (it % 251).toByte() }
        val read = ByteArrayInputStream(bytes).readCapped(AttachmentStore.MAX_PICK_BYTES)
        assertArrayEquals(bytes, read)
    }

    @Test
    fun `the cap is the app's own 256 MiB pick limit`() {
        assertEquals(256L * 1024 * 1024, AttachmentStore.MAX_PICK_BYTES)
    }

    @Test
    fun `a stream over the cap is refused without its bytes`() {
        // One byte past the limit: the guard gives up instead of staging the pick.
        val over = ByteArrayInputStream(ByteArray(8))
        assertNull(over.readCapped(7))
        // Exactly at the limit still reads.
        val at = ByteArrayInputStream(ByteArray(7) { 1 })
        assertEquals(7, at.readCapped(7)?.size)
    }
}
