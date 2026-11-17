package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The composer's swarm chip (ticket 174): an M3 icon toggle button (Checkbox role,
 * ticket 185) found by its "Swarm mode" content description (icon-only since ticket
 * 185), plus the stable test tag
 * ticket 174 promised. The chip's state reaching the prompt (`swarm_mode`) is not a
 * Compose concern — `ChatInputBar.onSend` carries no swarm argument — and is covered on
 * the JVM: `ChatViewModelTest` ("…a send carries it as swarm_mode true", "toggling the
 * chip off sends swarm_mode false explicitly") and `ChatRepositoryTest` ("sendPrompt
 * carries swarm_mode true…"). */
@RunWith(AndroidJUnit4::class)
class SwarmModeChipTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var sends = 0

    private fun inputBar(initialSwarmMode: Boolean = false) {
        composeTestRule.setContent {
            var swarmMode by remember { mutableStateOf(initialSwarmMode) }
            KimiTheme {
                ChatInputBar(
                    text = "review these three files",
                    isSending = false,
                    isWorking = false,
                    attachments = emptyList(),
                    modelLabel = null,
                    modelEffort = null,
                    permissionMode = null,
                    swarmMode = swarmMode,
                    contextUsage = null,
                    compactEnabled = false,
                    onCompact = {},
                    onModelClick = {},
                    onPermissionClick = {},
                    onSwarmModeToggle = { swarmMode = !swarmMode },
                    onTextChange = {},
                    onSend = { sends++ },
                    onStop = {},
                    onAttach = {},
                    onAttachmentRemoved = {},
                )
            }
        }
    }

    private fun chip() = composeTestRule.onNodeWithContentDescription("Swarm mode")

    @Test
    fun chipIsAnAccessibleToggleWithItsStableTag() {
        inputBar()

        chip().assertIsDisplayed()
        chip().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        chip().assertIsOff()

        chip().performClick()
        chip().assertIsOn()

        chip().performClick()
        chip().assertIsOff()
        // Ticket 174's stable tag stays on the same node.
        composeTestRule.onNodeWithTag(SWARM_MODE_CHIP_TAG).assertIsOff()
    }

    /** Wiring only: toggling never sends, and Send is still one onSend with the chip
     * on. The swarm flag on that send is the JVM tests' (see the class KDoc). */
    @Test
    fun togglingTheChipNeverSendsAndSendInvokesOnSendOnce() {
        inputBar()

        chip().performClick()
        composeTestRule.runOnIdle { assertEquals(0, sends) }

        composeTestRule.onNodeWithContentDescription("Send").performClick()
        composeTestRule.runOnIdle { assertEquals(1, sends) }
        chip().assertIsOn()
    }

    @Test
    fun chipStartsOnWhenTheSessionIsInSwarmMode() {
        inputBar(initialSwarmMode = true)

        chip().assertIsOn()
    }
}
