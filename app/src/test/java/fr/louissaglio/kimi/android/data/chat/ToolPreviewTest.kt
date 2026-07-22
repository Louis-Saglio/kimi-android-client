package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPreviewTest {

    @Test
    fun `bash input previews the command`() {
        // docs/server-api.md: {"type":"tool_use",...,"tool_name":"Bash","input":{"command":"echo doc-verify"}}
        val input = buildJsonObject { put("command", "echo doc-verify") }

        assertEquals("echo doc-verify", ToolPreview.of(input))
    }

    @Test
    fun `file tool input previews the path`() {
        // Read/Write/Edit: the path identifies the call, not the (huge) content.
        val input = buildJsonObject {
            put("path", "app/src/main/AndroidManifest.xml")
            put("content", "<manifest/>")
        }

        assertEquals("app/src/main/AndroidManifest.xml", ToolPreview.of(input))
    }

    @Test
    fun `input without an identifying argument falls back to the compact json`() {
        val input = buildJsonObject { put("pattern", "*.kt") }

        assertEquals("""{"pattern":"*.kt"}""", ToolPreview.of(input))
    }

    @Test
    fun `no input previews nothing`() {
        assertEquals("", ToolPreview.of(null))
        assertEquals("", ToolPreview.of(buildJsonObject { }))
    }

    @Test
    fun `multi-line values collapse to one line`() {
        val input = buildJsonObject { put("command", "echo one\necho two") }

        assertEquals("echo one echo two", ToolPreview.of(input))
    }
}
