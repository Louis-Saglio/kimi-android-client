package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.JsonObject

/**
 * Domain model of `GET /api/v1/sessions/{id}/snapshot` (docs/server-api.md, Session
 * snapshot — ticket 085): the whole session state as one atomic bundle at an
 * event-stream watermark ([asOfSeq]/[epoch]). It is the single cold-start/resync
 * entry point: everything the event stream never replays (sub-agent roster, pending
 * approvals/questions) or never persisted into history arrives here, and the
 * watermark seeds the socket's resume cursor so the first subscribe stitches
 * gaplessly onto the bundle.
 */
data class SessionSnapshot(
    /** The event-stream watermark the bundle is consistent at — the resume cursor. */
    val asOfSeq: Long,
    val epoch: String,
    /** From the `session` object: the mid-turn flag and the context window fill.
     * [contextUsage] is null when the wire usage is the server's all-zero placeholder
     * (every read path since 0.32.0) — the ring's live sources are /status and the
     * WS agent.status.updated stream instead. */
    val busy: Boolean,
    val contextUsage: ContextUsage?,
    /** The newest history page (same message DTOs + `has_more` as `/messages`). */
    val messages: MessagePage,
    /** Pending permission requests (the legacy `event.approval.requested` shape). */
    val pendingApprovals: List<SnapshotApproval>,
    val pendingQuestions: List<PendingQuestion>,
    /** The foreground sub-agent roster at the watermark. */
    val subagents: List<SubagentRosterEntry>,
    /** The in-flight turn's live tail. Decoded for completeness only — NOT consumed
     * (ticket 085, out of scope): the in-flight content reaches the timeline via the
     * messages page plus live frames. */
    val inFlightTurn: InFlightTurn?,
)

/** One pending approval of the snapshot; [summary] is the card's compact argument
 * preview (ChatMessageMapper.summary, the same rendering the live event gets). */
data class SnapshotApproval(
    val approvalId: String,
    val toolName: String,
    val summary: String,
)

/** One foreground sub-agent of the snapshot roster (docs/server-api.md, Session
 * snapshot roster). [parentToolCallId] links the spawning `Agent` tool row;
 * [outputPreview] is the terminal run's result summary tail. */
data class SubagentRosterEntry(
    val id: String,
    val subagentType: String,
    val description: String,
    val phase: SubagentRosterPhase,
    val parentToolCallId: String,
    val runInBackground: Boolean,
    val outputPreview: String?,
)

/** `subagent_phase` of a roster entry; [Unknown] covers anything newer the server
 * adds — the entry still seeds (identity + frame accumulation), its status falls
 * back to the transcript-derived one. */
enum class SubagentRosterPhase { Queued, Working, Suspended, Completed, Failed, Unknown }

/** The active turn's live tail (verified 0.31.1 shape); decoded, never consumed. */
data class InFlightTurn(
    val turnId: Long,
    val assistantText: String,
    val thinkingText: String,
    val runningTools: List<RunningTool>,
    val currentPromptId: String,
)

/** One still-running tool call of the in-flight turn; [args]/[display] stay raw —
 * nothing consumes them. */
data class RunningTool(
    val toolCallId: String,
    val name: String,
    val args: JsonObject?,
    val display: JsonObject?,
)
