package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.data.chat.AgentCallDisplay
import fr.louissaglio.kimi.android.data.chat.ToolPayload
import fr.louissaglio.kimi.android.data.chat.ToolPreview
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the rich tool-activity rows (ticket 022). */
@RunWith(AndroidJUnit4::class)
class ToolRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun toolRow(activity: ChatItem.ToolActivity) {
        composeTestRule.setContent { KimiTheme { ToolRow(activity) } }
    }

    @Test
    fun bashRowShowsTerminalGlyphNamePreviewAndSuccessCheck() {
        toolRow(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, preview = "echo doc-verify"))

        composeTestRule.onNodeWithText(">_").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bash").assertIsDisplayed()
        composeTestRule.onNodeWithText("echo doc-verify").assertIsDisplayed()
        composeTestRule.onNodeWithText("✓").assertIsDisplayed()
    }

    @Test
    fun nonBashRowShowsNameAndPreviewWithoutTerminalGlyph() {
        toolRow(ChatItem.ToolActivity("tool_1", "Read", ToolStatus.Done, preview = "/tmp/spike/a.txt"))

        composeTestRule.onNodeWithText(">_").assertDoesNotExist()
        composeTestRule.onNodeWithText("Read").assertIsDisplayed()
        composeTestRule.onNodeWithText("/tmp/spike/a.txt").assertIsDisplayed()
        composeTestRule.onNodeWithText("✓").assertIsDisplayed()
    }

    @Test
    fun errorRowShowsCrossInsteadOfCheck() {
        toolRow(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Error, preview = "rm -rf /"))

        composeTestRule.onNodeWithText("✗").assertIsDisplayed()
        composeTestRule.onNodeWithText("✓").assertDoesNotExist()
    }

    @Test
    fun runningRowShowsSpinnerAndNoOutcomeGlyph() {
        toolRow(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Running, preview = "sleep 10"))

        composeTestRule.onNodeWithContentDescription("Tool running").assertIsDisplayed()
        composeTestRule.onNodeWithText("✓").assertDoesNotExist()
        composeTestRule.onNodeWithText("✗").assertDoesNotExist()
    }

    @Test
    fun tappingRowExpandsInputAndOutputThenCollapses() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "Bash",
                ToolStatus.Done,
                preview = "echo hi",
                input = "{\n    \"command\": \"echo hi\"\n}",
                output = "hi\n",
            ),
        )

        // Collapsed: details hidden, the chevron offers to expand.
        composeTestRule.onNodeWithText("hi\n").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Expand tool details").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("{\n    \"command\": \"echo hi\"\n}").assertIsDisplayed()
        composeTestRule.onNodeWithText("hi\n").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Collapse tool details").performClick()
        composeTestRule.onNodeWithText("hi\n").assertDoesNotExist()
    }

    @Test
    fun rowWithoutDetailsHasNoChevron() {
        toolRow(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Running, preview = "sleep 10"))

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
    }

    /** Ticket 118: a call with object args expands to the generic key/value table —
     * labels and values, no raw-JSON braces. (An MCP tool: a builtin name with
     * parseable args — Glob since ticket 122 — would get the rich row instead.) */
    @Test
    fun rowWithObjectArgsExpandsToKeyValueTable() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "mcp__kiln__submit_batch",
                ToolStatus.Done,
                preview = "glob the sources",
                args = buildJsonObject {
                    put("pattern", "*.kt")
                    put("path", "/tmp/spike")
                },
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("pattern").assertIsDisplayed()
        composeTestRule.onNodeWithText("*.kt").assertIsDisplayed()
        composeTestRule.onNodeWithText("path").assertIsDisplayed()
        composeTestRule.onNodeWithText("/tmp/spike").assertIsDisplayed()
        // The table replaced the pretty-printed JSON: no braces anywhere.
        composeTestRule.onNodeWithText("{").assertDoesNotExist()
        composeTestRule.onNodeWithText("}").assertDoesNotExist()
    }

    /** Ticket 118: a call that carried no object args and has no output yet stays
     * non-expandable — the rule is unchanged. */
    @Test
    fun rowWithNeitherArgsNorOutputStaysNonExpandable() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "mcp__kiln__list_runners",
                ToolStatus.Running,
                preview = """{"runners":3}""",
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
        composeTestRule.onNodeWithText("mcp__kiln__list_runners").performClick()
        composeTestRule.onNodeWithContentDescription("Collapse tool details").assertDoesNotExist()
    }

    @Test
    fun linkedRowNeverExpandsAndNavigatesOnRowTap() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity(
                        "tool_1",
                        "Agent",
                        ToolStatus.Running,
                        preview = "doc-verify",
                        input = "{\n    \"prompt\": \"Reply ok\"\n}",
                        subagentId = "agent-12",
                    ),
                    onOpenSubagent = { opened = it },
                )
            }
        }

        // Even with call details available, a linked row shows no expand affordance…
        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Open sub-agent").assertIsDisplayed()

        // …and tapping the row itself navigates to the sub-agent page.
        composeTestRule.onNodeWithText("Agent").performClick()
        composeTestRule.onNodeWithText("{\n    \"prompt\": \"Reply ok\"\n}").assertDoesNotExist()
        assertEquals("agent-12", opened)
    }

    @Test
    fun linkedRowOpensSubagentFromTrailingButton() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity("tool_1", "Agent", ToolStatus.Running, preview = "doc-verify", subagentId = "agent-12"),
                    onOpenSubagent = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Open sub-agent").performClick()
        assertEquals("agent-12", opened)
    }

    /** Ticket 096: a bash row linked to a background job behaves like a linked Agent
     * row — it never expands, and the tap navigates to the job detail screen. */
    @Test
    fun bashLinkedRowNeverExpandsAndNavigatesOnRowTap() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity(
                        "tool_1",
                        "Bash",
                        ToolStatus.Done,
                        preview = "tick counter loop",
                        input = "{\n    \"command\": \"for i in …\"\n}",
                        output = "task_id: bash-1\n…",
                        bashTaskId = "bash-1",
                    ),
                    onOpenJob = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Open job").assertIsDisplayed()

        composeTestRule.onNodeWithText("Bash").performClick()
        composeTestRule.onNodeWithText("task_id: bash-1\n…").assertDoesNotExist()
        assertEquals("bash-1", opened)
    }

    @Test
    fun bashLinkedRowOpensJobFromTrailingButton() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Running, preview = "tick counter loop", bashTaskId = "bash-1"),
                    onOpenJob = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Open job").performClick()
        assertEquals("bash-1", opened)
    }

    /** Tickets 088/089: question rows never expand and show no chevron — the tap is
     * forwarded with the row's id (= tool call id = question id), input and output. */
    @Test
    fun questionRowForwardsTapAndShowsNoChevron() {
        var tapped: Triple<String, String, String>? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity(
                        "tool_q1",
                        "AskUserQuestion",
                        ToolStatus.Running,
                        preview = "Red or blue?",
                        input = "{\n    \"questions\": []\n}",
                    ),
                    onQuestionRowTapped = { id, input, output -> tapped = Triple(id, input, output) },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
        composeTestRule.onNodeWithText("AskUserQuestion").performClick()
        assertEquals(Triple("tool_q1", "{\n    \"questions\": []\n}", ""), tapped)
        // No expansion: the raw input never renders.
        composeTestRule.onNodeWithText("{\n    \"questions\": []\n}").assertDoesNotExist()
    }

    /** Ticket 089: a resolved question row is equally chevron-free — its details are
     * the read-only card, not raw JSON. */
    @Test
    fun resolvedQuestionRowShowsNoChevron() {
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity(
                        "tool_q1",
                        "AskUserQuestion",
                        ToolStatus.Done,
                        preview = "Red or blue?",
                        input = "{\n    \"questions\": []\n}",
                        output = "{\"answers\":{}}",
                    ),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
    }

    // --- Ticket 119: parsed Edit rows -------------------------------------------------

    private val editPayload = ToolPayload.Edit("/tmp/spike/a.txt", "one\ntwo", "one\nTWO\nthree", replaceAll = false)

    @Test
    fun editRowShowsStatChipAndOpenDiffChevronWithoutExpansion() {
        // old [one, two] vs new [one, TWO, three]: one removed, two added.
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "Edit",
                ToolStatus.Done,
                preview = "/tmp/spike/a.txt",
                payload = editPayload,
            ),
        )

        composeTestRule.onNodeWithText("+2").assertIsDisplayed()
        composeTestRule.onNodeWithText("−1").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Open diff").assertIsDisplayed()
    }

    @Test
    fun editRowWithReplaceAllShowsAllBadge() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "Edit",
                ToolStatus.Done,
                preview = "/tmp/spike/a.txt",
                payload = editPayload.copy(replaceAll = true),
            ),
        )

        composeTestRule.onNodeWithText("All").assertIsDisplayed()
    }

    @Test
    fun editRowWithoutReplaceAllShowsNoAllBadge() {
        toolRow(
            ChatItem.ToolActivity("tool_1", "Edit", ToolStatus.Done, preview = "/tmp/spike/a.txt", payload = editPayload),
        )

        composeTestRule.onNodeWithText("All").assertDoesNotExist()
    }

    @Test
    fun tappingEditRowNavigatesInsteadOfExpanding() {
        var opened: Pair<String, ToolPayload.Edit>? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity(
                        "tool_1",
                        "Edit",
                        ToolStatus.Done,
                        preview = "/tmp/spike/a.txt",
                        args = buildJsonObject {
                            put("path", "/tmp/spike/a.txt")
                            put("old_string", "one\ntwo")
                            put("new_string", "one\nTWO\nthree")
                        },
                        payload = editPayload,
                    ),
                    onOpenEditDiff = { id, payload -> opened = id to payload },
                )
            }
        }

        composeTestRule.onNodeWithText("Edit").performClick()

        assertEquals("tool_1" to editPayload, opened)
        // No inline expansion: the args table's labels never render.
        composeTestRule.onNodeWithText("old_string").assertDoesNotExist()
    }

    @Test
    fun editRowChevronNavigates() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity("tool_1", "Edit", ToolStatus.Running, preview = "/tmp/spike/a.txt", payload = editPayload),
                    onOpenEditDiff = { id, _ -> opened = id },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Open diff").performClick()
        assertEquals("tool_1", opened)
    }

    /** An Edit call whose args did not parse (no payload) keeps the generic
     * key/value expansion. */
    @Test
    fun unparseableEditRowKeepsGenericExpansion() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "Edit",
                ToolStatus.Done,
                preview = "/tmp/spike/a.txt",
                args = buildJsonObject { put("path", "/tmp/spike/other.txt") },
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("path").assertIsDisplayed()
        composeTestRule.onNodeWithText("/tmp/spike/other.txt").assertIsDisplayed()
    }

    // --- Ticket 120: parsed Read/Write rows -----------------------------------------

    private val readPayload = ToolPayload.Read("/tmp/spike/a.txt", offset = 40, limit = 100)
    private val writePayload = ToolPayload.Write("/tmp/spike/a.txt", "one\ntwo\nthree", append = false)

    @Test
    fun rangedReadRowShowsLineRangeChipAndOpenFileChevronWithoutExpansion() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "Read",
                ToolStatus.Done,
                preview = "/tmp/spike/a.txt",
                payload = readPayload,
                output = "    40→forty",
            ),
        )

        composeTestRule.onNodeWithText("lines 40–139").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Open file").assertIsDisplayed()
    }

    @Test
    fun fullFileReadRowShowsNoRangeChip() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "Read",
                ToolStatus.Done,
                preview = "/tmp/spike/a.txt",
                payload = ToolPayload.Read("/tmp/spike/a.txt", offset = null, limit = null),
            ),
        )

        composeTestRule.onNode(hasText("lines", substring = true)).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Open file").assertIsDisplayed()
    }

    @Test
    fun tappingReadRowNavigatesWithPayloadAndOutputSnapshot() {
        var opened: Triple<String, ToolPayload, String>? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity(
                        "tool_1",
                        "Read",
                        ToolStatus.Running,
                        preview = "/tmp/spike/a.txt",
                        args = buildJsonObject {
                            put("path", "/tmp/spike/a.txt")
                            put("line_offset", 40)
                            put("n_lines", 100)
                        },
                        payload = readPayload,
                        output = "    40→partial",
                    ),
                    onOpenFileContent = { id, payload, output -> opened = Triple(id, payload, output) },
                )
            }
        }

        composeTestRule.onNodeWithText("Read").performClick()

        assertEquals(Triple("tool_1", readPayload, "    40→partial"), opened)
        // No inline expansion: the args table's labels never render.
        composeTestRule.onNodeWithText("line_offset").assertDoesNotExist()
    }

    @Test
    fun readRowChevronNavigates() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity("tool_1", "Read", ToolStatus.Done, preview = "/tmp/spike/a.txt", payload = readPayload),
                    onOpenFileContent = { id, _, _ -> opened = id },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Open file").performClick()
        assertEquals("tool_1", opened)
    }

    @Test
    fun writeRowShowsSizeChipAndNoAppendBadgeForOverwrite() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "Write",
                ToolStatus.Done,
                preview = "/tmp/spike/a.txt",
                payload = writePayload,
            ),
        )

        // "one\ntwo\nthree": 3 lines, 13 bytes.
        composeTestRule.onNodeWithText("3 lines · 13 B").assertIsDisplayed()
        composeTestRule.onNodeWithText("append").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Open file").assertIsDisplayed()
    }

    @Test
    fun appendWriteRowShowsAppendBadge() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "Write",
                ToolStatus.Done,
                preview = "/tmp/spike/a.txt",
                payload = writePayload.copy(append = true),
            ),
        )

        composeTestRule.onNodeWithText("append").assertIsDisplayed()
    }

    @Test
    fun tappingWriteRowNavigatesWithPayloadAndOutputSnapshot() {
        var opened: Triple<String, ToolPayload, String>? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity(
                        "tool_1",
                        "Write",
                        ToolStatus.Done,
                        preview = "/tmp/spike/a.txt",
                        payload = writePayload,
                        output = "File written",
                    ),
                    onOpenFileContent = { id, payload, output -> opened = Triple(id, payload, output) },
                )
            }
        }

        composeTestRule.onNodeWithText("Write").performClick()

        assertEquals(Triple("tool_1", writePayload, "File written"), opened)
    }

    /** A Read/Write call whose args did not parse (no payload) keeps the generic
     * key/value expansion. */
    @Test
    fun unparseableReadRowKeepsGenericExpansion() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "Read",
                ToolStatus.Done,
                preview = "/tmp/spike/a.txt",
                args = buildJsonObject { put("line_offset", 40) },
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("line_offset").assertIsDisplayed()
    }

    // --- Ticket 121: parsed Bash rows — the terminal detail block -------------------

    /** A foreground Bash row with its parsed payload: args, payload and the row's
     * budget fields kept in sync like the live derivation does (tickets 097/098). */
    private fun parsedBashRow(
        command: String,
        description: String? = null,
        cwd: String? = null,
        status: ToolStatus = ToolStatus.Done,
        output: String = "",
        preview: String = "run it",
        timeoutSeconds: Int? = null,
        timeoutDisabled: Boolean = false,
        bashTaskId: String? = null,
    ) = ChatItem.ToolActivity(
        "tool_1",
        "Bash",
        status,
        preview = preview,
        args = buildJsonObject {
            put("command", command)
            description?.let { put("description", it) }
            cwd?.let { put("cwd", it) }
            timeoutSeconds?.let { put("timeout", it) }
            if (timeoutDisabled) put("disable_timeout", true)
        },
        payload = ToolPayload.Bash(command, description, cwd, timeoutSeconds, timeoutDisabled),
        output = output,
        timeoutSeconds = timeoutSeconds,
        timeoutDisabled = timeoutDisabled,
        bashTaskId = bashTaskId,
    )

    @Test
    fun parsedBashRowExpandsToCaptionAndTerminalCommandBlock() {
        toolRow(
            parsedBashRow(
                command = "./gradlew build",
                description = "Compile and assemble the app",
                preview = "Compile and assemble the app",
                output = "BUILD SUCCESSFUL\n",
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        // The description is already the row's preview (ticket 095) — collapsed and
        // caption both show it.
        composeTestRule.onAllNodesWithText("Compile and assemble the app").assertCountEquals(2)
        composeTestRule.onNodeWithText("\$ ").assertIsDisplayed()
        composeTestRule.onNodeWithText("./gradlew build").assertIsDisplayed()
        composeTestRule.onNodeWithText("BUILD SUCCESSFUL\n").assertIsDisplayed()
        // The generic key/value table is replaced: no arg labels, no JSON braces.
        composeTestRule.onNodeWithText("command").assertDoesNotExist()
        composeTestRule.onNodeWithText("{").assertDoesNotExist()
    }

    /** The acceptance case: a default call (no description/cwd/timeout) shows no
     * chips at all. */
    @Test
    fun defaultParsedBashRowShowsNoChips() {
        toolRow(parsedBashRow(command = "./gradlew build", output = "ok\n"))

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("./gradlew build").assertIsDisplayed()
        composeTestRule.onNode(hasText("⏱", substring = true)).assertDoesNotExist()
        composeTestRule.onNodeWithText("no timeout").assertDoesNotExist()
    }

    @Test
    fun parsedBashRowShowsCwdAndTimeoutChips() {
        toolRow(parsedBashRow(command = "make test", cwd = "/tmp/spike", timeoutSeconds = 30))

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("/tmp/spike").assertIsDisplayed()
        composeTestRule.onNodeWithText("⏱ 30s").assertIsDisplayed()
    }

    @Test
    fun parsedBashRowWithDisabledTimeoutShowsNoTimeoutChip() {
        toolRow(parsedBashRow(command = "./gradlew build", timeoutDisabled = true))

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("no timeout").assertIsDisplayed()
        composeTestRule.onNode(hasText("⏱", substring = true)).assertDoesNotExist()
    }

    @Test
    fun failedParsedBashRowStripsTrailerAndShowsExitChip() {
        toolRow(
            parsedBashRow(
                command = "make deploy",
                status = ToolStatus.Error,
                output = "boom happened\nCommand failed with exit code: 1",
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("boom happened").assertIsDisplayed()
        composeTestRule.onNode(hasText("Command failed with exit code", substring = true)).assertDoesNotExist()
        composeTestRule.onNodeWithText("exit 1").assertIsDisplayed()
    }

    /** A background-linked row keeps navigating to the job screen even with a parsed
     * payload — it never expands inline (tickets 096/121). */
    @Test
    fun backgroundLinkedParsedBashRowNavigatesInsteadOfExpanding() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    parsedBashRow(
                        command = "for i in …",
                        output = "task_id: bash-1\nstatus: running\n",
                        bashTaskId = "bash-1",
                    ),
                    onOpenJob = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Open job").assertIsDisplayed()

        composeTestRule.onNodeWithText("Bash").performClick()
        assertEquals("bash-1", opened)
        // No expansion: the terminal command block never renders.
        composeTestRule.onNodeWithText("for i in …").assertDoesNotExist()
    }

    /** A Bash call whose args did not parse (no command) keeps the generic key/value
     * expansion. */
    @Test
    fun unparseableBashRowKeepsGenericExpansion() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "Bash",
                ToolStatus.Done,
                preview = "sleep 30",
                args = buildJsonObject { put("timeout", 30) },
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("timeout").assertIsDisplayed()
        composeTestRule.onNodeWithText("30").assertIsDisplayed()
    }

    // --- Ticket 122: parsed lookup rows (Glob / WebSearch / FetchURL) -----------------

    /** A parsed Glob row, args/payload kept in sync like the live derivation. */
    private fun globRow(
        pattern: String = "*.kt",
        path: String? = null,
        includeIgnored: Boolean = false,
        status: ToolStatus = ToolStatus.Done,
        output: String = "",
    ) = ChatItem.ToolActivity(
        "tool_1",
        "Glob",
        status,
        preview = pattern,
        args = buildJsonObject {
            put("pattern", pattern)
            path?.let { put("path", it) }
            if (includeIgnored) put("include_ignored", true)
        },
        payload = ToolPayload.Glob(pattern, path, includeIgnored),
        output = output,
    )

    @Test
    fun globRowShowsMagnifierIconAndPatternPreview() {
        toolRow(globRow(output = "app/a.kt\n"))

        composeTestRule.onNodeWithTag("toolIconGlob", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Glob").assertIsDisplayed()
        composeTestRule.onNodeWithText("*.kt").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand tool details").assertIsDisplayed()
    }

    @Test
    fun webSearchRowShowsGlobeIconAndProseQueryPreview() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "WebSearch",
                ToolStatus.Done,
                preview = "kimi code changelog",
                args = buildJsonObject { put("query", "kimi code changelog") },
                payload = ToolPayload.WebSearch("kimi code changelog"),
                output = "results",
            ),
        )

        composeTestRule.onNodeWithTag("toolIconWebSearch", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("kimi code changelog").assertIsDisplayed()
    }

    @Test
    fun fetchUrlRowShowsLinkIconAndHostFirstPreview() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "FetchURL",
                ToolStatus.Done,
                preview = "https://example.com/docs/guide/install.html",
                args = buildJsonObject { put("url", "https://example.com/docs/guide/install.html") },
                payload = ToolPayload.FetchUrl("https://example.com/docs/guide/install.html"),
            ),
        )

        composeTestRule.onNodeWithTag("toolIconFetchUrl", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("example.com/docs/…").assertIsDisplayed()
        // The full URL is the expansion's, not the row's.
        composeTestRule.onNodeWithText("https://example.com/docs/guide/install.html").assertDoesNotExist()
    }

    @Test
    fun globRowExpandsToCountedPathList() {
        toolRow(globRow(output = "app/a.kt\napp/b.kt\n"))

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("2 matches").assertIsDisplayed()
        composeTestRule.onNodeWithText("app/a.kt\napp/b.kt").assertIsDisplayed()
        // The generic key/value table is replaced: no arg labels.
        composeTestRule.onNodeWithText("pattern").assertDoesNotExist()
        composeTestRule.onNodeWithText("truncated").assertDoesNotExist()
    }

    @Test
    fun truncatedGlobRowShowsChipAndStrippedList() {
        toolRow(
            globRow(
                output = "[Truncated at 100 matches \n use a more specific pattern]\n" +
                    "Only the first 100 matches are returned.\napp/a.kt\napp/b.kt",
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("2 matches").assertIsDisplayed()
        composeTestRule.onNodeWithText("truncated").assertIsDisplayed()
        composeTestRule.onNodeWithText("app/a.kt\napp/b.kt").assertIsDisplayed()
        composeTestRule.onNode(hasText("Only the first", substring = true)).assertDoesNotExist()
        composeTestRule.onNode(hasText("Truncated at", substring = true)).assertDoesNotExist()
    }

    @Test
    fun globRowWithNonDefaultScopeShowsChips() {
        toolRow(globRow(path = "/tmp/spike", includeIgnored = true, output = "a.kt\n"))

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("/tmp/spike").assertIsDisplayed()
        composeTestRule.onNodeWithText("including ignored").assertIsDisplayed()
    }

    @Test
    fun globRowWithStatusOnlyOutputRendersVerbatim() {
        toolRow(globRow(output = "No matches found"))

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("No matches found").assertIsDisplayed()
        // Not a one-line "list": no counted header.
        composeTestRule.onNodeWithText("1 match").assertDoesNotExist()
    }

    @Test
    fun globErrorRowRendersVerbatim() {
        toolRow(globRow(status = ToolStatus.Error, output = "/tmp/nope does not exist"))

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("/tmp/nope does not exist").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 match").assertDoesNotExist()
    }

    @Test
    fun webSearchRowExpandsToRenderedMarkdown() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "WebSearch",
                ToolStatus.Done,
                preview = "kimi code changelog",
                args = buildJsonObject { put("query", "kimi code changelog") },
                payload = ToolPayload.WebSearch("kimi code changelog"),
                output = "Title: Some Page\n\n[Open the guide](https://example.com/guide)",
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        // Rendered markdown: the link text node exists, the raw markdown syntax does not.
        composeTestRule.onNodeWithText("Some Page", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Open the guide").assertIsDisplayed()
        composeTestRule.onNodeWithText("[Open the guide](https://example.com/guide)").assertDoesNotExist()
    }

    @Test
    fun runningWebSearchRowWithoutOutputStaysNonExpandable() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "WebSearch",
                ToolStatus.Running,
                preview = "kimi code changelog",
                args = buildJsonObject { put("query", "kimi code changelog") },
                payload = ToolPayload.WebSearch("kimi code changelog"),
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
    }

    @Test
    fun fetchUrlRowExpandsToFullUrlNoteCaptionAndVerbatimContent() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "FetchURL",
                ToolStatus.Done,
                preview = "https://example.com/docs/guide",
                args = buildJsonObject { put("url", "https://example.com/docs/guide") },
                payload = ToolPayload.FetchUrl("https://example.com/docs/guide"),
                output = "The returned content is the main text extracted from the page. " +
                    "If you use it in your answer, cite this page as a markdown link, e.g. [title](url).\n\n" +
                    "# Guide\n\nbody text",
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("https://example.com/docs/guide").assertIsDisplayed()
        composeTestRule.onNodeWithText("The returned content is the main text extracted from the page.").assertIsDisplayed()
        composeTestRule.onNodeWithText("# Guide\n\nbody text").assertIsDisplayed()
        // The model-directed citation instruction is stripped with the preamble.
        composeTestRule.onNode(hasText("cite this page", substring = true)).assertDoesNotExist()
    }

    @Test
    fun fetchUrlRowWithDriftedNoteRendersVerbatim() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "FetchURL",
                ToolStatus.Done,
                preview = "https://example.com/x",
                args = buildJsonObject { put("url", "https://example.com/x") },
                payload = ToolPayload.FetchUrl("https://example.com/x"),
                output = "Just some content, no note.",
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("Just some content, no note.").assertIsDisplayed()
    }

    /** A Glob call whose args did not parse (no pattern) keeps the generic key/value
     * expansion. */
    @Test
    fun unparseableGlobRowKeepsGenericExpansion() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "Glob",
                ToolStatus.Done,
                preview = "glob the sources",
                args = buildJsonObject { put("path", "/tmp/spike") },
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("path").assertIsDisplayed()
        composeTestRule.onNodeWithText("/tmp/spike").assertIsDisplayed()
    }

    // --- Ticket 123: parsed Grep rows ---------------------------------------------

    /** A parsed Grep row, args/payload kept in sync like the live derivation. */
    private fun grepRow(
        pattern: String = "match",
        path: String? = null,
        glob: String? = null,
        outputMode: String = "content",
        caseInsensitive: Boolean = false,
        contextC: Int? = null,
        multiline: Boolean = false,
        includeIgnored: Boolean = false,
        type: String? = null,
        status: ToolStatus = ToolStatus.Done,
        output: String = "",
    ) = ChatItem.ToolActivity(
        "tool_1",
        "Grep",
        status,
        preview = pattern,
        args = buildJsonObject {
            put("pattern", pattern)
            path?.let { put("path", it) }
            glob?.let { put("glob", it) }
            put("output_mode", outputMode)
            if (caseInsensitive) put("-i", true)
            contextC?.let { put("-C", it) }
            if (multiline) put("multiline", true)
            if (includeIgnored) put("include_ignored", true)
            type?.let { put("type", it) }
        },
        payload = ToolPayload.Grep(
            pattern,
            path,
            glob,
            outputMode,
            caseInsensitive,
            contextBefore = contextC,
            contextAfter = contextC,
            multiline = multiline,
            includeIgnored = includeIgnored,
            type = type,
        ),
        output = output,
    )

    @Test
    fun grepRowShowsMagnifierIconAndSlashDelimitedPatternPreview() {
        toolRow(grepRow(pattern = "foo|bar", output = "app/a.kt:12:beta match here"))

        composeTestRule.onNodeWithTag("toolIconGrep", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Grep").assertIsDisplayed()
        composeTestRule.onNodeWithText("/foo|bar/").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand tool details").assertIsDisplayed()
    }

    @Test
    fun grepRowWithPathShowsScopeChip() {
        toolRow(grepRow(path = "/tmp/spike", output = "app/a.kt:12:beta match here"))

        composeTestRule.onNodeWithText("/tmp/spike").assertIsDisplayed()
    }

    @Test
    fun grepRowWithGlobOnlyShowsGlobScopeChip() {
        toolRow(grepRow(glob = "*.kt", output = "app/a.kt:12:beta match here"))

        composeTestRule.onNodeWithText("*.kt").assertIsDisplayed()
    }

    @Test
    fun grepRowWithPathAndGlobPrefersThePathScopeChip() {
        toolRow(grepRow(path = "/tmp/spike", glob = "*.kt", output = "app/a.kt:12:beta match here"))

        composeTestRule.onNodeWithText("/tmp/spike").assertIsDisplayed()
        composeTestRule.onNodeWithText("*.kt").assertDoesNotExist()
    }

    @Test
    fun grepContentRowExpandsToPerFileGroups() {
        toolRow(
            grepRow(
                // The wire's context-line shape is `path-NN-text` (dash both
                // sides) — `path-NN:text` fails the grammar and renders verbatim.
                output = "app/a.kt:12:beta match here\n" +
                    "app/a.kt-13-after context\n" +
                    "--\n" +
                    "sub/b.kt:2:match in b",
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        // The per-file group headers, the dimmed line numbers as their own nodes,
        // the context line's text; the `--` separator never renders as text.
        composeTestRule.onNodeWithText("app/a.kt").assertIsDisplayed()
        composeTestRule.onNodeWithText("sub/b.kt").assertIsDisplayed()
        composeTestRule.onNodeWithText("12").assertIsDisplayed()
        composeTestRule.onNodeWithText("beta match here").assertIsDisplayed()
        composeTestRule.onNodeWithText("after context").assertIsDisplayed()
        composeTestRule.onNodeWithText("--").assertDoesNotExist()
        // The generic key/value table is replaced: no arg labels.
        composeTestRule.onNodeWithText("output_mode").assertDoesNotExist()
    }

    @Test
    fun defaultGrepRowShowsNoChips() {
        toolRow(grepRow(output = "app/a.kt:12:beta match here"))

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("app/a.kt").assertIsDisplayed()
        composeTestRule.onNodeWithText("ignore case").assertDoesNotExist()
        composeTestRule.onNode(hasText("±", substring = true)).assertDoesNotExist()
        composeTestRule.onNodeWithText("multiline").assertDoesNotExist()
        composeTestRule.onNodeWithText("include hidden").assertDoesNotExist()
        composeTestRule.onNode(hasText("type:", substring = true)).assertDoesNotExist()
    }

    /** The acceptance case: a `-i -C 2` call shows exactly the `ignore case` and
     * `±2` chips. */
    @Test
    fun grepRowWithCaseInsensitiveAndSymmetricContextShowsExactlyThoseChips() {
        toolRow(grepRow(caseInsensitive = true, contextC = 2, output = "app/a.kt:12:beta match here"))

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("ignore case").assertIsDisplayed()
        composeTestRule.onNodeWithText("±2").assertIsDisplayed()
        composeTestRule.onNodeWithText("multiline").assertDoesNotExist()
        composeTestRule.onNodeWithText("include hidden").assertDoesNotExist()
        composeTestRule.onNode(hasText("type:", substring = true)).assertDoesNotExist()
    }

    @Test
    fun grepRowWithTypeAndHiddenKnobsShowsThoseChips() {
        toolRow(grepRow(includeIgnored = true, type = "kt", multiline = true, output = "app/a.kt:12:beta match here"))

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("include hidden").assertIsDisplayed()
        composeTestRule.onNodeWithText("multiline").assertIsDisplayed()
        composeTestRule.onNodeWithText("type: kt").assertIsDisplayed()
    }

    @Test
    fun grepFilesModeRowExpandsToCountedPathList() {
        toolRow(grepRow(outputMode = "files_with_matches", output = "app/a.kt\nsub/b.kt"))

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("2 matches").assertIsDisplayed()
        composeTestRule.onNodeWithText("app/a.kt\nsub/b.kt").assertIsDisplayed()
    }

    @Test
    fun grepCountModeRowExpandsToSummaryAndCountBadges() {
        toolRow(
            grepRow(
                outputMode = "count_matches",
                output = "Found 5 total occurrences across 2 files.\napp/a.kt:2\nsub/b.kt:3",
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("Found 5 total occurrences across 2 files.").assertIsDisplayed()
        composeTestRule.onNodeWithText("app/a.kt").assertIsDisplayed()
        composeTestRule.onNodeWithText("2").assertIsDisplayed()
        composeTestRule.onNodeWithText("sub/b.kt").assertIsDisplayed()
        composeTestRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun grepRowWithStrippedNoticeShowsItAsCaption() {
        toolRow(
            grepRow(
                output = "app/a.kt:12:beta match here\n" +
                    "Results truncated to 250 lines (total: 400). Use offset=250 to see more.",
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        // The body structures, the lifted notice rides as a caption below it.
        composeTestRule.onNodeWithText("app/a.kt").assertIsDisplayed()
        composeTestRule.onNodeWithText("Results truncated to 250 lines (total: 400). Use offset=250 to see more.")
            .assertIsDisplayed()
    }

    @Test
    fun grepRowWithOneBadLineRendersTheWholeOutputVerbatim() {
        val output = "app/a.kt:12:beta match here\ngarbage line\nsub/b.kt:2:match in b"
        toolRow(grepRow(output = output))

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        // Verbatim: the whole output is one text node; no group structure exists.
        composeTestRule.onNodeWithText(output).assertIsDisplayed()
        composeTestRule.onNodeWithText("app/a.kt").assertDoesNotExist()
        composeTestRule.onNodeWithText("12").assertDoesNotExist()
    }

    @Test
    fun grepErrorRowRendersVerbatim() {
        toolRow(grepRow(status = ToolStatus.Error, output = "Failed to grep: boom"))

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("Failed to grep: boom").assertIsDisplayed()
    }

    @Test
    fun runningGrepRowWithoutOutputOrKnobsStaysNonExpandable() {
        toolRow(grepRow(status = ToolStatus.Running))

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
    }

    /** A Grep call whose args did not parse (no pattern) keeps the generic key/value
     * expansion. */
    @Test
    fun unparseableGrepRowKeepsGenericExpansion() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "Grep",
                ToolStatus.Done,
                preview = "grep the sources",
                args = buildJsonObject { put("path", "/tmp/spike") },
            ),
        )

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("path").assertIsDisplayed()
        composeTestRule.onNodeWithText("/tmp/spike").assertIsDisplayed()
    }

    // --- Ticket 124: cron rows --------------------------------------------------------

    /** A parsed CronCreate row, args/payload kept in sync like the live derivation. */
    private fun cronCreateRow(
        cron: String = "15 7 1 8 *",
        prompt: String = "Run the blocked robo test",
        recurring: Boolean = true,
        status: ToolStatus = ToolStatus.Done,
        output: String = "",
    ) = ChatItem.ToolActivity(
        "tool_1",
        "CronCreate",
        status,
        preview = cron,
        args = buildJsonObject {
            put("cron", cron)
            put("prompt", prompt)
            put("recurring", recurring)
        },
        payload = ToolPayload.CronCreate(cron, prompt, recurring),
        output = output,
    )

    @Test
    fun cronCreateRowShowsClockIconAndCronPreview() {
        toolRow(cronCreateRow())

        composeTestRule.onNodeWithTag("toolIconCron", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("CronCreate").assertIsDisplayed()
        composeTestRule.onNodeWithText("15 7 1 8 *").assertIsDisplayed()
        composeTestRule.onNodeWithText("one-shot").assertDoesNotExist()
    }

    @Test
    fun oneShotCronCreateRowShowsTheBadge() {
        toolRow(cronCreateRow(recurring = false))

        composeTestRule.onNodeWithText("one-shot").assertIsDisplayed()
    }

    /** The expansion carries the full prompt and the verbatim result — the wire
     * shape is `key: value` prose, not JSON (the live capture, docs/server-api.md),
     * so no summary line exists. */
    @Test
    fun cronCreateRowExpandsToFullPromptAndVerbatimResult() {
        val output = "id: 01KYXPV7A4HPX4GNZAAGTSRSMH\ncron: 15 7 1 8 *\nhumanSchedule: at 07:15 on day 1 of August\n" +
            "recurring: false\nnextFireAt: 2026-08-01T07:15:00.000+00:00"
        toolRow(cronCreateRow(prompt = "Run the blocked robo test\nand report back", recurring = false, output = output))

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        composeTestRule.onNodeWithText("Run the blocked robo test\nand report back").assertIsDisplayed()
        composeTestRule.onNodeWithText(output).assertIsDisplayed()
        // The generic key/value table is replaced: no arg labels, no JSON braces.
        composeTestRule.onNodeWithText("prompt").assertDoesNotExist()
        composeTestRule.onNodeWithText("{").assertDoesNotExist()
    }

    @Test
    fun cronDeleteRowShowsClockIconIdPreviewAndGenericExpansion() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "CronDelete",
                ToolStatus.Done,
                preview = "01KYXPV7A4HPX4GNZAAGTSRSMH",
                args = buildJsonObject { put("id", "01KYXPV7A4HPX4GNZAAGTSRSMH") },
                payload = ToolPayload.CronDelete("01KYXPV7A4HPX4GNZAAGTSRSMH"),
                output = "Deleted cron job 01KYXPV7A4HPX4GNZAAGTSRSMH.",
            ),
        )

        composeTestRule.onNodeWithTag("toolIconCron", useUnmergedTree = true).assertIsDisplayed()
        // The row's preview is the job id.
        composeTestRule.onAllNodesWithText("01KYXPV7A4HPX4GNZAAGTSRSMH").assertCountEquals(1)

        // The lean row keeps the generic expansion: the args table + verbatim output.
        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("id").assertIsDisplayed()
        // …the id now shown twice — the row's preview and the table's value.
        composeTestRule.onAllNodesWithText("01KYXPV7A4HPX4GNZAAGTSRSMH").assertCountEquals(2)
        composeTestRule.onNodeWithText("Deleted cron job 01KYXPV7A4HPX4GNZAAGTSRSMH.").assertIsDisplayed()
    }

    @Test
    fun cronListRowShowsClockIconLabelPreviewAndVerbatimOutput() {
        val output = "cron_jobs: 1\n" +
            "id: 01KYXPV7A4HPX4GNZAAGTSRSMH\ncron: 15 7 1 8 *\nhumanSchedule: at 07:15 on day 1 of August\n" +
            "prompt: \"Run the blocked robo test\"\nnextFireAt: 2026-08-01T07:15:00.000+00:00\n" +
            "recurring: false\nageDays: 0.00\nstale: false"
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "CronList",
                ToolStatus.Done,
                args = buildJsonObject { },
                output = output,
            ),
        )

        composeTestRule.onNodeWithTag("toolIconCron", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("list schedules").assertIsDisplayed()

        // The multi-record prose stays verbatim — no parser by design (out of scope).
        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText(output).assertIsDisplayed()
    }

    // --- Ticket 124: task-control rows --------------------------------------------------

    /** A parsed task-control row, args/payload kept in sync like the live derivation
     * (the preview is ToolPreview's compact-JSON fallback — no identifying key). */
    private fun taskControlRow(
        name: String,
        taskId: String?,
        output: String = "task output",
    ): ChatItem.ToolActivity {
        val args = buildJsonObject {
            when (name) {
                "TaskList" -> {
                    put("active_only", true)
                    put("limit", 20)
                }
                else -> {
                    taskId?.let { put("task_id", it) }
                    if (name == "WaitFor") put("timeout", 300)
                }
            }
        }
        return ChatItem.ToolActivity(
            "tool_1",
            name,
            ToolStatus.Done,
            preview = ToolPreview.of(name, args),
            args = args,
            payload = when (name) {
                "TaskOutput" -> taskId?.let { ToolPayload.TaskOutput(it) }
                "TaskStop" -> taskId?.let { ToolPayload.TaskStop(it) }
                "WaitFor" -> ToolPayload.WaitFor(taskId)
                "TaskList" -> ToolPayload.TaskList(activeOnly = true, limit = 20)
                else -> null
            },
            output = output,
        )
    }

    /** A TaskOutput row linked by its task_id behaves like a background-linked Bash
     * row (ticket 096): no inline expansion, the tap navigates to the job screen. */
    @Test
    fun taskOutputRowNeverExpandsAndNavigatesOnRowTap() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    taskControlRow("TaskOutput", "bash-1"),
                    onOpenJob = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Open job").assertIsDisplayed()

        composeTestRule.onNodeWithText("TaskOutput").performClick()
        composeTestRule.onNodeWithText("task output").assertDoesNotExist()
        assertEquals("bash-1", opened)
    }

    @Test
    fun taskStopRowOpensJobFromTrailingButton() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    taskControlRow("TaskStop", "bash-2"),
                    onOpenJob = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Open job").performClick()
        assertEquals("bash-2", opened)
    }

    @Test
    fun waitForRowWithTaskIdGetsTheChevron() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    taskControlRow("WaitFor", "bash-3"),
                    onOpenJob = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Open job").performClick()
        assertEquals("bash-3", opened)
    }

    /** A wait-any WaitFor (no task_id) keeps the generic expansion — no chevron. */
    @Test
    fun waitForRowWithoutTaskIdKeepsGenericExpansion() {
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    taskControlRow("WaitFor", null),
                    onOpenJob = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Open job").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("timeout").assertIsDisplayed()
        composeTestRule.onNodeWithText("300").assertIsDisplayed()
    }

    /** TaskList carries no task_id: generic expansion, no chevron. */
    @Test
    fun taskListRowKeepsGenericExpansion() {
        toolRow(taskControlRow("TaskList", null))

        composeTestRule.onNodeWithContentDescription("Open job").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("active_only").assertIsDisplayed()
        composeTestRule.onNodeWithText("task output").assertIsDisplayed()
    }

    // --- Ticket 125: meta rows (Skill, plan mode, goal, ReadMediaFile) ----------------

    /** A parsed Skill row, args/payload/preview kept in sync like the live derivation. */
    private fun skillRow(skill: String = "unslop", skillArgs: String? = null): ChatItem.ToolActivity {
        val args = buildJsonObject {
            put("skill", skill)
            skillArgs?.let { put("args", it) }
        }
        return ChatItem.ToolActivity(
            "tool_1",
            "Skill",
            ToolStatus.Done,
            preview = ToolPreview.of("Skill", args),
            args = args,
            payload = ToolPayload.Skill(skill, skillArgs),
        )
    }

    @Test
    fun skillRowShowsSkillNamePreviewAndGenericExpansion() {
        toolRow(skillRow())

        composeTestRule.onNodeWithText("Skill").assertIsDisplayed()
        composeTestRule.onNodeWithText("unslop").assertIsDisplayed()
        // No bespoke glyph: the meta rows bar ReadMediaFile keep the generic tool icon.
        composeTestRule.onNodeWithTag("toolIconReadMedia", useUnmergedTree = true).assertDoesNotExist()

        // The lean row keeps the generic key/value expansion.
        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("skill").assertIsDisplayed()
        // …the name now shown twice — the row's preview and the table's value.
        composeTestRule.onAllNodesWithText("unslop").assertCountEquals(2)
    }

    @Test
    fun skillRowWithArgsShowsTheInvocationPreview() {
        toolRow(skillRow(skill = "commit", skillArgs = "-m \"fix bug\""))

        composeTestRule.onNodeWithText("commit -m \"fix bug\"").assertIsDisplayed()
    }

    /** A parsed ReadMediaFile row, args/payload kept in sync like the live derivation
     * (the parser's all-or-nothing region rule mirrored by hand). */
    private fun readMediaRow(width: Int? = null, height: Int? = null): ChatItem.ToolActivity {
        val fullRegion = width != null && height != null
        val args = buildJsonObject {
            put("path", "/tmp/spike/shot.png")
            if (width != null || height != null) {
                put(
                    "region",
                    buildJsonObject {
                        put("x", 0)
                        put("y", 0)
                        width?.let { put("width", it) }
                        height?.let { put("height", it) }
                    },
                )
            }
        }
        return ChatItem.ToolActivity(
            "tool_1",
            "ReadMediaFile",
            ToolStatus.Done,
            preview = ToolPreview.of("ReadMediaFile", args),
            args = args,
            payload = ToolPayload.ReadMedia(
                "/tmp/spike/shot.png",
                regionWidth = if (fullRegion) width else null,
                regionHeight = if (fullRegion) height else null,
            ),
        )
    }

    @Test
    fun readMediaRowShowsImageIconPathPreviewAndCropBadge() {
        toolRow(readMediaRow(width = 100, height = 200))

        composeTestRule.onNodeWithTag("toolIconReadMedia", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("ReadMediaFile").assertIsDisplayed()
        composeTestRule.onNodeWithText("/tmp/spike/shot.png").assertIsDisplayed()
        composeTestRule.onNodeWithText("crop 100×200").assertIsDisplayed()
    }

    @Test
    fun readMediaRowWithoutRegionShowsNoCropBadgeAndKeepsGenericExpansion() {
        toolRow(readMediaRow())

        composeTestRule.onNodeWithTag("toolIconReadMedia", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNode(hasText("crop", substring = true)).assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("path").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("/tmp/spike/shot.png").assertCountEquals(2)
    }

    /** The ticket's acceptance case on the row: a half-specified region earns no
     * badge (the parser nulled both dimensions). */
    @Test
    fun readMediaRowWithHalfSpecifiedRegionShowsNoCropBadge() {
        toolRow(readMediaRow(width = 100))

        composeTestRule.onNode(hasText("crop", substring = true)).assertDoesNotExist()
    }

    /** A parsed ExitPlanMode row, args/payload kept in sync like the live derivation. */
    private fun exitPlanRow(vararg labels: String): ChatItem.ToolActivity {
        val args = buildJsonObject {
            if (labels.isNotEmpty()) {
                put(
                    "options",
                    buildJsonArray {
                        labels.forEach { label ->
                            add(buildJsonObject { put("label", label) })
                        }
                    },
                )
            }
        }
        return ChatItem.ToolActivity(
            "tool_1",
            "ExitPlanMode",
            ToolStatus.Done,
            preview = ToolPreview.of("ExitPlanMode", args),
            args = args,
            payload = ToolPayload.ExitPlanMode(labels.toList()),
        )
    }

    @Test
    fun exitPlanRowShowsFixedPreviewAndOptionChipsInExpansion() {
        toolRow(exitPlanRow("Rewrite the parser (Recommended)", "Patch the lexer"))

        // The fixed prose label leads the row, never the raw options JSON.
        composeTestRule.onNodeWithText("Plan ready for review").assertIsDisplayed()
        composeTestRule.onNode(hasText("options", substring = true)).assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()

        // The option labels as chips…
        composeTestRule.onNodeWithText("Rewrite the parser (Recommended)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Patch the lexer").assertIsDisplayed()
        // …above the generic key/value table, which stays (the 118 rule).
        composeTestRule.onNodeWithText("options").assertIsDisplayed()
    }

    @Test
    fun plainExitPlanRowShowsTheFixedPreviewWithoutChips() {
        toolRow(exitPlanRow())

        composeTestRule.onNodeWithText("Plan ready for review").assertIsDisplayed()
        // No options on the wire: nothing to expand (no args, no output).
        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
    }

    @Test
    fun updateGoalRowShowsStatusBadgeInsteadOfPreview() {
        val args = buildJsonObject { put("status", "complete") }
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "UpdateGoal",
                ToolStatus.Done,
                // The unsuppressed preview would be the compact-JSON fallback.
                preview = ToolPreview.of("UpdateGoal", args),
                args = args,
                payload = ToolPayload.UpdateGoal("complete"),
            ),
        )

        composeTestRule.onNodeWithText("UpdateGoal").assertIsDisplayed()
        composeTestRule.onNodeWithText("complete").assertIsDisplayed()
        composeTestRule.onNodeWithText("""{"status":"complete"}""").assertDoesNotExist()

        // The lean row keeps the generic key/value expansion.
        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("status").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("complete").assertCountEquals(2)
    }

    @Test
    fun createGoalRowShowsObjectivePreviewAndGenericExpansion() {
        val args = buildJsonObject {
            put("objective", "Fix the memory leak")
            put("completionCriterion", "The leak test passes")
        }
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "CreateGoal",
                ToolStatus.Done,
                preview = ToolPreview.of("CreateGoal", args),
                args = args,
                payload = ToolPayload.CreateGoal("Fix the memory leak", "The leak test passes"),
            ),
        )

        composeTestRule.onNodeWithText("Fix the memory leak").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("completionCriterion").assertIsDisplayed()
        composeTestRule.onNodeWithText("The leak test passes").assertIsDisplayed()
    }

    @Test
    fun setGoalBudgetRowShowsBudgetPreviewAndGenericExpansion() {
        val args = buildJsonObject {
            put("value", 30)
            put("unit", "minutes")
        }
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "SetGoalBudget",
                ToolStatus.Done,
                preview = ToolPreview.of("SetGoalBudget", args),
                args = args,
                payload = ToolPayload.SetGoalBudget(30.0, "minutes"),
            ),
        )

        composeTestRule.onNodeWithText("30 minutes").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("value").assertIsDisplayed()
        composeTestRule.onNodeWithText("30").assertIsDisplayed()
        composeTestRule.onNodeWithText("unit").assertIsDisplayed()
        composeTestRule.onNodeWithText("minutes").assertIsDisplayed()
    }

    /** GetGoal/EnterPlanMode carry no payload by design: the default generic row —
     * generic icon, no tuned preview, verbatim output in the expansion. */
    @Test
    fun goalAndPlanEntryRowsKeepTheDefaultGenericRow() {
        toolRow(ChatItem.ToolActivity("tool_1", "GetGoal", ToolStatus.Done, output = "No active goal."))

        composeTestRule.onNodeWithText("GetGoal").assertIsDisplayed()
        composeTestRule.onNodeWithTag("toolIconReadMedia", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("No active goal.").assertIsDisplayed()
    }

    @Test
    fun enterPlanModeRowKeepsTheDefaultGenericRow() {
        toolRow(ChatItem.ToolActivity("tool_1", "EnterPlanMode", ToolStatus.Done, output = "Entered plan mode."))

        composeTestRule.onNodeWithText("EnterPlanMode").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand tool details").performClick()
        composeTestRule.onNodeWithText("Entered plan mode.").assertIsDisplayed()
    }

    private fun swarmMember(
        id: String,
        status: SubagentStatus,
        model: String? = "kimi-code/k3",
        thinkingEffort: String? = "high",
    ) = SubagentStreamStore.SubagentState(
        id = id,
        name = "explore",
        description = "Check $id",
        runInBackground = false,
        status = status,
        model = model,
        thinkingEffort = thinkingEffort,
    )

    /** Ticket 134: a linked AgentSwarm call renders as the swarm card — sparkles
     * glyph, "Swarm" title, the description arg, the member count — one tap target
     * firing the roster navigation with the tool call id. */
    @Test
    fun swarmCardShowsTitleDescriptionMemberCountAndFiresCallback() {
        val args = buildJsonObject {
            put("description", "review the docs")
            put("prompt_template", "Check {{item}}")
            put("items", buildJsonArray { })
        }
        var opened: String? = null
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity(
                        "tool_1",
                        "AgentSwarm",
                        ToolStatus.Running,
                        preview = ToolPreview.of("AgentSwarm", args),
                    ),
                    swarmMembers = mapOf(
                        "tool_1" to listOf(
                            swarmMember("agent-1", SubagentStatus.Working),
                            swarmMember("agent-2", SubagentStatus.Working),
                            swarmMember("agent-3", SubagentStatus.Queued),
                        ),
                    ),
                    onOpenSwarm = { opened = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("toolIconSwarm", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Swarm").assertIsDisplayed()
        composeTestRule.onNodeWithText("review the docs").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 members").assertIsDisplayed()
        // The generic row is fully replaced: no name row, no expand affordance.
        composeTestRule.onNodeWithText("AgentSwarm").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()

        composeTestRule.onNodeWithText("Swarm").performClick()
        assertEquals("tool_1", opened)
    }

    /** Ticket 134: with zero member links (cold history before the mining lands) the
     * AgentSwarm row keeps today's generic rendering. */
    @Test
    fun agentSwarmRowWithoutLinksKeepsTheGenericRow() {
        toolRow(
            ChatItem.ToolActivity(
                "tool_1",
                "AgentSwarm",
                ToolStatus.Done,
                preview = "review the docs",
                input = "{\"description\":\"review the docs\"}",
            ),
        )

        composeTestRule.onNodeWithText("Swarm").assertDoesNotExist()
        composeTestRule.onNodeWithTag("toolIconSwarm", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("AgentSwarm").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Expand tool details").assertIsDisplayed()
    }

    /** Ticket 135: the running aggregate — the "Running" chip, the done/total count
     * against the items-derived expected total (covering the not-yet-spawned), one
     * bar segment per present phase plus the pending remainder, the in-progress
     * subtitle, and the shared model line when every member agrees. */
    @Test
    fun swarmCardShowsTheRunningAggregateOfItsMembers() {
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity(
                        "tool_1",
                        "AgentSwarm",
                        ToolStatus.Running,
                        preview = "review the docs",
                        payload = ToolPayload.AgentSwarm(description = "review the docs", expectedTotal = 10),
                    ),
                    swarmMembers = mapOf(
                        "tool_1" to listOf(
                            swarmMember("agent-1", SubagentStatus.Working),
                            swarmMember("agent-2", SubagentStatus.Working),
                            swarmMember("agent-3", SubagentStatus.Suspended),
                            swarmMember("agent-4", SubagentStatus.Completed),
                            swarmMember("agent-5", SubagentStatus.Completed),
                            swarmMember("agent-6", SubagentStatus.Completed),
                            swarmMember("agent-7", SubagentStatus.Failed),
                            swarmMember("agent-8", SubagentStatus.Cancelled),
                        ),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("8 members").assertIsDisplayed()
        // Tags inside the card: the whole card is one clickable, so its semantics
        // merge — tag lookups go to the unmerged tree (the 134 test's precedent).
        composeTestRule.onNodeWithTag("swarmAggregateChip", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Running").assertIsDisplayed()
        composeTestRule.onNodeWithTag("swarmDoneTotal", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("5/10").assertIsDisplayed()
        // One segment per present phase, none for the absent queued, the track for
        // the 2 not-yet-spawned members; the bar's contentDescription summarizes.
        composeTestRule.onNodeWithTag("swarmBarSegmentWorking", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("swarmBarSegmentSuspended", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("swarmBarSegmentCompleted", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("swarmBarSegmentFailed", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("swarmBarSegmentCancelled", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("swarmBarSegmentPending", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("swarmBarSegmentQueued", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("swarmProgressBar", useUnmergedTree = true)
            .assertContentDescriptionEquals("2 working · 1 suspended · 3 completed · 1 failed · 1 cancelled")
        composeTestRule.onNodeWithTag("swarmSubtitle", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("3 in progress").assertIsDisplayed()
        composeTestRule.onNodeWithTag("swarmModelLine", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("kimi-code/k3 · high").assertIsDisplayed()
    }

    /** Ticket 135: the settled aggregate — any failure makes the chip "Failed", the
     * subtitle counts cancelled separately, and a mixed model pair hides the line. */
    @Test
    fun swarmCardShowsTheSettledAggregateOfItsMembers() {
        composeTestRule.setContent {
            KimiTheme {
                ToolRow(
                    ChatItem.ToolActivity(
                        "tool_1",
                        "AgentSwarm",
                        ToolStatus.Done,
                        preview = "review the docs",
                    ),
                    swarmMembers = mapOf(
                        "tool_1" to listOf(
                            swarmMember("agent-4", SubagentStatus.Completed),
                            swarmMember("agent-5", SubagentStatus.Completed),
                            swarmMember("agent-6", SubagentStatus.Completed),
                            swarmMember("agent-7", SubagentStatus.Failed),
                            swarmMember("agent-8", SubagentStatus.Cancelled, model = "kimi-code/k2"),
                        ),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("Failed").assertIsDisplayed()
        composeTestRule.onNodeWithText("5/5").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 completed · 1 failed · 1 cancelled").assertIsDisplayed()
        // The card's clickable merges its semantics: the absent-tag checks only
        // mean something in the unmerged tree.
        composeTestRule.onNodeWithTag("swarmBarSegmentPending", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("swarmBarSegmentWorking", useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag("swarmModelLine", useUnmergedTree = true).assertDoesNotExist()
    }

    // --- Ticket 157: agent_call spawn rows --------------------------------------------

    /** A witnessed `Agent` spawn row: the parsed display rides the row (the raw args
     * preview stays behind — the spawn row must show the prompt, not the JSON wall). */
    private fun agentSpawnRow(background: Boolean) = ChatItem.ToolActivity(
        "tool_1",
        "Agent",
        ToolStatus.Running,
        preview = """{"subagent_type":"explore","prompt":"Reply with the single word: ok"}""",
        agentCall = AgentCallDisplay("explore", "Reply with the single word: ok", background),
    )

    @Test
    fun agentSpawnRowShowsNamePromptAndBackgroundBadge() {
        toolRow(agentSpawnRow(background = true))

        composeTestRule.onNodeWithText("explore sub-agent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reply with the single word: ok").assertIsDisplayed()
        composeTestRule.onNodeWithText("Background").assertIsDisplayed()
        // The dedicated row never expands — the spawn's deep link owns the tap.
        composeTestRule.onNodeWithContentDescription("Expand tool details").assertDoesNotExist()
    }

    @Test
    fun foregroundAgentSpawnRowShowsNameAndPromptWithoutTheBadge() {
        toolRow(agentSpawnRow(background = false))

        composeTestRule.onNodeWithText("explore sub-agent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reply with the single word: ok").assertIsDisplayed()
        composeTestRule.onNodeWithText("Background").assertDoesNotExist()
    }
}
