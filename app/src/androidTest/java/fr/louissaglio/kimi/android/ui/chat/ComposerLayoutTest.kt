package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.data.chat.ContextUsage
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regression guard for the composer-row overflow: with every element present (long
 * model label and the ticket-174 swarm chip included) the send button used to be
 * clipped past the row's end. The turn controls are pinned to the row's ends and the
 * model chip ellipsizes inside the weighted middle zone, so every right edge must stay
 * within the root's. */
@RunWith(AndroidJUnit4::class)
class ComposerLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** [width] pins the bar to a phone width; null leaves it at the device's. */
    private fun inputBar(isWorking: Boolean, width: Dp? = null) {
        composeTestRule.setContent {
            KimiTheme {
                Box(if (width != null) Modifier.width(width) else Modifier) {
                    ChatInputBar(
                        text = "",
                        isSending = false,
                        isWorking = isWorking,
                        attachments = emptyList(),
                        modelLabel = "K3-256k-with-a-much-longer-model-name",
                        modelEffort = "maximum",
                        permissionMode = PermissionMode.NeverAsk,
                        swarmMode = true,
                        contextUsage = ContextUsage(usedTokens = 100_000, maxTokens = 256_000),
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
    }

    private fun SemanticsNodeInteraction.assertRightEdgeWithinRoot() {
        val rootRight = composeTestRule.onRoot().getUnclippedBoundsInRoot().right
        val right = getUnclippedBoundsInRoot().right
        assertTrue("right edge $right must not pass the root's right edge $rootRight", right <= rootRight)
    }

    @Test
    fun sendStaysOnScreenWithAllComposerElementsPresent() {
        inputBar(isWorking = false)

        composeTestRule.onNodeWithContentDescription("Send").assertRightEdgeWithinRoot()
        composeTestRule.onNodeWithContentDescription("Swarm mode").assertRightEdgeWithinRoot()
    }

    @Test
    fun stopAndSteerStayOnScreenWithAllComposerElementsPresent() {
        inputBar(isWorking = true)

        composeTestRule.onNodeWithContentDescription("Stop").assertRightEdgeWithinRoot()
        composeTestRule.onNodeWithText("Steer").assertRightEdgeWithinRoot()
        composeTestRule.onNodeWithContentDescription("Swarm mode").assertRightEdgeWithinRoot()
    }

    /** Ticket 185 (C1): on a 360dp phone the swarm chip must not starve the model chip
     * when idle, and mid-turn (Stop + Steer pinned) the icon-sized middle-zone controls
     * keep their 48dp touch targets — only the model chip gives. */
    @Test
    fun onA360dpPhoneTheModelChipStaysTappableWhenIdle() {
        inputBar(isWorking = false, width = 360.dp)

        composeTestRule.onNodeWithContentDescription("Model:", substring = true).assertWidthIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription("Swarm mode").assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun onA360dpPhoneMidTurnTheChipsKeepTheirTouchTargets() {
        inputBar(isWorking = true, width = 360.dp)

        composeTestRule.onNodeWithContentDescription("Swarm mode").assertWidthIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription("Permission mode:", substring = true).assertWidthIsAtLeast(48.dp)
        composeTestRule.onNodeWithContentDescription("Context usage:", substring = true).assertWidthIsAtLeast(48.dp)
    }
}
