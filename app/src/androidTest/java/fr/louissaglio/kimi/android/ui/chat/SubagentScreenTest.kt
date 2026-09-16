package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the sub-agent page's "To background" overflow (ticket 138).
 * SubagentContent takes a plain UiState, so no store and no Hilt is involved (the
 * SwarmScreenTest pattern). */
@RunWith(AndroidJUnit4::class)
class SubagentScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun detachableState() = SubagentViewModel.UiState(
        subagentId = "agent-12",
        sessionId = "session_1",
        name = "explore",
        description = "doc-verify",
        status = SubagentStatus.Working,
        items = listOf(ChatItem.AssistantMessage("m1", "working on it", thinking = "")),
        taskId = "agent-t1",
        detachable = true,
    )

    @Test
    fun toBackgroundShowsInTheOverflowOnlyWhileDetachable() {
        composeTestRule.setContent {
            KimiTheme {
                SubagentContent(
                    uiState = detachableState(),
                    onNavigateBack = {},
                )
            }
        }

        // The overflow carries the web-parity label; nothing shows before it opens.
        composeTestRule.onNodeWithText("To background").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Sub-agent options").performClick()
        composeTestRule.onNodeWithText("To background").assertIsDisplayed()
    }

    @Test
    fun backgroundAndTerminalRecordsHideTheAction() {
        val state = mutableStateOf(
            // A background member: the registration id is known, yet the record is
            // already main-owned — no affordance.
            detachableState().copy(detachable = false),
        )
        composeTestRule.setContent {
            KimiTheme {
                SubagentContent(
                    uiState = state.value,
                    onNavigateBack = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Sub-agent options").assertDoesNotExist()
        composeTestRule.onNodeWithText("To background").assertDoesNotExist()

        // A terminal record never shows it either.
        composeTestRule.runOnIdle {
            state.value = state.value.copy(status = SubagentStatus.Completed)
        }
        composeTestRule.onNodeWithContentDescription("Sub-agent options").assertDoesNotExist()
        composeTestRule.onNodeWithText("To background").assertDoesNotExist()
    }

    @Test
    fun tappingToBackgroundReportsTheIntentDisablesTheItemAndTheFlipHidesIt() {
        var detachTaps = 0
        val state = mutableStateOf(detachableState())
        composeTestRule.setContent {
            KimiTheme {
                SubagentContent(
                    uiState = state.value,
                    onNavigateBack = {},
                    onDetachToBackground = {
                        detachTaps++
                        // The ViewModel latches detaching while the POST is in flight.
                        state.value = state.value.copy(detaching = true)
                    },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Sub-agent options").performClick()
        composeTestRule.onNodeWithText("To background").assertIsEnabled()
        composeTestRule.onNodeWithText("To background").performClick()
        assertEquals(1, detachTaps)

        // In flight: the item disables — a double-tap cannot re-fire.
        composeTestRule.onNodeWithContentDescription("Sub-agent options").performClick()
        composeTestRule.onNodeWithText("To background").assertIsNotEnabled()

        // The conversion's task.started flips the record: the whole affordance
        // disappears (the member now lives the background lifecycle).
        composeTestRule.runOnIdle {
            state.value = state.value.copy(detachable = false, detaching = false)
        }
        composeTestRule.onNodeWithContentDescription("Sub-agent options").assertDoesNotExist()
        composeTestRule.onNodeWithText("To background").assertDoesNotExist()
        assertEquals(1, detachTaps)
    }
}
