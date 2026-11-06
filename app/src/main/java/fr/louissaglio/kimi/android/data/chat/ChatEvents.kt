package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.DataError
import kotlinx.serialization.json.JsonObject

/** Payload tag of the main agent (docs/server-api.md, Sub-agents): every transcript
 * frame carries the emitting agent's id — "main", or the sub-agent's id for its own
 * turn frames. Untagged payloads (the interaction families) read as main. */
const val MAIN_AGENT_ID = "main"

/** The main agent's sub-agent-spawning tool (docs/server-api.md, Sub-agents). */
const val AGENT_TOOL_NAME = "Agent"

/** The main agent's swarm-spawning tool (docs/server-api.md, Sub-agents): one call
 * fans out N sub-agents sharing its tool call id as `parentToolCallId` (ticket 134). */
const val AGENT_SWARM_TOOL_NAME = "AgentSwarm"

/** The managed Kimi provider's name in the model catalog — the only provider whose
 * effort descriptions the client config supplies (ticket 170). */
const val MANAGED_KIMI_PROVIDER = "managed:kimi-code"

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

/** The `agent_call` rendering of a tool frame's `display` (0.31.0 —
 * docs/server-api.md, Sub-agents — ticket 157): the typed model of an `Agent`
 * spawn's display, carried to the row that titles itself "{[agentName]} sub-agent",
 * previews [prompt] and badges a [background] spawn. History `tool_use` parts carry
 * no display, so this exists only on rows witnessed live (or seeded from a
 * running transcript turn's tool frame on a cold open — ticket 176). */
data class AgentCallDisplay(
    val agentName: String,
    val prompt: String,
    val background: Boolean,
)

/** Events of one session, parsed from the WebSocket stream (docs/server-api.md). */
sealed interface SessionEvent {
    /** `prompt.upsert` (docs/server-api.md, Transcript protocol — ticket 175, replacing
     * 154's `prompt.submitted`/`queued`/`started`): one prompt entity changed status.
     * [queueLength] is the number of prompts the agent holds queued once this one is
     * counted — set on [PromptStatus.Queued] only (the echo bubble's "Queued #N"), 0
     * otherwise. A steered prompt flips to [PromptStatus.Completed]. Agent-scoped like
     * every transcript event: a sub-agent's prompts route to its stream store. */
    data class PromptUpdated(
        val promptId: String,
        val status: PromptStatus,
        val queueLength: Int = 0,
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** The agent's op journal could not cover the resume watermark (ticket 176): the
     * server answered the subscribe's `transcript_since` with a fresh
     * `transcript.reset` instead of replaying the missed batches. The socket has
     * already reset the agent's fold; the collector rebuilds the agent's rows from
     * REST (the main chat refetches its cold start) instead of trusting a partial
     * replay. */
    data class TranscriptStale(override val agentId: String = MAIN_AGENT_ID) : SessionEvent, AgentScopedEvent

    data class TurnStarted(override val agentId: String = MAIN_AGENT_ID) : SessionEvent, AgentScopedEvent

    /** A terminal `turn.upsert` (ticket 175): [reason] is the turn's terminal state
     * (`completed`/`cancelled`/`failed`). [interruptReason] says why the turn stopped
     * early (ticket 155's footer vocabulary — `user_cancelled` for a cancelled turn,
     * `error` for a failed one); null on a completed turn. [errorMessage] is a failed
     * turn's `error` text — the footer carries it (the standalone `error` frame is
     * gone). */
    data class TurnEnded(
        val reason: String,
        override val agentId: String = MAIN_AGENT_ID,
        val interruptReason: String? = null,
        val errorMessage: String? = null,
    ) : SessionEvent, AgentScopedEvent

    /** A running `step.upsert` carrying a `retry` object (ticket 058, re-fed by 175 —
     * the `retry` shape is schema-level, never captured live): the step's LLM call
     * failed and attempt [nextAttempt] of [maxAttempts] starts after a backoff.
     * Transient status only — it never creates a timeline row; the next step's
     * [StepStarted], the step's end, or the turn's end clears it. */
    data class StepRetrying(
        val step: Int,
        val nextAttempt: Int,
        val maxAttempts: Int,
        val errorMessage: String,
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** A `step.upsert` entering `running`: parsed only to clear the retry status
     * (ticket 058) — the app renders no per-step rows. */
    data class StepStarted(val step: Int, val stepId: String, override val agentId: String = MAIN_AGENT_ID) : SessionEvent, AgentScopedEvent

    /** A `step.upsert` reaching `completed` or `interrupted`: same contract as
     * [StepStarted]. */
    data class StepCompleted(val step: Int, override val agentId: String = MAIN_AGENT_ID) : SessionEvent, AgentScopedEvent
    data class AssistantDelta(val delta: String, override val agentId: String = MAIN_AGENT_ID) : SessionEvent, AgentScopedEvent
    data class ThinkingDelta(val delta: String, override val agentId: String = MAIN_AGENT_ID) : SessionEvent, AgentScopedEvent

    /** A tool frame first seen — or seen again with newly known arguments/display —
     * while not yet settled (ticket 175). [preview] is derived from the frame's
     * display/args (tickets 095/153, ToolPreview), so the live row shows what the call
     * is doing from the start, not only after a refetch. [args] is the raw arguments object
     * (ticket 118), rendered by the expanded row as a key/value table
     * (ToolDetailFields); [input] is the same arguments pretty-printed —
     * the expanded row's fallback when no object args exist. [agentCall] is the
     * parsed `agent_call` display (ticket 157) — the spawn row's model, null for
     * every other call. */
    data class ToolCallStarted(
        val toolCallId: String,
        val name: String,
        val preview: String = "",
        val input: String = "",
        val args: JsonObject? = null,
        override val agentId: String = MAIN_AGENT_ID,
        val agentCall: AgentCallDisplay? = null,
    ) : SessionEvent, AgentScopedEvent

    /** A tool frame reaching `done`/`error` (ticket 175). [output] is the full result
     * text, shown by the expanded row (ticket 023). */
    data class ToolCallResult(
        val toolCallId: String,
        val isError: Boolean,
        val output: String = "",
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** An `undo` marker's splice (docs/server-api.md, Transcript protocol — ticket 175):
     * carries the just-sent user message — the sent-prompt echo — with injections
     * (`origin.kind:"injection"`) already dropped. For a sub-agent
     * ([AgentScopedEvent.agentId]) these are its internal prompts (the task, nudges). */
    data class ContextSpliced(
        val messages: List<ChatMessage>,
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** `subagent.spawned`: the only carrier of a sub-agent's identity metadata —
     * [parentToolCallId] links the spawning `Agent`/`AgentSwarm` tool row, [name] is
     * the subagent type (e.g. "explore"). Later lifecycle frames carry only the id.
     * [swarmIndex] is the member's position inside an `AgentSwarm` call (schema-only
     * upstream, never captured live — docs/server-api.md:3311): parsed for ordering,
     * never depended on. [taskId] is the member's background-task registration id
     * (0.38.0). Both are null for a plain `Agent` spawn. [model]/[thinkingEffort]
     * are the run's bound model/effort (0.34.0) — the swarm aggregate's shared-model
     * line reads them (ticket 135). */
    data class SubagentSpawned(
        override val subagentId: String,
        val name: String,
        val parentToolCallId: String,
        val description: String,
        val runInBackground: Boolean,
        val swarmIndex: Int? = null,
        val taskId: String? = null,
        val model: String? = null,
        val thinkingEffort: String? = null,
    ) : SubagentLifecycleEvent

    /** `subagent.started`; re-fires when a suspended sub-agent resumes. */
    data class SubagentStarted(override val subagentId: String) : SubagentLifecycleEvent

    data class SubagentCompleted(override val subagentId: String, val resultSummary: String) : SubagentLifecycleEvent

    data class SubagentFailed(override val subagentId: String, val error: String) : SubagentLifecycleEvent

    /** `subagent.suspended` (upstream schema: `{subagentId, reason}`); not terminal —
     * a later [SubagentStarted] marks the resume. [reason] is the suspend cause,
     * shown as the swarm roster row's activity line (ticket 136); blank when the
     * frame carried none. */
    data class SubagentSuspended(override val subagentId: String, val reason: String) : SubagentLifecycleEvent

    /** `subagent.cancelled` (upstream schema-level, never captured live): the run was
     * stopped on purpose. The LIVE carrier of the same truth is `task.terminated`
     * with `status:"killed"` (docs/server-api.md) — no `subagent.completed`/`failed`
     * follows either path, so both map to the store's Cancelled state (ticket 135). */
    data class SubagentCancelled(override val subagentId: String) : SubagentLifecycleEvent

    /** A tool call waits on the user's decision. [summary] is the compact argument
     * preview for the approval card (the command for shell calls, else the action).
     * [agentId] is the ASKING agent (ticket 139) — "main", or the sub-agent's id:
     * presentation only (the card's sub-agent badge), deliberately NOT an
     * [AgentScopedEvent] — the request resolves through the session-level approvals
     * route whoever asked, so it must stay main-chat-routed. */
    data class ApprovalRequested(
        val approvalId: String,
        val toolName: String,
        val summary: String,
        val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent

    /** The request was decided — by this client or another one (event.approval.resolved). */
    data class ApprovalResolved(val approvalId: String) : SessionEvent

    /** The agent asks the user a structured question (event.question.requested); the
     * payload carries the full question inline — there is no detail endpoint to fetch.
     * The question's [PendingQuestion.agentId] is the ASKING agent (2.0.2 — ticket
     * 156), presentation only like an approval's: the request resolves through the
     * session-level questions route whoever asked, so this stays main-chat-routed. */
    data class QuestionRequested(val question: PendingQuestion) : SessionEvent

    /** The question was answered or dismissed — by this client or another one
     * (event.question.answered / event.question.dismissed share the consumed shape). */
    data class QuestionResolved(val questionId: String) : SessionEvent

    /** Live context window fill (the `usage` state frame — server 2.5.0, ticket 177).
     * Only emitted when the frame carries the used tokens AND a positive limit: an
     * absent or zero `max_context_tokens` is the engine's "unknown" marker and emits
     * nothing, leaving the displayed ring untouched (never a 0/0 fill). */
    data class ContextUsageUpdated(
        val contextUsage: ContextUsage,
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** The session's bound model/effort as the server reports it (the `agent` state
     * frame — ticket 177, after 107's `/status` seed). Emitted when the frame carries
     * either value; a null field means the frame said nothing about it (a model-less
     * session omits both), never "unset". */
    data class SessionModelUpdated(
        val model: String?,
        val thinkingEffort: String?,
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** The session's effective permission mode as the server reports it (the `agent`
     * frame's `permission_mode` — ticket 177, after 165). The server re-sends the
     * frame the moment the mode changes, including when another client writes it
     * through the profile route; the chat's chip follows it unless a local pick is in
     * force (ticket 141). */
    data class SessionPermissionUpdated(
        val permission: String,
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** The agent's busy state (the `agent` frame's `status` — ticket 177, replacing
     * 148's phase variant of the removed `agent.status.updated`): [running] while a turn
     * or background work keeps the agent busy, false on `idle`. An unknown status
     * value emits nothing. The status line's sub-phase (tool call, retry, waiting on
     * the user) is derived from the transcript state by the ViewModel — the frame
     * carries none. */
    data class AgentBusyUpdated(
        val running: Boolean,
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

    /** `task.started` (docs/server-api.md, Background lifecycle — ticket 092): a
     * background task (sub-agent or bash) registered in the task store — REST-visible
     * from here. [info] carries the CORE status vocabulary. [agentId] (the envelope
     * stamp) names the OWNING agent — "main", or the sub-agent that spawned the task
     * (its tasks never reach REST `/tasks`, which serves only the main agent's store);
     * `info.agentId` is a different link — the spawned sub-agent's page id. */
    data class TaskStarted(val info: TaskInfo, override val agentId: String = MAIN_AGENT_ID) : SessionEvent, AgentScopedEvent

    /** `task.terminated`: the task reached its terminal status ([TaskInfo.status] still
     * core vocabulary — killed/timed_out/lost included; the collector maps it to the
     * REST wire one). `task.notified` and the legacy `background.task.*` aliases are
     * deliberately NOT parsed (web parity) — they fall into the unknown-type Log path.
     * [agentId] is the owning agent, exactly as on [TaskStarted]. */
    data class TaskTerminated(val info: TaskInfo, override val agentId: String = MAIN_AGENT_ID) : SessionEvent, AgentScopedEvent
}

/** A prompt entity's status (`prompt.upsert`, docs/server-api.md — ticket 175). */
enum class PromptStatus { Running, Queued, Completed, Aborted, Blocked, Failed }

/** Context window fill of a session: used tokens out of the model's maximum. */
data class ContextUsage(
    val usedTokens: Long,
    val maxTokens: Long,
)

/** What the socket reports to the collector: connection lifecycle + session events. */
sealed interface SocketEvent {
    /** First connection of this collection. */
    data object Connected : SocketEvent

    /** Re-connected after a drop within this collection (ticket 175): the subscription
     * did not survive the drop, so the socket re-subscribed — the server re-sends a
     * fresh `transcript.reset` and the live ops from now on, never the missed window
     * (ticket 176 owns gap recovery). The collector re-syncs from REST. */
    data object Reconnected : SocketEvent

    /** Connection lost (or never established); a retry with backoff is scheduled. */
    data object Disconnected : SocketEvent

    /** The stream cannot run at all — an invalid server URL, or a session the server
     * does not know (the subscribe ack's `not_found`, [DataError.SessionNotFound]).
     * Terminal: the flow completes after it. Surfaced as an event instead of thrown so
     * collectors see every failure in one place. */
    data class Failed(val error: DataError) : SocketEvent

    data class Event(val event: SessionEvent) : SocketEvent
}
