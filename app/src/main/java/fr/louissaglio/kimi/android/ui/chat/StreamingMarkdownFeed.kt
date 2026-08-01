package fr.louissaglio.kimi.android.ui.chat

/**
 * Append-only feeding logic for `StreamingMarkdownState` (ticket 082). The
 * timeline publishes FULL text snapshots per message (the coalesced stream),
 * while the renderer's streaming state only accepts `append(chunk)`: this
 * holder tracks what the state has already consumed ([fed]) and classifies
 * each new snapshot as an append, a no-op, or a reset (different message in
 * this row slot, history replace). Pure logic holder so the decision is
 * unit-testable off the composition.
 */
internal class StreamingMarkdownFeed {

    /** The text the current streaming-state instance has consumed so far. */
    var fed: String = ""
        private set

    sealed interface Action {
        /** [chunk] is the not-yet-consumed suffix of the new snapshot. */
        data class Append(val chunk: String) : Action

        /** The new snapshot is not an extension of [fed] — the state must be discarded and re-seeded. */
        data object Reset : Action

        data object None : Action
    }

    fun classify(text: String): Action = when {
        text == fed -> Action.None
        text.startsWith(fed) -> Action.Append(text.substring(fed.length))
        else -> Action.Reset
    }

    /** Marks [text] as consumed after a successful append/seed. */
    fun markFed(text: String) {
        fed = text
    }
}
