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

/** Semantics tests for the composer's permission-mode chip (ticket 141): the chip is
 * icon-only, the mode's label lives in the contentDescription instead of on the chip;
 * null hides the chip. */
@RunWith(AndroidJUnit4::class)
class PermissionModeChipTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun inputBar(permissionMode: PermissionMode?) {
        composeTestRule.setContent {
            KimiTheme {
                ChatInputBar(
                    text = "",
                    isSending = false,
                    isWorking = false,
                    attachments = emptyList(),
                    modelLabel = null,
                    modelEffort = null,
                    permissionMode = permissionMode,
                    contextUsage = null,
                    compactEnabled = false,
                    onCompact = {},
                    onModelClick = {},
                    onPermissionClick = {},
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
    fun chipCarriesTheModeInItsContentDescriptionOnly() {
        inputBar(permissionMode = PermissionMode.AskWhenNeeded)

        composeTestRule.onNodeWithContentDescription("Permission mode: Ask When Needed").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ask When Needed").assertDoesNotExist()
    }

    @Test
    fun chipIsHiddenWhileTheModeIsUnknown() {
        inputBar(permissionMode = null)

        composeTestRule.onNodeWithContentDescription("Permission mode: Always Ask").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Permission mode: Ask When Needed").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Permission mode: Never Ask").assertDoesNotExist()
    }
}
