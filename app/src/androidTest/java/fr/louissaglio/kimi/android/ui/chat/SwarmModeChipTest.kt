package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** The composer's swarm chip (ticket 174): a toggle with a stable test tag and the
 * "Toggle swarm mode" content description, and a send made while it is on hands
 * `swarmMode = true` to the send path (the fake below stands in for
 * `ChatViewModel.onSend` → `ChatRepository.sendPrompt`, whose body is covered by the
 * JVM tests). */
@RunWith(AndroidJUnit4::class)
class SwarmModeChipTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Every send's swarm flag, in order — the repository fake's record. */
    private val sentSwarmModes = mutableListOf<Boolean>()

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
                    onSend = { sentSwarmModes += swarmMode },
                    onStop = {},
                    onAttach = {},
                    onAttachmentRemoved = {},
                )
            }
        }
    }

    @Test
    fun chipExposesItsTagAndContentDescriptionAndToggles() {
        inputBar()

        composeTestRule.onNodeWithContentDescription("Toggle swarm mode").assertIsDisplayed()
        val chip = composeTestRule.onNodeWithTag(SWARM_MODE_CHIP_TAG)
        chip.assertIsOff()

        chip.performClick()
        chip.assertIsOn()

        chip.performClick()
        chip.assertIsOff()
    }

    @Test
    fun aSendWithTheChipOnCarriesSwarmModeTrue() {
        inputBar()

        composeTestRule.onNodeWithTag(SWARM_MODE_CHIP_TAG).performClick()
        composeTestRule.onNodeWithContentDescription("Send").performClick()
        composeTestRule.onNodeWithTag(SWARM_MODE_CHIP_TAG).performClick()
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        assertEquals(listOf(true, false), sentSwarmModes)
    }

    @Test
    fun chipStartsOnWhenTheSessionIsInSwarmMode() {
        inputBar(initialSwarmMode = true)

        composeTestRule.onNodeWithTag(SWARM_MODE_CHIP_TAG).assertIsOn()
    }
}
