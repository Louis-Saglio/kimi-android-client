package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.data.chat.Todo
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the todo widget (ticket 042): summary row + detail sheet. */
@RunWith(AndroidJUnit4::class)
class TodosWidgetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val todos = listOf(
        Todo("Write the parser", Todo.Status.Done),
        Todo("Wire the timeline", Todo.Status.Done),
        Todo("Build the widget", Todo.Status.InProgress),
        Todo("Ship it", Todo.Status.Pending),
    )

    private fun summaryRow(onClick: () -> Unit = {}) {
        composeTestRule.setContent { KimiTheme { TodosSummaryRow(todos, onClick) } }
    }

    @Test
    fun summaryRowShowsDoneCountOverTotal() {
        summaryRow()

        composeTestRule.onNodeWithText("Todos · 2/4").assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription("Todos, 2 of 4 done, tap to view")
            .assertIsDisplayed()
    }

    @Test
    fun summaryRowWrapsContentInsteadOfFillingWidth() {
        // A full-size parent stands in for the composer's bottomBar column: with
        // createComposeRule the root wraps the content, so a full-bleed Box is the
        // only reliable full-width reference.
        composeTestRule.setContent {
            KimiTheme {
                Box(Modifier.fillMaxSize()) { TodosSummaryRow(todos, onClick = {}) }
            }
        }

        val rowBounds = composeTestRule
            .onNodeWithContentDescription("Todos, 2 of 4 done, tap to view")
            .getBoundsInRoot()
        val screenBounds = composeTestRule.onRoot().getBoundsInRoot()
        assertTrue(
            "widget should wrap its content, not fill the screen " +
                "(row=$rowBounds, root=$screenBounds)",
            rowBounds.right - rowBounds.left < screenBounds.right - screenBounds.left,
        )
    }

    @Test
    fun tappingSummaryRowInvokesCallback() {
        var clicked = false
        summaryRow(onClick = { clicked = true })

        composeTestRule
            .onNodeWithContentDescription("Todos, 2 of 4 done, tap to view")
            .performClick()
        assertTrue(clicked)
    }

    @Test
    fun sheetListsEveryTodoUnderACountHeading() {
        composeTestRule.setContent { KimiTheme { TodosSheet(todos, onDismiss = {}) } }

        composeTestRule.onNodeWithText("Todos · 2/4").assertIsDisplayed()
        todos.forEach { composeTestRule.onNodeWithText(it.title).assertIsDisplayed() }
    }
}
