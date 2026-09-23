package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// DTOs for GET /api/v1/sessions/{id}/messages and the /api/v1/ws frames
// (docs/server-api.md). Unknown fields are ignored (Json { ignoreUnknownKeys = true });
// only what the app consumes is declared.

@Serializable
data class MessageListData(
    val items: List<MessageDto> = emptyList(),
    // Surfaced as MessagePage.hasMore — the cursor of ticket 043's backwards paging.
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class MessageDto(
    // The steer echo splices the steered user message without an id (docs/server-api.md);
    // the compaction summary omits the key too since 0.31.0 (Compact) — both read as "".
    val id: String? = null,
    val role: String = "",
    val content: List<ContentPartDto> = emptyList(),
    // ISO-8601 UTC instant ("2026-07-23T03:27:03.317Z"); absent on splice echoes.
    @SerialName("created_at") val createdAt: String? = null,
    // Origin kind (ticket 056): history carries it at metadata.origin.kind, the
    // context.spliced echo at the message's top-level origin.kind — same kind space.
    val metadata: MessageMetadataDto? = null,
    val origin: MessageOriginDto? = null,
) {
    /** The message's origin kind wherever the wire shape puts it; null when absent. */
    val originKind: String? get() = metadata?.origin?.kind ?: origin?.kind

    /** The prompt's file attachments wherever the wire shape puts them (ticket 160 —
     * 0.39.1, docs/server-api.md, Prompts); null when the message carried none. */
    val originAttachments: List<OriginAttachmentDto>? get() = metadata?.origin?.attachments ?: origin?.attachments
}

@Serializable
data class MessageMetadataDto(
    val origin: MessageOriginDto? = null,
)

@Serializable
data class MessageOriginDto(
    val kind: String = "",
    // 0.39.1: every prompt file attachment is recorded here, camelCase
    // (docs/server-api.md, Prompts) — the structured source of the chat's chips.
    val attachments: List<OriginAttachmentDto>? = null,
)

/** One entry of `origin.attachments` (ticket 160). [path] is server-local: the
 * materialized `<sessionDir>/attachments/<fileId>-<name>` copy for uploads, the original
 * path for server-local ones — never displayed, only mined for the file id. */
@Serializable
data class OriginAttachmentDto(
    val name: String = "",
    val mediaType: String = "",
    val size: Long = 0,
    val path: String = "",
)

/** Flat part shape: each `type` fills only its own fields, the rest stay null. */
@Serializable
data class ContentPartDto(
    val type: String = "",
    val text: String? = null,
    val thinking: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
    // Raw tool arguments on tool_use parts ({"command": "..."} for Bash); the
    // one-line row preview is derived from it (ToolPreview).
    val input: JsonObject? = null,
    // tool_result parts: the full output text (may be a JSON value for structured
    // tools like AskUserQuestion); rendered by the expanded tool row (ticket 023).
    val output: JsonElement? = null,
    @SerialName("is_error") val isError: Boolean? = null,
    // image parts (ticket 034): the inline source. Since 0.37.2 both history and the
    // prompt ACK echo carry a session_media file_id (ticket 104); the pre-0.37.2 shapes
    // were a history data-url, ACK raw base64, a file reference a file_id
    // (docs/server-api.md).
    val source: MediaSourceDto? = null,
    // 0.42.0: image/video parts carry the original file's `name` on both the ACK echo
    // and history (docs/server-api.md, Prompts) — the bubble's filename label (160).
    val name: String? = null,
)

/** `source` of an image content part; each `kind` fills only its own fields. */
@Serializable
data class MediaSourceDto(
    val kind: String = "",
    @SerialName("media_type") val mediaType: String? = null,
    val data: String? = null,
    val url: String? = null,
    @SerialName("file_id") val fileId: String? = null,
)

@Serializable
data class SessionStatusData(
    val busy: Boolean = false,
    // Context window fill (ticket 041); defaulted like every DTO field so a server
    // omission degrades to a partial read instead of failing the whole status poll.
    @SerialName("context_tokens") val contextTokens: Long = 0,
    @SerialName("max_context_tokens") val maxContextTokens: Long = 0,
    // The session's bound model/effort (ticket 107): `model` is omitted entirely when
    // unbound, `thinking_level` reads "" on a fresh never-prompted session.
    val model: String? = null,
    @SerialName("thinking_level") val thinkingLevel: String? = null,
    // The session's permission mode (ticket 141): manual/yolo/auto. Unlike `model` it
    // is ALWAYS reported — a fresh session reads the server default.
    val permission: String? = null,
)

/**
 * `agent.status.updated` payload (docs/server-api.md, volatile). Two variants share the
 * event type: the model/usage one carries [contextTokens]/[maxContextTokens], the phase
 * one carries [phase] — its null token fields must not clobber the stored values.
 */
@Serializable
data class AgentStatusUpdatedPayload(
    val contextTokens: Long? = null,
    val maxContextTokens: Long? = null,
    // Model/usage variant only (ticket 107): the session's bound model and effort.
    val model: String? = null,
    val thinkingEffort: String? = null,
    // Phase variant only (ticket 148): the agent's live activity.
    val phase: AgentPhaseDto? = null,
)

/** `phase` of the agent.status.updated phase variant (docs/server-api.md — ticket 148):
 * every field beyond [kind] fills only for its own kind — [since] (epoch ms) rides the
 * ACTIVE kinds, [name]/[toolCallId] the tool_call kind, [approval] awaiting_approval;
 * `ended`/`interrupted` carry `at` instead of [since] (not decoded — no elapsed readout
 * exists for them). The `streaming` kind was removed in 0.43.0 (the phase stays
 * `running` while tokens stream). */
@Serializable
data class AgentPhaseDto(
    val kind: String = "",
    val turnId: Long? = null,
    val step: Int? = null,
    val stepId: String? = null,
    val since: Long? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val approval: AgentPhaseApprovalDto? = null,
)

/** awaiting_approval's inline approval; this ticket consumes the kind only, never the
 * ids ([approvalId] is the dedicated `approval_<uuid>` since 0.39.0, distinct from
 * [toolCallId]). */
@Serializable
data class AgentPhaseApprovalDto(
    val approvalId: String? = null,
    val toolCallId: String? = null,
)

/** `data` of `GET /api/v1/config` (and the POST's echo): the app consumes the
 * server-wide default model, the default thinking effort, the default permission
 * mode (ticket 142) and the providers map (credentials redacted to `has_api_key`)
 * — ticket 109. */
@Serializable
data class ConfigData(
    @SerialName("default_model") val defaultModel: String? = null,
    @SerialName("default_permission_mode") val defaultPermissionMode: String? = null,
    val thinking: ThinkingConfigDto? = null,
    val providers: Map<String, ProviderDto> = emptyMap(),
)

@Serializable
data class ThinkingConfigDto(
    val effort: String? = null,
)

@Serializable
data class ProviderDto(
    val type: String = "",
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("has_api_key") val hasApiKey: Boolean = false,
)

/** `data` of `GET /api/v1/models` (docs/server-api.md). */
@Serializable
data class ModelListData(
    val items: List<ModelDto> = emptyList(),
)

@Serializable
data class ModelDto(
    val provider: String = "",
    val model: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("max_context_size") val maxContextSize: Long = 0,
    // Only on models with thinking-effort levels (K3 on 0.29.0); absent elsewhere.
    @SerialName("support_efforts") val supportEfforts: List<String>? = null,
    @SerialName("default_effort") val defaultEffort: String? = null,
)

/** `data` of the POST /prompts ack. */
@Serializable
data class PromptData(
    @SerialName("prompt_id") val promptId: String = "",
)

/** `data` of `POST /api/v1/files` (docs/server-api.md, Files). [mediaType] is sniffed
 * from the bytes server-side — the `name` field and extension are ignored there. */
@Serializable
data class FileUploadData(
    val id: String = "",
    val name: String = "",
    @SerialName("media_type") val mediaType: String = "",
    val size: Long = 0,
)

/** Envelope of one WebSocket frame; [payload] is decoded per [type] by the repository. */
@Serializable
data class WsFrame(
    val type: String = "",
    val seq: Long? = null,
    val epoch: String? = null,
    // The emitting session, on event frames only (docs/server-api.md, Event envelope).
    // 0.31.0 sends events for sessions this connection never subscribed to, so
    // SessionSocket drops any frame whose sessionId is not the subscribed one.
    @SerialName("session_id") val sessionId: String? = null,
    // Only on high-frequency deltas; absent otherwise (docs/server-api.md). Volatile
    // frames share the seq of the event they piggyback on, so they must not move the
    // resume cursor.
    val volatile: Boolean = false,
    val payload: JsonObject? = null,
)

/** Peek at the emitter tag every event payload carries (docs/server-api.md,
 * Sub-agents): `"main"` for the main agent, the sub-agent's id for its own frames.
 * Untagged payloads (the interaction families) read as main. */
@Serializable
data class AgentIdPeek(val agentId: String = "main")

/**
 * `payload` of a subscribe/unsubscribe `ack` frame (docs/server-api.md); only
 * `resync_required` is consumed — the sessions whose resume cursor the server
 * could not honor (replay buffer overrun). No replay will come for them: the
 * collector must re-sync from REST (ticket 048).
 */
@Serializable
data class AckPayload(
    @SerialName("resync_required") val resyncRequired: List<String> = emptyList(),
)

@Serializable
data class DeltaPayload(val delta: String = "")

@Serializable
data class ToolCallPayload(
    @SerialName("toolCallId") val toolCallId: String = "",
    val name: String = "",
    // tool.call.started only (docs/server-api.md): the full arguments, plus the
    // server's display rendering — both feed the live row preview.
    val args: JsonObject? = null,
    val display: ToolInputDisplayDto? = null,
    // tool.call.started only: the server-rendered human-readable action (same text
    // as the approval's `action`) — the row title's first choice (ticket 153).
    val description: String? = null,
)

@Serializable
data class ToolResultPayload(
    @SerialName("toolCallId") val toolCallId: String = "",
    // String for shell-style tools, a JSON value for structured ones (docs/server-api.md).
    val output: JsonElement? = null,
    @SerialName("is_error") val isError: Boolean = false,
)

/** `tool.progress` payload (docs/server-api.md, volatile live tool output):
 * `update` carries one output chunk (`kind` "stdout", possibly others later). */
@Serializable
data class ToolProgressPayload(
    @SerialName("toolCallId") val toolCallId: String = "",
    val update: ToolProgressUpdateDto? = null,
)

@Serializable
data class ToolProgressUpdateDto(
    val kind: String = "",
    val text: String = "",
)

/** `turn.ended` payload. [interruptReason] (0.31.1, docs/server-api.md — ticket 155)
 * says why a turn stopped early ("user_cancelled" on a REST `:abort`, else
 * "aborted"/"max_steps"/"error"/"filtered"/"blocked"); null on a completed turn. */
@Serializable
data class TurnEndedPayload(val reason: String = "", val interruptReason: String? = null)

/** `turn.step.retrying` payload (docs/server-api.md, 0.31.0 — ticket 058): the step's
 * LLM call failed ([errorName]/[errorMessage]/[statusCode]) and attempt [nextAttempt]
 * of [maxAttempts] starts after [delayMs]; a fresh `turn.step.started` with a NEW
 * stepId then re-fires for the same step number. [delayMs] is a double on the wire. */
@Serializable
data class StepRetryingPayload(
    val step: Int = 0,
    val stepId: String = "",
    val failedAttempt: Int = 0,
    val nextAttempt: Int = 0,
    val maxAttempts: Int = 0,
    val delayMs: Double = 0.0,
    val errorName: String = "",
    val errorMessage: String = "",
    val statusCode: Int? = null,
)

/** Shared consumed shape of `turn.step.started`/`turn.step.completed`: only the step
 * identity is used — clearing the retry status (ticket 058). Every other field
 * (usage, latency stats, finishReason) stays ignored. */
@Serializable
data class TurnStepPayload(
    val step: Int = 0,
    val stepId: String = "",
)

// --- prompt.* lifecycle (docs/server-api.md — ticket 154) ----------------------

/** `prompt.submitted` payload: the submission echo. Only [promptId] is consumed
 * (correlation with the local echo bubble); status/content/userMessageId/createdAt
 * stay ignored. */
@Serializable
data class PromptSubmittedPayload(val promptId: String = "")

/** `prompt.queued` payload: the prompt waits server-side while the agent is busy.
 * [queueLength] is the queue size at submission — the echo bubble's "Queued #N". */
@Serializable
data class PromptQueuedPayload(val promptId: String = "", val queueLength: Int = 0)

/** `prompt.started` payload (0.39.0): the prompt actually began executing. The
 * emitting agent rides the envelope peek like every transcript frame. */
@Serializable
data class PromptStartedPayload(val promptId: String = "")

/** `context.spliced` payload: full message objects, decoded with the history MessageDto
 * (its extra fields — toolCalls — are ignored; origin IS decoded, ticket 056). */
@Serializable
data class ContextSplicedPayload(
    val messages: List<MessageDto> = emptyList(),
)

@Serializable
data class ApprovalRequestedPayload(
    @SerialName("approval_id") val approvalId: String = "",
    @SerialName("tool_name") val toolName: String = "",
    val action: String = "",
    @SerialName("tool_input_display") val toolInputDisplay: ToolInputDisplayDto? = null,
    /** The ASKING agent (0.33.0 — ticket 139): "main", or the sub-agent's id for a
     * swarm member's request. This is the camelCase spelling the live WS frame's
     * payload stamps; the socket reads it. */
    val agentId: String = MAIN_AGENT_ID,
    /** The same asking agent, snake_case spelling (2.0.2 — ticket 156): the snapshot's
     * `pending_approvals` items and the `/approvals?status=pending` poll items stamp
     * `agent_id` only (the 2.0.2 WS frame carries both spellings, equal). The
     * snapshot mapping reads this one — decode per surface, never rename. */
    @SerialName("agent_id") val agentIdSnakeCase: String = MAIN_AGENT_ID,
)

/** `tool_input_display` of an approval request; only `command` (kind "command") is consumed.
 * On a `tool.call.started` the same shape carries the `agent_call` kind instead (an
 * `Agent` spawn, 0.31.0 — docs/server-api.md, Sub-agents): [agentName]/[prompt] feed
 * the spawn row (ticket 157), [background] marks a background spawn. */
@Serializable
data class ToolInputDisplayDto(
    val kind: String = "",
    val command: String? = null,
    @SerialName("agent_name") val agentName: String? = null,
    val prompt: String? = null,
    val background: Boolean = false,
)

@Serializable
data class ApprovalResolvedPayload(
    @SerialName("approval_id") val approvalId: String = "",
)

/**
 * `event.question.requested` payload (docs/server-api.md, Questions); the same
 * shape comes back as items of `GET .../questions?status=pending` and of the
 * snapshot's `pending_questions`. Since 0.38.0 `question_id` is a dedicated
 * `question_<uuid>` interaction id and [toolCallId] is distinct (they were equal
 * before): answer/dismiss route by `question_id`, the transcript row carries
 * `tool_call_id`. There is no v2 camelCase duplicate of this event family.
 */
@Serializable
data class QuestionRequestedPayload(
    @SerialName("question_id") val questionId: String = "",
    @SerialName("tool_call_id") val toolCallId: String = "",
    val questions: List<QuestionItemDto> = emptyList(),
    /** The ASKING agent (2.0.2 — ticket 156), optional in the schema: the pending
     * poll's items, the snapshot's `pending_questions` items and the live frame all
     * stamp snake_case `agent_id`; absent = the main agent. Unlike approvals there is
     * no camelCase twin to decode — the frame's trailing `agentId` is the envelope
     * mirror, not the asker. */
    @SerialName("agent_id") val agentId: String? = null,
)

@Serializable
data class QuestionItemDto(
    val id: String = "",
    val question: String = "",
    val header: String = "",
    val options: List<QuestionOptionDto> = emptyList(),
    @SerialName("allow_other") val allowOther: Boolean = false,
    // Present in both forms on every question surface (ticket 090); absent = false.
    @SerialName("multi_select") val multiSelect: Boolean = false,
)

@Serializable
data class QuestionOptionDto(
    val id: String = "",
    val label: String = "",
    // Omitted when the option has none.
    val description: String? = null,
)

/** Shared shape of `event.question.answered` and `event.question.dismissed`: only the id is consumed. */
@Serializable
data class QuestionResolvedPayload(
    @SerialName("question_id") val questionId: String = "",
)

/** `data` of `GET .../questions?status=pending`. */
@Serializable
data class QuestionListData(
    val items: List<QuestionRequestedPayload> = emptyList(),
)

// --- REST /tasks + task.* WS events (docs/server-api.md, Background lifecycle —
// ticket 092) ------------------------------------------------------------------
// Background tasks only: background sub-agents, background bash, question flows (the
// last filtered out at the mapping boundary). Terminal tasks stay listed.

/** `data` of `GET .../tasks?status=…`. */
@Serializable
data class TaskListData(
    val items: List<TaskDto> = emptyList(),
)

/** One REST task object; created_at == started_at. Times are ISO-8601 strings.
 * [runInBackground] rides every task since 0.39.0 (list and get, running and
 * terminal; `false` on a listed foreground sub-agent) — the jobs seed's foreground
 * filter (ticket 162). The shape also carries `subagent_type` and
 * `parent_tool_call_id` (since 0.36.1; `parent_tool_call_id` on bash-kind tasks
 * since 0.39.0), not decoded here. [command] rides on bash-kind tasks;
 * [outputPreview]/[outputBytes] only on a get-with-output read (ticket 096).
 * [model]/[thinkingEffort] ride on subagent-kind tasks (0.34.0 — the swarm
 * aggregate's shared-model line, ticket 135). */
@Serializable
data class TaskDto(
    val id: String = "",
    val kind: String = "",
    val description: String = "",
    val status: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("run_in_background") val runInBackground: Boolean = false,
    @SerialName("completed_at") val completedAt: String? = null,
    // Subagent-kind tasks carry it since 0.36.1 (docs/server-api.md); bash tasks never do.
    @SerialName("agent_id") val agentId: String? = null,
    val command: String? = null,
    @SerialName("output_preview") val outputPreview: String? = null,
    @SerialName("output_bytes") val outputBytes: Int? = null,
    val model: String? = null,
    @SerialName("thinking_effort") val thinkingEffort: String? = null,
)

/** `data` of `POST .../tasks/{id}:cancel`; a terminal task answers envelope 40904 with
 * `cancelled:false`. */
@Serializable
data class TaskCancelData(
    val cancelled: Boolean = false,
)

/** `data` of `POST .../tasks/{id}:detach` (0.39.0 — ticket 138): a converted
 * foreground task answers `detached:true, status:"running"`; an already-background
 * or terminal task answers envelope code 0 with `detached:false` and the current
 * status — success is `data.detached`, not `code == 0` (the dismissQuestion shape). */
@Serializable
data class TaskDetachData(
    val detached: Boolean = false,
    val status: String = "",
)

/** Shared payload shape of `task.started`/`task.terminated` (docs/server-api.md).
 * [agentId] is the envelope stamp naming the OWNING agent — the one whose task store
 * holds the task ("main", or the sub-agent that spawned the task); it differs from
 * `info.agentId`, which links a sub-agent-kind task to the spawned sub-agent's page. */
@Serializable
data class TaskEventPayload(
    val info: TaskInfoDto? = null,
    val agentId: String = MAIN_AGENT_ID,
)

/** The WS task `info` object: [status] is the CORE vocabulary (running | completed |
 * failed | timed_out | killed | lost), times are epoch MILLIS (not REST's ISO strings).
 * `detached`/`timeoutMs` exist on the wire but are not consumed. */
@Serializable
data class TaskInfoDto(
    val taskId: String = "",
    val description: String = "",
    val status: String = "",
    val startedAt: Long = 0,
    val endedAt: Long? = null,
    val stopReason: String? = null,
    val kind: String = "",
    val agentId: String? = null,
    val subagentType: String? = null,
)

/** `subagent.spawned` payload (docs/server-api.md, Sub-agents): the only frame carrying
 * a sub-agent's identity metadata. `parentToolCallUuid` exists upstream but is not
 * consumed. The frame itself is tagged `agentId:"main"`. [model]/[thinkingEffort]
 * (camelCase) ride the frame since 0.34.0. */
@Serializable
data class SubagentSpawnedPayload(
    val subagentId: String = "",
    val subagentName: String = "",
    val parentToolCallId: String = "",
    val description: String = "",
    val runInBackground: Boolean = false,
    val swarmIndex: Int? = null,
    val taskId: String? = null,
    val model: String? = null,
    val thinkingEffort: String? = null,
)

/** Shared shape of `subagent.started`/`completed`/`failed`/`suspended`/`cancelled`:
 * every variant carries [subagentId], plus only its own extra field (resultSummary,
 * error, reason). */
@Serializable
data class SubagentLifecyclePayload(
    val subagentId: String = "",
    val resultSummary: String? = null,
    val error: String? = null,
    val reason: String? = null,
)

// --- GET /api/v1/sessions/{id}/snapshot (docs/server-api.md, Session snapshot —
// ticket 085) ------------------------------------------------------------------
// The cold-start/resync bundle, verified live on 0.31.1 (2026-08-01). The roster is
// the newest surface of the API: every field stays optional/defensive.

@Serializable
data class SnapshotData(
    @SerialName("as_of_seq") val asOfSeq: Long = 0,
    val epoch: String = "",
    val session: SnapshotSessionDto? = null,
    val messages: MessageListData? = null,
    @SerialName("in_flight_turn") val inFlightTurn: InFlightTurnDto? = null,
    val subagents: List<SubagentRosterEntryDto> = emptyList(),
    // The snapshot items are the legacy event.approval.requested /
    // event.question.requested payload shapes verbatim (verified live), so the
    // existing payload DTOs decode them.
    @SerialName("pending_approvals") val pendingApprovals: List<ApprovalRequestedPayload> = emptyList(),
    @SerialName("pending_questions") val pendingQuestions: List<QuestionRequestedPayload> = emptyList(),
)

/** The snapshot's rich `session` object; only [busy] and the context fill of [usage]
 * are consumed (usage carries `context_tokens`/`context_limit` — not the /status
 * `max_context_tokens` naming). The usage has been real since 0.38.0 and seeds the
 * cold-start ring directly (ticket 161); the same server version made
 * `total_cost_usd`/`context_limit`/`turn_count` optional — the snapshot omits cost
 * and turn count (never decoded here), and an absent limit decodes to 0, the
 * "unknown" marker the mapper drops like an explicit 0 (100's rule). */
@Serializable
data class SnapshotSessionDto(
    val busy: Boolean = false,
    val usage: SnapshotUsageDto? = null,
)

@Serializable
data class SnapshotUsageDto(
    @SerialName("context_tokens") val contextTokens: Long = 0,
    @SerialName("context_limit") val contextLimit: Long = 0,
)

@Serializable
data class SubagentRosterEntryDto(
    val id: String = "",
    @SerialName("subagent_type") val subagentType: String = "",
    val description: String = "",
    @SerialName("subagent_phase") val subagentPhase: String = "",
    @SerialName("parent_tool_call_id") val parentToolCallId: String = "",
    @SerialName("run_in_background") val runInBackground: Boolean = false,
    // Optional (docs/server-api.md, Session snapshot roster): the member's position
    // inside an AgentSwarm call. The roster carries no task-id field — its `id` is
    // the subagent id (verified upstream, ticket 134).
    @SerialName("swarm_index") val swarmIndex: Int? = null,
    // Present once the run is terminal (the result summary tail).
    @SerialName("output_preview") val outputPreview: String? = null,
    // The run's bound model/effort (0.34.0, snake_case here — ticket 135).
    val model: String? = null,
    @SerialName("thinking_effort") val thinkingEffort: String? = null,
)

/** The active turn's live tail: the ONLY carrier of the turn's pre-watermark content
 * (the resume-cursor replay covers frames after the watermark only, and the turn's
 * messages commit to history at its end), so the cold start seeds the live tail from
 * it (ticket 144). `running_tools` entries also carry a `description` — ignored. */
@Serializable
data class InFlightTurnDto(
    @SerialName("turn_id") val turnId: Long = 0,
    @SerialName("assistant_text") val assistantText: String = "",
    @SerialName("thinking_text") val thinkingText: String = "",
    @SerialName("running_tools") val runningTools: List<RunningToolDto> = emptyList(),
    @SerialName("current_prompt_id") val currentPromptId: String = "",
)

@Serializable
data class RunningToolDto(
    @SerialName("tool_call_id") val toolCallId: String = "",
    val name: String = "",
    val args: JsonObject? = null,
    // Same shape as tool.call.started's display (the 0.31.1 verbatim capture,
    // docs/server-api.md): it feeds the seeded row's preview.
    val display: ToolInputDisplayDto? = null,
)

// --- GET /api/v1/sessions/{id}/transcript (docs/server-api.md, Transcript) -----------
// Turn-granular per-agent history (046), pinned from live 0.29.2 captures. The
// cold-rebuild path omits the warm-only fields (startedAt/endedAt, usage/timing,
// inputText/display/progress), so everything outside the core key set is optional.

@Serializable
data class TranscriptData(
    @SerialName("agent_id") val agentId: String = "",
    val items: List<TranscriptItemDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** Flat item shape: [kind] is `turn` or `marker`, each filling only its own fields.
 * Markers (`swarm.enter`/`exit`, `undo`) are tolerated by the parse, never rendered. */
@Serializable
data class TranscriptItemDto(
    val kind: String = "",
    // turn fields
    val turnId: String = "",
    val ordinal: Int = 0,
    val state: String = "",
    val origin: TranscriptOriginDto? = null,
    // Only on user-origin turns: the prompting text (a sub-agent's internal
    // system_trigger turns never carry it).
    val prompt: String? = null,
    val steps: List<TranscriptStepDto> = emptyList(),
    // marker fields (not consumed)
    val markerId: String? = null,
    val marker: String? = null,
)

@Serializable
data class TranscriptOriginDto(
    val kind: String = "",
    // Only on kind "task" (verified on 0.34.0 — docs/server-api.md, Transcript):
    // the flattened task id; `status`/`notificationId` live inside [payload].
    val taskId: String? = null,
    val payload: JsonObject? = null,
)

@Serializable
data class TranscriptStepDto(
    val kind: String = "",
    val stepId: String = "",
    val turnId: String = "",
    val ordinal: Int = 0,
    val state: String = "",
    val frames: List<TranscriptFrameDto> = emptyList(),
)

/** Flat frame shape: [kind] `thinking`/`text`/`tool`, each filling only its own fields. */
@Serializable
data class TranscriptFrameDto(
    val kind: String = "",
    val frameId: String = "",
    // thinking and text frames
    val text: String? = null,
    // text frames only ("assistant" observed; "user" would be a nudge)
    val role: String? = null,
    // tool frames
    val toolCallId: String? = null,
    val name: String? = null,
    val state: String? = null,
    val input: JsonObject? = null,
    // Warm path only: the compact input string, the server display rendering, and the
    // volatile progress tail ({kind, text}) of a finished/running call.
    val inputText: String? = null,
    val display: JsonObject? = null,
    val progress: JsonObject? = null,
    // String for shell-style tools, a JSON value for structured ones (like tool_result).
    val output: JsonElement? = null,
)


// --- compaction.* (docs/server-api.md, Compact — ticket 056) -------------------------

/** `compaction.started` payload: [instruction] is the optional summarizer hint sent
 * with the POST (consumed only for completeness — the app sends no instruction). */
@Serializable
data class CompactionStartedPayload(
    val trigger: String = "",
    val instruction: String? = null,
)

@Serializable
data class CompactionCompletedPayload(
    val result: CompactionResultDto? = null,
)

@Serializable
data class CompactionResultDto(
    val summary: String = "",
    val compactedCount: Int = 0,
    val tokensBefore: Long = 0,
    val tokensAfter: Long = 0,
    val keptUserMessageCount: Int = 0,
)

/** `compaction.failed`/`cancelled`/`blocked` payload: the schemas exist only in the
 * protocol bundle and were never observed on this server — every field stays optional
 * so any shape decodes, and a missing payload still ends the compacting state. */
@Serializable
data class CompactionFailedPayload(
    val reason: String? = null,
    val message: String? = null,
    val error: String? = null,
) {
    /** Best-effort human-readable cause, whichever field the variant carries. */
    val cause: String? get() = message ?: error ?: reason
}
