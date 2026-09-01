package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Edit payload parser (ticket 119). */
class ToolPayloadsTest {

    @Test
    fun `a full Edit call parses all four fields`() {
        val args = buildJsonObject {
            put("path", "/tmp/spike/a.txt")
            put("old_string", "one\ntwo")
            put("new_string", "one\nTWO\nthree")
            put("replace_all", true)
        }

        assertEquals(
            ToolPayload.Edit("/tmp/spike/a.txt", "one\ntwo", "one\nTWO\nthree", replaceAll = true),
            ToolPayloads.of("Edit", args),
        )
    }

    @Test
    fun `replace_all defaults to false`() {
        val args = buildJsonObject {
            put("path", "/tmp/spike/a.txt")
            put("old_string", "one")
            put("new_string", "two")
        }

        assertEquals(
            ToolPayload.Edit("/tmp/spike/a.txt", "one", "two", replaceAll = false),
            ToolPayloads.of("Edit", args),
        )
    }

    @Test
    fun `empty old or new strings are legitimate values`() {
        val args = buildJsonObject {
            put("path", "/tmp/spike/a.txt")
            put("old_string", "")
            put("new_string", "added")
        }

        assertEquals(
            ToolPayload.Edit("/tmp/spike/a.txt", "", "added", replaceAll = false),
            ToolPayloads.of("Edit", args),
        )
    }

    @Test
    fun `a missing string key rejects the payload`() {
        fun argsOf(vararg keys: String) = buildJsonObject {
            if ("path" in keys) put("path", "/tmp/spike/a.txt")
            if ("old_string" in keys) put("old_string", "one")
            if ("new_string" in keys) put("new_string", "two")
        }

        assertNull(ToolPayloads.of("Edit", argsOf("old_string", "new_string")))
        assertNull(ToolPayloads.of("Edit", argsOf("path", "new_string")))
        assertNull(ToolPayloads.of("Edit", argsOf("path", "old_string")))
    }

    @Test
    fun `a non-string key rejects the payload`() {
        val args = buildJsonObject {
            put("path", 42)
            put("old_string", "one")
            put("new_string", "two")
        }

        assertNull(ToolPayloads.of("Edit", args))
    }

    @Test
    fun `a string replace_all is not the boolean flag`() {
        val args = buildJsonObject {
            put("path", "/tmp/spike/a.txt")
            put("old_string", "one")
            put("new_string", "two")
            put("replace_all", "true")
        }

        assertEquals(false, (ToolPayloads.of("Edit", args) as ToolPayload.Edit).replaceAll)
    }

    @Test
    fun `null or empty args yield no payload`() {
        assertNull(ToolPayloads.of("Edit", null))
        assertNull(ToolPayloads.of("Edit", buildJsonObject { }))
    }

    @Test
    fun `another tool never parses as an Edit payload`() {
        val args = buildJsonObject {
            put("path", "/tmp/spike/a.txt")
            put("old_string", "one")
            put("new_string", "two")
        }

        assertNull(ToolPayloads.of("Write", args))
        assertNull(ToolPayloads.of("Bash", args))
    }
}
