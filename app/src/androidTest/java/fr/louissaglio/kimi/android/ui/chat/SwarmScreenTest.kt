package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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

    private fun member(
        id: String,
        description: String,
        status: SubagentStatus,
        model: String? = null,
        thinkingEffort: String? = null,
        taskId: String? = null,
    ) =
        SubagentStreamStore.SubagentState(
            id = id,
            name = "explore",
            description = description,
            runInBackground = false,
            status = status,
            model = model,
            thinkingEffort = thinkingEffort,
            taskId = taskId,
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

    /** Ticket 136: roster rows carry a one-line muted activity (the last assistant
     * text while working, the suspend cause while suspended, the result/error's
     * first line once terminal), and a ghost row (a backfilled `not_started`
     * member) shows no open chevron and is not clickable. */
    @Test
    fun memberRowsShowTheActivityLineAndGhostRowsAreNotClickable() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                SwarmContent(
                    uiState = SwarmViewModel.UiState(
                        sessionId = "session_1",
                        toolCallId = "tool_swarm",
                        members = listOf(
                            member("agent-1", "Check a", SubagentStatus.Working).copy(
                                items = listOf(
                                    ChatItem.AssistantMessage("live_0", "first line\nsecond line", thinking = ""),
                                ),
                            ),
                            member("agent-2", "Check b", SubagentStatus.Suspended).copy(
                                suspendReason = "provider rate limit",
                            ),
                            member("agent-3", "Check c", SubagentStatus.Completed).copy(
                                resultSummary = "the tail\nwith details",
                            ),
                            member("tool_swarm:ghost:3", "Check d", SubagentStatus.Cancelled).copy(ghost = true),
                        ),
                    ),
                    onNavigateBack = {},
                    onOpenSubagent = { opened = it },
                )
            }
        }

        // The activity line per status: last text line, suspend reason, summary head.
        composeTestRule.onNodeWithText("second line").assertIsDisplayed()
        composeTestRule.onNodeWithText("provider rate limit").assertIsDisplayed()
        composeTestRule.onNodeWithText("the tail").assertIsDisplayed()

        // The ghost row renders its label and cancelled chip but no open chevron.
        composeTestRule.onNodeWithText("Check d").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancelled").assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription("Open sub-agent").assertCountEquals(3)
        // ...and is not clickable, while a live member's row opens its page.
        composeTestRule.onNodeWithText("Check d").performClick()
        assertEquals(null, opened)
        composeTestRule.onNodeWithText("Check c").performClick()
        assertEquals("agent-3", opened)
    }

    /** Ticket 135: the strip under the top bar renders the same aggregate as the
     * chat's swarm card — the present phases' counts, plus the shared model line
     * when every member runs the same model/effort. */
    @Test
    fun summaryStripShowsTheCountsLineAndTheSharedModelLine() {
        composeTestRule.setContent {
            KimiTheme {
                SwarmContent(
                    uiState = SwarmViewModel.UiState(
                        sessionId = "session_1",
                        toolCallId = "tool_swarm",
                        members = listOf(
                            member("agent-1", "Check a", SubagentStatus.Working, "kimi-code/k3", "high"),
                            member("agent-2", "Check b", SubagentStatus.Working, "kimi-code/k3", "high"),
                            member("agent-3", "Check c", SubagentStatus.Completed, "kimi-code/k3", "high"),
                            member("agent-4", "Check d", SubagentStatus.Cancelled, "kimi-code/k3", "high"),
                        ),
                    ),
                    onNavigateBack = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("swarmSummaryStrip").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 working · 1 completed · 1 cancelled").assertIsDisplayed()
        composeTestRule.onNodeWithText("kimi-code/k3 · high").assertIsDisplayed()
        // The cancelled member row shows the cancelled chip, not a failure.
        composeTestRule.onNodeWithText("Check d").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancelled").assertIsDisplayed()
    }

    /** Ticket 135: a mixed model pair hides the strip's model line. */
    @Test
    fun summaryStripHidesTheModelLineWhenMembersDisagree() {
        composeTestRule.setContent {
            KimiTheme {
                SwarmContent(
                    uiState = SwarmViewModel.UiState(
                        sessionId = "session_1",
                        toolCallId = "tool_swarm",
                        members = listOf(
                            member("agent-1", "Check a", SubagentStatus.Working, "kimi-code/k3", "high"),
                            member("agent-2", "Check b", SubagentStatus.Working, "kimi-code/k2", "low"),
                        ),
                    ),
                    onNavigateBack = {},
                )
            }
        }

        composeTestRule.onNodeWithText("2 working").assertIsDisplayed()
        composeTestRule.onNodeWithText("kimi-code/k3 · high").assertDoesNotExist()
    }

    /** Ticket 137: a running member whose task id is known shows a trailing stop
     * button; terminal members, a running member without a task id (the snapshot
     * roster carries none), and ghost rows never do. */
    @Test
    fun stopButtonShowsOnlyForRunningMembersWithAKnownTaskId() {
        composeTestRule.setContent {
            KimiTheme {
                SwarmContent(
                    uiState = SwarmViewModel.UiState(
                        sessionId = "session_1",
                        toolCallId = "tool_swarm",
                        members = listOf(
                            member("agent-1", "Check a", SubagentStatus.Queued, taskId = "agent-t1"),
                            member("agent-2", "Check b", SubagentStatus.Working, taskId = "agent-t2"),
                            member("agent-3", "Check c", SubagentStatus.Suspended, taskId = "agent-t3"),
                            member("agent-4", "Check d", SubagentStatus.Working),
                            member("agent-5", "Check e", SubagentStatus.Completed, taskId = "agent-t5"),
                            member("agent-6", "Check f", SubagentStatus.Cancelled, taskId = "agent-t6"),
                            member("tool_swarm:ghost:7", "Check g", SubagentStatus.Cancelled).copy(ghost = true),
                        ),
                    ),
                    onNavigateBack = {},
                )
            }
        }

        composeTestRule.onAllNodesWithContentDescription("Stop").assertCountEquals(3)
        composeTestRule.onNodeWithTag("stopMember:agent-1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stopMember:agent-2").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stopMember:agent-3").assertIsDisplayed()
    }

    /** Ticket 137: tapping Stop reports the member and disables the button
     * immediately; the row settles (button gone, terminal chip) when the kill's
     * `task.terminated` lands — the store's killed → Cancelled mapping (ticket 135)
     * drives the state flip, the row just renders it. */
    @Test
    fun tappingStopDisablesTheButtonAndTheSettledRowHidesIt() {
        var stopped: String? = null
        val state = mutableStateOf(
            SwarmViewModel.UiState(
                sessionId = "session_1",
                toolCallId = "tool_swarm",
                members = listOf(member("agent-1", "Check a", SubagentStatus.Working, taskId = "agent-t1")),
            ),
        )
        composeTestRule.setContent {
            KimiTheme {
                SwarmContent(
                    uiState = state.value,
                    onNavigateBack = {},
                    onStopMember = { memberId ->
                        stopped = memberId
                        // The ViewModel's synchronous latch (unit-tested separately).
                        state.value = state.value.copy(
                            stoppingMemberIds = state.value.stoppingMemberIds + memberId,
                        )
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag("stopMember:agent-1").assertIsEnabled()
        composeTestRule.onNodeWithTag("stopMember:agent-1").performClick()
        assertEquals("agent-1", stopped)
        composeTestRule.onNodeWithTag("stopMember:agent-1").assertIsNotEnabled()

        // The kill's task.terminated arrives: the member goes terminal, the button
        // is gone, the chip reads the cancelled truth.
        composeTestRule.runOnIdle {
            state.value = state.value.copy(
                members = listOf(member("agent-1", "Check a", SubagentStatus.Cancelled, taskId = "agent-t1")),
            )
        }
        composeTestRule.onAllNodesWithContentDescription("Stop").assertCountEquals(0)
        composeTestRule.onNodeWithText("Cancelled").assertIsDisplayed()
    }
}
