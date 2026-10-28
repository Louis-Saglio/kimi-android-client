package fr.louissaglio.kimi.android.ui.chat

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-lifetime hand-off of a done compaction divider's summary to its screen
 * (ticket 130, the [ToolContentStore] pattern): the markdown is far too large for nav
 * arguments, so the divider's tap [put]s it under the divider id and the screen
 * [consume]s it on arrival. A miss (process death with the screen on top — the store
 * dies with the process) is the screen's missing-content state; back and re-tapping
 * the divider re-stages the entry. Entries are consumed exactly once, right after
 * their put, so the map never grows; Main-confined like the other ui-layer stores.
 */
@Singleton
class CompactionSummaryStore @Inject constructor() {

    /** What the screen gets: the markdown summary and, on a live divider only, the
     * token delta (history-sourced dividers never carry the numbers). */
    data class Entry(val summary: String, val tokensBefore: Long?, val tokensAfter: Long?)

    private val entries = mutableMapOf<String, Entry>()

    fun put(dividerId: String, entry: Entry) {
        entries[dividerId] = entry
    }

    /** Reads AND removes the entry — a summary serves one screen instance. */
    fun consume(dividerId: String): Entry? = entries.remove(dividerId)
}
