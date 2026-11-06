package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    // Ticket 167: alias → model metadata; only the upstream `model` id is consumed
    // (client-config rules match that, not the alias).
    val models: Map<String, ConfigModelDto> = emptyMap(),
)

/** One entry of the config's `models` map (camelCase upstream, unlike the catalog's
 * `GET /models` items): just the upstream id the switch rules match. */
@Serializable
data class ConfigModelDto(
    val model: String? = null,
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
    // Ticket 166 (2.3.0): optional retirement/replacement guidance. No live catalog
    // item publishes it yet (verified 2026-10-28).
    val upgrade: ModelUpgradeDto? = null,
)

/** The model catalog item's optional `upgrade` object (2.3.0, ticket 166):
 * `{model, migration_markdown, retirement_at?}`. */
@Serializable
data class ModelUpgradeDto(
    val model: String = "",
    @SerialName("migration_markdown") val migrationMarkdown: String = "",
    @SerialName("retirement_at") val retirementAt: String? = null,
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

/** Envelope of one session-socket frame (docs/server-api.md, WebSocket); [payload] is
 * decoded per [type] by SessionSocket. The flat state frames (`agent`, `usage`,
 * `interaction`, `task`…) carry their fields beside `type` and no `payload`. */
@Serializable
data class WsFrame(
    val type: String = "",
    // The frame's session; absent on control frames (ack, server_hello, ping). Global
    // events reach every connection, so SessionSocket drops any frame whose sessionId
    // is not the subscribed one.
    @SerialName("session_id") val sessionId: String? = null,
    // Control-frame request id: an `ack` echoes the subscribe_v2 frame's `id`.
    val id: String? = null,
    val code: Int = 0,
    val msg: String? = null,
    val payload: JsonObject? = null,
)

/**
 * The flat `agent` state frame (docs/server-api.md, WebSocket → Session frames —
 * server 2.5.0, ticket 177): the agent's live state, sent before the subscribe ack and
 * again on every change, replacing the client copy wholesale. [status] is
 * `idle`/`running` (running while a turn or background work keeps the agent busy);
 * [model]/[thinking] are omitted on a model-less session; [permissionMode] is the
 * effective `manual`/`yolo`/`auto`. Of the mode booleans only [swarmMode] is consumed
 * (ticket 174 — the composer's swarm chip); `plan_mode`/`tower_mode` are ignored.
 */
@Serializable
data class AgentStateFrame(
    @SerialName("agent_id") val agentId: String = MAIN_AGENT_ID,
    val status: String? = null,
    val model: String? = null,
    val thinking: String? = null,
    @SerialName("permission_mode") val permissionMode: String? = null,
    @SerialName("swarm_mode") val swarmMode: Boolean? = null,
)

/**
 * `payload` of the global `event.session.work_changed` frame as the chat reads it
 * (docs/server-api.md, Global events — ticket 181): only [busy]. Absent = unreadable,
 * never "idle" — a drifted frame must not end a running compaction.
 */
@Serializable
data class ChatWorkChangedPayload(
    val busy: Boolean? = null,
)

/**
 * The flat `usage` state frame (docs/server-api.md, WebSocket → Session frames —
 * ticket 177): the agent's context fill, coalesced server-side to a few frames per
 * second. [maxContextTokens] is optional — absent or `0` is the engine's "unknown"
 * limit, never a real one.
 */
@Serializable
data class UsageFrame(
    @SerialName("agent_id") val agentId: String = MAIN_AGENT_ID,
    @SerialName("context_tokens") val contextTokens: Long? = null,
    @SerialName("max_context_tokens") val maxContextTokens: Long? = null,
)

/**
 * The flat `todo` state frame (docs/server-api.md, Todos — server 2.5.0, ticket 178):
 * the agent's FULL todo list, sent when the agent joins the subscription (before the
 * subscribe ack) and on every change, undo and cold restore included. It replaces the
 * client's list wholesale; `[]` clears it. Mapped by [toTodos].
 */
@Serializable
data class TodoFrame(
    @SerialName("agent_id") val agentId: String = MAIN_AGENT_ID,
    val items: List<TodoItemDto> = emptyList(),
)

/** One `todo` frame item: `{title, status:"pending"|"in_progress"|"done"}`. */
@Serializable
data class TodoItemDto(
    val title: String? = null,
    val status: String? = null,
)

/**
 * One task's full state (docs/server-api.md, Sub-agents → Task frames and controls —
 * server 2.5.0, ticket 179): the body of a flat `task` frame (its fields sit beside
 * `type`), a `task.list` entry, and a task control reply's `task`. Mapped by
 * [toTaskState]. The timestamps are ISO-8601 strings on the wire; [JsonPrimitive]
 * keeps an epoch-millis number decodable too.
 */
@Serializable
data class TaskStateDto(
    @SerialName("task_id") val taskId: String = "",
    val kind: String = "",
    val status: String = "",
    val outcome: String? = null,
    val phase: String? = null,
    val detached: Boolean = false,
    val description: String? = null,
    @SerialName("child_agent_id") val childAgentId: String? = null,
    @SerialName("parent_tool_call_id") val parentToolCallId: String? = null,
    @SerialName("swarm_index") val swarmIndex: Int? = null,
    val command: String? = null,
    @SerialName("started_at") val startedAt: JsonPrimitive? = null,
    @SerialName("ended_at") val endedAt: JsonPrimitive? = null,
    @SerialName("result_summary") val resultSummary: String? = null,
    val error: String? = null,
    @SerialName("state_reason") val stateReason: String? = null,
    val model: String? = null,
    val thinking: String? = null,
)

/** The flat `task` frame: the owning agent ([agentId] — `main`, or the child agent that
 * started the task) plus the task's full state, decoded from the same frame text. */
@Serializable
data class TaskFrame(
    @SerialName("agent_id") val agentId: String = MAIN_AGENT_ID,
)

/** The flat `task.list` frame: every RUNNING task of [agentId], sent when a connection
 * starts receiving the agent (before the subscribe ack, again on every reconnect). */
@Serializable
data class TaskListFrame(
    @SerialName("agent_id") val agentId: String = MAIN_AGENT_ID,
    val tasks: List<TaskStateDto> = emptyList(),
)

/** The flat `task.output` frame (ticket 180): sent only to a connection that sent
 * `task.watch` — [text] starts at UTF-8 byte [offset] of the task's output. */
@Serializable
data class TaskOutputFrame(
    @SerialName("agent_id") val agentId: String = MAIN_AGENT_ID,
    @SerialName("task_id") val taskId: String,
    val offset: Long,
    val text: String = "",
)

/** The reply to a flat task control (`task.stop`/`task.background`, ticket 179;
 * `task.watch`/`task.unwatch`, ticket 180):
 * `{type:"response",request_id,code,msg?,task?}` — matched to its request by
 * [requestId]; [task] is the task's state after the control (also sent on some
 * errors, e.g. `40933`). */
@Serializable
data class TaskControlResponse(
    @SerialName("request_id") val requestId: String? = null,
    val code: Int = 0,
    val msg: String? = null,
    val task: TaskStateDto? = null,
)

/**
 * The flat `interaction` frame (docs/server-api.md, Interactions → The `interaction`
 * frame — tickets 171/172): one approval or question of any agent of the session, sent on
 * every state change and re-sent for every still-pending one after each accepted
 * `subscribe_v2`. No `payload` and no `seq`: the fields sit beside `type`. [status] is
 * `pending`/`ended`; [outcome] is set on `ended` only. [request] stays raw — its shape
 * depends on [kind] ([ApprovalInteractionRequestDto] for `approval`,
 * [QuestionRequestDto] for `question` — ticket 172).
 */
@Serializable
data class InteractionFrame(
    @SerialName("agent_id") val agentId: String = MAIN_AGENT_ID,
    @SerialName("interaction_id") val interactionId: String = "",
    val status: String = "",
    val outcome: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val kind: String = "",
    val request: JsonObject? = null,
)

/** The `request` of an approval [InteractionFrame] — snake_case, unlike the transcript
 * entity's camelCase [TranscriptApprovalRequestDto]. `tool_input_display` is optional
 * (the card then falls back to [action]); `expires_at` is not consumed. */
@Serializable
data class ApprovalInteractionRequestDto(
    @SerialName("tool_name") val toolName: String = "",
    val action: String = "",
    @SerialName("tool_input_display") val toolInputDisplay: ToolInputDisplayDto? = null,
)

/**
 * `payload` of the `subscribe_v2` `ack` (docs/server-api.md, Control frames): an
 * unknown session answers `code:0` with its id in [notFound] — the socket surfaces it
 * as a load failure instead of silence (ticket 175).
 */
@Serializable
data class AckPayload(
    val accepted: List<String> = emptyList(),
    @SerialName("not_found") val notFound: List<String> = emptyList(),
)

/**
 * `payload` of the `server_hello` frame (docs/server-api.md, WebSocket); only
 * `heartbeat_ms` is consumed — the stall watchdog's idle threshold is 3× it
 * (ticket 152).
 */
@Serializable
data class ServerHelloPayload(
    @SerialName("heartbeat_ms") val heartbeatMs: Long? = null,
)

/** `tool_input_display` of an approval request: `command` (kind "command") is the
 * card's summary; kind `plan_review` (`ExitPlanMode`, 2.4.0 — docs/server-api.md,
 * Plan review approvals) carries the plan Markdown in [plan] and the plan file in
 * [path] (decoded, not consumed — ticket 173). A tool frame's `display` has the same
 * shape, and carries the `agent_call` kind (an `Agent` spawn, 0.31.0 —
 * docs/server-api.md, Sub-agents): [agentName]/[prompt] feed the spawn row (ticket
 * 157), [background] marks a background spawn. */
@Serializable
data class ToolInputDisplayDto(
    val kind: String = "",
    val command: String? = null,
    @SerialName("agent_name") val agentName: String? = null,
    val prompt: String? = null,
    val background: Boolean = false,
    val plan: String? = null,
    val path: String? = null,
)

/**
 * The `request` of a question interaction (docs/server-api.md, Interactions): the
 * flat `interaction` frame carries `{questions[]}` only (ticket 172); the transcript
 * entity's `request` (`GET .../transcript` and `transcript.reset.snapshot.
 * interactions`) adds `question_id`, `tool_call_id`, `turn_id` and `created_at`. The
 * mapper always keys the card on the interaction id — the one the response route
 * takes — and prefers the frame's/entity's own `tool_call_id`. The asking agent is
 * the frame's `agent_id`; the entity's request carries none (main, until the post-ack
 * re-send stamps it).
 */
@Serializable
data class QuestionRequestDto(
    @SerialName("tool_call_id") val toolCallId: String = "",
    val questions: List<QuestionItemDto> = emptyList(),
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

// --- GET /api/v1/sessions/{id}/transcript (docs/server-api.md, Transcript) -----------
// Turn-granular per-agent history (046), pinned from live 0.29.2 captures. The
// cold-rebuild path omits the warm-only fields (startedAt/endedAt, usage/timing,
// inputText/display/progress), so everything outside the core key set is optional.

@Serializable
data class TranscriptData(
    @SerialName("agent_id") val agentId: String = "",
    val items: List<TranscriptItemDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    // The session's unpaginated global state, shipped with every page (2.5.0 — the
    // cold-start read model replacing the removed snapshot, ticket 176). [seq] is the
    // agent's op-batch watermark for `transcript_since` (live sessions only — absent
    // on a cold session). [interactions] is the full entity list, ended ones
    // included; [pendingInteractions] the ids still waiting.
    val interactions: List<InteractionEntityDto> = emptyList(),
    @SerialName("pending_interactions") val pendingInteractions: List<String> = emptyList(),
    val prompts: List<PromptEntityDto> = emptyList(),
    val agents: List<TranscriptAgentDto> = emptyList(),
    val seq: Long? = null,
)

/** One `agents` roster entry of the transcript (2.5.0): every agent of the session,
 * children included. [type] is `main` or `sub`. */
@Serializable
data class TranscriptAgentDto(
    val agentId: String = "",
    val type: String = "",
    val parentAgentId: String? = null,
)

/** The `request` of a pending APPROVAL interaction on the REST transcript — camelCase,
 * unlike the WS `interaction` frame's snake_case request (re-verified live on 2.5.1,
 * docs/server-api.md, Pending state on REST). [display] is the same discriminated
 * object tool frames carry; [agentId] is the asking agent. */
@Serializable
data class TranscriptApprovalRequestDto(
    val toolName: String = "",
    val action: String = "",
    val display: ToolInputDisplayDto? = null,
    val agentId: String? = null,
)

// --- GET /api/v1/sessions/{id}/prompts (docs/server-api.md, Prompts → Queue
// snapshot — new in 2.5.0) ------------------------------------------------------

/** `data` of the main agent's prompt queue: the running prompt and the queued ones,
 * in queue order. */
@Serializable
data class PromptQueueData(
    val active: QueuePromptDto? = null,
    val queued: List<QueuePromptDto> = emptyList(),
)

/** One prompt of the queue view (snake_case, unlike the camelCase transcript prompt
 * entity). [content] is the submitted content array — the history part shapes. */
@Serializable
data class QueuePromptDto(
    @SerialName("prompt_id") val promptId: String = "",
    @SerialName("user_message_id") val userMessageId: String? = null,
    val status: String = "",
    val content: List<ContentPartDto> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
)

// --- GET /api/v1/sessions/{id}/transcript/ops (docs/server-api.md, Transcript
// catch-up — 2.5.0) -----------------------------------------------------------

/** `data` of the transcript catch-up. Only the journal coverage is consumed: the
 * batches themselves are replayed over the WebSocket by `transcript_since`. */
@Serializable
data class TranscriptCatchUpData(
    @SerialName("agent_id") val agentId: String = "",
    @SerialName("latest_seq") val latestSeq: Long = 0,
    val complete: Boolean = false,
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
