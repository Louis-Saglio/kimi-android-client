package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentNoticeTest {

    @Test
    fun `the documented notice wording parses into name mime size and fileId`() {
        val parsed = AttachmentNotice.parse(
            "Attached file \"notes.txt\" (text/plain, 340 bytes): " +
                "/home/ubuntu/.kimi-code/sessions/wd_1/session_2/attachments/" +
                "f_01KYA4RCBE4NPA1RX5TT7DB5J9-notes.txt — open it with the Read tool",
        )

        assertEquals(
            ChatMessage.Part.Attachment("notes.txt", "text/plain", 340, "f_01KYA4RCBE4NPA1RX5TT7DB5J9"),
            parsed,
        )
    }

    @Test
    fun `a v2 uuid file id is recovered whole, not truncated at its first hyphen`() {
        val parsed = AttachmentNotice.parse(
            "Attached file \"photo.png\" (image/png, 1024 bytes): " +
                "/data/attachments/f_123e4567-e89b-42d3-a456-426614174000-photo.png — open it with the Read tool",
        )

        assertEquals("f_123e4567-e89b-42d3-a456-426614174000", parsed?.fileId)
        assertEquals("photo.png", parsed?.name)
    }

    @Test
    fun `a basename without a file id still parses, with a null fileId`() {
        val parsed = AttachmentNotice.parse(
            "Attached file \"report.pdf\" (application/pdf, 2048 bytes): " +
                "/tmp/attachments/report.pdf — open it with the Read tool",
        )

        assertEquals(ChatMessage.Part.Attachment("report.pdf", "application/pdf", 2048, null), parsed)
    }

    @Test
    fun `non-matching text passes through untouched`() {
        assertNull(AttachmentNotice.parse("just a normal message"))
        assertNull(
            AttachmentNotice.parse(
                "Attached file \"x.txt\" (text/plain, 12 bytes): /tmp/x.txt — different trailing wording",
            ),
        )
    }
}
