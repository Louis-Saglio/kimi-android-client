package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.ToolMedia
import fr.louissaglio.kimi.android.data.chat.TranscriptFrame
import fr.louissaglio.kimi.android.data.chat.TranscriptState
import fr.louissaglio.kimi.android.data.chat.TranscriptStep
import fr.louissaglio.kimi.android.data.chat.TranscriptTurn
import fr.louissaglio.kimi.android.testutil.testChatTimeline
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ticket 184: a tool result's media references ride every row path to
 * [ChatItem.ToolActivity.media] — history, the transcript flatten (the sub-agent page),
 * the cold start's running-turn seed and the live result event — so the row renders
 * the same image whichever carrier delivered it.
 */
class ChatTimelineToolMediaTest {

    private val media = listOf(ToolMedia("f_13df9359", ToolMedia.Kind.Image))

    private fun ChatTimeline.tool(id: String): ChatItem.ToolActivity =
        items().filterIsInstance<ChatItem.ToolGroup>().flatMap { it.tools }.single { it.id == id }

    private fun mediaFrame(running: Boolean = false) = TranscriptFrame.Tool(
        id = "t1.1.call_1",
        toolCallId = "call_1",
        name = "ReadMediaFile",
        running = running,
        preview = "/tmp/one.png",
        input = "",
        output = "",
        media = if (running) emptyList() else media,
    )

    private fun turn(state: TranscriptState, vararg frames: TranscriptFrame) =
        TranscriptTurn("t1", 1, state, prompt = null, steps = listOf(TranscriptStep("t1.1", state, frames.toList())))

    @Test
    fun `a history result's media land on the call's row`() {
        val timeline = testChatTimeline()

        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_1",
                    ChatMessage.Role.Assistant,
                    listOf(ChatMessage.Part.ToolUse("call_1", "ReadMediaFile", "/tmp/one.png")),
                ),
                ChatMessage(
                    "msg_2",
                    ChatMessage.Role.Tool,
                    listOf(ChatMessage.Part.ToolResult("call_1", isError = false, output = "", media = media)),
                ),
            ),
            keepLive = false,
        )

        val row = timeline.tool("call_1")
        assertEquals(ToolStatus.Done, row.status)
        assertEquals("", row.output)
        assertEquals(media, row.media)
    }

    @Test
    fun `an orphan history result keeps its media`() {
        val timeline = testChatTimeline()

        timeline.replaceHistory(
            listOf(
                ChatMessage(
                    "msg_2",
                    ChatMessage.Role.Tool,
                    listOf(ChatMessage.Part.ToolResult("call_1", isError = false, media = media)),
                ),
            ),
            keepLive = false,
        )

        assertEquals(media, timeline.tool("call_1").media)
    }

    @Test
    fun `a transcript page's tool frame carries its media to the row`() {
        val timeline = testChatTimeline()

        timeline.replaceTranscript(listOf(turn(TranscriptState.Completed, mediaFrame())), keepLive = false)

        assertEquals(media, timeline.tool("call_1").media)
    }

    @Test
    fun `a seeded running turn's settled tool frame carries its media`() {
        val timeline = testChatTimeline()

        timeline.seedRunningTurn(turn(TranscriptState.Running, mediaFrame()))

        assertEquals(media, timeline.tool("call_1").media)
    }

    @Test
    fun `the live result event sets the row's media`() {
        val timeline = testChatTimeline()

        timeline.apply(SessionEvent.ToolCallStarted("call_1", "ReadMediaFile", "/tmp/one.png"))
        assertEquals(emptyList<ToolMedia>(), timeline.tool("call_1").media)

        timeline.apply(SessionEvent.ToolCallResult("call_1", isError = false, output = "", media = media))

        val row = timeline.tool("call_1")
        assertEquals(ToolStatus.Done, row.status)
        assertEquals(media, row.media)
    }

    @Test
    fun `a plain result carries no media`() {
        val timeline = testChatTimeline()

        timeline.apply(SessionEvent.ToolCallStarted("call_1", "Bash", "ls"))
        timeline.apply(SessionEvent.ToolCallResult("call_1", isError = false, output = "a.txt"))

        val row = timeline.tool("call_1")
        assertEquals("a.txt", row.output)
        assertEquals(emptyList<ToolMedia>(), row.media)
    }
}
