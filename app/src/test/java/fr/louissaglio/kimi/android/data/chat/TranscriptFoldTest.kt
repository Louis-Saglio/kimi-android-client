package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `transcript.ops` → [SessionEvent] fold (ticket 175): every op of the union, and
 * the upsert → transition bookkeeping. Op bodies follow the verbatim 2.5.0 captures in
 * docs/server-api.md (WebSocket → Transcript protocol / Captured round-trips).
 */
class TranscriptFoldTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val fold = TranscriptFold(json, ChatMessageMapper(json))

    private fun ops(vararg ops: String): List<JsonObject> = ops.map { json.parseToJsonElement(it).jsonObject }

    private fun fold(vararg ops: String, agentId: String = MAIN_AGENT_ID): List<SessionEvent> =
        fold.fold(agentId, ops(*ops))

    private fun prompt(id: String, status: String, extra: String = "") =
        """{"op":"prompt.upsert","prompt":{"promptId":"$id","status":"$status","userMessageId":"$id",""" +
            """"content":[{"type":"text","text":"hi"}],"createdAt":"2026-11-04T19:25:50.000Z"$extra}}"""

    private fun turn(id: String, state: String, extra: String = "") =
        """{"op":"turn.upsert","turn":{"kind":"turn","turnId":"$id","ordinal":1,"state":"$state",""" +
            """"origin":{"kind":"user","payload":{"kind":"user"}},"prompt":"hi"$extra}}"""

    private fun step(turnId: String, id: String, state: String, extra: String = "") =
        """{"op":"step.upsert","turnId":"$turnId","step":{"kind":"step","stepId":"$id","turnId":"$turnId",""" +
            """"ordinal":1,"state":"$state"$extra}}"""

    private fun textFrame(frameId: String, text: String, kind: String = "text", role: String = "assistant", turnId: String = "t1") =
        """{"op":"frame.upsert","turnId":"$turnId","stepId":"$turnId.1","frame":{"kind":"$kind","frameId":"$frameId",""" +
            (if (kind == "text") """"role":"$role",""" else "") + """"text":"$text"}}"""

    private fun append(frameId: String, offset: Int, text: String, turnId: String = "t1") =
        """{"op":"append","target":{"type":"frame","turnId":"$turnId","stepId":"$turnId.1","frameId":"$frameId"},""" +
            """"offset":$offset,"text":"$text"}"""

    private fun toolFrame(state: String, extra: String = "", name: String = "Bash") =
        """{"op":"frame.upsert","turnId":"t1","stepId":"t1.1","frame":{"kind":"tool","frameId":"t1.1.call_1",""" +
            """"toolCallId":"call_1","name":"$name","state":"$state"$extra}}"""

    private fun undoMarker(markerId: String, messages: String) =
        """{"op":"marker.upsert","item":{"kind":"marker","markerId":"$markerId","marker":"undo",""" +
            """"payload":{"agentId":"main","start":4,"deleteCount":0,"messages":[$messages]},"at":"2026-11-04T19:25:51.000Z"}}"""

    // --- prompt.upsert -----------------------------------------------------------------

    @Test
    fun `a prompt upsert emits its status once per change`() {
        assertEquals(
            listOf(SessionEvent.PromptUpdated("msg_1", PromptStatus.Running)),
            fold(prompt("msg_1", "running")),
        )
        // The capture re-sends the running entity after the splice: no second event.
        assertEquals(emptyList<SessionEvent>(), fold(prompt("msg_1", "running")))
        assertEquals(
            listOf(SessionEvent.PromptUpdated("msg_1", PromptStatus.Completed)),
            fold(prompt("msg_1", "completed", ""","finishedAt":"2026-11-04T19:25:53.000Z"""")),
        )
    }

    @Test
    fun `queued prompts carry the agent's queue length`() {
        fold(prompt("msg_1", "running"))

        val events = fold(prompt("msg_2", "queued"), prompt("msg_2", "queued"), prompt("msg_3", "queued"))

        assertEquals(
            listOf(
                SessionEvent.PromptUpdated("msg_2", PromptStatus.Queued, queueLength = 1),
                SessionEvent.PromptUpdated("msg_3", PromptStatus.Queued, queueLength = 2),
            ),
            events,
        )
        // A steer flips the queued prompt to completed with steeredAt.
        assertEquals(
            listOf(SessionEvent.PromptUpdated("msg_2", PromptStatus.Completed)),
            fold(prompt("msg_2", "completed", ""","steeredAt":"2026-11-04T19:26:00.000Z"""")),
        )
    }

    @Test
    fun `every prompt status maps and an unknown one drops`() {
        val events = fold(
            prompt("a", "aborted"), prompt("b", "blocked"), prompt("c", "failed"), prompt("d", "warping"),
        )

        assertEquals(
            listOf(PromptStatus.Aborted, PromptStatus.Blocked, PromptStatus.Failed),
            events.map { (it as SessionEvent.PromptUpdated).status },
        )
    }

    // --- turn.upsert -------------------------------------------------------------------

    @Test
    fun `a turn upsert emits started once and ended once`() {
        assertEquals(listOf(SessionEvent.TurnStarted()), fold(turn("t1", "running")))
        assertEquals(emptyList<SessionEvent>(), fold(turn("t1", "running")))
        assertEquals(
            listOf(SessionEvent.TurnEnded("completed")),
            fold(turn("t1", "completed", ""","durationMs":1545""")),
        )
        assertEquals(emptyList<SessionEvent>(), fold(turn("t1", "completed")))
    }

    @Test
    fun `a cancelled turn ends as a user cancel`() {
        fold(turn("t1", "running"))

        assertEquals(
            listOf(SessionEvent.TurnEnded("cancelled", interruptReason = "user_cancelled")),
            fold(turn("t1", "cancelled")),
        )
    }

    @Test
    fun `a failed turn carries its error message as a string or an object`() {
        fold(turn("t1", "running"), turn("t2", "running"))

        val events = fold(
            turn("t1", "failed", ""","error":"[provider.auth_error] no access""""),
            turn("t2", "failed", ""","error":{"code":"provider.auth_error","message":"no access"}"""),
        )

        assertEquals(
            listOf(
                SessionEvent.TurnEnded("failed", interruptReason = "error", errorMessage = "[provider.auth_error] no access"),
                SessionEvent.TurnEnded("failed", interruptReason = "error", errorMessage = "no access"),
            ),
            events,
        )
    }

    @Test
    fun `a turn first seen terminal still ends`() {
        // Subscribed mid-turn: the start was before the subscription.
        assertEquals(listOf(SessionEvent.TurnEnded("completed")), fold(turn("t9", "completed")))
    }

    // --- step.upsert -------------------------------------------------------------------

    @Test
    fun `step upserts map running to started and completed or interrupted to completed`() {
        val events = fold(
            step("t1", "t1.1", "running"),
            step("t1", "t1.1", "running"),
            step("t1", "t1.1", "completed", ""","finishReason":"end_turn""""),
            step("t1", "t1.2", "running"),
            step("t1", "t1.2", "interrupted", ""","endReason":"error","endMessage":"[provider.auth_error] …""""),
        )

        assertEquals(
            listOf(
                SessionEvent.StepStarted(1, "t1.1"),
                SessionEvent.StepCompleted(1),
                SessionEvent.StepStarted(1, "t1.2"),
                SessionEvent.StepCompleted(1),
            ),
            events,
        )
    }

    @Test
    fun `a running step with a retry object opens the retry status`() {
        val events = fold(
            step("t1", "t1.1", "running", ""","retry":{"nextAttempt":2,"maxAttempts":3,"errorMessage":"overloaded"}"""),
        )

        assertEquals(
            listOf(SessionEvent.StepStarted(1, "t1.1"), SessionEvent.StepRetrying(1, 2, 3, "overloaded")),
            events,
        )
    }

    @Test
    fun `an unreadable retry object emits no retry status`() {
        assertEquals(
            listOf(SessionEvent.StepStarted(1, "t1.1")),
            fold(step("t1", "t1.1", "running", ""","retry":{"shape":"unknown"}""")),
        )
    }

    // --- frame.upsert / append: text and thinking ---------------------------------------

    @Test
    fun `assistant text streams as deltas and the final upsert adds nothing new`() {
        // The verbatim delta-probe round: an empty frame, two appends, the final upsert.
        val events = fold(textFrame("t1.1.f2", ""), append("t1.1.f2", 0, "delta")) +
            fold(append("t1.1.f2", 5, "-pro")) +
            fold(textFrame("t1.1.f2", "delta-probe"))

        assertEquals(
            listOf(
                SessionEvent.AssistantDelta("delta"),
                SessionEvent.AssistantDelta("-pro"),
                SessionEvent.AssistantDelta("be"),
            ),
            events,
        )
    }

    @Test
    fun `thinking frames stream as thinking deltas`() {
        val events = fold(textFrame("t1.1.f1", "Let me", kind = "thinking"), append("t1.1.f1", 6, " think"))

        assertEquals(
            listOf(SessionEvent.ThinkingDelta("Let me"), SessionEvent.ThinkingDelta(" think")),
            events,
        )
    }

    @Test
    fun `duplicate and overlapping appends emit only the unseen suffix`() {
        fold(textFrame("f", "abc"))

        val events = fold(append("f", 0, "abc"), append("f", 1, "bcde"), append("f", 5, "f"))

        assertEquals(listOf(SessionEvent.AssistantDelta("de"), SessionEvent.AssistantDelta("f")), events)
    }

    @Test
    fun `an append past the local end is a gap and drops`() {
        fold(textFrame("f", "abc"))

        assertEquals(emptyList<SessionEvent>(), fold(append("f", 7, "late")))
        // The stream resumes once the offsets line up again.
        assertEquals(listOf(SessionEvent.AssistantDelta("d")), fold(append("f", 3, "d")))
    }

    @Test
    fun `an append to an unknown frame drops`() {
        assertEquals(emptyList<SessionEvent>(), fold(append("never-upserted", 0, "x")))
    }

    @Test
    fun `a re-sent frame emits nothing and a rewritten one replaces the accumulated text`() {
        fold(textFrame("f", "hello"))

        assertEquals(emptyList<SessionEvent>(), fold(textFrame("f", "hello")))
        // A rewrite is no delta; later appends build on the server's text.
        assertEquals(emptyList<SessionEvent>(), fold(textFrame("f", "HELLO")))
        assertEquals(listOf(SessionEvent.AssistantDelta("!")), fold(append("f", 5, "!")))
    }

    @Test
    fun `user-role text frames and their appends render nothing`() {
        // The steered-in text: the sent bubbles ride the undo marker instead.
        val events = fold(textFrame("t1.1.f3", "also do X", role = "user"), append("t1.1.f3", 9, "!"))

        assertEquals(emptyList<SessionEvent>(), events)
    }

    // --- frame.upsert: tool ------------------------------------------------------------

    @Test
    fun `a tool frame starts, gains its arguments, and settles exactly once`() {
        val running = fold(toolFrame("running", ""","inputText":"{\"comm""""))
        val withInput = fold(
            toolFrame(
                "running",
                ""","input":{"command":"ls -la"},"inputText":"{\"command\":\"ls -la\"}",""" +
                    """"display":{"kind":"command","command":"ls -la","cwd":"/srv","language":"bash"}""",
            ),
        )
        val unchanged = fold(
            toolFrame(
                "running",
                ""","input":{"command":"ls -la"},"display":{"kind":"command","command":"ls -la"},"approvalId":"approval_1"""",
            ),
        )
        val done = fold(toolFrame("done", ""","input":{"command":"ls -la"},"output":"a\nb\n""""))
        val late = fold(toolFrame("done", ""","input":{"command":"ls -la"},"output":"a\nb\n""""))

        // A partial inputText renders no arguments: the row's preview must not lock
        // onto a fragment.
        assertEquals(listOf(SessionEvent.ToolCallStarted("call_1", "Bash")), running)
        val args = buildJsonObject { put("command", "ls -la") }
        assertEquals(
            listOf(
                SessionEvent.ToolCallStarted(
                    "call_1", "Bash", preview = "ls -la", input = "{\n    \"command\": \"ls -la\"\n}", args = args,
                ),
            ),
            withInput,
        )
        assertEquals(emptyList<SessionEvent>(), unchanged)
        assertEquals(listOf(SessionEvent.ToolCallResult("call_1", false, "a\nb\n")), done)
        assertEquals(emptyList<SessionEvent>(), late)
    }

    @Test
    fun `a tool frame first seen done starts and settles in one go`() {
        val events = fold(toolFrame("error", ""","input":{"path":"/nope"},"output":"No such file"""", name = "Read"))

        val args = buildJsonObject { put("path", "/nope") }
        assertEquals(
            listOf(
                SessionEvent.ToolCallStarted(
                    "call_1", "Read", preview = ToolPreview.of("Read", args), input = "{\n    \"path\": \"/nope\"\n}", args = args,
                ),
                SessionEvent.ToolCallResult("call_1", true, "No such file"),
            ),
            events,
        )
    }

    @Test
    fun `a complete inputText parses into the arguments when no input object exists`() {
        val events = fold(toolFrame("running", ""","inputText":"{\"command\":\"pwd\"}""""))

        val started = events.single() as SessionEvent.ToolCallStarted
        assertEquals(buildJsonObject { put("command", "pwd") }, started.args)
        assertEquals("pwd", started.preview)
    }

    @Test
    fun `an agent_call display becomes the spawn row's model`() {
        val events = fold(
            toolFrame(
                "running",
                ""","input":{"prompt":"look around"},""" +
                    """"display":{"kind":"agent_call","agent_name":"explore","prompt":"look around","background":true}""",
                name = "Agent",
            ),
        )

        assertEquals(AgentCallDisplay("explore", "look around", true), (events.single() as SessionEvent.ToolCallStarted).agentCall)
    }

    // --- marker.upsert -----------------------------------------------------------------

    @Test
    fun `an undo marker splices its user messages and drops injections`() {
        val events = fold(
            undoMarker(
                "live-m4",
                """{"role":"user","content":[{"type":"text","text":"Reply with exactly: delta-probe"}],"id":"msg_1","origin":{"kind":"user"}},""" +
                    """{"role":"user","content":[{"type":"text","text":"The user interrupted you."}],"origin":{"kind":"injection","variant":"interruption"}}""",
            ),
        )

        val splice = events.single() as SessionEvent.ContextSpliced
        assertEquals(listOf("msg_1"), splice.messages.map { it.id })
        assertEquals(ChatMessage.Role.User, splice.messages.single().role)
        assertEquals(listOf(ChatMessage.Part.Text("Reply with exactly: delta-probe")), splice.messages.single().parts)
    }

    @Test
    fun `a replayed undo marker is not spliced twice, and other markers are ignored`() {
        val marker = undoMarker("live-m4", """{"role":"user","content":[{"type":"text","text":"hi"}],"id":"msg_1"}""")
        fold(marker)

        val events = fold(
            marker,
            """{"op":"marker.upsert","item":{"kind":"marker","markerId":"live-m5","marker":"compaction",""" +
                """"payload":{"phase":"started","agentId":"main","trigger":"manual"}}}""",
        )

        assertEquals(emptyList<SessionEvent>(), events)
    }

    @Test
    fun `an injection-only undo marker emits nothing`() {
        val events = fold(
            undoMarker("m", """{"role":"user","content":[{"type":"text","text":"x"}],"origin":{"kind":"injection"}}"""),
        )

        assertEquals(emptyList<SessionEvent>(), events)
    }

    // --- the remaining ops, agents, robustness -----------------------------------------

    @Test
    fun `interaction, attachment and removal ops emit nothing`() {
        val events = fold(
            """{"op":"interaction.upsert","interaction":{"interactionId":"approval_1","interactionKind":"approval",""" +
                """"toolCallId":"call_1","state":"pending","request":{"tool_name":"Bash"}}}""",
            """{"op":"attachment.upsert","attachment":{"attachmentId":"att_1","mediaType":"image/png"}}""",
            """{"op":"items.remove","ids":["t0"]}""",
            """{"op":"some.future.op"}""",
        )

        assertEquals(emptyList<SessionEvent>(), events)
    }

    @Test
    fun `a malformed op drops alone, never its batch`() {
        val events = fold(
            """{"op":"turn.upsert","turn":{"turnId":["not","a","string"]}}""",
            turn("t1", "running"),
        )

        assertEquals(listOf(SessionEvent.TurnStarted()), events)
    }

    @Test
    fun `events carry the batch's agent and agents keep separate state`() {
        val sub = fold(turn("t0", "running"), textFrame("t0.1.f1", "sub text", turnId = "t0"), agentId = "agent-0")
        val main = fold(turn("t0", "running"), textFrame("t0.1.f1", "main text", turnId = "t0"))

        assertEquals(
            listOf(SessionEvent.TurnStarted("agent-0"), SessionEvent.AssistantDelta("sub text", "agent-0")),
            sub,
        )
        assertEquals(listOf(SessionEvent.TurnStarted(), SessionEvent.AssistantDelta("main text")), main)
        assertTrue(sub.all { it.isSubagentScoped() })
        assertTrue(main.none { it.isSubagentScoped() })
    }

    @Test
    fun `an abort ends the in-flight entities from the ops alone`() {
        fold(prompt("msg_1", "running"), turn("t1", "running"), step("t1", "t1.1", "running"), toolFrame("running"))

        // The captured abort: step interrupted, turn cancelled, prompt aborted.
        val events = fold(step("t1", "t1.1", "interrupted"), turn("t1", "cancelled")) +
            fold(prompt("msg_1", "aborted"))

        assertEquals(
            listOf(
                SessionEvent.StepCompleted(1),
                SessionEvent.TurnEnded("cancelled", interruptReason = "user_cancelled"),
                SessionEvent.PromptUpdated("msg_1", PromptStatus.Aborted),
            ),
            events,
        )
    }

    @Test
    fun `ops of an ended turn are ignored`() {
        fold(turn("t1", "running"), textFrame("f", "abc"))
        fold(turn("t1", "completed"))

        val events = fold(append("f", 3, "late"), textFrame("f2", "late frame"), step("t1", "t1.1", "running"))

        assertEquals(emptyList<SessionEvent>(), events)
    }

    @Test
    fun `a turn's error text is absent on a failed turn without one`() {
        fold(turn("t1", "running"))

        val ended = fold(turn("t1", "failed")).single() as SessionEvent.TurnEnded

        assertNull(ended.errorMessage)
        assertEquals("error", ended.interruptReason)
    }
}
