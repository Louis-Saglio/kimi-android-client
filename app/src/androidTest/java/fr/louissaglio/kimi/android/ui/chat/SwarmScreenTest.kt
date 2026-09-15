package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the swarm roster screen (ticket 134). SwarmContent takes a
 * plain UiState, so no store and no Hilt is involved (the EditDiffScreenTest
 * pattern). */
@RunWith(AndroidJUnit4::class)
class SwarmScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun member(id: String, description: String, status: SubagentStatus) =
        SubagentStreamStore.SubagentState(
            id = id,
            name = "explore",
            description = description,
            runInBackground = false,
            status = status,
        )

    @Test
    fun memberRowsShowLabelAndLiveStatusAndTapOpensTheSubagentPage() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                SwarmContent(
                    uiState = SwarmViewModel.UiState(
                        sessionId = "session_1",
                        toolCallId = "tool_swarm",
                        description = "review the docs",
                        members = listOf(
                            member("agent-1", "Check a", SubagentStatus.Working),
                            member("agent-2", "Check b", SubagentStatus.Completed),
                        ),
                    ),
                    onNavigateBack = {},
                    onOpenSubagent = { opened = it },
                )
            }
        }

        // Top bar: the title and the swarm call's description as the supporting line.
        composeTestRule.onNodeWithText("Swarm").assertIsDisplayed()
        composeTestRule.onNodeWithText("review the docs").assertIsDisplayed()
        // Member rows: the record description as the label, the live status chip.
        composeTestRule.onNodeWithText("Check a").assertIsDisplayed()
        composeTestRule.onNodeWithText("Working").assertIsDisplayed()
        composeTestRule.onNodeWithText("Check b").assertIsDisplayed()
        composeTestRule.onNodeWithText("Completed").assertIsDisplayed()

        composeTestRule.onNodeWithText("Check b").performClick()
        assertEquals("agent-2", opened)
    }

    @Test
    fun emptyStateRendersWhenNoMembersAreKnown() {
        composeTestRule.setContent {
            KimiTheme {
                SwarmContent(
                    uiState = SwarmViewModel.UiState(sessionId = "session_1", toolCallId = "tool_swarm"),
                    onNavigateBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText("No members known yet").assertIsDisplayed()
    }
}
