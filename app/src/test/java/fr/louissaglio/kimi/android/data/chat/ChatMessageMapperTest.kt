package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The transcript mapping's envelope boundary (ticket 068): user-role text frames of an
 * agent transcript go through the same InternalEnvelopes stripping as history messages —
 * a sub-agent's internal prompts/nudges must not render as raw XML on the sub-agent
 * page. Assistant/thinking frames pass through untouched. Also covers the live row
 * preview's precedence (ticket 095).
 */
class ChatMessageMapperTest {

    private val mapper = ChatMessageMapper(Json { ignoreUnknownKeys = true })

    @Test
    fun `a started bash call's description beats the server display command`() {
        // ticket 095: the live tool.call.started path prefers display.command — for Bash
        // a non-blank args.description (the human-written label) must win over it.
        val payload = ToolCallPayload(
            toolCallId = "tool_1",
            name = "Bash",
            args = buildJsonObject {
                put("command", "./gradlew test")
                put("description", "Run the unit tests")
            },
            display = ToolInputDisplayDto(kind = "command", command = "./gradlew test"),
        )

        assertEquals("Run the unit tests", mapper.preview(payload))
    }

    @Test
    fun `a user-role transcript frame is stripped of internal envelopes`() {
        val page = mapper.toDomain(
            transcript(
                TranscriptFrameDto(
                    kind = "text",
                    frameId = "t0.1.f1",
                    role = "user",
                    text = "deploy the app\n\n<system-reminder>\ninternal note\n</system-reminder>",
                ),
            ),
        )

        assertEquals(
            listOf(TranscriptFrame.Text("t0.1.f1", TranscriptFrame.Text.Role.User, "deploy the app")),
            page.turns.single().steps.single().frames,
        )
    }

    @Test
    fun `a user-role transcript frame that is entirely an envelope is dropped`() {
        val page = mapper.toDomain(
            transcript(
                TranscriptFrameDto(
                    kind = "text",
                    frameId = "t0.1.f1",
                    role = "user",
                    text = "<notification id=\"n_1\" category=\"task\" type=\"task.completed\" " +
                        "source_kind=\"background_task\" source_id=\"task_1\">\nTitle: done\n</notification>",
                ),
            ),
        )

        assertEquals(emptyList<TranscriptFrame>(), page.turns.single().steps.single().frames)
    }

    @Test
    fun `assistant and thinking transcript frames pass through untouched`() {
        val page = mapper.toDomain(
            transcript(
                TranscriptFrameDto(
                    kind = "thinking",
                    frameId = "t0.1.f1",
                    text = "hmm <system-reminder>raw</system-reminder>",
                ),
                TranscriptFrameDto(
                    kind = "text",
                    frameId = "t0.1.f2",
                    role = "assistant",
                    text = "I keep <system-reminder>raw</system-reminder> as-is",
                ),
            ),
        )

        assertEquals(
            listOf(
                TranscriptFrame.Thinking("t0.1.f1", "hmm <system-reminder>raw</system-reminder>"),
                TranscriptFrame.Text(
                    "t0.1.f2",
                    TranscriptFrame.Text.Role.Assistant,
                    "I keep <system-reminder>raw</system-reminder> as-is",
                ),
            ),
            page.turns.single().steps.single().frames,
        )
    }

    @Test
    fun `transcript turn states map the terminal failure states to Failed`() {
        // Real on the wire since 0.31.1 (ticket 085): the sub-agent page's cold status
        // reads them — a cancelled/failed run must not blanket-map to Completed.
        fun stateOf(state: String) = mapper.toDomain(
            TranscriptData(
                agentId = "agent-1",
                items = listOf(TranscriptItemDto(kind = "turn", turnId = "t0", state = state)),
            ),
        ).turns.single().state

        assertEquals(TranscriptState.Running, stateOf("running"))
        assertEquals(TranscriptState.Completed, stateOf("completed"))
        assertEquals(TranscriptState.Failed, stateOf("failed"))
        assertEquals(TranscriptState.Failed, stateOf("cancelled"))
        assertEquals(TranscriptState.Other, stateOf("something-new"))
    }

    private fun transcript(vararg frames: TranscriptFrameDto): TranscriptData = TranscriptData(
        agentId = "agent-1",
        items = listOf(
            TranscriptItemDto(
                kind = "turn",
                turnId = "t0",
                steps = listOf(
                    TranscriptStepDto(kind = "step", stepId = "t0.1", frames = frames.toList()),
                ),
            ),
        ),
    )
}
