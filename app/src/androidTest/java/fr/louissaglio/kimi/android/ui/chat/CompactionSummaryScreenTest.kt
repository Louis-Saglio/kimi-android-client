package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Ticket 130: the compaction summary screen — static markdown, the live delta line,
 * and the store-miss recovery. */
@RunWith(AndroidJUnit4::class)
class CompactionSummaryScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun screen(
        entry: CompactionSummaryStore.Entry?,
        onNavigateBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            KimiTheme {
                CompactionSummaryScreen(entry, onNavigateBack = onNavigateBack)
            }
        }
    }

    @Test
    fun summaryRendersAsMarkdownWithTheTokenDelta() {
        screen(
            CompactionSummaryStore.Entry(
                summary = "## Summary\n\nThe context was compacted.",
                tokensBefore = 26_500,
                tokensAfter = 386,
            ),
        )

        composeTestRule.onNodeWithText("Compaction summary").assertIsDisplayed()
        composeTestRule.onNodeWithText("26.5k → 386 tokens").assertIsDisplayed()
        composeTestRule.onNode(hasText("Summary", substring = true)).assertIsDisplayed()
        composeTestRule.onNode(hasText("The context was compacted.", substring = true)).assertIsDisplayed()
    }

    @Test
    fun historySourcedSummaryHasNoDeltaLine() {
        screen(
            CompactionSummaryStore.Entry(
                summary = "A history summary.",
                tokensBefore = null,
                tokensAfter = null,
            ),
        )

        composeTestRule.onNodeWithText("Compaction summary").assertIsDisplayed()
        composeTestRule.onNode(hasText("tokens", substring = true)).assertDoesNotExist()
    }

    @Test
    fun storeMissShowsTheMissingStateAndBackRecovers() {
        var back = false
        screen(entry = null, onNavigateBack = { back = true })

        composeTestRule.onNode(hasText("no longer available", substring = true)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Back").performClick()
        assertTrue(back)
    }
}
