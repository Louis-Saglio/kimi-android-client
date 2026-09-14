package fr.louissaglio.kimi.android.ui.sessions

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.data.sessions.Session
import fr.louissaglio.kimi.android.ui.common.formatTimestamp
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the session-row status indicators (tickets 016/087), the supporting line (114) and the long-press full-name dialog (117). */
@RunWith(AndroidJUnit4::class)
class SessionRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(
        title: String? = "A session",
        isRunning: Boolean = false,
        isAwaitingInteraction: Boolean = false,
        cwd: String? = "/tmp/spike",
        workspaceLabel: String? = null,
        updatedAt: Instant = Instant.now(),
        onClick: () -> Unit = {},
        onFork: () -> Unit = {},
        onArchive: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            KimiTheme {
                SessionRow(
                    Session(
                        id = "session_1",
                        title = title,
                        cwd = cwd,
                        updatedAt = updatedAt,
                        isRunning = isRunning,
                        isAwaitingInteraction = isAwaitingInteraction,
                    ),
                    workspaceLabel = workspaceLabel,
                    onClick = onClick,
                    onFork = onFork,
                    onArchive = onArchive,
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

    @Test
    fun longPressOpensTheDialogWithTheFullTitle() {
        // Longer than the one-line ellipsized headline can ever show.
        val longTitle = "An automatically generated session title that keeps going " +
            "long past what the single-line row can display"
        row(title = longTitle)

        composeTestRule.onNode(hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)

        // The headline node carries the same text, so only the in-dialog copy counts.
        composeTestRule.onNode(hasText(longTitle) and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    @Test
    fun untitledSessionLongPressShowsTheFullCwd() {
        val cwd = "/home/ubuntu/projects/some/deeply/nested/workspace"
        row(title = null, cwd = cwd)

        composeTestRule.onNode(hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)

        // The row's headline only shows the basename; the dialog has the full path.
        composeTestRule.onNode(hasText(cwd) and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    @Test
    fun tapNavigatesAndNeverOpensTheDialog() {
        var clicked = false
        row(onClick = { clicked = true })

        composeTestRule.onNode(hasClickAction()).performClick()

        assertTrue(clicked)
        composeTestRule.onNode(isDialog()).assertDoesNotExist()
    }
}
