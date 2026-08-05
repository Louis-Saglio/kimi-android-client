package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.AGENT_TOOL_NAME
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.ORIGIN_KIND_COMPACTION_SUMMARY
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.Todo
import fr.louissaglio.kimi.android.data.chat.TodoParser
import fr.louissaglio.kimi.android.data.chat.TranscriptFrame
import fr.louissaglio.kimi.android.data.chat.TranscriptTurn
import fr.louissaglio.kimi.android.data.chat.bashTaskIdOf
import fr.louissaglio.kimi.android.data.chat.bashTimeoutOf

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

    /** Tool-call id → linked sub-agent (ticket 045), learned live (spawned, or a
     * background run's early result) and re-driven from the store's session link
     * registry by ChatViewModel after every store-affecting apply (ticket 091 — the
     * store owns the links; this map is the timeline's re-application cache, never
     * pruned): a refetch rebuilds committed rows from history, where the `agent_id:`
     * result marker only exists once the result is committed too. */
    private val subagentLinks = mutableMapOf<String, String>()

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
                    val timeout = bashTimeoutOf(call.name, call.input)
                    tool.copy(
                        name = call.name,
                        preview = call.preview,
                        input = call.input,
                        output = tool.output.ifBlank { call.output },
                        subagentId = tool.subagentId ?: agentResultSubagentId(call.name, tool.output),
                        bashTaskId = tool.bashTaskId ?: bashTaskIdOf(call.name, tool.output),
                        timeoutSeconds = tool.timeoutSeconds ?: timeout?.seconds,
                        timeoutDisabled = tool.timeoutDisabled || (timeout?.disabled == true),
                    )
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

    // --- The committed/live boundary ---------------------------------------------------

    /** A refetch replaces the committed prefix wholesale: [fresh] is canonical. With
     * [keepLive] the live tail survives minus the rows the server has since committed
     * ([pruneCoveredLiveRows] — the ONE live-vs-committed merge); without it the tail
     * is cleared: history is the canonical rendering of the finished turn. */
    private fun replaceCommitted(fresh: List<ChatItem>, keepLive: Boolean) {
        rows.subList(0, liveStart).clear()
        rows.addAll(0, fresh)
        liveStart = fresh.size
        reapplySubagentLinks()
        if (!keepLive) {
            rows.subList(liveStart, rows.size).clear()
            liveTodos = null
            return
        }
        pruneCoveredLiveRows()
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
            }
            is SessionEvent.AssistantDelta -> appendDelta(event.delta, thinking = false)
            is SessionEvent.ThinkingDelta -> appendDelta(event.delta, thinking = true)
            is SessionEvent.ToolCallDelta ->
                if (isTodoCall(event.toolCallId, event.name)) Unit
                else upsertTool(event.toolCallId, event.name, ToolStatus.Running)
            is SessionEvent.ToolCallStarted ->
                if (isTodoCall(event.toolCallId, event.name)) {
                    // The todo state comes from the call's input, not its result.
                    todoParser.of(event.name, event.input)?.let { liveTodos = it }
                } else {
                    upsertTool(event.toolCallId, event.name, ToolStatus.Running, event.preview, input = event.input)
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
                    linkSubagentFromResult(event.toolCallId, event.output)
                    linkBashTaskFromResult(event.toolCallId, event.output)
                }
            is SessionEvent.ToolCallProgress ->
                if (event.toolCallId !in todoCallIds) appendToolProgress(event.toolCallId, event.chunk)
            is SessionEvent.ContextSpliced -> event.messages.forEach(::appendSpliced)
            // TurnEnded: the ViewModel refetches history instead.
            else -> Unit
        }
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
            if ((0 until liveStart).any { rows[it].id == message.id }) return
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

    /** Live output streaming (ticket 025): chunks append to the matching *running* row
     * wherever it lives — a reconnect replay can lag the refetch that committed the
     * call (ticket 055). Progress is volatile — it never creates a row (unknown ids
     * are dropped), a finished call keeps its definitive result, and a history refetch
     * replaces the rows wholesale, clearing whatever accumulated. */
    private fun appendToolProgress(toolCallId: String, chunk: String) {
        healTool(toolCallId) { existing ->
            if (existing.status == ToolStatus.Running) existing.copy(output = existing.output + chunk) else existing
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
    ) {
        var found = false
        healTool(toolCallId) { existing ->
            found = true
            existing.copy(
                name = existing.name.ifBlank { name },
                preview = existing.preview.ifBlank { preview },
                input = existing.input.ifBlank { input },
                output = if (replaceOutput) output else existing.output.ifBlank { output },
                status = status,
            )
        }
        if (!found) appendLiveTool(ChatItem.ToolActivity(toolCallId, name, status, preview, input, output))
    }

    /** Live row→sub-agent linkage (ticket 045): `subagent.spawned` names the `Agent`
     * call it answers, so the row gains its open affordance. The whole timeline is
     * searched — a mid-turn refetch may have committed the running row already. */
    fun linkSubagentTool(toolCallId: String, subagentId: String) {
        subagentLinks[toolCallId] = subagentId
        healTool(toolCallId) { row -> if (row.subagentId == null) row.copy(subagentId = subagentId) else row }
    }

    /** Cold row→sub-agent linkage (ticket 045): an `Agent` call's result text carries
     * `agent_id: <id>` on its own line (the version-pinned wire format — first line for
     * foreground runs, after the task fields for background ones). This is the only
     * linkage available when the spawn happened before the app subscribed; a result
     * without the marker leaves the row unlinked, hence non-tappable. */
    private fun linkSubagentFromResult(toolCallId: String, output: String) {
        healTool(toolCallId) { row ->
            val linked = row.subagentId ?: agentResultSubagentId(row.name, output)
            if (linked != null) subagentLinks[toolCallId] = linked
            row.copy(subagentId = linked)
        }
    }

    /** Live row→job linkage (ticket 096): a background Bash result's first line
     * carries `task_id: bash-…`, so the row gains its open affordance. Unlike the
     * sub-agent links there is no registry: the result text is the only carrier and
     * history repeats it verbatim, so every rebuild re-derives the link. The call
     * input's timeout knobs (ticket 097) ride the same heal — the row's `input`
     * holds the args by result time. */
    private fun linkBashTaskFromResult(toolCallId: String, output: String) {
        healTool(toolCallId) { row ->
            val timeout = bashTimeoutOf(row.name, row.input)
            row.copy(
                bashTaskId = row.bashTaskId ?: bashTaskIdOf(row.name, output),
                timeoutSeconds = row.timeoutSeconds ?: timeout?.seconds,
                timeoutDisabled = row.timeoutDisabled || (timeout?.disabled == true),
            )
        }
    }

    /** Re-applies live-learned links after a refetch rebuilt the committed rows. */
    private fun reapplySubagentLinks() {
        for (index in 0 until liveStart) {
            val group = rows[index] as? ChatItem.ToolGroup ?: continue
            if (group.tools.none { it.subagentId == null && it.id in subagentLinks }) continue
            rows[index] = group.copy(
                tools = group.tools.map { tool ->
                    if (tool.subagentId == null) tool.copy(subagentId = subagentLinks[tool.id]) else tool
                },
            )
        }
    }

    private fun agentResultSubagentId(name: String, output: String): String? =
        if (name != AGENT_TOOL_NAME) null
        else output.lineSequence()
            .firstOrNull { it.startsWith(AGENT_ID_PREFIX) }
            ?.removePrefix(AGENT_ID_PREFIX)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

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
                            ChatItem.ToolActivity(it.toolCallId, it.toolName, ToolStatus.Running, it.preview, it.input),
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
                        val timeout = bashTimeoutOf(existing.name, existing.input)
                        existing.copy(
                            status = status,
                            output = existing.output.ifBlank { result.output },
                            subagentId = existing.subagentId ?: agentResultSubagentId(existing.name, result.output),
                            bashTaskId = existing.bashTaskId ?: bashTaskIdOf(existing.name, result.output),
                            timeoutSeconds = existing.timeoutSeconds ?: timeout?.seconds,
                            timeoutDisabled = existing.timeoutDisabled || (timeout?.disabled == true),
                        )
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
                            target.appendTool(
                                ChatItem.ToolActivity(
                                    frame.toolCallId,
                                    frame.name,
                                    if (frame.running) ToolStatus.Running else ToolStatus.Done,
                                    frame.preview,
                                    frame.input,
                                    frame.output,
                                ),
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

    private companion object {
        /** Line prefix of the linkage marker in an `Agent` call's result text. */
        const val AGENT_ID_PREFIX = "agent_id: "
    }
}
