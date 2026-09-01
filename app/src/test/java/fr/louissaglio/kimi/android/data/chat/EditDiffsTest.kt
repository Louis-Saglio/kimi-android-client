package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The unified line-diff model behind the Edit diff screen and the row's stat chip
 * (ticket 119). */
class EditDiffsTest {

    @Test
    fun `a changed middle line yields context removed added context rows`() {
        val model = EditDiffs.of("a\nb\nc", "a\nX\nc")

        assertEquals(
            listOf(
                EditDiffs.Row.Context("a"),
                EditDiffs.Row.Removed("b"),
                EditDiffs.Row.Added("X"),
                EditDiffs.Row.Context("c"),
            ),
            model.rows,
        )
        assertEquals(1, model.addedCount)
        assertEquals(1, model.removedCount)
    }

    @Test
    fun `a three-line unchanged run stays expanded`() {
        val model = EditDiffs.of("c1\nc2\nc3\nold", "c1\nc2\nc3\nnew")

        assertEquals(
            listOf(
                EditDiffs.Row.Context("c1"),
                EditDiffs.Row.Context("c2"),
                EditDiffs.Row.Context("c3"),
                EditDiffs.Row.Removed("old"),
                EditDiffs.Row.Added("new"),
            ),
            model.rows,
        )
    }

    @Test
    fun `a four-line unchanged run collapses into one expander row`() {
        val model = EditDiffs.of("c1\nc2\nc3\nc4\nold", "c1\nc2\nc3\nc4\nnew")

        assertEquals(
            listOf(
                EditDiffs.Row.Collapsed(4, listOf("c1", "c2", "c3", "c4")),
                EditDiffs.Row.Removed("old"),
                EditDiffs.Row.Added("new"),
            ),
            model.rows,
        )
    }

    @Test
    fun `context between two changes collapses and the counts accumulate`() {
        val model = EditDiffs.of("h\na\nx1\nx2\nx3\nx4\nb\nt", "h\nA\nx1\nx2\nx3\nx4\nB\nt")

        assertEquals(
            listOf(
                EditDiffs.Row.Context("h"),
                EditDiffs.Row.Removed("a"),
                EditDiffs.Row.Added("A"),
                EditDiffs.Row.Collapsed(4, listOf("x1", "x2", "x3", "x4")),
                EditDiffs.Row.Removed("b"),
                EditDiffs.Row.Added("B"),
                EditDiffs.Row.Context("t"),
            ),
            model.rows,
        )
        assertEquals(2, model.addedCount)
        assertEquals(2, model.removedCount)
    }

    @Test
    fun `an empty old string is a pure addition`() {
        val model = EditDiffs.of("", "a\nb")

        assertEquals(listOf(EditDiffs.Row.Added("a"), EditDiffs.Row.Added("b")), model.rows)
        assertEquals(2, model.addedCount)
        assertEquals(0, model.removedCount)
    }

    @Test
    fun `an empty new string is a pure deletion`() {
        val model = EditDiffs.of("a\nb", "")

        assertEquals(listOf(EditDiffs.Row.Removed("a"), EditDiffs.Row.Removed("b")), model.rows)
        assertEquals(0, model.addedCount)
        assertEquals(2, model.removedCount)
    }

    @Test
    fun `an unchanged trailing newline does not inflate the stats`() {
        // Both strings split to two lines (the trailing ""); the delta counts one
        // changed line each way — raw line splits would claim more.
        val model = EditDiffs.of("a\n", "b\n")

        assertEquals(1, model.addedCount)
        assertEquals(1, model.removedCount)
        assertEquals(
            listOf(
                EditDiffs.Row.Removed("a"),
                EditDiffs.Row.Added("b"),
                EditDiffs.Row.Context(""),
            ),
            model.rows,
        )
    }

    @Test
    fun `identical strings produce no changes`() {
        val model = EditDiffs.of("same\nlines", "same\nlines")

        assertFalse(model.hasChanges)
        assertEquals(0, model.addedCount)
        assertEquals(0, model.removedCount)
        assertEquals(listOf(EditDiffs.Row.Context("same"), EditDiffs.Row.Context("lines")), model.rows)
    }

    @Test
    fun `replace_all has no effect on the diff`() {
        // replace_all is a call-site multiplicity flag, not diff input: the model is
        // derived from the old/new strings alone.
        val replaceOnce = ToolPayload.Edit("/tmp/a.txt", "one", "two", replaceAll = false)
        val replaceAll = ToolPayload.Edit("/tmp/a.txt", "one", "two", replaceAll = true)

        assertEquals(
            EditDiffs.of(replaceOnce.oldString, replaceOnce.newString),
            EditDiffs.of(replaceAll.oldString, replaceAll.newString),
        )
    }
}
