package fr.louissaglio.kimi.android.data.chat

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Wire DTO → domain mapping for history messages and socket payloads (the single
 * mapping layer shared by ChatApi and SessionSocket). Also owns the display
 * renderings derived from payloads (previews, summaries, pretty-printed details).
 */
@Singleton
class ChatMessageMapper @Inject constructor(json: Json) {

    // Pretty-printing for the expanded tool row (ticket 023): strings render as-is,
    // JSON values (tool inputs, structured outputs) render indented.
    private val prettyJson = Json(json) { prettyPrint = true }

    /** Renders a wire JSON value for the expanded tool row: a plain string comes out as
     * its raw text (shell output stays untouched), anything else as pretty-printed JSON. */
    fun formatDetail(element: JsonElement?): String = when {
        element == null || element is JsonNull -> ""
        element is JsonPrimitive && element.isString -> element.content
        else -> prettyJson.encodeToString(JsonElement.serializer(), element)
    }

    /** The compact argument preview for the approval card (the command for shell
     * calls, else the action). */
    fun summary(payload: ApprovalRequestedPayload): String =
        payload.toolInputDisplay?.command?.takeIf { it.isNotBlank() } ?: payload.action

    /** The row preview for a started call: the server's display command when it sent
     * one, else derived from the arguments (ToolPreview). */
    fun preview(payload: ToolCallPayload): String =
        payload.display?.command?.takeIf { it.isNotBlank() } ?: ToolPreview.of(payload.args)

    fun toDomain(payload: QuestionRequestedPayload): PendingQuestion = PendingQuestion(
        questionId = payload.questionId,
        items = payload.questions.map { item ->
            PendingQuestion.Item(
                id = item.id,
                header = item.header,
                text = item.question,
                options = item.options.map { PendingQuestion.Option(it.id, it.label, it.description) },
                allowOther = item.allowOther,
            )
        },
    )

    fun toDomain(dto: MessageDto): ChatMessage {
        val domainRole = when (dto.role) {
            "user" -> ChatMessage.Role.User
            "assistant" -> ChatMessage.Role.Assistant
            "tool" -> ChatMessage.Role.Tool
            else -> ChatMessage.Role.Other
        }
        return ChatMessage(
            id = dto.id.orEmpty(),
            role = domainRole,
            parts = dto.content.mapNotNull { toDomain(it, stripEnvelopes = domainRole == ChatMessage.Role.User) },
            // A malformed timestamp must not fail the whole message: drop it, keep the rest.
            createdAt = dto.createdAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            originKind = dto.originKind,
        )
    }

    private fun toDomain(dto: ContentPartDto, stripEnvelopes: Boolean): ChatMessage.Part? = when (dto.type) {
        // Internal envelopes are stripped here — the single domain boundary every
        // ingestion path (history, context.spliced echoes) flows through. A part
        // left blank is dropped so ChatTimeline never sees an empty bubble's text.
        "text" -> dto.text
            ?.let { if (stripEnvelopes) InternalEnvelopes.strip(it) else it }
            ?.takeIf { it.isNotBlank() }
            // The server rewrites a prompt's file parts into this notice
            // (docs/server-api.md, Prompts); recover the chip instead of rendering
            // the raw sentence (ticket 034). User-role only: stripEnvelopes gates it.
            ?.let { if (stripEnvelopes) AttachmentNotice.parse(it) ?: ChatMessage.Part.Text(it) else ChatMessage.Part.Text(it) }
        "thinking" -> dto.thinking?.let { ChatMessage.Part.Thinking(it) }
        "image" -> dto.source?.let { toDomain(it) }
        "tool_use" -> dto.toolCallId?.let {
            ChatMessage.Part.ToolUse(it, dto.toolName.orEmpty(), ToolPreview.of(dto.input), formatDetail(dto.input))
        }
        "tool_result" -> dto.toolCallId?.let {
            ChatMessage.Part.ToolResult(it, dto.isError ?: false, formatDetail(dto.output))
        }
        else -> null
    }

    private fun toDomain(dto: MediaSourceDto): ChatMessage.Part.Image? = when (dto.kind) {
        "base64" -> dto.data?.let { ChatMessage.Part.Image(base64Data = it, fileId = null) }
        "url" -> dto.url?.let { parseDataUrl(it) }
        "file" -> dto.fileId?.let { ChatMessage.Part.Image(base64Data = null, fileId = it) }
        else -> null
    }

    /** History inlines images as `data:<mime>;base64,…` urls (docs/server-api.md). */
    private fun parseDataUrl(url: String): ChatMessage.Part.Image? =
        DATA_URL_RE.matchEntire(url)?.let {
            ChatMessage.Part.Image(base64Data = it.groupValues[2], fileId = null)
        }

    /** Transcript page (docs/server-api.md, Transcript): marker items are dropped here,
     * turns keep their ascending wire order. */
    fun toDomain(dto: TranscriptData): TranscriptPage = TranscriptPage(
        agentId = dto.agentId,
        turns = dto.items.filter { it.kind == "turn" }.map(::toDomain),
        hasMore = dto.hasMore,
    )

    /** Session snapshot (docs/server-api.md, Session snapshot — ticket 085): the
     * cold-start bundle. The messages page is already domain-ordered: unlike
     * `/messages` (wire newest-first → reversed here), the snapshot's `items`
     * come OLDEST-first on the wire (live-verified on 0.31.1, 2026-08-02, against
     * a long session: item ids and `created_at` ascend across the page) — reversing
     * them inverts the whole cold-start timeline (calls stuck Running behind orphan
     * result rows, the bottom pin landing mid-session, a newest-id paging cursor). */
    fun toDomain(dto: SnapshotData): SessionSnapshot = SessionSnapshot(
        asOfSeq = dto.asOfSeq,
        epoch = dto.epoch,
        busy = dto.session?.busy == true,
        contextUsage = dto.session?.usage?.let { ContextUsage(it.contextTokens, it.contextLimit) },
        messages = MessagePage(
            items = dto.messages?.items.orEmpty().map { toDomain(it) },
            hasMore = dto.messages?.hasMore == true,
        ),
        pendingApprovals = dto.pendingApprovals.map {
            SnapshotApproval(it.approvalId, it.toolName, summary(it))
        },
        pendingQuestions = dto.pendingQuestions.map { toDomain(it) },
        subagents = dto.subagents.map { entry ->
            SubagentRosterEntry(
                id = entry.id,
                subagentType = entry.subagentType,
                description = entry.description,
                phase = when (entry.subagentPhase) {
                    "queued" -> SubagentRosterPhase.Queued
                    "working" -> SubagentRosterPhase.Working
                    "suspended" -> SubagentRosterPhase.Suspended
                    "completed" -> SubagentRosterPhase.Completed
                    "failed" -> SubagentRosterPhase.Failed
                    else -> SubagentRosterPhase.Unknown
                },
                parentToolCallId = entry.parentToolCallId,
                runInBackground = entry.runInBackground,
                outputPreview = entry.outputPreview?.takeIf { it.isNotBlank() },
            )
        },
        inFlightTurn = dto.inFlightTurn?.let { turn ->
            InFlightTurn(
                turnId = turn.turnId,
                assistantText = turn.assistantText,
                thinkingText = turn.thinkingText,
                runningTools = turn.runningTools.map { RunningTool(it.toolCallId, it.name, it.args, it.display) },
                currentPromptId = turn.currentPromptId,
            )
        },
    )

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
    )

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
            val stripped = if (role == TranscriptFrame.Text.Role.User) InternalEnvelopes.strip(text) else text
            stripped.takeIf { it.isNotBlank() }?.let { TranscriptFrame.Text(dto.frameId, role, it) }
        }
        "tool" -> dto.toolCallId?.let { toolCallId ->
            TranscriptFrame.Tool(
                id = dto.frameId,
                toolCallId = toolCallId,
                name = dto.name.orEmpty(),
                running = dto.state == "running",
                preview = ToolPreview.of(dto.input),
                input = formatDetail(dto.input).ifBlank { dto.inputText.orEmpty() },
                // The volatile progress tail stands in while no definitive output exists.
                output = formatDetail(dto.output).ifBlank { progressText(dto.progress) },
            )
        }
        else -> null
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
        val DATA_URL_RE = Regex("""^data:([^;,]+);base64,(.*)$""", RegexOption.DOT_MATCHES_ALL)
    }
}
