package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.ToolPayload

/** Near-bottom slop: a fling that stops a few pixels above the end still counts
 * as "at the bottom" for auto-scroll purposes (ticket 030). */
private val AutoScrollThreshold = 32.dp

/** Pins the viewport to the very end of the list: [scrollToItem] tops the row
 * at the viewport start, so a last row taller than the viewport (long streaming
 * bubble) still hides its tail — the overshoot scrollBy lands the tail exactly
 * at the viewport end. Shared by the at-bottom re-pin and the explicit
 * send-intent jump (ticket 051) so the overshoot math lives in one place. */
internal suspend fun LazyListState.scrollToBottom(itemCount: Int) {
    if (itemCount <= 0) return
    scrollToItem(itemCount - 1)
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull { it.index == itemCount - 1 }
    if (last != null) {
        val overshoot = (last.offset + last.size - info.viewportEndOffset).toFloat()
        if (overshoot > 0f) scrollBy(overshoot)
    }
}

@Composable
internal fun MessageList(
    items: List<ChatItem>,
    modifier: Modifier = Modifier,
    // Open at the newest row: the initial index is evaluated once, at the first
    // composition, when the history load is already in `items`.
    listState: LazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = items.lastIndex.coerceAtLeast(0),
    ),
    onImageClick: (ChatMessage.Part.Image) -> Unit = {},
    onOpenAttachment: (ChatMessage.Part.Attachment) -> Unit = {},
    loadImageBytes: suspend (ChatMessage.Part.Image) -> ByteArray? = { null },
    /** Invoked with the subagentId when a linked `Agent` tool row is tapped (ticket 045). */
    onOpenSubagent: (String) -> Unit = {},
    /** Invoked with the taskId when a linked background `Bash` tool row is tapped
     * (ticket 096) — the row's `task_id: bash-…` stub navigates to the job screen. */
    onOpenJob: (String) -> Unit = {},
    /** Ticket 119: an `Edit` row with a parsed payload opens the unified diff screen
     * instead of expanding — the tap carries the payload for the ToolContentStore
     * hand-off. */
    onOpenEditDiff: (toolCallId: String, payload: ToolPayload.Edit) -> Unit = { _, _ -> },
    /** Ticket 120: a `Read`/`Write` row with a parsed payload opens the file content
     * screen instead of expanding — the tap carries the payload and the call's output
     * snapshot for the ToolContentStore hand-off. */
    onOpenFileContent: (toolCallId: String, payload: ToolPayload, output: String) -> Unit = { _, _, _ -> },
    /** Tickets 088/089: any `AskUserQuestion` row tap (the row id is the tool call id;
     * since 0.38.0 the pending question carries it as a distinct field next to its
     * question id — ticket 106). The pending row toggles the live card; a resolved
     * row opens or closes its read-only view. Question rows never expand — the card
     * is the details. */
    onQuestionRowTapped: (toolCallId: String, input: String, output: String) -> Unit = { _, _, _ -> },
    /** toolCallId → linked member states of the session's `AgentSwarm` calls
     * (ticket 134; the states themselves since 135 — the card's aggregate reads
     * them); a call with a non-empty list renders as the swarm card. */
    swarmMembers: Map<String, List<SubagentStreamStore.SubagentState>> = emptyMap(),
    /** Invoked with the toolCallId when a swarm card is tapped (ticket 134). */
    onOpenSwarm: (String) -> Unit = {},
    /** Top-anchored history pagination (ticket 046 on the sub-agent page; the 043
     * mechanics): a small overlay spinner shows while an older page loads and
     * [onHistoryTopReached] fires when the scroll position nears the top. Prepending
     * keeps the scroll position — LazyColumn re-anchors on the first visible item's
     * stable key. */
    isLoadingMoreHistory: Boolean = false,
    onHistoryTopReached: () -> Unit = {},
    /** Explicit "go to the newest" intent (ticket 051): ChatScreen's send wrapper
     * bumps this tick; the jump to the tail fires regardless of the current
     * at-bottom — a scrolled-up reader who sends wants the newest. Once the tail
     * is visible `isAtBottom` flips true, so the echo and the streaming reply
     * re-pin through the items effect below with no extra machinery. */
    scrollToBottomTick: Int = 0,
) {
    // No reverseLayout: a top-anchored list keeps a growing row's header in place
    // and pushes the rows below down, so expanded tool details unfold downward
    // with no compensating scroll (the reverseLayout compensation needed a scroll
    // during the layout pass, which crashes with "performMeasureAndLayout called
    // during measure layout"). The price is that LazyList does NOT pin the
    // viewport to the newest row: with stable keys it re-anchors the scroll
    // position to the key of the first visible item, so any tail insertion (tool
    // row, new bubble) or growth of the streaming bubble silently detaches the
    // follow (ticket 030). Track at-bottom continuously and re-pin on every
    // emission; a scrolled-up reader is never yanked.
    val thresholdPx = with(LocalDensity.current) { AutoScrollThreshold.toPx() }
    var isAtBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        // The viewport end is tracked alongside at-bottom: a shrink (IME opening
        // under adjustResize, approval card growing the bottom bar) moves the
        // viewport end up while the key anchor holds the scroll offset, so the
        // tail slides out of view and the positional at-bottom flips false —
        // silently detaching the follow (ticket 050).
        var previousViewportEnd: Int? = null
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            // At the bottom when the final row is visible and its tail overshoots
            // the viewport end by no more than the slop (or the list is empty).
            val atBottom = last == null ||
                (last.index == info.totalItemsCount - 1 &&
                    last.offset + last.size - info.viewportEndOffset <= thresholdPx)
            atBottom to info.viewportEndOffset
        }.collect { (atBottom, viewportEnd) ->
            // Re-pin on shrink only, using the PRE-shrink at-bottom (the fresh
            // value is already false); growth keeps the pin by itself. The IME
            // inset animates over several frames, so one scrollBy per frame
            // slides the conversation up with the keyboard for free.
            val previousEnd = previousViewportEnd
            if (previousEnd != null && viewportEnd < previousEnd && isAtBottom) {
                listState.scrollBy((previousEnd - viewportEnd).toFloat())
            }
            previousViewportEnd = viewportEnd
            isAtBottom = atBottom
        }
    }
    LaunchedEffect(items) {
        // Reads the pre-emission value: this effect runs after composition apply,
        // before the layout pass that would move the last row off the viewport
        // end. Keyed on `items`, not `items.size`: streaming growth of the last
        // bubble changes the content without changing the count.
        if (isAtBottom && items.isNotEmpty()) {
            listState.scrollToBottom(items.size)
        }
    }
    LaunchedEffect(scrollToBottomTick) {
        if (scrollToBottomTick > 0) listState.scrollToBottom(items.size)
    }
    // History pagination trigger: near the top means the first (or second) row is
    // visible. The ViewModel guards re-triggers (in-flight Job, has_more), so the
    // plain derivedStateOf edge is enough — including a list shorter than the
    // viewport, which is permanently "near the top" until the server says stop.
    val nearTop by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(nearTop) {
        if (nearTop) onHistoryTopReached()
    }
    // The loading spinner is an OVERLAY, never a list row (Louis's on-device report,
    // 2026-07-29): a removable header row becomes the first visible row when a top
    // fling overshoots into it, and LazyColumn anchors the scroll position on the
    // first visible row's key — removing the header in the same update that prepends
    // the page destroyed the anchor and reset the scroll to the top of the new page.
    // With no removable rows, the native key anchoring pins the message being read
    // across every prepend.
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Heterogeneous rows: the content type lets LazyColumn reuse composition
            // only across rows of the same bubble/card type.
            items(items, key = ChatItem::id, contentType = { it::class }) { item ->
                when (item) {
                    is ChatItem.UserMessage ->
                        UserBubble(
                            item,
                            onImageClick = onImageClick,
                            onOpenAttachment = onOpenAttachment,
                            loadImageBytes = loadImageBytes,
                        )
                    is ChatItem.AssistantMessage -> AssistantBubble(item)
                    is ChatItem.ToolGroup ->
                        ToolGroupCard(
                            item, onOpenSubagent, onOpenJob, onQuestionRowTapped, onOpenEditDiff, onOpenFileContent,
                            swarmMembers, onOpenSwarm,
                        )
                    is ChatItem.CompactionDivider -> CompactionDividerRow(item)
                    is ChatItem.TurnEnd -> TurnEndFooter(item.label)
                    // Bare rows are not produced by ChatTimeline anymore (ticket 037) but
                    // stay renderable for tests and previews.
                    is ChatItem.ToolActivity ->
                        ToolRow(
                            item, onOpenSubagent, onOpenJob, onQuestionRowTapped, onOpenEditDiff, onOpenFileContent,
                            swarmMembers, onOpenSwarm,
                        )
                }
            }
        }
        if (isLoadingMoreHistory) {
            CircularProgressIndicator(
                Modifier.align(Alignment.TopCenter).padding(4.dp).size(20.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}
