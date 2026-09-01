package fr.louissaglio.kimi.android.data.chat

import com.github.difflib.DiffUtils

/**
 * The unified line-diff model of an Edit call (ticket 119): [of] diffs the call's
 * old/new strings line-level (java-diff-utils' Myers — no hand-rolled algorithm, no
 * file fetch or surrounding-context reconstruction) and flattens the patch into
 * display rows for the diff screen: removed/added lines plus the unchanged context,
 * with runs of at least [COLLAPSE_THRESHOLD] context lines folded into one
 * [Row.Collapsed] expander.
 *
 * [Model.addedCount]/[Model.removedCount] are the row chip's "+a −b" and the diff
 * screen's title stats: line counts of the patch's deltas BY TYPE (an INSERT's
 * target lines, a DELETE's source lines, a CHANGE's both) — never raw line splits —
 * so an unchanged trailing newline cannot inflate the numbers.
 */
object EditDiffs {

    /** Context runs shorter than this stay expanded. */
    const val COLLAPSE_THRESHOLD = 4

    /** One display row of the unified diff. [Collapsed] carries its hidden context
     * lines so the screen can reveal them in place. */
    sealed interface Row {
        data class Added(val text: String) : Row
        data class Removed(val text: String) : Row
        data class Context(val text: String) : Row
        data class Collapsed(val count: Int, val lines: List<String>) : Row
    }

    data class Model(
        val rows: List<Row>,
        val addedCount: Int,
        val removedCount: Int,
    ) {
        /** Identical old/new (or both empty): the screen shows "No changes". */
        val hasChanges: Boolean get() = addedCount > 0 || removedCount > 0
    }

    fun of(oldString: String, newString: String): Model {
        val oldLines = linesOf(oldString)
        val patch = DiffUtils.diff(oldLines, linesOf(newString))
        val rows = mutableListOf<Row>()
        val context = mutableListOf<String>()
        var addedCount = 0
        var removedCount = 0
        // Position in oldLines up to which the context was consumed: the patch's
        // deltas carry only the changes (the default diff overload), so the
        // unchanged runs are the gaps between them.
        var cursor = 0

        fun flushContext() {
            if (context.size >= COLLAPSE_THRESHOLD) {
                rows += Row.Collapsed(context.size, context.toList())
            } else {
                context.forEach { rows += Row.Context(it) }
            }
            context.clear()
        }

        for (delta in patch.deltas) {
            while (cursor < delta.source.position) context += oldLines[cursor++]
            flushContext()
            removedCount += delta.source.size()
            addedCount += delta.target.size()
            delta.source.lines.forEach { rows += Row.Removed(it) }
            delta.target.lines.forEach { rows += Row.Added(it) }
            cursor = delta.source.position + delta.source.size()
        }
        while (cursor < oldLines.size) context += oldLines[cursor++]
        flushContext()
        return Model(rows, addedCount, removedCount)
    }

    /** "".lines() is [""], which would diff as a CHANGE instead of a pure
     * addition — an empty string has NO lines. */
    private fun linesOf(text: String): List<String> = if (text.isEmpty()) emptyList() else text.lines()
}
