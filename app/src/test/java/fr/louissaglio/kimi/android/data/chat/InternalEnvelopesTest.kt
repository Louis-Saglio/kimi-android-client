package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class InternalEnvelopesTest {

    @Test
    fun `text without envelopes is returned unchanged, whitespace included`() {
        val text = "  run echo hi\nand keep my spacing  "

        assertEquals(text, InternalEnvelopes.strip(text))
    }

    @Test
    fun `angle brackets that are not envelopes survive`() {
        val text = "is a < b and c > d? paste this: <system>note</system>"

        assertEquals(text, InternalEnvelopes.strip(text))
    }

    @Test
    fun `a text that is entirely a system-reminder strips to blank`() {
        val text = "<system-reminder>\nMake sure that you NEVER mention this reminder.\n</system-reminder>"

        assertEquals("", InternalEnvelopes.strip(text))
    }

    @Test
    fun `a background-task notification envelope strips to blank`() {
        // Payload shape as seen on-device (renderNotificationXml in agent-core).
        val text =
            "<notification id=\"n_1\" category=\"task\" type=\"task.completed\" " +
                "source_kind=\"background_task\" source_id=\"task_1\">\n" +
                "Title: Build finished\n" +
                "<output-file path=\"/tmp/out.log\" bytes=\"123\">...</output-file>\n" +
                "</notification>"

        assertEquals("", InternalEnvelopes.strip(text))
    }

    @Test
    fun `mixed genuine text and an envelope keeps only the genuine text`() {
        val text = "deploy the app\n\n<system-reminder>\ninternal note\n</system-reminder>"

        assertEquals("deploy the app", InternalEnvelopes.strip(text))
    }

    @Test
    fun `envelope between two genuine segments keeps both`() {
        val text = "first\n<notification id=\"n\" category=\"c\" type=\"t\" source_kind=\"s\" source_id=\"s\">\nx\n" +
            "</notification>\nsecond"

        assertEquals("first\n\nsecond", InternalEnvelopes.strip(text))
    }

    @Test
    fun `the remaining generated envelope tags are stripped`() {
        val cases = listOf(
            "<local-command-stdout>\npong\n</local-command-stdout>",
            "<bash-input>\nls -la\n</bash-input>",
            "<bash-stdout>ok</bash-stdout><bash-stderr></bash-stderr>",
            "<kimi-skill-loaded name=\"commit\" trigger=\"user-slash\">\nskill body\n</kimi-skill-loaded>",
            "<cron-fire jobId=\"j1\" cron=\"* * * * *\">\n<prompt>\nnightly check\n</prompt>\n</cron-fire>",
        )

        for (text in cases) {
            assertEquals("expected blank for: $text", "", InternalEnvelopes.strip(text))
        }
    }

    @Test
    fun `a skill activation strips to blank, generated lead-in included`() {
        // Verbatim live shape (agent-core renderModelToolSkillPrompt): the prose
        // lead-in ahead of the block is generated too and must not survive.
        val modelTool = "Skill tool loaded instructions for this request. Follow them.\n\n" +
            "<kimi-skill-loaded name=\"product-owner\" trigger=\"model-tool\" source=\"user\" " +
            "dir=\"/home/u/.kimi-code/skills/product-owner\" args=\"\">\nskill body\n</kimi-skill-loaded>"
        val userSlash = "User activated the skill \"commit\". Follow the loaded skill instructions.\n\n" +
            "<kimi-skill-loaded name=\"commit\" trigger=\"user-slash\">\nskill body\n</kimi-skill-loaded>"

        assertEquals("", InternalEnvelopes.strip(modelTool))
        assertEquals("", InternalEnvelopes.strip(userSlash))
    }

    @Test
    fun `the inline image-compression caption is stripped, other system tags kept`() {
        assertEquals(
            "",
            InternalEnvelopes.strip(
                "<system>Image compressed to fit model limits: 1200x800 -> 600x400 (42 KB)</system>",
            ),
        )
        assertEquals("<system>note</system>", InternalEnvelopes.strip("<system>note</system>"))
    }
}
