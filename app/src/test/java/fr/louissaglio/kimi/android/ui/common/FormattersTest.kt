package fr.louissaglio.kimi.android.ui.common

import java.time.Duration
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

    @Test
    fun `elapsed durations render mm ss and switch to h mm ss past an hour`() {
        assertEquals("00:00", formatElapsed(Duration.ofSeconds(0)))
        assertEquals("00:07", formatElapsed(Duration.ofSeconds(7)))
        assertEquals("03:12", formatElapsed(Duration.ofSeconds(192)))
        assertEquals("59:59", formatElapsed(Duration.ofSeconds(3_599)))
        assertEquals("1:00:00", formatElapsed(Duration.ofSeconds(3_600)))
        assertEquals("2:04:05", formatElapsed(Duration.ofSeconds(7_445)))
        // A clock skew (ended before started) clamps to zero, never negative.
        assertEquals("00:00", formatElapsed(Duration.ofSeconds(-5)))
    }
}
