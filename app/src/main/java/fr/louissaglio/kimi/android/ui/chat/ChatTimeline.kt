package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.AGENT_TOOL_NAME
import fr.louissaglio.kimi.android.data.chat.AgentCallDisplay
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.ORIGIN_KIND_COMPACTION_SUMMARY
import fr.louissaglio.kimi.android.data.chat.PromptStatus
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.Todo
import fr.louissaglio.kimi.android.data.chat.TodoParser
import fr.louissaglio.kimi.android.data.chat.ToolPayloads
import fr.louissaglio.kimi.android.data.chat.TranscriptFrame
import fr.louissaglio.kimi.android.data.chat.TranscriptTurn
import fr.louissaglio.kimi.android.data.chat.agentNextStepOf
import fr.louissaglio.kimi.android.data.chat.agentResumeHintOf
import fr.louissaglio.kimi.android.data.chat.agentTaskIdOf
import fr.louissaglio.kimi.android.data.chat.bashTaskIdOf
import fr.louissaglio.kimi.android.data.chat.bashTimeoutOf
import kotlinx.serialization.json.JsonObject

/**
 * Pure reducer behind the chat screen: flattens history messages into timeline rows
 * and applies live socket events. Holds no coroutine state, which keeps it trivially
 * unit-testable; the ViewModel publishes [items] into its UiState after each call.
 *
 * The timeline is ONE ordered row list: committed (history-backed) vs live (in-flight
 * turn) is a position — [liveStart] — not a list membership (ticket 055). The previous
 * pair of parallel lists in the same id space let one tool-call id exist twice: a
 * reconnect refetch committed the in-flight call while the cursor replay re-created
 * its row live, and LazyColumn crashed on the duplicate key. Now every tool event
 * upserts across the WHOLE timeline in place — a late/replayed event for an
 * already-committed call heals that row instead of appending a twin — and a refetch
 * replaces the committed prefix wholesale, dropping the live rows it covers: the
 * single live-vs-committed merge. Sub-agent streams (ticket 045) use
 * [historyBacked] = false: turn.started seals the live tail into the committed prefix
 * (the boundary moves to the end) instead of clearing it. Ticket 046 backs them with
 * the per-agent transcript endpoint: [replaceTranscript] / [prependTranscript] feed
 * turn pages into the same prefix/tail split.
 */
class ChatTimeline(
    private val todoParser: TodoParser,
    private val historyBacked: Boolean = true,
) {

    /** The whole timeline, oldest first: rows before [liveStart] are committed
     * (backed by fetched history/transcript pages), the rest are the live tail of the
     * in-flight turn. All structural mutations go through the boundary helpers
     * ([replaceCommitted], [prependCommitted], [appendCommitted], [appendLiveTool]) so
     * the split can never drift. */
    private val rows = mutableListOf<ChatItem>()
    private var liveStart = 0
    private var liveBubbleCount = 0
    private var syntheticSpliceCount = 0

    /** Turn ids of the committed transcript rows (ticket 046): the prepend dedup unit.
     * Rebuilt by [replaceTranscript], grown by [prependTranscript]. */
    private val committedTurnIds = mutableSetOf<String>()

    /** Message ids of the committed history rows (ticket 043): the prepend dedup unit.
     * Rebuilt by [replaceHistory], grown by [prependHistory]. */
    private val committedMessageIds = mutableSetOf<String>()

    /** Todo tool calls seen so far (ticket 042): their rows are suppressed — the
     * widget replaces them — and their results must not surface as orphan rows. Ids
     * are globally unique per session, so the set is never pruned. */
    private val todoCallIds = mutableSetOf<String>()

    // The widget mirrors the latest TodoList call (ticket 042). Committed and live
    // states are tracked apart: a mid-turn refetch re-derives the committed value
    // while a live call not yet committed stays the latest. null = no call seen,
    // empty = the last call cleared the list; both hide the widget.
    private var committedTodos: List<Todo>? = null
    private var liveTodos: List<Todo>? = null

    /** Copy of the newest ended turn's footer (ticket 155), null when the last
     * `turn.ended` carried no known `interruptReason`. Set by [onTurnEnded]; the row
     * itself is re-appended by every full replace (the turn-end refetch wipes the live
     * tail it first lands in) and dropped when the next turn starts. */
    private var turnEndLabel: String? = null

    /** Tool-call id → linked sub-agent (ticket 045), learned live (spawned, or a
     * background run's early result) and re-driven from the store's session link
     * registry by ChatViewModel after every store-affecting apply (ticket 091 — the
     * store owns the links; this map is the timeline's re-application cache, never
     * pruned): a refetch rebuilds committed rows from history, where the `agent_id:`
     * result marker only exists once the result is committed too. */
    private val subagentLinks = mutableMapOf<String, String>()

    /** Uncommitted local echoes (ticket 154): message id → the optimistic bubble of a
     * prompt this client sent, awaiting its server commit. A refetch that finds the
     * id in history drops it (committed — the canonical row renders); any other
     * re-appends the stored row, so a queued prompt's bubble survives the running
     * turn's turn-end refetch (its message cannot be in history before its own turn
     * commits). */
    private val localEchoes = mutableMapOf<String, ChatItem.UserMessage>()

    fun items(): List<ChatItem> = rows.toList()

    /** Live calls win over history (they are newer) until the turn-end refetch
     * commits them. */
    fun todos(): List<Todo>? = liveTodos ?: committedTodos

    /**
     * Replaces committed rows from a fresh history fetch. With [keepLive] (mid-turn
     * refetch), the live tail survives — minus the rows the server has since committed.
     * Without it (turn ended), the live tail is cleared: history is the canonical
     * rendering of the finished turn.
     */
    fun replaceHistory(messages: List<ChatMessage>, keepLive: Boolean) {
        committedMessageIds.clear()
        committedTodos = null
        val fresh = mutableListOf<ChatItem>()
        messages.forEach { message ->
            committedMessageIds += message.id
            flatten(fresh, message, updateTodos = true)
        }
        replaceCommitted(fresh, keepLive)
    }

    /**
     * Ticket 043 pagination: an older history page prepends its rows before the loaded
     * ones, deduplicated by message id ([committedMessageIds]) — a racing
     * [replaceHistory] can never make a page apply twice. Id-less messages are always
     * kept: they never come from history pages (the steer/splice echoes, which carry no
     * id, stay live-end only), so dedup by id is sound. The live tail is untouched: it
     * only ever grows at the newest end. The todo widget is untouched too — it mirrors
     * the newest page's latest call, and an older page's call must not regress it.
     * Tool pairs split by the page boundary are re-joined by [completeOrphanResults].
     */
    fun prependHistory(messages: List<ChatMessage>) {
        val fresh = messages.filter { it.id.isBlank() || committedMessageIds.add(it.id) }
        if (fresh.isEmpty()) return
        val head = mutableListOf<ChatItem>()
        fresh.forEach { flatten(head, it, updateTodos = false) }
        completeOrphanResults(head)
        prependCommitted(head)
    }

    /**
     * A tool result at the top of the newer page whose call ended the older page (page
     * boundary) flattened to an ORPHAN row in the committed prefix (result without a
     * call — the flatten's "show it anyway" path). Prepending the call must not add a
     * second row with the same tool-call id — a duplicate LazyColumn key (crash verified
     * on-device 2026-07-29, sessions "ticket 043"/"ticket 047") — but it must not
     * re-key or remove the orphan row either: the orphan is the scroll anchor while
     * the user reads the page top, and LazyColumn pins the viewport to the first
     * visible row's key, so losing that key reset the scroll to the top of the new
     * page (Louis's report 2026-07-29, session "Sub-agents documentation" — the older
     * page's trailing group kept its own first call's key). The orphan is therefore
     * completed IN PLACE with the call's name/preview/input (key, position and group
     * untouched), and the call is dropped from [head]; a head group left short re-keys
     * to its new first row or is removed when emptied — head rows are all new, so no
     * anchor can point at them, and their ids are unique per session. Committed rows
     * matching a head tool id can only be such orphans: the call's message exists in
     * exactly one page, so a complete committed row never collides.
     */
    private fun completeOrphanResults(head: MutableList<ChatItem>) {
        val headCalls = mutableMapOf<String, ChatItem.ToolActivity>()
        head.filterIsInstance<ChatItem.ToolGroup>().forEach { group ->
            group.tools.forEach { headCalls[it.id] = it }
        }
        if (headCalls.isEmpty()) return
        val adopted = mutableSetOf<String>()
        for (index in 0 until liveStart) {
            val group = rows[index] as? ChatItem.ToolGroup ?: continue
            if (group.tools.none { it.id in headCalls }) continue
            rows[index] = group.copy(
                tools = group.tools.map { tool ->
                    val call = headCalls[tool.id] ?: return@map tool
                    adopted += tool.id
                    tool.copy(
                        name = call.name,
                        preview = call.preview,
                        input = call.input,
                        output = tool.output.ifBlank { call.output },
                        args = tool.args ?: call.args,
                        payload = tool.payload ?: ToolPayloads.of(call.name, tool.args ?: call.args),
                    ).enriched(name = call.name, input = call.input, output = tool.output)
                },
            )
        }
        if (adopted.isEmpty()) return
        for (index in head.indices.reversed()) {
            val group = head[index] as? ChatItem.ToolGroup ?: continue
            val kept = group.tools.filterNot { it.id in adopted }
            if (kept.size == group.tools.size) continue
            if (kept.isEmpty()) {
                head.removeAt(index)
            } else {
                head[index] = ChatItem.ToolGroup(kept.first().id, kept)
            }
        }
    }

    /**
     * Ticket 046: a fresh transcript page (the newest turns) replaces committed rows —
     * the transcript-backed twin of [replaceHistory]. Same [keepLive] contract: the
     * sub-agent's in-flight live tail survives, minus the rows the server has since
     * committed. Pagination state resets to the new page ([committedTurnIds] rebuilt);
     * older pages previously prepended are dropped, exactly like a full refetch
     * resetting message pagination (ticket 043 semantics).
     */
    fun replaceTranscript(turns: List<TranscriptTurn>, keepLive: Boolean) {
        committedTurnIds.clear()
        committedTodos = null
        val fresh = mutableListOf<ChatItem>()
        turns.forEach { turn ->
            flatten(fresh, turn)
            committedTurnIds += turn.id
        }
        replaceCommitted(fresh, keepLive)
    }

    /**
     * Ticket 046 pagination: an older transcript page prepends its rows before the
     * loaded ones, deduplicated by turn id ([committedTurnIds]) — a racing
     * [replaceTranscript] can never make a page apply twice. The live tail is
     * untouched: it only ever grows at the newest end.
     */
    fun prependTranscript(turns: List<TranscriptTurn>) {
        val fresh = turns.filter { committedTurnIds.add(it.id) }
        if (fresh.isEmpty()) return
        val head = mutableListOf<ChatItem>()
        fresh.forEach { flatten(head, it) }
        prependCommitted(head)
    }

    /**
     * The cold start's running-turn seed (ticket 176, 144's role — the removed
     * snapshot's `in_flight_turn` is now the newest `GET .../transcript` turn while it
     * runs): the turn's steps/frames become ordinary LIVE rows, so a cold open of a
     * busy session shows the in-flight text, thinking and tool calls (running or
     * already settled) instead of a gap between the last committed message and the live
     * stream. Frames flatten like a transcript page's — consecutive text/thinking frames
     * share one bubble, a tool frame breaks it — and the socket fold is seeded with the
     * same frames, so the replayed/live ops after the page's watermark extend these rows
     * in place (the last bubble absorbs the text deltas, tool events heal their row by
     * call id): no gap, no duplicate. User-role frames (steered-in prompts) and the
     * turn's prompt are left to history and the splice echoes. Whatever history
     * already committed of the turn is pruned from the seed (the ordinary keep-live
     * merge), and the turn-end refetch replaces the rows with the canonical turn.
     */
    fun seedRunningTurn(turn: TranscriptTurn) {
        for (step in turn.steps) {
            var text = ""
            var thinking = ""
            fun flushBubble() {
                if (text.isNotBlank() || thinking.isNotBlank()) {
                    rows += ChatItem.AssistantMessage(id = "live_${liveBubbleCount++}", text = text, thinking = thinking)
                }
                text = ""
                thinking = ""
            }
            for (frame in step.frames) {
                when (frame) {
                    is TranscriptFrame.Thinking ->
                        thinking = listOf(thinking, frame.text).filter { it.isNotBlank() }.joinToString("\n")
                    is TranscriptFrame.Text ->
                        if (frame.role == TranscriptFrame.Text.Role.Assistant) {
                            text = listOf(text, frame.text).filter { it.isNotBlank() }.joinToString("\n")
                        }
                    is TranscriptFrame.Tool -> {
                        flushBubble()
                        if (isTodoCall(frame.toolCallId, frame.name)) {
                            // Same rule as a live TodoList call: no row, the widget mirrors it.
                            todoParser.of(frame.name, frame.input)?.let { liveTodos = it }
                        } else {
                            upsertTool(
                                frame.toolCallId, frame.name, frame.toolStatus(), frame.preview,
                                input = frame.input, output = frame.output, args = frame.args,
                                agentCall = frame.agentCall,
                            )
                            if (!frame.running) linkFromResult(frame.toolCallId, frame.output)
                        }
                    }
                }
            }
            flushBubble()
        }
        pruneCoveredLiveRows()
    }

    /**
     * The cold start's queued prompts (ticket 176, from `GET .../prompts`): each waiting
     * prompt becomes a "Queued #N" bubble exactly like the optimistic echo of a prompt
     * this client queued (ticket 154) — its message cannot be in history before its own
     * turn starts, so the echo registry keeps it across refetches; the turn-start splice
     * heals it in place (same message id) and the prompt's `prompt.upsert` clears the
     * mark. [prompts] are in queue order; a message already shown (history, an echo)
     * keeps its row.
     */
    fun seedQueuedPrompts(prompts: List<ChatMessage>) {
        prompts.forEachIndexed { index, message ->
            appendLocalEcho(
                message.id,
                message.parts.filterIsInstance<ChatMessage.Part.Text>().joinToString("\n") { it.text },
                message.parts.filterIsInstance<ChatMessage.Part.Attachment>(),
                message.parts.filterIsInstance<ChatMessage.Part.Image>(),
            )
            onPromptQueued(message.id, index + 1)
        }
    }

    // --- The committed/live boundary ---------------------------------------------------

    /** A refetch replaces the committed prefix wholesale: [fresh] is canonical. With
     * [keepLive] the live tail survives minus the rows the server has since committed
     * ([pruneCoveredLiveRows] — the ONE live-vs-committed merge); without it the tail
     * is cleared: history is the canonical rendering of the finished turn. The turn-end
     * footer (ticket 155) is then re-appended at the tail — the history page carries no
     * interrupt reason, so the row only lives here. */
    private fun replaceCommitted(fresh: List<ChatItem>, keepLive: Boolean) {
        rows.subList(0, liveStart).clear()
        rows.addAll(0, fresh)
        liveStart = fresh.size
        reapplySubagentLinks()
        if (localEchoes.isNotEmpty()) {
            // Echo survival (ticket 154): an echo whose message this refetch committed
            // is done — the canonical row renders. Any other is re-appended at the
            // committed tail (insertion order = send order): its prompt is still
            // queued or running, so history cannot show it yet.
            val committedIds = fresh.mapTo(mutableSetOf()) { it.id }
            val echoes = localEchoes.iterator()
            while (echoes.hasNext()) {
                val (id, echo) = echoes.next()
                if (id in committedIds) echoes.remove() else appendCommitted(echo)
            }
        }
        if (!keepLive) {
            rows.subList(liveStart, rows.size).clear()
            liveTodos = null
        } else {
            pruneCoveredLiveRows()
        }
        appendTurnEndRow()
    }

    /** Older pages insert before the loaded rows; the boundary shifts with them.
     * Rows the page covers are dropped from the EXISTING timeline first (Crashlytics
     * 159e25351be7042db517f3af684684db): a live-tail zombie — a late/replayed event
     * for a call whose message was not loaded (the "show it anyway" orphan path) —
     * or a replayed old splice would otherwise land a duplicate LazyColumn key the
     * moment its own page prepends. The page is canonical: its rows keep their true
     * position, and future live events heal them in place ([healTool] searches the
     * whole timeline). Page-backed committed rows can never collide — the id sets
     * ([committedMessageIds]/[committedTurnIds]) already filtered them from [head]. */
    private fun prependCommitted(head: List<ChatItem>) {
        dropRowsCoveredBy(head)
        rows.addAll(0, head)
        liveStart += head.size
    }

    /** Drops existing rows whose key a [head] row carries: tool groups lose the
     * covered calls (an emptied group is removed, a shortened one re-keys to its new
     * first row — the [pruneCoveredLiveRows] convention) and whole rows keyed by a
     * covered id are removed. Committed-zone removals shift the boundary with them. */
    private fun dropRowsCoveredBy(head: List<ChatItem>) {
        val headToolIds = mutableSetOf<String>()
        val headRowIds = mutableSetOf<String>()
        head.forEach { item ->
            when (item) {
                is ChatItem.ToolGroup -> item.tools.forEach { headToolIds += it.id }
                // Blank row ids (id-less steer echoes) are not coverage: unrelated
                // messages share them, so dropping on them would eat live rows.
                else -> if (item.id.isNotBlank()) headRowIds += item.id
            }
        }
        if (headToolIds.isEmpty() && headRowIds.isEmpty()) return
        for (index in rows.indices.reversed()) {
            when (val item = rows[index]) {
                is ChatItem.ToolGroup -> {
                    val survivors = item.tools.filterNot { it.id in headToolIds }
                    if (survivors.size == item.tools.size) continue
                    if (survivors.isEmpty()) {
                        rows.removeAt(index)
                        if (index < liveStart) liveStart--
                    } else {
                        rows[index] = ChatItem.ToolGroup(survivors.first().id, survivors)
                    }
                }
                else -> if (item.id in headRowIds) {
                    rows.removeAt(index)
                    if (index < liveStart) liveStart--
                }
            }
        }
    }

    /** Mid-turn commits (splice echoes) land at the committed tail, above the live
     * tail. */
    private fun appendCommitted(item: ChatItem) {
        rows.add(liveStart, item)
        liveStart++
    }

    /** Drops the live rows a refetch has since committed, so [keepLive]-style replaces
     * never show a row twice: tool rows dedup by call id (a half-committed group keeps
     * its survivors under the first one's id), assistant bubbles by exact content. */
    private fun pruneCoveredLiveRows() {
        val committedToolIds = mutableSetOf<String>()
        val committedBubbles = mutableListOf<ChatItem.AssistantMessage>()
        for (index in 0 until liveStart) {
            when (val item = rows[index]) {
                is ChatItem.ToolGroup -> item.tools.forEach { committedToolIds += it.id }
                is ChatItem.AssistantMessage -> committedBubbles += item
                else -> Unit
            }
        }
        for (index in rows.indices.reversed()) {
            if (index < liveStart) break
            when (val item = rows[index]) {
                is ChatItem.ToolGroup -> {
                    val survivors = item.tools.filterNot { it.id in committedToolIds }
                    if (survivors.isEmpty()) {
                        rows.removeAt(index)
                    } else if (survivors.size < item.tools.size) {
                        rows[index] = ChatItem.ToolGroup(survivors.first().id, survivors)
                    }
                }
                is ChatItem.AssistantMessage ->
                    if (committedBubbles.any { it.text == item.text && it.thinking == item.thinking }) {
                        rows.removeAt(index)
                    }
                else -> Unit
            }
        }
    }

    fun apply(event: SessionEvent) {
        when (event) {
            is SessionEvent.TurnStarted -> {
                if (historyBacked) {
                    // Defensive clear: a new turn must never inherit a previous turn's
                    // leftovers (possible only if the earlier turn.ended refetch failed).
                    rows.subList(liveStart, rows.size).clear()
                } else {
                    // Seal the finished internal turn's rows so they keep their place
                    // above the next turn's spliced prompts; transcript refetches
                    // (046) merge under the live tail instead of clearing it.
                    liveStart = rows.size
                }
                // Same clear as the rows: the previous turn's todo calls are committed
                // (history-backed) or irrelevant now (sub-agent page renders no widget).
                liveTodos = null
                // The footer closed the PREVIOUS turn (ticket 155) — a new one starts
                // clean. Removal keeps the committed/live boundary like compactionFailed.
                turnEndLabel = null
                val turnEndIndex = rows.indexOfFirst { it.id == TURN_END_ROW_ID }
                if (turnEndIndex >= 0) {
                    rows.removeAt(turnEndIndex)
                    if (turnEndIndex < liveStart) liveStart--
                }
            }
            is SessionEvent.AssistantDelta -> appendDelta(event.delta, thinking = false)
            is SessionEvent.ThinkingDelta -> appendDelta(event.delta, thinking = true)
            is SessionEvent.ToolCallStarted ->
                if (isTodoCall(event.toolCallId, event.name)) {
                    // The todo state comes from the call's input, not its result.
                    todoParser.of(event.name, event.input)?.let { liveTodos = it }
                } else {
                    upsertTool(
                        event.toolCallId, event.name, ToolStatus.Running, event.preview,
                        input = event.input, args = event.args, agentCall = event.agentCall,
                    )
                }
            is SessionEvent.ToolCallResult ->
                if (event.toolCallId in todoCallIds) Unit
                else {
                    upsertTool(
                        event.toolCallId,
                        "",
                        if (event.isError) ToolStatus.Error else ToolStatus.Done,
                        output = event.output,
                        // The definitive result replaces any accumulated progress wholesale.
                        replaceOutput = true,
                    )
                    linkFromResult(event.toolCallId, event.output)
                }
            is SessionEvent.ContextSpliced -> event.messages.forEach(::appendSpliced)
            // Prompt lifecycle (ticket 154, `prompt.upsert` since 175): the queued mark
            // lands on the optimistic local echo; any other status (running, steered
            // into completion, aborted…) flips it back to the normal look. No-op when
            // the id names no echo (e.g. a prompt queued from another client).
            is SessionEvent.PromptUpdated ->
                if (event.status == PromptStatus.Queued) onPromptQueued(event.promptId, event.queueLength)
                else onPromptLeftQueue(event.promptId)
            // The ViewModel refetches history on top; the sub-agent store's timelines
            // get only this.
            is SessionEvent.TurnEnded -> onTurnEnded(event.interruptReason, event.errorMessage)
            else -> Unit
        }
    }

    /**
     * A terminal turn (ticket 155; `turn.upsert` since 175): a turn that stopped early
     * (any [interruptReason]) first settles its still-running live tool rows to Error
     * — an abort's tool frames may never reach `done`, and the rows must not spin
     * until the refetch lands (this is the sub-agent page's only settle). Then, on the
     * main-chat timeline only, it records WHY the turn stopped — the footer copy, a
     * failed turn's [errorMessage] included — and shows the footer at once, ahead of
     * the ViewModel's canonical refetch (which re-appends it via [replaceCommitted]).
     * A completed turn (no/unknown reason) adds nothing.
     */
    fun onTurnEnded(interruptReason: String?, errorMessage: String? = null) {
        if (interruptReason != null) settleRunningLiveTools()
        if (!historyBacked) return
        turnEndLabel = turnEndCopy(interruptReason, errorMessage)
        appendTurnEndRow()
    }

    /** Running tool rows of the live tail → Error (an interrupted turn's leftovers). */
    private fun settleRunningLiveTools() {
        for (index in liveStart until rows.size) {
            val group = rows[index] as? ChatItem.ToolGroup ?: continue
            if (group.tools.none { it.status == ToolStatus.Running }) continue
            rows[index] = group.copy(
                tools = group.tools.map { if (it.status == ToolStatus.Running) it.copy(status = ToolStatus.Error) else it },
            )
        }
    }

    /** Upserts the footer row at the tail under one constant id (the compaction
     * divider's reset-in-place idiom); no-op while no early stop is recorded. */
    private fun appendTurnEndRow() {
        val label = turnEndLabel ?: return
        val row = ChatItem.TurnEnd(TURN_END_ROW_ID, label)
        val index = rows.indexOfFirst { it.id == TURN_END_ROW_ID }
        if (index >= 0) rows[index] = row else rows += row
    }

    /**
     * `compaction.started` (ticket 056): a pending divider at the tail — or the previous
     * run's done divider reset to pending (one constant id: the server runs one
     * compaction per session at a time, so successive runs reuse the row rather than
     * stacking keys). The row lives in the LIVE tail on purpose: a mid-compaction
     * resync keeps it (busy=true → the keep-live refetch), and a post-completion idle
     * resync drops it for the canonical history-sourced divider the refetch flattens
     * from the persisted summary.
     */
    fun compactionStarted() {
        val pending = ChatItem.CompactionDivider(LIVE_COMPACTION_DIVIDER_ID, pending = true)
        val index = rows.indexOfFirst { it.id == LIVE_COMPACTION_DIVIDER_ID }
        if (index >= 0) rows[index] = pending else rows += pending
    }

    /** `compaction.completed`: the pending divider flips to done with the token delta
     * and the summary. With no pending row (the app subscribed mid-run), the done row
     * is appended directly — the compaction still gets its timeline trace. */
    fun compactionCompleted(summary: String, tokensBefore: Long, tokensAfter: Long) {
        val done = ChatItem.CompactionDivider(
            LIVE_COMPACTION_DIVIDER_ID,
            pending = false,
            tokensBefore = tokensBefore,
            tokensAfter = tokensAfter,
            summary = summary,
        )
        val index = rows.indexOfFirst { it.id == LIVE_COMPACTION_DIVIDER_ID }
        if (index >= 0) rows[index] = done else rows += done
    }

    /** `compaction.failed`/`cancelled`/`blocked`: the pending divider is removed — the
     * error snackbar (ViewModel) carries the feedback instead. The removal keeps the
     * committed/live boundary: a divider sealed into the committed zone (a
     * non-history-backed timeline's turn.started moved it there) shifts [liveStart]
     * with it, or later boundary walks read past the row list. */
    fun compactionFailed() {
        val index = rows.indexOfFirst { it.id == LIVE_COMPACTION_DIVIDER_ID }
        if (index < 0) return
        rows.removeAt(index)
        if (index < liveStart) liveStart--
    }

    /** The just-sent user message arrives spliced mid-turn: commit it immediately so
     * the chat does not look dead for the whole turn. Deduped by message id — a
     * reconnect replay or a racing refetch must not add a second bubble; the
     * turn.ended refetch then replaces committed rows wholesale. Assistant/tool
     * splices are ignored: deltas and history cover them.
     *
     * The steer echo (docs/server-api.md, steer round-trip) splices the steered message
     * without an id: those dedup on the text instead — a racing mid-turn refetch commits
     * the same message with its real id — and get a synthetic key, since two id-less
     * bubbles would otherwise collide as LazyColumn keys. */
    private fun appendSpliced(message: ChatMessage) {
        if (message.role != ChatMessage.Role.User) return
        // The compaction splice (ticket 056) carries the summary as an ID-LESS user
        // message whose text is the model-facing prefix — internal content, dropped
        // outright (web parity). The check must come first: id-less, it would
        // otherwise fall into the steer-echo text dedup and could surface as a bubble.
        if (message.originKind == ORIGIN_KIND_COMPACTION_SUMMARY) return
        if (message.id.isNotBlank()) {
            val index = (0 until liveStart).indexOfFirst { rows[it].id == message.id }
            if (index >= 0) {
                // Dedup — a reconnect replay or a racing refetch must not add a
                // second bubble — with one upgrade (ticket 154): a local echo heals
                // IN PLACE, the server message replacing the optimistic row
                // (canonical content, its timestamp, the queued mark cleared).
                if (message.id in localEchoes) {
                    val healed = mutableListOf<ChatItem>()
                    flatten(healed, message, updateTodos = true)
                    val row = healed.filterIsInstance<ChatItem.UserMessage>().firstOrNull()
                    if (row != null) {
                        rows[index] = row
                        localEchoes[message.id] = row
                    }
                }
                return
            }
            val fresh = mutableListOf<ChatItem>()
            flatten(fresh, message, updateTodos = true)
            fresh.forEach(::appendCommitted)
            return
        }
        val text = message.parts.filterIsInstance<ChatMessage.Part.Text>().joinToString("\n") { it.text }
        val attachments = message.parts.filterIsInstance<ChatMessage.Part.Attachment>()
        val images = message.parts.filterIsInstance<ChatMessage.Part.Image>()
        if (text.isBlank() && attachments.isEmpty() && images.isEmpty()) return
        if ((0 until liveStart).asSequence().map { rows[it] }.filterIsInstance<ChatItem.UserMessage>().any {
                it.text == text && it.attachments == attachments && it.images == images
            }
        ) {
            return
        }
        appendCommitted(ChatItem.UserMessage("spliced_${syntheticSpliceCount++}", text, attachments, images, message.createdAt))
    }

    /**
     * The optimistic echo of a just-sent prompt (ticket 154): committed at once so the
     * bubble shows without waiting for the socket's `context.spliced` — for a prompt
     * queued while the agent is busy that splice arrives only when the queued turn
     * starts. The server message carries the same id (the ACK's `prompt_id` ==
     * `user_message_id` == the splice's message id), so the splice heals this row in
     * place ([appendSpliced]) and the turn-end refetch replaces it with the canonical
     * committed row. [ChatViewModel] drives the mark/clear through [apply].
     *
     * The splice can also BEAT the ACK: the server broadcasts `context.spliced` within
     * milliseconds of writing the POST response, and the warm socket's dispatch can
     * land before the REST continuation (verified on-device 2026-09-25, server 2.1.1 —
     * splice applied 4ms before the ACK's echo, every send crashed on the duplicate
     * LazyColumn key). The row is then already committed: adopt it as this echo's row
     * (queued marks still heal in place, the refetch's echo survival still applies)
     * instead of appending a twin.
     */
    fun appendLocalEcho(
        id: String,
        text: String,
        attachments: List<ChatMessage.Part.Attachment>,
        images: List<ChatMessage.Part.Image>,
    ) {
        if (text.isBlank() && attachments.isEmpty() && images.isEmpty()) return
        val existing = rows.firstOrNull { it.id == id }
        if (existing != null) {
            if (existing is ChatItem.UserMessage) localEchoes[id] = existing
            return
        }
        val echo = ChatItem.UserMessage(id, text, attachments, images)
        localEchoes[id] = echo
        appendCommitted(echo)
    }

    /** A queued prompt (ticket 154): the echo shows its queue position ("Queued #N").
     * A later frame wins. No-op when the id names no local echo (a prompt queued from
     * another client has none). */
    private fun onPromptQueued(promptId: String, queueLength: Int) {
        healLocalEcho(promptId) { it.copy(queued = queueLength) }
    }

    /** The prompt left the queue (running, steered, aborted… — ticket 154): its echo
     * flips back to the normal in-flight look. */
    private fun onPromptLeftQueue(promptId: String) {
        healLocalEcho(promptId) { it.copy(queued = null) }
    }

    /** In-place update of the local echo [promptId] (row and registry together), so a
     * mark/clear survives the refetches that rebuild the committed rows. */
    private fun healLocalEcho(promptId: String, update: (ChatItem.UserMessage) -> ChatItem.UserMessage) {
        val echo = localEchoes[promptId] ?: return
        val healed = update(echo)
        localEchoes[promptId] = healed
        val index = rows.indexOfFirst { it.id == promptId }
        if (index >= 0) rows[index] = healed
    }

    /** Deltas extend the last LIVE bubble; after a tool row a new bubble starts, so a
     * multi-step turn reads text → tool → text like the web client. A committed bubble
     * never absorbs late deltas — they open a fresh live bubble instead. */
    private fun appendDelta(delta: String, thinking: Boolean) {
        val last = rows.lastOrNull()
        if (rows.size > liveStart && last is ChatItem.AssistantMessage) {
            rows[rows.lastIndex] =
                if (thinking) last.copy(thinking = last.thinking + delta) else last.copy(text = last.text + delta)
        } else {
            rows += ChatItem.AssistantMessage(
                id = "live_${liveBubbleCount++}",
                text = if (thinking) "" else delta,
                thinking = if (thinking) delta else "",
            )
        }
    }

    /** The tool-row insertion choke point (ticket 055): [healTool] updates the row
     * wherever it lives — committed prefix included, so a replayed event for a call a
     * refetch just committed updates that row's status/output in place instead of
     * appending a duplicate — and only an unknown id opens a new live row at the tail. */
    private fun upsertTool(
        toolCallId: String,
        name: String,
        status: ToolStatus,
        preview: String = "",
        input: String = "",
        output: String = "",
        replaceOutput: Boolean = false,
        args: JsonObject? = null,
        agentCall: AgentCallDisplay? = null,
    ) {
        var found = false
        healTool(toolCallId) { existing ->
            found = true
            val mergedName = existing.name.ifBlank { name }
            val mergedArgs = existing.args ?: args
            existing.copy(
                name = mergedName,
                preview = existing.preview.ifBlank { preview },
                input = existing.input.ifBlank { input },
                output = if (replaceOutput) output else existing.output.ifBlank { output },
                args = mergedArgs,
                // The typed payload (ticket 119) follows the args' first-writer-wins
                // rule, derived from the row's EFFECTIVE name/args. The spawn row's
                // display (ticket 157) follows the same rule.
                payload = existing.payload ?: ToolPayloads.of(mergedName, mergedArgs),
                agentCall = existing.agentCall ?: agentCall,
                status = status,
            )
        }
        if (!found) {
            appendLiveTool(
                ChatItem.ToolActivity(
                    toolCallId, name, status, preview, input, output,
                    args = args,
                    payload = ToolPayloads.of(name, args),
                    agentCall = agentCall,
                ),
            )
        }
    }

    /** Live row→sub-agent linkage (ticket 045): `subagent.spawned` names the `Agent`
     * call it answers, so the row gains its open affordance. The whole timeline is
     * searched — a mid-turn refetch may have committed the running row already.
     * Single-`Agent` only (ticket 134): an `AgentSwarm` row never carries a
     * subagentId — it fans out many members, and the swarm card navigates by the
     * tool call id it already holds. */
    fun linkSubagentTool(toolCallId: String, subagentId: String) {
        subagentLinks[toolCallId] = subagentId
        healTool(toolCallId) { row ->
            if (row.subagentId == null && row.name == AGENT_TOOL_NAME) row.copy(subagentId = subagentId) else row
        }
    }

    /** Live row linkage (tickets 045/096/097, unified in 098): one [enriched] pass over
     * the row — the result's `agent_id:` marker and `task_id: bash-…` first line, plus
     * the call input's timeout knobs (the row's `input` holds the args by result time).
     * First writer wins, so a spawned/registry-learned sub-agent link survives. The
     * derived sub-agent link also feeds the [subagentLinks] cache: a refetch rebuilds
     * committed rows from history, where the `agent_id:` result marker only exists once
     * the result is committed too. The bash-task link has no registry: the result text
     * is the only carrier and history/transcript repeat it verbatim, so every rebuild
     * re-derives it. */
    private fun linkFromResult(toolCallId: String, output: String) {
        healTool(toolCallId) { row ->
            val enriched = row.enriched(output = output)
            enriched.subagentId?.let { subagentLinks[toolCallId] = it }
            enriched
        }
    }

    /** Re-applies live-learned links after a refetch rebuilt the committed rows.
     * Single-`Agent` only, same guard as [linkSubagentTool] (ticket 134). */
    private fun reapplySubagentLinks() {
        for (index in 0 until liveStart) {
            val group = rows[index] as? ChatItem.ToolGroup ?: continue
            if (group.tools.none { it.subagentId == null && it.name == AGENT_TOOL_NAME && it.id in subagentLinks }) continue
            rows[index] = group.copy(
                tools = group.tools.map { tool ->
                    if (tool.subagentId == null && tool.name == AGENT_TOOL_NAME) {
                        tool.copy(subagentId = subagentLinks[tool.id])
                    } else {
                        tool
                    }
                },
            )
        }
    }

    /** Records a call as the agent's todo tool (ticket 042): its row is suppressed and
     * later result/progress frames for the id are dropped too. */
    private fun isTodoCall(toolCallId: String, name: String): Boolean {
        if (name != TodoParser.TOOL_NAME) return false
        todoCallIds += toolCallId
        return true
    }

    /**
     * Flattens one history message into [target] rows. [updateTodos] gates the
     * [committedTodos] write: [replaceHistory] re-derives the widget from the newest
     * page, while [prependHistory] must not let an older page's call regress it. Todo
     * row suppression itself is unconditional — todo calls never become rows.
     */
    private fun flatten(target: MutableList<ChatItem>, message: ChatMessage, updateTodos: Boolean) {
        when (message.role) {
            ChatMessage.Role.User -> {
                val text = message.parts.filterIsInstance<ChatMessage.Part.Text>().joinToString("\n") { it.text }
                val attachments = message.parts.filterIsInstance<ChatMessage.Part.Attachment>()
                val images = message.parts.filterIsInstance<ChatMessage.Part.Image>()
                if (message.originKind == ORIGIN_KIND_COMPACTION_SUMMARY) {
                    // The persisted compaction summary (ticket 056): a done divider
                    // holding the bare summary — never a user bubble (web parity).
                    // The token numbers are not persisted, so the label goes without.
                    target += ChatItem.CompactionDivider(message.id, pending = false, summary = text)
                } else if (text.isNotBlank() || attachments.isNotEmpty() || images.isNotEmpty()) {
                    target += ChatItem.UserMessage(message.id, text, attachments, images, message.createdAt)
                }
            }
            ChatMessage.Role.Assistant -> {
                val text = message.parts.filterIsInstance<ChatMessage.Part.Text>().joinToString("\n") { it.text }
                val thinking = message.parts.filterIsInstance<ChatMessage.Part.Thinking>().joinToString("\n") { it.text }
                if (text.isNotBlank() || thinking.isNotBlank()) {
                    target += ChatItem.AssistantMessage(message.id, text, thinking)
                }
                message.parts.filterIsInstance<ChatMessage.Part.ToolUse>().forEach {
                    if (isTodoCall(it.toolCallId, it.toolName)) {
                        if (updateTodos) {
                            todoParser.of(it.toolName, it.input)?.let { parsed -> committedTodos = parsed }
                        }
                    } else {
                        target.appendTool(
                            ChatItem.ToolActivity(
                                it.toolCallId, it.toolName, ToolStatus.Running, it.preview, it.input,
                                args = it.args,
                                payload = ToolPayloads.of(it.toolName, it.args),
                            ),
                        )
                    }
                }
            }
            ChatMessage.Role.Tool ->
                message.parts.filterIsInstance<ChatMessage.Part.ToolResult>().forEach { result ->
                    if (result.toolCallId in todoCallIds) return@forEach
                    val status = if (result.isError) ToolStatus.Error else ToolStatus.Done
                    var found = false
                    target.updateTool(result.toolCallId) { existing ->
                        found = true
                        existing.copy(
                            status = status,
                            output = existing.output.ifBlank { result.output },
                        ).enriched(output = result.output)
                    }
                    if (!found) {
                        // Result without a matching call (e.g. page boundary): show it anyway.
                        target.appendTool(ChatItem.ToolActivity(result.toolCallId, "", status, output = result.output))
                    }
                }
            ChatMessage.Role.Other -> Unit
        }
    }

    /**
     * Flattens one transcript turn into [target] rows (ticket 046): the turn's prompt
     * (user-origin turns only) becomes a user bubble; within each step, consecutive
     * thinking/text frames accumulate into one assistant bubble (joined "\n" like the
     * message-history flatten) and tool frames break bubbles into their own grouped
     * rows — mirroring how live deltas and tool rows interleave. The sub-agent's own
     * todo tool calls are suppressed exactly like the live path (no widget renders
     * them). Row ids are the server frame/turn ids, so [prependTranscript] pages and
     * refetches dedup cleanly.
     */
    private fun flatten(target: MutableList<ChatItem>, turn: TranscriptTurn) {
        turn.prompt?.let { prompt ->
            target += ChatItem.UserMessage("${turn.id}.prompt", prompt)
        }
        for (step in turn.steps) {
            var text = ""
            var thinking = ""
            var bubbleId: String? = null
            fun flushBubble() {
                val id = bubbleId ?: return
                if (text.isNotBlank() || thinking.isNotBlank()) {
                    target += ChatItem.AssistantMessage(id, text, thinking)
                }
                text = ""
                thinking = ""
                bubbleId = null
            }
            for (frame in step.frames) {
                when (frame) {
                    is TranscriptFrame.Thinking -> {
                        if (bubbleId == null) bubbleId = frame.id
                        thinking = listOf(thinking, frame.text).filter { it.isNotBlank() }.joinToString("\n")
                    }
                    is TranscriptFrame.Text ->
                        if (frame.role == TranscriptFrame.Text.Role.User) {
                            flushBubble()
                            target += ChatItem.UserMessage(frame.id, frame.text)
                        } else {
                            if (bubbleId == null) bubbleId = frame.id
                            text = listOf(text, frame.text).filter { it.isNotBlank() }.joinToString("\n")
                        }
                    is TranscriptFrame.Tool -> {
                        flushBubble()
                        if (isTodoCall(frame.toolCallId, frame.name)) {
                            todoParser.of(frame.name, frame.input)?.let { parsed -> committedTodos = parsed }
                        } else {
                            // The same enrichment as every other row path (ticket 098)
                            // — transcript frames carry input and output in one frame.
                            target.appendTool(
                                ChatItem.ToolActivity(
                                    frame.toolCallId,
                                    frame.name,
                                    frame.toolStatus(),
                                    frame.preview,
                                    frame.input,
                                    frame.output,
                                    args = frame.args,
                                    payload = ToolPayloads.of(frame.name, frame.args),
                                ).enriched(),
                            )
                        }
                    }
                }
            }
            flushBubble()
        }
    }

    /** Appends [tool] to the trailing LIVE group, or opens a new one when the tail is
     * not a live tool run — a non-tool row between two calls splits them into two
     * groups (ticket 037), and a committed group never absorbs a new live call (one
     * row must not span the boundary, ticket 055). The group id stays the first call's
     * id, so a growing live group keeps its LazyColumn key. */
    private fun appendLiveTool(tool: ChatItem.ToolActivity) {
        val last = rows.lastOrNull()
        if (rows.size > liveStart && last is ChatItem.ToolGroup) {
            rows[rows.lastIndex] = last.copy(tools = last.tools + tool)
        } else {
            rows += ChatItem.ToolGroup(tool)
        }
    }

    /** In-place update of the row [toolCallId] inside whichever group holds it,
     * searched from the tail across the WHOLE timeline (a result matches its most
     * recent call). The one tool-row lookup (ticket 055): committed rows heal too, so
     * a late/replayed event never appends a twin of an already-committed row. No-op
     * when the id is unknown. */
    private fun healTool(
        toolCallId: String,
        update: (ChatItem.ToolActivity) -> ChatItem.ToolActivity,
    ) {
        for (groupIndex in rows.indices.reversed()) {
            val group = rows[groupIndex] as? ChatItem.ToolGroup ?: continue
            val toolIndex = group.tools.indexOfFirst { it.id == toolCallId }
            if (toolIndex < 0) continue
            val tools = group.tools.toMutableList()
            tools[toolIndex] = update(tools[toolIndex])
            rows[groupIndex] = group.copy(tools = tools)
            return
        }
    }

    /** Appends [tool] to the trailing group of a fresh page being flattened, or opens
     * a new one — a non-tool row between two calls splits them into two groups
     * (ticket 037). The group id stays the first call's id. */
    private fun MutableList<ChatItem>.appendTool(tool: ChatItem.ToolActivity) {
        val last = lastOrNull()
        if (last is ChatItem.ToolGroup) {
            this[lastIndex] = last.copy(tools = last.tools + tool)
        } else {
            this += ChatItem.ToolGroup(tool)
        }
    }

    /** In-place update of the row [toolCallId] inside a fresh page being flattened,
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

/** A transcript tool frame's row status: running, else error (2.5.0's `error` state)
 * or done. */
private fun TranscriptFrame.Tool.toolStatus(): ToolStatus = when {
    running -> ToolStatus.Running
    failed -> ToolStatus.Error
    else -> ToolStatus.Done
}

/**
 * The ONE tool-row enrichment (ticket 098 — the root fix for transcript-backed rows
 * losing their links): every [ChatItem.ToolActivity] field computable from the call's
 * name/input and a result text is derived HERE — [ChatItem.ToolActivity.bashTaskId]
 * from the result's `task_id: bash-…` first line, [ChatItem.ToolActivity.agentTaskId]
 * from the `task_id: agent-…` first line of a background spawn's result (ticket 157),
 * [ChatItem.ToolActivity.subagentId] from the `agent_id:` result marker,
 * [ChatItem.ToolActivity.resumeHint]/[ChatItem.ToolActivity.nextStep] from the
 * result's continuation blocks (ticket 158),
 * [ChatItem.ToolActivity.timeoutSeconds]/[ChatItem.ToolActivity.timeoutDisabled]
 * from the call input's timeout knobs. All
 * four row construction/update sites in [ChatTimeline] route through it (the history
 * flatten's result branch, the transcript-turn flatten, the live `ToolCallResult`
 * handling, and [ChatTimeline]'s orphan completion), so no row path can forget a
 * derivation. First writer wins — an already-set field is never overwritten, so a
 * live/registry-learned link survives any later refetch-derived pass.
 */
internal fun ChatItem.ToolActivity.enriched(
    name: String = this.name,
    input: String = this.input,
    output: String = this.output,
): ChatItem.ToolActivity {
    val timeout = bashTimeoutOf(name, input)
    return copy(
        subagentId = subagentId ?: agentResultSubagentId(name, output),
        bashTaskId = bashTaskId ?: bashTaskIdOf(name, output),
        agentTaskId = agentTaskId ?: agentTaskIdOf(name, output),
        resumeHint = resumeHint ?: agentResumeHintOf(name, output),
        nextStep = nextStep ?: agentNextStepOf(name, output),
        timeoutSeconds = timeoutSeconds ?: timeout?.seconds,
        timeoutDisabled = timeoutDisabled || (timeout?.disabled == true),
    )
}

/** Line prefix of the linkage marker in an `Agent` call's result text. */
private const val AGENT_ID_PREFIX = "agent_id: "

/** The turn-end footer's copy per the 0.31.1 `turn.ended.interruptReason`
 * (docs/server-api.md — ticket 155): a user abort reads "Cancelled" (the copy every
 * early stop wore before the reason existed); the server's own stops say which. Null
 * — absent or unknown, the app's ignore-unknown rule — means no footer: today's
 * behavior for a turn that simply completed. */
internal fun turnEndCopy(interruptReason: String?, errorMessage: String? = null): String? = when (interruptReason) {
    "user_cancelled" -> "Cancelled"
    "aborted" -> "Aborted"
    "max_steps" -> "Stopped: max steps"
    // A failed turn's `error` text rides the footer (ticket 175 — the standalone
    // `error` frame is gone).
    "error" -> errorMessage?.takeIf { it.isNotBlank() }?.let { "Stopped: error — $it" } ?: "Stopped: error"
    "filtered" -> "Stopped: filtered"
    "blocked" -> "Stopped: blocked"
    else -> null
}

/** The `agent_id:` linkage marker of an `Agent` call's result text (the version-pinned
 * wire format — first line for foreground runs, after the task fields for background
 * ones); null for non-`Agent` calls and unmarked results. This is the only sub-agent
 * linkage available when the spawn happened before the app subscribed; a result
 * without the marker leaves the row unlinked, hence non-tappable. */
private fun agentResultSubagentId(name: String, output: String): String? =
    if (name != AGENT_TOOL_NAME) null
    else output.lineSequence()
        .firstOrNull { it.startsWith(AGENT_ID_PREFIX) }
        ?.removePrefix(AGENT_ID_PREFIX)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
