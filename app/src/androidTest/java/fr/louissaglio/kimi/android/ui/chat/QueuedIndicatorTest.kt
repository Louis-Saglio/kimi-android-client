package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics test for the queued-prompt indicator (ticket 154): the optimistic echo
 * bubble captions "Queued #N" while the prompt waits server-side, and shows the plain
 * in-flight look (no queue caption) once it starts. */
@RunWith(AndroidJUnit4::class)
class QueuedIndicatorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun bubble(queued: Int?) {
        composeTestRule.setContent {
            KimiTheme {
                UserBubble(ChatItem.UserMessage("msg_1", "second prompt", queued = queued))
            }
        }
    }

    @Test
    fun queuedEchoShowsItsQueuePosition() {
        bubble(queued = 1)

        composeTestRule.onNodeWithText("Queued #1").assertIsDisplayed()
    }

    @Test
    fun inFlightEchoShowsNoQueuePosition() {
        bubble(queued = null)

        composeTestRule.onNodeWithText("Queued #1").assertDoesNotExist()
    }
}
