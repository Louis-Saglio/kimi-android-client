package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `pendingApprovals keeps pending approval entities once, by state or pending id, in entity order`() {
        // transcript.reset.snapshot.interactions holds ended entities too (ticket 171).
        val entities = json.decodeFromString<List<InteractionEntityDto>>(
            "[" + listOf(
                fr.louissaglio.kimi.android.testutil.TranscriptFrames.approvalEntity("approval_0", state = "approved"),
                fr.louissaglio.kimi.android.testutil.TranscriptFrames.approvalEntity("approval_1", command = "ls", agentId = "agent-0"),
                fr.louissaglio.kimi.android.testutil.TranscriptFrames.approvalEntity("approval_1", command = "ls", agentId = "agent-0"),
                """{"interactionId":"question_1","interactionKind":"question","state":"pending","request":{}}""",
                """{"interactionId":"approval_2","interactionKind":"approval","state":"","request":{"toolName":"Write","action":"Writing a.txt"}}""",
            ).joinToString(",") + "]",
        )

        assertEquals(
            listOf(PendingApprovalRequest("approval_1", "Bash", "ls", agentId = "agent-0")),
            mapper.pendingApprovals(entities),
        )
        assertEquals(
            listOf(
                PendingApprovalRequest("approval_1", "Bash", "ls", agentId = "agent-0"),
                PendingApprovalRequest("approval_2", "Write", "Writing a.txt"),
            ),
            mapper.pendingApprovals(entities, pendingIds = setOf("approval_2")),
        )
    }

    @Test
    fun `an approval interaction frame maps by status and ignores questions and unknown statuses`() {
        fun frame(text: String) = json.decodeFromString<InteractionFrame>(text)
        val pending = frame(fr.louissaglio.kimi.android.testutil.TranscriptFrames.approvalFrame("approval_1", command = "echo hi"))
        assertEquals(SessionEvent.ApprovalRequested("approval_1", "Bash", "echo hi"), mapper.toApprovalEvent(pending))
        listOf("approved", "rejected", "cancelled").forEach { outcome ->
            val ended = frame(
                fr.louissaglio.kimi.android.testutil.TranscriptFrames.approvalFrame("approval_1", status = "ended", outcome = outcome),
            )
            assertEquals(SessionEvent.ApprovalResolved("approval_1"), mapper.toApprovalEvent(ended))
        }
        assertNull(mapper.toApprovalEvent(pending.copy(kind = "question")))
        assertNull(mapper.toApprovalEvent(pending.copy(status = "expired")))
        assertNull(mapper.toApprovalEvent(pending.copy(request = null)))
    }

    @Test
    fun `pendingQuestions keeps pending question entities once, keyed on the interaction id`() {
        // Ticket 172: the reset snapshot's full entity list (ended included) and the REST
        // transcript's pending ids select the same questions; approvals stay out.
        val frames = fr.louissaglio.kimi.android.testutil.TranscriptFrames
        val entities = json.decodeFromString<List<InteractionEntityDto>>(
            "[" + listOf(
                frames.questionEntity("question_0", state = "answered"),
                frames.questionEntity("question_1", toolCallId = "call_q1"),
                frames.questionEntity("question_1", toolCallId = "call_q1"),
                frames.approvalEntity("approval_1"),
                frames.questionEntity("question_2", state = ""),
                """{"interactionId":"question_3","interactionKind":"question","state":"pending","request":{"questions":7}}""",
            ).joinToString(",") + "]",
        )

        val pending = mapper.pendingQuestions(entities)
        assertEquals(listOf("question_1"), pending.map { it.questionId })
        assertEquals("call_q1", pending.single().toolCallId)
        assertEquals(MAIN_AGENT_ID, pending.single().agentId)
        assertEquals(listOf("Cake", "Pie"), pending.single().items.single().options.map { it.label })
        assertEquals(
            listOf("question_1", "question_2"),
            mapper.pendingQuestions(entities, pendingIds = setOf("question_2")).map { it.questionId },
        )
    }

    @Test
    fun `a question interaction frame maps by status and ignores approvals and unknown statuses`() {
        fun frame(text: String) = json.decodeFromString<InteractionFrame>(text)
        val frames = fr.louissaglio.kimi.android.testutil.TranscriptFrames
        val pending = frame(frames.questionFrame("question_1", toolCallId = "call_00_q", agentId = "agent-3"))

        val question = (mapper.toQuestionEvent(pending) as SessionEvent.QuestionRequested).question
        assertEquals("question_1", question.questionId)
        assertEquals("call_00_q", question.toolCallId)
        assertEquals("agent-3", question.agentId)
        assertEquals(listOf("q_0"), question.items.map { it.id })
        listOf("answered", "dismissed", "cancelled").forEach { outcome ->
            val ended = frame(frames.questionFrame("question_1", status = "ended", outcome = outcome))
            assertEquals(SessionEvent.QuestionResolved("question_1"), mapper.toQuestionEvent(ended))
            assertEquals(SessionEvent.QuestionResolved("question_1"), mapper.toInteractionEvent(ended))
        }
        assertNull(mapper.toQuestionEvent(pending.copy(kind = "approval")))
        assertNull(mapper.toQuestionEvent(pending.copy(status = "expired")))
        assertNull(mapper.toQuestionEvent(pending.copy(request = null)))
        assertNull(mapper.toQuestionEvent(pending.copy(interactionId = "")))
        // The dispatcher routes each kind to its own mapping, and nothing else.
        assertEquals(mapper.toQuestionEvent(pending), mapper.toInteractionEvent(pending))
        val approval = frame(frames.approvalFrame("approval_1"))
        assertEquals(mapper.toApprovalEvent(approval), mapper.toInteractionEvent(approval))
        assertNull(mapper.toInteractionEvent(pending.copy(kind = "survey")))
    }

    @Test
    fun `a started bash call's description beats the server display command`() {
        // ticket 095: the live tool-frame path prefers display.command — for Bash a
        // non-blank args.description (the human-written label) must win over it.
        val args = buildJsonObject {
            put("command", "./gradlew test")
            put("description", "Run the unit tests")
        }
        val display = ToolInputDisplayDto(kind = "command", command = "./gradlew test")

        assertEquals("Run the unit tests", mapper.preview("Bash", args, display))
    }

    @Test
    fun `the display command beats the args derivation when no description exists`() {
        val args = buildJsonObject { put("command", "ls  -la") }
        val display = ToolInputDisplayDto(kind = "command", command = "ls -la /srv")

        assertEquals("ls -la /srv", mapper.preview("Bash", args, display))
    }

    @Test
    fun `a call without a display previews its args`() {
        val args = buildJsonObject { put("path", "/tmp/spike/a.txt") }

        assertEquals(ToolPreview.of("Read", args), mapper.preview("Read", args, null))
    }

    // --- Raw args plumbing (ticket 118) ------------------------------------------------

    @Test
    fun `a tool_use part carries its input object as args`() {
        val input = buildJsonObject {
            put("command", "ls /tmp/spike")
            put("timeout", 30)
        }

        val message = mapper.toDomain(
            MessageDto(
                id = "msg_1",
                role = "assistant",
                content = listOf(
                    ContentPartDto(type = "tool_use", toolCallId = "tool_1", toolName = "Bash", input = input),
                ),
            ),
        )

        val part = message.parts.single() as ChatMessage.Part.ToolUse
        assertEquals(input, part.args)
        // The pretty string stays: the no-object-args expansion fallback still uses it.
        assertEquals(mapper.formatDetail(input), part.input)
    }

    @Test
    fun `a transcript tool frame carries its input object as args`() {
        val input = buildJsonObject { put("pattern", "*.kt") }

        val page = mapper.toDomain(
            transcript(
                TranscriptFrameDto(
                    kind = "tool",
                    frameId = "t0.1.tool_1",
                    toolCallId = "tool_1",
                    name = "Glob",
                    state = "done",
                    input = input,
                ),
            ),
        )

        val frame = page.turns.single().steps.single().frames.single() as TranscriptFrame.Tool
        assertEquals(input, frame.args)
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

    // --- Transcript task origins (ticket 099 — verified on 0.34.0, docs/server-api.md) ---

    private fun taskOriginTurnDto(
        origin: TranscriptOriginDto?,
    ): TranscriptData = TranscriptData(
        agentId = "agent-1",
        items = listOf(TranscriptItemDto(kind = "turn", turnId = "t1", state = "completed", origin = origin)),
    )

    @Test
    fun `a task-origin turn parses the task id and terminal status`() {
        // The verbatim 0.34.0 shape: taskId flattened AND inside payload, status
        // only inside payload.
        val page = mapper.toDomain(
            taskOriginTurnDto(
                TranscriptOriginDto(
                    kind = "task",
                    taskId = "bash-xhlawxzk",
                    payload = buildJsonObject {
                        put("kind", "task")
                        put("taskId", "bash-xhlawxzk")
                        put("status", "completed")
                        put("notificationId", "task:bash-xhlawxzk:completed")
                    },
                ),
            ),
        )

        assertEquals(
            TranscriptTaskOrigin("bash-xhlawxzk", TaskInfo.Status.Completed),
            page.turns.single().taskOrigin,
        )
    }

    @Test
    fun `the task origin falls back to the flattened task id and maps the core vocabulary`() {
        val page = mapper.toDomain(
            taskOriginTurnDto(
                TranscriptOriginDto(
                    kind = "task",
                    taskId = "agent-a7fxomh0",
                    payload = buildJsonObject { put("status", "killed") },
                ),
            ),
        )

        assertEquals(
            TranscriptTaskOrigin("agent-a7fxomh0", TaskInfo.Status.Killed),
            page.turns.single().taskOrigin,
        )
    }

    @Test
    fun `an unknown origin status maps to null rather than a guess`() {
        val page = mapper.toDomain(
            taskOriginTurnDto(
                TranscriptOriginDto(
                    kind = "task",
                    payload = buildJsonObject {
                        put("taskId", "bash-1")
                        put("status", "something-new")
                    },
                ),
            ),
        )

        assertEquals(TranscriptTaskOrigin("bash-1", null), page.turns.single().taskOrigin)
    }

    @Test
    fun `non-task origins carry no task origin`() {
        val page = mapper.toDomain(
            taskOriginTurnDto(
                TranscriptOriginDto(
                    kind = "other",
                    payload = buildJsonObject {
                        put("kind", "system_trigger")
                        put("name", "subagent")
                    },
                ),
            ),
        )

        assertNull(page.turns.single().taskOrigin)
    }

    // --- Origin attachments (ticket 160 — 0.39.1, docs/server-api.md, Prompts) ---------

    private val wireJson = Json { ignoreUnknownKeys = true }

    @Test
    fun `origin attachments parse into chips and their notice text drops out`() {
        // The doc shape: `metadata.origin.attachments` entries are camelCase
        // {name, mediaType, size, path}. The server still rewrites each file part into
        // its "Attached file" notice text part — the chip carries the structured data,
        // the sentence drops; the 0.42.0 media part's `name` lands on the image.
        val message = wireJson.decodeFromString(
            MessageDto.serializer(),
            """
            {"id":"msg_1","role":"user","content":[
              {"type":"text","text":"Attached file \"notes.txt\" (text/plain, 340 bytes): /home/ubuntu/.kimi-code/sessions/wd_1/session_2/attachments/f_01KYA4RCBE4NPA1RX5TT7DB5J9-notes.txt — open it with the Read tool"},
              {"type":"text","text":"what is in this?"},
              {"type":"image","source":{"kind":"session_media","file_id":"f_img"},"name":"one.png"}
            ],
            "metadata":{"origin":{"kind":"user","attachments":[
              {"name":"notes.txt","mediaType":"text/plain","size":340,"path":"/home/ubuntu/.kimi-code/sessions/wd_1/session_2/attachments/f_01KYA4RCBE4NPA1RX5TT7DB5J9-notes.txt"}
            ]}}}
            """.trimIndent(),
        )

        assertEquals(
            ChatMessage(
                "msg_1",
                ChatMessage.Role.User,
                listOf(
                    ChatMessage.Part.Text("what is in this?"),
                    ChatMessage.Part.Image(base64Data = null, fileId = "f_img", isSessionMedia = true, name = "one.png"),
                    ChatMessage.Part.Attachment("notes.txt", "text/plain", 340, "f_01KYA4RCBE4NPA1RX5TT7DB5J9"),
                ),
                originKind = "user",
            ),
            mapper.toDomain(message),
        )
    }

    @Test
    fun `the splice echo carries its attachments on the top-level origin`() {
        // context.spliced puts the same array on the message's top-level `origin`
        // (docs/server-api.md, Prompts). A server-local path carries no file id —
        // the chip is inert.
        val message = wireJson.decodeFromString(
            MessageDto.serializer(),
            """
            {"id":"msg_1","role":"user","content":[
              {"type":"text","text":"Attached file \"report.pdf\" (application/pdf, 2048 bytes): /tmp/report.pdf — open it with the Read tool"}
            ],
            "origin":{"kind":"user","attachments":[
              {"name":"report.pdf","mediaType":"application/pdf","size":2048,"path":"/tmp/report.pdf"}
            ]}}
            """.trimIndent(),
        )

        assertEquals(
            listOf(ChatMessage.Part.Attachment("report.pdf", "application/pdf", 2048, null)),
            mapper.toDomain(message).parts,
        )
    }

    @Test
    fun `notice-shaped text without the origin array renders as plain text`() {
        // No notice regex anymore (ticket 160): with no structured array there is no
        // chip, and the sentence is just text to the app.
        val message = mapper.toDomain(
            MessageDto(
                id = "msg_1",
                role = "user",
                content = listOf(
                    ContentPartDto(
                        type = "text",
                        text = "Attached file \"notes.txt\" (text/plain, 340 bytes): /tmp/notes.txt — open it with the Read tool",
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(
                ChatMessage.Part.Text(
                    "Attached file \"notes.txt\" (text/plain, 340 bytes): /tmp/notes.txt — open it with the Read tool",
                ),
            ),
            message.parts,
        )
    }

    // --- agent_call display parsing (ticket 157) ----------------------------------

    @Test
    fun `an agent call display parses into the spawn row's model`() {
        // Doc-verbatim (docs/server-api.md, Sub-agents — 0.31.0):
        // {"kind":"agent_call","agent_name":"explore","prompt":"..."}.
        val display = wireJson.decodeFromString(
            ToolInputDisplayDto.serializer(),
            """{"kind":"agent_call","agent_name":"explore",""" +
                """"prompt":"Reply with the single word: ok"}""",
        )

        assertEquals(
            AgentCallDisplay("explore", "Reply with the single word: ok", background = false),
            mapper.agentCall(display),
        )
    }

    @Test
    fun `a background agent call display parses with the flag set`() {
        // A background spawn adds "background":true (docs/server-api.md, Sub-agents).
        val display = wireJson.decodeFromString(
            ToolInputDisplayDto.serializer(),
            """{"kind":"agent_call","agent_name":"explore",""" +
                """"prompt":"Reply with the single word: ok","background":true}""",
        )

        assertEquals(
            AgentCallDisplay("explore", "Reply with the single word: ok", background = true),
            mapper.agentCall(display),
        )
    }

    @Test
    fun `the command kind and unknown kinds parse to no agent call`() {
        // Unknown-kind tolerance is untouched: only kind "agent_call" parses.
        assertNull(mapper.agentCall(ToolInputDisplayDto(kind = "command", command = "ls")))
        assertNull(mapper.agentCall(ToolInputDisplayDto(kind = "future_kind")))
        assertNull(mapper.agentCall(null))
    }

    @Test
    fun `an agent call display without a usable name parses to nothing`() {
        assertNull(mapper.agentCall(ToolInputDisplayDto(kind = "agent_call", prompt = "Reply ok")))
        assertNull(mapper.agentCall(ToolInputDisplayDto(kind = "agent_call", agentName = " ", prompt = "Reply ok")))
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
