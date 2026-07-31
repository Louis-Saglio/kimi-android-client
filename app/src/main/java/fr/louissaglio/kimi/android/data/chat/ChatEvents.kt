package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.DataError

/** Payload tag of the main agent (docs/server-api.md, Sub-agents): every transcript
 * frame carries the emitting agent's id — "main", or the sub-agent's id for its own
 * turn frames. Untagged payloads (the interaction families) read as main. */
const val MAIN_AGENT_ID = "main"

/** The main agent's sub-agent-spawning tool (docs/server-api.md, Sub-agents). */
const val AGENT_TOOL_NAME = "Agent"

/** A transcript event scoped to one emitting agent (payload `agentId`). The chat
 * screen consumes only [MAIN_AGENT_ID] events itself; the rest are routed to the
 * sub-agent stream store (ticket 045). */
interface AgentScopedEvent {
    val agentId: String
}

/** `subagent.*` lifecycle frames (docs/server-api.md, Sub-agents): they arrive tagged
 * `agentId:"main"` but describe one sub-agent, named by [subagentId]. Only
 * [SessionEvent.SubagentSpawned] carries identity metadata. */
sealed interface SubagentLifecycleEvent : SessionEvent {
    val subagentId: String
}

/** True when the event belongs to a sub-agent's stream and must be routed to the
 * sub-agent stream store instead of the main chat: transcript frames tagged with a
 * sub-agent id, or `subagent.*` lifecycle frames (tagged "main", see above). */
fun SessionEvent.isSubagentScoped(): Boolean = when (this) {
    is SubagentLifecycleEvent -> true
    is AgentScopedEvent -> agentId != MAIN_AGENT_ID
    else -> false
}

/** Events of one session, parsed from the WebSocket stream (docs/server-api.md). */
sealed interface SessionEvent {
    data class TurnStarted(override val agentId: String = MAIN_AGENT_ID) : SessionEvent, AgentScopedEvent
    data class TurnEnded(val reason: String, override val agentId: String = MAIN_AGENT_ID) : SessionEvent, AgentScopedEvent

    /** `turn.step.retrying` (0.31.0 — ticket 058): the step's LLM call failed and
     * attempt [nextAttempt] of [maxAttempts] starts after a backoff. Transient status
     * only — it never creates a timeline row; the replacement [StepStarted] (new
     * stepId, same [step]) ends it. */
    data class StepRetrying(
        val step: Int,
        val nextAttempt: Int,
        val maxAttempts: Int,
        val errorMessage: String,
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** `turn.step.started`: parsed only to clear the retry status (ticket 058) — the
     * app renders no per-step rows. */
    data class StepStarted(val step: Int, val stepId: String, override val agentId: String = MAIN_AGENT_ID) : SessionEvent, AgentScopedEvent

    /** `turn.step.completed`: same contract as [StepStarted]. */
    data class StepCompleted(val step: Int, override val agentId: String = MAIN_AGENT_ID) : SessionEvent, AgentScopedEvent
    data class AssistantDelta(val delta: String, override val agentId: String = MAIN_AGENT_ID) : SessionEvent, AgentScopedEvent
    data class ThinkingDelta(val delta: String, override val agentId: String = MAIN_AGENT_ID) : SessionEvent, AgentScopedEvent
    data class ToolCallDelta(val toolCallId: String, val name: String, override val agentId: String = MAIN_AGENT_ID) : SessionEvent, AgentScopedEvent

    /** [preview] is derived from the started payload's display/args (ToolPreview), so the
     * live row shows what the call is doing from the start, not only after a refetch.
     * [input] is the full arguments as pretty-printed JSON, for the expanded row. */
    data class ToolCallStarted(
        val toolCallId: String,
        val name: String,
        val preview: String = "",
        val input: String = "",
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** [output] is the full result text, shown by the expanded row (ticket 023). */
    data class ToolCallResult(
        val toolCallId: String,
        val isError: Boolean,
        val output: String = "",
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** One live output chunk of a running tool (tool.progress, volatile — ticket 025).
     * Never creates a timeline row on its own; the definitive tool.result output
     * replaces the accumulated chunks wholesale. */
    data class ToolCallProgress(
        val toolCallId: String,
        val chunk: String,
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** Messages spliced into the context inline (docs/server-api.md): carries the
     * just-sent user message — the sent-prompt echo. For a sub-agent
     * ([AgentScopedEvent.agentId]) these are its internal prompts (the task, nudges). */
    data class ContextSpliced(
        val messages: List<ChatMessage>,
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** `subagent.spawned`: the only carrier of a sub-agent's identity metadata —
     * [parentToolCallId] links the spawning `Agent` tool row, [name] is the subagent
     * type (e.g. "explore"). Later lifecycle frames carry only the id. */
    data class SubagentSpawned(
        override val subagentId: String,
        val name: String,
        val parentToolCallId: String,
        val description: String,
        val runInBackground: Boolean,
    ) : SubagentLifecycleEvent

    /** `subagent.started`; re-fires when a suspended sub-agent resumes. */
    data class SubagentStarted(override val subagentId: String) : SubagentLifecycleEvent

    data class SubagentCompleted(override val subagentId: String, val resultSummary: String) : SubagentLifecycleEvent

    data class SubagentFailed(override val subagentId: String, val error: String) : SubagentLifecycleEvent

    /** `subagent.suspended` (upstream schema only, not captured live); not terminal —
     * a later [SubagentStarted] marks the resume. */
    data class SubagentSuspended(override val subagentId: String) : SubagentLifecycleEvent

    /** A tool call waits on the user's decision. [summary] is the compact argument
     * preview for the approval card (the command for shell calls, else the action). */
    data class ApprovalRequested(val approvalId: String, val toolName: String, val summary: String) : SessionEvent

    /** The request was decided — by this client or another one (event.approval.resolved). */
    data class ApprovalResolved(val approvalId: String) : SessionEvent

    /** The agent asks the user a structured question (event.question.requested); the
     * payload carries the full question inline — there is no detail endpoint to fetch. */
    data class QuestionRequested(val question: PendingQuestion) : SessionEvent

    /** The question was answered or dismissed — by this client or another one
     * (event.question.answered / event.question.dismissed share the consumed shape). */
    data class QuestionResolved(val questionId: String) : SessionEvent

    /** Live context window fill (agent.status.updated, model/usage variant — ticket 041).
     * Only emitted when the frame carries both token counts; phase-variant frames of the
     * same event type emit nothing and leave the stored value untouched. */
    data class ContextUsageUpdated(
        val contextUsage: ContextUsage,
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** `compaction.started` (docs/server-api.md, Compact — ticket 056): the server
     * began summarizing the session — no turn events, compaction is not a turn. */
    data class CompactionStarted(val trigger: String, val instruction: String?) : SessionEvent

    /** `compaction.completed`: carries the result — the summary (also persisted to
     * history) and the token delta of the compacted span. */
    data class CompactionCompleted(
        val summary: String,
        val compactedCount: Int,
        val tokensBefore: Long,
        val tokensAfter: Long,
        val keptUserMessageCount: Int,
    ) : SessionEvent

    /** `compaction.failed`/`cancelled`/`blocked` ([kind] is the wire event type): any
     * of them ends the compacting state. [cause] is the payload's best-effort message;
     * null when the (unverified) shape carried none. */
    data class CompactionFailed(val kind: String, val cause: String?) : SessionEvent
}

/** Context window fill of a session: used tokens out of the model's maximum. */
data class ContextUsage(
    val usedTokens: Long,
    val maxTokens: Long,
)

/** Snapshot of `GET /api/v1/sessions/{id}/status` (ticket 041): [busy] is the mid-turn
 * flag; [contextUsage] is null only when the success envelope carried no data object. */
data class SessionStatus(
    val busy: Boolean,
    val contextUsage: ContextUsage?,
)

/** What the socket reports to the collector: connection lifecycle + session events. */
sealed interface SocketEvent {
    /** First connection of this collection. */
    data object Connected : SocketEvent

    /** Re-connected after a drop — or a fresh collection resuming with the stored
     * cursor (ticket 048); the server replays events newer than the resume cursor. */
    data object Reconnected : SocketEvent

    /** The server refused the resume cursor (subscribe ack's `resync_required`,
     * ticket 048): no replay will come — the collector must re-sync from REST. */
    data object ResyncRequired : SocketEvent

    /** Connection lost (or never established); a retry with backoff is scheduled. */
    data object Disconnected : SocketEvent

    /** The stream cannot run at all (e.g. an invalid server URL). Surfaced as an event
     * instead of thrown so collectors see every failure in one place. */
    data class Failed(val error: DataError) : SocketEvent

    data class Event(val event: SessionEvent) : SocketEvent
}
