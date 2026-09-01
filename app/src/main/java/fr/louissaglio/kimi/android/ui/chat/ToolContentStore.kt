package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ToolPayload
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-lifetime hand-off of a tool call's typed payload to its detail screen
 * (ticket 119 — the Edit diff screen first; the 120–125 viewers reuse it): the
 * content is far too large for nav arguments, so the navigation callback [put]s it
 * under the tool call id and the screen's ViewModel [consume]s it on init. A miss
 * (process death with the screen on top — the store dies with the process) is the
 * screen's missing-content state: the chat cold-restarts from its snapshot anyway,
 * and re-tapping the row re-stages the payload.
 *
 * Entries are consumed exactly once, right after their put, so the map never grows;
 * Main-confined like the other ui-layer stores (both call sites are click handlers
 * and ViewModel init).
 */
@Singleton
class ToolContentStore @Inject constructor() {

    private val contents = mutableMapOf<String, ToolPayload>()

    fun put(toolCallId: String, payload: ToolPayload) {
        contents[toolCallId] = payload
    }

    /** Reads AND removes the entry — a payload serves one screen instance. */
    fun consume(toolCallId: String): ToolPayload? = contents.remove(toolCallId)
}
