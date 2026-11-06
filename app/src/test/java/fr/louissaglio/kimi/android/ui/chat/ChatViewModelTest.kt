package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.chat.BackgroundTask
import fr.louissaglio.kimi.android.data.chat.ContextUsage
import fr.louissaglio.kimi.android.data.chat.ServerModel
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.sessions.Session
import fr.louissaglio.kimi.android.navigation.Chat
import fr.louissaglio.kimi.android.testutil.TranscriptFrames
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
            // In-order stream: the tool op is processed after both deltas, and pending
            // text flushes before it applies.
            socket.send(
                TranscriptFrames.opsFrame(TranscriptFrames.toolOp("call_ls", "Bash", "running", turnId = "tx")),
            )
            awaitState(vm.uiState) { state ->
                state.items?.filterIsInstance<ChatItem.ToolGroup>()?.any { g -> g.tools.any { it.id == "call_ls" } } == true
            }

            // The two trailing deltas became ONE emission, published before the tool op.
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

        // The re-opened gate subscribes afresh (subscribe_v2 — no replay of the missed
        // window, ticket 176): the REST resync is what heals it.
        awaitCondition { clientFrames.count { "\"type\":\"subscribe_v2\"" in it } >= 2 }

        // A finished turn can have no live rows: the status-aware resync clears the
        // truncated ghost bubble and the canonical message renders exactly once. The
        // resync is a multi-round-trip path (/status, history, jobs) — give it more
        // than the default budget on a loaded runner (ticket 163).
        awaitState(viewModel.uiState, timeoutMs = 15_000) { state ->
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
        // stream: the flush runs before the tool row applies).
        socket.send(
            TranscriptFrames.opsFrame(TranscriptFrames.toolOp("call_ls", "Bash", "running", ""","input":{"command":"ls"}""", turnId = "tx")),
        )
        awaitState(vm.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.ToolGroup>()?.any { g -> g.tools.any { it.id == "call_ls" } } == true
        }

        // One bubble, full text: the flush-before-replace made the exact-text dedup
        // absorb the live bubble instead of stranding its fragment.
        assertEquals(
            listOf("Hello world!"),
            vm.uiState.value.items.orEmpty().filterIsInstance<ChatItem.AssistantMessage>().map { it.text },
        )
    }

    @Test
    fun `sub-agent frames route to the store and never touch the main chat`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        // The sub-agent's record exists (its spawn carrier — the `task` frame — is
        // ticket 182's, so the record is seeded through the store's spawn funnel), and
        // the main agent's Agent call streams as a tool frame.
        subagentStore.apply(
            "session_1",
            SessionEvent.SubagentSpawned("agent-12", "explore", "tool_agent", "doc-verify", runInBackground = false),
        )
        socket.send(
            TranscriptFrames.opsFrame(
                TranscriptFrames.toolOp(
                    "tool_agent", "Agent", "running",
                    ""","input":{"subagent_type":"explore","description":"doc-verify","prompt":"Reply ok"}""",
                    turnId = "tx",
                ),
            ),
        )
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.ToolGroup>()?.flatMap { it.tools }?.any { it.id == "tool_agent" } == true
        }

        // The sub-agent's whole turn streams tagged with its own agent_id.
        socket.send(subTurnStartedFrame())
        socket.send(subContextSplicedFrame())
        socket.send(subAssistantDeltaFrame("sub reply"))
        awaitCondition {
            subagentEntry("agent-12")
                ?.items?.filterIsInstance<ChatItem.AssistantMessage>()?.singleOrNull()?.text == "sub reply"
        }

        // Main chat: the Agent row only — no foreign bubble, no working-state flip.
        val mainItems = viewModel.uiState.value.items.orEmpty()
        assertEquals(1, mainItems.size)
        assertTrue(mainItems.single() is ChatItem.ToolGroup)
        assertFalse(viewModel.uiState.value.isWorking)

        // The store accumulated the sub-agent's own stream: the task-turn splice was
        // lifted into the record's prompt field (ticket 091), the deltas stream below.
        val entry = subagentEntry("agent-12")!!
        assertEquals("explore", entry.name)
        assertEquals(
            listOf(
                ChatItem.UserMessage("seeded_prompt", "sub task"),
                ChatItem.AssistantMessage("live_0", "sub reply", thinking = ""),
            ),
            entry.items,
        )
    }

    // --- Ticket 175: the subscribe_v2 transcript stream -------------------------------

    @Test
    fun `a live prompt streams thinking, text and a tool call without any REST refetch`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.promptOp("msg_1", "running")))
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.turnOp("t1", "running")))
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.undoOp("live-m1", TranscriptFrames.userMessage("msg_1", "List the files"))))
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.stepOp("t1", "t1.1", "running")))
        socket.send(
            TranscriptFrames.opsFrame(
                TranscriptFrames.textOp("t1.1.f1", "", kind = "thinking"),
                TranscriptFrames.appendOp("t1.1.f1", 0, "Listing."),
            ),
        )
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.textOp("t1.1.f2", ""), TranscriptFrames.appendOp("t1.1.f2", 0, "Here")))
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.appendOp("t1.1.f2", 4, " you go")))
        socket.send(
            TranscriptFrames.opsFrame(TranscriptFrames.toolOp("call_ls", "Bash", "running", ""","input":{"command":"ls"}""")),
        )

        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.ToolGroup>()?.any { g -> g.tools.any { it.id == "call_ls" } } == true
        }
        val items = viewModel.uiState.value.items!!
        assertTrue(viewModel.uiState.value.isWorking)
        assertEquals(listOf("msg_1"), items.filterIsInstance<ChatItem.UserMessage>().map { it.id })
        val bubble = items.filterIsInstance<ChatItem.AssistantMessage>().single()
        assertEquals("Listing.", bubble.thinking)
        assertEquals("Here you go", bubble.text)
        val tool = items.filterIsInstance<ChatItem.ToolGroup>().single().tools.single()
        assertEquals(ToolStatus.Running, tool.status)
        assertEquals("ls", tool.preview)
        // All of it from the socket alone: no history/status refetch went out.
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `the subscribe-time reset neither clears nor duplicates the REST history`() = runBlocking {
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_2","session_id":"session_1","role":"assistant",""" +
            """"content":[{"type":"text","text":"Earlier answer"}],"created_at":"2026-11-04T19:00:01.000Z"},""" +
            """{"id":"msg_1","session_id":"session_1","role":"user",""" +
            """"content":[{"type":"text","text":"Earlier question"}],"created_at":"2026-11-04T19:00:00.000Z"}""" +
            """],"has_more":false},"request_id":"r"}"""
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items?.size == 2 }
        val before = viewModel.uiState.value.items

        socket.send(TranscriptFrames.resetFrame(seq = 18))
        socket.send(TranscriptFrames.ackFrame())
        // A later live op proves the reset was processed (in-order stream).
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.promptOp("msg_9", "completed")))
        delay(300)

        assertEquals(before, viewModel.uiState.value.items)
        assertEquals(listOf("msg_1", "msg_2"), viewModel.uiState.value.items!!.map { it.id })
    }

    @Test
    fun `an unknown sub-agent's ops never render in the main chat`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }

        socket.send(TranscriptFrames.resetFrame(agentId = "agent-3", seq = 0))
        socket.send(
            TranscriptFrames.opsFrame(
                TranscriptFrames.turnOp("t0", "running"),
                TranscriptFrames.textOp("t0.1.f1", "child text", turnId = "t0"),
                TranscriptFrames.toolOp("call_child", "Bash", "running", ""","input":{"command":"pwd"}""", turnId = "t0"),
                agentId = "agent-3",
            ),
        )
        // A main op after it, so the child batch has certainly been processed.
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.textOp("t1.1.f1", "main text")))
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.any { it.text == "main text" } == true
        }

        assertEquals(
            listOf(ChatItem.AssistantMessage("live_0", "main text", "")),
            viewModel.uiState.value.items,
        )
        assertFalse(viewModel.uiState.value.isWorking)
    }

    @Test
    fun `an abort ends the running turn from the ops alone`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }

        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.promptOp("msg_1", "running"), TranscriptFrames.turnOp("t1", "running")))
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.stepOp("t1", "t1.1", "running")))
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.toolOp("call_sleep", "Bash", "running", ""","input":{"command":"sleep 60"}""")))
        awaitState(viewModel.uiState) { it.isWorking && it.items.orEmpty().isNotEmpty() }

        // The captured abort: step interrupted, turn cancelled, prompt aborted.
        socket.send(
            TranscriptFrames.opsFrame(
                TranscriptFrames.stepOp("t1", "t1.1", "interrupted"),
                TranscriptFrames.turnOp("t1", "cancelled"),
            ),
        )
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.promptOp("msg_1", "aborted")))

        awaitState(viewModel.uiState) { state ->
            !state.isWorking && state.items.orEmpty().any { it is ChatItem.TurnEnd && it.label == "Cancelled" }
        }
        assertNull(viewModel.uiState.value.stepRetry)
        // No row still spins: the running call settled (or the refetch replaced it).
        assertTrue(
            viewModel.uiState.value.items.orEmpty().filterIsInstance<ChatItem.ToolGroup>()
                .flatMap { it.tools }.none { it.status == ToolStatus.Running },
        )
    }

    @Test
    fun `a failed turn shows its error text in the footer`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }

        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.turnOp("t1", "running")))
        socket.send(
            TranscriptFrames.opsFrame(
                TranscriptFrames.stepOp("t1", "t1.1", "interrupted", ""","endReason":"error","endMessage":"[provider.auth_error] no access""""),
                TranscriptFrames.turnOp("t1", "failed", ""","error":"[provider.auth_error] no access""""),
            ),
        )

        awaitState(viewModel.uiState) { state ->
            state.items.orEmpty().any { it is ChatItem.TurnEnd && it.label == "Stopped: error — [provider.auth_error] no access" }
        }
        assertFalse(viewModel.uiState.value.isWorking)
    }

    @Test
    fun `a subscribe acked not_found surfaces as a load failure`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }

        socket.send(TranscriptFrames.ackFrame(accepted = emptyList(), notFound = listOf("session_1")))

        awaitState(viewModel.uiState) { it.error == "This session no longer exists on the server" }
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
    fun `a swarm's members all link to the one call from the cold-start roster`() = runBlocking {
        // Cold start mid-swarm (ticket 134): the snapshot roster's three members share
        // the AgentSwarm call's toolCallId, the messages page carries the call itself.
        // The store links every member (the one-to-many registry), the row stays
        // unlinked (the swarm card navigates by the tool call id), and the UiState
        // publishes the member list the card's aggregate reads (ticket 135).
        snapshotBody = """{"code":0,"msg":"success","data":{"as_of_seq":18,"epoch":"ep_1",""" +
            """"session":{"id":"session_1","busy":true,"usage":{"context_tokens":0,"context_limit":0}},""" +
            """"messages":{"items":[""" +
            """{"id":"msg_1","session_id":"session_1","role":"assistant",""" +
            """"content":[{"type":"tool_use","tool_call_id":"tool_swarm","tool_name":"AgentSwarm",""" +
            """"input":{"description":"review the docs","prompt_template":"Check {{item}}",""" +
            """"items":["a","b","c"]}}],""" +
            """"created_at":"2026-09-15T10:00:00.000Z"}],"has_more":false},""" +
            """"in_flight_turn":null,""" +
            """"subagents":[""" +
            swarmRosterEntry("agent-1", 0, "Check a") + "," +
            swarmRosterEntry("agent-2", 1, "Check b") + "," +
            swarmRosterEntry("agent-3", 2, "Check c") +
            """],"pending_approvals":[],"pending_questions":[]},"request_id":"r"}"""

        viewModel.onScreenStarted()

        awaitCondition {
            subagentStore.linksFor("session_1")["tool_swarm"] == listOf("agent-1", "agent-2", "agent-3")
        }
        awaitState(viewModel.uiState) { it.swarmMembers["tool_swarm"]?.size == 3 }
        // The AgentSwarm row itself never gains a subagentId (single-Agent only)...
        val row = viewModel.uiState.value.items.orEmpty()
            .filterIsInstance<ChatItem.ToolGroup>()
            .flatMap { it.tools }
            .single { it.id == "tool_swarm" }
        assertNull(row.subagentId)
        // ...the call's own description arg is the swarm's label...
        assertEquals("review the docs", subagentStore.swarmDescription("session_1", "tool_swarm").first())
        // ...and the members carry their roster-seeded index.
        assertEquals(1, subagentEntry("agent-2")!!.swarmIndex)
    }

    private fun swarmRosterEntry(id: String, index: Int, description: String): String =
        """{"id":"$id","session_id":"session_1","kind":"subagent","description":"$description",""" +
            """"status":"running","subagent_phase":"working","subagent_type":"explore",""" +
            """"parent_tool_call_id":"tool_swarm","run_in_background":false,"swarm_index":$index,""" +
            """"created_at":"2026-09-15T10:00:00.000Z","started_at":"2026-09-15T10:00:00.100Z"}"""

    @Test
    fun `a settled swarm's cold open rebuilds the roster from the result xml`() = runBlocking {
        // Ticket 136: reopened after the swarm settled — the roster cleared on the
        // main agent's next turn and no subagent.* frame ever replays, so the only
        // per-member record left is the call's <agent_swarm_result> (member shapes
        // verbatim from the upstream render, swarm.test.ts at 0.43.1).
        val resultXml = (
            "<agent_swarm_result>\n" +
                "<summary>completed: 1, failed: 1, aborted: 2</summary>\n" +
                "<resume_hint>Call AgentSwarm with resume_agent_ids using the agent_id values in this result to continue unfinished work.</resume_hint>\n" +
                "<subagent agent_id=\"agent-1\" item=\"src/a.ts\" outcome=\"completed\">imports are stable</subagent>\n" +
                "<subagent agent_id=\"agent-2\" item=\"src/b.ts\" outcome=\"failed\">Agent timed out after 30s.</subagent>\n" +
                "<subagent agent_id=\"agent-3\" item=\"src/c.ts\" state=\"started\" outcome=\"aborted\">The user manually interrupted this subagent batch before this subagent finished.</subagent>\n" +
                "<subagent item=\"src/d.ts\" state=\"not_started\" outcome=\"aborted\">The user manually interrupted this subagent batch before this subagent was started.</subagent>\n" +
                "</agent_swarm_result>"
            ).replace("\"", "\\\"").replace("\n", "\\n")
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_2","session_id":"session_1","role":"tool",""" +
            """"content":[{"type":"tool_result","tool_call_id":"tool_swarm","is_error":false,""" +
            """"output":"$resultXml"}],""" +
            """"created_at":"2026-09-15T10:05:00.000Z"},""" +
            """{"id":"msg_1","session_id":"session_1","role":"assistant",""" +
            """"content":[{"type":"tool_use","tool_call_id":"tool_swarm","tool_name":"AgentSwarm",""" +
            """"input":{"description":"review the docs","prompt_template":"Check {{item}}",""" +
            """"items":["src/a.ts","src/b.ts","src/c.ts","src/d.ts"]}}],""" +
            """"created_at":"2026-09-15T10:00:00.000Z"}""" +
            """],"has_more":false},"request_id":"r"}"""

        viewModel.onScreenStarted()

        // The roster rebuilds from the result alone (the snapshot roster is empty):
        // the three healed members plus the backfilled ghost, in document order.
        awaitCondition {
            runBlocking { subagentStore.swarmMembers("session_1", "tool_swarm").first().map { it.id } } ==
                listOf("agent-1", "agent-2", "agent-3", "tool_swarm:ghost:3")
        }
        assertEquals(SubagentStatus.Completed, subagentEntry("agent-1")!!.status)
        assertEquals(SubagentStatus.Failed, subagentEntry("agent-2")!!.status)
        assertEquals(SubagentStatus.Cancelled, subagentEntry("agent-3")!!.status)
        val ghost = subagentEntry("tool_swarm:ghost:3")!!
        assertEquals(SubagentStatus.Cancelled, ghost.status)
        assertEquals("src/d.ts", ghost.description)
        assertTrue(ghost.ghost)
        // The UiState carries the same member list for the swarm card's aggregate
        // (135): 1 completed · 1 failed · 2 cancelled.
        awaitState(viewModel.uiState) { state ->
            state.swarmMembers["tool_swarm"]?.map { it.id } ==
                listOf("agent-1", "agent-2", "agent-3", "tool_swarm:ghost:3")
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
    fun `a snapshot with real usage rings immediately`() = runBlocking {
        // Ticket 161: the bundle's usage has been real since 0.38.0 and is the
        // cold-start ring's authority — it paints from the snapshot alone, with
        // no /status round trip. The seed's GET is parked so nothing but the
        // bundle can set the ring here.
        snapshotBody = """{"code":0,"msg":"success","data":{"as_of_seq":1,"epoch":"ep_1",""" +
            """"session":{"id":"session_1","busy":false,""" +
            """"usage":{"context_tokens":25050,"context_limit":1048576}},""" +
            """"messages":{"items":[],"has_more":false},"in_flight_turn":null,"subagents":[],""" +
            """"pending_approvals":[],"pending_questions":[]},"request_id":"r"}"""
        statusResponseGate = CountDownLatch(1)

        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.contextUsage == ContextUsage(25050, 1048576) }

        statusResponseGate!!.countDown()
    }

    @Test
    fun `the status seed fills the ring and syncs the model on every cold start`() = runBlocking {
        // Ticket 107: the session's bound model/effort is served only by /status,
        // so the seed runs on EVERY cold start — and with an unknown-limit bundle
        // (the harness default: a zero limit maps to "unknown") the ring's fill
        // rides the same read.
        statusBody = """{"code":0,"msg":"success","data":{"busy":false,""" +
            """"model":"kimi-code/kimi-for-coding","thinking_level":"low",""" +
            """"context_tokens":25050,"max_context_tokens":1048576,"context_usage":0.0239},"request_id":"r"}"""

        viewModel.onScreenStarted()
        awaitRequest("/status")

        awaitState(viewModel.uiState) { it.contextUsage == ContextUsage(25050, 1048576) }
        awaitState(viewModel.uiState) { it.sessionModel == "kimi-code/kimi-for-coding" }
        assertEquals("low", viewModel.uiState.value.sessionEffort)
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
    fun `queued prompt bubble shows Queued #N until prompt started clears it`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onInputChange("second prompt")
        viewModel.onSend()
        // The ACK's prompt id lands the optimistic local echo at once — no splice yet
        // (ticket 154: a queued prompt's splice arrives only when its turn starts).
        awaitState(viewModel.uiState) { it.items?.any { item -> item.id == "msg_1" } == true }

        socket.send(promptQueuedFrame(promptId = "msg_1", queueLength = 1))
        awaitState(viewModel.uiState) { (it.items?.firstOrNull() as? ChatItem.UserMessage)?.queued == 1 }

        // The running turn completes: the turn.ended refetch must NOT drop the queued
        // bubble — its message is not committed to history yet.
        socket.send(turnEndedFrame(reason = "completed"))
        awaitRequest("/messages")
        delay(300)
        assertEquals(
            1,
            (viewModel.uiState.value.items?.firstOrNull() as? ChatItem.UserMessage)?.queued,
        )

        // The queued prompt's own turn starts: prompt.started flips the bubble back
        // to the normal in-flight look.
        socket.send(promptStartedFrame(promptId = "msg_1"))
        awaitState(viewModel.uiState) { (it.items?.firstOrNull() as? ChatItem.UserMessage)?.queued == null }
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
            unreadStore = unreadStore,
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
            unreadStore = unreadStore,
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
            unreadStore = unreadStore,
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
            unreadStore = unreadStore,
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

        // Persisted: the next prompt carries the server-reported values. The
        // write-through lands off the seed's call stack (syncServerSessionModel
        // launches it) — poll until it commits, like the agent-status test below.
        withTimeout(5_000) {
            while (dataStore.data.first()[stringPreferencesKey("effort_for_session_session_1")] != "low") delay(10)
        }
        // The /status seed and the /models fetch race: promptThinking drops the effort
        // stamp while the models cache is cold (ticket 163), so wait for it before the
        // send assertion below.
        awaitState(viewModel.uiState) { it.modelsAvailable }

        viewModel.onInputChange("hi")
        viewModel.onSend()
        val body = awaitRequest("/prompts").body!!.utf8()
        assertTrue(""""model":"deepseek/deepseek-v4-pro"""" in body)
        assertTrue(""""thinking":"low"""" in body)
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

    // --- Cloud switch policies (tickets 167/170) ---------------------------------

    /** The catalog and alias map the gate tests share: k3 → k3.1-pro is the switch
     * the captured live rules gate. */
    private fun configureSwitchCatalog() {
        modelsBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"provider":"managed:kimi-code","model":"kimi-code/k3","display_name":"K3",""" +
            """"max_context_size":1048576,"support_efforts":["low","high","max"],"default_effort":"high"},""" +
            """{"provider":"managed:kimi-code","model":"kimi-code/k3.1-pro","display_name":"K3.1 Pro",""" +
            """"max_context_size":1048576,"support_efforts":["low","high","max"],"default_effort":"high"},""" +
            """{"provider":"deepseek","model":"deepseek/deepseek-v4-pro","display_name":"DeepSeek V4 Pro",""" +
            """"max_context_size":1000000,"support_efforts":["low","high","max"],"default_effort":"high"}""" +
            """]},"request_id":"r"}"""
        configBody = """{"code":0,"msg":"success","data":{"default_model":"kimi-code/k3",""" +
            """"models":{"kimi-code/k3":{"model":"k3"},"kimi-code/k3.1-pro":{"model":"k3.1-pro"}},""" +
            """"thinking":{"effort":"high"}},"request_id":"r"}"""
    }

    private fun configureModelSwitchRule(sessionType: String) {
        modelSwitchConfigBody = """{"code":0,"msg":"success","data":{"name":"model_switch_confirm",""" +
            """"config":{"enable":true,"rules":[{"from":"k3","to":"k3.1-pro","session_type":"$sessionType",""" +
            """"hint":{"en":"Switching may affect quality"},"warning":{"en":"Costs about twice as much"}}]}},"request_id":"r"}"""
    }

    private fun configureEffortSwitchRule(sessionType: String) {
        effortSwitchConfigBody = """{"code":0,"msg":"success","data":{"name":"effort_switch_confirm",""" +
            """"config":{"descriptions":{"enable":true,"items":{"low":{"en":"Light reasoning"},""" +
            """"max":{"en":"Maximum reasoning"}}},"confirm":{"enable":true,"rules":[""" +
            """{"from":"*","to":"*","session_type":"$sessionType","hint":{"en":"Affects output quality"}}]}}},"request_id":"r"}"""
    }

    @Test
    fun `a matching model rule opens the dialog and only Continue persists the pick`() = runBlocking {
        configureSwitchCatalog()
        configureModelSwitchRule("new")
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable && it.modelAliases.isNotEmpty() }

        viewModel.onModelPicked("kimi-code/k3.1-pro")

        val pending = viewModel.uiState.value.pendingModelSwitch
        assertEquals("kimi-code/k3.1-pro", pending?.target)
        assertEquals("K3", pending?.fromLabel)
        assertEquals("K3.1 Pro", pending?.toLabel)
        assertEquals("Switching may affect quality", pending?.hint)
        assertEquals("Costs about twice as much", pending?.warning)
        // Nothing is committed before Continue.
        assertNull(viewModel.uiState.value.sessionModel)
        assertNull(dataStore.data.first()[stringPreferencesKey("model_for_session_session_1")])

        viewModel.onModelSwitchConfirmed()
        awaitState(viewModel.uiState) { it.sessionModel == "kimi-code/k3.1-pro" }
        assertNull(viewModel.uiState.value.pendingModelSwitch)
        assertEquals(
            "kimi-code/k3.1-pro",
            dataStore.data.first()[stringPreferencesKey("model_for_session_session_1")],
        )
    }

    @Test
    fun `Cancel leaves the model preference untouched`() = runBlocking {
        configureSwitchCatalog()
        configureModelSwitchRule("new")
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable && it.modelAliases.isNotEmpty() }

        viewModel.onModelPicked("kimi-code/k3.1-pro")
        viewModel.onModelSwitchDismissed()

        assertNull(viewModel.uiState.value.pendingModelSwitch)
        assertNull(viewModel.uiState.value.sessionModel)
        assertNull(dataStore.data.first()[stringPreferencesKey("model_for_session_session_1")])
    }

    @Test
    fun `an old-session rule gates a chat with history`() = runBlocking {
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_0","role":"user","content":[{"type":"text","text":"earlier question"}]}""" +
            """]},"request_id":"r"}"""
        configureSwitchCatalog()
        configureModelSwitchRule("old")
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable && it.modelAliases.isNotEmpty() }
        awaitState(viewModel.uiState) { it.items?.isNotEmpty() == true }

        viewModel.onModelPicked("kimi-code/k3.1-pro")

        assertEquals("kimi-code/k3.1-pro", viewModel.uiState.value.pendingModelSwitch?.target)
    }

    @Test
    fun `a new-session-only rule does not gate a chat with history`() = runBlocking {
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_0","role":"user","content":[{"type":"text","text":"earlier question"}]}""" +
            """]},"request_id":"r"}"""
        configureSwitchCatalog()
        configureModelSwitchRule("new")
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable && it.modelAliases.isNotEmpty() }
        awaitState(viewModel.uiState) { it.items?.isNotEmpty() == true }

        viewModel.onModelPicked("kimi-code/k3.1-pro")

        assertNull(viewModel.uiState.value.pendingModelSwitch)
        awaitState(viewModel.uiState) { it.sessionModel == "kimi-code/k3.1-pro" }
    }

    @Test
    fun `a null config preserves immediate model selection`() = runBlocking {
        configureSwitchCatalog()
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable }

        viewModel.onModelPicked("kimi-code/k3.1-pro")

        assertNull(viewModel.uiState.value.pendingModelSwitch)
        awaitState(viewModel.uiState) { it.sessionModel == "kimi-code/k3.1-pro" }
    }

    @Test
    fun `a nonmatching rule preserves immediate model selection`() = runBlocking {
        configureSwitchCatalog()
        // The rule gates only a kimi-for-coding → k3.1-pro switch.
        modelSwitchConfigBody = """{"code":0,"msg":"success","data":{"name":"model_switch_confirm",""" +
            """"config":{"enable":true,"rules":[{"from":"kimi-for-coding","to":"k3.1-pro","session_type":"all"}]}},"request_id":"r"}"""
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable && it.modelAliases.isNotEmpty() }

        viewModel.onModelPicked("kimi-code/k3.1-pro")

        assertNull(viewModel.uiState.value.pendingModelSwitch)
        awaitState(viewModel.uiState) { it.sessionModel == "kimi-code/k3.1-pro" }
    }

    @Test
    fun `the effort gate opens on an old-session rule and Cancel writes nothing`() = runBlocking {
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_0","role":"user","content":[{"type":"text","text":"earlier question"}]}""" +
            """]},"request_id":"r"}"""
        configureSwitchCatalog()
        configureEffortSwitchRule("old")
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable && it.effortDescriptions.isNotEmpty() }
        awaitState(viewModel.uiState) { it.items?.isNotEmpty() == true }

        assertEquals("high", viewModel.uiState.value.effectiveEffort)
        assertEquals("managed:kimi-code", viewModel.uiState.value.currentModel?.provider)
        viewModel.onEffortPicked("max")

        val pending = viewModel.uiState.value.pendingEffortSwitch
        assertEquals("max", pending?.target)
        assertEquals("Affects output quality", pending?.hint)
        assertNull(dataStore.data.first()[stringPreferencesKey("effort_for_session_session_1")])

        viewModel.onEffortSwitchDismissed()
        assertNull(viewModel.uiState.value.pendingEffortSwitch)
        assertNull(dataStore.data.first()[stringPreferencesKey("effort_for_session_session_1")])

        // The next pick goes through the gate again, and Continue persists it.
        viewModel.onEffortPicked("max")
        viewModel.onEffortSwitchConfirmed()
        awaitState(viewModel.uiState) { it.sessionEffort == "max" }
        assertEquals("max", dataStore.data.first()[stringPreferencesKey("effort_for_session_session_1")])
    }

    @Test
    fun `effort descriptions apply to the managed provider only`() = runBlocking {
        configureSwitchCatalog()
        configureEffortSwitchRule("old")
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable && it.effortDescriptions.isNotEmpty() }

        // The effective effort is the model's default ("high" → no description in this
        // fixture); pick "low", which the config describes.
        viewModel.onEffortSelected("low")
        awaitState(viewModel.uiState) { it.effectiveEffort == "low" }
        assertEquals("Light reasoning", viewModel.uiState.value.activeEffortDescription)

        // A non-managed provider renders no description even with the same effort.
        viewModel.onModelSelected("deepseek/deepseek-v4-pro")
        awaitState(viewModel.uiState) { it.currentModel?.provider == "deepseek" }
        assertEquals("low", viewModel.uiState.value.effectiveEffort)
        assertNull(viewModel.uiState.value.activeEffortDescription)
    }

    @Test
    fun `a rule with no alias metadata preserves immediate selection`() = runBlocking {
        // Catalog without the config's alias map: the switch cannot be resolved to
        // upstream ids, so the gate stays out of the way.
        configureSwitchCatalog()
        configBody = """{"code":0,"msg":"success","data":{"default_model":"kimi-code/k3"},"request_id":"r"}"""
        configureModelSwitchRule("all")
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable }

        viewModel.onModelPicked("kimi-code/k3.1-pro")

        assertNull(viewModel.uiState.value.pendingModelSwitch)
        awaitState(viewModel.uiState) { it.sessionModel == "kimi-code/k3.1-pro" }
    }

    // --- Cloud switch policies end ------------------------------------------------

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
            unreadStore = unreadStore,
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
    fun `onCompact is a no-op while a turn runs`() = runBlocking {
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

    // --- Detaching a member to the background (ticket 138) ---

}
