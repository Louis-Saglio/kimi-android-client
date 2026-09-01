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

    // --- Ticket 120: Read/Write payloads -------------------------------------------

    @Test
    fun `a full-file Read call parses with no range`() {
        val args = buildJsonObject { put("path", "/tmp/spike/a.txt") }

        assertEquals(
            ToolPayload.Read("/tmp/spike/a.txt", offset = null, limit = null),
            ToolPayloads.of("Read", args),
        )
    }

    @Test
    fun `a ranged Read call parses offset and limit`() {
        val args = buildJsonObject {
            put("path", "/tmp/spike/a.txt")
            put("line_offset", 40)
            put("n_lines", 100)
        }

        assertEquals(
            ToolPayload.Read("/tmp/spike/a.txt", offset = 40, limit = 100),
            ToolPayloads.of("Read", args),
        )
    }

    @Test
    fun `a Read call may carry only one of the range arguments`() {
        val offsetOnly = buildJsonObject {
            put("path", "/tmp/spike/a.txt")
            put("line_offset", 40)
        }
        val limitOnly = buildJsonObject {
            put("path", "/tmp/spike/a.txt")
            put("n_lines", 50)
        }

        assertEquals(
            ToolPayload.Read("/tmp/spike/a.txt", offset = 40, limit = null),
            ToolPayloads.of("Read", offsetOnly),
        )
        assertEquals(
            ToolPayload.Read("/tmp/spike/a.txt", offset = null, limit = 50),
            ToolPayloads.of("Read", limitOnly),
        )
    }

    @Test
    fun `a Read call without a string path is rejected`() {
        assertNull(ToolPayloads.of("Read", null))
        assertNull(ToolPayloads.of("Read", buildJsonObject { put("line_offset", 40) }))
        assertNull(
            ToolPayloads.of(
                "Read",
                buildJsonObject {
                    put("path", 42)
                    put("n_lines", 50)
                },
            ),
        )
    }

    @Test
    fun `a Write call parses content and defaults to overwrite`() {
        val args = buildJsonObject {
            put("path", "/tmp/spike/a.txt")
            put("content", "one\ntwo\n")
        }

        assertEquals(
            ToolPayload.Write("/tmp/spike/a.txt", "one\ntwo\n", append = false),
            ToolPayloads.of("Write", args),
        )
    }

    @Test
    fun `only mode append sets the Write append flag`() {
        fun argsWithMode(mode: String) = buildJsonObject {
            put("path", "/tmp/spike/a.txt")
            put("content", "x")
            put("mode", mode)
        }

        assertEquals(true, (ToolPayloads.of("Write", argsWithMode("append")) as ToolPayload.Write).append)
        assertEquals(false, (ToolPayloads.of("Write", argsWithMode("overwrite")) as ToolPayload.Write).append)
    }

    @Test
    fun `a Write call may carry empty content but not missing or non-string content`() {
        val empty = buildJsonObject {
            put("path", "/tmp/spike/a.txt")
            put("content", "")
        }

        assertEquals(
            ToolPayload.Write("/tmp/spike/a.txt", "", append = false),
            ToolPayloads.of("Write", empty),
        )
        assertNull(ToolPayloads.of("Write", buildJsonObject { put("path", "/tmp/spike/a.txt") }))
        assertNull(
            ToolPayloads.of(
                "Write",
                buildJsonObject {
                    put("path", "/tmp/spike/a.txt")
                    put("content", 42)
                },
            ),
        )
    }
}
