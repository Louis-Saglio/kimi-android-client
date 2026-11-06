package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ticket 184 — server 2.5.1's media tool results: `output` is a content-part ARRAY
 * (docs/server-api.md, Messages → History and Transcript). One shared parse
 * ([ChatMessageMapper.toolOutput]) feeds all three carriers — the history
 * `tool_result` part, the REST transcript tool frame and the live `transcript.ops`
 * fold — so each entry point is pinned against the verbatim capture.
 */
class ToolResultMediaTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val mapper = ChatMessageMapper(json)

    /** The verbatim 2.5.1 `ReadMediaFile` result array (file id trimmed as captured). */
    private val capturedOutput = """[
        {"type":"text","text":"<image path=\"/home/ubuntu/spike/one.png\">"},
        {"type":"image","source":{"kind":"session_media","file_id":"f_13df9359"}},
        {"type":"text","text":"</image>"}]"""

    private val capturedMedia = listOf(ToolMedia("f_13df9359", ToolMedia.Kind.Image))

    private fun output(raw: String) = mapper.toolOutput(json.parseToJsonElement(raw))

    // --- The shared parse ----------------------------------------------------------------

    @Test
    fun `the captured array yields the image and no text - the wrapper tags are dropped`() {
        assertEquals(ToolOutput("", capturedMedia), output(capturedOutput))
    }

    @Test
    fun `text parts become the output text and an unknown part type is ignored`() {
        val result = output(
            """[{"type":"text","text":"first"},
                {"type":"audio","source":{"kind":"session_media","file_id":"f_audio"}},
                {"type":"image","source":{"kind":"session_media","file_id":"f_1"},"name":"shot.png"},
                {"type":"text","text":"second"}]""",
        )

        assertEquals(ToolOutput("first\nsecond", listOf(ToolMedia("f_1", ToolMedia.Kind.Image, "shot.png"))), result)
    }

    @Test
    fun `a video part maps to video media with its name`() {
        val result = output(
            """[{"type":"text","text":"<video path=\"/tmp/a.mp4\">"},
                {"type":"video","source":{"kind":"session_media","file_id":"f_v"},"name":"a.mp4"},
                {"type":"text","text":"</video>"}]""",
        )

        assertEquals(ToolOutput("", listOf(ToolMedia("f_v", ToolMedia.Kind.Video, "a.mp4"))), result)
    }

    @Test
    fun `a media part with a non session-media source is ignored and the wrapper text kept`() {
        // Base64/https media stay on the server's text path; an image part with another
        // source is outside the documented union, so it is no media and nothing is lost.
        val result = output(
            """[{"type":"text","text":"<image path=\"/a.png\">"},
                {"type":"image","source":{"kind":"url","url":"https://example.com/a.png"}},
                {"type":"text","text":"</image>"}]""",
        )

        assertEquals(ToolOutput("<image path=\"/a.png\">\n</image>"), result)
    }

    @Test
    fun `text that only mentions an image tag is not a wrapper`() {
        val result = output(
            """[{"type":"text","text":"Read <image> tags carefully"},
                {"type":"image","source":{"kind":"session_media","file_id":"f_1"}}]""",
        )

        assertEquals("Read <image> tags carefully", result.text)
    }

    @Test
    fun `a string output keeps today's rendering exactly`() {
        assertEquals(ToolOutput("a\nb\n"), mapper.toolOutput(JsonPrimitive("a\nb\n")))
        assertEquals(ToolOutput(""), mapper.toolOutput(null))
    }

    @Test
    fun `a structured output that is not a content-part array keeps the pretty-printed JSON`() {
        listOf(
            """{"answers":{"q1":"yes"}}""",
            """["a","b"]""",
            """[{"path":"a.txt"}]""",
            """[{"type":"file","path":"a.txt"}]""",
            """[]""",
        ).forEach { raw ->
            val element = json.parseToJsonElement(raw)
            assertEquals(raw, ToolOutput(mapper.formatDetail(element)), mapper.toolOutput(element))
        }
    }

    @Test
    fun `only image media convert to an image part - a video never reaches the decoder`() {
        assertEquals(
            ChatMessage.Part.Image(base64Data = null, fileId = "f_1", isSessionMedia = true, name = "one.png"),
            ToolMedia("f_1", ToolMedia.Kind.Image, "one.png").toImagePart(),
        )
        assertNull(ToolMedia("f_v", ToolMedia.Kind.Video, "a.mp4").toImagePart())
    }

    // --- Entry point 1: history `tool_result` (GET .../messages) ----------------------

    @Test
    fun `a history tool_result with the captured array carries the media, not the JSON blob`() {
        val dto = json.decodeFromString<MessageDto>(
            """{"id":"msg_s_000006","role":"tool","content":[{"type":"tool_result",
                "tool_call_id":"ReadMediaFile_0_731303c4","output":$capturedOutput}],
                "created_at":"2026-11-05T19:27:50.461Z"}""",
        )

        val part = mapper.toDomain(dto).parts.single()

        assertEquals(ChatMessage.Part.ToolResult("ReadMediaFile_0_731303c4", false, "", capturedMedia), part)
    }

    @Test
    fun `a history tool_result with a string output is unchanged`() {
        val dto = json.decodeFromString<MessageDto>(
            """{"id":"m","role":"tool","content":[{"type":"tool_result","tool_call_id":"c","output":"ok","is_error":true}]}""",
        )

        assertEquals(ChatMessage.Part.ToolResult("c", true, "ok"), mapper.toDomain(dto).parts.single())
    }

    // --- Entry point 2: the REST transcript tool frame (warm transcript) ---------------

    private val capturedFrame = """{"kind":"tool","frameId":"t1.1.ReadMediaFile_0_731303c4",
        "toolCallId":"ReadMediaFile_0_731303c4","name":"ReadMediaFile","state":"done",
        "input":{"path":"/home/ubuntu/spike/one.png"},"output":$capturedOutput}"""

    @Test
    fun `a transcript tool frame with the captured array carries the media`() {
        val page = mapper.toDomain(
            TranscriptData(
                agentId = "main",
                items = listOf(
                    TranscriptItemDto(
                        kind = "turn",
                        turnId = "t1",
                        steps = listOf(
                            TranscriptStepDto(
                                kind = "step",
                                stepId = "t1.1",
                                frames = listOf(json.decodeFromString<TranscriptFrameDto>(capturedFrame)),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val frame = page.turns.single().steps.single().frames.single() as TranscriptFrame.Tool
        assertEquals("", frame.output)
        assertEquals(capturedMedia, frame.media)
    }

    @Test
    fun `a media-only frame result is definitive - the progress tail does not stand in`() {
        val dto = json.decodeFromString<TranscriptFrameDto>(
            capturedFrame.replace(""""state":"done",""", """"state":"done","progress":{"kind":"text","text":"reading…"},"""),
        )

        val page = mapper.toDomain(
            TranscriptData(
                agentId = "main",
                items = listOf(
                    TranscriptItemDto(
                        kind = "turn",
                        turnId = "t1",
                        steps = listOf(TranscriptStepDto(kind = "step", stepId = "t1.1", frames = listOf(dto))),
                    ),
                ),
            ),
        )

        val frame = page.turns.single().steps.single().frames.single() as TranscriptFrame.Tool
        assertEquals("", frame.output)
        assertEquals(capturedMedia, frame.media)
    }

    // --- Entry point 3: the live `frame.upsert` (transcript.ops fold) ------------------

    @Test
    fun `a live done tool frame with the captured array emits a result carrying the media`() {
        val fold = TranscriptFold(json, mapper)
        val op = json.parseToJsonElement(
            """{"op":"frame.upsert","turnId":"t1","stepId":"t1.1","frame":$capturedFrame}""",
        ).jsonObject

        val result = fold.fold(MAIN_AGENT_ID, listOf(op)).filterIsInstance<SessionEvent.ToolCallResult>().single()

        assertEquals(
            SessionEvent.ToolCallResult("ReadMediaFile_0_731303c4", false, "", MAIN_AGENT_ID, capturedMedia),
            result,
        )
    }
}
