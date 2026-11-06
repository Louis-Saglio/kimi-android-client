package fr.louissaglio.kimi.android.ui.chat

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-lifetime, byte-budgeted LRU of fetched chat image bytes (ticket 185). A
 * thumbnail's `produceState` restarts every time its row re-enters composition — a
 * scroll back, a tool row's expand, the full-screen viewer — and tool results
 * (ticket 184) can carry several screenshots each, so without it every re-entry is a
 * fresh download on mobile data. A file id never names different bytes, so entries
 * need no invalidation; only [resolveImageBytes]' successful fetches are stored (a
 * failure retries on the next entry), and inline base64 images are never cached —
 * there is no network to save. An entry larger than the whole budget is not kept.
 * Synchronized because callers on any dispatcher may share the singleton.
 */
@Singleton
class ImageBytesCache internal constructor(private val maxBytes: Long) {

    @Inject constructor() : this(DEFAULT_MAX_BYTES)

    /** [sessionId] scopes a `session_media` id (ids are session-scoped server-side);
     * null for a plain `/files` id. [serverUrl] keeps a server switch from serving
     * another server's bytes. */
    data class Key(val serverUrl: String, val sessionId: String?, val fileId: String)

    private val entries = LinkedHashMap<Key, ByteArray>(16, 0.75f, true)
    private var totalBytes = 0L

    @Synchronized
    fun get(key: Key): ByteArray? = entries[key]

    @Synchronized
    fun put(key: Key, bytes: ByteArray) {
        if (bytes.size > maxBytes) return
        entries.put(key, bytes)?.let { totalBytes -= it.size }
        totalBytes += bytes.size
        val eldest = entries.entries.iterator()
        while (totalBytes > maxBytes && eldest.hasNext()) {
            totalBytes -= eldest.next().value.size
            eldest.remove()
        }
    }

    internal companion object {
        /** Roughly a dozen phone screenshots — enough for a screenshot-heavy turn. */
        const val DEFAULT_MAX_BYTES = 16L * 1024 * 1024
    }
}
