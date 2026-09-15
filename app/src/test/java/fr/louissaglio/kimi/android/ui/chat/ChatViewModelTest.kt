package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.chat.BackgroundTask
import fr.louissaglio.kimi.android.data.chat.ContextUsage
import fr.louissaglio.kimi.android.data.chat.ServerModel
import fr.louissaglio.kimi.android.data.sessions.Session
import fr.louissaglio.kimi.android.navigation.Chat
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import fr.louissaglio.kimi.android.testutil.testChatRepository
import fr.louissaglio.kimi.android.testutil.testSessionsRepository
import fr.louissaglio.kimi.android.testutil.testTodoParser
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chat-screen core coverage: socket → timeline → UiState, approvals, sends, history
 * paging, models, compaction. The attachments and question-wizard coverage lives next
 * to the extracted collaborators (ChatAttachmentsTest, QuestionWizardTest — ticket
 * 072); the shared MockWebServer/socket harness is [ChatViewModelTestBase].
 */
class ChatViewModelTest : ChatViewModelTestBase() {

    @Test
    fun `approval request event shows card and resolved event clears it`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()

        socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: echo hi", command = "echo hi"))
        awaitState(viewModel.uiState) { it.pendingApprovals.isNotEmpty() }
        assertEquals(
            listOf(ChatViewModel.PendingApproval("ap_1", "Bash", "echo hi")),
            viewModel.uiState.value.pendingApprovals,
        )

        // Resolved from another client: the card clears without local action.
        socket.send(approvalResolvedFrame("ap_1"))
        awaitState(viewModel.uiState) { it.pendingApprovals.isEmpty() }
        assertEquals(emptyList<ChatViewModel.PendingApproval>(), viewModel.uiState.value.pendingApprovals)
    }

    @Test
    fun `stream deltas publish the leading edge then coalesce until a substantive event flushes`() = runBlocking {
        val vm = newViewModel(streamCoalesceWindowMillis = 100)
        vm.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(vm.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        val (texts, recorder) = assistantTextRecorder(vm)
        try {
            socket.send(assistantDeltaFrame("Hello", seq = 5))
            // The burst's leading edge renders immediately, without waiting out the window.
            awaitCondition { texts == listOf("Hello") }

            socket.send(assistantDeltaFrame(" world", seq = 6))
            socket.send(assistantDeltaFrame("!", seq = 7))
            // In-order stream: the approval frame is processed after both deltas, and
            // pending text flushes before it applies.
            socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: ls", command = "ls"))
            awaitState(vm.uiState) { it.pendingApprovals.isNotEmpty() }

            // The two trailing deltas became ONE emission, published before the approval.
            // Awaited (not read at an arbitrary instant): the recorder's append races the
            // reader thread's queue drain — see assistantTextRecorder (ticket 049).
            awaitCondition { texts == listOf("Hello", "Hello world!") }
        } finally {
            recorder.cancel()
        }
    }

    @Test
    fun `turn ended flushes the accumulated text exactly before the completion applies`() = runBlocking {
        val vm = newViewModel(streamCoalesceWindowMillis = 100)
        vm.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(vm.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        val (texts, recorder) = assistantTextRecorder(vm)
        try {
            socket.send(turnStartedFrame())
            awaitState(vm.uiState) { it.isWorking }
            socket.send(assistantDeltaFrame("Hello", seq = 5))
            awaitCondition { texts == listOf("Hello") }

            socket.send(assistantDeltaFrame(" world!", seq = 6))
            socket.send(turnEndedFrame(reason = "completed"))
            // The turn-end refetch is issued after the flush: once it has run, the
            // accumulated text was necessarily published first, and in full.
            awaitRequest("/messages")
            awaitState(vm.uiState) { !it.isWorking }

            // Awaited (not read at an arbitrary instant): the recorder's append races the
            // reader thread's queue drain — see assistantTextRecorder (ticket 049).
            awaitCondition { texts == listOf("Hello", "Hello world!") }
        } finally {
            recorder.cancel()
        }
    }

    @Test
    fun `window zero publishes every delta on arrival`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        val (texts, recorder) = assistantTextRecorder(viewModel)
        try {
            socket.send(assistantDeltaFrame("H", seq = 5))
            socket.send(assistantDeltaFrame("e", seq = 6))
            socket.send(assistantDeltaFrame("llo", seq = 7))
            // One emission per delta — the pre-conflation behavior the rest of the
            // suite asserts. Awaited (not read at an arbitrary instant): the recorder's
            // append races the reader thread's queue drain — see assistantTextRecorder
            // (ticket 049).
            awaitCondition { texts == listOf("H", "He", "Hello") }
        } finally {
            recorder.cancel()
        }
    }

    @Test
    fun `step retrying shows a transient status cleared by the replacement step started`() = runBlocking {
        // Ticket 058: a 0.31.0 retry storm (provider 429) must read as "retrying
        // (attempt N of 10)", not as a stalled chat.
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }

        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }

        socket.send(stepRetryingFrame(nextAttempt = 2))
        awaitState(viewModel.uiState) { it.stepRetry != null }
        assertEquals(
            ChatViewModel.StepRetry(
                nextAttempt = 2,
                maxAttempts = 10,
                errorMessage = "429 The engine is currently overloaded, please try again later",
            ),
            viewModel.uiState.value.stepRetry,
        )

        // The retry begins: a fresh turn.step.started re-fires with a NEW stepId for
        // the same step number (docs/server-api.md) and ends the status.
        socket.send(stepStartedFrame(step = 1, stepId = "f1ed9c2a-new"))
        awaitState(viewModel.uiState) { it.stepRetry == null }
        // Neither frame created a timeline row.
        assertEquals(emptyList<ChatItem>(), viewModel.uiState.value.items)
    }

    @Test
    fun `step retrying status clears when the turn ends`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }

        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }
        socket.send(stepRetryingFrame(nextAttempt = 9))
        awaitState(viewModel.uiState) { it.stepRetry != null }

        socket.send(turnEndedFrame(reason = "failed"))
        awaitState(viewModel.uiState) { !it.isWorking }
        assertNull(viewModel.uiState.value.stepRetry)
    }

    @Test
    fun `a turn that ended while the screen was away leaves no ghost live rows`() = runBlocking {
        // Louis's report (ticket 048): the stream is watched, the app backgrounds
        // mid-turn, the agent finishes while away, the app comes back.
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        socket.send(turnStartedFrame())
        socket.send(assistantDeltaFrame("partial", seq = 6))
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.singleOrNull()?.text == "partial"
        }
        viewModel.onScreenStopped()

        // While away, the turn ends: history now carries the complete message.
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_1","session_id":"session_1","role":"assistant",""" +
            """"content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":"partial plus the rest"}],""" +
            """"created_at":"2026-07-29T00:00:01.000Z"}""" +
            """],"has_more":false},"request_id":"r"}"""
        serverSocket = null
        viewModel.onScreenStarted()

        // The re-subscribe resumes with the cursor stored before backgrounding
        // (turnStarted's seq 20 — the volatile delta must not move it): the missed
        // window replays instead of being lost. (The LAST cursors frame: the cold
        // start's first subscribe already carried the snapshot watermark, ticket 085.)
        awaitCondition { clientFrames.any { "\"seq\":20" in it } }
        val resume = clientFrames.last { "\"cursors\"" in it }
        assertTrue(resume, resume.contains("\"seq\":20"))
        assertTrue(resume, resume.contains("\"epoch\":\"ep_1\""))

        // A finished turn can have no live rows: the status-aware resync clears the
        // truncated ghost bubble and the canonical message renders exactly once.
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()
                ?.singleOrNull()?.let { it.id == "msg_1" && it.text == "partial plus the rest" } == true
        }
        assertEquals(
            listOf("msg_1"),
            viewModel.uiState.value.items.orEmpty().map { it.id },
        )
    }

    @Test
    fun `a turn still running on return keeps its live rows`() = runBlocking {
        statusBody = """{"code":0,"msg":"success","data":{"busy":true,"context_tokens":25050,""" +
            """"max_context_tokens":1048576,"context_usage":0.0239},"request_id":"r"}"""
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        socket.send(turnStartedFrame())
        socket.send(assistantDeltaFrame("still streaming", seq = 6))
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.singleOrNull()?.text == "still streaming"
        }
        viewModel.onScreenStopped()

        // The turn is still running: history carries only the user's prompt so far.
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_u","session_id":"session_1","role":"user",""" +
            """"content":[{"type":"text","text":"do the thing"}],""" +
            """"created_at":"2026-07-29T00:00:00.000Z"}""" +
            """],"has_more":false},"request_id":"r"}"""
        serverSocket = null
        viewModel.onScreenStarted()

        // busy:true → keep-live refetch: the committed prompt lands and the
        // in-flight bubble survives (the resumed replay keeps feeding it).
        awaitState(viewModel.uiState) { state ->
            state.items?.any { it is ChatItem.UserMessage } == true
        }
        val bubbles = viewModel.uiState.value.items.orEmpty().filterIsInstance<ChatItem.AssistantMessage>()
        assertEquals(1, bubbles.size)
        assertEquals("still streaming", bubbles.single().text)
    }

    @Test
    fun `a turn event racing the resync status fetch downgrades the clear`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
        socket.send(turnStartedFrame())
        socket.send(assistantDeltaFrame("partial", seq = 6))
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.singleOrNull()?.text == "partial"
        }
        viewModel.onScreenStopped()

        // The first turn ended while away (history carries its full message), but
        // the idle /status snapshot the resync is about to read is overtaken by a
        // NEW turn starting on the resumed socket.
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_1","session_id":"session_1","role":"assistant",""" +
            """"content":[{"type":"text","text":"partial plus the rest"}],""" +
            """"created_at":"2026-07-29T00:00:01.000Z"}""" +
            """],"has_more":false},"request_id":"r"}"""
        statusResponseGate = CountDownLatch(1)
        serverSocket = null
        viewModel.onScreenStarted()

        // The resync's /status GET is parked server-side; the new turn starts and
        // streams its first text — strictly newer than the parked snapshot.
        val socket2 = awaitServerSocket()
        socket2.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }
        socket2.send(assistantDeltaFrame("new turn text", seq = 7))
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.singleOrNull()?.text == "new turn text"
        }

        // Releasing the idle snapshot must NOT clear the new turn's live rows.
        statusResponseGate!!.countDown()
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.any { it.id == "msg_1" } == true
        }
        assertEquals(
            listOf("partial plus the rest", "new turn text"),
            viewModel.uiState.value.items.orEmpty().filterIsInstance<ChatItem.AssistantMessage>().map { it.text },
        )
    }

    @Test
    fun `a resync refetch landing mid burst flushes the pending deltas before replacing history`() = runBlocking {
        // Ticket 062: the REST refetch bypasses the socket dispatcher's flush, so a
        // keep-live response landing mid-burst dedups the committed full bubble
        // against the live leading-edge FRAGMENT (exact-text match), keeps both, and
        // the trailing flush grows the fragment into a duplicate bubble.
        val vm = newViewModel(streamCoalesceWindowMillis = 100)
        vm.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(vm.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        socket.send(turnStartedFrame())
        awaitState(vm.uiState) { it.isWorking }

        // The resync's /status GET parks server-side, so the burst below opens
        // strictly BEFORE the refetch lands; busy=true (a turn is streaming) makes
        // the resync keep-live.
        statusBody = """{"code":0,"msg":"success","data":{"busy":true,"context_tokens":0,""" +
            """"max_context_tokens":1048576,"context_usage":0.0},"request_id":"r"}"""
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_1","session_id":"session_1","role":"assistant",""" +
            """"content":[{"type":"text","text":"Hello world!"}],""" +
            """"created_at":"2026-07-31T00:00:01.000Z"}""" +
            """],"has_more":false},"request_id":"r"}"""
        statusResponseGate = CountDownLatch(1)
        vm.onScreenStarted()

        socket.send(assistantDeltaFrame("Hello", seq = 5))
        // The burst's leading edge renders on its own and stays the only published
        // text (the flush timer never fires in this harness — see newViewModel).
        awaitState(vm.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.singleOrNull()?.text == "Hello"
        }
        socket.send(assistantDeltaFrame(" world!", seq = 6)) // coalesced, unpublished

        // The committed page with the FULL bubble text lands mid-burst...
        statusResponseGate!!.countDown()
        awaitState(vm.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.any { it.id == "msg_1" } == true
        }
        // ...then the next substantive event drives the trailing flush (in-order
        // stream: the flush runs before the approval applies).
        socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: ls", command = "ls"))
        awaitState(vm.uiState) { it.pendingApprovals.isNotEmpty() }

        // One bubble, full text: the flush-before-replace made the exact-text dedup
        // absorb the live bubble instead of stranding its fragment.
        assertEquals(
            listOf("Hello world!"),
            vm.uiState.value.items.orEmpty().filterIsInstance<ChatItem.AssistantMessage>().map { it.text },
        )
    }

    @Test
    fun `a resync_required ack re-syncs from a fresh snapshot`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        // The subscribe ack refuses the resume (replay buffer overrun,
        // docs/server-api.md): the missed window heals from a fresh snapshot fetch
        // (ticket 085) — the /messages refetch of the 048 path stays out of it.
        // The placeholder bundle's /status convergence re-read (web parity) is the
        // ring's, not the 048 pair's busy check.
        socket.send(
            """{"type":"ack","id":"c_1","code":0,"msg":"success","payload":{"accepted":[],""" +
                """"not_found":[],"resync_required":["session_1"],"cursors":{}}}""",
        )

        awaitRequest("/snapshot")
        awaitRequest("/status")
        delay(300)
        val paths = mutableListOf<String>()
        while (true) {
            paths += server.takeRequest(100, TimeUnit.MILLISECONDS)?.target?.orEmpty() ?: break
        }
        assertTrue(paths.toString(), paths.none { "/messages" in it })
    }

    @Test
    fun `sub-agent frames route to the store and never touch the main chat`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        // The main agent spawns a sub-agent (verbatim 0.29.2 shapes, docs/server-api.md):
        // the Agent call row, then the spawned frame that links it to the sub-agent.
        socket.send(
            """{"type":"tool.call.started","seq":17,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-28T03:37:35.000Z","payload":{"type":"tool.call.started","turnId":0,""" +
                """"toolCallId":"tool_agent","name":"Agent","args":{"subagent_type":"explore",""" +
                """"description":"doc-verify","prompt":"Reply ok"},"agentId":"main","sessionId":"session_1"}}""",
        )
        socket.send(subagentSpawnedFrame())
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.ToolGroup>()
                ?.flatMap { it.tools }
                ?.any { it.id == "tool_agent" && it.subagentId == "agent-12" } == true
        }

        // The sub-agent's whole turn streams tagged with its own agentId.
        socket.send(subagentStartedFrame())
        socket.send(subTurnStartedFrame())
        socket.send(subContextSplicedFrame())
        socket.send(subAssistantDeltaFrame("sub reply"))
        awaitCondition {
            subagentEntry("agent-12")
                ?.items?.filterIsInstance<ChatItem.AssistantMessage>()?.singleOrNull()?.text == "sub reply"
        }

        // Main chat: the linked Agent row only — no foreign bubble, no working-state flip.
        val mainItems = viewModel.uiState.value.items.orEmpty()
        assertEquals(1, mainItems.size)
        assertTrue(mainItems.single() is ChatItem.ToolGroup)
        assertFalse(viewModel.uiState.value.isWorking)

        // The store accumulated the sub-agent's own stream: the task-turn splice was
        // lifted into the record's prompt field (ticket 091), the deltas stream below.
        val entry = subagentEntry("agent-12")!!
        assertEquals("explore", entry.name)
        assertEquals(SubagentStatus.Working, entry.status)
        assertEquals(
            listOf(
                ChatItem.UserMessage("seeded_prompt", "sub task"),
                ChatItem.AssistantMessage("live_0", "sub reply", thinking = ""),
            ),
            entry.items,
        )

        // Completion flips the store status and leaves the main chat untouched.
        socket.send(subagentCompletedFrame())
        awaitCondition { subagentEntry("agent-12")?.status == SubagentStatus.Completed }
        assertEquals("done", subagentEntry("agent-12")!!.resultSummary)
        assertEquals(1, viewModel.uiState.value.items.orEmpty().size)
    }

    /** Non-suspend read of a store entry, for awaitCondition's plain-lambda condition. */
    private fun subagentEntry(subagentId: String): SubagentStreamStore.SubagentState? =
        runBlocking { subagentStore.entry("session_1", subagentId).first() }

    @Test
    fun `a sub-agent that ran before the app connected is seeded from history`() = runBlocking {
        // Louis's cold-open case: the app opens after the whole run — no subagent.*
        // frame will ever arrive, but history carries the Agent call and its result
        // (docs/server-api.md, Sub-agents). The page shows the seeded identity, status
        // and summary instead of the empty state.
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_2","session_id":"session_1","role":"tool",""" +
            """"content":[{"type":"tool_result","tool_call_id":"tool_1","is_error":false,""" +
            """"output":"agent_id: agent-12\nactual_subagent_type: explore\nstatus: completed\n\n[summary]\nthe final report"}],""" +
            """"created_at":"2026-07-28T03:37:52.000Z"},""" +
            """{"id":"msg_1","session_id":"session_1","role":"assistant",""" +
            """"content":[{"type":"tool_use","tool_call_id":"tool_1","tool_name":"Agent",""" +
            """"input":{"subagent_type":"explore","description":"doc-verify","prompt":"Reply ok"}}],""" +
            """"created_at":"2026-07-28T03:37:35.000Z"}""" +
            """],"has_more":false},"request_id":"r"}"""

        viewModel.onScreenStarted()

        awaitCondition { subagentEntry("agent-12")?.status == SubagentStatus.Completed }
        val entry = subagentEntry("agent-12")!!
        assertEquals("explore", entry.name)
        assertEquals("doc-verify", entry.description)
        assertEquals("the final report", entry.resultSummary)
        assertEquals(
            listOf(
                ChatItem.UserMessage("seeded_prompt", "Reply ok"),
                ChatItem.AssistantMessage("seeded_summary", "the final report", thinking = ""),
            ),
            entry.items,
        )
    }

    @Test
    fun `a live spawn link survives the history replace that first commits the row`() = runBlocking {
        // The link registry (ticket 091): the spawned frame links the row live; the
        // turn-end refetch then rebuilds the committed rows from history — where the
        // running call has no agent_id: marker yet — and the store's registry
        // re-drives the link onto the rebuilt row.
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        socket.send(
            """{"type":"tool.call.started","seq":17,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-28T03:37:35.000Z","payload":{"type":"tool.call.started","turnId":0,""" +
                """"toolCallId":"tool_agent","name":"Agent","args":{"subagent_type":"explore",""" +
                """"description":"doc-verify","prompt":"Reply ok"},"agentId":"main","sessionId":"session_1"}}""",
        )
        socket.send(subagentSpawnedFrame())
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.ToolGroup>()
                ?.flatMap { it.tools }
                ?.any { it.id == "tool_agent" && it.subagentId == "agent-12" } == true
        }

        // The turn ends: history now carries the still-running call (no result, no
        // marker) — the refetch replaces the committed rows wholesale.
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_1","session_id":"session_1","role":"assistant",""" +
            """"content":[{"type":"tool_use","tool_call_id":"tool_agent","tool_name":"Agent",""" +
            """"input":{"subagent_type":"explore","description":"doc-verify","prompt":"Reply ok"}}],""" +
            """"created_at":"2026-07-28T03:37:35.000Z"}""" +
            """],"has_more":false},"request_id":"r"}"""
        socket.send(turnEndedFrame("completed"))

        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.ToolGroup>()
                ?.flatMap { it.tools }
                ?.any { it.id == "tool_agent" && it.subagentId == "agent-12" } == true
        }
    }

    @Test
    fun `onHistoryTopReached prepends the next page deduplicated and stops when has_more is false`() = runBlocking {
        // Newest page (wire order newest-first): msg_2 is the oldest loaded — the cursor.
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_4","role":"user","content":[{"type":"text","text":"fourth"}]},""" +
            """{"id":"msg_3","role":"user","content":[{"type":"text","text":"third"}]},""" +
            """{"id":"msg_2","role":"user","content":[{"type":"text","text":"second"}]}""" +
            """],"has_more":true},"request_id":"r"}"""
        // Older page: msg_2 overlaps the loaded page (boundary race) and must not
        // produce a second row; has_more false stops the paging (ticket 043).
        olderMessagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_2","role":"user","content":[{"type":"text","text":"second"}]},""" +
            """{"id":"msg_1","role":"user","content":[{"type":"text","text":"first"}]}""" +
            """],"has_more":false},"request_id":"r"}"""

        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_2", "msg_3", "msg_4") }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
        assertTrue(viewModel.uiState.value.hasMoreHistory)

        viewModel.onHistoryTopReached()

        val request = awaitRequest("before_id")
        assertEquals("/api/v1/sessions/session_1/messages?page_size=100&before_id=msg_2", request.target)
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_1", "msg_2", "msg_3", "msg_4") }
        assertFalse(viewModel.uiState.value.hasMoreHistory)
        assertFalse(viewModel.uiState.value.isLoadingMoreHistory)

        // has_more was false: the next trigger is a no-op.
        viewModel.onHistoryTopReached()
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a failed older-page load keeps the loaded history and retries on the next trigger`() = runBlocking {
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_2","role":"user","content":[{"type":"text","text":"second"}]}""" +
            """],"has_more":true},"request_id":"r"}"""
        olderMessagesBody = """{"code":50000,"msg":"boom","data":null,"request_id":"r"}"""

        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_2") }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onHistoryTopReached()

        awaitRequest("before_id")
        // The load failed: the spinner clears, the loaded history stays, and the
        // session is still pageable — no error UI, the next trigger retries (043).
        awaitState(viewModel.uiState) { !it.isLoadingMoreHistory }
        assertEquals(listOf("msg_2"), viewModel.uiState.value.items?.map(ChatItem::id))
        assertTrue(viewModel.uiState.value.hasMoreHistory)

        olderMessagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_1","role":"user","content":[{"type":"text","text":"first"}]}""" +
            """],"has_more":false},"request_id":"r"}"""
        viewModel.onHistoryTopReached()

        val retry = awaitRequest("before_id")
        assertEquals("/api/v1/sessions/session_1/messages?page_size=100&before_id=msg_2", retry.target)
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_1", "msg_2") }
    }

    @Test
    fun `a turn-end refetch after paging resets to the newest page`() = runBlocking {
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_2","role":"user","content":[{"type":"text","text":"second"}]}""" +
            """],"has_more":true},"request_id":"r"}"""
        olderMessagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_1","role":"user","content":[{"type":"text","text":"first"}]}""" +
            """],"has_more":true},"request_id":"r"}"""

        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_2") }
        viewModel.onHistoryTopReached()
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_1", "msg_2") }

        // The turn ends: the refetch replaces history with the newest page only —
        // the prepended page is dropped, no row is duplicated (ticket 043).
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_4","role":"user","content":[{"type":"text","text":"fourth"}]},""" +
            """{"id":"msg_3","role":"user","content":[{"type":"text","text":"third"}]}""" +
            """],"has_more":true},"request_id":"r"}"""
        socket.send(turnEndedFrame(reason = "completed"))
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_3", "msg_4") }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        // The next top trigger pages from the NEW oldest message, not the stale cursor.
        olderMessagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_2","role":"user","content":[{"type":"text","text":"second"}]}""" +
            """],"has_more":false},"request_id":"r"}"""
        viewModel.onHistoryTopReached()
        val request = awaitRequest("before_id")
        assertEquals("/api/v1/sessions/session_1/messages?page_size=100&before_id=msg_3", request.target)
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_2", "msg_3", "msg_4") }
        assertFalse(viewModel.uiState.value.hasMoreHistory)
    }

    @Test
    fun `the cold start seeds the ring from status when the snapshot usage is the placeholder`() = runBlocking {
        // The reported bug (Louis, 2026-08-05): opening a past session showed a fake
        // 0/0 ring until the next message's agent.status.updated. Since 0.32.0 the
        // snapshot's session.usage is the server's all-zero placeholder on every read
        // path (live-verified busy + idle) — mapped to "unknown", the ring converges
        // from a /status re-read instead (web parity). The harness's default snapshot
        // carries the placeholder; only /status serves the real fill below.
        statusBody =
            """{"code":0,"msg":"success","data":{"busy":false,"context_tokens":25050,""" +
            """"max_context_tokens":1048576,"context_usage":0.0239},"request_id":"r"}"""

        viewModel.onScreenStarted()
        awaitRequest("/status")
        awaitState(viewModel.uiState) { it.contextUsage != null }

        assertEquals(ContextUsage(25050, 1048576), viewModel.uiState.value.contextUsage)
    }

    @Test
    fun `a non-placeholder snapshot usage still seeds the status for the model sync`() = runBlocking {
        // Since 0.38.0 the bundle carries a REAL fill, so gating the /status re-read
        // on a placeholder bundle skipped it on every session that has run turns —
        // and with it the ticket-107 model sync: sessions bound from the web client
        // showed the server default on the chip (Louis, 2026-09-01). The seed now
        // runs on every cold start; the bundle's fill applies and the seed's fresh
        // read confirms it.
        snapshotBody = """{"code":0,"msg":"success","data":{"as_of_seq":1,"epoch":"ep_1",""" +
            """"session":{"id":"session_1","busy":false,""" +
            """"usage":{"context_tokens":25050,"context_limit":1048576}},""" +
            """"messages":{"items":[],"has_more":false},"in_flight_turn":null,"subagents":[],""" +
            """"pending_approvals":[],"pending_questions":[]},"request_id":"r"}"""
        statusBody = """{"code":0,"msg":"success","data":{"busy":false,""" +
            """"model":"kimi-code/kimi-for-coding","thinking_level":"low",""" +
            """"context_tokens":25050,"max_context_tokens":1048576,"context_usage":0.0239},"request_id":"r"}"""

        viewModel.onScreenStarted()
        awaitRequest("/status")

        assertEquals(ContextUsage(25050, 1048576), viewModel.uiState.value.contextUsage)
        awaitState(viewModel.uiState) { it.sessionModel == "kimi-code/kimi-for-coding" }
        assertEquals("low", viewModel.uiState.value.sessionEffort)
    }

    @Test
    fun `a live usage frame overtakes the cold-start status seed`() = runBlocking {
        // Same guard as the 048 seed: the placeholder-triggered /status GET races the
        // live stream (it is still in flight when the socket subscribes) — a frame
        // landing first is newer, so the late response is dropped, not applied.
        statusBody =
            """{"code":0,"msg":"success","data":{"busy":false,"context_tokens":25050,""" +
            """"max_context_tokens":1048576,"context_usage":0.0239},"request_id":"r"}"""
        statusResponseGate = CountDownLatch(1)

        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(agentStatusUsageFrame(500, 1000))
        awaitState(viewModel.uiState) { it.contextUsage == ContextUsage(500, 1000) }

        statusResponseGate!!.countDown()
        delay(200)
        assertEquals(ContextUsage(500, 1000), viewModel.uiState.value.contextUsage)
    }

    @Test
    fun `usage frames grow the ring live, phase frames leave it untouched`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        // The cold-start /status seed plants the baseline (the bundle's usage is the
        // server's placeholder; /status always carries the counts).
        awaitState(viewModel.uiState) { it.contextUsage != null }
        assertEquals(ContextUsage(0, 1048576), viewModel.uiState.value.contextUsage)

        socket.send(agentStatusPhaseFrame())
        delay(200)
        assertEquals(ContextUsage(0, 1048576), viewModel.uiState.value.contextUsage)

        socket.send(agentStatusUsageFrame(25050, 1048576))
        awaitState(viewModel.uiState) { it.contextUsage == ContextUsage(25050, 1048576) }

        socket.send(agentStatusPhaseFrame())
        delay(200)
        assertEquals(ContextUsage(25050, 1048576), viewModel.uiState.value.contextUsage)
    }

    @Test
    fun `multiple requests queue in arrival order`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()

        socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: echo one", command = "echo one"))
        socket.send(approvalRequestedFrame(id = "ap_2", tool = "Write", action = "Writing /tmp/a.txt", command = ""))
        awaitState(viewModel.uiState) { it.pendingApprovals.size == 2 }

        val queue = viewModel.uiState.value.pendingApprovals
        assertEquals(listOf("ap_1", "ap_2"), queue.map { it.approvalId })
        // Without a command display, the human-readable action is the summary.
        assertEquals("Writing /tmp/a.txt", queue[1].summary)

        socket.send(approvalResolvedFrame("ap_1"))
        awaitState(viewModel.uiState) { it.pendingApprovals.size == 1 }
        assertEquals("ap_2", viewModel.uiState.value.pendingApprovals.single().approvalId)
    }

    @Test
    fun `allow posts approved decision and card clears on resolved`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: echo hi", command = "echo hi"))
        awaitState(viewModel.uiState) { it.pendingApprovals.isNotEmpty() }

        viewModel.onAllow()

        val request = awaitRequest("/approvals/")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/approvals/ap_1", request.target)
        assertEquals("""{"decision":"approved"}""", request.body!!.utf8())

        // The ack alone must not clear the card — only the resolved event does.
        delay(300)
        assertEquals(1, viewModel.uiState.value.pendingApprovals.size)
        socket.send(approvalResolvedFrame("ap_1"))
        awaitState(viewModel.uiState) { it.pendingApprovals.isEmpty() }
    }

    @Test
    fun `deny posts rejected decision`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: rm -rf /", command = "rm -rf /"))
        awaitState(viewModel.uiState) { it.pendingApprovals.isNotEmpty() }

        viewModel.onDeny()

        val request = awaitRequest("/approvals/")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/approvals/ap_1", request.target)
        assertEquals("""{"decision":"rejected"}""", request.body!!.utf8())
    }

    @Test
    fun `failed decision post keeps card and emits ApprovalFailed`() = runBlocking {
        approvalsSucceed = false
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: echo hi", command = "echo hi"))
        awaitState(viewModel.uiState) { it.pendingApprovals.isNotEmpty() }

        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onAllow()
            awaitRequest("/approvals/")
            awaitCondition { effects.isNotEmpty() }

            assertEquals(
                ChatViewModel.Effect.ApprovalFailed("approval does not exist"),
                effects.single(),
            )
            // No resolved event will come: the card must stay up.
            assertEquals(1, viewModel.uiState.value.pendingApprovals.size)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `stop posts abort and turn ended clears the working state`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }

        viewModel.onStop()

        val request = awaitRequest(":abort")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1:abort", request.target)
        assertEquals("{}", request.body!!.utf8())

        // The ack alone must not clear the indicator — the server's turn.ended does.
        assertTrue(viewModel.uiState.value.isWorking)
        socket.send(turnEndedFrame(reason = "cancelled"))
        awaitState(viewModel.uiState) { !it.isWorking }
    }

    @Test
    fun `stop without a running turn sends no request`() = runBlocking {
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        // Drain the history/status/WS requests recorded so far.
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onStop()

        delay(200)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `failed abort emits AbortFailed and keeps the working state`() = runBlocking {
        abortsSucceed = false
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }

        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onStop()
            awaitRequest(":abort")
            awaitCondition { effects.isNotEmpty() }

            assertEquals(
                ChatViewModel.Effect.AbortFailed("no running prompt"),
                effects.single(),
            )
            // The turn is still running: the indicator and the Stop action stay up.
            assertTrue(viewModel.uiState.value.isWorking)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `status seed in flight does not clear a turn that just started`() = runBlocking {
        // The cold start owns the first start's seeds (ticket 085), so the /status
        // seed this guards runs on the within-process resume (second ON_START).
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
        serverSocket = null
        viewModel.onScreenStopped()

        val gate = CountDownLatch(1)
        statusResponseGate = gate
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()

        // The /status response is parked server-side: turn.started lands first.
        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }

        // The stale busy:false snapshot arrives after the event and must be dropped.
        gate.countDown()
        delay(200)
        assertTrue(viewModel.uiState.value.isWorking)
    }

    @Test
    fun `context spliced shows the sent prompt bubble without a history refetch`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        // Drain the history/status/WS requests recorded so far.
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        socket.send(contextSplicedFrame(id = "msg_1", text = "hello from the phone"))

        awaitState(viewModel.uiState) { !it.items.isNullOrEmpty() }
        assertEquals(
            listOf(ChatItem.UserMessage("msg_1", "hello from the phone")),
            viewModel.uiState.value.items,
        )
        // The echo came from the socket frame alone: no HTTP refetch fired.
        delay(200)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `no duplicate user bubble after the turn ended refetch`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
        socket.send(contextSplicedFrame(id = "msg_1", text = "hello from the phone"))
        awaitState(viewModel.uiState) { it.items?.size == 1 }

        // The server committed the message; the turn.ended refetch returns it in history.
        messagesBody = """
            {"code":0,"msg":"success","data":{"items":[
              {"id":"msg_1","session_id":"session_1","role":"user",
               "content":[{"type":"text","text":"hello from the phone"}],
               "created_at":"2026-07-19T00:00:00.500Z"}
            ],"has_more":false},"request_id":"r"}
        """.trimIndent()
        socket.send(turnEndedFrame(reason = "completed"))

        // The refetch replaces committed rows wholesale: still exactly one bubble,
        // now carrying the server timestamp (ticket 039).
        awaitRequest("/messages")
        delay(300)
        assertEquals(
            listOf(
                ChatItem.UserMessage(
                    "msg_1",
                    "hello from the phone",
                    createdAt = Instant.parse("2026-07-19T00:00:00.500Z"),
                ),
            ),
            viewModel.uiState.value.items,
        )
    }

    @Test
    fun `sending while busy steers the queued prompt into the running turn`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onInputChange("Stop counting")
        viewModel.onSend()

        // docs/server-api.md, Steer: queue the prompt as usual, then steer its id in.
        // The config GET served at screen start makes the server default known (017).
        val prompt = awaitRequest("/prompts")
        assertEquals("POST", prompt.method)
        assertEquals("/api/v1/sessions/session_1/prompts", prompt.target)
        assertEquals("""{"content":[{"type":"text","text":"Stop counting"}],"model":"kimi-code/k3"}""", prompt.body!!.utf8())
        val steer = awaitRequest("prompts:steer")
        assertEquals("POST", steer.method)
        assertEquals("/api/v1/sessions/session_1/prompts:steer", steer.target)
        assertEquals("""{"prompt_ids":["msg_1"]}""", steer.body!!.utf8())

        awaitState(viewModel.uiState) { it.inputText.isEmpty() && !it.isSending }
        // The turn keeps running: the working state and Stop action stay up.
        assertTrue(viewModel.uiState.value.isWorking)
    }

    @Test
    fun `sending while idle posts a plain prompt, not a steer`() = runBlocking {
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onInputChange("hello")
        viewModel.onSend()

        val request = awaitRequest("/prompts")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/prompts", request.target)
        assertEquals("""{"content":[{"type":"text","text":"hello"}],"model":"kimi-code/k3"}""", request.body!!.utf8())
        // A plain prompt marks the session busy; no steer call follows.
        awaitState(viewModel.uiState) { it.isWorking }
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `steered message echo appears from the socket while the turn continues`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        socket.send(steerEchoSplicedFrame(text = "Stop counting"))

        awaitState(viewModel.uiState) { !it.items.isNullOrEmpty() }
        val items = viewModel.uiState.value.items.orEmpty()
        assertEquals(1, items.size)
        assertEquals("Stop counting", (items.single() as ChatItem.UserMessage).text)
        // The turn is still running and the echo needed no history refetch.
        assertTrue(viewModel.uiState.value.isWorking)
        delay(200)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `nav arguments seed the top bar before the session fetch lands`() {
        val seeded = ChatViewModel(
            chatRepository,
            testSessionsRepository(),
            settingsRepository,
            FakeAttachmentStore(),
            subagentStore,
            snapshotSync,
            Chat("session_1", title = "Fix the bug", cwd = "/home/ubuntu/kimi-android-client"),
            todoParser = testTodoParser(),
        )

        assertEquals("Fix the bug", seeded.uiState.value.sessionTitle)
        assertEquals("/home/ubuntu/kimi-android-client", seeded.uiState.value.sessionCwd)
    }

    @Test
    fun `nav arguments with only a cwd seed the basename`() {
        val seeded = ChatViewModel(
            chatRepository,
            testSessionsRepository(),
            settingsRepository,
            FakeAttachmentStore(),
            subagentStore,
            snapshotSync,
            Chat("session_1", cwd = "/home/ubuntu/kimi-android-client"),
            todoParser = testTodoParser(),
        )

        assertEquals("kimi-android-client", seeded.uiState.value.sessionTitle)
    }

    @Test
    fun `session title and cwd load into the top bar state`() = runBlocking {
        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { it.sessionTitle != null }
        assertEquals("Fix the bug", viewModel.uiState.value.sessionTitle)
        assertEquals("/home/ubuntu/kimi-android-client", viewModel.uiState.value.sessionCwd)
    }

    @Test
    fun `titleless session falls back to the cwd basename in the top bar`() = runBlocking {
        sessionBody =
            """{"code":0,"msg":"success","data":{"id":"session_1","title":"",""" +
                """"updated_at":"2026-07-20T00:00:00.000Z","busy":false,""" +
                """"metadata":{"cwd":"/home/ubuntu/kimi-android-client"}},"request_id":"r"}"""
        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { it.sessionTitle != null }
        assertEquals("kimi-android-client", viewModel.uiState.value.sessionTitle)
    }

    @Test
    fun `failed session lookup leaves the top bar on the session id fallback`() = runBlocking {
        sessionBody = """{"code":40404,"msg":"session not found","data":null,"request_id":"r"}"""
        viewModel.onScreenStarted()

        // Wait until the failed lookup was served before asserting the fallback held.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val request = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: continue
            if (request.target == "/api/v1/sessions/session_1") break
        }
        delay(200)
        assertNull(viewModel.uiState.value.sessionTitle)
        assertNull(viewModel.uiState.value.sessionCwd)
    }

    @Test
    fun `models load into state and the server default drives the chip label`() = runBlocking {
        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { it.modelsAvailable }
        val state = viewModel.uiState.value
        assertEquals(3, state.availableModels.size)
        assertEquals("kimi-code/k3", state.serverDefaultModel)
        assertNull(state.sessionModel)
        // No session choice yet: the chip shows the server default's display name.
        assertEquals("K3", state.currentModelLabel)
    }

    @Test
    fun `failed models fetch hides the chip`() = runBlocking {
        modelsBody = """{"code":50000,"msg":"boom","data":null,"request_id":"r"}"""
        viewModel.onScreenStarted()

        // Wait until the failed fetch was served (the WS is gated on the startup GETs,
        // and a failed models fetch never triggers the config GET — so the socket is
        // not the synchronization point here).
        awaitRequest("/api/v1/models")
        delay(300)

        // The fetch failed: no models, no chip — and the rest of the screen works.
        assertEquals(emptyList<ServerModel>(), viewModel.uiState.value.availableModels)
        assertNull(viewModel.uiState.value.currentModelLabel)
    }

    @Test
    fun `selected model persists and survives ViewModel recreation`() = runBlocking {
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable }

        viewModel.onModelSelected("kimi-code/kimi-for-coding-highspeed")

        awaitState(viewModel.uiState) { it.sessionModel == "kimi-code/kimi-for-coding-highspeed" }
        assertEquals("K2.7 Coding Highspeed", viewModel.uiState.value.currentModelLabel)

        // Recreation over the same DataStore: the choice is reloaded, not lost.
        val recreated = ChatViewModel(
            chatRepository,
            testSessionsRepository(),
            settingsRepository,
            FakeAttachmentStore(),
            subagentStore,
            snapshotSync,
            Chat("session_1"),
            todoParser = testTodoParser(),
        )
        startedViewModels += recreated
        recreated.onScreenStarted()
        awaitState(recreated.uiState) { it.modelsAvailable && it.sessionModel != null }
        assertEquals("kimi-code/kimi-for-coding-highspeed", recreated.uiState.value.sessionModel)
        assertEquals("K2.7 Coding Highspeed", recreated.uiState.value.currentModelLabel)
    }

    @Test
    fun `selected effort persists and survives ViewModel recreation`() = runBlocking {
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable }

        viewModel.onEffortSelected("low")

        awaitState(viewModel.uiState) { it.sessionEffort == "low" }
        val state = viewModel.uiState.value
        // K3 (the effective model) offers its efforts; the stored one is selected.
        assertEquals(listOf("low", "high", "max"), state.supportedEfforts)
        assertEquals("low", state.selectedEffort)
        assertEquals("high", state.defaultEffort)

        // Recreation over the same DataStore: the choice is reloaded, not lost.
        val recreated = ChatViewModel(
            chatRepository,
            testSessionsRepository(),
            settingsRepository,
            FakeAttachmentStore(),
            subagentStore,
            snapshotSync,
            Chat("session_1"),
            todoParser = testTodoParser(),
        )
        startedViewModels += recreated
        recreated.onScreenStarted()
        awaitState(recreated.uiState) { it.modelsAvailable && it.sessionEffort != null }
        assertEquals("low", recreated.uiState.value.sessionEffort)
        assertEquals("low", recreated.uiState.value.selectedEffort)
    }

    @Test
    fun `stored effort incompatible with the session's model exposes no effort selection`() = runBlocking {
        // Picked on K3, then the session switched to K2.7 (no support_efforts):
        // the sheet shows no effort section and no selection (ticket 032).
        dataStore.edit {
            it[stringPreferencesKey("effort_for_session_session_1")] = "low"
            it[stringPreferencesKey("model_for_session_session_1")] = "kimi-code/kimi-for-coding"
        }
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable && it.sessionModel != null }

        val state = viewModel.uiState.value
        assertEquals("low", state.sessionEffort)
        assertEquals(emptyList<String>(), state.supportedEfforts)
        assertNull(state.selectedEffort)
        assertNull(state.defaultEffort)
    }

    @Test
    fun `chip effort is the session choice else the model default, absent on effort-less models`() = runBlocking {
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable }

        // Nothing stored: K3's server default_effort drives the chip (ticket 108).
        assertEquals("K3", viewModel.uiState.value.currentModelLabel)
        assertEquals("high", viewModel.uiState.value.effectiveEffort)

        viewModel.onEffortSelected("low")
        awaitState(viewModel.uiState) { it.sessionEffort == "low" }
        assertEquals("low", viewModel.uiState.value.effectiveEffort)

        // An effort-less model drops the effort from the chip entirely.
        viewModel.onModelSelected("kimi-code/kimi-for-coding")
        awaitState(viewModel.uiState) { it.sessionModel == "kimi-code/kimi-for-coding" }
        assertEquals("K2.7 Coding", viewModel.uiState.value.currentModelLabel)
        assertNull(viewModel.uiState.value.effectiveEffort)
    }

    // --- Server-owned session model (ticket 107) ----------------------------------------

    @Test
    fun `status seed syncs the server-reported model and effort into the session prefs`() = runBlocking {
        // The server owns the session's binding: /status serves it even for sessions
        // cold since server start (live-verified 2026-09-01). The bound model is not
        // the server default — the pre-107 chip would have shown the default instead.
        modelsBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"provider":"managed:kimi-code","model":"kimi-code/k3","display_name":"K3",""" +
            """"max_context_size":1048576,"support_efforts":["low","high","max"],"default_effort":"high"},""" +
            """{"provider":"deepseek","model":"deepseek/deepseek-v4-pro","display_name":"DeepSeek V4 Pro",""" +
            """"max_context_size":1000000,"support_efforts":["low","high"],"default_effort":"high"}""" +
            """]},"request_id":"r"}"""
        statusBody = """{"code":0,"msg":"success","data":{"busy":false,"model":"deepseek/deepseek-v4-pro",""" +
            """"thinking_level":"low","context_tokens":0,"max_context_tokens":1000000,""" +
            """"context_usage":0.0},"request_id":"r"}"""
        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { it.sessionModel == "deepseek/deepseek-v4-pro" }
        assertEquals("low", viewModel.uiState.value.sessionEffort)

        // Persisted: the next prompt carries the server-reported values.
        viewModel.onInputChange("hi")
        viewModel.onSend()
        val body = awaitRequest("/prompts").body!!.utf8()
        assertTrue(""""model":"deepseek/deepseek-v4-pro"""" in body)
        assertTrue(""""thinking":"low"""" in body)
    }

    @Test
    fun `agent status frame syncs the server-reported model and effort`() = runBlocking {
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable }
        val socket = awaitServerSocket()

        socket.send(agentStatusUsageFrame(100, 262144, model = "kimi-code/kimi-for-coding", effort = "max"))

        awaitState(viewModel.uiState) { it.sessionModel == "kimi-code/kimi-for-coding" }
        assertEquals("max", viewModel.uiState.value.sessionEffort)
        // Written through to the store the send path reads (the edit lands off the
        // frame's call stack — poll until it commits).
        withTimeout(5_000) {
            while (dataStore.data.first()[stringPreferencesKey("model_for_session_session_1")] != "kimi-code/kimi-for-coding") delay(10)
        }
        assertEquals("max", dataStore.data.first()[stringPreferencesKey("effort_for_session_session_1")])
    }

    @Test
    fun `blank status values leave the stored model choice untouched`() = runBlocking {
        dataStore.edit { it[stringPreferencesKey("model_for_session_session_1")] = "kimi-code/kimi-for-coding" }
        // Default statusBody: no model key, no thinking_level — a fresh session's answer
        // must not erase the stored choice.
        viewModel.onScreenStarted()
        // The seed has applied once its context usage lands in the state.
        awaitState(viewModel.uiState) { it.contextUsage != null && it.modelsAvailable }

        assertEquals("kimi-code/kimi-for-coding", viewModel.uiState.value.sessionModel)
        assertEquals(
            "kimi-code/kimi-for-coding",
            dataStore.data.first()[stringPreferencesKey("model_for_session_session_1")],
        )
    }

    @Test
    fun `a pending pick is not clobbered by a status frame of the running turn`() = runBlocking {
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable }
        val socket = awaitServerSocket()

        viewModel.onModelSelected("kimi-code/kimi-for-coding")
        awaitState(viewModel.uiState) { it.sessionModel == "kimi-code/kimi-for-coding" }

        // A frame of the still-running turn reports the OLD binding (k3): the unsent
        // model pick must survive. The effort was not picked, so it does sync.
        socket.send(agentStatusUsageFrame(100, 1048576, model = "kimi-code/k3", effort = "max"))

        // The frame was processed once its distinct context usage lands.
        awaitState(viewModel.uiState) { it.contextUsage == ContextUsage(100, 1048576) }
        assertEquals("kimi-code/kimi-for-coding", viewModel.uiState.value.sessionModel)
        assertEquals("max", viewModel.uiState.value.sessionEffort)
    }

    @Test
    fun `the next status frame syncs again once the prompt carrying the pick was sent`() = runBlocking {
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable }
        val socket = awaitServerSocket()

        viewModel.onModelSelected("kimi-code/kimi-for-coding")
        awaitState(viewModel.uiState) { it.sessionModel == "kimi-code/kimi-for-coding" }
        viewModel.onInputChange("hi")
        viewModel.onSend()

        val body = awaitRequest("/prompts").body!!.utf8()
        assertTrue(""""model":"kimi-code/kimi-for-coding"""" in body)
        awaitState(viewModel.uiState) { !it.isSending }

        // The prompt bound the session; later frames reflect the server's state again
        // (here: another client rebound the session to K3 with max effort).
        socket.send(agentStatusUsageFrame(100, 1048576, model = "kimi-code/k3", effort = "max"))

        awaitState(viewModel.uiState) { it.sessionModel == "kimi-code/k3" }
        assertEquals("max", viewModel.uiState.value.sessionEffort)
    }

    // --- Permission mode (ticket 141) ---------------------------------------------------

    @Test
    fun `status seed syncs the server-reported permission mode without storing it`() = runBlocking {
        // Unlike the model binding, /status ALWAYS reports a mode (a fresh session reads
        // the server default): it feeds the chip but never the per-session store — the
        // store holds only explicit picks, so an unpicked prompt carries no
        // permission_mode field (absent = follow the server).
        statusBody = """{"code":0,"msg":"success","data":{"busy":false,"permission":"manual",""" +
            """"context_tokens":0,"max_context_tokens":1048576,"context_usage":0.0},"request_id":"r"}"""
        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { it.sessionPermission == "manual" }
        assertEquals(PermissionMode.AlwaysAsk, viewModel.uiState.value.currentPermissionMode)
        assertNull(dataStore.data.first()[stringPreferencesKey("permission_for_session_session_1")])

        // The seed left the store empty: the next prompt omits permission_mode.
        viewModel.onInputChange("hi")
        viewModel.onSend()
        val body = awaitRequest("/prompts").body!!.utf8()
        assertFalse(body.contains("permission_mode"))
    }

    @Test
    fun `selected permission mode persists, stamps prompts and survives recreation`() = runBlocking {
        statusBody = """{"code":0,"msg":"success","data":{"busy":false,"permission":"yolo",""" +
            """"context_tokens":0,"max_context_tokens":1048576,"context_usage":0.0},"request_id":"r"}"""
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessionPermission == "yolo" }
        // The non-positional mapping: the yolo wire value is the middle "Ask When
        // Needed" label, never sorted into place.
        assertEquals(PermissionMode.AskWhenNeeded, viewModel.uiState.value.currentPermissionMode)

        viewModel.onPermissionModeSelected(PermissionMode.AlwaysAsk)
        awaitState(viewModel.uiState) { it.sessionPermission == "manual" }
        assertEquals("manual", dataStore.data.first()[stringPreferencesKey("permission_for_session_session_1")])

        viewModel.onInputChange("hi")
        viewModel.onSend()
        val body = awaitRequest("/prompts").body!!.utf8()
        assertTrue(body, body.contains(""""permission_mode":"manual""""))
        awaitState(viewModel.uiState) { !it.isSending }

        // Recreation over the same DataStore (kill + reopen): the /status seed still
        // reports the server's yolo, but the stored pick wins and re-stamps.
        val recreated = ChatViewModel(
            chatRepository,
            testSessionsRepository(),
            settingsRepository,
            FakeAttachmentStore(),
            subagentStore,
            snapshotSync,
            Chat("session_1"),
            todoParser = testTodoParser(),
        )
        startedViewModels += recreated
        recreated.onScreenStarted()
        awaitState(recreated.uiState) { it.sessionPermission == "manual" }

        recreated.onInputChange("hi again")
        recreated.onSend()
        val recreatedBody = awaitRequest("/prompts").body!!.utf8()
        assertTrue(recreatedBody, recreatedBody.contains(""""permission_mode":"manual""""))
    }

    @Test
    fun `a pending permission pick is not clobbered by a later status seed`() = runBlocking {
        statusBody = """{"code":0,"msg":"success","data":{"busy":false,"permission":"yolo",""" +
            """"context_tokens":0,"max_context_tokens":1048576,"context_usage":0.0},"request_id":"r"}"""
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.sessionPermission == "yolo" }
        // Drain the startup burst so the next /status request is the re-seed's.
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onPermissionModeSelected(PermissionMode.AlwaysAsk)
        awaitState(viewModel.uiState) { it.sessionPermission == "manual" }

        // A later ON_START re-seeds from /status; the server still reports the
        // previous mode (the pick was never sent) — the unsent pick must win.
        statusBody = """{"code":0,"msg":"success","data":{"busy":false,"permission":"auto",""" +
            """"context_tokens":0,"max_context_tokens":1048576,"context_usage":0.0},"request_id":"r"}"""
        viewModel.onScreenStarted()
        awaitRequest("/status")
        delay(200)

        assertEquals("manual", viewModel.uiState.value.sessionPermission)
        assertEquals("manual", dataStore.data.first()[stringPreferencesKey("permission_for_session_session_1")])
    }

    // --- Compact session (ticket 056) ---------------------------------------------------

    @Test
    fun `onCompact posts to the compact endpoint and sets the compacting state`() = runBlocking {
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onCompact()

        val request = awaitRequest(":compact")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1:compact", request.target)
        assertEquals("{}", request.body!!.utf8())
        awaitState(viewModel.uiState) { it.isCompacting }
    }

    @Test
    fun `onCompact is a no-op while a turn runs or a compaction is in progress`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        // A running turn disables compact (the server would 40910 it anyway).
        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }
        viewModel.onCompact()
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        assertFalse(viewModel.uiState.value.isCompacting)

        // A compaction in progress too (a second compact is a silent server no-op).
        socket.send(turnEndedFrame(reason = "completed"))
        awaitState(viewModel.uiState) { !it.isWorking }
        socket.send(compactionStartedFrame())
        awaitState(viewModel.uiState) { it.isCompacting }
        // Drain the turn-end refetch so only a compact POST could arrive next.
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
        viewModel.onCompact()
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a compact POST failure surfaces the server's 40910 message and leaves the state`() = runBlocking {
        // Compacting an empty session: the snackbar shows the server's own sentence.
        compactsSucceed = false
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onScreenStarted()
            awaitState(viewModel.uiState) { it.items != null }
            while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

            viewModel.onCompact()

            awaitCondition { effects.isNotEmpty() }
            assertEquals(
                ChatViewModel.Effect.CompactionFailed("No messages to compact in current history."),
                effects.single(),
            )
            assertFalse(viewModel.uiState.value.isCompacting)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `compaction frames drive the divider, the state and the snackbars`() = runBlocking {
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_1","session_id":"session_1","role":"user",""" +
            """"content":[{"type":"text","text":"run echo hi"}],""" +
            """"created_at":"2026-07-30T05:44:56.000Z","metadata":{"origin":{"kind":"user"}}}""" +
            """],"has_more":false},"request_id":"r"}"""
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onScreenStarted()
            val socket = awaitServerSocket()
            awaitState(viewModel.uiState) { state -> state.items?.singleOrNull() is ChatItem.UserMessage }

            // Triggered from anywhere (the app, the TUI's /compact): the started frame
            // sets the state and inserts the pending divider at the tail.
            socket.send(compactionStartedFrame())
            awaitState(viewModel.uiState) { state ->
                state.isCompacting &&
                    state.items?.last() == ChatItem.CompactionDivider(LIVE_COMPACTION_DIVIDER_ID, pending = true)
            }

            // The context rewrite, then completion. The frames are processed in order,
            // so once the completion applies, the splice was processed too: the full
            // row list below proves the summary never became a bubble and the kept
            // user message did not duplicate.
            socket.send(compactionSpliceFrame())
            socket.send(compactionCompletedFrame())
            awaitState(viewModel.uiState) { state ->
                !state.isCompacting &&
                    state.items == listOf(
                        ChatItem.UserMessage(
                            "msg_1",
                            "run echo hi",
                            createdAt = Instant.parse("2026-07-30T05:44:56.000Z"),
                        ),
                        ChatItem.CompactionDivider(
                            LIVE_COMPACTION_DIVIDER_ID,
                            pending = false,
                            tokensBefore = 850,
                            tokensAfter = 386,
                            summary = "Completed spike, nothing pending.",
                        ),
                    )
            }
            // The divider flipped to done with the token delta, the snackbar confirms.
            awaitCondition { effects.isNotEmpty() }
            assertEquals(
                ChatViewModel.Effect.CompactionFinished("Context compacted: 850 → 386 tokens"),
                effects.single(),
            )
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `a compaction failure removes the pending divider and emits the error snackbar`() = runBlocking {
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onScreenStarted()
            val socket = awaitServerSocket()
            awaitState(viewModel.uiState) { it.items != null }

            socket.send(compactionStartedFrame())
            awaitState(viewModel.uiState) { it.isCompacting }
            socket.send(compactionFailedFrame())

            // The handler updates the state before sending the effect: once the
            // snackbar arrives, the divider removal is necessarily applied too.
            awaitCondition { effects.isNotEmpty() }
            assertEquals(
                ChatViewModel.Effect.CompactionFailed("summarizer unreachable"),
                effects.single(),
            )
            assertFalse(viewModel.uiState.value.isCompacting)
            assertEquals(
                emptyList<ChatItem.CompactionDivider>(),
                viewModel.uiState.value.items.orEmpty().filterIsInstance<ChatItem.CompactionDivider>(),
            )
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `a compaction_summary history message renders as a divider on cold open`() {
        runBlocking {
            // After killing and reopening the app: the persisted summary (bare text, no
            // token numbers) is a done divider holding the dialog's summary — and the
            // compacting state stays off (no live compaction).
            messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
                """{"id":"msg_2","session_id":"session_1","role":"user",""" +
                """"content":[{"type":"text","text":"Working summary of the task."}],""" +
                """"created_at":"2026-07-30T05:44:56.660Z","metadata":{"origin":{"kind":"compaction_summary"}}},""" +
                """{"id":"msg_1","session_id":"session_1","role":"user",""" +
                """"content":[{"type":"text","text":"run echo hi"}],""" +
                """"created_at":"2026-07-17T04:12:10.000Z","metadata":{"origin":{"kind":"user"}}}""" +
                """],"has_more":false},"request_id":"r"}"""

            viewModel.onScreenStarted()

            awaitState(viewModel.uiState) { state ->
                state.items == listOf(
                    ChatItem.UserMessage("msg_1", "run echo hi", createdAt = Instant.parse("2026-07-17T04:12:10.000Z")),
                    ChatItem.CompactionDivider("msg_2", pending = false, summary = "Working summary of the task."),
                )
            }
            assertFalse(viewModel.uiState.value.isCompacting)
        }
    }

    // --- Fork session (ticket 127) ------------------------------------------------------

    @Test
    fun `onFork posts to the fork endpoint and emits the navigation effect`() = runBlocking {
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onScreenStarted()
            awaitState(viewModel.uiState) { it.items != null }
            while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

            viewModel.onFork()

            val request = awaitRequest(":fork")
            assertEquals("POST", request.method)
            assertEquals("/api/v1/sessions/session_1:fork", request.target)
            assertEquals("{}", request.body!!.utf8())
            awaitCondition { effects.isNotEmpty() }
            assertEquals(
                ChatViewModel.Effect.SessionForked(
                    Session(
                        id = "session_fork",
                        title = "Fork: Fix the bug",
                        cwd = "/home/ubuntu/kimi-android-client",
                        updatedAt = Instant.parse("2026-09-14T09:27:37.222Z"),
                        isRunning = false,
                        isAwaitingInteraction = false,
                    ),
                ),
                effects.single(),
            )
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `onFork is a no-op while a turn runs`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        // A running turn disables fork (the server would 40901 it anyway) — the same
        // rule disables the overflow menu item.
        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }

        viewModel.onFork()

        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a fork POST failure surfaces the server's 40901 message`() = runBlocking {
        // Forking a busy session: the snackbar shows the server's own sentence and the
        // user stays on the source session (no navigation effect).
        forksSucceed = false
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onScreenStarted()
            awaitState(viewModel.uiState) { it.items != null }
            while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

            viewModel.onFork()

            awaitCondition { effects.isNotEmpty() }
            assertEquals(
                ChatViewModel.Effect.ForkFailed("Session \"session_1\" cannot be forked while a turn is running"),
                effects.single(),
            )
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `task events feed the jobs list and termination drops the running count`() {
        runBlocking {
            viewModel.onScreenStarted()
            val socket = awaitServerSocket()

            // A background sub-agent and a background bash register in the task store
            // (docs/server-api.md, Background lifecycle — ticket 092).
            socket.send(taskStartedFrame(taskId = "agent-7noib8bb", description = "doc-verify-bg"))
            socket.send(
                taskStartedFrame(
                    taskId = "proc-9xk2",
                    description = "sleep 1000",
                    kind = "process",
                    agentId = null,
                    seq = 53,
                ),
            )
            awaitState(viewModel.uiState) { state -> state.jobs.count { it.isRunning } == 2 }

            // CORE status "killed" maps to the wire "cancelled"; the finished job
            // lingers for the sheet, the running count drops.
            socket.send(taskTerminatedFrame(taskId = "agent-7noib8bb", status = "killed"))
            awaitState(viewModel.uiState) { state -> state.jobs.count { it.isRunning } == 1 }

            val jobs = viewModel.uiState.value.jobs
            assertEquals(2, jobs.size)
            val terminated = jobs.first { it.taskId == "agent-7noib8bb" }
            assertEquals(BackgroundTask.Status.Cancelled, terminated.status)
            assertEquals(Instant.ofEpochMilli(1785210118804), terminated.endedAt)
            // info.agentId links the task to its sub-agent page (ticket 092).
            assertEquals("agent-13", terminated.agentId)
            val bash = jobs.first { it.taskId == "proc-9xk2" }
            assertEquals(BackgroundTask.Kind.Bash, bash.kind)
            assertEquals(BackgroundTask.Status.Running, bash.status)
            assertNull(bash.agentId)
        }
    }

    @Test
    fun `a sub-agent-owned task frame routes to its store record, never the main jobs list`() {
        runBlocking {
            viewModel.onScreenStarted()
            val socket = awaitServerSocket()

            // The spawn landed, then the sub-agent's own background bash registers —
            // the frame's agentId stamp names the OWNING agent (docs/server-api.md,
            // Background lifecycle: REST /tasks serves only the main agent's store,
            // so a sub-agent's tasks arrive live-only, tagged with its id).
            socket.send(subagentSpawnedFrame())
            socket.send(
                taskStartedFrame(
                    taskId = "proc-sub1",
                    description = "nested bg bash",
                    kind = "process",
                    agentId = null,
                    seq = 60,
                    ownerAgentId = "agent-12",
                ),
            )

            awaitCondition { subagentEntry("agent-12")?.jobs?.isNotEmpty() == true }
            val job = subagentEntry("agent-12")!!.jobs.single()
            assertEquals("proc-sub1", job.taskId)
            assertEquals(BackgroundTask.Kind.Bash, job.kind)
            assertEquals(BackgroundTask.Status.Running, job.status)
            // The composer pill counts only main-owned tasks.
            assertTrue(viewModel.uiState.value.jobs.isEmpty())

            // Its termination lands on the same record (core timed_out → the wire
            // Failed + the live-only timed-out flag, ticket 097).
            socket.send(
                taskTerminatedFrame(
                    taskId = "proc-sub1",
                    status = "timed_out",
                    kind = "process",
                    agentId = null,
                    seq = 61,
                    ownerAgentId = "agent-12",
                ),
            )
            awaitCondition { subagentEntry("agent-12")?.jobs?.single()?.isRunning == false }
            val terminated = subagentEntry("agent-12")!!.jobs.single()
            assertEquals(BackgroundTask.Status.Failed, terminated.status)
            assertTrue(terminated.timedOut)
            assertTrue(viewModel.uiState.value.jobs.isEmpty())
        }
    }

}
