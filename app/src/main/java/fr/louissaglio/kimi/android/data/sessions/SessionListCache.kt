package fr.louissaglio.kimi.android.data.sessions

import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Last-known session list on disk (ticket 111): one JSON file per server, so a
 * cold start renders the previous list immediately instead of the
 * `sessions == null` spinner. The file name is the SHA-256 hex of the server
 * base URL — switching servers in settings never shows the previous server's
 * sessions. The directory lives in filesDir, not cacheDir: the cached list is
 * user-visible app state the system must not reclaim. A corrupt or undecodable
 * file is deleted and treated as absent — no schema versioning, the next
 * refresh rewrites it.
 */
class SessionListCache(
    private val dir: File,
    private val json: Json,
) {

    /** The decoded entry for [serverKey]; null when absent or unreadable. Blocking. */
    fun load(serverKey: String): Entry? {
        val file = fileFor(serverKey)
        if (!file.isFile) return null
        val entry = runCatching { json.decodeFromString<CachedList>(file.readText()).toEntry() }.getOrNull()
        if (entry == null) {
            Log.w(TAG, "Deleting undecodable session list cache ${file.name}")
            file.delete()
        }
        return entry
    }

    /** Overwrites [serverKey]'s file. Blocking; a failed write is logged, never
     * thrown — a cache must not break the write-through collection it serves. */
    fun save(serverKey: String, sessions: List<Session>, hasMore: Boolean) {
        runCatching {
            dir.mkdirs()
            fileFor(serverKey).writeText(json.encodeToString(sessions.toCachedList(hasMore)))
        }.onFailure { Log.w(TAG, "Session list cache save failed", it) }
    }

    private fun fileFor(serverKey: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(serverKey.toByteArray(Charsets.UTF_8))
        return File(dir, hash.joinToString("") { "%02x".format(it) } + ".json")
    }

    /** One server's cached list; [sessions] mirror the domain [Session] field for field. */
    data class Entry(
        val sessions: List<Session>,
        val hasMore: Boolean,
    )

    private companion object {
        const val TAG = "SessionListCache"
    }
}

// On-disk shapes. Fields mirror the domain Session; defaults keep older files
// decodable when a field is added (unknown keys are ignored the other way).

@Serializable
private data class CachedList(
    val sessions: List<CachedSession> = emptyList(),
    val hasMore: Boolean = false,
)

@Serializable
private data class CachedSession(
    val id: String,
    val title: String? = null,
    val cwd: String? = null,
    /** Epoch milliseconds — the domain [Instant] is rebuilt from it. */
    val updatedAt: Long = 0,
    val isRunning: Boolean = false,
    val isAwaitingInteraction: Boolean = false,
    val isLastTurnInterrupted: Boolean = false,
)

private fun CachedList.toEntry() = SessionListCache.Entry(
    sessions = sessions.map { it.toDomain() },
    hasMore = hasMore,
)

private fun CachedSession.toDomain() = Session(
    id = id,
    title = title,
    cwd = cwd,
    updatedAt = Instant.ofEpochMilli(updatedAt),
    isRunning = isRunning,
    isAwaitingInteraction = isAwaitingInteraction,
    isLastTurnInterrupted = isLastTurnInterrupted,
)

private fun List<Session>.toCachedList(hasMore: Boolean) = CachedList(
    sessions = map {
        CachedSession(
            id = it.id,
            title = it.title,
            cwd = it.cwd,
            updatedAt = it.updatedAt.toEpochMilli(),
            isRunning = it.isRunning,
            isAwaitingInteraction = it.isAwaitingInteraction,
            isLastTurnInterrupted = it.isLastTurnInterrupted,
        )
    },
    hasMore = hasMore,
)
