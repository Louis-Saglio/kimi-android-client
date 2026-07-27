package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
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

    private fun messageList(items: State<List<ChatItem>>, listState: LazyListState) {
        composeTestRule.setContent {
            KimiTheme {
                Box(Modifier.height(300.dp)) {
                    MessageList(items.value, listState = listState)
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
        messageList(items, listState)
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
        messageList(items, listState)
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { runBlocking { listState.scrollToItem(5) } }
        composeTestRule.waitForIdle()
        val anchorKey = listState.layoutInfo.visibleItemsInfo.first().key

        items.value = items.value + toolCall("tool_1")
        composeTestRule.waitForIdle()

        assertEquals(anchorKey, listState.layoutInfo.visibleItemsInfo.first().key)
    }
}
