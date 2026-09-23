package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the chat's live activity status line (ticket 148): the line
 * shows during a tool call with its elapsed readout ticking, and disappears when the
 * phase clears (idle/ended/interrupted → null). */
@RunWith(AndroidJUnit4::class)
class ActivityStatusLineTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val since: Instant = Instant.parse("2026-09-23T10:00:00.000Z")

    @Test
    fun aToolCallShowsTheStatusAndTheElapsedTicks() {
        val status = mutableStateOf<ChatViewModel.ActivityStatus?>(
            ChatViewModel.ActivityStatus("Running Bash…", since),
        )
        val now = mutableStateOf(since.plusSeconds(12))
        composeTestRule.setContent { KimiTheme { ActivityStatusLine(status.value, now.value) } }

        composeTestRule.onNodeWithText("Running Bash… · 00:12").assertIsDisplayed()
        now.value = now.value.plusSeconds(1)
        composeTestRule.onNodeWithText("Running Bash… · 00:13").assertIsDisplayed()
    }

    @Test
    fun aClearedPhaseRemovesTheLine() {
        val status = mutableStateOf<ChatViewModel.ActivityStatus?>(
            ChatViewModel.ActivityStatus("Thinking…", since),
        )
        composeTestRule.setContent { KimiTheme { ActivityStatusLine(status.value, since) } }
        composeTestRule.onNodeWithText("Thinking… · 00:00").assertIsDisplayed()

        status.value = null
        composeTestRule.onNodeWithText("Thinking…", substring = true).assertDoesNotExist()
    }

    @Test
    fun aPhaseWithoutAStartShowsItsBareLabel() {
        composeTestRule.setContent {
            KimiTheme { ActivityStatusLine(ChatViewModel.ActivityStatus("Waiting for approval"), since) }
        }

        composeTestRule.onNodeWithText("Waiting for approval").assertIsDisplayed()
    }
}
