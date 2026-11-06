package fr.louissaglio.kimi.android.data.chat

import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Folds one session's `transcript.ops` batches (docs/server-api.md, WebSocket →
 * Transcript protocol — server 2.5.0, ticket 175) into the [SessionEvent]s the chat and
 * the sub-agent stream store already consume. The ops are entity upserts; the events
 * are transitions, so the fold keeps just enough per-agent state to turn the one into
 * the other:
 *
 * - text/thinking frames: the accumulated text per frame id, so a `frame.upsert` or an
 *   `append` emits only the NEW suffix as an [SessionEvent.AssistantDelta]/
 *   [SessionEvent.ThinkingDelta] (a frame's final `frame.upsert` replaces the
 *   accumulated text; a re-sent frame or a duplicate `append` emits nothing; an append
 *   past the local end is a gap — dropped: the frame's final `frame.upsert` heals the
 *   text, the turn-end refetch the row; a journal that cannot cover the resume
 *   watermark is the socket's stale signal, ticket 176);
 * - tool frames: the last rendering emitted, so [SessionEvent.ToolCallStarted] fires on
 *   first sight and again only when arguments/display become known, and
 *   [SessionEvent.ToolCallResult] exactly once, on `done`/`error`;
 * - turns, steps and prompts: their last state, so a re-sent upsert never re-fires a
 *   transition (turn started/ended, step started/completed, prompt status);
 * - `undo` markers: the ids seen, so a replayed splice is not re-emitted.
 *
 * Events carry the batch's `agent_id`; the collectors route non-main agents to the
 * sub-agent stream store. A turn's frame state is pruned when the turn ends. Not
 * thread-safe: one collection at a time feeds a session (ticket 064), and the socket
 * applies batches sequentially.
 */
internal class TranscriptFold(
    private val json: Json,
    private val mapper: ChatMessageMapper,
) {
    private sealed interface FrameState {
        val turnId: String

        /** An assistant text or thinking frame: [text] is what was emitted so far. */
        class Streamed(override val turnId: String, val thinking: Boolean, var text: String) : FrameState

        /** A tool frame: [rendering] is the last started rendering emitted, [settled]
         * whether its result was emitted. */
        class Tool(override val turnId: String, var rendering: ToolRendering?, var settled: Boolean) : FrameState

        /** A frame the chat does not stream (user-role text, notice): appends to it are
         * consumed silently. */
        class Ignored(override val turnId: String) : FrameState
    }

    private data class ToolRendering(
        val name: String,
        val preview: String,
        val input: String,
        val args: JsonObject?,
        val agentCall: AgentCallDisplay?,
    )

    private data class Key(val agentId: String, val id: String)

    private data class StepState(val turnId: String, val state: String)

    private val frames = HashMap<Key, FrameState>()
    private val turnStates = HashMap<Key, String>()
    private val stepStates = HashMap<Key, StepState>()
    private val promptStates = HashMap<Key, PromptStatus>()
    private val seenMarkers = HashSet<Key>()

    /** One `transcript.ops` batch of [agentId], in op order. A malformed op is dropped
     * alone (Log.w), never its batch. */
    fun fold(agentId: String, ops: List<JsonObject>): List<SessionEvent> {
        val events = mutableListOf<SessionEvent>()
        for (raw in ops) {
            val op = runCatching { json.decodeFromJsonElement<TranscriptOpDto>(raw) }
                .onFailure { Log.w(TAG, "Dropping undecodable transcript op", it) }
                .getOrNull() ?: continue
            when (op.op) {
                "prompt.upsert" -> op.prompt?.let { onPrompt(agentId, it, events) }
                "turn.upsert" -> op.turn?.let { onTurn(agentId, it, events) }
                "step.upsert" -> op.step?.let { onStep(agentId, it, events) }
                "frame.upsert" -> op.frame?.let { onFrame(agentId, op.turnId.orEmpty(), it, events) }
                "append" -> op.target?.let { onAppend(agentId, it, op.offset, op.text, events) }
                "marker.upsert" -> op.item?.let { onMarker(agentId, it, events) }
                // Approvals/questions ride the flat `interaction` frame (tickets 171/172);
                // attachments and removals carry nothing the live rows render — the
                // turn-end refetch reconciles them.
                "interaction.upsert", "attachment.upsert", "items.remove" ->
                    Log.d(TAG, "Ignoring '${op.op}' op")
                else -> Log.w(TAG, "Dropping unknown transcript op '${op.op}'")
            }
        }
        return events
    }

    /** Forgets everything folded for [agentId] — its journal no longer continues from
     * what the fold saw (a stale resume, ticket 176), or a REST page replaces it
     * ([seed]). */
    fun reset(agentId: String) {
        frames.keys.removeAll { it.agentId == agentId }
        turnStates.keys.removeAll { it.agentId == agentId }
        stepStates.keys.removeAll { it.agentId == agentId }
        promptStates.keys.removeAll { it.agentId == agentId }
        seenMarkers.removeAll { it.agentId == agentId }
    }

    /**
     * Replaces [agentId]'s fold state with a REST transcript page (the cold start,
     * ticket 176): the page's turn/step states, its running turn's frames (the text
     * the chat seeds its live rows from — so a replayed `append` past the seeded text
     * extends it instead of reading as a gap, and a replayed `turn.upsert running`
     * re-fires no TurnStarted that would clear the seeded rows) and its prompt
     * statuses (the queue-length bookkeeping). The ops that follow the page's `seq`
     * then fold onto exactly what the chat shows. Tool frames are seeded unrendered:
     * the first replayed upsert of a running call re-emits its started event, which
     * heals the seeded row in place.
     */
    fun seed(agentId: String, page: TranscriptPage) {
        reset(agentId)
        for (turn in page.turns) {
            val turnState = when (turn.state) {
                TranscriptState.Running -> "running"
                TranscriptState.Completed -> "completed"
                TranscriptState.Failed -> "failed"
                TranscriptState.Other -> continue
            }
            turnStates[Key(agentId, turn.id)] = turnState
            if (turn.state != TranscriptState.Running) continue
            for (step in turn.steps) {
                val stepState = when (step.state) {
                    TranscriptState.Running -> "running"
                    TranscriptState.Completed -> "completed"
                    TranscriptState.Failed -> "interrupted"
                    TranscriptState.Other -> null
                }
                if (stepState != null) stepStates[Key(agentId, step.id)] = StepState(turn.id, stepState)
                for (frame in step.frames) {
                    frames[Key(agentId, frame.id)] = when (frame) {
                        is TranscriptFrame.Thinking -> FrameState.Streamed(turn.id, thinking = true, frame.text)
                        is TranscriptFrame.Text ->
                            if (frame.role == TranscriptFrame.Text.Role.Assistant) {
                                FrameState.Streamed(turn.id, thinking = false, frame.text)
                            } else {
                                FrameState.Ignored(turn.id)
                            }
                        is TranscriptFrame.Tool -> FrameState.Tool(turn.id, rendering = null, settled = !frame.running)
                    }
                }
            }
        }
        for (prompt in page.prompts) {
            val status = prompt.status ?: continue
            promptStates[Key(agentId, prompt.id)] = status
        }
    }

    private fun onPrompt(agentId: String, prompt: PromptEntityDto, events: MutableList<SessionEvent>) {
        val status = mapper.promptStatus(prompt.status) ?: run {
            Log.w(TAG, "Dropping prompt '${prompt.promptId}' with unknown status '${prompt.status}'")
            return
        }
        val key = Key(agentId, prompt.promptId)
        // The server re-sends unchanged entities (a queued prompt arrives twice).
        if (promptStates.put(key, status) == status) return
        val queueLength = if (status == PromptStatus.Queued) {
            promptStates.count { (k, v) -> k.agentId == agentId && v == PromptStatus.Queued }
        } else {
            0
        }
        events += SessionEvent.PromptUpdated(prompt.promptId, status, queueLength, agentId)
    }

    private fun onTurn(agentId: String, turn: TurnHeaderDto, events: MutableList<SessionEvent>) {
        val key = Key(agentId, turn.turnId)
        val previous = turnStates[key]
        when (turn.state) {
            "running" -> if (previous == null) {
                turnStates[key] = turn.state
                events += SessionEvent.TurnStarted(agentId)
            }
            "completed", "cancelled", "failed" -> if (previous == null || previous == "running") {
                turnStates[key] = turn.state
                events += SessionEvent.TurnEnded(
                    reason = turn.state,
                    agentId = agentId,
                    interruptReason = when (turn.state) {
                        "cancelled" -> INTERRUPT_USER_CANCELLED
                        "failed" -> INTERRUPT_ERROR
                        else -> null
                    },
                    errorMessage = if (turn.state == "failed") turn.errorMessage() else null,
                )
                // The turn's rows commit to history at its end: its frame state is done.
                frames.entries.removeAll { (k, v) -> k.agentId == agentId && v.turnId == turn.turnId }
                stepStates.entries.removeAll { (k, v) -> k.agentId == agentId && v.turnId == turn.turnId }
            }
            else -> Log.d(TAG, "Ignoring turn '${turn.turnId}' state '${turn.state}'")
        }
    }

    /** A failed turn's `error`: a message string, or an object carrying `message`. */
    private fun TurnHeaderDto.errorMessage(): String? {
        val error = error ?: return null
        val text = when (error) {
            is JsonPrimitive -> error.contentOrNull
            is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
                ?: (error["code"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
        return text?.takeIf { it.isNotBlank() }
    }

    private fun onStep(agentId: String, step: StepHeaderDto, events: MutableList<SessionEvent>) {
        if (isEnded(agentId, step.turnId)) return
        val key = Key(agentId, step.stepId)
        val previous = stepStates.put(key, StepState(step.turnId, step.state))?.state
        when (step.state) {
            "running" -> {
                if (previous != "running") events += SessionEvent.StepStarted(step.ordinal, step.stepId, agentId)
                step.retry?.let { raw ->
                    runCatching { json.decodeFromJsonElement<StepRetryDto>(raw) }.getOrNull()
                        ?.takeIf { it.maxAttempts > 0 }
                        ?.let {
                            events += SessionEvent.StepRetrying(
                                step = step.ordinal,
                                nextAttempt = it.nextAttempt.takeIf { n -> n > 0 } ?: it.attempt,
                                maxAttempts = it.maxAttempts,
                                errorMessage = it.errorMessage ?: it.message.orEmpty(),
                                agentId = agentId,
                            )
                        }
                        ?: Log.w(TAG, "Ignoring unreadable retry object on step '${step.stepId}'")
                }
            }
            "completed", "interrupted" ->
                if (previous != step.state) events += SessionEvent.StepCompleted(step.ordinal, agentId)
            else -> Log.d(TAG, "Ignoring step '${step.stepId}' state '${step.state}'")
        }
    }

    /** True once [turnId] reached a terminal state: its pruned frames/steps must not be
     * resurrected by a late or replayed op — the turn-end refetch owns its rows now. */
    private fun isEnded(agentId: String, turnId: String): Boolean =
        turnStates[Key(agentId, turnId)].let { it != null && it != "running" }

    private fun onFrame(agentId: String, turnId: String, frame: TranscriptFrameDto, events: MutableList<SessionEvent>) {
        if (isEnded(agentId, turnId)) return
        val key = Key(agentId, frame.frameId)
        when {
            frame.kind == "thinking" || (frame.kind == "text" && frame.role == "assistant") -> {
                val thinking = frame.kind == "thinking"
                val text = frame.text.orEmpty()
                val state = frames[key] as? FrameState.Streamed
                if (state == null) {
                    frames[key] = FrameState.Streamed(turnId, thinking, text)
                    emitDelta(agentId, thinking, text, events)
                } else if (text.startsWith(state.text)) {
                    val suffix = text.substring(state.text.length)
                    state.text = text
                    emitDelta(agentId, thinking, suffix, events)
                } else {
                    // The final upsert replaces the accumulated text. A rewrite (not an
                    // extension) cannot be expressed as a delta: the next appends build on
                    // the server's text, and the turn-end refetch replaces the bubble.
                    Log.w(TAG, "Frame '${frame.frameId}' text diverged from the streamed text")
                    state.text = text
                }
            }
            frame.kind == "tool" -> onToolFrame(agentId, turnId, key, frame, events)
            // User-role text (a steered-in message — the sent bubbles ride the `undo`
            // marker) and notices carry nothing the live rows stream.
            else -> {
                frames.putIfAbsent(key, FrameState.Ignored(turnId))
                Log.d(TAG, "Ignoring '${frame.kind}' frame (role ${frame.role})")
            }
        }
    }

    private fun onToolFrame(
        agentId: String,
        turnId: String,
        key: Key,
        frame: TranscriptFrameDto,
        events: MutableList<SessionEvent>,
    ) {
        val toolCallId = frame.toolCallId?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "Dropping tool frame '${frame.frameId}' without a toolCallId")
            return
        }
        val state = frames[key] as? FrameState.Tool
            ?: FrameState.Tool(turnId, rendering = null, settled = false).also { frames[key] = it }
        if (state.settled) return
        val rendering = render(frame)
        if (rendering != state.rendering) {
            state.rendering = rendering
            events += SessionEvent.ToolCallStarted(
                toolCallId = toolCallId,
                name = rendering.name,
                preview = rendering.preview,
                input = rendering.input,
                args = rendering.args,
                agentId = agentId,
                agentCall = rendering.agentCall,
            )
        }
        if (frame.state == "done" || frame.state == "error") {
            state.settled = true
            events += SessionEvent.ToolCallResult(toolCallId, frame.state == "error", mapper.formatDetail(frame.output), agentId)
        }
    }

    /** The live row's rendering of a tool frame: the `input` object — or, while the
     * call streams, `inputText` once it parses as a complete object (a partial one
     * renders nothing: the row's first-writer-wins preview must not lock onto a
     * fragment) — plus the server's `display` (command, `agent_call`). */
    private fun render(frame: TranscriptFrameDto): ToolRendering {
        val name = frame.name.orEmpty()
        val args = frame.input ?: frame.inputText?.let {
            runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull()
        }
        val display = frame.display?.let {
            runCatching { json.decodeFromJsonElement<ToolInputDisplayDto>(it) }.getOrNull()
        }
        return ToolRendering(
            name = name,
            preview = mapper.preview(name, args, display),
            input = mapper.formatDetail(args),
            args = args,
            agentCall = mapper.agentCall(display),
        )
    }

    private fun onAppend(agentId: String, target: AppendTargetDto, offset: Int, text: String, events: MutableList<SessionEvent>) {
        if (isEnded(agentId, target.turnId)) return
        when (val state = frames[Key(agentId, target.frameId)]) {
            is FrameState.Streamed -> {
                val local = state.text.length
                when {
                    offset > local ->
                        // A gap: chunks were missed. Dropped — the frame's final upsert
                        // carries the whole text and heals it; a resume the journal cannot
                        // cover never gets here (the socket's stale path, ticket 176).
                        Log.w(TAG, "Dropping append past the end of frame '${target.frameId}' ($offset > $local)")
                    offset + text.length <= local -> Unit // a duplicate chunk
                    else -> {
                        val suffix = text.substring(local - offset)
                        state.text += suffix
                        emitDelta(agentId, state.thinking, suffix, events)
                    }
                }
            }
            is FrameState.Tool, is FrameState.Ignored -> Unit
            null -> Log.w(TAG, "Dropping append to unknown frame '${target.frameId}'")
        }
    }

    private fun emitDelta(agentId: String, thinking: Boolean, delta: String, events: MutableList<SessionEvent>) {
        if (delta.isEmpty()) return
        events += if (thinking) SessionEvent.ThinkingDelta(delta, agentId) else SessionEvent.AssistantDelta(delta, agentId)
    }

    private fun onMarker(agentId: String, item: MarkerItemDto, events: MutableList<SessionEvent>) {
        if (item.marker != MARKER_UNDO) {
            // `compaction` (ticket 181), `swarm.*`: not this fold's business.
            Log.d(TAG, "Ignoring '${item.marker}' marker")
            return
        }
        if (!seenMarkers.add(Key(agentId, item.markerId))) return
        val payload = item.payload?.let {
            runCatching { json.decodeFromJsonElement<UndoMarkerPayload>(it) }
                .onFailure { e -> Log.w(TAG, "Dropping undecodable undo marker '${item.markerId}'", e) }
                .getOrNull()
        } ?: return
        // Injections (the interruption reminder, the compaction continuation) are the
        // harness talking to the model, never a sent bubble.
        val messages = payload.messages
            .filterNot { it.originKind == ORIGIN_KIND_INJECTION }
            .map(mapper::toDomain)
        if (messages.isNotEmpty()) events += SessionEvent.ContextSpliced(messages, agentId)
    }

    private companion object {
        const val TAG = "TranscriptFold"
        const val MARKER_UNDO = "undo"
        const val ORIGIN_KIND_INJECTION = "injection"

        // Ticket 155's footer vocabulary (ChatTimeline.turnEndCopy).
        const val INTERRUPT_USER_CANCELLED = "user_cancelled"
        const val INTERRUPT_ERROR = "error"
    }
}
