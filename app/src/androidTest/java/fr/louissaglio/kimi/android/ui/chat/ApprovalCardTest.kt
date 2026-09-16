package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the approval card's sub-agent badge (ticket 139): a sub-agent's
 * request names the asking member, falls back to the plain label while the store has
 * no record for it, and a main-agent request renders exactly as before. */
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
}
