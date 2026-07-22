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
        live.removeAll { item ->
            when (item) {
                is ChatItem.ToolActivity -> committed.any { it.id == item.id }
                is ChatItem.AssistantMessage ->
                    committed.filterIsInstance<ChatItem.AssistantMessage>()
                        .any { it.text == item.text && it.thinking == item.thinking }
                else -> false
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
                upsertTool(event.toolCallId, event.name, ToolStatus.Running, event.preview)
            is SessionEvent.ToolCallResult ->
                upsertTool(event.toolCallId, "", if (event.isError) ToolStatus.Error else ToolStatus.Done)
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
        if (text.isBlank()) return
        if (committed.filterIsInstance<ChatItem.UserMessage>().any { it.text == text }) return
        committed += ChatItem.UserMessage("spliced_${syntheticSpliceCount++}", text)
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

    private fun upsertTool(toolCallId: String, name: String, status: ToolStatus, preview: String = "") {
        val index = live.indexOfFirst { it is ChatItem.ToolActivity && it.id == toolCallId }
        if (index >= 0) {
            val existing = live[index] as ChatItem.ToolActivity
            live[index] = existing.copy(
                name = existing.name.ifBlank { name },
                preview = existing.preview.ifBlank { preview },
                status = status,
            )
        } else {
            live += ChatItem.ToolActivity(toolCallId, name, status, preview)
        }
    }

    private fun flatten(message: ChatMessage) {
        when (message.role) {
            ChatMessage.Role.User -> {
                val text = message.parts.filterIsInstance<ChatMessage.Part.Text>().joinToString("\n") { it.text }
                if (text.isNotBlank()) committed += ChatItem.UserMessage(message.id, text)
            }
            ChatMessage.Role.Assistant -> {
                val text = message.parts.filterIsInstance<ChatMessage.Part.Text>().joinToString("\n") { it.text }
                val thinking = message.parts.filterIsInstance<ChatMessage.Part.Thinking>().joinToString("\n") { it.text }
                if (text.isNotBlank() || thinking.isNotBlank()) {
                    committed += ChatItem.AssistantMessage(message.id, text, thinking)
                }
                message.parts.filterIsInstance<ChatMessage.Part.ToolUse>().forEach {
                    committed += ChatItem.ToolActivity(it.toolCallId, it.toolName, ToolStatus.Running, it.preview)
                }
            }
            ChatMessage.Role.Tool ->
                message.parts.filterIsInstance<ChatMessage.Part.ToolResult>().forEach { result ->
                    val index = committed.indexOfLast { it is ChatItem.ToolActivity && it.id == result.toolCallId }
                    val status = if (result.isError) ToolStatus.Error else ToolStatus.Done
                    if (index >= 0) {
                        committed[index] = (committed[index] as ChatItem.ToolActivity).copy(status = status)
                    } else {
                        // Result without a matching call (e.g. page boundary): show it anyway.
                        committed += ChatItem.ToolActivity(result.toolCallId, "", status)
                    }
                }
            ChatMessage.Role.Other -> Unit
        }
    }
}
