package fr.louissaglio.kimi.android.data.sessions

import android.util.Log
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/**
 * Device-local "turn finished, not yet viewed" markers (ticket 145), one JSON file
 * per server — the Android port of the web client's `kimi-web.unread` localStorage
 * map. The server has no unread concept: a mark comes from an observed work_changed
 * idle frame (reason "completed") and clears when the session is opened. Same
 * directory and per-server-key rationale as [SessionListCache]; a corrupt file is
 * deleted and treated as empty. All methods are blocking; the files are a handful
 * of ids.
 */
class UnreadSessionsStore(
    private val dir: File,
    private val json: Json,
) {

    private val states = mutableMapOf<String, MutableStateFlow<Set<String>>>()

    /** [serverKey]'s unread ids; the first access per key loads them from disk. Blocking on that first call. */
    @Synchronized
    fun unreadIds(serverKey: String): StateFlow<Set<String>> = stateFor(serverKey).asStateFlow()

    /** Adds [sessionId] to [serverKey]'s set, written through. Blocking. */
    @Synchronized
    fun mark(serverKey: String, sessionId: String) {
        stateFor(serverKey).update { it + sessionId }
        save(serverKey)
    }

    /** Removes [sessionId] from [serverKey]'s set, written through. Blocking. */
    @Synchronized
    fun clear(serverKey: String, sessionId: String) {
        stateFor(serverKey).update { it - sessionId }
        save(serverKey)
    }

    private fun stateFor(serverKey: String): MutableStateFlow<Set<String>> =
        states.getOrPut(serverKey) { MutableStateFlow(load(serverKey)) }

    private fun load(serverKey: String): Set<String> {
        val file = fileFor(serverKey)
        if (!file.isFile) return emptySet()
        val ids = runCatching { json.decodeFromString<Set<String>>(file.readText()) }.getOrNull()
        if (ids == null) {
            Log.w(TAG, "Deleting undecodable unread-sessions file ${file.name}")
            file.delete()
            return emptySet()
        }
        return ids
    }

    /** A failed write is logged, never thrown — the [SessionListCache.save] contract. */
    private fun save(serverKey: String) {
        runCatching {
            dir.mkdirs()
            fileFor(serverKey).writeText(json.encodeToString(states.getValue(serverKey).value))
        }.onFailure { Log.w(TAG, "Unread-sessions save failed", it) }
    }

    private fun fileFor(serverKey: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(serverKey.toByteArray(Charsets.UTF_8))
        return File(dir, hash.joinToString("") { "%02x".format(it) } + ".json")
    }

    private companion object {
        const val TAG = "UnreadSessionsStore"
    }
}
