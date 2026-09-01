package fr.louissaglio.kimi.android.ui.chat

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
import fr.louissaglio.kimi.android.data.chat.ToolPayload
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
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
}
