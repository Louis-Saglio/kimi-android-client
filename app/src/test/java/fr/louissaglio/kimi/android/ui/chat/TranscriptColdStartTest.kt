package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.MAIN_AGENT_ID
import fr.louissaglio.kimi.android.testutil.TranscriptFrames
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript cold start (ticket 176 — server 2.5.0 removed `/snapshot`) on the
 * shared [ChatViewModelTestBase] harness: `GET .../messages`, `GET .../transcript?
 * agent_id=main` and `GET .../prompts` rebuild the chat before the first subscribe —
 * history, the running turn's live rows, the working state, the pending cards and the
 * queued prompts — and the transcript's `seq` becomes the `transcript_since` watermark
 * every subscribe sends back.
 */
class TranscriptColdStartTest : ChatViewModelTestBase() {

    /** A completed history page: prompt, call, result, reply (`/messages` wire order is
     * newest-first). */
    private val completedHistory = """{"code":0,"msg":"success","data":{"items":[""" +
        """{"id":"msg_4","session_id":"session_1","role":"assistant",""" +
        """"content":[{"type":"text","text":"all done"}],"created_at":"2026-11-04T10:00:03.000Z"},""" +
        """{"id":"msg_3","session_id":"session_1","role":"tool",""" +
        """"content":[{"type":"tool_result","tool_call_id":"tool_1","output":"ok"}],"created_at":"2026-11-04T10:00:02.000Z"},""" +
        """{"id":"msg_2","session_id":"session_1","role":"assistant",""" +
        """"content":[{"type":"text","text":"running it"},{"type":"tool_use","tool_call_id":"tool_1",""" +
        """"tool_name":"Edit","input":{"path":"docs/server-api.md"}}],"created_at":"2026-11-04T10:00:01.000Z"},""" +
        """{"id":"msg_1","session_id":"session_1","role":"user",""" +
        """"content":[{"type":"text","text":"run it"}],"created_at":"2026-11-04T10:00:00.000Z"}""" +
        """],"has_more":false},"request_id":"r"}"""

    /** The user prompt of a running turn, committed to history at turn start. */
    private val runningHistory = """{"code":0,"msg":"success","data":{"items":[""" +
        """{"id":"msg_1","session_id":"session_1","role":"user",""" +
        """"content":[{"type":"text","text":"run sleep 60"}],"created_at":"2026-11-04T10:00:00.000Z"}""" +
        """],"has_more":false},"request_id":"r"}"""

    /** A running turn `t1` (the verbatim warm shape, docs/server-api.md, Transcript):
     * thinking + streamed text, then a running Bash call. */
    private val runningTurn = """[{"kind":"turn","turnId":"t1","ordinal":1,"state":"running",""" +
        """"origin":{"kind":"user","payload":{"kind":"user"}},"prompt":"run sleep 60",""" +
        """"steps":[{"kind":"step","stepId":"t1.1","turnId":"t1","ordinal":1,"state":"running","frames":[""" +
        """{"kind":"thinking","frameId":"t1.1.f1","text":"Let me see."},""" +
        """{"kind":"text","frameId":"t1.1.f2","role":"assistant","text":"Working on it."},""" +
        """{"kind":"tool","frameId":"t1.1.tool_bash","toolCallId":"tool_bash","name":"Bash","state":"running",""" +
        """"input":{"command":"sleep 60","timeout":90},"inputText":"{\"command\":\"sleep 60\",\"timeout\":90}",""" +
        """"display":{"kind":"command","command":"sleep 60","cwd":"/tmp","language":"bash"}}""" +
        """]}]}]"""

    private fun drainRequests(windowMs: Long = 500): List<String> {
        val paths = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            server.takeRequest(100, TimeUnit.MILLISECONDS)?.let { paths += it.target.orEmpty() }
        }
        return paths
    }

    private fun subscribeFrames(): List<String> = clientFrames.filter { "\"type\":\"subscribe_v2\"" in it }

    @Test
    fun `a completed session renders its history from the three cold-start reads with no snapshot request`() = runBlocking {
        messagesBody = completedHistory

        viewModel.onScreenStarted()

        // Chronological order, the result joined to its call.
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_1", "msg_2", "tool_1", "msg_4") }
        val tool = viewModel.uiState.value.items!!.filterIsInstance<ChatItem.ToolGroup>().single().tools.single()
        assertEquals(ToolStatus.Done, tool.status)
        assertEquals("ok", tool.output)
        assertFalse(viewModel.uiState.value.isWorking)

        val paths = drainRequests()
        assertTrue(paths.toString(), paths.none { "/snapshot" in it })
        assertTrue(paths.toString(), paths.any { it.startsWith("/api/v1/sessions/session_1/messages") })
        assertTrue(paths.toString(), paths.any { it.startsWith("/api/v1/sessions/session_1/transcript?agent_id=main") })
        assertTrue(paths.toString(), paths.any { it == "/api/v1/sessions/session_1/prompts" })
    }

    @Test
    fun `a mid-turn cold open shows the running turn's rows and the working state`() = runBlocking {
        messagesBody = runningHistory
        transcriptBody = transcriptData(items = runningTurn, seq = 42)

        viewModel.onScreenStarted()

        // History, then the running turn's thinking+text bubble, then the running tool.
        awaitState(viewModel.uiState) { state ->
            state.items?.map(ChatItem::id) == listOf("msg_1", "live_0", "tool_bash")
        }
        val items = viewModel.uiState.value.items!!
        val bubble = items.filterIsInstance<ChatItem.AssistantMessage>().single()
        assertEquals("Working on it.", bubble.text)
        assertEquals("Let me see.", bubble.thinking)
        val tool = items.filterIsInstance<ChatItem.ToolGroup>().single().tools.single()
        assertEquals("Bash", tool.name)
        assertEquals(ToolStatus.Running, tool.status)
        assertEquals("sleep 60", tool.preview)
        assertTrue(viewModel.uiState.value.isWorking)
    }

    @Test
    fun `a mid-turn cold open's running tool names the status line once the agent frame says running`() = runBlocking {
        // Ticket 177: the status line's sub-phase is derived from the transcript — the
        // seeded running tool frame — and gated by the subscribe's `agent` frame.
        messagesBody = runningHistory
        transcriptBody = transcriptData(items = runningTurn, seq = 42)

        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { state -> state.items?.map(ChatItem::id) == listOf("msg_1", "live_0", "tool_bash") }
        assertNull(viewModel.uiState.value.activityStatus)

        socket.send(TranscriptFrames.agentFrame(status = "running"))

        awaitState(viewModel.uiState) { it.activityStatus?.text == "Running Bash…" }
    }

    @Test
    fun `live ops after the watermark extend the seeded rows without a gap or a duplicate`() = runBlocking {
        messagesBody = runningHistory
        transcriptBody = transcriptData(items = runningTurn, seq = 42)

        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { state -> state.items?.map(ChatItem::id) == listOf("msg_1", "live_0", "tool_bash") }

        // The replay after seq 42: the turn header re-sent unchanged (no TurnStarted —
        // that would clear the seeded rows), the seeded tool settling in place, then a
        // new text frame streaming on (upsert, then an append at its exact end).
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.turnOp("t1", "running"), seq = 43))
        socket.send(
            TranscriptFrames.opsFrame(
                TranscriptFrames.toolOp(
                    "tool_bash", "Bash", "done",
                    ""","input":{"command":"sleep 60","timeout":90},"output":"slept"""",
                ),
                seq = 44,
            ),
        )
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.textOp("t1.1.f3", "Done"), seq = 45))
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.appendOp("t1.1.f3", offset = 4, text = "!"), seq = 46))

        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.lastOrNull()?.text == "Done!"
        }
        val items = viewModel.uiState.value.items!!
        assertEquals(listOf("msg_1", "live_0", "tool_bash", "live_1"), items.map(ChatItem::id))
        assertEquals(
            listOf("Working on it.", "Done!"),
            items.filterIsInstance<ChatItem.AssistantMessage>().map { it.text },
        )
        val tool = items.filterIsInstance<ChatItem.ToolGroup>().single().tools.single()
        assertEquals(ToolStatus.Done, tool.status)
        assertEquals("slept", tool.output)
        assertTrue(viewModel.uiState.value.isWorking)
    }

    @Test
    fun `a replayed append continues the seeded text frame at its end`() = runBlocking {
        // The running turn's last frame is the streaming text: the replay's next chunk
        // lands at the seeded length — the fold knows it, so it extends the bubble
        // instead of reading as a gap.
        messagesBody = runningHistory
        transcriptBody = transcriptData(
            items = """[{"kind":"turn","turnId":"t1","ordinal":1,"state":"running",""" +
                """"origin":{"kind":"user"},"steps":[{"kind":"step","stepId":"t1.1","turnId":"t1","ordinal":1,""" +
                """"state":"running","frames":[{"kind":"text","frameId":"t1.1.f1","role":"assistant","text":"Hel"}]}]}]""",
            seq = 5,
        )

        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { state -> state.items?.map(ChatItem::id) == listOf("msg_1", "live_0") }

        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.appendOp("t1.1.f1", offset = 3, text = "lo"), seq = 6))

        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.singleOrNull()?.text == "Hello"
        }
        assertEquals(listOf("msg_1", "live_0"), viewModel.uiState.value.items!!.map(ChatItem::id))
    }

    @Test
    fun `history already committing the running turn's steps is not duplicated by the seed`() = runBlocking {
        // A finished step of the running turn may already be in /messages: its text
        // bubble and its tool row must render once, the seed only adding what history
        // lacks (here the running step's text).
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_3","session_id":"session_1","role":"tool",""" +
            """"content":[{"type":"tool_result","tool_call_id":"tool_ls","output":"a.txt"}],"created_at":"2026-11-04T10:00:02.000Z"},""" +
            """{"id":"msg_2","session_id":"session_1","role":"assistant",""" +
            """"content":[{"type":"text","text":"Listing."},{"type":"tool_use","tool_call_id":"tool_ls",""" +
            """"tool_name":"Bash","input":{"command":"ls"}}],"created_at":"2026-11-04T10:00:01.000Z"},""" +
            """{"id":"msg_1","session_id":"session_1","role":"user",""" +
            """"content":[{"type":"text","text":"list"}],"created_at":"2026-11-04T10:00:00.000Z"}""" +
            """],"has_more":false},"request_id":"r"}"""
        transcriptBody = transcriptData(
            items = """[{"kind":"turn","turnId":"t1","ordinal":1,"state":"running",""" +
                """"origin":{"kind":"user"},"prompt":"list","steps":[""" +
                """{"kind":"step","stepId":"t1.1","turnId":"t1","ordinal":1,"state":"completed","frames":[""" +
                """{"kind":"text","frameId":"t1.1.f1","role":"assistant","text":"Listing."},""" +
                """{"kind":"tool","frameId":"t1.1.tool_ls","toolCallId":"tool_ls","name":"Bash","state":"done",""" +
                """"input":{"command":"ls"},"output":"a.txt"}]},""" +
                """{"kind":"step","stepId":"t1.2","turnId":"t1","ordinal":2,"state":"running","frames":[""" +
                """{"kind":"text","frameId":"t1.2.f1","role":"assistant","text":"Found one file"}]}]}]""",
            seq = 9,
        )

        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { state ->
            state.items?.map(ChatItem::id) == listOf("msg_1", "msg_2", "tool_ls", "live_1")
        }
        assertEquals(
            listOf("Listing.", "Found one file"),
            viewModel.uiState.value.items!!.filterIsInstance<ChatItem.AssistantMessage>().map { it.text },
        )
    }

    @Test
    fun `a pending approval and a pending question each seed exactly one card from the transcript`() = runBlocking {
        // The REST transcript's interactions (docs/server-api.md, Pending state on REST
        // — verified 2.5.1): the approval request is camelCase, the question request
        // keeps the snake_case question payload. An ended interaction stays out.
        transcriptBody = transcriptData(
            interactions = """[""" +
                """{"interactionId":"approval_1","interactionKind":"approval","toolCallId":"call_1","state":"pending",""" +
                """"request":{"id":"approval_1","sessionId":"session_1","agentId":"main","turnId":"t1",""" +
                """"toolCallId":"call_1","toolName":"Bash","action":"Running: rm -rf target",""" +
                """"display":{"kind":"command","command":"rm -rf target","cwd":"/tmp","language":"bash"}}},""" +
                """{"interactionId":"approval_0","interactionKind":"approval","toolCallId":"call_0","state":"approved",""" +
                """"request":{"toolName":"Bash","action":"Running: ls"},"response":{"decision":"approved"}},""" +
                """{"interactionId":"question_1","interactionKind":"question","toolCallId":"call_q","state":"pending",""" +
                """"request":{"question_id":"question_1","tool_call_id":"call_q","created_at":"2026-11-05T10:00:00.000Z",""" +
                """"turn_id":"t1","questions":[{"id":"q_0","question":"Cake or pie?","header":"Dessert",""" +
                """"options":[{"id":"opt_0_0","label":"Cake"},{"id":"opt_0_1","label":"Pie"}],"allow_other":true}]}}""" +
                """]""",
            pendingInteractions = """["approval_1","question_1"]""",
        )

        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { it.pendingApprovals.isNotEmpty() && it.pendingQuestions.isNotEmpty() }
        assertEquals(
            listOf(ChatViewModel.PendingApproval("approval_1", "Bash", "rm -rf target", MAIN_AGENT_ID)),
            viewModel.uiState.value.pendingApprovals,
        )
        val question = viewModel.uiState.value.pendingQuestions.single()
        assertEquals("question_1", question.questionId)
        assertEquals("call_q", question.toolCallId)
        assertEquals("Cake or pie?", question.items.single().text)
        assertEquals(listOf("Cake", "Pie"), question.items.single().options.map { it.label })
    }

    @Test
    fun `a cold open sees one pending approval three times - transcript, reset, re-sent frame - and shows one card`() = runBlocking {
        // Ticket 171: the REST transcript seeds the card, the subscribe's reset snapshot
        // carries the same entity, and the ack is followed by its standalone re-send.
        val entity = TranscriptFrames.approvalEntity("approval_1", command = "rm -rf target")
        transcriptBody = transcriptData(interactions = "[$entity]", pendingInteractions = """["approval_1"]""")

        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.pendingApprovals.isNotEmpty() }
        socket.send(TranscriptFrames.resetFrame(seq = 2, interactions = "[$entity]"))
        socket.send(TranscriptFrames.ackFrame())
        socket.send(TranscriptFrames.approvalFrame("approval_1", command = "rm -rf target"))
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.promptOp("msg_9", "completed"), seq = 3))
        // A trailing sentinel on the same socket: once its job shows, every frame above
        // was applied.
        socket.send(TranscriptFrames.taskFrame(TranscriptFrames.taskState("bash-sentinel")))
        awaitState(viewModel.uiState) { it.jobs.isNotEmpty() }

        assertEquals(
            listOf(ChatViewModel.PendingApproval("approval_1", "Bash", "rm -rf target", MAIN_AGENT_ID)),
            viewModel.uiState.value.pendingApprovals,
        )
    }

    @Test
    fun `a queued prompt is visible on entry from the prompt queue`() = runBlocking {
        messagesBody = runningHistory
        transcriptBody = transcriptData(items = runningTurn, seq = 42)
        promptQueueBody = """{"code":0,"msg":"success","data":{""" +
            """"active":{"prompt_id":"msg_1","user_message_id":"msg_1","status":"running",""" +
            """"content":[{"type":"text","text":"run sleep 60"}],"created_at":"2026-11-04T10:00:00.000Z"},""" +
            """"queued":[{"prompt_id":"msg_q","user_message_id":"msg_q","status":"queued",""" +
            """"content":[{"type":"text","text":"Reply with exactly: steered-ok"}],""" +
            """"created_at":"2026-11-04T10:00:05.000Z"}]},"request_id":"r"}"""

        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { state -> state.items?.any { it.id == "msg_q" } == true }
        val queued = viewModel.uiState.value.items!!.single { it.id == "msg_q" } as ChatItem.UserMessage
        assertEquals("Reply with exactly: steered-ok", queued.text)
        assertEquals(1, queued.queued)
        // The active prompt is the running turn's own: it is history, never a second bubble.
        assertEquals(1, viewModel.uiState.value.items!!.count { it.id == "msg_1" })
    }

    @Test
    fun `the queued bubble clears when its prompt starts and heals from the splice`() = runBlocking {
        promptQueueBody = """{"code":0,"msg":"success","data":{"active":null,""" +
            """"queued":[{"prompt_id":"msg_q","user_message_id":"msg_q","status":"queued",""" +
            """"content":[{"type":"text","text":"next one"}],"created_at":"2026-11-04T10:00:05.000Z"}]},"request_id":"r"}"""
        transcriptBody = transcriptData(
            prompts = """[{"promptId":"msg_q","status":"queued","userMessageId":"msg_q",""" +
                """"content":[{"type":"text","text":"next one"}],"createdAt":"2026-11-04T10:00:05.000Z"}]""",
        )

        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { state -> (state.items?.singleOrNull() as? ChatItem.UserMessage)?.queued == 1 }

        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.promptOp("msg_q", "running"), seq = 2))
        socket.send(contextSplicedFrame("msg_q", "next one"))

        awaitState(viewModel.uiState) { state ->
            (state.items?.singleOrNull() as? ChatItem.UserMessage)?.let { it.id == "msg_q" && it.queued == null } == true
        }
    }

    @Test
    fun `the first subscribe resumes from the transcript watermark`() = runBlocking {
        transcriptBody = transcriptData(seq = 42)

        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitCondition { subscribeFrames().isNotEmpty() }

        assertTrue(subscribeFrames().first(), "\"transcript_since\":{\"main\":42}" in subscribeFrames().first())
    }

    @Test
    fun `a re-subscribe carries the watermark of main and of every child seen live`() = runBlocking {
        transcriptBody = transcriptData(
            agents = """[{"agentId":"main","type":"main"},{"agentId":"agent-12","type":"sub","parentAgentId":"main"}]""",
            seq = 42,
        )

        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitCondition { subscribeFrames().size == 1 }
        // The child never streamed on this process: the first subscribe resumes main
        // only — the child takes the server's baseline reset, which carries its seq.
        assertFalse(subscribeFrames().first(), "agent-12" in subscribeFrames().first())
        socket.send(TranscriptFrames.resetFrame(agentId = "agent-12", seq = 3))
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.turnOp("t1", "running"), seq = 43))
        socket.send(TranscriptFrames.opsFrame(subTurnOp(), agentId = "agent-12", seq = 7))
        awaitCondition { chatRepository.transcriptWatermark("session_1", "agent-12") == 7L }

        viewModel.onScreenStopped()
        serverSocket = null
        viewModel.onScreenStarted()
        awaitCondition { subscribeFrames().size == 2 }

        val resume = subscribeFrames()[1]
        assertTrue(resume, "\"transcript_since\":{" in resume)
        assertTrue(resume, "\"main\":43" in resume)
        assertTrue(resume, "\"agent-12\":7" in resume)
    }

    @Test
    fun `a journal that cannot cover the watermark triggers a full transcript refetch`() = runBlocking {
        transcriptBody = transcriptData(seq = 42)
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
        viewModel.onScreenStopped()

        // While away a turn started and the journal was trimmed past seq 42.
        catchUpBody = """{"code":0,"msg":"success","data":{"agent_id":"main","batches":[],""" +
            """"latest_seq":90,"complete":false},"request_id":"r"}"""
        messagesBody = runningHistory
        transcriptBody = transcriptData(items = runningTurn, seq = 90)
        serverSocket = null
        viewModel.onScreenStarted()

        awaitRequest("/transcript/ops?agent_id=main&since_seq=42")
        awaitRequest("/transcript?agent_id=main")
        awaitState(viewModel.uiState) { state -> state.items?.map(ChatItem::id) == listOf("msg_1", "live_0", "tool_bash") }
        assertTrue(viewModel.uiState.value.isWorking)
        assertEquals(90L, chatRepository.transcriptWatermark("session_1", MAIN_AGENT_ID))
    }

    @Test
    fun `a covered journal resumes from the replay alone`() = runBlocking {
        transcriptBody = transcriptData(seq = 42)
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
        viewModel.onScreenStopped()

        serverSocket = null
        viewModel.onScreenStarted()
        awaitServerSocket()

        val paths = drainRequests()
        assertTrue(paths.toString(), paths.any { "/transcript/ops?agent_id=main&since_seq=42" in it })
        // complete:true → no second cold start: the subscribe's replay heals the gap.
        assertTrue(paths.toString(), paths.none { "/transcript?agent_id=main" in it })
        assertTrue(paths.toString(), paths.none { "/snapshot" in it })
    }

    @Test
    fun `a turn that ended while away is replayed and leaves no ghost live rows`() = runBlocking {
        // Ticket 048's report on the 2.5.0 resume: the stream is watched, the app
        // backgrounds mid-turn, the agent finishes while away. The re-subscribe's
        // watermark makes the server replay the turn end, whose refetch commits it.
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.turnOp("t1", "running"), seq = 2))
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.textOp("t1.1.f1", "partial"), seq = 3))
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.singleOrNull()?.text == "partial"
        }
        awaitCondition { chatRepository.transcriptWatermark("session_1", MAIN_AGENT_ID) == 3L }
        viewModel.onScreenStopped()

        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_1","session_id":"session_1","role":"assistant",""" +
            """"content":[{"type":"text","text":"partial plus the rest"}],""" +
            """"created_at":"2026-07-29T00:00:01.000Z"}],"has_more":false},"request_id":"r"}"""
        serverSocket = null
        viewModel.onScreenStarted()
        val resumed = awaitServerSocket()
        awaitCondition { subscribeFrames().size == 2 }
        assertTrue(subscribeFrames()[1], "\"main\":3" in subscribeFrames()[1])

        // The replay of what the gap missed: the rest of the text, then the turn end.
        resumed.send(TranscriptFrames.opsFrame(TranscriptFrames.appendOp("t1.1.f1", 7, " plus the rest"), seq = 4))
        resumed.send(TranscriptFrames.opsFrame(TranscriptFrames.turnOp("t1", "completed"), seq = 5))

        awaitState(viewModel.uiState, timeoutMs = 15_000) { state ->
            state.items?.map(ChatItem::id) == listOf("msg_1") && !state.isWorking
        }
    }

    @Test
    fun `a reset answering the watermark rebuilds the chat from the transcript`() = runBlocking {
        transcriptBody = transcriptData(seq = 42)
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        // The server could not replay from 42: a fresh baseline instead of the batches.
        messagesBody = runningHistory
        transcriptBody = transcriptData(items = runningTurn, seq = 77)
        socket.send(TranscriptFrames.resetFrame(agentId = MAIN_AGENT_ID, seq = 77))

        awaitRequest("/transcript?agent_id=main")
        awaitState(viewModel.uiState) { state -> state.items?.map(ChatItem::id) == listOf("msg_1", "live_0", "tool_bash") }
        assertTrue(viewModel.uiState.value.isWorking)
        assertEquals(77L, chatRepository.transcriptWatermark("session_1", MAIN_AGENT_ID))
    }

    @Test
    fun `a cold-opened Agent spawn row links to the sub-agent page`() = runBlocking {
        // Mining preserved (ticket 091 on the 176 carriers): a settled spawn whose
        // result carries the agent_id: marker — here still inside the running turn,
        // not yet committed to history — links its row and seeds the store record.
        transcriptBody = transcriptData(
            items = """[{"kind":"turn","turnId":"t1","ordinal":1,"state":"running",""" +
                """"origin":{"kind":"user"},"steps":[{"kind":"step","stepId":"t1.1","turnId":"t1","ordinal":1,""" +
                """"state":"running","frames":[{"kind":"tool","frameId":"t1.1.tool_agent","toolCallId":"tool_agent",""" +
                """"name":"Agent","state":"done",""" +
                """"input":{"subagent_type":"explore","description":"doc-verify","prompt":"Reply ok"},""" +
                """"display":{"kind":"agent_call","agent_name":"explore","prompt":"Reply ok","background":false},""" +
                """"output":"agent_id: agent-12\n\nok"}]}]}]""",
            agents = """[{"agentId":"main","type":"main"},{"agentId":"agent-12","type":"sub","parentAgentId":"main"}]""",
        )

        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.ToolGroup>()?.flatMap { it.tools }
                ?.any { it.id == "tool_agent" && it.subagentId == "agent-12" } == true
        }
        val row = viewModel.uiState.value.items!!.filterIsInstance<ChatItem.ToolGroup>().single().tools.single()
        assertEquals("explore", row.agentCall?.agentName)
        val record = subagentStore.entry("session_1", "agent-12").first()
        assertNotNull(record)
        assertEquals(listOf(ChatItem.UserMessage("seeded_prompt", "Reply ok")), record!!.items)
    }

    @Test
    fun `the transcript roster creates a record for every child agent`() = runBlocking {
        transcriptBody = transcriptData(
            agents = """[{"agentId":"main","type":"main"},{"agentId":"agent-3","type":"sub","parentAgentId":"main"}]""",
        )

        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.items != null }

        assertNotNull(subagentStore.entry("session_1", "agent-3").first())
        assertNull(subagentStore.entry("session_1", "main").first())
    }

    @Test
    fun `a failed cold start surfaces the load error and the retry refetches`() = runBlocking {
        transcriptBody = """{"code":50000,"msg":"boom","data":null,"request_id":"r"}"""

        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { it.error != null }
        assertNull(viewModel.uiState.value.items)
        assertTrue(drainRequests().none { "/snapshot" in it })

        transcriptBody = null
        viewModel.onRetry()
        awaitRequest("/transcript?agent_id=main")
        awaitState(viewModel.uiState) { it.items != null && it.error == null }
    }

    @Test
    fun `a screen stop-start round trip performs no extra cold-start fetch`() = runBlocking {
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onScreenStopped()
        viewModel.onScreenStarted()

        val paths = drainRequests()
        assertTrue(paths.toString(), paths.none { "/transcript?agent_id=main" in it || it.endsWith("/prompts") })
    }

    /** A sub-agent turn header, for the child's own ops batch. */
    private fun subTurnOp(): String = TranscriptFrames.turnOp("t0", "running")
}
