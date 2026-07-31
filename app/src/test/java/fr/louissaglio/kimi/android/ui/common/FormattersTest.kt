package fr.louissaglio.kimi.android.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** The one k/M scaler behind every compact count in the app (ticket 071). */
class FormattersTest {

    @Test
    fun `token counts format compactly with one truncated decimal`() {
        assertEquals("999", formatTokenCount(999))
        assertEquals("25.5k", formatTokenCount(25500))
        assertEquals("25k", formatTokenCount(25050))
        assertEquals("262.1k", formatTokenCount(262144))
        assertEquals("1M", formatTokenCount(1048576))
        assertEquals("2M", formatTokenCount(2_000_000))
    }

    @Test
    fun `file sizes use decimal units with the same truncated decimal`() {
        assertEquals("512 B", formatFileSize(512))
        assertEquals("340 kB", formatFileSize(340_000))
        assertEquals("340.5 kB", formatFileSize(340_500))
        assertEquals("1.2 MB", formatFileSize(1_200_000))
        assertEquals("1 MB", formatFileSize(1_000_000))
    }
}
