package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ChatMessage
import java.time.Instant

enum class ToolStatus { Running, Done, Error }

/** One row of the chat timeline. [id] is the LazyColumn key: message id for bubbles,
 * tool call id for activity rows, first tool call id of the run for [ToolGroup],
 * synthetic `live_N` for in-flight streaming bubbles. */
sealed interface ChatItem {
    val id: String

    /** [attachments] are the non-image files recovered from the server's notice text,
     * [images] the inlined image parts (ticket 034); both empty for plain messages.
     * [createdAt] is the server timestamp, shown as a caption under the bubble
     * (ticket 039); null on live spliced messages until the turn-end refetch. */
    data class UserMessage(
        override val id: String,
        val text: String,
        val attachments: List<ChatMessage.Part.Attachment> = emptyList(),
        val images: List<ChatMessage.Part.Image> = emptyList(),
        val createdAt: Instant? = null,
    ) : ChatItem

    data class AssistantMessage(
        override val id: String,
        val text: String,
        val thinking: String,
    ) : ChatItem

    /** One row per tool call. [preview] is the one-line summary of the call's input
     * (e.g. the shell command for Bash); blank when the server sent none. [input] and
     * [output] are the full call input (pretty JSON) and result text, revealed by the
     * expanded row (ticket 023); either may be blank while the call is still running.
     * While running, [output] accumulates the volatile tool.progress chunks (ticket 025)
     * — the definitive tool.result output replaces them wholesale. [subagentId] links an
     * `Agent` row to its sub-agent's page (ticket 045); null rows are not tappable.
     * [bashTaskId] links a background `Bash` row to its job screen (ticket 096) —
     * parsed from the result's `task_id: bash-…` first line; like the sub-agent link,
     * a linked row never expands. */
    data class ToolActivity(
        override val id: String,
        val name: String,
        val status: ToolStatus,
        val preview: String = "",
        val input: String = "",
        val output: String = "",
        val subagentId: String? = null,
        val bashTaskId: String? = null,
    ) : ChatItem

    /** A run of consecutive tool calls collapsed into one card (ticket 037), like the
     * web client. Built by [ChatTimeline] — the reducer groups in both the committed
     * and the live path, so a non-tool row always splits the run. [id] is the first
     * tool call's id: appending to the group while a turn streams keeps the key stable. */
    data class ToolGroup(
        override val id: String,
        val tools: List<ToolActivity>,
    ) : ChatItem {
        constructor(tool: ToolActivity) : this(tool.id, listOf(tool))
    }

    /** A compaction divider (ticket 056), the timeline trace of a `/compact`: [pending]
     * while the server summarizes ("Compacting context…"), done afterwards. Done rows
     * carry the token delta when the completion event reported it (live); history-sourced
     * rows have none — the numbers are not persisted. [summary] (null only while pending)
     * opens the "Compaction summary" dialog on tap. The live row's [id] is the synthetic
     * [LIVE_COMPACTION_DIVIDER_ID]; a history-sourced row keys on the summary message id. */
    data class CompactionDivider(
        override val id: String,
        val pending: Boolean,
        val tokensBefore: Long? = null,
        val tokensAfter: Long? = null,
        val summary: String? = null,
    ) : ChatItem
}

/** LazyColumn key of the live compaction divider (ticket 056): one constant — the
 * server runs one compaction per session at a time, and a new run resets the previous
 * run's done row in place instead of stacking a second key (ChatTimeline). */
const val LIVE_COMPACTION_DIVIDER_ID = "compaction_live"
