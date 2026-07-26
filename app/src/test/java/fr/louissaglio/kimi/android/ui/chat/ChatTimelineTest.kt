package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTimelineTest {

    private fun userMessage(id: String, text: String) =
        ChatMessage(id, ChatMessage.Role.User, listOf(ChatMessage.Part.Text(text)))

    /** A single tool row as the group the reducer wraps it in (ticket 037). */
    private fun toolGroup(tool: ChatItem.ToolActivity) = ChatItem.ToolGroup(tool)

    @Test
    fun `a user message mixing an attachment and real text renders the text plus a chip`() {
        val timeline = ChatTimeline()

        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.User,
                    listOf(
                        ChatMessage.Part.Attachment("notes.txt", "text/plain", 340, "f_01KYA4RCBE4NPA1RX5TT7DB5J9"),
                        ChatMessage.Part.Image("image/png", base64Data = "aGVsbG8=", fileId = null),
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
                    images = listOf(ChatMessage.Part.Image("image/png", base64Data = "aGVsbG8=", fileId = null)),
                ),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `history and spliced user messages carry their timestamp to the bubble`() {
        val timeline = ChatTimeline()
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
        val timeline = ChatTimeline()
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
        val timeline = ChatTimeline()

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
        val timeline = ChatTimeline()

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
        val timeline = ChatTimeline()

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
        val timeline = ChatTimeline()

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
        val timeline = ChatTimeline()

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
        val timeline = ChatTimeline()

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
        val timeline = ChatTimeline()

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
    fun `tool call started fills the running row preview live`() {
        val timeline = ChatTimeline()
        timeline.apply(SessionEvent.TurnStarted)

        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "echo doc-verify"))

        assertEquals(
            listOf(toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Running, "echo doc-verify"))),
            timeline.items(),
        )
    }

    @Test
    fun `a live stream appends new calls to the open group until a bubble splits it`() {
        val timeline = ChatTimeline()
        timeline.apply(SessionEvent.TurnStarted)

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
    fun `a row created by argument deltas picks up the started preview and keeps it on result`() {
        val timeline = ChatTimeline()
        timeline.apply(SessionEvent.TurnStarted)
        // tool.call.delta arrives first (argument streaming), then the started frame.
        timeline.apply(SessionEvent.ToolCallDelta("tool_1", "Bash"))

        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "echo hi"))
        timeline.apply(SessionEvent.ToolCallResult("tool_1", false))

        assertEquals(
            listOf(toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, "echo hi"))),
            timeline.items(),
        )
    }

    @Test
    fun `a live tool result fills the output of its running row`() {
        val timeline = ChatTimeline()
        timeline.apply(SessionEvent.TurnStarted)
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
    fun `progress chunks append to the matching running row only`() {
        val timeline = ChatTimeline()
        timeline.apply(SessionEvent.TurnStarted)
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "build"))
        timeline.apply(SessionEvent.ToolCallStarted("tool_2", "Bash", "test"))

        timeline.apply(SessionEvent.ToolCallProgress("tool_2", "compiling...\n"))
        timeline.apply(SessionEvent.ToolCallProgress("tool_1", "> Task :app:assemble\n"))
        timeline.apply(SessionEvent.ToolCallProgress("tool_2", "running tests\n"))

        assertEquals(
            listOf(
                ChatItem.ToolGroup(
                    "tool_1",
                    listOf(
                        ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Running, "build", output = "> Task :app:assemble\n"),
                        ChatItem.ToolActivity("tool_2", "Bash", ToolStatus.Running, "test", output = "compiling...\nrunning tests\n"),
                    ),
                ),
            ),
            timeline.items(),
        )
    }

    @Test
    fun `progress for unknown or finished calls is ignored`() {
        val timeline = ChatTimeline()
        timeline.apply(SessionEvent.TurnStarted)
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash"))
        timeline.apply(SessionEvent.ToolCallResult("tool_1", false, "final\n"))

        // Unknown id: volatile frames must never create a row on their own.
        timeline.apply(SessionEvent.ToolCallProgress("tool_9", "stray\n"))
        // Finished call: the definitive result stands.
        timeline.apply(SessionEvent.ToolCallProgress("tool_1", "late chunk\n"))

        assertEquals(
            listOf(toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, output = "final\n"))),
            timeline.items(),
        )
    }

    @Test
    fun `tool result replaces the accumulated progress wholesale`() {
        val timeline = ChatTimeline()
        timeline.apply(SessionEvent.TurnStarted)
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "echo hi"))
        timeline.apply(SessionEvent.ToolCallProgress("tool_1", "h"))
        timeline.apply(SessionEvent.ToolCallProgress("tool_1", "i\n"))

        timeline.apply(SessionEvent.ToolCallResult("tool_1", false, "hi\n"))

        assertEquals(
            listOf(toolGroup(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done, "echo hi", output = "hi\n"))),
            timeline.items(),
        )
    }

    @Test
    fun `mid-turn refetch clears the volatile progress of committed rows`() {
        val timeline = ChatTimeline()
        timeline.apply(SessionEvent.TurnStarted)
        timeline.apply(SessionEvent.ToolCallStarted("tool_1", "Bash", "build"))
        timeline.apply(SessionEvent.ToolCallProgress("tool_1", "partial log\n"))

        // The server has since committed the finished call: the refetched history is
        // canonical and the progress-accumulating live row is dropped.
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
        val timeline = ChatTimeline()
        timeline.apply(SessionEvent.TurnStarted)
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
        val timeline = ChatTimeline()

        timeline.apply(SessionEvent.TurnStarted)
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
        val timeline = ChatTimeline()
        timeline.apply(SessionEvent.TurnStarted)
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
        val timeline = ChatTimeline()
        timeline.apply(SessionEvent.TurnStarted)
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
        val timeline = ChatTimeline()
        timeline.apply(SessionEvent.TurnStarted)
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
        val timeline = ChatTimeline()
        timeline.apply(SessionEvent.TurnStarted)

        timeline.apply(SessionEvent.ContextSpliced(listOf(userMessage("msg_1", "hello from the phone"))))

        assertEquals(
            listOf(ChatItem.UserMessage("msg_1", "hello from the phone")),
            timeline.items(),
        )
    }

    @Test
    fun `context spliced is deduplicated by message id`() {
        val timeline = ChatTimeline()
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
    fun `context spliced ignores non-user messages`() {
        val timeline = ChatTimeline()

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
        val timeline = ChatTimeline()
        timeline.apply(SessionEvent.TurnStarted)
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
        val timeline = ChatTimeline()

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
        val timeline = ChatTimeline()
        timeline.replaceHistory(listOf(userMessage("msg_9", "stop counting")), keepLive = true)

        timeline.apply(
            SessionEvent.ContextSpliced(
                listOf(ChatMessage("", ChatMessage.Role.User, listOf(ChatMessage.Part.Text("stop counting")))),
            ),
        )

        assertEquals(listOf(ChatItem.UserMessage("msg_9", "stop counting")), timeline.items())
    }
}
