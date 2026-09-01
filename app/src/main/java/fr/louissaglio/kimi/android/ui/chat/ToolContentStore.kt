package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ToolPayload
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-lifetime hand-off of a tool call's typed payload to its detail screen
 * (ticket 119 — the Edit diff screen first; the 120–125 viewers reuse it): the
 * content is far too large for nav arguments, so the navigation callback [put]s it
 * under the tool call id and the screen [consume]s it on arrival. A miss
 * (process death with the screen on top — the store dies with the process) is the
 * screen's missing-content state: the chat cold-restarts from its snapshot anyway,
 * and re-tapping the row re-stages the payload.
 *
 * [Entry] also carries the call's output snapshot at tap time (ticket 120): the
 * Edit screen diffs the payload alone, but the Read viewer's content IS the output.
 * Entries are consumed exactly once, right after their put, so the map never grows;
 * Main-confined like the other ui-layer stores (the call sites are click handlers
 * and screen/ViewModel init).
 */
@Singleton
class ToolContentStore @Inject constructor() {

    /** What a detail screen gets: the call's typed payload and its output text at
     * tap time (snapshot semantics — the screens never live-update). */
    data class Entry(val payload: ToolPayload, val output: String = "")

    private val contents = mutableMapOf<String, Entry>()

    fun put(toolCallId: String, payload: ToolPayload, output: String = "") {
        contents[toolCallId] = Entry(payload, output)
    }

    /** Reads AND removes the entry — a payload serves one screen instance. */
    fun consume(toolCallId: String): Entry? = contents.remove(toolCallId)
}
