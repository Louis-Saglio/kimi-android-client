package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Auto-scroll regression tests (ticket 030): a tail insertion (tool row, new
 * bubble) must not detach the bottom pin, and must not yank a scrolled-up reader.
 * The list is rendered in a fixed-height box so 20 one-line bubbles overflow it. */
@RunWith(AndroidJUnit4::class)
class ChatAutoScrollTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun messages(n: Int): List<ChatItem> =
        (1..n).map {
            ChatItem.AssistantMessage(
                "msg_$it",
                "Message $it with enough text to give the bubble some height.",
                "",
            )
        }

    private fun toolCall(id: String) =
        ChatItem.ToolActivity(id, "Bash", ToolStatus.Running, preview = "ls")

    private fun messageList(
        items: State<List<ChatItem>>,
        listState: LazyListState,
        height: State<Dp>,
        tick: State<Int> = mutableStateOf(0),
    ) {
        composeTestRule.setContent {
            KimiTheme {
                Box(Modifier.height(height.value)) {
                    MessageList(items.value, listState = listState, scrollToBottomTick = tick.value)
                }
            }
        }
    }

    /** The final row is fully on screen and its tail sits at the viewport end
     * (within the auto-scroll slop plus the 8dp end padding). */
    private fun assertPinnedAtBottom(listState: LazyListState, itemCount: Int) {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.last()
        assertEquals(itemCount - 1, last.index)
        val gapToEnd = info.viewportEndOffset - (last.offset + last.size)
        val maxGapPx = with(composeTestRule.density) { (32.dp + 8.dp).toPx() }
        assertTrue("tail is $gapToEnd px from the viewport end", gapToEnd in 0..maxGapPx.toInt())
    }

    @Test
    fun pinnedAtBottomStaysPinnedWhenItemsAreAppended() {
        val items = mutableStateOf(messages(20))
        val listState = LazyListState()
        messageList(items, listState, mutableStateOf(300.dp))
        composeTestRule.waitForIdle()
        assertPinnedAtBottom(listState, 20)

        items.value = items.value + toolCall("tool_1")
        composeTestRule.waitForIdle()

        // Pre-fix, the key-based re-anchor left the viewport on the old newest
        // row, so this assertion was RED.
        assertPinnedAtBottom(listState, 21)
    }

    @Test
    fun scrolledUpReaderIsNotYankedWhenItemsAreAppended() {
        val items = mutableStateOf(messages(20))
        val listState = LazyListState()
        messageList(items, listState, mutableStateOf(300.dp))
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { runBlocking { listState.scrollToItem(5) } }
        composeTestRule.waitForIdle()
        val anchorKey = listState.layoutInfo.visibleItemsInfo.first().key

        items.value = items.value + toolCall("tool_1")
        composeTestRule.waitForIdle()

        assertEquals(anchorKey, listState.layoutInfo.visibleItemsInfo.first().key)
    }

    @Test
    fun pinnedAtBottomStaysPinnedWhenViewportShrinks() {
        val items = mutableStateOf(messages(20))
        val height = mutableStateOf(300.dp)
        val listState = LazyListState()
        messageList(items, listState, height)
        composeTestRule.waitForIdle()
        assertPinnedAtBottom(listState, 20)

        // IME opening under adjustResize: the weighted list keeps its scroll
        // offset while the viewport end jumps up by the IME height.
        height.value = 180.dp
        composeTestRule.waitForIdle()

        // Pre-fix (ticket 050), the tail slid below the shrunken viewport end
        // and the follow silently detached, so this assertion was RED.
        assertPinnedAtBottom(listState, 20)
    }

    @Test
    fun scrolledUpReaderIsNotYankedWhenViewportShrinks() {
        val items = mutableStateOf(messages(20))
        val height = mutableStateOf(300.dp)
        val listState = LazyListState()
        messageList(items, listState, height)
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { runBlocking { listState.scrollToItem(5) } }
        composeTestRule.waitForIdle()
        val anchorKey = listState.layoutInfo.visibleItemsInfo.first().key

        height.value = 180.dp
        composeTestRule.waitForIdle()

        assertEquals(anchorKey, listState.layoutInfo.visibleItemsInfo.first().key)
    }

    @Test
    fun scrolledUpSendScrollsToBottomAndStaysPinned() {
        val items = mutableStateOf(messages(20))
        val tick = mutableStateOf(0)
        val listState = LazyListState()
        messageList(items, listState, mutableStateOf(300.dp), tick)
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { runBlocking { listState.scrollToItem(5) } }
        composeTestRule.waitForIdle()

        // ChatScreen's send wrapper bumps the tick (ticket 051); the user echo
        // append lands right after, like the ViewModel's splice.
        tick.value = 1
        composeTestRule.waitForIdle()
        assertPinnedAtBottom(listState, 20)

        items.value = items.value + ChatItem.UserMessage("echo_1", "Follow-up sent while scrolled up.")
        composeTestRule.waitForIdle()

        // Pre-fix (ticket 051), nothing scrolled on send: the reader stayed on
        // the old row and the echo pinned nothing, so both assertions were RED.
        assertPinnedAtBottom(listState, 21)
    }
}
