package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the composer's model chip (ticket 108): the effective effort
 * sits on the label ("K3 · high") and in the contentDescription. */
@RunWith(AndroidJUnit4::class)
class ModelChipTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun inputBar(modelLabel: String, modelEffort: String? = null) {
        composeTestRule.setContent {
            KimiTheme {
                ChatInputBar(
                    text = "",
                    isSending = false,
                    isWorking = false,
                    attachments = emptyList(),
                    modelLabel = modelLabel,
                    modelEffort = modelEffort,
                    permissionMode = null,
                    swarmMode = false,
                    contextUsage = null,
                    compactEnabled = false,
                    onCompact = {},
                    onModelClick = {},
                    onPermissionClick = {},
                    onSwarmModeToggle = {},
                    onTextChange = {},
                    onSend = {},
                    onStop = {},
                    onAttach = {},
                    onAttachmentRemoved = {},
                )
            }
        }
    }

    @Test
    fun chipShowsTheEffectiveEffortNextToTheModel() {
        inputBar(modelLabel = "K3", modelEffort = "high")

        composeTestRule.onNodeWithText("K3 · high").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Model: K3, effort high").assertIsDisplayed()
    }

    @Test
    fun chipOmitsTheEffortForEffortLessModels() {
        inputBar(modelLabel = "K2.7 Coding")

        composeTestRule.onNodeWithText("K2.7 Coding").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Model: K2.7 Coding").assertIsDisplayed()
    }
}
