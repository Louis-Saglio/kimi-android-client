package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ToolPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The navigation → screen payload hand-off (ticket 119). */
class ToolContentStoreTest {

    private val payload = ToolPayload.Edit("/tmp/spike/a.txt", "one", "two", replaceAll = false)

    @Test
    fun `a staged payload is consumed back`() {
        val store = ToolContentStore()

        store.put("tool_1", payload)

        assertEquals(payload, store.consume("tool_1"))
    }

    @Test
    fun `consume removes the entry`() {
        val store = ToolContentStore()
        store.put("tool_1", payload)

        store.consume("tool_1")

        assertNull(store.consume("tool_1"))
    }

    @Test
    fun `a missing entry consumes as null`() {
        assertNull(ToolContentStore().consume("tool_1"))
    }

    @Test
    fun `a re-put overwrites the staged payload`() {
        val store = ToolContentStore()
        store.put("tool_1", payload)
        val newer = payload.copy(newString = "three")
        store.put("tool_1", newer)

        assertEquals(newer, store.consume("tool_1"))
    }
}
