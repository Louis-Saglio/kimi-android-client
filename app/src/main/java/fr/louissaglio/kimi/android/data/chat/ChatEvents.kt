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

/** True when the event belongs to a sub-agent's stream and must be routed to the
 * sub-agent stream store instead of the main chat: every agent-scoped event a child
 * agent emits (its transcript ops and reset signal, its `agent`/`todo` state frames,
 * its own `task.list`/`task` frames). A child's SPAWN record is a main-owned `task`
 * frame (`kind:"subagent"`, ticket 182) — main-scoped here, so the chat sees it too
 * (its jobs widget) and forwards it to the store. */
fun SessionEvent.isSubagentScoped(): Boolean = this is AgentScopedEvent && agentId != MAIN_AGENT_ID

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
     * text, shown by the expanded row (ticket 023); [media] the result's
     * `session_media` references (ticket 184 — ChatMessageMapper.toolOutput). */
    data class ToolCallResult(
        val toolCallId: String,
        val isError: Boolean,
        val output: String = "",
        override val agentId: String = MAIN_AGENT_ID,
        val media: List<ToolMedia> = emptyList(),
    ) : SessionEvent, AgentScopedEvent

    /** An `undo` marker's splice (docs/server-api.md, Transcript protocol — ticket 175):
     * carries the just-sent user message — the sent-prompt echo — with injections
     * (`origin.kind:"injection"`) already dropped. For a sub-agent
     * ([AgentScopedEvent.agentId]) these are its internal prompts (the task, nudges). */
    data class ContextSpliced(
        val messages: List<ChatMessage>,
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** A tool call waits on the user's decision (a `pending` approval `interaction`
     * frame — ticket 171). [approvalId] is the interaction id; [summary] is the compact
     * argument preview for the approval card (the command for shell calls, else the
     * action). [agentId] is the ASKING agent (ticket 139) — "main", or the sub-agent's
     * id: presentation only (the card's sub-agent badge), deliberately NOT an
     * [AgentScopedEvent] — the request resolves through the session-level interactions
     * route whoever asked, so it must stay main-chat-routed. [plan] is the Markdown
     * of a `plan_review` display (`ExitPlanMode` — ticket 173), null for any other
     * request. */
    data class ApprovalRequested(
        val approvalId: String,
        val toolName: String,
        val summary: String,
        val agentId: String = MAIN_AGENT_ID,
        val plan: String? = null,
    ) : SessionEvent

    /** The request ended — decided by this client or another one, or cancelled with
     * its turn (an `ended` approval `interaction` frame, any outcome — ticket 171). */
    data class ApprovalResolved(val approvalId: String) : SessionEvent

    /** The session's pending approvals as of an accepted `subscribe_v2` (ticket 171):
     * emitted on the ack, carrying the pending approvals of the connection's
     * `transcript.reset` snapshots (empty on a resumed subscribe, which sends no
     * reset). The queue is REPLACED — a decision missed while disconnected heals — and
     * the standalone pending frames the server re-sends after the ack dedup into it. */
    data class ApprovalsReset(val pending: List<PendingApprovalRequest>) : SessionEvent

    /** The agent asks the user a structured question (a `pending` question
     * `interaction` frame — ticket 172); the frame carries the full question inline —
     * there is no detail endpoint to fetch. [PendingQuestion.questionId] is the
     * interaction id; [PendingQuestion.agentId] is the ASKING agent (ticket 156) —
     * presentation only, deliberately NOT an [AgentScopedEvent]: the request resolves
     * through the session-level interactions route whoever asked, so this stays
     * main-chat-routed. */
    data class QuestionRequested(val question: PendingQuestion) : SessionEvent

    /** The question ended — answered or dismissed by this client or another one, or
     * dismissed with its aborted turn (an `ended` question `interaction` frame, any
     * outcome — ticket 172). */
    data class QuestionResolved(val questionId: String) : SessionEvent

    /** The session's pending questions as of an accepted `subscribe_v2` (ticket 172 —
     * [ApprovalsReset]'s twin): the pending questions of the connection's
     * `transcript.reset` snapshots (empty on a resumed subscribe). The queue is
     * REPLACED, and the pending frames the server re-sends after the ack dedup into it
     * by question id. */
    data class QuestionsReset(val pending: List<PendingQuestion>) : SessionEvent

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

    /** The session's swarm mode as the server reports it (the `agent` frame's
     * `swarm_mode` — ticket 174): re-sent on every change, including the one a prompt's
     * own `swarm_mode` field makes and a profile write from another client. The
     * composer's swarm chip follows it unless an unsent local toggle is in force. */
    data class SessionSwarmModeUpdated(
        val swarmMode: Boolean,
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

    /** The agent's full todo list (the flat `todo` frame — server 2.5.0, ticket 178):
     * replaces the widget's list wholesale, an empty [items] clears it. The first one
     * precedes the subscribe ack (the cold-start seed) and the server re-sends it on
     * every change, undo and cold restore included — the list is never derived from
     * `TodoList` tool calls. Agent-scoped: main's feeds the chat's widget, a child's
     * its sub-agent page's dock. */
    data class TodoUpdated(
        val items: List<Todo>,
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** A `marker:"compaction"` transcript item with `phase:"started"` (docs/server-api.md,
     * Compact → WebSocket sequence — server 2.5.0, ticket 181; before: ticket 056's
     * `compaction.started`): the server began summarizing [agentId]'s context — no turn
     * ops, compaction is not a turn. [trigger] is `manual` for a `:compact`. */
    data class CompactionStarted(
        val trigger: String,
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** The `phase:"completed"` compaction marker: its `result` block — the summary (also
     * persisted to history) and the token delta of the compacted span. The context
     * rewrite itself rides the `undo` marker between the two (a [ContextSpliced]). There
     * is no failure marker: a compaction that never completes shows only as the stream
     * going idle ([AgentBusyUpdated] / [SessionWorkChanged]) without this event. */
    data class CompactionCompleted(
        val summary: String,
        val compactedCount: Int,
        val tokensBefore: Long,
        val tokensAfter: Long,
        val keptUserMessageCount: Int,
        override val agentId: String = MAIN_AGENT_ID,
    ) : SessionEvent, AgentScopedEvent

    /** The global `event.session.work_changed` frame for THIS session (docs/server-api.md,
     * Global events — ticket 181): [busy] covers turns AND compactions (a compaction
     * reads `busy:true`, `main_turn_active:false` for its whole run), so `busy:false` is
     * the session going idle — the signal that ends a compaction whose completed marker
     * never came. Not journaled, never replayed. */
    data class SessionWorkChanged(val busy: Boolean) : SessionEvent

    /** `task.list` (docs/server-api.md, Sub-agents → Task frames and controls — server
     * 2.5.0, ticket 179): every RUNNING task of [agentId] (the OWNING agent — "main",
     * or a child agent that started its own tasks), sent when the connection starts
     * receiving that agent (before the subscribe ack, again on every reconnect). It is
     * authoritative for running tasks only: ended tasks are never listed again, so
     * consumers merge it instead of replacing their terminal rows. */
    data class TaskListed(val tasks: List<TaskState>, override val agentId: String = MAIN_AGENT_ID) :
        SessionEvent, AgentScopedEvent

    /** `task`: one task's FULL state after a change (start, phase change, detach, end) —
     * also emitted for a task control's code-0 reply, whose `task` is the state after
     * the control. The `status:"ended"` frame is the task's last. [agentId] is the
     * owning agent, exactly as on [TaskListed]; a sub-agent spawn's own agent id is
     * [TaskState.childAgentId]. */
    data class TaskUpdated(val task: TaskState, override val agentId: String = MAIN_AGENT_ID) :
        SessionEvent, AgentScopedEvent
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
