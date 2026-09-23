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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.data.sessions.Session
import fr.louissaglio.kimi.android.ui.common.formatTimestamp
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the session-row status indicators (tickets 016/087/145/159), the supporting line (114), the long-press full-name dialog (117) and the delete confirmation (151). */
@RunWith(AndroidJUnit4::class)
class SessionRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(
        title: String? = "A session",
        isRunning: Boolean = false,
        isAwaitingInteraction: Boolean = false,
        isLastTurnInterrupted: Boolean = false,
        hasUnread: Boolean = false,
        cwd: String? = "/tmp/spike",
        workspaceLabel: String? = null,
        updatedAt: Instant = Instant.now(),
        onClick: () -> Unit = {},
        onFork: () -> Unit = {},
        onArchive: () -> Unit = {},
        onDelete: () -> Unit = {},
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
                        isLastTurnInterrupted = isLastTurnInterrupted,
                    ),
                    workspaceLabel = workspaceLabel,
                    hasUnread = hasUnread,
                    onClick = onClick,
                    onFork = onFork,
                    onArchive = onArchive,
                    onDelete = onDelete,
                )
            }
        }
    }

    @Test
    fun pendingInteractionShowsNeedsAttentionBadgeNotSpinner() {
        row(isRunning = true, isAwaitingInteraction = true)

        composeTestRule.onNodeWithContentDescription("Needs attention").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Session running").assertDoesNotExist()
    }

    @Test
    fun idleSessionShowsNoIndicator() {
        row(isRunning = false, isAwaitingInteraction = false)

        composeTestRule.onNodeWithContentDescription("Needs attention").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Session running").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Session has unread activity").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Last turn did not complete").assertDoesNotExist()
    }

    @Test
    fun busyWithoutInteractionShowsSpinnerOnly() {
        row(isRunning = true, isAwaitingInteraction = false)

        composeTestRule.onNodeWithContentDescription("Session running").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Needs attention").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Session has unread activity").assertDoesNotExist()
    }

    @Test
    fun unreadSessionShowsDot() {
        row(hasUnread = true)

        composeTestRule.onNodeWithContentDescription("Session has unread activity").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Session running").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Needs attention").assertDoesNotExist()
    }

    @Test
    fun runningWinsOverUnread() {
        row(isRunning = true, hasUnread = true)

        composeTestRule.onNodeWithContentDescription("Session running").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Session has unread activity").assertDoesNotExist()
    }

    @Test
    fun interruptedSessionShowsBadgeOnly() {
        row(isLastTurnInterrupted = true)

        composeTestRule.onNodeWithContentDescription("Last turn did not complete").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Session running").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Session has unread activity").assertDoesNotExist()
    }

    @Test
    fun runningWinsOverInterruptedBadge() {
        // Spinner precedence (ticket 159): the badge shows only when not running.
        row(isRunning = true, isLastTurnInterrupted = true)

        composeTestRule.onNodeWithContentDescription("Session running").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Last turn did not complete").assertDoesNotExist()
    }

    @Test
    fun interruptedBadgeWinsOverUnreadDot() {
        // Web order (ticket 159): the badge sits between the spinner and the unread
        // dot — the abort is the newer event, so it takes the trailing slot.
        row(isLastTurnInterrupted = true, hasUnread = true)

        composeTestRule.onNodeWithContentDescription("Last turn did not complete").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Session has unread activity").assertDoesNotExist()
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

    @Test
    fun deleteConfirmRemovesTheRow() {
        // Stateful stand-in for the ViewModel (ticket 151): the confirm callback is
        // what drops the row, so the harness wires it to the row's removal.
        composeTestRule.setContent {
            KimiTheme {
                var deleted by remember { mutableStateOf(false) }
                if (!deleted) {
                    SessionRow(
                        Session(
                            id = "session_1",
                            title = "A session",
                            cwd = "/tmp/spike",
                            updatedAt = Instant.now(),
                            isRunning = false,
                            isAwaitingInteraction = false,
                        ),
                        workspaceLabel = null,
                        hasUnread = false,
                        onClick = {},
                        onFork = {},
                        onArchive = {},
                        onDelete = { deleted = true },
                    )
                }
            }
        }

        composeTestRule.onNode(hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        composeTestRule.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()

        // The full-name dialog swaps to the confirmation before anything fires.
        composeTestRule
            .onNodeWithText("Delete session \"A session\"? This cannot be undone.")
            .assertIsDisplayed()
        composeTestRule.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()

        composeTestRule.onNodeWithText("A session").assertDoesNotExist()
        composeTestRule.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun deleteCancelLeavesTheRow() {
        var deleteCalled = false
        row(onDelete = { deleteCalled = true })

        composeTestRule.onNode(hasClickAction()).performSemanticsAction(SemanticsActions.OnLongClick)
        composeTestRule.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertFalse(deleteCalled)
        composeTestRule.onNode(isDialog()).assertDoesNotExist()
        composeTestRule.onNodeWithText("A session").assertIsDisplayed()
    }
}
