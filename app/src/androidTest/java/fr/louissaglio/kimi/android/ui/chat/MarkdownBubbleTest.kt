package fr.louissaglio.kimi.android.ui.chat

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for markdown rendering in message bubbles (ticket 020). */
@RunWith(AndroidJUnit4::class)
class MarkdownBubbleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun assistantBubble(markdown: String) {
        composeTestRule.setContent {
            KimiTheme {
                AssistantBubble(ChatItem.AssistantMessage("msg_1", markdown, thinking = ""))
            }
        }
    }

    @Test
    fun fullMarkdownSubsetRendersFormatted() {
        assistantBubble(
            """
            ## Heading two

            Some **bold words** and *italic words* plus `inline code` here.

            - bullet one
            - bullet two

            1. numbered one
            2. numbered two

            ```
            fenced code block
            ```
            """.trimIndent(),
        )

        composeTestRule.onNodeWithText("Heading two", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("bold words", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("italic words", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("inline code", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("bullet one", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("numbered one", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("fenced code block", substring = true).assertIsDisplayed()
        // Raw markdown syntax must not survive rendering.
        composeTestRule.onNodeWithText("**", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("##", substring = true).assertDoesNotExist()
    }

    @Test
    fun tappingLinkOpensUriInBrowser() {
        val openedUris = mutableListOf<String>()
        composeTestRule.setContent {
            KimiTheme {
                // On device this is the platform handler firing ACTION_VIEW; the fake
                // stands in for "the device's default browser".
                CompositionLocalProvider(
                    LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) {
                            openedUris += uri
                        }
                    },
                ) {
                    AssistantBubble(
                        ChatItem.AssistantMessage("msg_1", "[Open example](https://example.com)", thinking = ""),
                    )
                }
            }
        }

        // The library detects link taps with raw pointer input, not a semantics click
        // action, so inject a real touch at the node center instead of performClick().
        composeTestRule.onNodeWithText("Open example").performTouchInput { click() }

        assertEquals(listOf("https://example.com"), openedUris)
    }

    @Test
    fun streamingDeltaEndsWithFormattedText() {
        val streamed = mutableStateOf("Fix the `NPE` in **MyFi")
        composeTestRule.setContent {
            KimiTheme {
                AssistantBubble(ChatItem.AssistantMessage("msg_1", streamed.value, thinking = ""))
            }
        }

        composeTestRule.runOnIdle { streamed.value = "Fix the `NPE` in **MyFile.kt** now" }

        composeTestRule.onNodeWithText("MyFile.kt", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("**", substring = true).assertDoesNotExist()
    }

    @Test
    fun userBubbleRendersMarkdownToo() {
        composeTestRule.setContent {
            KimiTheme {
                UserBubble(ChatItem.UserMessage("u1", "Deploy with **force** please"))
            }
        }

        composeTestRule.onNodeWithText("force", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("**", substring = true).assertDoesNotExist()
    }

    // Ticket 024: the copy action under an assistant message must place the raw
    // markdown source — not the rendered text — on the device clipboard.
    @Test
    fun copyActionPutsRawMarkdownOnClipboard() {
        val markdown = "Fix it **now** with `sudo make`"
        assistantBubble(markdown)

        composeTestRule.onNodeWithContentDescription("Copy message").performClick()

        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(ClipboardManager::class.java)
        assertEquals(markdown, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    // Ticket 021: a code block line longer than the screen must scroll
    // horizontally (the library's horizontalScroll exposes axis-range semantics)
    // instead of wrapping.
    @Test
    fun longCodeLineScrollsHorizontally() {
        val longLine = "val result = someFunction(" + "argument, ".repeat(30) + ")"
        assistantBubble("```kotlin\n$longLine\n```")

        val node = composeTestRule.onNodeWithText("argument,", substring = true).fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsProperties.HorizontalScrollAxisRange))
    }

    // Ticket 021: heading scale is capped — a `#` must not render at the library
    // default (M3 displayLarge, 57sp).
    @Test
    fun headingFontSizeIsCapped() {
        assistantBubble("# Big heading")

        val text = composeTestRule.onNodeWithText("Big heading")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .first()
        val sizes = text.spanStyles.map { it.item.fontSize }.filter { it != TextUnit.Unspecified }
        assertTrue("expected a sized span on the heading", sizes.isNotEmpty())
        assertTrue("heading too large: $sizes", sizes.all { it <= 26.sp })
    }
}
