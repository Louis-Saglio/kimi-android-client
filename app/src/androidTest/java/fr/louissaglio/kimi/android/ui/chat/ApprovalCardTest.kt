package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the approval card's sub-agent badge (ticket 139): a sub-agent's
 * request names the asking member, falls back to the plain label while the store has
 * no record for it, and a main-agent request renders exactly as before. Ticket 173: a
 * plan review renders its plan in a height-capped scroll area above Allow/Deny. */
@RunWith(AndroidJUnit4::class)
class ApprovalCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun card(approval: ChatViewModel.PendingApproval, subagentName: String? = null) {
        composeTestRule.setContent {
            KimiTheme {
                ApprovalCard(
                    approval = approval,
                    queueSize = 1,
                    subagentName = subagentName,
                    onAllow = {},
                    onDeny = {},
                )
            }
        }
    }

    @Test
    fun subagentRequestShowsTheMemberNameBadge() {
        card(
            ChatViewModel.PendingApproval("ap_1", "Bash", "sleep 300", agentId = "agent-1"),
            subagentName = "explore",
        )

        composeTestRule.onNodeWithText("Sub-agent · explore").assertIsDisplayed()
    }

    @Test
    fun subagentRequestWithoutAStoreRecordShowsThePlainBadge() {
        card(ChatViewModel.PendingApproval("ap_1", "Bash", "sleep 300", agentId = "agent-1"))

        composeTestRule.onNodeWithText("Sub-agent").assertIsDisplayed()
    }

    @Test
    fun mainAgentRequestShowsNoBadge() {
        card(ChatViewModel.PendingApproval("ap_1", "Bash", "echo hi"))

        composeTestRule.onNodeWithText("Sub-agent").assertDoesNotExist()
        // The rest of the card is unchanged.
        composeTestRule.onNodeWithText("Permission request").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bash").assertIsDisplayed()
        composeTestRule.onNodeWithText("echo hi").assertIsDisplayed()
    }

    @Test
    fun planReviewRendersThePlanAboveAllowAndDeny() {
        var allowed = 0
        composeTestRule.setContent {
            KimiTheme {
                ApprovalCard(
                    approval = ChatViewModel.PendingApproval(
                        "approval_plan",
                        "ExitPlanMode",
                        "Presenting plan and exiting plan mode",
                        plan = "# Add README.md\n\n## Context\n\nThe repository has no README.",
                    ),
                    queueSize = 1,
                    onAllow = { allowed++ },
                    onDeny = {},
                )
            }
        }

        composeTestRule.onNodeWithText("ExitPlanMode").assertIsDisplayed()
        composeTestRule.onNodeWithText("Presenting plan and exiting plan mode").assertIsDisplayed()
        composeTestRule.onNode(hasText("Add README.md", substring = true)).assertIsDisplayed()
        composeTestRule.onNode(hasText("The repository has no README.", substring = true)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Allow").performClick()
        composeTestRule.runOnIdle { assertEquals(1, allowed) }
    }

    @Test
    fun aLongPlanScrollsInsideTheCardAndKeepsTheButtonsOnScreen() {
        val plan = (1..80).joinToString("\n\n") { "Step $it of the plan." }
        composeTestRule.setContent {
            KimiTheme {
                // A small phone's bottom dock: the card is bottom-aligned in 480 dp.
                Box(Modifier.fillMaxWidth().height(480.dp), contentAlignment = Alignment.BottomCenter) {
                    ApprovalCard(
                        approval = ChatViewModel.PendingApproval(
                            "approval_plan", "ExitPlanMode", "Presenting plan and exiting plan mode", plan = plan,
                        ),
                        queueSize = 1,
                        onAllow = {},
                        onDeny = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Allow").assertIsDisplayed()
        composeTestRule.onNodeWithText("Deny").assertIsDisplayed()
        composeTestRule.onNodeWithText("Permission request").assertIsDisplayed()
        val planBounds = composeTestRule.onNodeWithTag(APPROVAL_PLAN_TAG).getUnclippedBoundsInRoot()
        val planHeight = planBounds.bottom - planBounds.top
        assertTrue("plan body is $planHeight tall", planHeight <= 240.dp)
        val lastStep = composeTestRule.onNode(hasText("Step 80 of the plan.", substring = true))
        lastStep.assertIsNotDisplayed()
        lastStep.performScrollTo()
        lastStep.assertIsDisplayed()
        // Scrolling the plan never moves the buttons.
        composeTestRule.onNodeWithText("Allow").assertIsDisplayed()
    }

    @Test
    fun theNextPlanReviewOpensAtTheTopOfItsPlan() {
        // Ticket 185 (C3): the bottom bar renders the queue head; when it resolves and
        // the next head is also a plan review, the new plan must not inherit the old
        // one's scroll offset.
        fun plan(name: String) = (1..80).joinToString("\n\n") { "$name step $it." }
        var head by mutableStateOf(
            ChatViewModel.PendingApproval("approval_a", "ExitPlanMode", "First plan", plan = plan("First")),
        )
        composeTestRule.setContent {
            KimiTheme {
                ApprovalCard(approval = head, queueSize = 1, onAllow = {}, onDeny = {})
            }
        }
        composeTestRule.onNode(hasText("First step 80.", substring = true)).performScrollTo()
        composeTestRule.onNode(hasText("First step 1.", substring = true)).assertIsNotDisplayed()

        head = ChatViewModel.PendingApproval("approval_b", "ExitPlanMode", "Second plan", plan = plan("Second"))

        composeTestRule.onNode(hasText("Second step 1.", substring = true)).assertIsDisplayed()
        composeTestRule.onNode(hasText("Second step 80.", substring = true)).assertIsNotDisplayed()
    }

    @Test
    fun aCommandApprovalHasNoPlanBody() {
        card(ChatViewModel.PendingApproval("ap_1", "Bash", "echo hi"))

        composeTestRule.onNodeWithTag(APPROVAL_PLAN_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText("echo hi").assertIsDisplayed()
        composeTestRule.onNodeWithText("Allow").assertIsDisplayed()
    }
}
