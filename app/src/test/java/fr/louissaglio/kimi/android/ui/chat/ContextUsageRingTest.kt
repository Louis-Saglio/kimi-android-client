package fr.louissaglio.kimi.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextUsageRingTest {

    @Test
    fun `fill percent ceils so sub-half-percent usage still shows a sliver`() {
        assertEquals(1, contextUsagePercent(used = 1, max = 1048576))
        assertEquals(3, contextUsagePercent(used = 25050, max = 1048576)) // 2.39% → 3
        assertEquals(80, contextUsagePercent(used = 80, max = 100))
        assertEquals(100, contextUsagePercent(used = 100, max = 100))
    }

    @Test
    fun `fill percent clamps over-estimates to 100`() {
        assertEquals(100, contextUsagePercent(used = 1_100_000, max = 1048576))
    }

    @Test
    fun `fill percent is zero for empty usage or a missing maximum`() {
        assertEquals(0, contextUsagePercent(used = 0, max = 1048576))
        assertEquals(0, contextUsagePercent(used = 100, max = 0))
    }

    @Test
    fun `token counts format compactly with one truncated decimal`() {
        assertEquals("999", formatTokenCount(999))
        assertEquals("25.5k", formatTokenCount(25500))
        assertEquals("25k", formatTokenCount(25050))
        assertEquals("262.1k", formatTokenCount(262144))
        assertEquals("1M", formatTokenCount(1048576))
        assertEquals("2M", formatTokenCount(2_000_000))
    }
}
