package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.ORIGIN_KIND_COMPACTION_SUMMARY
import fr.louissaglio.kimi.android.data.chat.PromptStatus
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.Todo
import fr.louissaglio.kimi.android.data.chat.ToolPayload
import fr.louissaglio.kimi.android.data.chat.TranscriptFrame
import fr.louissaglio.kimi.android.data.chat.TranscriptState
import fr.louissaglio.kimi.android.data.chat.TranscriptStep
import fr.louissaglio.kimi.android.data.chat.TranscriptTurn
import fr.louissaglio.kimi.android.testutil.testChatTimeline
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineTest {

    private fun userMessage(id: String, text: String) =
        ChatMessage(id, ChatMessage.Role.User, listOf(ChatMessage.Part.Text(text)))

    /** A single tool row as the group the reducer wraps it in (ticket 037). */
    private fun toolGroup(tool: ChatItem.ToolActivity) = ChatItem.ToolGroup(tool)

    @Test
    fun `a user message mixing an attachment and real text renders the text plus a chip`() {
        val timeline = testChatTimeline()

        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.User,
                    listOf(
                        ChatMessage.Part.Attachment("notes.txt", "text/plain", 340, "f_01KYA4RCBE4NPA1RX5TT7DB5J9"),
                        ChatMessage.Part.Image(base64Data = "aGVsbG8=", fileId = null),
                        ChatMessage.Part.Text("what is in these?"),
                    ),
                ),
            ),
            keepLive = false,
        )

        assertEquals(
            listOf(
                ChatItem.UserMessage(
                    "msg_1",
                    "what is in these?",
                    attachments = listOf(
                        ChatMessage.Part.Attachment("notes.txt", "text/plain", 340, "f_01KYA4RCBE4NPA1RX5TT7DB5J9"),
                    ),
                    images = listOf(ChatMessage.Part.Image(base64Data = "aGVsbG8=", fileId = null)),
                ),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `history and spliced user messages carry their timestamp to the bubble`() {
        val timeline = testChatTimeline()
        val stamp = Instant.parse("2026-07-26T06:30:00Z")

        timeline.replaceHistory(
            listOf(
                ChatMessage("msg_1", ChatMessage.Role.User, listOf(ChatMessage.Part.Text("hi")), createdAt = stamp),
            ),
            keepLive = false,
        )
        timeline.apply(
            SessionEvent.ContextSpliced(
                listOf(
                    ChatMessage("msg_2", ChatMessage.Role.User, listOf(ChatMessage.Part.Text("again")), createdAt = stamp),
                ),
            ),
        )

        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "hi", createdAt = stamp),
                ChatItem.UserMessage("msg_2", "again", createdAt = stamp),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `a live spliced message gains its timestamp on the turn-end refetch`() {
        val timeline = testChatTimeline()
        val stamp = Instant.parse("2026-07-26T06:30:00Z")

        // The splice echo carries no created_at: the bubble starts without a caption.
        timeline.apply(
            SessionEvent.ContextSpliced(
                listOf(ChatMessage("msg_1", ChatMessage.Role.User, listOf(ChatMessage.Part.Text("hi")))),
            ),
        )
        assertEquals(listOf(ChatItem.UserMessage("msg_1", "hi", createdAt = null)), timeline.items())

        timeline.replaceHistory(
            listOf(
                ChatMessage("msg_1", ChatMessage.Role.User, listOf(ChatMessage.Part.Text("hi")), createdAt = stamp),
            ),
            keepLive = false,
        )

        assertEquals(listOf(ChatItem.UserMessage("msg_1", "hi", createdAt = stamp)), timeline.items())
    }

    @Test
    fun `an attachments-only user message still renders a bubble`() {
        val timeline = testChatTimeline()

        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.User,
                    listOf(ChatMessage.Part.Attachment("notes.txt", "text/plain", 340, null)),
                ),
            ),
            keepLive = false,
        )

        assertEquals(
            listOf(
                ChatItem.UserMessage(
                    "msg_1",
                    "",
                    attachments = listOf(ChatMessage.Part.Attachment("notes.txt", "text/plain", 340, null)),
                ),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `history flattens to bubbles and tool groups in order`() {
        val timeline = testChatTimeline()

        timeline.replaceHistory(
            listOf(
                userMessage("msg_1", "run echo hi"),
                ChatMessage(
                    "msg_2",
                    ChatMessage.Role.Assistant,
                    listOf(
                        ChatMessage.Part.Thinking("hmm"),
                        ChatMessage.Part.Text("let me run that"),
                        ChatMessage.Part.ToolUse("tool_1", "Bash"),
                    ),
                ),
                ChatMessage("msg_3", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", false))),
            ),
            keepLive = false,
        )

        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "run echo hi"),
                ChatItem.AssistantMessage("msg_2", "let me run that", "hmm"),
                toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done)),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `adjacent history tool calls coalesce into one group`() {
        val timeline = testChatTimeline()

        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(
                        ChatMessage.Part.ToolUse("tool_1", "Bash"),
                        ChatMessage.Part.ToolUse("tool_2", "Read"),
                    ),
                ),
                ChatMessage(
                    "msg_2",
                    ChatMessage.Role.Tool,
                    listOf(
                        ChatMessage.Part.ToolResult("tool_1", false),
                        ChatMessage.Part.ToolResult("tool_2", false),
                    ),
                ),
            ),
            keepLive = false,
        )

        assertEquals(
            listOf(
                ChatItem.ToolGroup(
                    "tool_1",
                    listOf(
                        ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done),
                        ChatItem.ToolActivity("tool_2", "Read", ToolStatus.Done),
                    ),
                ),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `an assistant message between two tool runs splits the group`() {
        val timeline = testChatTimeline()

        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("tool_1", "Bash")),
                ),
                ChatMessage("msg_2", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", false))),
                ChatMessage(
                    "msg_3",
                    ChatMessage.Role.Assistant,
                    listOf(
                        ChatMessage.Part.Text("now reading the file"),
                        ChatMessage.Part.ToolUse("tool_2", "Read"),
                    ),
                ),
                ChatMessage("msg_4", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_2", false))),
            ),
            keepLive = false,
        )

        assertEquals(
            listOf(
                toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done)),
                ChatItem.AssistantMessage("msg_3", "now reading the file", ""),
                toolGroup(ChatItem.ToolActivity("tool_2", "Read", ToolStatus.Done)),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `tool result with is_error marks the row as error`() {
        val timeline = testChatTimeline()

        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("tool_1", "Bash")),
                ),
                ChatMessage("msg_2", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", true))),
            ),
            keepLive = false,
        )

        assertEquals(
            listOf(toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Error))),
            timeline.items(),
        )
    }

    @Test
    fun `history tool use threads its preview into the row`() {
        val timeline = testChatTimeline()

        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("tool_1", "Bash", "echo doc-verify")),
                ),
                ChatMessage("msg_2", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", false))),
            ),
            keepLive = false,
        )

        assertEquals(
            listOf(toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, "echo doc-verify"))),
            timeline.items(),
        )
    }

    @Test
    fun `tool result without a matching call renders a row with an empty preview`() {
        val timeline = testChatTimeline()

        timeline.replaceHistory(
            listOf(ChatMessage("msg_1", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_9", false)))),
            keepLive = false,
        )

        assertEquals(
            listOf(toolGroup(ChatItem.ToolActivity("tool_9", "", ToolStatus.Done, ""))),
            timeline.items(),
        )
    }

    @Test
    fun `a background bash result links the row to its job`() {
        // Verbatim background Bash tool result (docs/server-api.md, REST /tasks —
        // ticket 096): the first line's `task_id: bash-…` is the row→job link.
        val output = "task_id: bash-2q5gu61e\npid: 502439\ndescription: tick counter loop (120)\n" +
            "status: running\nautomatic_notification: true\nnext_step: …\n"
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "tick counter loop (120)"))

        timeline.apply(SessionEvent.ToolCallResult("tool_1", false, output))

        assertEquals(
            listOf(
                toolGroup(
                    ChatItem.ToolActivity(
                        "tool_1", "Bash", ToolStatus.Done, "tick counter loop (120)",
                        output = output,
                        bashTaskId = "bash-2q5gu61e",
                    ),
                ),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `a background agent result links its agent task id but never the bash one`() {
        // Background Agent results also start with `task_id:` but an agent-… id
        // (docs/server-api.md): the bash- prefix requirement keeps bashTaskId null
        // (ticket 096) — the id lands on the row's agentTaskId instead (ticket 157),
        // the spawn row's own job-screen link.
        val output = "task_id: agent-7noib8bb\nstatus: running\nagent_id: agent-13\nactual_subagent_type: explore\n"
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Agent", "doc-verify-bg"))

        timeline.apply(SessionEvent.ToolCallResult("tool_1", false, output))

        assertEquals(
            listOf(
                toolGroup(
                    ChatItem.ToolActivity(
                        "tool_1", "Agent", ToolStatus.Done, "doc-verify-bg",
                        output = output,
                        subagentId = "agent-13",
                        agentTaskId = "agent-7noib8bb",
                    ),
                ),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `a bash result without a task id leaves the row unlinked`() {
        // Foreground Bash: plain shell output — the row keeps its inline expand.
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "echo hi"))

        timeline.apply(SessionEvent.ToolCallResult("tool_1", false, "hi\n"))

        assertEquals(
            listOf(toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, "echo hi", output = "hi\n"))),
            timeline.items(),
        )
    }

    @Test
    fun `a history background bash pair links the row to its job`() {
        // History carries the same stub text in the tool_result part
        // (docs/server-api.md), so the row→job link survives a session re-entry.
        val output = "task_id: bash-2q5gu61e\npid: 502439\ndescription: tick counter loop (120)\nstatus: running\n"
        val timeline = testChatTimeline()

        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("tool_1", "Bash", "tick counter loop (120)")),
                ),
                ChatMessage("msg_2", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", false, output))),
            ),
            keepLive = false,
        )

        assertEquals(
            listOf(
                toolGroup(
                    ChatItem.ToolActivity(
                        "tool_1", "Bash", ToolStatus.Done, "tick counter loop (120)",
                        output = output,
                        bashTaskId = "bash-2q5gu61e",
                    ),
                ),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `a background bash row keeps the call input's explicit timeout`() {
        // Ticket 097: the call input is the ONLY wire location of the kill budget —
        // parsed at the same link site as the result's task_id, live args here.
        val output = "task_id: bash-2q5gu61e\npid: 502439\nstatus: running\n"
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(
            SessionEvent.ToolCallStarted(
                "tool_1",
                "Bash",
                "tick counter loop (120)",
                input = """{"command":"for i in …","timeout":30}""",
            ),
        )

        timeline.apply(SessionEvent.ToolCallResult("tool_1", false, output))

        val tool = (timeline.items().single() as ChatItem.ToolGroup).tools.single()
        assertEquals("bash-2q5gu61e", tool.bashTaskId)
        assertEquals(30, tool.timeoutSeconds)
        assertEquals(false, tool.timeoutDisabled)
    }

    @Test
    fun `a background bash row keeps the call input's disable_timeout`() {
        val output = "task_id: bash-2q5gu61e\npid: 502439\nstatus: running\n"
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(
            SessionEvent.ToolCallStarted(
                "tool_1",
                "Bash",
                "tick counter loop (120)",
                input = """{"command":"for i in …","disable_timeout":true}""",
            ),
        )

        timeline.apply(SessionEvent.ToolCallResult("tool_1", false, output))

        val tool = (timeline.items().single() as ChatItem.ToolGroup).tools.single()
        assertEquals(null, tool.timeoutSeconds)
        assertEquals(true, tool.timeoutDisabled)
    }

    @Test
    fun `a history background bash pair keeps the tool_use input's timeout`() {
        // History tool_use parts carry the same input object, so the budget survives
        // a session re-entry exactly like the task_id link.
        val output = "task_id: bash-2q5gu61e\npid: 502439\nstatus: running\n"
        val timeline = testChatTimeline()

        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(
                        ChatMessage.Part.ToolUse(
                            "tool_1",
                            "Bash",
                            "tick counter loop (120)",
                            input = """{"command":"for i in …","timeout":30}""",
                        ),
                    ),
                ),
                ChatMessage("msg_2", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", false, output))),
            ),
            keepLive = false,
        )

        val tool = (timeline.items().single() as ChatItem.ToolGroup).tools.single()
        assertEquals("bash-2q5gu61e", tool.bashTaskId)
        assertEquals(30, tool.timeoutSeconds)
        assertEquals(false, tool.timeoutDisabled)
    }

    @Test
    fun `the enrichment derives every link and never overwrites set fields`() {
        // Ticket 098's single choke point: bashTaskId from the result's first line,
        // subagentId from the agent_id marker, the timeout knobs from the call input.
        val bashOutput = "task_id: bash-2q5gu61e\npid: 502439\nstatus: running\n"
        val agentOutput = "task_id: agent-7noib8bb\nstatus: running\nagent_id: agent-13\n"
        val input = """{"command":"for i in …","timeout":30}"""

        val derived = ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, input = input, output = bashOutput)
            .enriched()
        assertEquals("bash-2q5gu61e", derived.bashTaskId)
        assertEquals(30, derived.timeoutSeconds)
        assertEquals(false, derived.timeoutDisabled)

        assertEquals(
            "agent-13",
            ChatItem.ToolActivity("tool_2", "Agent", ToolStatus.Done, output = agentOutput).enriched().subagentId,
        )

        // First writer wins: a live/registry-learned field survives any later pass.
        val linked = ChatItem.ToolActivity(
            "tool_3", "Bash", ToolStatus.Done,
            input = """{"command":"for i in …","timeout":30}""",
            output = bashOutput,
            subagentId = "agent-99",
            bashTaskId = "bash-livelearned",
            timeoutSeconds = 120,
            timeoutDisabled = true,
        ).enriched(name = "Agent", output = agentOutput)
        assertEquals("agent-99", linked.subagentId)
        assertEquals("bash-livelearned", linked.bashTaskId)
        assertEquals(120, linked.timeoutSeconds)
        assertEquals(true, linked.timeoutDisabled)
    }

    @Test
    fun `transcript rows carry the links and timeout of history-flattened rows`() {
        // Ticket 098 regression: the transcript-turn flatten derived nothing, so every
        // transcript-backed row (the sub-agent page's whole committed history) lost
        // its job/sub-agent links and its timeout budget.
        val bashOutput = "task_id: bash-2q5gu61e\npid: 502439\nstatus: running\n"
        val agentOutput = "agent_id: agent-13\nstatus: completed\n"
        val timeline = testChatTimeline(historyBacked = false)

        timeline.replaceTranscript(
            listOf(
                transcriptTurn(
                    "t0", 0,
                    transcriptStep(
                        "t0.1",
                        TranscriptFrame.Tool(
                            "t0.1.tool_1", "tool_1", "Bash", running = false,
                            preview = "tick counter loop (120)",
                            input = """{"command":"for i in …","timeout":30}""",
                            output = bashOutput,
                        ),
                        TranscriptFrame.Tool(
                            "t0.1.tool_2", "tool_2", "Agent", running = false,
                            preview = "doc-verify",
                            input = """{"prompt":"check the docs"}""",
                            output = agentOutput,
                        ),
                    ),
                ),
            ),
            keepLive = false,
        )

        assertEquals(
            listOf(
                ChatItem.ToolActivity(
                    "tool_1", "Bash", ToolStatus.Done, "tick counter loop (120)",
                    input = """{"command":"for i in …","timeout":30}""",
                    output = bashOutput,
                    bashTaskId = "bash-2q5gu61e",
                    timeoutSeconds = 30,
                ),
                ChatItem.ToolActivity(
                    "tool_2", "Agent", ToolStatus.Done, "doc-verify",
                    input = """{"prompt":"check the docs"}""",
                    output = agentOutput,
                    subagentId = "agent-13",
                ),
            ),
            timeline.items().filterIsInstance<ChatItem.ToolGroup>().flatMap { it.tools },
        )
    }

    @Test
    fun `a prepended transcript page carries the links too`() {
        // Pagination (046) flattens through the same turn path — an older page's
        // background bash rows stay linked as well.
        val bashOutput = "task_id: bash-2q5gu61e\npid: 502439\nstatus: running\n"
        val timeline = testChatTimeline(historyBacked = false)
        timeline.replaceTranscript(
            listOf(
                transcriptTurn(
                    "t1", 1,
                    transcriptStep("t1.1", TranscriptFrame.Text("t1.1.f1", TranscriptFrame.Text.Role.Assistant, "newer")),
                ),
            ),
            keepLive = false,
        )

        timeline.prependTranscript(
            listOf(
                transcriptTurn(
                    "t0", 0,
                    transcriptStep(
                        "t0.1",
                        TranscriptFrame.Tool(
                            "t0.1.tool_1", "tool_1", "Bash", running = false,
                            preview = "tick counter loop (120)", input = "", output = bashOutput,
                        ),
                    ),
                ),
            ),
        )

        val tool = timeline.items().filterIsInstance<ChatItem.ToolGroup>().flatMap { it.tools }.single()
        assertEquals("bash-2q5gu61e", tool.bashTaskId)
    }

    @Test
    fun `a live result-linked row keeps its bashTaskId across the terminal transcript reconcile`() {
        // Ticket 098: the sub-agent page's terminal reconcile (SubagentViewModel's
        // keepLive = false refetch) drops the live rows — the link survives only if
        // the transcript flatten re-derives it from the committed stub output.
        val output = "task_id: bash-2q5gu61e\npid: 502439\nstatus: running\n"
        val timeline = testChatTimeline(historyBacked = false)
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "tick counter loop (120)"))
        timeline.apply(SessionEvent.ToolCallResult("tool_1", false, output))
        assertEquals(
            "bash-2q5gu61e",
            (timeline.items().single() as ChatItem.ToolGroup).tools.single().bashTaskId,
        )

        timeline.replaceTranscript(
            listOf(
                transcriptTurn(
                    "t0", 0,
                    transcriptStep(
                        "t0.1",
                        TranscriptFrame.Tool(
                            "t0.1.tool_1", "tool_1", "Bash", running = false,
                            preview = "tick counter loop (120)", input = "", output = output,
                        ),
                    ),
                ),
            ),
            keepLive = false,
        )

        assertEquals(
            "bash-2q5gu61e",
            (timeline.items().single() as ChatItem.ToolGroup).tools.single().bashTaskId,
        )
    }

    @Test
    fun `tool call started fills the running row preview live`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())

        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "echo doc-verify"))

        assertEquals(
            listOf(toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Running, "echo doc-verify"))),
            timeline.items(),
        )
    }

    @Test
    fun `a live stream appends new calls to the open group until a bubble splits it`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())

        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash"))
        timeline.apply(SessionEvent.ToolCallStarted("tool_2", "Read"))
        timeline.apply(SessionEvent.ToolCallResult("tool_1", false))
        // Text after the run starts a new bubble, so the next call opens a new group.
        timeline.apply(SessionEvent.AssistantDelta("one more"))
        timeline.apply(SessionEvent.ToolCallStarted("tool_3", "Bash"))

        assertEquals(
            listOf(
                ChatItem.ToolGroup(
                    "tool_1",
                    listOf(
                        ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done),
                        ChatItem.ToolActivity("tool_2", "Read", ToolStatus.Running),
                    ),
                ),
                ChatItem.AssistantMessage("live_0", "one more", ""),
                toolGroup(ChatItem.ToolActivity("tool_3", "Bash", ToolStatus.Running)),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `a row started without arguments picks up the later preview and keeps it on result`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        // The fold's contract (ticket 175): a running tool frame first renders with no
        // arguments yet, then again once its input is known.
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash"))

        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "echo hi"))
        timeline.apply(SessionEvent.ToolCallResult("tool_1", false))

        assertEquals(
            listOf(toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, "echo hi"))),
            timeline.items(),
        )
    }

    @Test
    fun `a live tool result fills the output of its running row`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "./gradlew test", input = "{\n    \"command\": \"./gradlew test\"\n}"))

        timeline.apply(SessionEvent.ToolCallResult("tool_1", true, "FAIL 3 tests\n"))

        assertEquals(
            listOf(
                toolGroup(
                    ChatItem.ToolActivity(
                        "tool_1",
                        "Bash",
                        ToolStatus.Error,
                        "./gradlew test",
                        input = "{\n    \"command\": \"./gradlew test\"\n}",
                        output = "FAIL 3 tests\n",
                    ),
                ),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `mid-turn refetch replaces a live row the server has committed`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "build"))

        // The server has since committed the finished call: the refetched history is
        // canonical and the running live row is dropped.
        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("tool_1", "Bash", "build")),
                ),
                ChatMessage(
                    "msg_2",
                    ChatMessage.Role.Tool,
                    listOf(ChatMessage.Part.ToolResult("tool_1", false, "BUILD OK\n")),
                ),
            ),
            keepLive = true,
        )

        assertEquals(
            listOf(toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, "build", output = "BUILD OK\n"))),
            timeline.items(),
        )
    }

    @Test
    fun `mid-turn refetch keeps the uncommitted tail of a live group`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash"))
        timeline.apply(SessionEvent.ToolCallStarted("tool_2", "Read"))

        // The refetch landed mid-run: tool_1 is committed server-side, tool_2 is not.
        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("tool_1", "Bash")),
                ),
                ChatMessage("msg_2", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", false))),
            ),
            keepLive = true,
        )

        assertEquals(
            listOf(
                toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done)),
                toolGroup(ChatItem.ToolActivity("tool_2", "Read", ToolStatus.Running)),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `deltas build the live bubble and tool rows update in place`() {
        val timeline = testChatTimeline()

        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.ThinkingDelta("thi"))
        timeline.apply(SessionEvent.ThinkingDelta("nking"))
        timeline.apply(SessionEvent.AssistantDelta("hel"))
        timeline.apply(SessionEvent.AssistantDelta("lo"))
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash"))
        timeline.apply(SessionEvent.ToolCallResult("tool_1", false))
        // Text after a tool row starts a new bubble, matching the web client's steps.
        timeline.apply(SessionEvent.AssistantDelta("done"))

        assertEquals(
            listOf(
                ChatItem.AssistantMessage("live_0", "hello", "thinking"),
                toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done)),
                ChatItem.AssistantMessage("live_1", "done", ""),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `replaceHistory without keepLive clears live rows`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.AssistantDelta("streamed"))

        timeline.replaceHistory(
            listOf(ChatMessage("msg_1", ChatMessage.Role.Assistant, listOf(ChatMessage.Part.Text("streamed")))),
            keepLive = false,
        )

        assertEquals(
            listOf(ChatItem.AssistantMessage("msg_1", "streamed", "")),
            timeline.items(),
        )
    }

    @Test
    fun `mid-turn refetch drops live rows the server has committed`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.AssistantDelta("hello"))
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash"))

        timeline.replaceHistory(
            listOf(
                userMessage("msg_1", "hi"),
                ChatMessage(
                    "msg_2",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.Text("hello"), ChatMessage.Part.ToolUse("tool_1", "Bash")),
                ),
                ChatMessage("msg_3", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", false))),
            ),
            keepLive = true,
        )

        // No duplicates: the live bubble and tool row are now committed server-side.
        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "hi"),
                ChatItem.AssistantMessage("msg_2", "hello", ""),
                toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done)),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `mid-turn refetch keeps uncommitted live rows`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.AssistantDelta("partial"))

        timeline.replaceHistory(listOf(userMessage("msg_1", "hi")), keepLive = true)

        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "hi"),
                ChatItem.AssistantMessage("live_0", "partial", ""),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `context spliced commits the sent user message mid-turn`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())

        timeline.apply(SessionEvent.ContextSpliced(listOf(userMessage("msg_1", "hello from the phone"))))

        assertEquals(
            listOf(ChatItem.UserMessage("msg_1", "hello from the phone")),
            timeline.items(),
        )
    }

    @Test
    fun `context spliced is deduplicated by message id`() {
        val timeline = testChatTimeline()
        timeline.replaceHistory(listOf(userMessage("msg_1", "hi")), keepLive = false)

        // Reconnect replay of the same splice, then a new one.
        timeline.apply(SessionEvent.ContextSpliced(listOf(userMessage("msg_1", "hi"))))
        timeline.apply(SessionEvent.ContextSpliced(listOf(userMessage("msg_2", "second"))))

        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "hi"),
                ChatItem.UserMessage("msg_2", "second"),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `a splice that beats the prompt ACK leaves exactly one bubble`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())

        // Server 2.1.x ordering: the socket's context.spliced can land before the POST
        // ACK's continuation — the echo must adopt the spliced row, not twin it
        // (Louis's duplicate-key crash, 2026-09-25).
        timeline.apply(SessionEvent.ContextSpliced(listOf(userMessage("msg_1", "hello"))))
        timeline.appendLocalEcho("msg_1", "hello", emptyList(), emptyList())

        assertEquals(
            listOf(ChatItem.UserMessage("msg_1", "hello")),
            timeline.items(),
        )
    }

    @Test
    fun `a splice-adopted echo still takes the queued mark`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())

        timeline.apply(SessionEvent.ContextSpliced(listOf(userMessage("msg_1", "hello"))))
        timeline.appendLocalEcho("msg_1", "hello", emptyList(), emptyList())
        timeline.apply(SessionEvent.PromptUpdated("msg_1", PromptStatus.Queued, queueLength = 2))

        assertEquals(
            listOf(ChatItem.UserMessage("msg_1", "hello", queued = 2)),
            timeline.items(),
        )
    }

    @Test
    fun `context spliced ignores non-user messages`() {
        val timeline = testChatTimeline()

        timeline.apply(
            SessionEvent.ContextSpliced(
                listOf(
                    ChatMessage("msg_1", ChatMessage.Role.Assistant, listOf(ChatMessage.Part.Text("reply"))),
                    ChatMessage(
                        "msg_2",
                        ChatMessage.Role.Tool,
                        listOf(ChatMessage.Part.ToolResult("tool_1", false)),
                    ),
                ),
            ),
        )

        assertEquals(emptyList<ChatItem>(), timeline.items())
    }

    @Test
    fun `id-less spliced steer echo commits once, deduped on the text`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        // The steer echo splices the steered message without an id (docs/server-api.md).
        val steered = ChatMessage("", ChatMessage.Role.User, listOf(ChatMessage.Part.Text("stop counting")))

        timeline.apply(SessionEvent.ContextSpliced(listOf(steered)))
        // A replay of the same splice must not double the bubble.
        timeline.apply(SessionEvent.ContextSpliced(listOf(steered)))

        assertEquals(1, timeline.items().size)
        assertEquals("stop counting", (timeline.items().single() as ChatItem.UserMessage).text)
    }

    @Test
    fun `id-less splices get distinct synthetic keys`() {
        val timeline = testChatTimeline()

        timeline.apply(
            SessionEvent.ContextSpliced(
                listOf(ChatMessage("", ChatMessage.Role.User, listOf(ChatMessage.Part.Text("first")))),
            ),
        )
        timeline.apply(
            SessionEvent.ContextSpliced(
                listOf(ChatMessage("", ChatMessage.Role.User, listOf(ChatMessage.Part.Text("second")))),
            ),
        )

        val items = timeline.items()
        // Two id-less messages must not collide as LazyColumn keys.
        assertEquals(2, items.size)
        assertEquals(2, items.map { it.id }.distinct().size)
    }

    @Test
    fun `id-less splice already committed by a racing refetch is dropped`() {
        val timeline = testChatTimeline()
        timeline.replaceHistory(listOf(userMessage("msg_9", "stop counting")), keepLive = true)

        timeline.apply(
            SessionEvent.ContextSpliced(
                listOf(ChatMessage("", ChatMessage.Role.User, listOf(ChatMessage.Part.Text("stop counting")))),
            ),
        )

        assertEquals(listOf(ChatItem.UserMessage("msg_9", "stop counting")), timeline.items())
    }

    // Ticket 042: TodoList tool calls feed the composer widget instead of the timeline.

    private val todoInput = """{"todos": [{"title": "one", "status": "done"}, {"title": "two", "status": "pending"}]}"""
    private val expectedTodos = listOf(Todo("one", Todo.Status.Done), Todo("two", Todo.Status.Pending))

    @Test
    fun `a history todo call renders no rows and derives the widget state`() {
        val timeline = testChatTimeline()

        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(
                        ChatMessage.Part.Text("planning"),
                        ChatMessage.Part.ToolUse("tool_1", "TodoList", input = todoInput),
                    ),
                ),
                // The result must not surface as an orphan row either.
                ChatMessage("msg_2", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", false))),
            ),
            keepLive = false,
        )

        assertEquals(listOf(ChatItem.AssistantMessage("msg_1", "planning", "")), timeline.items())
        assertEquals(expectedTodos, timeline.todos())
    }

    @Test
    fun `a live todo call renders no rows and derives the widget state`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())

        // The running frame renders without arguments first; the input arrives later.
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "TodoList"))
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "TodoList", input = todoInput))
        timeline.apply(SessionEvent.ToolCallResult("tool_1", false))

        assertEquals(emptyList<ChatItem>(), timeline.items())
        assertEquals(expectedTodos, timeline.todos())
    }

    @Test
    fun `the latest todo call wins across history and live events`() {
        val timeline = testChatTimeline()
        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("tool_1", "TodoList", input = todoInput)),
                ),
            ),
            keepLive = false,
        )
        timeline.apply(SessionEvent.TurnStarted())

        timeline.apply(
            SessionEvent.ToolCallStarted(
                "tool_2",
                "TodoList",
                input = """{"todos": [{"title": "new", "status": "in_progress"}]}""",
            ),
        )

        assertEquals(listOf(Todo("new", Todo.Status.InProgress)), timeline.todos())
    }

    @Test
    fun `an empty todos array hides the widget again`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "TodoList", input = todoInput))

        timeline.apply(SessionEvent.ToolCallStarted("tool_2", "TodoList", input = """{"todos": []}"""))

        assertEquals(emptyList<Todo>(), timeline.todos())
    }

    @Test
    fun `a mid-turn refetch keeps a live todo state the server has not committed yet`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "TodoList", input = todoInput))

        // The refetch predates the call: the live state is newer and must survive.
        timeline.replaceHistory(listOf(userMessage("msg_1", "hi")), keepLive = true)

        assertEquals(expectedTodos, timeline.todos())
    }

    @Test
    fun `the turn-end refetch re-derives the widget state from the canonical history`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "TodoList", input = todoInput))

        // History without any todo call: the live leftover is gone.
        timeline.replaceHistory(listOf(userMessage("msg_1", "hi")), keepLive = false)
        assertEquals(null, timeline.todos())

        // History carrying the call (now committed): same state, derived from history.
        timeline.apply(SessionEvent.TurnStarted())
        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_2",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("tool_1", "TodoList", input = todoInput)),
                ),
            ),
            keepLive = false,
        )
        assertEquals(expectedTodos, timeline.todos())
    }

    @Test
    fun `an Agent tool row gains its subagentId from the live spawned linkage`() {
        // subagent.spawned names the Agent call it answers (parentToolCallId); the
        // ChatViewModel forwards the link here (ticket 045).
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Agent", "doc-verify"))

        timeline.linkSubagentTool("tool_1", "agent-12")

        assertEquals(
            listOf(toolGroup(ChatItem.ToolActivity("tool_1", "Agent", ToolStatus.Running, "doc-verify", subagentId = "agent-12"))),
            timeline.items(),
        )
    }

    @Test
    fun `the live spawned linkage also reaches an already-committed Agent row`() {
        // A mid-turn refetch may commit the running Agent call before spawned arrives.
        val timeline = testChatTimeline()
        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("tool_1", "Agent", "doc-verify")),
                ),
            ),
            keepLive = false,
        )

        timeline.linkSubagentTool("tool_1", "agent-12")

        assertEquals("agent-12", (timeline.items().single() as ChatItem.ToolGroup).tools.single().subagentId)
    }

    @Test
    fun `a committed Agent row gains its subagentId from the agent_id result line`() {
        // Cold linkage (ticket 045): the spawn predates the subscription; the Agent
        // result's `agent_id:` marker line is the only link left (version-pinned wire
        // format — first line foreground, after the task fields background).
        val timeline = testChatTimeline()
        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(
                        ChatMessage.Part.ToolUse("tool_1", "Agent", "fg"),
                        ChatMessage.Part.ToolUse("tool_2", "Agent", "bg"),
                    ),
                ),
                ChatMessage(
                    "msg_2",
                    ChatMessage.Role.Tool,
                    listOf(
                        ChatMessage.Part.ToolResult("tool_1", false, "agent_id: agent-12\nstatus: completed\n\n[summary]\nok"),
                        ChatMessage.Part.ToolResult("tool_2", false, "task_id: agent-7noib8bb\nstatus: running\nagent_id: agent-13"),
                    ),
                ),
            ),
            keepLive = false,
        )

        val tools = timeline.items().flatMap { (it as ChatItem.ToolGroup).tools }
        assertEquals("agent-12", tools.first { it.id == "tool_1" }.subagentId)
        assertEquals("agent-13", tools.first { it.id == "tool_2" }.subagentId)
    }

    @Test
    fun `a live Agent result links the row mid-run`() {
        // A background sub-agent's result returns immediately (status: running) while
        // its stream continues: the row must become tappable from the live result too.
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Agent", "bg"))
        timeline.apply(SessionEvent.ToolCallResult("tool_1", false, "task_id: agent-7noib8bb\nstatus: running\nagent_id: agent-13"))

        assertEquals(
            "agent-13",
            (timeline.items().single() as ChatItem.ToolGroup).tools.single().subagentId,
        )
    }

    @Test
    fun `the live spawned linkage survives a mid-turn refetch committing the Agent call`() {
        // A reconnect refetches mid-run: history now commits the Agent call (without
        // its result — still running), and the linked live row is dropped as a
        // duplicate. The link must be re-applied from reducer state.
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Agent", "doc-verify"))
        timeline.linkSubagentTool("tool_1", "agent-12")

        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("tool_1", "Agent", "doc-verify")),
                ),
            ),
            keepLive = true,
        )

        assertEquals(
            "agent-12",
            (timeline.items().single() as ChatItem.ToolGroup).tools.single().subagentId,
        )
    }

    @Test
    fun `a result without the agent_id marker leaves the row unlinked`() {
        val timeline = testChatTimeline()
        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(
                        ChatMessage.Part.ToolUse("tool_1", "Agent", "old format"),
                        // Not an Agent call: a shell output mentioning the marker must
                        // not turn into a link either.
                        ChatMessage.Part.ToolUse("tool_2", "Bash", "echo agent_id: agent-9"),
                    ),
                ),
                ChatMessage(
                    "msg_2",
                    ChatMessage.Role.Tool,
                    listOf(
                        ChatMessage.Part.ToolResult("tool_1", false, "status: completed\n\n[summary]\nok"),
                        ChatMessage.Part.ToolResult("tool_2", false, "agent_id: agent-9\n"),
                    ),
                ),
            ),
            keepLive = false,
        )

        val tools = timeline.items().flatMap { (it as ChatItem.ToolGroup).tools }
        assertEquals(null, tools.first { it.id == "tool_1" }.subagentId)
        assertEquals(null, tools.first { it.id == "tool_2" }.subagentId)
    }

    // --- History pagination (ticket 043) --------------------------------------------

    @Test
    fun `an older history page prepends deduplicated by message id`() {
        val timeline = testChatTimeline()
        timeline.replaceHistory(
            listOf(userMessage("msg_2", "second"), userMessage("msg_3", "third")),
            keepLive = false,
        )

        // The older page overlaps by one id (a racing refetch can never make it apply
        // twice) and carries a blank-id row: the duplicate is dropped, the id-less row
        // is kept — splice echoes carry no id and never come from history pages.
        timeline.prependHistory(
            listOf(
                userMessage("msg_0", "zeroth"),
                userMessage("", "id-less"),
                userMessage("msg_2", "second"),
            ),
        )

        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_0", "zeroth"),
                ChatItem.UserMessage("", "id-less"),
                ChatItem.UserMessage("msg_2", "second"),
                ChatItem.UserMessage("msg_3", "third"),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `prependHistory leaves the live rows and the todo widget untouched`() {
        val timeline = testChatTimeline()
        timeline.replaceHistory(listOf(userMessage("msg_2", "second")), keepLive = true)
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.AssistantDelta("streaming"))

        timeline.prependHistory(listOf(userMessage("msg_1", "first")))

        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "first"),
                ChatItem.UserMessage("msg_2", "second"),
                ChatItem.AssistantMessage("live_0", "streaming", thinking = ""),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `a full refetch after a prepend resets the dedup to the newest page`() {
        val timeline = testChatTimeline()
        timeline.replaceHistory(listOf(userMessage("msg_2", "second")), keepLive = false)
        timeline.prependHistory(listOf(userMessage("msg_1", "first")))

        // The refetch is canonical: previously prepended rows are dropped, and a page
        // overlapping the OLD loaded range dedups against the fresh page only.
        timeline.replaceHistory(listOf(userMessage("msg_3", "third")), keepLive = false)
        timeline.prependHistory(listOf(userMessage("msg_2", "second"), userMessage("msg_3", "third")))

        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_2", "second"),
                ChatItem.UserMessage("msg_3", "third"),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `an older page completing a boundary-split tool pair merges the orphan result`() {
        val timeline = testChatTimeline()
        // The newer page starts with an orphan result (its call is in the older page):
        // it renders as a nameless row ("result without a matching call: show it anyway").
        timeline.replaceHistory(
            listOf(
                ChatMessage("msg_2", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", false, "ok\n"))),
            ),
            keepLive = false,
        )

        // The older page brings the call: it must COMPLETE the orphan in place — a
        // second row with the same tool-call id is a duplicate LazyColumn key (the
        // 047 crash), and moving the orphan into the head page would lose the scroll
        // anchor's key (the "Sub-agents documentation" jump).
        timeline.prependHistory(
            listOf(
                ChatMessage("msg_1", ChatMessage.Role.Assistant, listOf(ChatMessage.Part.ToolUse("tool_1", "Bash", "echo hi"))),
            ),
        )

        assertEquals(
            listOf(
                ChatItem.ToolGroup(
                    ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, "echo hi", output = "ok\n"),
                ),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `an older page completing one row of a committed tool group keeps the group key and survivors in place`() {
        val timeline = testChatTimeline()
        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_3",
                    ChatMessage.Role.Tool,
                    listOf(
                        ChatMessage.Part.ToolResult("tool_1", false, "one"),
                        ChatMessage.Part.ToolResult("tool_2", false, "two"),
                    ),
                ),
            ),
            keepLive = false,
        )

        timeline.prependHistory(
            listOf(ChatMessage("msg_1", ChatMessage.Role.Assistant, listOf(ChatMessage.Part.ToolUse("tool_1", "Bash", "one")))),
        )

        // The completed row keeps the orphan's key, position and group — LazyColumn
        // anchors the scroll position on the first visible row's key across the
        // prepend, so the group must not be re-keyed or split.
        assertEquals(
            listOf(
                ChatItem.ToolGroup(
                    "tool_1",
                    listOf(
                        ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, "one", output = "one"),
                        ChatItem.ToolActivity("tool_2", "", ToolStatus.Done, output = "two"),
                    ),
                ),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `a boundary-split pair trailing another call keeps the orphan row anchored at the page top`() {
        val timeline = testChatTimeline()
        // The newer page starts with the orphan result of tool_2 (its call is in the
        // older page) — the exact "Sub-agents documentation" page boundary.
        timeline.replaceHistory(
            listOf(
                ChatMessage("msg_3", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_2", false, "two"))),
                userMessage("msg_4", "later"),
            ),
            keepLive = false,
        )

        // The older page ends with consecutive calls tool_1, tool_2: they flatten to
        // one group keyed tool_1, so completing the pair by moving the orphan would
        // delete the anchor key tool_2 — the scroll jumped to the new page's top.
        timeline.prependHistory(
            listOf(
                ChatMessage("msg_1", ChatMessage.Role.Assistant, listOf(ChatMessage.Part.ToolUse("tool_1", "Bash", "one"))),
                ChatMessage("msg_2", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", false, "one out"))),
                ChatMessage("msg_2b", ChatMessage.Role.Assistant, listOf(ChatMessage.Part.ToolUse("tool_2", "Write", "two"))),
            ),
        )

        // The orphan row is completed in place (key tool_2, still the first row of the
        // page); the head group keeps its surviving call under its own key.
        assertEquals(
            listOf(
                ChatItem.ToolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, "one", output = "one out")),
                ChatItem.ToolGroup("tool_2", listOf(ChatItem.ToolActivity("tool_2", "Write", ToolStatus.Done, "two", output = "two"))),
                ChatItem.UserMessage("msg_4", "later"),
            ),
            timeline.items(),
        )
        // No duplicate LazyColumn keys (the 047 crash).
        assertEquals(timeline.items().size, timeline.items().map { it.id }.distinct().size)
    }

    // --- Transcript-backed sub-agent history (ticket 046) ----------------------------

    private fun transcriptTurn(id: String, ordinal: Int, vararg steps: TranscriptStep) =
        TranscriptTurn(id, ordinal, TranscriptState.Completed, prompt = null, steps = steps.toList())

    private fun transcriptStep(id: String, vararg frames: TranscriptFrame) =
        TranscriptStep(id, TranscriptState.Completed, frames.toList())

    @Test
    fun `a transcript page flattens into prompt, bubbles and tool rows`() {
        val timeline = testChatTimeline(historyBacked = false)

        timeline.replaceTranscript(
            listOf(
                TranscriptTurn(
                    "t0", 0, TranscriptState.Completed, prompt = "list /tmp/spike",
                    steps = listOf(
                        transcriptStep(
                            "t0.1",
                            TranscriptFrame.Thinking("t0.1.f1", "Plan it."),
                            TranscriptFrame.Tool(
                                "t0.1.tool_1", "tool_1", "Bash", running = false,
                                preview = "ls /tmp/spike", input = "{\n    \"command\": \"ls /tmp/spike\"\n}", output = "total 0\n",
                            ),
                        ),
                        transcriptStep(
                            "t0.2",
                            TranscriptFrame.Thinking("t0.2.f1", "Empty."),
                            TranscriptFrame.Text("t0.2.f2", TranscriptFrame.Text.Role.Assistant, "No files."),
                            TranscriptFrame.Text("t0.2.f3", TranscriptFrame.Text.Role.Assistant, "Nothing to do."),
                        ),
                    ),
                ),
            ),
            keepLive = false,
        )

        assertEquals(
            listOf(
                ChatItem.UserMessage("t0.prompt", "list /tmp/spike"),
                ChatItem.AssistantMessage("t0.1.f1", "", thinking = "Plan it."),
                toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, "ls /tmp/spike", "{\n    \"command\": \"ls /tmp/spike\"\n}", "total 0\n")),
                // Consecutive text frames join into one bubble (the message-history
                // "\n" convention); the step boundary broke the thinking-only bubble.
                ChatItem.AssistantMessage("t0.2.f1", "No files.\nNothing to do.", thinking = "Empty."),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `a mid-run transcript page keeps the uncommitted live tail without duplicates`() {
        val timeline = testChatTimeline(historyBacked = false)
        // The live tail: a finished tool call, a running one, and fresh text.
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "ls"))
        timeline.apply(SessionEvent.ToolCallResult("tool_1", false, "total 0\n"))
        timeline.apply(SessionEvent.ToolCallStarted("tool_2", "Bash", "pwd"))
        timeline.apply(SessionEvent.AssistantDelta("Working on it"))

        timeline.replaceTranscript(
            listOf(
                transcriptTurn(
                    "t0", 0,
                    transcriptStep(
                        "t0.1",
                        TranscriptFrame.Tool(
                            "t0.1.tool_1", "tool_1", "Bash", running = false,
                            preview = "ls", input = "", output = "total 0\n",
                        ),
                    ),
                ),
            ),
            keepLive = true,
        )

        assertEquals(
            listOf(
                toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, "ls", "", "total 0\n")),
                // tool_1 dropped as committed; tool_2 survives on its own group.
                toolGroup(ChatItem.ToolActivity("tool_2", "Bash", ToolStatus.Running, "pwd")),
                ChatItem.AssistantMessage("live_0", "Working on it", thinking = ""),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `an older transcript page prepends deduplicated by turn id`() {
        val timeline = testChatTimeline(historyBacked = false)
        timeline.replaceTranscript(
            listOf(
                transcriptTurn(
                    "t2", 2,
                    transcriptStep("t2.1", TranscriptFrame.Text("t2.1.f1", TranscriptFrame.Text.Role.Assistant, "newest")),
                ),
            ),
            keepLive = false,
        )

        timeline.prependTranscript(
            listOf(
                transcriptTurn(
                    "t0", 0,
                    transcriptStep("t0.1", TranscriptFrame.Text("t0.1.f1", TranscriptFrame.Text.Role.Assistant, "oldest")),
                ),
                transcriptTurn(
                    "t1", 1,
                    transcriptStep("t1.1", TranscriptFrame.Text("t1.1.f1", TranscriptFrame.Text.Role.Assistant, "middle")),
                ),
            ),
        )
        // A racing refetch of the same page must not add a row twice.
        timeline.prependTranscript(
            listOf(
                transcriptTurn(
                    "t1", 1,
                    transcriptStep("t1.1", TranscriptFrame.Text("t1.1.f1", TranscriptFrame.Text.Role.Assistant, "middle")),
                ),
            ),
        )

        assertEquals(
            listOf("oldest", "middle", "newest"),
            timeline.items().map { (it as ChatItem.AssistantMessage).text },
        )
    }

    @Test
    fun `a fresh transcript page resets pagination and clears the live tail on completion`() {
        val timeline = testChatTimeline(historyBacked = false)
        timeline.prependTranscript(
            listOf(
                transcriptTurn(
                    "t0", 0,
                    transcriptStep("t0.1", TranscriptFrame.Text("t0.1.f1", TranscriptFrame.Text.Role.Assistant, "old page")),
                ),
            ),
        )
        timeline.apply(SessionEvent.AssistantDelta("live text"))

        // The completion reconcile (keepLive = false): the live tail goes, history is
        // canonical, and the prepended older page is dropped back to the newest page.
        timeline.replaceTranscript(
            listOf(
                transcriptTurn(
                    "t1", 1,
                    transcriptStep("t1.1", TranscriptFrame.Text("t1.1.f1", TranscriptFrame.Text.Role.Assistant, "final")),
                ),
            ),
            keepLive = false,
        )

        assertEquals(
            listOf(ChatItem.AssistantMessage("t1.1.f1", "final", thinking = "")),
            timeline.items(),
        )
    }

    // --- Single key space (ticket 055) -------------------------------------------------

    @Test
    fun `a replayed tool event after a keep-live refetch heals the committed row`() {
        // The 2026-07-30 crash: on reconnect the refetch commits the in-flight call,
        // then the cursor replay re-delivers its events. The replay must heal the
        // committed row in place — a second row with the same id is a duplicate
        // LazyColumn key.
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "AskUserQuestion", "approve?"))

        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("tool_1", "AskUserQuestion", "approve?")),
                ),
            ),
            keepLive = true,
        )
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "AskUserQuestion", "approve?"))
        timeline.apply(SessionEvent.ToolCallResult("tool_1", false, "answered"))

        assertEquals(
            listOf(
                toolGroup(
                    ChatItem.ToolActivity("tool_1", "AskUserQuestion", ToolStatus.Done, "approve?", output = "answered"),
                ),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `a late tool event heals the committed row in place, preserving its position`() {
        val timeline = testChatTimeline()
        timeline.replaceHistory(
            listOf(
                ChatMessage("msg_1", ChatMessage.Role.Assistant, listOf(ChatMessage.Part.ToolUse("tool_1", "Bash", "one"))),
                ChatMessage("msg_2", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", false, "done\n"))),
                userMessage("msg_3", "next"),
            ),
            keepLive = false,
        )

        // A replayed started/result pair for the already-committed call updates that
        // row's fields but must not move or duplicate it.
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "one"))
        timeline.apply(SessionEvent.ToolCallResult("tool_1", false, "done\n"))

        assertEquals(
            listOf(
                toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, "one", output = "done\n")),
                ChatItem.UserMessage("msg_3", "next"),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `items never contains duplicate row ids across refetch and replay interleavings`() {
        val historyWithCall = listOf(
            ChatMessage("msg_1", ChatMessage.Role.Assistant, listOf(ChatMessage.Part.ToolUse("tool_1", "Bash", "run"))),
        )
        val historyWithResult = historyWithCall +
            ChatMessage("msg_2", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", false, "out\n")))
        val started = SessionEvent.ToolCallStarted("tool_1", "Bash", "run")
        val result = SessionEvent.ToolCallResult("tool_1", false, "out\n")

        // Every merge path that used to dedup two parallel lists against each other by
        // hand: mid-turn refetch (keepLive) vs replay, turn-end refetch (live cleared)
        // vs a late event, prepend vs a live event, turn sealing vs a late event,
        // transcript refetch vs replay.
        val scripts: List<Triple<Boolean, String, ChatTimeline.() -> Unit>> = listOf(
            Triple(
                true,
                "mid-turn refetch then replay",
                {
                    apply(SessionEvent.TurnStarted())
                    apply(started)
                    replaceHistory(historyWithCall, keepLive = true)
                    apply(started)
                    apply(result)
                },
            ),
            Triple(
                true,
                "turn-end refetch then late result",
                {
                    apply(SessionEvent.TurnStarted())
                    apply(started)
                    replaceHistory(historyWithResult, keepLive = false)
                    apply(result)
                },
            ),
            Triple(
                true,
                "prepend then live result",
                {
                    replaceHistory(listOf(userMessage("msg_9", "tail")), keepLive = false)
                    prependHistory(historyWithCall)
                    apply(result)
                },
            ),
            Triple(
                false,
                "sealed turn then late result",
                {
                    apply(SessionEvent.TurnStarted())
                    apply(started)
                    apply(SessionEvent.TurnStarted())
                    apply(result)
                },
            ),
            Triple(
                false,
                "transcript refetch then replay",
                {
                    apply(started)
                    replaceTranscript(
                        listOf(
                            transcriptTurn(
                                "t0", 0,
                                transcriptStep(
                                    "t0.1",
                                    TranscriptFrame.Tool(
                                        "t0.1.tool_1", "tool_1", "Bash", running = false,
                                        preview = "run", input = "", output = "out\n",
                                    ),
                                ),
                            ),
                        ),
                        keepLive = true,
                    )
                    apply(started)
                    apply(result)
                },
            ),
        )

        scripts.forEach { (historyBacked, name, script) ->
            val timeline = testChatTimeline(historyBacked = historyBacked)
            timeline.script()
            val ids = timeline.items().map { it.id }
            assertEquals("$name: duplicate row ids: $ids", ids.distinct().size, ids.size)
        }
    }

    // --- Compaction dividers (ticket 056) ---------------------------------------------

    private fun compactionSummaryMessage(id: String, summary: String) =
        ChatMessage(
            id,
            ChatMessage.Role.User,
            listOf(ChatMessage.Part.Text(summary)),
            originKind = ORIGIN_KIND_COMPACTION_SUMMARY,
        )

    @Test
    fun `a compaction_summary history message renders as a done divider, never a bubble`() {
        val timeline = testChatTimeline()

        timeline.replaceHistory(
            listOf(
                userMessage("msg_1", "run echo hi"),
                compactionSummaryMessage("msg_2", "Working summary of the task."),
            ),
            keepLive = false,
        )

        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "run echo hi"),
                // History-sourced: the bare summary, no token numbers (not persisted).
                ChatItem.CompactionDivider("msg_2", pending = false, summary = "Working summary of the task."),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `an older history page prepends its compaction divider, deduplicated by id`() {
        val timeline = testChatTimeline()
        timeline.replaceHistory(listOf(compactionSummaryMessage("msg_2", "summary")), keepLive = false)

        // A racing refetch already committed msg_1: the page applies once (ticket 043).
        timeline.prependHistory(listOf(userMessage("msg_1", "older"), compactionSummaryMessage("msg_2", "summary")))
        timeline.prependHistory(listOf(userMessage("msg_1", "older")))

        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "older"),
                ChatItem.CompactionDivider("msg_2", pending = false, summary = "summary"),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `compaction started, completed and failed drive the live divider`() {
        val timeline = testChatTimeline()
        timeline.replaceHistory(listOf(userMessage("msg_1", "hi")), keepLive = false)

        timeline.compactionStarted()
        // Idempotent: a cursor replay of the started frame stacks no second row.
        timeline.compactionStarted()
        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "hi"),
                ChatItem.CompactionDivider(LIVE_COMPACTION_DIVIDER_ID, pending = true),
            ),
            timeline.items(),
        )

        timeline.compactionCompleted("the summary", tokensBefore = 26_500, tokensAfter = 386)
        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "hi"),
                ChatItem.CompactionDivider(
                    LIVE_COMPACTION_DIVIDER_ID,
                    pending = false,
                    tokensBefore = 26_500,
                    tokensAfter = 386,
                    summary = "the summary",
                ),
            ),
            timeline.items(),
        )

        // A second run resets the done row to pending; when it FAILS the divider is
        // removed (the error snackbar carries the feedback) — the next resync restores
        // the first run's trace from the persisted summary in history.
        timeline.compactionStarted()
        assertEquals(
            ChatItem.CompactionDivider(LIVE_COMPACTION_DIVIDER_ID, pending = true),
            timeline.items().last(),
        )
        timeline.compactionFailed()
        assertEquals(listOf(ChatItem.UserMessage("msg_1", "hi")), timeline.items())
    }

    @Test
    fun `a completion without a pending divider appends the done row`() {
        // The app subscribed mid-run (cold open during a web-triggered compaction):
        // the compaction still gets its timeline trace.
        val timeline = testChatTimeline()
        timeline.replaceHistory(listOf(userMessage("msg_1", "hi")), keepLive = false)

        timeline.compactionCompleted("the summary", tokensBefore = 850, tokensAfter = 275)

        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "hi"),
                ChatItem.CompactionDivider(
                    LIVE_COMPACTION_DIVIDER_ID,
                    pending = false,
                    tokensBefore = 850,
                    tokensAfter = 275,
                    summary = "the summary",
                ),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `a mid-compaction keep-live refetch preserves the pending divider, an idle one replaces it with history`() {
        val timeline = testChatTimeline()
        timeline.replaceHistory(listOf(userMessage("msg_1", "hi")), keepLive = false)
        timeline.compactionStarted()

        // Mid-compaction resync (busy=true → keepLive): history has no summary yet,
        // the pending divider must survive.
        timeline.replaceHistory(listOf(userMessage("msg_1", "hi")), keepLive = true)
        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "hi"),
                ChatItem.CompactionDivider(LIVE_COMPACTION_DIVIDER_ID, pending = true),
            ),
            timeline.items(),
        )

        // Post-completion idle resync (clearLive): the live row goes, the refetched
        // history carries the canonical divider flattened from the persisted summary.
        timeline.replaceHistory(
            listOf(userMessage("msg_1", "hi"), compactionSummaryMessage("msg_2", "the summary")),
            keepLive = false,
        )
        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "hi"),
                ChatItem.CompactionDivider("msg_2", pending = false, summary = "the summary"),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `the spliced compaction summary is dropped and kept messages dedup by id`() {
        val timeline = testChatTimeline()
        timeline.replaceHistory(listOf(userMessage("msg_1", "kept")), keepLive = false)

        timeline.apply(
            SessionEvent.ContextSpliced(
                listOf(
                    // Kept user messages come back verbatim with their original ids.
                    userMessage("msg_1", "kept"),
                    // The summary: id-less, model-facing prefix text — internal content.
                    compactionSummaryMessage("", "The conversation so far has been compacted…"),
                ),
            ),
        )

        // No bubble for the summary, no duplicate of the kept message, no divider either
        // (the live pending divider from compaction.started owns the trace).
        assertEquals(listOf(ChatItem.UserMessage("msg_1", "kept")), timeline.items())
    }

    @Test
    fun `the id-less steer echo still appends after a compaction summary was dropped`() {
        // The summary is id-less too: the steer-echo text dedup must not misfire on it.
        val timeline = testChatTimeline()
        timeline.apply(
            SessionEvent.ContextSpliced(listOf(compactionSummaryMessage("", "prefixed summary text"))),
        )
        timeline.apply(
            SessionEvent.ContextSpliced(listOf(userMessage("", "steered text"))),
        )

        assertEquals(
            listOf(ChatItem.UserMessage("spliced_0", "steered text")),
            timeline.items(),
        )
    }

    // --- Prepend-vs-zombie dedup (Crashlytics 159e25351be7042db517f3af684684db) ---------
    // A late/replayed event for a call whose message is NOT loaded appends a live row
    // (the "show it anyway" orphan path). Paging back then flattens the same call from
    // its own page: two rows with the same LazyColumn key — the 2026-08-01/02 crashes
    // (7 events, `Key "tool_…" was already used`). The prepended page is canonical:
    // the zombie row goes, future events heal the page row in place.

    @Test
    fun `a late result for an unloaded call does not duplicate when its history page prepends`() {
        val timeline = testChatTimeline()
        // The loaded window covers only the newest messages; the long-running call's
        // message sits above them (a sleep 300 started a turn ago).
        timeline.replaceHistory(listOf(userMessage("msg_new", "newer turn")), keepLive = true)
        // The result arrives late (a long-running call, or a reconnect replay): an
        // orphan live row — the call's message is nowhere in the loaded rows.
        timeline.apply(SessionEvent.ToolCallResult("tool_old", false, "slept 300s\n"))
        assertEquals("tool_old", timeline.items().last().id)

        // Scrolling up pages in the call's own message pair.
        timeline.prependHistory(
            listOf(
                ChatMessage(
                    "msg_call",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("tool_old", "Bash", "sleep 300")),
                ),
                ChatMessage(
                    "msg_res",
                    ChatMessage.Role.Tool,
                    listOf(ChatMessage.Part.ToolResult("tool_old", false, "slept 300s\n")),
                ),
            ),
        )

        // One row for the call — the page's — and no duplicate LazyColumn key.
        assertEquals(
            listOf(
                toolGroup(ChatItem.ToolActivity("tool_old", "Bash", ToolStatus.Done, "sleep 300", "", "slept 300s\n")),
                ChatItem.UserMessage("msg_new", "newer turn"),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `a replayed splice older than the loaded page does not duplicate when its page prepends`() {
        val timeline = testChatTimeline()
        timeline.replaceHistory(listOf(userMessage("msg_new", "newer")), keepLive = true)
        // A reconnect replay delivers a steered message older than the loaded window:
        // it commits at the tail, unregistered in committedMessageIds.
        timeline.apply(SessionEvent.ContextSpliced(listOf(userMessage("msg_old", "steered"))))

        // Paging back reaches the page that carries the same message.
        timeline.prependHistory(listOf(userMessage("msg_old", "steered")))

        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_old", "steered"),
                ChatItem.UserMessage("msg_new", "newer"),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `a sealed sub-agent row does not duplicate when its transcript page prepends`() {
        val timeline = testChatTimeline(historyBacked = false)
        // The newest transcript page is loaded; a replayed event for a call of an
        // older, unloaded turn appends a live row, then turn.started seals it.
        timeline.replaceTranscript(
            listOf(
                transcriptTurn(
                    "t5",
                    5,
                    transcriptStep("t5.1", TranscriptFrame.Text("t5.1.f1", TranscriptFrame.Text.Role.Assistant, "newest")),
                ),
            ),
            keepLive = true,
        )
        timeline.apply(SessionEvent.ToolCallStarted("tool_old", "Bash", "sleep 300", agentId = "agent-1"))
        timeline.apply(SessionEvent.TurnStarted(agentId = "agent-1"))

        // Scrolling up pages in the turn that owns the call.
        timeline.prependTranscript(
            listOf(
                transcriptTurn(
                    "t4",
                    4,
                    transcriptStep(
                        "t4.1",
                        TranscriptFrame.Tool(
                            "t4.1.tool_old", "tool_old", "Bash", running = false,
                            preview = "sleep 300", input = "", output = "slept 300s\n",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                toolGroup(ChatItem.ToolActivity("tool_old", "Bash", ToolStatus.Done, "sleep 300", "", "slept 300s\n")),
                ChatItem.AssistantMessage("t5.1.f1", "newest", thinking = ""),
            ),
            timeline.items(),
        )
    }

    // --- Raw args threading (ticket 118) ----------------------------------------------

    @Test
    fun `a history tool row carries the call's args object`() {
        val args = buildJsonObject {
            put("pattern", "*.kt")
            put("path", "/tmp/spike")
        }
        val timeline = testChatTimeline()

        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("tool_1", "Glob", "*.kt", input = "{\n    \"pattern\": \"*.kt\"\n}", args = args)),
                ),
            ),
            keepLive = false,
        )

        val tool = (timeline.items().single() as ChatItem.ToolGroup).tools.single()
        assertEquals(args, tool.args)
    }

    @Test
    fun `a live tool call started carries its args onto the row`() {
        val args = buildJsonObject { put("command", "ls /tmp/spike") }
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())

        timeline.apply(
            SessionEvent.ToolCallStarted(
                "tool_1",
                "Bash",
                "ls /tmp/spike",
                input = "{\n    \"command\": \"ls /tmp/spike\"\n}",
                args = args,
            ),
        )

        val tool = (timeline.items().single() as ChatItem.ToolGroup).tools.single()
        assertEquals(args, tool.args)
    }

    @Test
    fun `a transcript tool frame's args land on the flattened row`() {
        val args = buildJsonObject { put("pattern", "*.kt") }
        val timeline = testChatTimeline(historyBacked = false)

        timeline.replaceTranscript(
            listOf(
                transcriptTurn(
                    "t0", 0,
                    transcriptStep(
                        "t0.1",
                        TranscriptFrame.Tool(
                            "t0.1.tool_1", "tool_1", "Glob", running = false,
                            preview = "*.kt", input = "{\n    \"pattern\": \"*.kt\"\n}", output = "a.kt\n",
                            args = args,
                        ),
                    ),
                ),
            ),
            keepLive = false,
        )

        val tool = (timeline.items().single() as ChatItem.ToolGroup).tools.single()
        assertEquals(args, tool.args)
    }

    @Test
    fun `an orphan result completed by an older page gains the call's args`() {
        // Page boundary: the newer page opens with the result (orphan row), the older
        // page brings the call — the in-place completion threads the args across.
        val args = buildJsonObject { put("command", "echo hi") }
        val timeline = testChatTimeline()
        timeline.replaceHistory(
            listOf(
                ChatMessage("msg_2", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", false, "hi\n"))),
            ),
            keepLive = false,
        )

        timeline.prependHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("tool_1", "Bash", "echo hi", args = args)),
                ),
            ),
        )

        val tool = (timeline.items().single() as ChatItem.ToolGroup).tools.single()
        assertEquals(args, tool.args)
    }

    @Test
    fun `a healed tool row keeps its first-set args`() {
        // First writer wins, like preview/input: the definitive result and a replayed
        // start frame (reconnect) must not clear or replace the row's args.
        val args = buildJsonObject { put("command", "echo hi") }
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "echo hi", args = args))
        timeline.apply(SessionEvent.ToolCallResult("tool_1", false, "hi\n"))

        timeline.apply(
            SessionEvent.ToolCallStarted(
                "tool_1",
                "Bash",
                "echo hi",
                args = buildJsonObject { put("command", "echo replayed") },
            ),
        )

        val tool = (timeline.items().single() as ChatItem.ToolGroup).tools.single()
        assertEquals(args, tool.args)
        assertEquals("hi\n", tool.output)
    }

    // --- Typed payloads (ticket 119) --------------------------------------------------

    @Test
    fun `a live Edit call started carries its parsed payload onto the row`() {
        val args = buildJsonObject {
            put("path", "/tmp/spike/a.txt")
            put("old_string", "one\ntwo")
            put("new_string", "one\nTWO")
        }
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())

        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Edit", "/tmp/spike/a.txt", args = args))

        val tool = (timeline.items().single() as ChatItem.ToolGroup).tools.single()
        assertEquals(ToolPayload.Edit("/tmp/spike/a.txt", "one\ntwo", "one\nTWO", replaceAll = false), tool.payload)
    }

    @Test
    fun `a history Edit row carries the parsed payload`() {
        val args = buildJsonObject {
            put("path", "/tmp/spike/a.txt")
            put("old_string", "one")
            put("new_string", "two")
            put("replace_all", true)
        }
        val timeline = testChatTimeline()

        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("tool_1", "Edit", "/tmp/spike/a.txt", args = args)),
                ),
            ),
            keepLive = false,
        )

        val tool = (timeline.items().single() as ChatItem.ToolGroup).tools.single()
        assertEquals(ToolPayload.Edit("/tmp/spike/a.txt", "one", "two", replaceAll = true), tool.payload)
    }

    @Test
    fun `a transcript Edit frame lands its parsed payload on the flattened row`() {
        val args = buildJsonObject {
            put("path", "/tmp/spike/a.txt")
            put("old_string", "one")
            put("new_string", "two")
        }
        val timeline = testChatTimeline(historyBacked = false)

        timeline.replaceTranscript(
            listOf(
                transcriptTurn(
                    "t0", 0,
                    transcriptStep(
                        "t0.1",
                        TranscriptFrame.Tool(
                            "t0.1.tool_1", "tool_1", "Edit", running = false,
                            preview = "/tmp/spike/a.txt", input = "", output = "ok",
                            args = args,
                        ),
                    ),
                ),
            ),
            keepLive = false,
        )

        val tool = (timeline.items().single() as ChatItem.ToolGroup).tools.single()
        assertEquals(ToolPayload.Edit("/tmp/spike/a.txt", "one", "two", replaceAll = false), tool.payload)
    }

    @Test
    fun `an unparseable Edit call leaves the payload null`() {
        // Missing old_string: the row keeps the generic expansion instead of the diff.
        val args = buildJsonObject { put("path", "/tmp/spike/a.txt") }
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())

        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Edit", "/tmp/spike/a.txt", args = args))

        val tool = (timeline.items().single() as ChatItem.ToolGroup).tools.single()
        assertEquals(args, tool.args)
        assertEquals(null, tool.payload)
    }

    @Test
    fun `a healed Edit row keeps its first-parsed payload`() {
        // First writer wins, like the args themselves: a replayed start frame must
        // not replace the payload.
        val args = buildJsonObject {
            put("path", "/tmp/spike/a.txt")
            put("old_string", "one")
            put("new_string", "two")
        }
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Edit", "/tmp/spike/a.txt", args = args))

        timeline.apply(
            SessionEvent.ToolCallStarted(
                "tool_1",
                "Edit",
                "/tmp/spike/a.txt",
                args = buildJsonObject {
                    put("path", "/tmp/spike/a.txt")
                    put("old_string", "replayed")
                    put("new_string", "replayed")
                },
            ),
        )

        val tool = (timeline.items().single() as ChatItem.ToolGroup).tools.single()
        assertEquals(ToolPayload.Edit("/tmp/spike/a.txt", "one", "two", replaceAll = false), tool.payload)
    }

    @Test
    fun `an orphan Edit result completed by an older page gains the call's payload`() {
        val args = buildJsonObject {
            put("path", "/tmp/spike/a.txt")
            put("old_string", "one")
            put("new_string", "two")
        }
        val timeline = testChatTimeline()
        timeline.replaceHistory(
            listOf(
                ChatMessage("msg_2", ChatMessage.Role.Tool, listOf(ChatMessage.Part.ToolResult("tool_1", false, "ok"))),
            ),
            keepLive = false,
        )

        timeline.prependHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("tool_1", "Edit", "/tmp/spike/a.txt", args = args)),
                ),
            ),
        )

        val tool = (timeline.items().single() as ChatItem.ToolGroup).tools.single()
        assertEquals(ToolPayload.Edit("/tmp/spike/a.txt", "one", "two", replaceAll = false), tool.payload)
    }

    // Ticket 155: the turn-end footer's copy per `turn.ended.interruptReason`.
    @Test
    fun `interrupt reason maps to its turn-end footer copy`() {
        assertEquals("Cancelled", turnEndCopy("user_cancelled"))
        assertEquals("Aborted", turnEndCopy("aborted"))
        assertEquals("Stopped: max steps", turnEndCopy("max_steps"))
        assertEquals("Stopped: error", turnEndCopy("error"))
        assertEquals("Stopped: filtered", turnEndCopy("filtered"))
        assertEquals("Stopped: blocked", turnEndCopy("blocked"))
        // Absent or unknown (the ignore-unknown rule): no footer — today's behavior.
        assertNull(turnEndCopy(null))
        assertNull(turnEndCopy("some_future_reason"))
    }

    @Test
    fun `an early stop appends the footer and the turn-end refetch keeps it`() {
        val timeline = testChatTimeline()

        timeline.onTurnEnded("max_steps")
        assertEquals(listOf(ChatItem.TurnEnd(TURN_END_ROW_ID, "Stopped: max steps")), timeline.items())

        // The ViewModel's turn-end refetch replaces the rows wholesale; the footer is
        // re-appended from the recorded reason — history itself carries none.
        timeline.replaceHistory(listOf(userMessage("msg_1", "hi")), keepLive = false)
        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "hi"),
                ChatItem.TurnEnd(TURN_END_ROW_ID, "Stopped: max steps"),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `the next turn start clears the footer`() {
        val timeline = testChatTimeline()
        timeline.onTurnEnded("user_cancelled")

        timeline.apply(SessionEvent.TurnStarted())

        assertTrue(timeline.items().isEmpty())
    }

    @Test
    fun `a completed or unknown-reason turn end adds no footer`() {
        val timeline = testChatTimeline()

        timeline.onTurnEnded(null)
        timeline.onTurnEnded("some_future_reason")

        assertTrue(timeline.items().isEmpty())
    }

    // --- Ticket 175: the transcript fold's turn/prompt events ---------------------------

    @Test
    fun `a failed turn's footer carries the turn's error text`() {
        assertEquals("Stopped: error — [provider.auth_error] no access", turnEndCopy("error", "[provider.auth_error] no access"))
        assertEquals("Stopped: error", turnEndCopy("error", " "))
        // The message rides the error reason only.
        assertEquals("Cancelled", turnEndCopy("user_cancelled", "ignored"))
    }

    @Test
    fun `an interrupted turn end settles the still-running live tool rows`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "build"))
        timeline.apply(SessionEvent.ToolCallResult("tool_1", false, "ok\n"))
        timeline.apply(SessionEvent.ToolCallStarted("tool_2", "Bash", "sleep 60"))

        timeline.onTurnEnded("user_cancelled")

        assertEquals(
            listOf(
                ChatItem.ToolGroup(
                    "tool_1",
                    listOf(
                        ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, "build", output = "ok\n"),
                        ChatItem.ToolActivity("tool_2", "Bash", ToolStatus.Error, "sleep 60"),
                    ),
                ),
                ChatItem.TurnEnd(TURN_END_ROW_ID, "Cancelled"),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `a completed turn end leaves the live tool rows alone`() {
        val timeline = testChatTimeline()
        timeline.apply(SessionEvent.TurnStarted())
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "build"))

        timeline.onTurnEnded(null)

        assertEquals(listOf(toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Running, "build"))), timeline.items())
    }

    @Test
    fun `a sub-agent timeline settles its running tools on an interrupted turn end event`() {
        // The store's timelines get the TurnEnded event itself (no ViewModel refetch).
        val timeline = testChatTimeline(historyBacked = false)
        timeline.apply(SessionEvent.TurnStarted("agent-0"))
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "sleep 60", agentId = "agent-0"))

        timeline.apply(SessionEvent.TurnEnded("cancelled", "agent-0", interruptReason = "user_cancelled"))

        assertEquals(
            listOf(toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Error, "sleep 60"))),
            timeline.items(),
        )
    }

    @Test
    fun `a prompt leaving the queue clears the echo's queued mark`() {
        val timeline = testChatTimeline()
        timeline.appendLocalEcho("msg_1", "hello", emptyList(), emptyList())
        timeline.apply(SessionEvent.PromptUpdated("msg_1", PromptStatus.Queued, queueLength = 1))
        assertEquals(listOf(ChatItem.UserMessage("msg_1", "hello", queued = 1)), timeline.items())

        // A steer completes the queued prompt; an abort or a start clears it alike.
        timeline.apply(SessionEvent.PromptUpdated("msg_1", PromptStatus.Completed))

        assertEquals(listOf(ChatItem.UserMessage("msg_1", "hello")), timeline.items())
    }


    // --- cold-start seeds (ticket 176) -------------------------------------------------

    @Test
    fun `the running-turn seed flattens frames into live rows, todo calls into the widget`() {
        val timeline = testChatTimeline()
        timeline.replaceHistory(
            listOf(ChatMessage("msg_1", ChatMessage.Role.User, listOf(ChatMessage.Part.Text("go")))),
            keepLive = false,
        )

        timeline.seedRunningTurn(
            TranscriptTurn(
                "t1", 1, TranscriptState.Running, prompt = "go",
                steps = listOf(
                    TranscriptStep(
                        "t1.1", TranscriptState.Completed,
                        listOf(
                            TranscriptFrame.Text("t1.1.f1", TranscriptFrame.Text.Role.Assistant, "Planning."),
                            TranscriptFrame.Tool(
                                "t1.1.todo", "todo_1", "TodoList", running = false, preview = "",
                                input = """{"todos":[{"title":"write","status":"pending"}]}""", output = "",
                            ),
                            TranscriptFrame.Tool(
                                "t1.1.ls", "call_ls", "Bash", running = false, preview = "ls",
                                input = "", output = "boom", failed = true,
                            ),
                        ),
                    ),
                    TranscriptStep(
                        "t1.2", TranscriptState.Running,
                        listOf(
                            TranscriptFrame.Text("t1.2.f0", TranscriptFrame.Text.Role.User, "steered"),
                            TranscriptFrame.Text("t1.2.f1", TranscriptFrame.Text.Role.Assistant, "Retrying"),
                        ),
                    ),
                ),
            ),
        )

        val items = timeline.items()
        assertEquals(listOf("msg_1", "live_0", "call_ls", "live_1"), items.map(ChatItem::id))
        val tool = (items[2] as ChatItem.ToolGroup).tools.single()
        assertEquals(ToolStatus.Error, tool.status)
        assertEquals("boom", tool.output)
        assertEquals(listOf(Todo("write", Todo.Status.Pending)), timeline.todos())
        // A live delta extends the running step's bubble.
        timeline.apply(SessionEvent.AssistantDelta("…"))
        assertEquals("Retrying…", (timeline.items().last() as ChatItem.AssistantMessage).text)
    }

    @Test
    fun `seeded queued prompts show their queue position and survive a refetch until committed`() {
        val timeline = testChatTimeline()
        timeline.replaceHistory(emptyList(), keepLive = false)

        timeline.seedQueuedPrompts(
            listOf(
                ChatMessage("msg_a", ChatMessage.Role.User, listOf(ChatMessage.Part.Text("first"))),
                ChatMessage("msg_b", ChatMessage.Role.User, listOf(ChatMessage.Part.Text("second"))),
            ),
        )

        assertEquals(listOf(1, 2), timeline.items().map { (it as ChatItem.UserMessage).queued })
        timeline.replaceHistory(emptyList(), keepLive = false)
        assertEquals(listOf("msg_a", "msg_b"), timeline.items().map(ChatItem::id))
        timeline.apply(SessionEvent.PromptUpdated("msg_a", PromptStatus.Running))
        assertNull((timeline.items().first() as ChatItem.UserMessage).queued)
        timeline.replaceHistory(
            listOf(ChatMessage("msg_a", ChatMessage.Role.User, listOf(ChatMessage.Part.Text("first")))),
            keepLive = false,
        )
        assertEquals(listOf("msg_a", "msg_b"), timeline.items().map(ChatItem::id))
        assertNull((timeline.items().first() as ChatItem.UserMessage).queued)
    }
}
