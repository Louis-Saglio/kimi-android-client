package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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

/** Semantics tests for the question card: read-only mode (ticket 089), multi-select
 * checkbox rows (090), the unchanged single-select radio path, and the sub-agent
 * badge (156 — the approval badge's idiom, 139). */
@RunWith(AndroidJUnit4::class)
class QuestionCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val checkboxes = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
    private val radioButtons = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private val question = PendingQuestion(
        questionId = "tool_q1",
        toolCallId = "tool_q1",
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

    private val multiQuestion = PendingQuestion(
        questionId = "tool_q1",
        toolCallId = "tool_q1",
        items = listOf(
            PendingQuestion.Item(
                id = "q_0",
                header = "Palette",
                text = "Colors?",
                options = listOf(
                    PendingQuestion.Option("opt_0_0", "Red", null),
                    PendingQuestion.Option("opt_0_1", "Green", null),
                    PendingQuestion.Option("opt_0_2", "Blue", null),
                ),
                allowOther = true,
                multiSelect = true,
            ),
        ),
    )

    private fun card(
        question: PendingQuestion,
        selections: Map<String, Set<String>>,
        isSubmittable: Boolean,
        subagentName: String? = null,
        readOnly: Boolean = false,
        note: String? = null,
        onOptionPicked: (String, String) -> Unit = { _, _ -> },
        onOptionToggled: (String, String) -> Unit = { _, _ -> },
        onClose: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            KimiTheme {
                QuestionCard(
                    question = question,
                    queueSize = 1,
                    subagentName = subagentName,
                    step = 0,
                    selections = selections,
                    otherTexts = emptyMap(),
                    isStepAnswered = true,
                    isSubmittable = isSubmittable,
                    onOptionPicked = onOptionPicked,
                    onOptionToggled = onOptionToggled,
                    onOtherTextChange = { _, _ -> },
                    onNext = {},
                    onBack = {},
                    onSubmit = {},
                    onDismiss = {},
                    onMinimize = {},
                    readOnly = readOnly,
                    note = note,
                    onClose = onClose,
                )
            }
        }
    }

    @Test
    fun readOnlyCardShowsTheOutcomeAndNoActionButtons() {
        var closed = false
        card(question, mapOf("q_0" to setOf("opt_0_1")), isSubmittable = false, readOnly = true, onClose = { closed = true })

        composeTestRule.onNodeWithText("Question from Kimi · answered").assertIsDisplayed()
        composeTestRule.onNodeWithText("Submit").assertDoesNotExist()
        composeTestRule.onNodeWithText("Dismiss").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Minimize question").assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Close").performClick()
        assertEquals(true, closed)
    }

    @Test
    fun readOnlyDismissedCardShowsTheNote() {
        card(
            question,
            mapOf("q_0" to setOf("opt_0_1")),
            isSubmittable = false,
            readOnly = true,
            note = "User dismissed the question without answering.",
        )

        composeTestRule.onNodeWithText("Question from Kimi · dismissed").assertIsDisplayed()
        composeTestRule.onNodeWithText("User dismissed the question without answering.").assertIsDisplayed()
    }

    @Test
    fun singleSelectItemRendersRadioButtonsThatPick() {
        val picks = mutableListOf<Pair<String, String>>()
        card(question, emptyMap(), isSubmittable = false, onOptionPicked = { item, option -> picks += item to option })

        composeTestRule.onAllNodes(radioButtons).assertCountEquals(2)
        composeTestRule.onAllNodes(checkboxes).assertCountEquals(0)

        composeTestRule.onAllNodes(radioButtons)[1].performClick()
        assertEquals(listOf("q_0" to "opt_0_1"), picks)
    }

    @Test
    fun multiSelectItemRendersCheckboxesThatToggle() {
        val toggles = mutableListOf<Pair<String, String>>()
        card(multiQuestion, emptyMap(), isSubmittable = false, onOptionToggled = { item, option -> toggles += item to option })

        composeTestRule.onAllNodes(checkboxes).assertCountEquals(3)
        composeTestRule.onAllNodes(radioButtons).assertCountEquals(0)

        composeTestRule.onAllNodes(checkboxes)[0].performClick()
        composeTestRule.onAllNodes(checkboxes)[2].performClick()
        assertEquals(listOf("q_0" to "opt_0_0", "q_0" to "opt_0_2"), toggles)

        // Zero ticks: the item is not submittable.
        composeTestRule.onNodeWithText("Submit").assertIsNotEnabled()
    }

    @Test
    fun tickedMultiOptionsAreCheckedAndSubmittable() {
        card(multiQuestion, mapOf("q_0" to setOf("opt_0_0", "opt_0_2")), isSubmittable = true)

        composeTestRule.onAllNodes(checkboxes)[0].assertIsOn()
        composeTestRule.onAllNodes(checkboxes)[1].assertIsOff()
        composeTestRule.onAllNodes(checkboxes)[2].assertIsOn()
        composeTestRule.onNodeWithText("Submit").assertIsEnabled()
    }

    @Test
    fun readOnlyMultiCardShowsCheckedDisabledBoxes() {
        card(multiQuestion, mapOf("q_0" to setOf("opt_0_0", "opt_0_2")), isSubmittable = false, readOnly = true)

        composeTestRule.onAllNodes(checkboxes).assertCountEquals(3)
        composeTestRule.onAllNodes(checkboxes)[0].assertIsOn().assertIsNotEnabled()
        composeTestRule.onAllNodes(checkboxes)[1].assertIsOff().assertIsNotEnabled()
        composeTestRule.onAllNodes(checkboxes)[2].assertIsOn().assertIsNotEnabled()
        composeTestRule.onNodeWithText("Question from Kimi · answered").assertIsDisplayed()
    }

    @Test
    fun subagentQuestionShowsTheMemberNameBadge() {
        card(question.copy(agentId = "agent-1"), emptyMap(), isSubmittable = false, subagentName = "explore")

        composeTestRule.onNodeWithText("Sub-agent · explore").assertIsDisplayed()
    }

    @Test
    fun subagentQuestionWithoutAStoreRecordShowsThePlainBadge() {
        card(question.copy(agentId = "agent-1"), emptyMap(), isSubmittable = false)

        composeTestRule.onNodeWithText("Sub-agent").assertIsDisplayed()
    }

    @Test
    fun mainAgentQuestionShowsNoBadge() {
        card(question, emptyMap(), isSubmittable = false)

        composeTestRule.onNodeWithText("Sub-agent").assertDoesNotExist()
        // The rest of the card is unchanged.
        composeTestRule.onNodeWithText("Question from Kimi").assertIsDisplayed()
        composeTestRule.onNodeWithText("Red or blue?").assertIsDisplayed()
    }
}
