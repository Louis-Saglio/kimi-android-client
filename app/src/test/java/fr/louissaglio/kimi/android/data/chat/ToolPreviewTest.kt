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
    fun `a non-bash tool whose args carry a description keeps the compact-json fallback`() {
        // Regression guard (ticket 095): Agent args carry a description too — only Bash
        // rows change; adding the key to the shared list would silently relabel these.
        val input = buildJsonObject {
            put("description", "Find the leak")
            put("prompt", "Investigate the memory leak")
        }

        assertEquals(
            """{"description":"Find the leak","prompt":"Investigate the memory leak"}""",
            ToolPreview.of("Agent", input),
        )
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
        val input = buildJsonObject { put("pattern", "*.kt") }

        assertEquals("""{"pattern":"*.kt"}""", ToolPreview.of("Glob", input))
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
