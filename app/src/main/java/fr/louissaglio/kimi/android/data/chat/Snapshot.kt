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
     * [contextUsage] seeds the ring directly on cold start (real since 0.38.0,
     * ticket 161); it is null when the wire usage's limit is 0 or absent
     * ("unknown"), and the /status seed plus the WS agent.status.updated stream
     * keep the ring converging on the live value from there. */
    val busy: Boolean,
    val contextUsage: ContextUsage?,
    /** The newest history page (same message DTOs + `has_more` as `/messages`). */
    val messages: MessagePage,
    /** Pending permission requests (the legacy `event.approval.requested` shape). */
    val pendingApprovals: List<SnapshotApproval>,
    val pendingQuestions: List<PendingQuestion>,
    /** The foreground sub-agent roster at the watermark. */
    val subagents: List<SubagentRosterEntry>,
    /** The in-flight turn's live tail: the ONLY carrier of the turn's pre-watermark
     * content — the resume-cursor replay covers frames after the watermark and the
     * turn's messages commit to history at its end, so a cold open of a busy session
     * seeds its live tail from here (ticket 144) instead of showing a gap between the
     * last committed message and the live stream. */
    val inFlightTurn: InFlightTurn?,
)

/** One pending approval of the snapshot; [summary] is the card's compact argument
 * preview (ChatMessageMapper.summary, the same rendering the live event gets).
 * [agentId] is the asking agent (ticket 139): the snapshot items carry no such stamp
 * as of 0.43.1 (verified upstream, kap-server's toWireApproval — unlike the live
 * `event.approval.requested` payload), so it reads "main" here until upstream stamps
 * them; the decode rides the shared ApprovalRequestedPayload DTO, so a future stamp
 * lands without a wire change. */
data class SnapshotApproval(
    val approvalId: String,
    val toolName: String,
    val summary: String,
    val agentId: String = MAIN_AGENT_ID,
)

/** One foreground sub-agent of the snapshot roster (docs/server-api.md, Session
 * snapshot roster). [parentToolCallId] links the spawning `Agent`/`AgentSwarm` tool
 * row; [outputPreview] is the terminal run's result summary tail. [swarmIndex] is
 * the member's position inside an `AgentSwarm` call (schema-only upstream — parsed
 * for ordering, never depended on). [taskId] is the background-task registration
 * id: the roster carries no such field (verified upstream, ticket 134), so it is
 * always null here — records heal it from the `subagent.spawned` frame only.
 * [model]/[thinkingEffort] are the run's bound model/effort (0.34.0). */
data class SubagentRosterEntry(
    val id: String,
    val subagentType: String,
    val description: String,
    val phase: SubagentRosterPhase,
    val parentToolCallId: String,
    val runInBackground: Boolean,
    val outputPreview: String?,
    val swarmIndex: Int? = null,
    val taskId: String? = null,
    val model: String? = null,
    val thinkingEffort: String? = null,
)

/** `subagent_phase` of a roster entry; [Unknown] covers anything newer the server
 * adds — the entry still seeds (identity + frame accumulation), its status falls
 * back to the transcript-derived one. [Cancelled] is upstream schema-level (never
 * captured live): the live carrier of a deliberate stop is `task.terminated` with
 * `status:"killed"` (ticket 135). */
enum class SubagentRosterPhase { Queued, Working, Suspended, Completed, Failed, Cancelled, Unknown }

/** The active turn's live tail (verified 0.31.1 shape). */
data class InFlightTurn(
    val turnId: Long,
    val assistantText: String,
    val thinkingText: String,
    val runningTools: List<RunningTool>,
    val currentPromptId: String,
)

/** One still-running tool call of the in-flight turn; [preview]/[input] are the row
 * renderings derived at the mapping boundary (the same ones a live `tool.call.started`
 * carries), [args] stays raw for the typed payload. */
data class RunningTool(
    val toolCallId: String,
    val name: String,
    val args: JsonObject?,
    val preview: String,
    val input: String,
)
