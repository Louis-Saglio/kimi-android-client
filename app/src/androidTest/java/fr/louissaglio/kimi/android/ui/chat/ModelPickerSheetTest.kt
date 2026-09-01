package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.data.chat.ServerModel
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the model picker sheet (ticket 108): provider grouping and
 * collapse, display-name disambiguation, steady sheet on taps. The fixture mirrors
 * Louis's screenshot: two providers serving models with the same "K3-256k" display
 * name. */
@RunWith(AndroidJUnit4::class)
class ModelPickerSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val models = listOf(
        ServerModel("managed:kimi-code", "kimi-code/kimi-for-coding", "K2.7 Coding", 262_144),
        ServerModel(
            "managed:kimi-code", "kimi-code/k3-256k", "K3-256k", 262_144,
            supportEfforts = listOf("low", "high"), defaultEffort = "high",
        ),
        ServerModel("managed:openrouter", "openrouter/k3-256k", "K3-256k", 262_144),
    )

    private fun sheet(
        onModelSelected: (String) -> Unit = {},
        onEffortSelected: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            KimiTheme {
                // Stateful stand-in for the ViewModel: taps move the checks.
                var current by remember { mutableStateOf("kimi-code/k3-256k") }
                var effort by remember { mutableStateOf<String?>(null) }
                ModelPickerSheet(
                    availableModels = models,
                    currentModelId = current,
                    serverDefaultModel = "kimi-code/k3-256k",
                    supportedEfforts = listOf("low", "high"),
                    selectedEffort = effort,
                    defaultEffort = "high",
                    onModelSelected = { current = it; onModelSelected(it) },
                    onEffortSelected = { effort = it; onEffortSelected(it) },
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun collidingDisplayNamesRenderUnderTheirProviderWithTheModelIdInTheSubtitle() {
        sheet()
        // The openrouter group starts collapsed — its current model is elsewhere.
        composeTestRule.onNodeWithText("managed:openrouter").performClick()

        composeTestRule.onNodeWithText("managed:kimi-code").assertIsDisplayed()
        composeTestRule.onNodeWithText("managed:openrouter").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("K3-256k").assertCountEquals(2)
        composeTestRule.onNodeWithText("262.1k context · kimi-code/k3-256k").assertIsDisplayed()
        composeTestRule.onNodeWithText("262.1k context · openrouter/k3-256k").assertIsDisplayed()
        // A unique display name keeps the clean subtitle.
        composeTestRule.onNodeWithText("262.1k context").assertIsDisplayed()
    }

    @Test
    fun onlyTheEffectiveModelsGroupStartsExpandedAndHeadersToggleIt() {
        sheet()

        // Current model is kimi-code/k3-256k: its group shows its rows, the other
        // provider's rows are not composed.
        composeTestRule.onNodeWithText("K2.7 Coding").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("262.1k context · openrouter/k3-256k")
            .assertDoesNotExist()

        composeTestRule.onNodeWithText("managed:openrouter").performClick()
        composeTestRule
            .onNodeWithText("262.1k context · openrouter/k3-256k")
            .assertIsDisplayed()

        composeTestRule.onNodeWithText("managed:openrouter").performClick()
        composeTestRule
            .onNodeWithText("262.1k context · openrouter/k3-256k")
            .assertDoesNotExist()
    }

    @Test
    fun modelAndEffortTapsKeepTheSheetOpenWithBothSelectionsVisible() {
        var pickedModel: String? = null
        var pickedEffort: String? = null
        sheet(onModelSelected = { pickedModel = it }, onEffortSelected = { pickedEffort = it })

        composeTestRule.onNodeWithText("managed:openrouter").performClick()
        composeTestRule.onNodeWithText("262.1k context · openrouter/k3-256k").performClick()
        assertEquals("openrouter/k3-256k", pickedModel)
        // The tap did not dismiss: the effort row is still there.
        composeTestRule.onNodeWithText("Thinking").assertIsDisplayed()

        composeTestRule.onNode(hasText("low") and hasClickAction()).performClick()
        assertEquals("low", pickedEffort)
        // The picked model row carries the only check; the effort shows as a segment.
        composeTestRule.onAllNodesWithText("✓").assertCountEquals(1)
        composeTestRule.onNodeWithText("Thinking").assertIsDisplayed()
    }
}
