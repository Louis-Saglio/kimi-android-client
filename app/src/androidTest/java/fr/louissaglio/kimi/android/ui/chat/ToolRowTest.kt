package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the rich tool-activity rows (ticket 022). */
@RunWith(AndroidJUnit4::class)
class ToolRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun toolRow(activity: ChatItem.ToolActivity) {
        composeTestRule.setContent { KimiTheme { ToolRow(activity) } }
    }

    @Test
    fun bashRowShowsTerminalGlyphNamePreviewAndSuccessCheck() {
        toolRow(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, preview = "echo doc-verify"))

        composeTestRule.onNodeWithText(">_").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bash").assertIsDisplayed()
        composeTestRule.onNodeWithText("echo doc-verify").assertIsDisplayed()
        composeTestRule.onNodeWithText("✓").assertIsDisplayed()
    }

    @Test
    fun nonBashRowShowsNameAndPreviewWithoutTerminalGlyph() {
        toolRow(ChatItem.ToolActivity("tool_1", "Read", ToolStatus.Done, preview = "/tmp/spike/a.txt"))

        composeTestRule.onNodeWithText(">_").assertDoesNotExist()
        composeTestRule.onNodeWithText("Read").assertIsDisplayed()
        composeTestRule.onNodeWithText("/tmp/spike/a.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("✓").assertIsDisplayed()
    }

    @Test
    fun errorRowShowsCrossInsteadOfCheck() {
        toolRow(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Error, preview = "rm -rf /"))

        composeTestRule.onNodeWithText("✗").assertIsDisplayed()
        composeTestRule.onNodeWithText("✓").assertDoesNotExist()
    }

    @Test
    fun runningRowShowsSpinnerAndNoOutcomeGlyph() {
        toolRow(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Running, preview = "sleep 10"))

        composeTestRule.onNodeWithContentDescription("Tool running").assertIsDisplayed()
        composeTestRule.onNodeWithText("✓").assertDoesNotExist()
        composeTestRule.onNodeWithText("✗").assertDoesNotExist()
    }

    @Test
    fun tappingRowExpandsInputAndOutputThenCollapses() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "Bash",
                ToolStatus.Done,
                preview = "echo hi",
                input = "{\n    \"command\": \"echo hi\"\n}",
                output = "hi\n",
            ),
        )

        // Collapsed: details hidden, the chevron offers to expand.
        composeTestRule.onNodeWithText("hi\n").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Expand tool details").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("{\n    \"command\": \"echo hi\"\n}").assertIsDisplayed()
        composeTestRule.onNodeWithText("hi\n").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Collapse tool details").performClick()
        composeTestRule.onNodeWithText("hi\n").assertDoesNotExist()
    }

    @Test
    fun rowWithoutDetailsHasNoChevron() {
        toolRow(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Running, preview = "sleep 10"))

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
    }
}
