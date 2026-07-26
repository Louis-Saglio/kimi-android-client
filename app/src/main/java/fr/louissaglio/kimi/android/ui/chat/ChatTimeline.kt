package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.SessionEvent

/**
 * Pure reducer behind the chat screen: flattens history messages into timeline rows
 * and applies live socket events. Holds no coroutine state, which keeps it trivially
 * unit-testable; the ViewModel publishes [items] into its UiState after each call.
 *
 * History (committed) and live (in-flight turn) rows are kept apart: a refetch replaces
 * committed rows wholesale, which is what makes reconnects gap- and duplicate-free.
 */
class ChatTimeline {

    private val committed = mutableListOf<ChatItem>()
    private val live = mutableListOf<ChatItem>()
    private var liveBubbleCount = 0
    private var syntheticSpliceCount = 0

    fun items(): List<ChatItem> = committed + live

    /**
     * Replaces committed rows from a fresh history fetch. With [keepLive] (mid-turn
     * refetch), live rows survive — minus any the server has since committed, dropped
     * here to avoid duplicates. Without it (turn ended), live state is cleared: history
     * is the canonical rendering of the finished turn.
     */
    fun replaceHistory(messages: List<ChatMessage>, keepLive: Boolean) {
        committed.clear()
        messages.forEach(::flatten)
        if (!keepLive) {
            live.clear()
            return
        }
        val committedToolIds = committed.filterIsInstance<ChatItem.ToolGroup>()
            .flatMap { group -> group.tools.map { it.id } }
            .toSet()
        // A live group may be half-committed (a refetch landed mid-run): drop only the
        // tools the server now has, keep the rest under the first survivor's id.
        for (index in live.indices.reversed()) {
            when (val item = live[index]) {
                is ChatItem.ToolGroup -> {
                    val survivors = item.tools.filterNot { it.id in committedToolIds }
                    if (survivors.isEmpty()) {
                        live.removeAt(index)
                    } else if (survivors.size < item.tools.size) {
                        live[index] = ChatItem.ToolGroup(survivors.first().id, survivors)
                    }
                }
                is ChatItem.AssistantMessage ->
                    if (committed.filterIsInstance<ChatItem.AssistantMessage>()
                            .any { it.text == item.text && it.thinking == item.thinking }
                    ) {
                        live.removeAt(index)
                    }
                else -> Unit
            }
        }
    }

    fun apply(event: SessionEvent) {
        when (event) {
            // Defensive clear: a new turn must never inherit a previous turn's leftovers
            // (possible only if the earlier turn.ended refetch failed).
            SessionEvent.TurnStarted -> live.clear()
            is SessionEvent.AssistantDelta -> appendDelta(event.delta, thinking = false)
            is SessionEvent.ThinkingDelta -> appendDelta(event.delta, thinking = true)
            is SessionEvent.ToolCallDelta ->
                upsertTool(event.toolCallId, event.name, ToolStatus.Running)
            is SessionEvent.ToolCallStarted ->
                upsertTool(event.toolCallId, event.name, ToolStatus.Running, event.preview, input = event.input)
            is SessionEvent.ToolCallResult ->
                upsertTool(
                    event.toolCallId,
                    "",
                    if (event.isError) ToolStatus.Error else ToolStatus.Done,
                    output = event.output,
                    // The definitive result replaces any accumulated progress wholesale.
                    replaceOutput = true,
                )
            is SessionEvent.ToolCallProgress -> appendToolProgress(event.toolCallId, event.chunk)
            is SessionEvent.ContextSpliced -> event.messages.forEach(::appendSpliced)
            // TurnEnded / PromptSubmitted: the ViewModel refetches history instead.
            else -> Unit
        }
    }

    /** The just-sent user message arrives spliced mid-turn (0.27.0 dropped
     * prompt.submitted): commit it immediately so the chat does not look dead for the
     * whole turn. Deduped by message id — a reconnect replay or a racing refetch must
     * not add a second bubble; the turn.ended refetch then replaces committed rows
     * wholesale. Assistant/tool splices are ignored: deltas and history cover them.
     *
     * The steer echo (docs/server-api.md, steer round-trip) splices the steered message
     * without an id: those dedup on the text instead — a racing mid-turn refetch commits
     * the same message with its real id — and get a synthetic key, since two id-less
     * bubbles would otherwise collide as LazyColumn keys. */
    private fun appendSpliced(message: ChatMessage) {
        if (message.role != ChatMessage.Role.User) return
        if (message.id.isNotBlank()) {
            if (committed.any { it.id == message.id }) return
            flatten(message)
            return
        }
        val text = message.parts.filterIsInstance<ChatMessage.Part.Text>().joinToString("\n") { it.text }
        val attachments = message.parts.filterIsInstance<ChatMessage.Part.Attachment>()
        val images = message.parts.filterIsInstance<ChatMessage.Part.Image>()
        if (text.isBlank() && attachments.isEmpty() && images.isEmpty()) return
        if (committed.filterIsInstance<ChatItem.UserMessage>().any {
                it.text == text && it.attachments == attachments && it.images == images
            }
        ) {
            return
        }
        committed += ChatItem.UserMessage("spliced_${syntheticSpliceCount++}", text, attachments, images, message.createdAt)
    }

    /** Deltas extend the last live bubble; after a tool row a new bubble starts, so a
     * multi-step turn reads text → tool → text like the web client. */
    private fun appendDelta(delta: String, thinking: Boolean) {
        val last = live.lastOrNull()
        if (last is ChatItem.AssistantMessage) {
            live[live.lastIndex] =
                if (thinking) last.copy(thinking = last.thinking + delta) else last.copy(text = last.text + delta)
        } else {
            live += ChatItem.AssistantMessage(
                id = "live_${liveBubbleCount++}",
                text = if (thinking) "" else delta,
                thinking = if (thinking) delta else "",
            )
        }
    }

    /** Live output streaming (ticket 025): chunks append to the matching *running* row
     * only. Progress is volatile — it never creates a row (unknown ids are dropped),
     * a finished call keeps its definitive result, and a history refetch replaces the
     * rows wholesale, clearing whatever accumulated. */
    private fun appendToolProgress(toolCallId: String, chunk: String) {
        live.updateTool(toolCallId) { existing ->
            if (existing.status == ToolStatus.Running) existing.copy(output = existing.output + chunk) else existing
        }
    }

    private fun upsertTool(
        toolCallId: String,
        name: String,
        status: ToolStatus,
        preview: String = "",
        input: String = "",
        output: String = "",
        replaceOutput: Boolean = false,
    ) {
        var found = false
        live.updateTool(toolCallId) { existing ->
            found = true
            existing.copy(
                name = existing.name.ifBlank { name },
                preview = existing.preview.ifBlank { preview },
                input = existing.input.ifBlank { input },
                output = if (replaceOutput) output else existing.output.ifBlank { output },
                status = status,
            )
        }
        if (!found) live.appendTool(ChatItem.ToolActivity(toolCallId, name, status, preview, input, output))
    }

    private fun flatten(message: ChatMessage) {
        when (message.role) {
            ChatMessage.Role.User -> {
                val text = message.parts.filterIsInstance<ChatMessage.Part.Text>().joinToString("\n") { it.text }
                val attachments = message.parts.filterIsInstance<ChatMessage.Part.Attachment>()
                val images = message.parts.filterIsInstance<ChatMessage.Part.Image>()
                if (text.isNotBlank() || attachments.isNotEmpty() || images.isNotEmpty()) {
                    committed += ChatItem.UserMessage(message.id, text, attachments, images, message.createdAt)
                }
            }
            ChatMessage.Role.Assistant -> {
                val text = message.parts.filterIsInstance<ChatMessage.Part.Text>().joinToString("\n") { it.text }
                val thinking = message.parts.filterIsInstance<ChatMessage.Part.Thinking>().joinToString("\n") { it.text }
                if (text.isNotBlank() || thinking.isNotBlank()) {
                    committed += ChatItem.AssistantMessage(message.id, text, thinking)
                }
                message.parts.filterIsInstance<ChatMessage.Part.ToolUse>().forEach {
                    committed.appendTool(
                        ChatItem.ToolActivity(it.toolCallId, it.toolName, ToolStatus.Running, it.preview, it.input),
                    )
                }
            }
            ChatMessage.Role.Tool ->
                message.parts.filterIsInstance<ChatMessage.Part.ToolResult>().forEach { result ->
                    val status = if (result.isError) ToolStatus.Error else ToolStatus.Done
                    var found = false
                    committed.updateTool(result.toolCallId) { existing ->
                        found = true
                        existing.copy(
                            status = status,
                            output = existing.output.ifBlank { result.output },
                        )
                    }
                    if (!found) {
                        // Result without a matching call (e.g. page boundary): show it anyway.
                        committed.appendTool(ChatItem.ToolActivity(result.toolCallId, "", status, output = result.output))
                    }
                }
            ChatMessage.Role.Other -> Unit
        }
    }

    /** Appends [tool] to the trailing group, or opens a new one when the last row is
     * not a tool run — a non-tool row between two calls splits them into two groups
     * (ticket 037). The group id stays the first call's id, so a growing live group
     * keeps its LazyColumn key. */
    private fun MutableList<ChatItem>.appendTool(tool: ChatItem.ToolActivity) {
        val last = lastOrNull()
        if (last is ChatItem.ToolGroup) {
            this[lastIndex] = last.copy(tools = last.tools + tool)
        } else {
            this += ChatItem.ToolGroup(tool)
        }
    }

    /** In-place update of the row [toolCallId] inside whichever group holds it,
     * searched from the tail (a result matches its most recent call). No-op when the
     * id is unknown. */
    private fun MutableList<ChatItem>.updateTool(
        toolCallId: String,
        update: (ChatItem.ToolActivity) -> ChatItem.ToolActivity,
    ) {
        for (groupIndex in indices.reversed()) {
            val group = this[groupIndex] as? ChatItem.ToolGroup ?: continue
            val toolIndex = group.tools.indexOfFirst { it.id == toolCallId }
            if (toolIndex < 0) continue
            val tools = group.tools.toMutableList()
            tools[toolIndex] = update(tools[toolIndex])
            this[groupIndex] = group.copy(tools = tools)
            return
        }
    }
}
