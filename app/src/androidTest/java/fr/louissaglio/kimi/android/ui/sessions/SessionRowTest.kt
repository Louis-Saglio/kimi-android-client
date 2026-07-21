package fr.louissaglio.kimi.android.ui.sessions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.data.sessions.Session
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the session-row status indicators (ticket 016). */
@RunWith(AndroidJUnit4::class)
class SessionRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(isRunning: Boolean, isAwaitingApproval: Boolean) {
        composeTestRule.setContent {
            KimiTheme {
                SessionRow(
                    Session(
                        id = "session_1",
                        title = "A session",
                        cwd = "/tmp/spike",
                        updatedAt = Instant.now(),
                        isRunning = isRunning,
                        isAwaitingApproval = isAwaitingApproval,
                    ),
                    onClick = {},
                )
            }
        }
    }

    @Test
    fun approvalPendingShowsApprovalBadgeNotRunningDot() {
        row(isRunning = true, isAwaitingApproval = true)

        composeTestRule.onNodeWithContentDescription("Approval needed").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Session running").assertDoesNotExist()
    }

    @Test
    fun noPendingInteractionShowsNoBadge() {
        row(isRunning = false, isAwaitingApproval = false)

        composeTestRule.onNodeWithContentDescription("Approval needed").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Session running").assertDoesNotExist()
    }

    @Test
    fun busyWithoutApprovalShowsRunningDotOnly() {
        row(isRunning = true, isAwaitingApproval = false)

        composeTestRule.onNodeWithContentDescription("Session running").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Approval needed").assertDoesNotExist()
    }
}
