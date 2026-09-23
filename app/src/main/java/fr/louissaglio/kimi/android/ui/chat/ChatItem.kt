package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.ToolPayload
import java.time.Instant
import kotlinx.serialization.json.JsonObject

enum class ToolStatus { Running, Done, Error }

/** One row of the chat timeline. [id] is the LazyColumn key: message id for bubbles,
 * tool call id for activity rows, first tool call id of the run for [ToolGroup],
 * synthetic `live_N` for in-flight streaming bubbles. */
sealed interface ChatItem {
    val id: String

    /** [attachments] are the non-image files from the message origin's structured
     * `attachments` array (ticket 160), [images] the inlined image parts (ticket 034);
     * both empty for plain messages.
     * [createdAt] is the server timestamp, shown as a caption under the bubble
     * (ticket 039); null on live spliced messages until the turn-end refetch.
     * [queued] is the prompt's queue position while it waits server-side (ticket 154):
     * set on the optimistic local echo by `prompt.queued`, cleared by `prompt.started`
     * (or when the splice heals the echo) — the caption shows "Queued #N" while set. */
    data class UserMessage(
        override val id: String,
        val text: String,
        val attachments: List<ChatMessage.Part.Attachment> = emptyList(),
        val images: List<ChatMessage.Part.Image> = emptyList(),
        val createdAt: Instant? = null,
        val queued: Int? = null,
    ) : ChatItem

    data class AssistantMessage(
        override val id: String,
        val text: String,
        val thinking: String,
    ) : ChatItem

    /** One row per tool call. [preview] is the one-line summary of the call's input
     * (e.g. the shell command for Bash); blank when the server sent none. [args] is the
     * call's raw arguments object (ticket 118), rendered by the expanded row as a
     * key/value table (ToolDetailFields); [input] is the same input pretty-printed —
     * the expanded row's fallback when no object args exist. [payload] is the args'
     * typed, per-tool parse (ticket 119 — ToolPayloads, derived wherever args is set,
     * first-writer-wins like it): a parsed payload switches the row to its rich
     * treatment (an Edit row opens the diff screen, a Read/Write row the file content
     * screen — ticket 120 — instead of expanding inline; a foreground Bash row keeps
     * expanding inline but renders the terminal-styled detail block — ticket 121; the
     * Glob/WebSearch/FetchURL lookup rows keep expanding inline too, the result
     * rendered per tool in place of the generic args table — ticket 122; the Grep
     * row joins them, its result parsed per `output_mode` — ticket 123; a CronCreate
     * row expands to the full scheduled prompt — its `key: value` prose result stays
     * verbatim — CronDelete/CronList stay lean, and a TaskOutput/TaskStop/WaitFor
     * payload's task_id links the row to the job screen like [bashTaskId] does —
     * ticket 124).
     * [output] is the result
     * text; either may be blank while the call is still running.
     * While running, [output] accumulates the volatile tool.progress chunks (ticket 025)
     * — the definitive tool.result output replaces them wholesale. [subagentId] links an
     * `Agent` row to its sub-agent's page (ticket 045); null rows are not tappable.
     * It stays the single-`Agent` link (ticket 134): an `AgentSwarm` row never carries
     * it — the swarm card navigates by the tool call id the row already holds.
     * [bashTaskId] links a background `Bash` row to its job screen (ticket 096) —
     * parsed from the result's `task_id: bash-…` first line; like the sub-agent link,
     * a linked row never expands. [timeoutSeconds]/[timeoutDisabled] are the call
     * input's timeout knobs (ticket 097) — the job screen's budget display derives
     * from them (REST never exposes the timeout). All three link/budget fields are
     * derived by the ONE enrichment pass every row path routes through
     * ([enriched], ticket 098). */
    data class ToolActivity(
        override val id: String,
        val name: String,
        val status: ToolStatus,
        val preview: String = "",
        val input: String = "",
        val output: String = "",
        val args: JsonObject? = null,
        val payload: ToolPayload? = null,
        val subagentId: String? = null,
        val bashTaskId: String? = null,
        val timeoutSeconds: Int? = null,
        val timeoutDisabled: Boolean = false,
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

    /** The turn-end footer (ticket 155): one muted line closing a turn that stopped
     * early, its [label] the `turn.ended.interruptReason` copy ("Cancelled", "Stopped:
     * max steps", …). Only the newest ended turn carries one — a constant id reset in
     * place like [CompactionDivider]; the next turn.started removes it. */
    data class TurnEnd(override val id: String, val label: String) : ChatItem
}

/** LazyColumn key of the live compaction divider (ticket 056): one constant — the
 * server runs one compaction per session at a time, and a new run resets the previous
 * run's done row in place instead of stacking a second key (ChatTimeline). */
const val LIVE_COMPACTION_DIVIDER_ID = "compaction_live"

/** LazyColumn key of the turn-end footer (ticket 155): one constant — only the newest
 * ended turn's footer shows, reset in place on a successive early stop (ChatTimeline). */
const val TURN_END_ROW_ID = "turn_end"
