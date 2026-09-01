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

    // --- Ticket 121: Bash payloads -------------------------------------------

    @Test
    fun `a bare Bash call parses with defaults`() {
        val args = buildJsonObject { put("command", "./gradlew build") }

        assertEquals(
            ToolPayload.Bash(
                "./gradlew build",
                description = null,
                cwd = null,
                timeoutSeconds = null,
                timeoutDisabled = false,
            ),
            ToolPayloads.of("Bash", args),
        )
    }

    @Test
    fun `a Bash call parses its explicit knobs`() {
        val args = buildJsonObject {
            put("command", "make test")
            put("description", "Run the test suite")
            put("cwd", "/tmp/spike")
            put("timeout", 30)
            put("disable_timeout", true)
        }

        // The payload carries the raw knobs (bashTimeoutOf's derivation) — the
        // explicit-seconds-wins budget rule lives in BashTimeout.
        assertEquals(
            ToolPayload.Bash("make test", "Run the test suite", "/tmp/spike", 30, timeoutDisabled = true),
            ToolPayloads.of("Bash", args),
        )
    }

    @Test
    fun `a blank description or cwd parses as absent`() {
        val args = buildJsonObject {
            put("command", "ls")
            put("description", "  ")
            put("cwd", "")
        }

        assertEquals(
            ToolPayload.Bash("ls", description = null, cwd = null, timeoutSeconds = null, timeoutDisabled = false),
            ToolPayloads.of("Bash", args),
        )
    }

    @Test
    fun `non-primitive timeout knobs parse as absent rather than rejecting the payload`() {
        val args = buildJsonObject {
            put("command", "ls")
            put("timeout", buildJsonObject { })
        }

        assertEquals(
            ToolPayload.Bash("ls", description = null, cwd = null, timeoutSeconds = null, timeoutDisabled = false),
            ToolPayloads.of("Bash", args),
        )
    }

    @Test
    fun `a Bash call without a string command is rejected`() {
        assertNull(ToolPayloads.of("Bash", null))
        assertNull(ToolPayloads.of("Bash", buildJsonObject { }))
        assertNull(ToolPayloads.of("Bash", buildJsonObject { put("description", "x") }))
        assertNull(ToolPayloads.of("Bash", buildJsonObject { put("command", 42) }))
    }

    @Test
    fun `another tool never parses as a Bash payload`() {
        assertNull(ToolPayloads.of("Glob", buildJsonObject { put("command", "ls") }))
    }
}
