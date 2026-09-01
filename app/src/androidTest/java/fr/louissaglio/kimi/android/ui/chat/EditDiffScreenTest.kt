package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.data.chat.ToolPayload
import fr.louissaglio.kimi.android.navigation.EditDiff
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the Edit diff screen (ticket 119). The ViewModel is built
 * directly (route object + a hand-staged ToolContentStore) — no Hilt in these
 * tests, the JobDetailViewModel construction pattern. */
@RunWith(AndroidJUnit4::class)
class EditDiffScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Stages [payload] under the route's id (null stages nothing — the miss case)
     * and sets the screen's content. */
    private fun editDiffScreen(payload: ToolPayload.Edit?, onNavigateBack: () -> Unit = {}) {
        val store = ToolContentStore()
        if (payload != null) store.put("tool_1", payload)
        composeTestRule.setContent {
            KimiTheme {
                EditDiffScreen(
                    onNavigateBack = onNavigateBack,
                    viewModel = EditDiffViewModel(store, EditDiff("tool_1")),
                )
            }
        }
    }

    @Test
    fun topBarShowsFilenameFullPathAndStats() {
        editDiffScreen(ToolPayload.Edit("/tmp/spike/a.txt", "one\ntwo", "one\nTWO", replaceAll = false))

        composeTestRule.onNodeWithText("a.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("/tmp/spike/a.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("+1").assertIsDisplayed()
        composeTestRule.onNodeWithText("−1").assertIsDisplayed()
    }

    @Test
    fun removedAddedAndContextLinesRenderWithTheirGutters() {
        editDiffScreen(ToolPayload.Edit("/tmp/spike/a.txt", "one\ntwo", "one\nTWO", replaceAll = false))

        composeTestRule.onNodeWithText("one").assertIsDisplayed()
        composeTestRule.onNodeWithText("two").assertIsDisplayed()
        composeTestRule.onNodeWithText("TWO").assertIsDisplayed()
        // Exact matches: the title chip's "+1"/"−1" are distinct nodes.
        composeTestRule.onNodeWithText("−").assertIsDisplayed()
        composeTestRule.onNodeWithText("+").assertIsDisplayed()
    }

    @Test
    fun contextExpanderRevealsTheHiddenLines() {
        editDiffScreen(
            ToolPayload.Edit("/tmp/spike/a.txt", "c1\nc2\nc3\nc4\nold", "c1\nc2\nc3\nc4\nnew", replaceAll = false),
        )

        composeTestRule.onNodeWithText("4 unchanged lines").assertIsDisplayed()
        composeTestRule.onNodeWithText("c3").assertDoesNotExist()

        composeTestRule.onNodeWithText("4 unchanged lines").performClick()

        composeTestRule.onNodeWithText("c3").assertIsDisplayed()
    }

    @Test
    fun identicalOldAndNewShowsNoChanges() {
        editDiffScreen(ToolPayload.Edit("/tmp/spike/a.txt", "same\nlines", "same\nlines", replaceAll = false))

        composeTestRule.onNodeWithText("No changes").assertIsDisplayed()
    }

    @Test
    fun missingStoreEntryShowsTheErrorStateAndBackNavigates() {
        var backed = false
        editDiffScreen(null, onNavigateBack = { backed = true })

        composeTestRule.onNodeWithText("This diff is no longer available — reopen it from the chat.")
            .assertIsDisplayed()

        // The error state's Back button (the app bar's back arrow carries no text).
        composeTestRule.onNodeWithText("Back").performClick()
        assertTrue(backed)
    }
}
