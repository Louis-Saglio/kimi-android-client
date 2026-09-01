package fr.louissaglio.kimi.android.ui.sessions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.data.sessions.Session
import fr.louissaglio.kimi.android.ui.common.formatTimestamp
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the session-row status indicators (tickets 016/087) and supporting line (114). */
@RunWith(AndroidJUnit4::class)
class SessionRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(
        isRunning: Boolean = false,
        isAwaitingInteraction: Boolean = false,
        cwd: String? = "/tmp/spike",
        workspaceLabel: String? = null,
        updatedAt: Instant = Instant.now(),
    ) {
        composeTestRule.setContent {
            KimiTheme {
                SessionRow(
                    Session(
                        id = "session_1",
                        title = "A session",
                        cwd = cwd,
                        updatedAt = updatedAt,
                        isRunning = isRunning,
                        isAwaitingInteraction = isAwaitingInteraction,
                    ),
                    workspaceLabel = workspaceLabel,
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

    @Test
    fun aWorkspaceLabelPrefixesTheDate() {
        val updatedAt = Instant.parse("2026-07-17T03:48:31.606Z")
        row(workspaceLabel = "spike", updatedAt = updatedAt)

        composeTestRule.onNodeWithText("spike · ${formatTimestamp(updatedAt)}").assertIsDisplayed()
    }

    @Test
    fun noCwdKeepsTheBareDate() {
        val updatedAt = Instant.parse("2026-07-17T03:48:31.606Z")
        row(cwd = null, workspaceLabel = null, updatedAt = updatedAt)

        // Exact match: no "label · " prefix on the node.
        composeTestRule.onNodeWithText(formatTimestamp(updatedAt)).assertIsDisplayed()
    }
}
