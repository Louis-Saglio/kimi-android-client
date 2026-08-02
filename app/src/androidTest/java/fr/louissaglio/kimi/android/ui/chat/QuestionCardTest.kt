package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.data.chat.PendingQuestion
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the read-only mode of the question card (ticket 089). */
@RunWith(AndroidJUnit4::class)
class QuestionCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val question = PendingQuestion(
        questionId = "tool_q1",
        items = listOf(
            PendingQuestion.Item(
                id = "q_0",
                header = "Color",
                text = "Red or blue?",
                options = listOf(
                    PendingQuestion.Option("opt_0_0", "red", "warm color"),
                    PendingQuestion.Option("opt_0_1", "blue", "cool color"),
                ),
                allowOther = true,
            ),
        ),
    )

    private fun readOnlyCard(
        note: String? = null,
        onClose: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            KimiTheme {
                QuestionCard(
                    question = question,
                    queueSize = 1,
                    step = 0,
                    selections = mapOf("q_0" to "opt_0_1"),
                    otherTexts = emptyMap(),
                    isStepAnswered = true,
                    isSubmittable = false,
                    onOptionPicked = { _, _ -> },
                    onOtherTextChange = { _, _ -> },
                    onNext = {},
                    onBack = {},
                    onSubmit = {},
                    onDismiss = {},
                    onMinimize = {},
                    readOnly = true,
                    note = note,
                    onClose = onClose,
                )
            }
        }
    }

    @Test
    fun readOnlyCardShowsTheOutcomeAndNoActionButtons() {
        var closed = false
        readOnlyCard(onClose = { closed = true })

        composeTestRule.onNodeWithText("Question from Kimi · answered").assertIsDisplayed()
        composeTestRule.onNodeWithText("Submit").assertDoesNotExist()
        composeTestRule.onNodeWithText("Dismiss").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Minimize question").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Close").performClick()
        assertEquals(true, closed)
    }

    @Test
    fun readOnlyDismissedCardShowsTheNote() {
        readOnlyCard(note = "User dismissed the question without answering.")

        composeTestRule.onNodeWithText("Question from Kimi · dismissed").assertIsDisplayed()
        composeTestRule.onNodeWithText("User dismissed the question without answering.").assertIsDisplayed()
    }
}
