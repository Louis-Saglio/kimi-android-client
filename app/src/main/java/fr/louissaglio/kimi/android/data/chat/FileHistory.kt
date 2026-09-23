package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.network.ApiClient
import fr.louissaglio.kimi.android.data.network.Envelope
import fr.louissaglio.kimi.android.data.network.toResult
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable

/**
 * The turn file-history routes (docs/server-api.md, Turn file history — live since
 * server 0.41.0): per-turn snapshots of the files the agent touched.
 * `turn_id` is the main agent's 0-based turn index — the transcript routes'
 * `ordinal` numbering (there is no second id). Ticket 149 adds the changes list:
 * the shipped schema carries only `changes` and `recorded`; a turn with nothing
 * recorded answers `{"changes":[],"recorded":false}`. Ticket 150 adds the content
 * route: one file's snapshot at one phase, `{"content":{version, content?, binary?}
 * | null}` — a null `content` object means the file did not exist at that phase
 * (an added file's start, a deleted file's end). The only declared error is 40401
 * (unknown session), which surfaces as the typed [fr.louissaglio.kimi.android.data.DataError.Api]
 * through the [ApiClient] scaffold like any envelope error.
 */
@Singleton
class FileHistoryApi @Inject constructor(
    private val api: ApiClient,
) {
    /**
     * Fetches one turn's changed files
     * (`GET /api/v1/sessions/{id}/file-history/changes?turn_id=N`).
     */
    suspend fun getChanges(settings: ServerSettings, sessionId: String, turnId: Int): Result<TurnFileChanges> =
        api.get(settings, "/api/v1/sessions/$sessionId/file-history/changes?turn_id=$turnId") { body ->
            api.json.decodeFromString<Envelope<FileChangesData>>(body).toResult().map { data ->
                TurnFileChanges(
                    changes = data.changes.map(::toDomain),
                    recorded = data.recorded,
                )
            }
        }

    /**
     * Fetches one file's snapshot at one phase of a turn
     * (`GET /api/v1/sessions/{id}/file-history/content?turn_id=N&path=<p>&phase=
     * "start"|"end"` — ticket 150). [path] is a server-local absolute path and rides
     * URL-encoded as the query param. A null result means the file did not exist at
     * that phase; a present snapshot with [FileContent.content] null and
     * [FileContent.binary] true is a binary file (no text to render).
     */
    suspend fun getContent(
        settings: ServerSettings,
        sessionId: String,
        turnId: Int,
        path: String,
        phase: FilePhase,
    ): Result<FileContent?> {
        val encoded = URLEncoder.encode(path, StandardCharsets.UTF_8)
        return api.get(
            settings,
            "/api/v1/sessions/$sessionId/file-history/content?turn_id=$turnId&path=$encoded&phase=${phase.wire}",
        ) { body ->
            api.json.decodeFromString<Envelope<FileContentData>>(body).toResult().map { data ->
                data.content?.let { dto ->
                    FileContent(version = dto.version, content = dto.content, binary = dto.binary)
                }
            }
        }
    }

    private fun toDomain(dto: FileChangeDto): FileChange = FileChange(
        path = dto.path,
        status = when (dto.status) {
            "added" -> FileChange.Status.Added
            "deleted" -> FileChange.Status.Deleted
            "modified" -> FileChange.Status.Modified
            else -> FileChange.Status.Other
        },
        additions = dto.additions,
        deletions = dto.deletions,
        binary = dto.binary,
        oversize = dto.oversize,
    )
}

/** One turn's file changes. [recorded] false means the server kept no snapshot for
 * the turn — the list is then empty and the screen shows its empty state. */
data class TurnFileChanges(
    val changes: List<FileChange>,
    val recorded: Boolean,
)

/** One changed file of a turn. [additions]/[deletions] are the turn's line deltas.
 * [binary]/[oversize] rows have no comparable content — the list shows a note and
 * never opens the before/after viewer for them. */
data class FileChange(
    val path: String,
    val status: Status,
    val additions: Int,
    val deletions: Int,
    val binary: Boolean = false,
    val oversize: Boolean = false,
) {
    /** The wire vocabulary is closed ("added"/"modified"/"deleted"); an unknown
     * value reads as [Other] instead of failing the whole page. */
    enum class Status { Added, Modified, Deleted, Other }
}

@Serializable
data class FileChangesData(
    val changes: List<FileChangeDto> = emptyList(),
    val recorded: Boolean = false,
)

@Serializable
data class FileChangeDto(
    val path: String = "",
    val status: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val binary: Boolean = false,
    val oversize: Boolean = false,
)

/** The content route's phase vocabulary (ticket 150): [Start] is the file as the
 * turn found it (the viewer's Before tab), [End] as the turn left it (After). */
enum class FilePhase(val wire: String) { Start("start"), End("end") }

/** One file's snapshot at one phase of a turn (ticket 150). [version] is the
 * file-history snapshot version — a debugging aid, displayed nowhere. [content] is
 * the file's text verbatim; it is absent when [binary] is true (a binary snapshot
 * carries no text). A file that did not exist at the phase never reaches this type:
 * the wire's null `content` maps to a null result. */
data class FileContent(
    val version: Int,
    val content: String?,
    val binary: Boolean,
)

@Serializable
data class FileContentData(
    val content: FileContentDto? = null,
)

@Serializable
data class FileContentDto(
    val version: Int = 0,
    val content: String? = null,
    val binary: Boolean = false,
)
