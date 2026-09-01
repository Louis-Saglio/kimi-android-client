package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
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

    /** Ticket 118: a call with object args expands to the generic key/value table —
     * labels and values, no raw-JSON braces. */
    @Test
    fun rowWithObjectArgsExpandsToKeyValueTable() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "Glob",
                ToolStatus.Done,
                preview = "glob the sources",
                args = buildJsonObject {
                    put("pattern", "*.kt")
                    put("path", "/tmp/spike")
                },
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("pattern").assertIsDisplayed()
        composeTestRule.onNodeWithText("*.kt").assertIsDisplayed()
        composeTestRule.onNodeWithText("path").assertIsDisplayed()
        composeTestRule.onNodeWithText("/tmp/spike").assertIsDisplayed()
        // The table replaced the pretty-printed JSON: no braces anywhere.
        composeTestRule.onNodeWithText("{").assertDoesNotExist()
        composeTestRule.onNodeWithText("}").assertDoesNotExist()
    }

    /** Ticket 118: a call that carried no object args and has no output yet stays
     * non-expandable — the rule is unchanged. */
    @Test
    fun rowWithNeitherArgsNorOutputStaysNonExpandable() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "mcp__kiln__list_runners",
                ToolStatus.Running,
                preview = """{"runners":3}""",
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
        composeTestRule.onNodeWithText("mcp__kiln__list_runners").performClick()
        composeTestRule.onNodeWithContentDescription("Collapse tool details").assertDoesNotExist()
    }

    @Test
    fun linkedRowNeverExpandsAndNavigatesOnRowTap() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity(
                        "tool_1",
                        "Agent",
                        ToolStatus.Running,
                        preview = "doc-verify",
                        input = "{\n    \"prompt\": \"Reply ok\"\n}",
                        subagentId = "agent-12",
                    ),
                    onOpenSubagent = { opened = it },
                )
            }
        }

        // Even with call details available, a linked row shows no expand affordance…
        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Open sub-agent").assertIsDisplayed()

        // …and tapping the row itself navigates to the sub-agent page.
        composeTestRule.onNodeWithText("Agent").performClick()
        composeTestRule.onNodeWithText("{\n    \"prompt\": \"Reply ok\"\n}").assertDoesNotExist()
        assertEquals("agent-12", opened)
    }

    @Test
    fun linkedRowOpensSubagentFromTrailingButton() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity("tool_1", "Agent", ToolStatus.Running, preview = "doc-verify", subagentId = "agent-12"),
                    onOpenSubagent = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Open sub-agent").performClick()
        assertEquals("agent-12", opened)
    }

    /** Ticket 096: a bash row linked to a background job behaves like a linked Agent
     * row — it never expands, and the tap navigates to the job detail screen. */
    @Test
    fun bashLinkedRowNeverExpandsAndNavigatesOnRowTap() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity(
                        "tool_1",
                        "Bash",
                        ToolStatus.Done,
                        preview = "tick counter loop",
                        input = "{\n    \"command\": \"for i in …\"\n}",
                        output = "task_id: bash-1\n…",
                        bashTaskId = "bash-1",
                    ),
                    onOpenJob = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Open job").assertIsDisplayed()

        composeTestRule.onNodeWithText("Bash").performClick()
        composeTestRule.onNodeWithText("task_id: bash-1\n…").assertDoesNotExist()
        assertEquals("bash-1", opened)
    }

    @Test
    fun bashLinkedRowOpensJobFromTrailingButton() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Running, preview = "tick counter loop", bashTaskId = "bash-1"),
                    onOpenJob = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Open job").performClick()
        assertEquals("bash-1", opened)
    }

    /** Tickets 088/089: question rows never expand and show no chevron — the tap is
     * forwarded with the row's id (= tool call id = question id), input and output. */
    @Test
    fun questionRowForwardsTapAndShowsNoChevron() {
        var tapped: Triple<String, String, String>? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity(
                        "tool_q1",
                        "AskUserQuestion",
                        ToolStatus.Running,
                        preview = "Red or blue?",
                        input = "{\n    \"questions\": []\n}",
                    ),
                    onQuestionRowTapped = { id, input, output -> tapped = Triple(id, input, output) },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
        composeTestRule.onNodeWithText("AskUserQuestion").performClick()
        assertEquals(Triple("tool_q1", "{\n    \"questions\": []\n}", ""), tapped)
        // No expansion: the raw input never renders.
        composeTestRule.onNodeWithText("{\n    \"questions\": []\n}").assertDoesNotExist()
    }

    /** Ticket 089: a resolved question row is equally chevron-free — its details are
     * the read-only card, not raw JSON. */
    @Test
    fun resolvedQuestionRowShowsNoChevron() {
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity(
                        "tool_q1",
                        "AskUserQuestion",
                        ToolStatus.Done,
                        preview = "Red or blue?",
                        input = "{\n    \"questions\": []\n}",
                        output = "{\"answers\":{}}",
                    ),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
    }
}
