package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.data.chat.FileChange
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the turn file-changes screen (ticket 149): the stateless
 * [TurnChangesScreen] body is driven with hand-made states — no Hilt, no network
 * (the EditDiffScreenTest construction pattern). */
@RunWith(AndroidJUnit4::class)
class TurnChangesScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun turnChangesScreen(
        uiState: TurnChangesViewModel.UiState,
        onOpenFile: (FileChange) -> Unit = {},
    ) {
        composeTestRule.setContent {
            KimiTheme {
                TurnChangesScreen(uiState, onNavigateBack = {}, onOpenFile = onOpenFile)
            }
        }
    }

    @Test
    fun rowsRenderStatusBadgeAndCounts() {
        turnChangesScreen(
            TurnChangesViewModel.UiState(
                turnId = 2,
                changes = listOf(
                    FileChange("/home/ubuntu/spike/notes.txt", FileChange.Status.Modified, additions = 3, deletions = 1),
                    FileChange(
                        "/home/ubuntu/spike/logo.png",
                        FileChange.Status.Added,
                        additions = 0,
                        deletions = 0,
                        binary = true,
                    ),
                    FileChange("/home/ubuntu/spike/old.log", FileChange.Status.Deleted, additions = 0, deletions = 42),
                ),
            ),
        )

        // Title, basenames and parent paths.
        composeTestRule.onNodeWithText("Files changed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Turn 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("notes.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("/home/ubuntu/spike").assertIsDisplayed()
        // Status badges.
        composeTestRule.onNodeWithText("modified").assertIsDisplayed()
        composeTestRule.onNodeWithText("added").assertIsDisplayed()
        composeTestRule.onNodeWithText("deleted").assertIsDisplayed()
        // The diff-stat counts (ticket 119's idiom).
        composeTestRule.onNodeWithText("+3").assertIsDisplayed()
        composeTestRule.onNodeWithText("−1").assertIsDisplayed()
        composeTestRule.onNodeWithText("−42").assertIsDisplayed()
        // The binary row carries its note instead of counts.
        composeTestRule.onNodeWithText("Binary file").assertIsDisplayed()
    }

    @Test
    fun binaryRowIsNotClickableAndNormalRowTapReports() {
        val opened = mutableListOf<FileChange>()
        turnChangesScreen(
            TurnChangesViewModel.UiState(
                turnId = 0,
                changes = listOf(
                    FileChange("/tmp/a.txt", FileChange.Status.Modified, additions = 1, deletions = 0),
                    FileChange("/tmp/b.bin", FileChange.Status.Added, additions = 0, deletions = 0, binary = true),
                ),
            ),
            onOpenFile = { opened += it },
        )

        // Unmerged tree: the row's clickable lives on its own semantics node.
        composeTestRule
            .onNode(hasClickAction().and(hasAnyDescendant(hasText("b.bin"))), useUnmergedTree = true)
            .assertDoesNotExist()

        composeTestRule.onNodeWithText("a.txt").performClick()

        assertEquals(listOf(FileChange("/tmp/a.txt", FileChange.Status.Modified, additions = 1, deletions = 0)), opened)
    }

    @Test
    fun emptyChangesRenderTheEmptyState() {
        turnChangesScreen(TurnChangesViewModel.UiState(turnId = 1, changes = emptyList()))

        composeTestRule.onNodeWithText("No file changes recorded for this turn").assertIsDisplayed()
    }
}
