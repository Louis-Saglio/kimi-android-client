package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The turn-end footer (ticket 155): a turn stopped by the server shows its distinct
 * reason, not the user-cancel copy. */
@RunWith(AndroidJUnit4::class)
class TurnEndFooterTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun maxStepsTurnFooterShowsItsDistinctCopy() {
        composeTestRule.setContent {
            KimiTheme {
                TurnEndFooter(label = turnEndCopy("max_steps")!!)
            }
        }

        composeTestRule.onNodeWithText("Stopped: max steps").assertIsDisplayed()
    }
}
