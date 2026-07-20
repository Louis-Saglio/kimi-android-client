package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTimelineTest {

    private fun userMessage(id: String, text: String) =
        ChatMessage(id, ChatMessage.Role.User, listOf(ChatMessage.Part.Text(text)))

    @Test
    fun `history flattens to bubbles and tool rows in order`() {
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
                ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done),
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
            listOf(ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Error)),
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
                ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done),
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
                ChatItem.ToolActivity("tool_1", "Bash", ToolStatus.Done),
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
}
