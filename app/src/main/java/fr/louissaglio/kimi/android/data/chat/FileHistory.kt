package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.network.ApiClient
import fr.louissaglio.kimi.android.data.network.Envelope
import fr.louissaglio.kimi.android.data.network.toResult
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable

/**
 * The turn file-history routes (docs/server-api.md, Turn file history — live since
 * server 0.41.0, ticket 149): per-turn snapshots of the files the agent touched.
 * `turn_id` is the main agent's 0-based turn index — the transcript routes'
 * `ordinal` numbering (there is no second id). The shipped schema carries only
 * `changes` and `recorded`; a turn with nothing recorded answers
 * `{"changes":[],"recorded":false}`. The only declared error is 40401 (unknown
 * session), which surfaces as the typed [fr.louissaglio.kimi.android.data.DataError.Api]
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
