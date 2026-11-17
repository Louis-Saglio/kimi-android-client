package fr.louissaglio.kimi.android.data.chat

import android.util.Log
import fr.louissaglio.kimi.android.data.parseOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Wire DTO → domain mapping for history messages and socket payloads (the single
 * mapping layer shared by ChatApi and SessionSocket). Also owns the display
 * renderings derived from payloads (previews, summaries, pretty-printed details).
 */
@Singleton
class ChatMessageMapper @Inject constructor(private val json: Json) {

    // Pretty-printing for the expanded tool row (ticket 023): strings render as-is,
    // JSON values (tool inputs, structured outputs) render indented. Since ticket 118
    // the expansion renders object inputs as a key/value table (ToolDetailFields) —
    // this pretty string is the fallback for calls without object args.
    private val prettyJson = Json(json) { prettyPrint = true }

    /** Renders a wire JSON value for the expanded tool row: a plain string comes out as
     * its raw text (shell output stays untouched), anything else as pretty-printed JSON. */
    fun formatDetail(element: JsonElement?): String = when {
        element == null || element is JsonNull -> ""
        element is JsonPrimitive && element.isString -> element.content
        else -> prettyJson.encodeToString(JsonElement.serializer(), element)
    }

    /**
     * A tool result's `output` → the row's text plus its media (ticket 184 — server
     * 2.5.1, docs/server-api.md, Messages → History and Transcript): the ONE parse every
     * tool-result carrier shares (the history `tool_result` part, the REST transcript
     * tool frame, the live `transcript.ops` fold). A content-part array — non-empty,
     * every element an object with a string `type`, at least one of them
     * `text`/`image`/`video` — yields its `text` parts joined by "\n" and one
     * [ToolMedia] per `image`/`video` part whose source is `session_media`; any other
     * part type is ignored. When the array carried media, a text part that is only an
     * `<image …>`/`</image>` (or video) wrapper tag is dropped: it just frames the
     * image. Anything else — a string, an object, any other array — keeps
     * [formatDetail]'s rendering, with no media.
     */
    fun toolOutput(element: JsonElement?): ToolOutput {
        val parts = (element as? JsonArray)
            ?.takeIf { array -> array.isNotEmpty() && array.all { it.partType() != null } }
            ?.map { it as JsonObject }
            ?.takeIf { array -> array.any { it.partType() in TOOL_OUTPUT_PART_TYPES } }
            ?: return ToolOutput(formatDetail(element))
        val media = parts.mapNotNull { part ->
            val kind = when (part.partType()) {
                "image" -> ToolMedia.Kind.Image
                "video" -> ToolMedia.Kind.Video
                else -> return@mapNotNull null
            }
            val source = part["source"] as? JsonObject ?: return@mapNotNull null
            if (source.string("kind") != "session_media") return@mapNotNull null
            val fileId = source.string("file_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ToolMedia(fileId, kind, name = part.string("name")?.takeIf { it.isNotBlank() })
        }
        val text = parts
            .filter { it.partType() == "text" }
            .mapNotNull { it.string("text") }
            .filterNot { media.isNotEmpty() && MEDIA_WRAPPER_TAG_RE.matches(it.trim()) }
            .joinToString("\n")
        return ToolOutput(text, media)
    }

    private fun JsonElement.partType(): String? =
        ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The compact argument preview for the approval card (the display command for
     * shell calls, else the action). */
    fun summary(display: ToolInputDisplayDto?, action: String): String =
        display?.command?.takeIf { it.isNotBlank() } ?: action

    /** The plan Markdown of a `plan_review` display (ticket 173 — `ExitPlanMode`,
     * docs/server-api.md, Plan review approvals): the approval card renders it above
     * Allow/Deny. Null for every other kind, a missing display, or a blank plan — those
     * cards keep the [summary]-only treatment. */
    fun planReview(display: ToolInputDisplayDto?): String? =
        display?.takeIf { it.kind == PLAN_REVIEW_KIND }?.plan?.takeIf { it.isNotBlank() }

    /** The row preview for a transcript tool frame (a live op, or a REST transcript
     * page — the cold start's running turn, the sub-agent page): a Bash call's non-blank `description` argument wins (ticket 095), then the
     * server's display command when it sent one, else derived from the arguments
     * (ToolPreview). */
    fun preview(name: String, args: JsonObject?, display: ToolInputDisplayDto?): String {
        if (name == "Bash") {
            val description = (args?.get("description") as? JsonPrimitive)?.contentOrNull
            if (!description.isNullOrBlank()) return ToolPreview.of(name, args)
        }
        return display?.command?.takeIf { it.isNotBlank() } ?: ToolPreview.of(name, args)
    }

    /** The `agent_call` display's typed model (ticket 157 — 0.31.0, docs/server-api.md,
     * Sub-agents): the spawn row's title/preview/badge source. Null for the `command`
     * kind and any unknown kind, and for an `agent_call` without a usable agent name —
     * those rows keep the generic treatment. */
    fun agentCall(display: ToolInputDisplayDto?): AgentCallDisplay? {
        if (display?.kind != "agent_call") return null
        val name = display.agentName?.takeIf { it.isNotBlank() } ?: return null
        return AgentCallDisplay(name, display.prompt.orEmpty(), display.background)
    }

    /** A question interaction's request → the wizard's domain question. [questionId] is
     * the interaction id (the one the response route takes); [toolCallId] links the
     * card to its `AskUserQuestion` row; [agentId] is the asking agent (ticket 156). */
    private fun toPendingQuestion(
        request: QuestionRequestDto,
        questionId: String,
        toolCallId: String?,
        agentId: String?,
    ): PendingQuestion = PendingQuestion(
        questionId = questionId,
        toolCallId = toolCallId?.takeIf { it.isNotBlank() } ?: request.toolCallId,
        items = request.questions.map { item ->
            PendingQuestion.Item(
                id = item.id,
                header = item.header,
                text = item.question,
                options = item.options.map { PendingQuestion.Option(it.id, it.label, it.description) },
                allowOther = item.allowOther,
                multiSelect = item.multiSelect,
            )
        },
        agentId = agentId?.takeIf { it.isNotBlank() } ?: MAIN_AGENT_ID,
    )

    fun toDomain(dto: MessageDto): ChatMessage {
        val domainRole = when (dto.role) {
            "user" -> ChatMessage.Role.User
            "assistant" -> ChatMessage.Role.Assistant
            "tool" -> ChatMessage.Role.Tool
            else -> ChatMessage.Role.Other
        }
        // The prompt's file attachments ride the origin's structured array (ticket 160 —
        // 0.39.1, docs/server-api.md, Prompts): they become the bubble's chips. The
        // server still rewrites each file part into its "Attached file" notice text
        // part, fully determined by the same entry — that sentence drops out of the
        // text, recognized by exact reconstruction, never pattern-matched.
        val attachments = dto.originAttachments.orEmpty()
        val notices = attachments.mapTo(HashSet()) { it.noticeText() }
        return ChatMessage(
            id = dto.id.orEmpty(),
            role = domainRole,
            parts = dto.content.mapNotNull { toDomain(it, stripEnvelopes = domainRole == ChatMessage.Role.User, notices) } +
                attachments.map { it.toDomain() },
            // A malformed timestamp must not fail the whole message: drop it, keep the rest.
            createdAt = dto.createdAt?.let { parseOrNull { Instant.parse(it) } },
            originKind = dto.originKind,
        )
    }

    private fun toDomain(dto: ContentPartDto, stripEnvelopes: Boolean, attachmentNotices: Set<String>): ChatMessage.Part? = when (dto.type) {
        // Internal envelopes are stripped here — the single domain boundary every
        // ingestion path (history, context.spliced echoes) flows through. A part
        // left blank is dropped so ChatTimeline never sees an empty bubble's text.
        "text" -> dto.text
            ?.let { if (stripEnvelopes) InternalEnvelopes.strip(it) else it }
            ?.takeIf { it.isNotBlank() && it !in attachmentNotices }
            ?.let { ChatMessage.Part.Text(it) }
        "thinking" -> dto.thinking?.let { ChatMessage.Part.Thinking(it) }
        // 0.42.0: the media part carries the original file's `name` (ticket 160).
        "image" -> dto.source?.let { toDomain(it)?.copy(name = dto.name) }
        "tool_use" -> dto.toolCallId?.let {
            ChatMessage.Part.ToolUse(
                it,
                dto.toolName.orEmpty(),
                ToolPreview.of(dto.toolName, dto.input),
                formatDetail(dto.input),
                args = dto.input,
            )
        }
        "tool_result" -> dto.toolCallId?.let {
            // 2.5.1 (ticket 184): a media result's `output` is a content-part array.
            val output = toolOutput(dto.output)
            ChatMessage.Part.ToolResult(it, dto.isError ?: false, output.text, output.media)
        }
        else -> null
    }

    private fun toDomain(dto: MediaSourceDto): ChatMessage.Part.Image? = when (dto.kind) {
        "base64" -> dto.data?.let { ChatMessage.Part.Image(base64Data = it, fileId = null) }
        "url" -> dto.url?.let { parseDataUrl(it) }
        "file" -> dto.fileId?.let { ChatMessage.Part.Image(base64Data = null, fileId = it) }
        // 0.37.2 (ticket 104): history and the prompt ACK echo project uploaded images
        // as session-owned media; the id is session-scoped, hence the explicit marker
        // (docs/server-api.md, Files → Session media).
        "session_media" -> dto.fileId?.let {
            ChatMessage.Part.Image(base64Data = null, fileId = it, isSessionMedia = true)
        }
        else -> null
    }

    /** History inlines images as `data:<mime>;base64,…` urls (docs/server-api.md). */
    private fun parseDataUrl(url: String): ChatMessage.Part.Image? =
        DATA_URL_RE.matchEntire(url)?.let {
            ChatMessage.Part.Image(base64Data = it.groupValues[2], fileId = null)
        }

    /** An `origin.attachments` entry → the bubble's chip (ticket 160). [OriginAttachmentDto.path]
     * is server-local and never displayed; the file id hides in the materialized
     * `<fileId>-<name>` basename prefix for uploads — a server-local path has none,
     * leaving the chip inert. */
    private fun OriginAttachmentDto.toDomain(): ChatMessage.Part.Attachment =
        ChatMessage.Part.Attachment(
            name = name,
            mimeType = mediaType,
            sizeBytes = size,
            fileId = FILE_STORE_ID_AT_START_RE.find(path.split('/', '\\').last())?.value,
        )

    /** The notice sentence the server rewrote this file part into (docs/server-api.md,
     * Prompts) — a pure function of the same four fields the origin carries, so a text
     * part equal to it is the rewritten part, not user text (ticket 160). A wording
     * drift fails visible (the raw sentence renders next to the chip), never a wrong
     * chip. */
    private fun OriginAttachmentDto.noticeText(): String =
        "Attached file \"$name\" ($mediaType, $size bytes): $path — open it with the Read tool"

    /** Transcript page (docs/server-api.md, Transcript): marker items are dropped here,
     * turns keep their ascending wire order. */
    fun toDomain(dto: TranscriptData): TranscriptPage {
        val pending = dto.pendingInteractions.toSet()
        // The pending set is `pending_interactions` (the ids still waiting); the entity
        // list also holds ended interactions. An entity whose own state says pending
        // counts too — the two views are the same server state.
        return TranscriptPage(
            agentId = dto.agentId,
            turns = dto.items.filter { it.kind == "turn" }.map(::toDomain),
            hasMore = dto.hasMore,
            seq = dto.seq,
            agents = dto.agents.filter { it.agentId.isNotBlank() }.map {
                TranscriptAgent(it.agentId, isMain = it.type == "main", parentAgentId = it.parentAgentId)
            },
            prompts = dto.prompts.filter { it.promptId.isNotBlank() }.map {
                TranscriptPrompt(it.promptId, promptStatus(it.status))
            },
            pendingApprovals = pendingApprovals(dto.interactions, pending),
            pendingQuestions = pendingQuestions(dto.interactions, pending),
        )
    }

    /** A prompt entity's status (docs/server-api.md, Transcript protocol); null when the
     * wire value is one this app predates. */
    fun promptStatus(status: String): PromptStatus? = when (status) {
        "running" -> PromptStatus.Running
        "queued" -> PromptStatus.Queued
        "completed" -> PromptStatus.Completed
        "aborted" -> PromptStatus.Aborted
        "blocked" -> PromptStatus.Blocked
        "failed" -> PromptStatus.Failed
        else -> null
    }

    /** The pending approvals of an interaction entity list — the REST transcript's
     * (pre-filtered against `pending_interactions`) and `transcript.reset.snapshot.
     * interactions`, which also holds ended entities (docs/server-api.md, Interactions):
     * approval-kind entities whose state is `pending` or whose id is in [pendingIds],
     * deduped by interaction id, in entity order. */
    fun pendingApprovals(
        entities: List<InteractionEntityDto>,
        pendingIds: Set<String> = emptySet(),
    ): List<PendingApprovalRequest> =
        entities
            .filter { it.interactionKind == "approval" && (it.state == "pending" || it.interactionId in pendingIds) }
            .filter { it.interactionId.isNotBlank() }
            .distinctBy { it.interactionId }
            .mapNotNull(::toPendingApproval)

    /** The pending questions of an interaction entity list — [pendingApprovals]' filter
     * for question-kind entities (ticket 172): the REST transcript's cold seed and the
     * `transcript.reset` snapshots the subscribe ack's question reset carries. */
    fun pendingQuestions(
        entities: List<InteractionEntityDto>,
        pendingIds: Set<String> = emptySet(),
    ): List<PendingQuestion> =
        entities
            .filter { it.interactionKind == "question" && (it.state == "pending" || it.interactionId in pendingIds) }
            .filter { it.interactionId.isNotBlank() }
            .distinctBy { it.interactionId }
            .mapNotNull(::toPendingQuestion)

    /** The flat `interaction` frame → the card events of its [InteractionFrame.kind]:
     * [toApprovalEvent] or [toQuestionEvent]; any other kind maps to nothing. */
    fun toInteractionEvent(frame: InteractionFrame): SessionEvent? = when (frame.kind) {
        "approval" -> toApprovalEvent(frame)
        "question" -> toQuestionEvent(frame)
        else -> null
    }

    /**
     * The flat `interaction` frame → the approval card's events (ticket 171): `pending`
     * → [SessionEvent.ApprovalRequested] (card id = the interaction id, the one the
     * response route takes; [summary] over the display command, else the action; the
     * `plan_review` display's plan — ticket 173; the asking agent from the frame), `ended` → [SessionEvent.ApprovalResolved] whatever
     * the outcome (`approved`/`rejected`/`cancelled`). A question frame maps to nothing
     * here ([toQuestionEvent]'s), like an unknown status or an undecodable request.
     */
    fun toApprovalEvent(frame: InteractionFrame): SessionEvent? {
        if (frame.kind != "approval" || frame.interactionId.isBlank()) return null
        return when (frame.status) {
            "pending" -> {
                val request = frame.request?.let {
                    parseOrNull(onFailure = { e -> Log.w(TAG, "Dropping undecodable approval '${frame.interactionId}'", e) }) {
                        json.decodeFromJsonElement<ApprovalInteractionRequestDto>(it)
                    }
                } ?: return null
                SessionEvent.ApprovalRequested(
                    approvalId = frame.interactionId,
                    toolName = request.toolName,
                    summary = summary(request.toolInputDisplay, request.action),
                    agentId = frame.agentId.takeIf { it.isNotBlank() } ?: MAIN_AGENT_ID,
                    plan = planReview(request.toolInputDisplay),
                )
            }
            "ended" -> SessionEvent.ApprovalResolved(frame.interactionId)
            else -> null
        }
    }

    /**
     * The flat `interaction` frame → the question wizard's events (ticket 172):
     * `pending` → [SessionEvent.QuestionRequested] (card id = the interaction id,
     * `tool_call_id` links the `AskUserQuestion` row, `agent_id` is the asker, the
     * `request.questions[]` items decode through [QuestionItemDto]), `ended` →
     * [SessionEvent.QuestionResolved] whatever the outcome (`answered`/`dismissed`/
     * `cancelled` — an abort ends a pending question `dismissed`). An approval frame, an
     * unknown status or an undecodable request maps to nothing.
     */
    fun toQuestionEvent(frame: InteractionFrame): SessionEvent? {
        if (frame.kind != "question" || frame.interactionId.isBlank()) return null
        return when (frame.status) {
            "pending" -> {
                val request = frame.request?.let {
                    parseOrNull(onFailure = { e -> Log.w(TAG, "Dropping undecodable question '${frame.interactionId}'", e) }) {
                        json.decodeFromJsonElement<QuestionRequestDto>(it)
                    }
                } ?: return null
                SessionEvent.QuestionRequested(
                    toPendingQuestion(request, frame.interactionId, frame.toolCallId, frame.agentId),
                )
            }
            "ended" -> SessionEvent.QuestionResolved(frame.interactionId)
            else -> null
        }
    }

    /** A pending approval entity → the card's domain request: the transcript request is
     * camelCase (docs/server-api.md, Pending state on REST); an undecodable one drops
     * the entity with a Log.w, never the page. */
    private fun toPendingApproval(entity: InteractionEntityDto): PendingApprovalRequest? {
        val request = entity.request?.let {
            parseOrNull(onFailure = { e -> Log.w(TAG, "Dropping undecodable approval '${entity.interactionId}'", e) }) {
                json.decodeFromJsonElement<TranscriptApprovalRequestDto>(it)
            }
        } ?: return null
        return PendingApprovalRequest(
            approvalId = entity.interactionId,
            toolName = request.toolName,
            summary = summary(request.display, request.action),
            agentId = request.agentId?.takeIf { it.isNotBlank() } ?: MAIN_AGENT_ID,
            plan = planReview(request.display),
        )
    }

    /** A pending question entity → the wizard's domain question: the request keeps the
     * snake_case question shape. The card id is the INTERACTION id (the one the
     * response route takes); the row's tool call id comes from the entity, else the
     * request; the entity names no asker (main until a frame says otherwise). */
    private fun toPendingQuestion(entity: InteractionEntityDto): PendingQuestion? {
        val request = entity.request?.let {
            parseOrNull(onFailure = { e -> Log.w(TAG, "Dropping undecodable question '${entity.interactionId}'", e) }) {
                json.decodeFromJsonElement<QuestionRequestDto>(it)
            }
        } ?: return null
        return toPendingQuestion(request, entity.interactionId, entity.toolCallId, agentId = null)
    }

    private fun toDomain(dto: TranscriptItemDto): TranscriptTurn = TranscriptTurn(
        id = dto.turnId,
        ordinal = dto.ordinal,
        state = transcriptState(dto.state),
        prompt = dto.prompt?.takeIf { dto.origin?.kind == "user" }?.takeIf { it.isNotBlank() },
        steps = dto.steps.map { step ->
            TranscriptStep(
                id = step.stepId,
                state = transcriptState(step.state),
                frames = step.frames.mapNotNull(::toDomain),
            )
        },
        taskOrigin = dto.origin?.takeIf { it.kind == "task" }?.let(::toTaskOrigin),
    )

    /** The `task` origin of a completion-notification turn (verified on 0.34.0 —
     * docs/server-api.md, Transcript): `taskId` ships both flattened and inside
     * `payload`, `status` only inside `payload`. Null when the task id is absent. */
    private fun toTaskOrigin(dto: TranscriptOriginDto): TranscriptTaskOrigin? {
        val taskId = (dto.payload?.get("taskId") as? JsonPrimitive)?.contentOrNull ?: dto.taskId
        return taskId?.takeIf { it.isNotBlank() }?.let {
            TranscriptTaskOrigin(
                taskId = it,
                status = taskOriginStatus((dto.payload?.get("status") as? JsonPrimitive)?.contentOrNull),
            )
        }
    }

    /** The origin's terminal outcome — only "completed" is verified on the wire
     * (0.34.0); the rest of the task outcome vocabulary is assumed. "running" and an
     * unknown value map to null and the store's heal skips the turn instead of
     * guessing. */
    private fun taskOriginStatus(status: String?): TaskOutcome? = TaskOutcome.fromWire(status)

    private fun toDomain(dto: TranscriptFrameDto): TranscriptFrame? = when (dto.kind) {
        "thinking" -> dto.text?.let { TranscriptFrame.Thinking(dto.frameId, it) }
        "text" -> dto.text?.let { text ->
            val role = when (dto.role) {
                "assistant" -> TranscriptFrame.Text.Role.Assistant
                "user" -> TranscriptFrame.Text.Role.User
                else -> TranscriptFrame.Text.Role.Other
            }
            // Same boundary as the message path: a sub-agent's user-role frames (its
            // internal prompts/nudges) can carry harness envelopes — strip them rather
            // than render raw XML on the sub-agent page; a frame left blank is dropped.
            // A blank ASSISTANT frame is kept: a running turn's just-opened text frame
            // is "" until its appends land, and the cold start seeds the fold from it
            // (ticket 176) — the flatten skips blank text anyway.
            val stripped = if (role == TranscriptFrame.Text.Role.User) InternalEnvelopes.strip(text) else text
            stripped.takeIf { it.isNotBlank() || role == TranscriptFrame.Text.Role.Assistant }
                ?.let { TranscriptFrame.Text(dto.frameId, role, it) }
        }
        "tool" -> dto.toolCallId?.let { toolCallId ->
            val rendering = toolRendering(dto)
            // 2.5.1 (ticket 184): a media result's `output` is a content-part array.
            val output = toolOutput(dto.output)
            TranscriptFrame.Tool(
                id = dto.frameId,
                toolCallId = toolCallId,
                name = rendering.name,
                running = dto.state == "running",
                preview = rendering.preview,
                input = rendering.input,
                // The volatile progress tail stands in while no definitive output exists
                // (a media-only result is definitive: its text stays blank).
                output = if (output.text.isBlank() && output.media.isEmpty()) progressText(dto.progress) else output.text,
                media = output.media,
                // The raw args object (ticket 118) — null when the frame carries no
                // `input` object, leaving the pretty string as the row's fallback.
                args = rendering.args,
                agentCall = rendering.agentCall,
                failed = dto.state == "error",
            )
        }
        else -> null
    }

    /**
     * A tool frame's row rendering — the ONE derivation behind the live fold's
     * `ToolCallStarted` and the REST transcript's [TranscriptFrame.Tool] (the cold
     * start seeds rows the live ops then heal in place, so both must render a call
     * alike): the `input` object or, while the call streams, `inputText` once it
     * parses as a complete object (a partial fragment renders no args — the row's
     * first-writer-wins preview must not lock onto it), plus the server's `display`
     * (command, `agent_call`).
     */
    internal fun toolRendering(dto: TranscriptFrameDto): ToolRendering {
        val name = dto.name.orEmpty()
        val args = dto.input ?: dto.inputText?.let { parseOrNull { json.parseToJsonElement(it) as? JsonObject } }
        val display = dto.display?.let { parseOrNull { json.decodeFromJsonElement<ToolInputDisplayDto>(it) } }
        return ToolRendering(
            name = name,
            preview = preview(name, args, display),
            input = formatDetail(args),
            args = args,
            agentCall = agentCall(display),
        )
    }

    private fun transcriptState(state: String): TranscriptState = when (state) {
        "running" -> TranscriptState.Running
        "completed" -> TranscriptState.Completed
        // Real on the wire since 0.31.1 (docs/server-api.md, Transcript) — ticket 085
        // consumes them for the sub-agent's cold status.
        "failed", "cancelled" -> TranscriptState.Failed
        else -> TranscriptState.Other
    }

    /** The `{kind, text}` progress object of a tool frame; only the text is consumed. */
    private fun progressText(progress: JsonObject?): String =
        progress?.get("text")?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private companion object {
        const val TAG = "ChatMessageMapper"
        const val PLAN_REVIEW_KIND = "plan_review"
        val DATA_URL_RE = Regex("""^data:([^;,]+);base64,(.*)$""", RegexOption.DOT_MATCHES_ALL)

        /** The content-part types a tool-output array is recognized by (ticket 184). */
        val TOOL_OUTPUT_PART_TYPES = setOf("text", "image", "video")

        /** One bare `<image …>`/`</image>` (or video) tag — the wrapper text the media
         * tools frame their media part with (docs/server-api.md, Messages → History). */
        val MEDIA_WRAPPER_TAG_RE = Regex("""^(?:<(?:image|video)(?:\s[^<>]*)?>|</(?:image|video)>)$""")

        // File-store ids come in two shapes: v1 `f_`<26-char ULID> and v2 `f_`<randomUUID>.
        // Anchored at the start of the `<fileId>-<name>` basename — splitting on the first
        // '-' would truncate v2 UUID ids at their first inner hyphen.
        val FILE_STORE_ID_AT_START_RE =
            Regex("""^f_(?:[0-9A-Za-z]{26}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})(?=-)""")
    }
}

/** A tool frame's rendered call ([ChatMessageMapper.toolRendering]). */
internal data class ToolRendering(
    val name: String,
    val preview: String,
    val input: String,
    val args: JsonObject?,
    val agentCall: AgentCallDisplay?,
)
