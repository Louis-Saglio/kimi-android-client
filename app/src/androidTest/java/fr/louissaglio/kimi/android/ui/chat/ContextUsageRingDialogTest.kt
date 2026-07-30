package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.data.chat.ContextUsage
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the ring's exact-numbers dialog (ticket 057): the ≥80%
 * "Compact session" affordance, its enabled rule and the tap-to-dismiss flow. */
@RunWith(AndroidJUnit4::class)
class ContextUsageRingDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun openDialog(used: Long, compactEnabled: Boolean = true, onCompact: () -> Unit = {}) {
        composeTestRule.setContent {
            KimiTheme {
                ContextUsageRing(
                    ContextUsage(usedTokens = used, maxTokens = 100),
                    compactEnabled = compactEnabled,
                    onCompact = onCompact,
                )
            }
        }
        composeTestRule
            .onNodeWithContentDescription("Context usage", substring = true)
            .performClick()
    }

    @Test
    fun dialogBelow80PercentShowsNumbersOnly() {
        openDialog(used = 79)

        composeTestRule.onNodeWithText("79 / 100 tokens · 79%").assertIsDisplayed()
        composeTestRule.onNodeWithText("Compact session").assertDoesNotExist()
    }

    @Test
    fun dialogAt80PercentShowsCompactButton() {
        openDialog(used = 80)

        composeTestRule.onNodeWithText("80 / 100 tokens · 80%").assertIsDisplayed()
        composeTestRule.onNodeWithText("Compact session").assertIsDisplayed()
    }

    @Test
    fun tappingCompactButtonFiresTheCallbackAndDismissesTheDialog() {
        var compacted = false
        openDialog(used = 80, onCompact = { compacted = true })

        composeTestRule.onNodeWithText("Compact session").performClick()

        assertTrue(compacted)
        composeTestRule.onNodeWithText("80 / 100 tokens · 80%").assertDoesNotExist()
    }

    @Test
    fun compactButtonIsDisabledWhileWorkingOrCompacting() {
        openDialog(used = 80, compactEnabled = false)

        composeTestRule.onNodeWithText("Compact session").assertIsNotEnabled()
    }
}
