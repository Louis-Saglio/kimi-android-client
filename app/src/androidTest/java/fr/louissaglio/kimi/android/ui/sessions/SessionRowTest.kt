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

/** Semantics tests for the session-row status indicators (tickets 016/087). */
@RunWith(AndroidJUnit4::class)
class SessionRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(isRunning: Boolean, isAwaitingInteraction: Boolean) {
        composeTestRule.setContent {
            KimiTheme {
                SessionRow(
                    Session(
                        id = "session_1",
                        title = "A session",
                        cwd = "/tmp/spike",
                        updatedAt = Instant.now(),
                        isRunning = isRunning,
                        isAwaitingInteraction = isAwaitingInteraction,
                    ),
                    onClick = {},
                )
            }
        }
    }

    @Test
    fun pendingInteractionShowsNeedsAttentionBadgeNotRunningDot() {
        row(isRunning = true, isAwaitingInteraction = true)

        composeTestRule.onNodeWithContentDescription("Needs attention").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Session running").assertDoesNotExist()
    }

    @Test
    fun noPendingInteractionShowsNoBadge() {
        row(isRunning = false, isAwaitingInteraction = false)

        composeTestRule.onNodeWithContentDescription("Needs attention").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Session running").assertDoesNotExist()
    }

    @Test
    fun busyWithoutInteractionShowsRunningDotOnly() {
        row(isRunning = true, isAwaitingInteraction = false)

        composeTestRule.onNodeWithContentDescription("Session running").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Needs attention").assertDoesNotExist()
    }
}
