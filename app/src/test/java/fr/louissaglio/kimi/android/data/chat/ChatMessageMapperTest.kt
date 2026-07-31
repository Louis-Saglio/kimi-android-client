package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The transcript mapping's envelope boundary (ticket 068): user-role text frames of an
 * agent transcript go through the same InternalEnvelopes stripping as history messages —
 * a sub-agent's internal prompts/nudges must not render as raw XML on the sub-agent
 * page. Assistant/thinking frames pass through untouched.
 */
class ChatMessageMapperTest {

    private val mapper = ChatMessageMapper(Json { ignoreUnknownKeys = true })

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
