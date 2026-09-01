package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.buildJsonArray
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

    // --- Ticket 122: Glob/WebSearch/FetchURL payloads --------------------------------

    @Test
    fun `a full Glob call parses all three fields`() {
        val args = buildJsonObject {
            put("pattern", "**/*.kt")
            put("path", "/tmp/spike")
            put("include_ignored", true)
        }

        assertEquals(
            ToolPayload.Glob("**/*.kt", "/tmp/spike", includeIgnored = true),
            ToolPayloads.of("Glob", args),
        )
    }

    @Test
    fun `a bare Glob call parses with defaults`() {
        val args = buildJsonObject { put("pattern", "*.kt") }

        assertEquals(
            ToolPayload.Glob("*.kt", path = null, includeIgnored = false),
            ToolPayloads.of("Glob", args),
        )
    }

    @Test
    fun `a blank Glob path parses as absent and a string include_ignored is not the flag`() {
        val args = buildJsonObject {
            put("pattern", "*.kt")
            put("path", "  ")
            put("include_ignored", "true")
        }

        assertEquals(
            ToolPayload.Glob("*.kt", path = null, includeIgnored = false),
            ToolPayloads.of("Glob", args),
        )
    }

    @Test
    fun `a Glob call without a string pattern is rejected`() {
        assertNull(ToolPayloads.of("Glob", null))
        assertNull(ToolPayloads.of("Glob", buildJsonObject { }))
        assertNull(ToolPayloads.of("Glob", buildJsonObject { put("path", "/tmp/spike") }))
        assertNull(ToolPayloads.of("Glob", buildJsonObject { put("pattern", 42) }))
    }

    @Test
    fun `a WebSearch call parses its query`() {
        val args = buildJsonObject { put("query", "kimi code changelog") }

        assertEquals(ToolPayload.WebSearch("kimi code changelog"), ToolPayloads.of("WebSearch", args))
    }

    @Test
    fun `a WebSearch call without a string query is rejected`() {
        assertNull(ToolPayloads.of("WebSearch", null))
        assertNull(ToolPayloads.of("WebSearch", buildJsonObject { }))
        assertNull(ToolPayloads.of("WebSearch", buildJsonObject { put("query", 42) }))
    }

    @Test
    fun `a FetchURL call parses its url`() {
        val args = buildJsonObject { put("url", "https://example.com/docs/guide") }

        assertEquals(ToolPayload.FetchUrl("https://example.com/docs/guide"), ToolPayloads.of("FetchURL", args))
    }

    @Test
    fun `a FetchURL call without a string url is rejected`() {
        assertNull(ToolPayloads.of("FetchURL", null))
        assertNull(ToolPayloads.of("FetchURL", buildJsonObject { }))
        assertNull(ToolPayloads.of("FetchURL", buildJsonObject { put("url", 42) }))
    }

    @Test
    fun `another tool never parses as a lookup payload`() {
        val args = buildJsonObject {
            put("pattern", "*.kt")
            put("query", "q")
            put("url", "https://example.com")
        }

        // (Ticket 123: this case asserted Grep — a parseable tool now; Edit keeps
        // the "another tool" intent.)
        assertNull(ToolPayloads.of("Edit", args))
        assertNull(ToolPayloads.of("Read", args))
    }

    // --- Ticket 123: Grep payloads --------------------------------------------

    @Test
    fun `a full Grep call parses all ten fields`() {
        val args = buildJsonObject {
            put("pattern", "foo|bar")
            put("path", "/tmp/spike")
            put("glob", "*.kt")
            put("output_mode", "content")
            put("-i", true)
            put("-A", 3)
            put("-B", 1)
            put("multiline", true)
            put("include_ignored", true)
            put("type", "kt")
        }

        assertEquals(
            ToolPayload.Grep(
                "foo|bar",
                path = "/tmp/spike",
                glob = "*.kt",
                outputMode = "content",
                caseInsensitive = true,
                contextBefore = 1,
                contextAfter = 3,
                multiline = true,
                includeIgnored = true,
                type = "kt",
            ),
            ToolPayloads.of("Grep", args),
        )
    }

    @Test
    fun `a bare Grep call parses with defaults`() {
        val args = buildJsonObject { put("pattern", "foo") }

        assertEquals(
            ToolPayload.Grep(
                "foo",
                path = null,
                glob = null,
                outputMode = "files_with_matches",
                caseInsensitive = false,
                contextBefore = null,
                contextAfter = null,
                multiline = false,
                includeIgnored = false,
                type = null,
            ),
            ToolPayloads.of("Grep", args),
        )
    }

    @Test
    fun `-C fills both context sides and wins over -A and -B`() {
        val args = buildJsonObject {
            put("pattern", "foo")
            put("-C", 2)
            put("-A", 5)
            put("-B", 4)
        }

        val payload = ToolPayloads.of("Grep", args) as ToolPayload.Grep
        assertEquals(2, payload.contextBefore)
        assertEquals(2, payload.contextAfter)
    }

    @Test
    fun `blank Grep strings parse as absent and string flags are not flags`() {
        val args = buildJsonObject {
            put("pattern", "foo")
            put("path", "  ")
            put("glob", "")
            put("type", " ")
            put("-i", "true")
            put("multiline", "true")
            put("include_ignored", "true")
        }

        assertEquals(
            ToolPayload.Grep(
                "foo",
                path = null,
                glob = null,
                outputMode = "files_with_matches",
                caseInsensitive = false,
                contextBefore = null,
                contextAfter = null,
                multiline = false,
                includeIgnored = false,
                type = null,
            ),
            ToolPayloads.of("Grep", args),
        )
    }

    @Test
    fun `a Grep call without a string pattern is rejected`() {
        assertNull(ToolPayloads.of("Grep", null))
        assertNull(ToolPayloads.of("Grep", buildJsonObject { }))
        assertNull(ToolPayloads.of("Grep", buildJsonObject { put("path", "/tmp/spike") }))
        assertNull(ToolPayloads.of("Grep", buildJsonObject { put("pattern", 42) }))
    }

    @Test
    fun `another tool never parses as a Grep payload`() {
        val args = buildJsonObject {
            put("pattern", "foo")
            put("output_mode", "content")
        }

        assertNull(ToolPayloads.of("Bash", args))
        assertNull(ToolPayloads.of("FetchURL", args))
    }

    // --- Ticket 124: cron and task-control payloads -------------------------------

    @Test
    fun `a full CronCreate call parses all three fields`() {
        val args = buildJsonObject {
            put("cron", "15 7 1 8 *")
            put("prompt", "Run the blocked robo test")
            put("recurring", false)
        }

        assertEquals(
            ToolPayload.CronCreate("15 7 1 8 *", "Run the blocked robo test", recurring = false),
            ToolPayloads.of("CronCreate", args),
        )
    }

    @Test
    fun `CronCreate recurring defaults to true and a string is not the flag`() {
        val absent = buildJsonObject {
            put("cron", "0 9 * * *")
            put("prompt", "morning check")
        }
        val asString = buildJsonObject {
            put("cron", "0 9 * * *")
            put("prompt", "morning check")
            put("recurring", "false")
        }

        // The wire default is true — unlike every other flag (the Edit rule).
        assertEquals(true, (ToolPayloads.of("CronCreate", absent) as ToolPayload.CronCreate).recurring)
        assertEquals(true, (ToolPayloads.of("CronCreate", asString) as ToolPayload.CronCreate).recurring)
    }

    @Test
    fun `a CronCreate call without a string cron or prompt is rejected`() {
        assertNull(ToolPayloads.of("CronCreate", null))
        assertNull(ToolPayloads.of("CronCreate", buildJsonObject { }))
        assertNull(ToolPayloads.of("CronCreate", buildJsonObject { put("cron", "0 9 * * *") }))
        assertNull(ToolPayloads.of("CronCreate", buildJsonObject { put("prompt", "x") }))
        assertNull(
            ToolPayloads.of(
                "CronCreate",
                buildJsonObject {
                    put("cron", 42)
                    put("prompt", "x")
                },
            ),
        )
    }

    @Test
    fun `a CronDelete call parses its id`() {
        val args = buildJsonObject { put("id", "01KYXPV7A4HPX4GNZAAGTSRSMH") }

        assertEquals(ToolPayload.CronDelete("01KYXPV7A4HPX4GNZAAGTSRSMH"), ToolPayloads.of("CronDelete", args))
    }

    @Test
    fun `a CronDelete call without a string id is rejected`() {
        assertNull(ToolPayloads.of("CronDelete", null))
        assertNull(ToolPayloads.of("CronDelete", buildJsonObject { }))
        assertNull(ToolPayloads.of("CronDelete", buildJsonObject { put("id", 42) }))
    }

    @Test
    fun `CronList has no payload — the row is name-keyed`() {
        assertNull(ToolPayloads.of("CronList", buildJsonObject { }))
    }

    @Test
    fun `TaskOutput and TaskStop parse their task id`() {
        val args = buildJsonObject { put("task_id", "bash-abc123") }

        assertEquals(ToolPayload.TaskOutput("bash-abc123"), ToolPayloads.of("TaskOutput", args))
        assertEquals(ToolPayload.TaskStop("bash-abc123"), ToolPayloads.of("TaskStop", args))
    }

    @Test
    fun `a TaskOutput or TaskStop call without a string task_id is rejected`() {
        assertNull(ToolPayloads.of("TaskOutput", null))
        assertNull(ToolPayloads.of("TaskOutput", buildJsonObject { }))
        assertNull(ToolPayloads.of("TaskOutput", buildJsonObject { put("task_id", 42) }))
        assertNull(ToolPayloads.of("TaskStop", null))
        assertNull(ToolPayloads.of("TaskStop", buildJsonObject { put("reason", "stuck") }))
    }

    @Test
    fun `WaitFor parses with or without a task id`() {
        assertEquals(
            ToolPayload.WaitFor("bash-abc123"),
            ToolPayloads.of(
                "WaitFor",
                buildJsonObject {
                    put("task_id", "bash-abc123")
                    put("timeout", 300)
                },
            ),
        )
        // The wait-any call: no task_id, the row keeps the generic expansion.
        assertEquals(
            ToolPayload.WaitFor(null),
            ToolPayloads.of("WaitFor", buildJsonObject { put("timeout", 300) }),
        )
    }

    @Test
    fun `WaitFor requires args`() {
        assertNull(ToolPayloads.of("WaitFor", null))
    }

    @Test
    fun `TaskList parses with the wire defaults`() {
        assertEquals(
            ToolPayload.TaskList(activeOnly = true, limit = null),
            ToolPayloads.of("TaskList", buildJsonObject { }),
        )
        assertEquals(
            ToolPayload.TaskList(activeOnly = false, limit = 50),
            ToolPayloads.of(
                "TaskList",
                buildJsonObject {
                    put("active_only", false)
                    put("limit", 50)
                },
            ),
        )
    }

    @Test
    fun `a string active_only is not the TaskList flag and null args reject`() {
        val args = buildJsonObject { put("active_only", "false") }

        assertEquals(true, (ToolPayloads.of("TaskList", args) as ToolPayload.TaskList).activeOnly)
        assertNull(ToolPayloads.of("TaskList", null))
    }

    @Test
    fun `another tool never parses as a cron or task payload`() {
        val args = buildJsonObject {
            put("cron", "0 9 * * *")
            put("prompt", "x")
            put("task_id", "bash-1")
            put("id", "01KYXPV7A4HPX4GNZAAGTSRSMH")
        }

        assertNull(ToolPayloads.of("Edit", args))
        assertNull(ToolPayloads.of("Bash", args))
        assertNull(ToolPayloads.of("Grep", args))
    }

    // --- Ticket 125: meta tool payloads (Skill, plan mode, goal, ReadMediaFile) -----

    @Test
    fun `a full Skill call parses skill and args`() {
        val args = buildJsonObject {
            put("skill", "commit")
            put("args", "-m \"fix bug\"")
        }

        assertEquals(ToolPayload.Skill("commit", "-m \"fix bug\""), ToolPayloads.of("Skill", args))
    }

    @Test
    fun `a Skill call without a useful argument string parses it as absent`() {
        assertEquals(
            ToolPayload.Skill("unslop", args = null),
            ToolPayloads.of("Skill", buildJsonObject { put("skill", "unslop") }),
        )
        assertEquals(
            ToolPayload.Skill("unslop", args = null),
            ToolPayloads.of(
                "Skill",
                buildJsonObject {
                    put("skill", "unslop")
                    put("args", "  ")
                },
            ),
        )
    }

    @Test
    fun `a Skill call without a string skill is rejected`() {
        assertNull(ToolPayloads.of("Skill", null))
        assertNull(ToolPayloads.of("Skill", buildJsonObject { }))
        assertNull(ToolPayloads.of("Skill", buildJsonObject { put("args", "x") }))
        assertNull(ToolPayloads.of("Skill", buildJsonObject { put("skill", 42) }))
    }

    @Test
    fun `a ReadMediaFile call parses path and a full region`() {
        val args = buildJsonObject {
            put("path", "/tmp/spike/shot.png")
            put(
                "region",
                buildJsonObject {
                    put("x", 10)
                    put("y", 20)
                    put("width", 100)
                    put("height", 200)
                },
            )
        }

        assertEquals(
            ToolPayload.ReadMedia("/tmp/spike/shot.png", regionWidth = 100, regionHeight = 200),
            ToolPayloads.of("ReadMediaFile", args),
        )
    }

    @Test
    fun `a ReadMediaFile call without a region parses with no crop`() {
        val args = buildJsonObject {
            put("path", "/tmp/spike/shot.png")
            put("full_resolution", true)
        }

        assertEquals(
            ToolPayload.ReadMedia("/tmp/spike/shot.png", regionWidth = null, regionHeight = null),
            ToolPayloads.of("ReadMediaFile", args),
        )
    }

    /** The ticket's acceptance case: a half-specified region earns no badge — the
     * parser is all-or-nothing. */
    @Test
    fun `a half-specified region parses as no region`() {
        fun argsWithRegion(width: Int?, height: Int?) = buildJsonObject {
            put("path", "/tmp/spike/shot.png")
            put(
                "region",
                buildJsonObject {
                    width?.let { put("width", it) }
                    height?.let { put("height", it) }
                },
            )
        }

        assertEquals(
            ToolPayload.ReadMedia("/tmp/spike/shot.png", regionWidth = null, regionHeight = null),
            ToolPayloads.of("ReadMediaFile", argsWithRegion(width = 100, height = null)),
        )
        assertEquals(
            ToolPayload.ReadMedia("/tmp/spike/shot.png", regionWidth = null, regionHeight = null),
            ToolPayloads.of("ReadMediaFile", argsWithRegion(width = null, height = 200)),
        )
    }

    @Test
    fun `a ReadMediaFile call without a string path is rejected`() {
        assertNull(ToolPayloads.of("ReadMediaFile", null))
        assertNull(ToolPayloads.of("ReadMediaFile", buildJsonObject { }))
        assertNull(ToolPayloads.of("ReadMediaFile", buildJsonObject { put("path", 42) }))
    }

    @Test
    fun `an ExitPlanMode call parses its option labels`() {
        val args = buildJsonObject {
            put(
                "options",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("label", "Rewrite the parser (Recommended)")
                            put("description", "Clean but slow")
                        },
                    )
                    add(buildJsonObject { put("label", "Patch the lexer") })
                },
            )
        }

        // The descriptions stay in the generic expansion — only labels are carried.
        assertEquals(
            ToolPayload.ExitPlanMode(listOf("Rewrite the parser (Recommended)", "Patch the lexer")),
            ToolPayloads.of("ExitPlanMode", args),
        )
    }

    @Test
    fun `an ExitPlanMode call without options parses with no labels`() {
        // A plain plan approval carries no options array at all.
        assertEquals(ToolPayload.ExitPlanMode(emptyList()), ToolPayloads.of("ExitPlanMode", buildJsonObject { }))
        // An entry without a string label is dropped, never rejecting the payload.
        val args = buildJsonObject {
            put(
                "options",
                buildJsonArray {
                    add(buildJsonObject { put("description", "no label here") })
                    add(buildJsonObject { put("label", "Kept") })
                },
            )
        }
        assertEquals(ToolPayload.ExitPlanMode(listOf("Kept")), ToolPayloads.of("ExitPlanMode", args))
    }

    @Test
    fun `an ExitPlanMode call with null args is rejected`() {
        assertNull(ToolPayloads.of("ExitPlanMode", null))
    }

    @Test
    fun `a CreateGoal call parses objective and completion criterion`() {
        val args = buildJsonObject {
            put("objective", "Fix the memory leak")
            put("completionCriterion", "The leak test passes")
            put("replace", true)
        }

        // `replace` never earns UI space — not carried (the Grep knobs rule).
        assertEquals(
            ToolPayload.CreateGoal("Fix the memory leak", "The leak test passes"),
            ToolPayloads.of("CreateGoal", args),
        )
    }

    @Test
    fun `a CreateGoal call without a useful criterion parses it as absent`() {
        assertEquals(
            ToolPayload.CreateGoal("Fix the memory leak", completionCriterion = null),
            ToolPayloads.of("CreateGoal", buildJsonObject { put("objective", "Fix the memory leak") }),
        )
        assertEquals(
            ToolPayload.CreateGoal("Fix the memory leak", completionCriterion = null),
            ToolPayloads.of(
                "CreateGoal",
                buildJsonObject {
                    put("objective", "Fix the memory leak")
                    put("completionCriterion", " ")
                },
            ),
        )
    }

    @Test
    fun `a CreateGoal call without a string objective is rejected`() {
        assertNull(ToolPayloads.of("CreateGoal", null))
        assertNull(ToolPayloads.of("CreateGoal", buildJsonObject { }))
        assertNull(ToolPayloads.of("CreateGoal", buildJsonObject { put("completionCriterion", "x") }))
        assertNull(ToolPayloads.of("CreateGoal", buildJsonObject { put("objective", 42) }))
    }

    @Test
    fun `an UpdateGoal call parses its status`() {
        for (status in listOf("active", "complete", "blocked")) {
            assertEquals(
                ToolPayload.UpdateGoal(status),
                ToolPayloads.of("UpdateGoal", buildJsonObject { put("status", status) }),
            )
        }
    }

    @Test
    fun `an UpdateGoal call without a string status is rejected`() {
        assertNull(ToolPayloads.of("UpdateGoal", null))
        assertNull(ToolPayloads.of("UpdateGoal", buildJsonObject { }))
        assertNull(ToolPayloads.of("UpdateGoal", buildJsonObject { put("status", 42) }))
    }

    @Test
    fun `a SetGoalBudget call parses value and unit`() {
        assertEquals(
            ToolPayload.SetGoalBudget(30.0, "minutes"),
            ToolPayloads.of(
                "SetGoalBudget",
                buildJsonObject {
                    put("value", 30)
                    put("unit", "minutes")
                },
            ),
        )
        // The wire allows fractional time budgets (1.5 hours).
        assertEquals(
            ToolPayload.SetGoalBudget(1.5, "hours"),
            ToolPayloads.of(
                "SetGoalBudget",
                buildJsonObject {
                    put("value", 1.5)
                    put("unit", "hours")
                },
            ),
        )
    }

    @Test
    fun `a SetGoalBudget call without a numeric value or a string unit is rejected`() {
        assertNull(ToolPayloads.of("SetGoalBudget", null))
        assertNull(ToolPayloads.of("SetGoalBudget", buildJsonObject { }))
        assertNull(ToolPayloads.of("SetGoalBudget", buildJsonObject { put("value", 30) }))
        assertNull(ToolPayloads.of("SetGoalBudget", buildJsonObject { put("unit", "minutes") }))
        assertNull(
            ToolPayloads.of(
                "SetGoalBudget",
                buildJsonObject {
                    put("value", "thirty")
                    put("unit", "minutes")
                },
            ),
        )
    }

    /** GetGoal and EnterPlanMode carry no fields worth tuning — no payload, the
     * default generic row (the ticket's explicit call). */
    @Test
    fun `GetGoal and EnterPlanMode have no payload`() {
        assertNull(ToolPayloads.of("GetGoal", buildJsonObject { }))
        assertNull(ToolPayloads.of("EnterPlanMode", buildJsonObject { }))
    }

    @Test
    fun `another tool never parses as a meta payload`() {
        val args = buildJsonObject {
            put("skill", "unslop")
            put("path", "/tmp/spike/shot.png")
            put("objective", "Fix the leak")
            put("status", "complete")
            put("value", 30)
            put("unit", "minutes")
        }

        assertNull(ToolPayloads.of("Edit", args))
        assertNull(ToolPayloads.of("Bash", args))
        assertNull(ToolPayloads.of("CronDelete", args))
    }
}
