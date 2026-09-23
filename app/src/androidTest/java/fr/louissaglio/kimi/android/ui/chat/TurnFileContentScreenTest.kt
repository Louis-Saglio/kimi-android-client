package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.data.chat.FileContent
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the turn file before/after viewer (ticket 150): the stateless
 * [TurnFileContentScreen] body is driven with hand-made states — no Hilt, no network
 * (the TurnChangesScreenTest construction pattern). The tab toggle is driven by a
 * test-owned state, so clicks recompose like the ViewModel would (SwarmScreenTest). */
@RunWith(AndroidJUnit4::class)
class TurnFileContentScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun turnFileContentScreen(initial: TurnFileContentViewModel.UiState) {
        composeTestRule.setContent {
            KimiTheme {
                var uiState by remember { mutableStateOf(initial) }
                TurnFileContentScreen(
                    uiState,
                    onNavigateBack = {},
                    onTabSelected = { tab -> uiState = uiState.copy(tab = tab) },
                )
            }
        }
    }

    @Test
    fun bothSidesRenderThroughTheTabToggleWithAfterTheDefault() {
        turnFileContentScreen(
            TurnFileContentViewModel.UiState(
                path = "/home/ubuntu/spike/notes.txt",
                before = TurnFileContentViewModel.Side(
                    resolved = true,
                    snapshot = FileContent(version = 3, content = "before body line", binary = false),
                ),
                after = TurnFileContentViewModel.Side(
                    resolved = true,
                    snapshot = FileContent(version = 4, content = "after body line", binary = false),
                ),
            ),
        )

        // Title: basename + full path; the After side is the default.
        composeTestRule.onNodeWithText("notes.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("/home/ubuntu/spike/notes.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("after body line").assertIsDisplayed()
        composeTestRule.onNodeWithText("before body line").assertDoesNotExist()

        composeTestRule.onNodeWithText("Before").performClick()

        composeTestRule.onNodeWithText("before body line").assertIsDisplayed()
        composeTestRule.onNodeWithText("after body line").assertDoesNotExist()
    }

    @Test
    fun nullSidesRenderTheirPlaceholders() {
        turnFileContentScreen(
            TurnFileContentViewModel.UiState(
                path = "/tmp/new.txt",
                before = TurnFileContentViewModel.Side(resolved = true, snapshot = null),
                after = TurnFileContentViewModel.Side(resolved = true, snapshot = null),
            ),
        )

        // After is the default tab: a null After means the file is gone.
        composeTestRule.onNodeWithText("File deleted").assertIsDisplayed()

        composeTestRule.onNodeWithText("Before").performClick()

        composeTestRule.onNodeWithText("File did not exist yet").assertIsDisplayed()
    }

    @Test
    fun aBinarySideRendersThePlaceholder() {
        turnFileContentScreen(
            TurnFileContentViewModel.UiState(
                path = "/tmp/logo.png",
                after = TurnFileContentViewModel.Side(
                    resolved = true,
                    snapshot = FileContent(version = 1, content = null, binary = true),
                ),
            ),
        )

        composeTestRule.onNodeWithText("Binary file").assertIsDisplayed()
    }
}
