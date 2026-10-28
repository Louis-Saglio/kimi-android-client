package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
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

/** Ticket 130: the divider row's navigation cue and tap — no dialog any more. */
@RunWith(AndroidJUnit4::class)
class CompactionDividerRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun row(item: ChatItem.CompactionDivider, onOpenSummary: () -> Unit = {}) {
        composeTestRule.setContent {
            KimiTheme {
                CompactionDividerRow(item, onOpenSummary = onOpenSummary)
            }
        }
    }

    @Test
    fun doneRowWithSummaryShowsChevronAndOpensTheScreen() {
        var opened = 0
        row(
            ChatItem.CompactionDivider("compaction_1", pending = false, summary = "summary"),
            onOpenSummary = { opened++ },
        )

        composeTestRule.onNodeWithText("Context compacted").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Open compaction summary").assertIsDisplayed()
        composeTestRule.onNodeWithText("Context compacted").performClick()
        assertEquals(1, opened)
        // The old dialog is gone: nothing titled "Compaction summary" is composed here.
        composeTestRule.onNodeWithText("Compaction summary").assertDoesNotExist()
    }

    @Test
    fun pendingRowHasNoChevronAndIgnoresTaps() {
        var opened = 0
        row(
            ChatItem.CompactionDivider("compaction_live", pending = true),
            onOpenSummary = { opened++ },
        )

        composeTestRule.onNodeWithText("Compacting context…").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Open compaction summary").assertDoesNotExist()
        composeTestRule.onNodeWithText("Compacting context…").performClick()
        assertEquals(0, opened)
    }
}
