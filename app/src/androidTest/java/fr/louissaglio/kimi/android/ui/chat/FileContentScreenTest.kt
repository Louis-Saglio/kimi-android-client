package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.data.chat.ToolPayload
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the Read/Write file content screen (ticket 120). The screen
 * is ViewModel-free: tests hand the staged store entry directly, like
 * EditDiffScreenTest stages its ToolContentStore. */
@RunWith(AndroidJUnit4::class)
class FileContentScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Sets the screen's content with [entry] as the staged store entry (null is the
     * miss case). */
    private fun fileContent(entry: ToolContentStore.Entry?, onNavigateBack: () -> Unit = {}) {
        composeTestRule.setContent {
            KimiTheme {
                FileContentRoute("tool_1", entry, onNavigateBack = onNavigateBack)
            }
        }
    }

    @Test
    fun writeShowsTheCallsContentVerbatimWithPathTitle() {
        fileContent(
            ToolContentStore.Entry(ToolPayload.Write("/tmp/spike/a.txt", "one\ntwo\nthree", append = false)),
        )

        composeTestRule.onNodeWithText("a.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("/tmp/spike/a.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("one\ntwo\nthree").assertIsDisplayed()
    }

    @Test
    fun readShowsTheOutputVerbatimAndLiftsTheSystemBlockIntoACaption() {
        fileContent(
            ToolContentStore.Entry(
                ToolPayload.Read("/tmp/spike/a.txt", offset = 1, limit = 2),
                output = "     1→one\n     2→two\n<system>truncated: showing lines 1-2 of 99</system>",
            ),
        )

        // Line-number prefixes stay in the body, verbatim.
        composeTestRule.onNodeWithText("     1→one\n     2→two").assertIsDisplayed()
        composeTestRule.onNodeWithText("truncated: showing lines 1-2 of 99").assertIsDisplayed()
        // The block left the body: no node carries the tags anymore.
        composeTestRule.onNode(hasText("<system>", substring = true)).assertDoesNotExist()
    }

    @Test
    fun readWithAMalformedSystemBlockShowsTheOutputUntouched() {
        val output = "     1→one\n<system>not trailing</system>\n     2→two"
        fileContent(
            ToolContentStore.Entry(
                ToolPayload.Read("/tmp/spike/a.txt", offset = null, limit = null),
                output = output,
            ),
        )

        composeTestRule.onNodeWithText(output).assertIsDisplayed()
        // No caption chip was lifted out of the body.
        composeTestRule.onNodeWithText("not trailing").assertDoesNotExist()
    }

    @Test
    fun readTappedWhileRunningShowsThePlaceholder() {
        fileContent(
            ToolContentStore.Entry(ToolPayload.Read("/tmp/spike/a.txt", offset = null, limit = null), output = ""),
        )

        composeTestRule.onNodeWithText("Call still running — no content yet").assertIsDisplayed()
        // The path title still renders.
        composeTestRule.onNodeWithText("a.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("/tmp/spike/a.txt").assertIsDisplayed()
    }

    @Test
    fun missingStoreEntryShowsTheErrorStateAndBackNavigates() {
        var backed = false
        fileContent(null, onNavigateBack = { backed = true })

        composeTestRule.onNodeWithText("This file content is no longer available — reopen it from the chat.")
            .assertIsDisplayed()

        // The error state's Back button (the app bar's back arrow carries no text).
        composeTestRule.onNodeWithText("Back").performClick()
        assertTrue(backed)
    }
}
