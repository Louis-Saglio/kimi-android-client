package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPreviewTest {

    @Test
    fun `bash input with a description previews the description`() {
        // ticket 095: the call's human-written label beats the raw command on Bash rows.
        val input = buildJsonObject {
            put("command", "echo doc-verify")
            put("description", "Verify the doc claim")
        }

        assertEquals("Verify the doc claim", ToolPreview.of("Bash", input))
    }

    @Test
    fun `bash input without a description previews the command`() {
        // docs/server-api.md: {"type":"tool_use",...,"tool_name":"Bash","input":{"command":"echo doc-verify"}}
        val input = buildJsonObject { put("command", "echo doc-verify") }

        assertEquals("echo doc-verify", ToolPreview.of("Bash", input))
    }

    @Test
    fun `bash input with neither description nor command falls back to the compact json`() {
        val input = buildJsonObject { put("run_in_background", true) }

        assertEquals("""{"run_in_background":true}""", ToolPreview.of("Bash", input))
    }

    @Test
    fun `an agent call previews its description`() {
        // Agent args carry a human-written `description` (the sub-agent's short label):
        // it leads the row, not the compact JSON of the whole prompt payload.
        val input = buildJsonObject {
            put("description", "Find the leak")
            put("prompt", "Investigate the memory leak")
        }

        assertEquals("Find the leak", ToolPreview.of("Agent", input))
    }

    @Test
    fun `an agent call without a description falls back to the compact json`() {
        val input = buildJsonObject { put("prompt", "Investigate the memory leak") }

        assertEquals("""{"prompt":"Investigate the memory leak"}""", ToolPreview.of("Agent", input))
    }

    @Test
    fun `file tool input previews the path`() {
        // Read/Write/Edit: the path identifies the call, not the (huge) content.
        val input = buildJsonObject {
            put("path", "app/src/main/AndroidManifest.xml")
            put("content", "<manifest/>")
        }

        assertEquals("app/src/main/AndroidManifest.xml", ToolPreview.of("Write", input))
    }

    @Test
    fun `input without an identifying argument falls back to the compact json`() {
        val input = buildJsonObject { put("runners", 3) }

        assertEquals("""{"runners":3}""", ToolPreview.of("mcp__kiln__list_runners", input))
    }

    // Ticket 122: the lookup tools identify by their query argument.

    @Test
    fun `a glob call previews the pattern`() {
        val input = buildJsonObject {
            put("pattern", "**/*.kt")
            put("path", "/tmp/spike")
        }

        assertEquals("**/*.kt", ToolPreview.of("Glob", input))
    }

    @Test
    fun `a web search call previews the query`() {
        val input = buildJsonObject { put("query", "kimi code changelog") }

        assertEquals("kimi code changelog", ToolPreview.of("WebSearch", input))
    }

    @Test
    fun `a fetch url call previews the raw url`() {
        // The host-first shortening lives in the row, not in the preview string.
        val input = buildJsonObject { put("url", "https://example.com/docs/guide") }

        assertEquals("https://example.com/docs/guide", ToolPreview.of("FetchURL", input))
    }

    @Test
    fun `a grep call previews the pattern`() {
        // Ticket 123: the pattern identifies the call, not the scope path; the row
        // renders it slash-delimited.
        val input = buildJsonObject {
            put("pattern", "foo|bar")
            put("path", "/tmp/spike")
        }

        assertEquals("foo|bar", ToolPreview.of("Grep", input))
    }

    @Test
    fun `no input previews nothing`() {
        assertEquals("", ToolPreview.of("Bash", null))
        assertEquals("", ToolPreview.of(null, buildJsonObject { }))
    }

    @Test
    fun `multi-line values collapse to one line`() {
        val input = buildJsonObject { put("command", "echo one\necho two") }

        assertEquals("echo one echo two", ToolPreview.of("Bash", input))
    }

    @Test
    fun `a multi-line bash description collapses to one line`() {
        val input = buildJsonObject {
            put("command", "make test")
            put("description", "Run the suite\nand report")
        }

        assertEquals("Run the suite and report", ToolPreview.of("Bash", input))
    }
}
