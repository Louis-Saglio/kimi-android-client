package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Expansion scroll regression tests: tapping an expandable tool row must keep
 * the tapped header pinned so the details unfold downward — and it must not
 * crash. The list is rendered in a fixed-height box so the bubbles overflow it;
 * two trailing messages sit below the tool group so the expanded details stay
 * inside the viewport. */
@RunWith(AndroidJUnit4::class)
class ToolRowExpandScrollTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val expandableTool = ChatItem.ToolActivity(
        "tool_1",
        "Bash",
        ToolStatus.Done,
        preview = "echo hi",
        input = "{\n    \"command\": \"echo hi\"\n}",
        output = "hi\n",
    )

    private fun items(): List<ChatItem> {
        fun message(n: Int) = ChatItem.AssistantMessage(
            "msg_$n",
            "Message $n with enough text to give the bubble some height.",
            "",
        )
        return (1..15).map(::message) + ChatItem.ToolGroup(expandableTool) +
            listOf(message(16), message(17))
    }

    private fun messageList(listState: LazyListState) {
        composeTestRule.setContent {
            KimiTheme {
                Box(Modifier.height(300.dp)) {
                    MessageList(items(), listState = listState)
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun chevronTop(contentDescription: String): Float =
        composeTestRule
            .onNodeWithContentDescription(contentDescription)
            .fetchSemanticsNode()
            .boundsInRoot
            .top

    @Test
    fun tappingToolRowExpandsDetailsWithoutCrashing() {
        messageList(LazyListState())

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("{\n    \"command\": \"echo hi\"\n}").assertIsDisplayed()
        composeTestRule.onNodeWithText("hi\n").assertIsDisplayed()
    }

    @Test
    fun expandingToolRowKeepsHeaderPinned() {
        messageList(LazyListState())
        val beforeTop = chevronTop("Expand tool details")

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.waitForIdle()

        // The tapped header must not have moved: the details unfolded below it.
        assertEquals(beforeTop, chevronTop("Collapse tool details"), 2f)
    }

    @Test
    fun collapsingToolRowKeepsHeaderPinned() {
        messageList(LazyListState())
        val beforeTop = chevronTop("Expand tool details")

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithContentDescription("Collapse tool details").performClick()
        composeTestRule.waitForIdle()

        assertEquals(beforeTop, chevronTop("Expand tool details"), 2f)
    }
}
