package fr.louissaglio.kimi.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ticket 130: the compaction summary hand-off — one screen per staged entry. */
class CompactionSummaryStoreTest {

    private val store = CompactionSummaryStore()

    @Test
    fun `consume returns the staged entry once then a miss`() {
        val entry = CompactionSummaryStore.Entry("## Summary\ntext", tokensBefore = 26_500, tokensAfter = 386)
        store.put("compaction_1", entry)

        assertEquals(entry, store.consume("compaction_1"))
        assertNull(store.consume("compaction_1"))
    }

    @Test
    fun `a missing id is a miss`() {
        assertNull(store.consume("compaction_unknown"))
    }
}
