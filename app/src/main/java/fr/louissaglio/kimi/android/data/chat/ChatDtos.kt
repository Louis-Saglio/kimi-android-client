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

/** `tool_input_display` of an approval request; only `command` (kind "command") is consumed.
 * A tool frame's `display` has the same shape, and carries the `agent_call` kind (an
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

/**
 * `event.question.requested` payload (docs/server-api.md, Questions); the same
 * shape comes back as items of `GET .../questions?status=pending` and — since
 * 2.5.0 — as the `request` of a pending question interaction on
 * `GET .../transcript` (ticket 176's cold seed). Since 0.38.0 `question_id` is a dedicated
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
     * poll's items and the live frame stamp snake_case `agent_id` (the transcript
     * interaction's request carries none); absent = the main agent. Unlike approvals there is
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
