package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

/** Near-bottom slop: a fling that stops a few pixels above the end still counts
 * as "at the bottom" for auto-scroll purposes (ticket 030). */
private val AutoScrollThreshold = 32.dp

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
    /** Top-anchored history pagination (ticket 046 on the sub-agent page; the 043
     * mechanics): a small header spinner shows while an older page loads and
     * [onHistoryTopReached] fires when the scroll position nears the top. Prepending
     * keeps the scroll position — LazyColumn re-anchors on the first visible item's
     * stable key. */
    isLoadingMoreHistory: Boolean = false,
    onHistoryTopReached: () -> Unit = {},
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
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            // At the bottom when the final row is visible and its tail overshoots
            // the viewport end by no more than the slop (or the list is empty).
            last == null ||
                (last.index == info.totalItemsCount - 1 &&
                    last.offset + last.size - info.viewportEndOffset <= thresholdPx)
        }.collect { isAtBottom = it }
    }
    LaunchedEffect(items) {
        // Reads the pre-emission value: this effect runs after composition apply,
        // before the layout pass that would move the last row off the viewport
        // end. Keyed on `items`, not `items.size`: streaming growth of the last
        // bubble changes the content without changing the count.
        if (isAtBottom && items.isNotEmpty()) {
            listState.scrollToItem(items.lastIndex)
            // scrollToItem tops the row at the viewport start; a last row taller
            // than the viewport (long streaming bubble) still hides its tail.
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull { it.index == items.lastIndex }
            if (last != null) {
                val overshoot = (last.offset + last.size - info.viewportEndOffset).toFloat()
                if (overshoot > 0f) listState.scrollBy(overshoot)
            }
        }
    }
    // History pagination trigger: near the top means the first (or second) row is
    // visible. The ViewModel guards re-triggers (in-flight Job, has_more), so the
    // plain derivedStateOf edge is enough — including a list shorter than the
    // viewport, which is permanently "near the top" until the server says stop.
    val nearTop by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(nearTop) {
        if (nearTop) onHistoryTopReached()
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isLoadingMoreHistory) {
            item(key = "history_loading", contentType = "history_loading") {
                Box(Modifier.fillMaxWidth().padding(4.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
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
                is ChatItem.ToolGroup -> ToolGroupCard(item, onOpenSubagent)
                // Bare rows are not produced by ChatTimeline anymore (ticket 037) but
                // stay renderable for tests and previews.
                is ChatItem.ToolActivity -> ToolRow(item, onOpenSubagent)
            }
        }
    }
}
