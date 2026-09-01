package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.JsonObject

/**
 * Domain model of `GET /api/v1/sessions/{id}/transcript` (docs/server-api.md,
 * Transcript): turn-granular committed history of ONE agent — the main agent or any
 * sub-agent. Deliberately NOT mapped through [ChatMessage] (ticket 046): the response
 * is turn → step → frame, not message → part. The ui-layer ChatTimeline flattens the
 * turns into the same `ChatItem` rows as message history.
 */
data class TranscriptPage(
    val agentId: String,
    /** Ascending by ordinal — the wire intra-page order (opposite of `/messages`). */
    val turns: List<TranscriptTurn>,
    /** True while older turns exist beyond [TranscriptTurn.id] of the first turn. */
    val hasMore: Boolean,
)

/** Turn/step states observed on the wire: "running", "completed", and (real since
 * 0.31.1) "failed"/"cancelled" — both terminal-failure states read as [Failed]. */
enum class TranscriptState { Running, Completed, Failed, Other }

data class TranscriptTurn(
    /** Wire `turnId` ("t0", "t1", …); the `before_turn`/`after_turn` cursor unit. */
    val id: String,
    val ordinal: Int,
    val state: TranscriptState,
    /** The prompting user text. Only user-origin turns carry one: a sub-agent's
     * internal turns (system_trigger/subagent) never do — the task text is not in the
     * transcript, the page's seeded prompt bubble covers it. (Task-origin turns also
     * ship a prompt — the notification XML — which the mapper deliberately drops.) */
    val prompt: String?,
    val steps: List<TranscriptStep>,
    /** Non-null only on a background-task completion-notification turn (origin kind
     * `task` — see [TranscriptTaskOrigin]). */
    val taskOrigin: TranscriptTaskOrigin? = null,
)

/** The origin of a background-task completion-notification turn (origin kind `task`,
 * verified on 0.34.0 — docs/server-api.md, Transcript): the finished task's id and
 * terminal status. This is the cold-open healing channel for sub-agent-owned jobs
 * (ticket 099) — the record's jobs are otherwise live-only (REST `/tasks` never
 * serves them). [status] keeps the core vocabulary; null when the wire value is not
 * a known one (the heal then skips the turn rather than guess). */
data class TranscriptTaskOrigin(
    val taskId: String,
    val status: TaskInfo.Status?,
)

data class TranscriptStep(
    /** Wire `stepId` ("t0.1"). */
    val id: String,
    val state: TranscriptState,
    val frames: List<TranscriptFrame>,
)

sealed interface TranscriptFrame {
    /** Wire `frameId` ("t0.1.f1", "t0.1.tool_…") — stable, used as the row key. */
    val id: String

    data class Thinking(override val id: String, val text: String) : TranscriptFrame

    data class Text(override val id: String, val role: Role, val text: String) : TranscriptFrame {
        /** "assistant" is what the wire carries; "user" would be a nudge turn. */
        enum class Role { Assistant, User, Other }
    }

    data class Tool(
        override val id: String,
        val toolCallId: String,
        val name: String,
        /** Wire state "running"; false for "done" (the only other state observed). */
        val running: Boolean,
        /** One-line input summary (ToolPreview), the collapsed row's label. */
        val preview: String,
        /** Full call input as pretty JSON — the expanded row's fallback when the call
         * carried no object [args] (ticket 118). */
        val input: String,
        /** The definitive result text; the volatile progress tail while running. */
        val output: String,
        /** The raw `input` object (ticket 118): the expanded row renders it as a
         * key/value table (ToolDetailFields). Null when the wire frame carried no
         * `input` object — the pretty string is the row's fallback there. */
        val args: JsonObject? = null,
    ) : TranscriptFrame
}
