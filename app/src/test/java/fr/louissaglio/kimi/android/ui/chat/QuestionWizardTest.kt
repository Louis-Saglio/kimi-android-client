package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.PendingQuestion
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Question-wizard coverage (the [QuestionWizard] collaborator, ticket 072): the
 * pending-question lifecycle (queue, drafts, wizard step), answer submission and
 * dismissal, driven end-to-end through the ViewModel on the shared
 * [ChatViewModelTestBase] harness.
 */
class QuestionWizardTest : ChatViewModelTestBase() {

    @Test
    fun `question request event shows the card and answered event clears it`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()

        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
        assertEquals(
            listOf(
                PendingQuestion(
                    questionId = "tool_q1",
                    toolCallId = "tool_q1",
                    items = listOf(
                        PendingQuestion.Item(
                            id = "q_0",
                            header = "Color",
                            text = "Red or blue?",
                            options = listOf(
                                PendingQuestion.Option("opt_0_0", "red", "warm color"),
                                PendingQuestion.Option("opt_0_1", "blue", "cool color"),
                            ),
                            allowOther = true,
                        ),
                    ),
                ),
            ),
            viewModel.uiState.value.pendingQuestions,
        )

        // Answered from another client: the card clears without local action.
        socket.send(questionAnsweredFrame("tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isEmpty() }
    }

    @Test
    fun `a sub-agent's question queues with its asking agent and the badge name resolves from the store`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()

        // The member's identity lands first (subagent.spawned → the store), then its
        // question arrives stamped with its id (ticket 156): it queues like any
        // question — the stamp is presentation only (the card's sub-agent badge,
        // 139's idiom on the question surface).
        socket.send(subagentSpawnedFrame())
        socket.send(questionRequestedFrame(id = "tool_q1", agentId = "agent-12"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
        assertEquals("agent-12", viewModel.uiState.value.pendingQuestions.single().agentId)
        // The badge's name is the store record's subagent name.
        awaitState(viewModel.uiState) { it.subagentNames["agent-12"] == "explore" }
    }

    @Test
    fun `a sub-agent question racing ahead of its record queues without a badge name, which the late spawn heals`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()

        // The question can arrive before subagent.spawned (ticket 156): the card must
        // not wait for the record — no badge name, the plain "Sub-agent" fallback.
        socket.send(questionRequestedFrame(id = "tool_q1", agentId = "agent-1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
        assertEquals("agent-1", viewModel.uiState.value.pendingQuestions.single().agentId)
        assertNull(viewModel.uiState.value.subagentNames["agent-1"])

        // The late spawned frame lands the record — the badge gains its name live.
        socket.send(subagentSpawnedFrame(id = "agent-1"))
        awaitState(viewModel.uiState) { it.subagentNames["agent-1"] == "explore" }
    }

    @Test
    fun `questions poll in flight does not clear a question that just arrived`() = runBlocking {
        // The cold start owns the first start's question state (ticket 085), so the
        // guarded /questions seed this exercises runs on the within-process resume.
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        serverSocket = null
        viewModel.onScreenStopped()

        val gate = CountDownLatch(1)
        questionsResponseGate = gate
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()

        // The /questions response is parked server-side: the event lands first.
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        // The stale empty snapshot arrives after the event and must be dropped.
        gate.countDown()
        delay(200)
        assertEquals(listOf("tool_q1"), viewModel.uiState.value.pendingQuestions.map { it.questionId })
    }

    @Test
    fun `submit posts the picked option and the card clears on answered`() = runBlocking {
        // 0.38.0 split ids (ticket 106): the answer routes by question_id, not the
        // tool call's.
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "question_q1", toolCallId = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
        viewModel.onSubmitQuestion()

        val request = awaitRequest("/questions/")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/questions/question_q1", request.target)
        assertEquals("""{"answers":{"q_0":{"kind":"single","option_id":"opt_0_1"}}}""", request.body!!.utf8())

        // The ack alone must not clear the card — only the answered event does.
        delay(300)
        assertEquals(1, viewModel.uiState.value.pendingQuestions.size)
        socket.send(questionAnsweredFrame("question_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isEmpty() }
    }

    @Test
    fun `free text answer posts kind other`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        viewModel.onQuestionOtherTextChange("q_0", "green")
        viewModel.onSubmitQuestion()

        val request = awaitRequest("/questions/")
        assertEquals("""{"answers":{"q_0":{"kind":"other","text":"green"}}}""", request.body!!.utf8())
    }

    @Test
    fun `multi_select flag parses true false and absent as false`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(multiFlagQuestionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        assertEquals(
            listOf(true, false, false),
            viewModel.uiState.value.pendingQuestions.single().items.map { it.multiSelect },
        )
    }

    @Test
    fun `toggle ticks and unticks options of a multi item`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(multiSelectQuestionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
        assertFalse(viewModel.uiState.value.isQuestionStepAnswered)

        viewModel.onQuestionOptionToggled("q_0", "opt_0_0")
        viewModel.onQuestionOptionToggled("q_0", "opt_0_2")
        assertEquals(setOf("opt_0_0", "opt_0_2"), viewModel.uiState.value.questionSelections["q_0"])
        assertTrue(viewModel.uiState.value.isQuestionStepAnswered)

        viewModel.onQuestionOptionToggled("q_0", "opt_0_0")
        assertEquals(setOf("opt_0_2"), viewModel.uiState.value.questionSelections["q_0"])

        // Unticking the last option drops the entry and un-answers the item.
        viewModel.onQuestionOptionToggled("q_0", "opt_0_2")
        assertNull(viewModel.uiState.value.questionSelections["q_0"])
        assertFalse(viewModel.uiState.value.isQuestionStepAnswered)
        assertFalse(viewModel.uiState.value.isQuestionSubmittable)
    }

    @Test
    fun `toggle no-ops for an option id the item does not carry`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(multiSelectQuestionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        viewModel.onQuestionOptionToggled("q_0", "opt_bogus")

        assertNull(viewModel.uiState.value.questionSelections["q_0"])
        assertFalse(viewModel.uiState.value.isQuestionStepAnswered)
    }

    @Test
    fun `multi item is answered by non-blank other text alone`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(multiSelectQuestionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        viewModel.onQuestionOtherTextChange("q_0", "Purple")

        assertTrue(viewModel.uiState.value.isQuestionStepAnswered)
        assertTrue(viewModel.uiState.value.isQuestionSubmittable)
    }

    @Test
    fun `submit of ticked multi options posts kind multi in wire order`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(multiSelectQuestionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        // Toggled out of order: the body must follow the item's option order.
        viewModel.onQuestionOptionToggled("q_0", "opt_0_2")
        viewModel.onQuestionOptionToggled("q_0", "opt_0_0")
        viewModel.onSubmitQuestion()

        val request = awaitRequest("/questions/")
        assertEquals(
            """{"answers":{"q_0":{"kind":"multi","option_ids":["opt_0_0","opt_0_2"]}}}""",
            request.body!!.utf8(),
        )
    }

    @Test
    fun `submit of ticks plus other text posts kind multi_with_other`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(multiSelectQuestionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        viewModel.onQuestionOptionToggled("q_0", "opt_0_0")
        viewModel.onQuestionOtherTextChange("q_0", "Purple")
        viewModel.onSubmitQuestion()

        val request = awaitRequest("/questions/")
        assertEquals(
            """{"answers":{"q_0":{"kind":"multi_with_other","option_ids":["opt_0_0"],"other_text":"Purple"}}}""",
            request.body!!.utf8(),
        )
    }

    @Test
    fun `other text alone on a multi item posts kind other`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(multiSelectQuestionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        viewModel.onQuestionOtherTextChange("q_0", "Purple")
        viewModel.onSubmitQuestion()

        val request = awaitRequest("/questions/")
        assertEquals("""{"answers":{"q_0":{"kind":"other","text":"Purple"}}}""", request.body!!.utf8())
    }

    @Test
    fun `submit is a no-op while an item is unanswered`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
        // Drain the history/status/questions/WS requests recorded so far.
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onSubmitQuestion()

        delay(200)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        assertEquals(1, viewModel.uiState.value.pendingQuestions.size)
    }

    @Test
    fun `wizard next advances only when the current item is answered`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(multiItemQuestionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        assertEquals(0, viewModel.uiState.value.questionStep)
        assertFalse(viewModel.uiState.value.isQuestionStepAnswered)

        // The current item has no answer yet: Next is a no-op.
        viewModel.onQuestionNext()
        assertEquals(0, viewModel.uiState.value.questionStep)

        viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
        assertTrue(viewModel.uiState.value.isQuestionStepAnswered)
        viewModel.onQuestionNext()
        assertEquals(1, viewModel.uiState.value.questionStep)
    }

    @Test
    fun `wizard next is a no-op on the last item`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(multiItemQuestionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
        viewModel.onQuestionNext()
        viewModel.onQuestionOptionPicked("q_1", "opt_1_0")
        viewModel.onQuestionNext()
        assertEquals(2, viewModel.uiState.value.questionStep)

        viewModel.onQuestionOptionPicked("q_2", "opt_2_0")
        viewModel.onQuestionNext()
        assertEquals(2, viewModel.uiState.value.questionStep)
    }

    @Test
    fun `wizard back returns to the previous item and keeps the draft`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(multiItemQuestionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
        viewModel.onQuestionNext()
        assertEquals(1, viewModel.uiState.value.questionStep)

        viewModel.onQuestionBack()
        assertEquals(0, viewModel.uiState.value.questionStep)
        assertEquals(setOf("opt_0_1"), viewModel.uiState.value.questionSelections["q_0"])

        // Back on the first item is a no-op.
        viewModel.onQuestionBack()
        assertEquals(0, viewModel.uiState.value.questionStep)
    }

    @Test
    fun `wizard step resets when the queue head advances`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(multiItemQuestionRequestedFrame(id = "tool_q1"))
        socket.send(questionRequestedFrame(id = "tool_q2"))
        awaitState(viewModel.uiState) { it.pendingQuestions.size == 2 }

        viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
        viewModel.onQuestionNext()
        assertEquals(1, viewModel.uiState.value.questionStep)

        socket.send(questionAnsweredFrame("tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.map { q -> q.questionId } == listOf("tool_q2") }
        assertEquals(0, viewModel.uiState.value.questionStep)
    }

    @Test
    fun `minimize and reopen keep the question and its drafts`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
        viewModel.onMinimizeQuestion()
        assertTrue(viewModel.uiState.value.questionCardMinimized)

        // Minimize is purely local: nothing is posted, the queue and drafts are kept.
        delay(200)
        assertEquals(1, viewModel.uiState.value.pendingQuestions.size)
        assertEquals(setOf("opt_0_1"), viewModel.uiState.value.questionSelections["q_0"])

        viewModel.onReopenQuestion()
        assertFalse(viewModel.uiState.value.questionCardMinimized)
        assertEquals(setOf("opt_0_1"), viewModel.uiState.value.questionSelections["q_0"])
        assertEquals(1, viewModel.uiState.value.pendingQuestions.size)
    }

    @Test
    fun `toggle flips the minimized flag both ways`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        viewModel.onToggleQuestionCard()
        assertTrue(viewModel.uiState.value.questionCardMinimized)
        viewModel.onToggleQuestionCard()
        assertFalse(viewModel.uiState.value.questionCardMinimized)
    }

    @Test
    fun `minimized flag survives a queued-behind request but resets when the head changes`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
        viewModel.onMinimizeQuestion()

        // A question queueing behind the head does not surface: the minimized card is
        // still the head's, so the flag stays.
        socket.send(questionRequestedFrame(id = "tool_q2"))
        awaitState(viewModel.uiState) { it.pendingQuestions.size == 2 }
        assertTrue(viewModel.uiState.value.questionCardMinimized)

        // The head resolves and the queued question slides in — always expanded.
        socket.send(questionAnsweredFrame("tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.map { q -> q.questionId } == listOf("tool_q2") }
        assertFalse(viewModel.uiState.value.questionCardMinimized)
    }

    @Test
    fun `minimized flag resets when the head question resolves`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
        viewModel.onMinimizeQuestion()
        assertTrue(viewModel.uiState.value.questionCardMinimized)

        socket.send(questionAnsweredFrame("tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isEmpty() }
        assertFalse(viewModel.uiState.value.questionCardMinimized)
    }

    @Test
    fun `dismiss posts to the dismiss endpoint and the card clears on dismissed`() = runBlocking {
        // 0.38.0 split ids (ticket 106): dismiss routes by question_id.
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "question_q1", toolCallId = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        viewModel.onDismissQuestion()

        val request = awaitRequest(":dismiss")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/questions/question_q1:dismiss", request.target)
        assertEquals("{}", request.body!!.utf8())

        socket.send(questionDismissedFrame("question_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isEmpty() }
    }

    @Test
    fun `failed answer post keeps the card and emits QuestionFailed`() = runBlocking {
        answersSucceed = false
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
            viewModel.onSubmitQuestion()
            awaitRequest("/questions/")
            awaitCondition { effects.isNotEmpty() }

            assertEquals(
                ChatViewModel.Effect.QuestionFailed("question tool_q1 not found"),
                effects.single(),
            )
            // No resolved event will come: the card must stay up.
            assertEquals(1, viewModel.uiState.value.pendingQuestions.size)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `pending question survives the turn ended refetch`() = runBlocking {
        // The snapshot's pending_questions are the cold-start source of the card
        // (ticket 085 — same item shape as the poll); the poll endpoint stays the
        // source of truth the turn.ended re-derivation reads, so it keeps returning
        // the question too.
        val questionItem = """{"type":"event.question.requested","question_id":"tool_q1","session_id":"session_1",""" +
            """"turn_id":0,"tool_call_id":"tool_q1",""" +
            """"questions":[{"id":"q_0","question":"Red or blue?","header":"Color",""" +
            """"options":[{"id":"opt_0_0","label":"red"},{"id":"opt_0_1","label":"blue"}],"allow_other":false}],""" +
            """"created_at":"2026-07-20T06:06:49.808Z","agentId":"main","sessionId":"session_1"}"""
        snapshotBody = """{"code":0,"msg":"success","data":{"as_of_seq":1,"epoch":"ep_1",""" +
            """"session":{"id":"session_1","busy":false,"usage":{"context_tokens":0,"context_limit":1048576}},""" +
            """"messages":{"items":[],"has_more":false},"in_flight_turn":null,"subagents":[],""" +
            """"pending_approvals":[],"pending_questions":[$questionItem]},"request_id":"r"}"""
        questionsBody = """{"code":0,"msg":"success","data":{"items":[$questionItem]},"request_id":"r"}"""
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }
        // Drain the snapshot/session/models/WS requests recorded so far so the next
        // questions poll observed is the one turn.ended triggers.
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        socket.send(turnEndedFrame(reason = "completed"))

        awaitRequest("/questions")
        delay(300)
        assertEquals(listOf("tool_q1"), viewModel.uiState.value.pendingQuestions.map { it.questionId })
    }

    @Test
    fun `resolved row tap opens the read-only view with the answer marked`() = runBlocking {
        viewModel.onQuestionRowTapped("tool_old", resolvedInput, resolvedOutput)

        val view = viewModel.uiState.value.resolvedQuestionView!!
        assertEquals("tool_old", view.toolCallId)
        assertNull(view.note)
        assertEquals(listOf("blue"), view.items.single().selectedLabels)
        assertEquals(0, viewModel.uiState.value.resolvedQuestionStep)
    }

    @Test
    fun `re-tapping the view's own row closes it and an unparseable row no-ops`() = runBlocking {
        viewModel.onQuestionRowTapped("tool_old", resolvedInput, resolvedOutput)
        assertTrue(viewModel.uiState.value.resolvedQuestionView != null)

        viewModel.onQuestionRowTapped("tool_old", resolvedInput, resolvedOutput)
        assertNull(viewModel.uiState.value.resolvedQuestionView)

        // Background-question plain-text result: nothing to show, nothing happens.
        viewModel.onQuestionRowTapped("tool_bg", resolvedInput, "task_id: 1\nstatus: running")
        assertNull(viewModel.uiState.value.resolvedQuestionView)
    }

    @Test
    fun `resolved view paginates without answer gating`() = runBlocking {
        viewModel.onQuestionRowTapped("tool_old", multiItemResolvedInput, multiItemResolvedOutput)
        assertEquals(0, viewModel.uiState.value.resolvedQuestionStep)

        viewModel.onResolvedQuestionNext()
        assertEquals(1, viewModel.uiState.value.resolvedQuestionStep)
        viewModel.onResolvedQuestionNext()
        assertEquals(1, viewModel.uiState.value.resolvedQuestionStep)
        viewModel.onResolvedQuestionBack()
        assertEquals(0, viewModel.uiState.value.resolvedQuestionStep)

        viewModel.onCloseResolvedQuestion()
        assertNull(viewModel.uiState.value.resolvedQuestionView)
        assertEquals(0, viewModel.uiState.value.resolvedQuestionStep)
    }

    @Test
    fun `resolved view hides the live card which returns on close`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "question_q1", toolCallId = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        viewModel.onQuestionRowTapped("tool_old", resolvedInput, resolvedOutput)
        assertTrue(viewModel.uiState.value.resolvedQuestionView != null)
        // The live question is untouched behind the view.
        assertEquals(listOf("question_q1"), viewModel.uiState.value.pendingQuestions.map { it.questionId })

        // Tapping the pending row (its tool_call_id, ticket 106) while the view is
        // open surfaces the live card.
        viewModel.onQuestionRowTapped("tool_q1", "ignored", "ignored")
        assertNull(viewModel.uiState.value.resolvedQuestionView)
        assertEquals(listOf("question_q1"), viewModel.uiState.value.pendingQuestions.map { it.questionId })
        assertFalse(viewModel.uiState.value.questionCardMinimized)
    }

    @Test
    fun `a newly surfaced question closes the resolved view and shows expanded`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        viewModel.onQuestionRowTapped("tool_old", resolvedInput, resolvedOutput)
        assertTrue(viewModel.uiState.value.resolvedQuestionView != null)

        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
        assertNull(viewModel.uiState.value.resolvedQuestionView)
        assertFalse(viewModel.uiState.value.questionCardMinimized)
    }

    @Test
    fun `pending row tap still toggles the live card`() = runBlocking {
        // Ticket 106: since 0.38.0 the pending question's question_id differs from
        // its tool row's tool_call_id — the row tap correlates via the latter.
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "question_q1", toolCallId = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        viewModel.onQuestionRowTapped("tool_q1", "ignored", "ignored")
        assertTrue(viewModel.uiState.value.questionCardMinimized)
        viewModel.onQuestionRowTapped("tool_q1", "ignored", "ignored")
        assertFalse(viewModel.uiState.value.questionCardMinimized)

        // The question_id is not a row id: a tap on it no-ops (the resolved-row
        // parser rejects the unparseable strings).
        viewModel.onQuestionRowTapped("question_q1", "ignored", "ignored")
        assertFalse(viewModel.uiState.value.questionCardMinimized)
        assertNull(viewModel.uiState.value.resolvedQuestionView)
    }

    private val resolvedInput = """{"questions":[{"question":"Red or blue?","header":"Color",""" +
        """"options":[{"label":"red"},{"label":"blue"}],"multi_select":false}]}"""
    private val resolvedOutput = """{"answers":{"Red or blue?":"blue"}}"""
    private val multiItemResolvedInput = """{"questions":[""" +
        """{"question":"Red or blue?","header":"Color","options":[{"label":"red"},{"label":"blue"}],"multi_select":false},""" +
        """{"question":"Fast or safe?","header":"Mode","options":[{"label":"fast"},{"label":"safe"}],"multi_select":false}]}"""
    private val multiItemResolvedOutput = """{"answers":{"Red or blue?":"red","Fast or safe?":"fast"}}"""

    /** Verbatim shape (docs/server-api.md, "Core event payloads"). Since 0.38.0
     * `question_id` is a dedicated `question_<uuid>` id distinct from `tool_call_id`
     * (ticket 106) — [toolCallId] defaults to the pre-0.38.0 equality. [agentId] is
     * the 2.0.2 snake_case asking-agent stamp (ticket 156). */
    private fun questionRequestedFrame(id: String, toolCallId: String = id, agentId: String = "main"): String =
        """{"type":"event.question.requested","seq":9,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-20T06:06:49.808Z","payload":{"type":"event.question.requested",""" +
            """"question_id":"$id","session_id":"session_1","turn_id":0,"tool_call_id":"$toolCallId",""" +
            """"agent_id":"$agentId",""" +
            """"questions":[{"id":"q_0","question":"Red or blue?","header":"Color",""" +
            """"options":[{"id":"opt_0_0","label":"red","description":"warm color"},""" +
            """{"id":"opt_0_1","label":"blue","description":"cool color"}],"allow_other":true}],""" +
            """"created_at":"2026-07-20T06:06:49.808Z","agentId":"main","sessionId":"session_1"}}"""

    /** Three-item variant of [questionRequestedFrame], for the wizard tests (ticket 035). */
    private fun multiItemQuestionRequestedFrame(id: String): String =
        """{"type":"event.question.requested","seq":9,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-20T06:06:49.808Z","payload":{"type":"event.question.requested",""" +
            """"question_id":"$id","session_id":"session_1","turn_id":0,"tool_call_id":"$id",""" +
            """"questions":[""" +
            """{"id":"q_0","question":"Red or blue?","header":"Color",""" +
            """"options":[{"id":"opt_0_0","label":"red","description":"warm color"},""" +
            """{"id":"opt_0_1","label":"blue","description":"cool color"}],"allow_other":true},""" +
            """{"id":"q_1","question":"Fast or safe?","header":"Mode",""" +
            """"options":[{"id":"opt_1_0","label":"fast","description":"skip checks"},""" +
            """{"id":"opt_1_1","label":"safe","description":"run checks"}],"allow_other":false},""" +
            """{"id":"q_2","question":"Yes or no?","header":"Confirm",""" +
            """"options":[{"id":"opt_2_0","label":"yes","description":"go ahead"},""" +
            """{"id":"opt_2_1","label":"no","description":"stop"}],"allow_other":false}],""" +
            """"created_at":"2026-07-20T06:06:49.808Z","agentId":"main","sessionId":"session_1"}}"""

    /** One multi-select item with three options and Other allowed (ticket 090). */
    private fun multiSelectQuestionRequestedFrame(id: String): String =
        """{"type":"event.question.requested","seq":9,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-20T06:06:49.808Z","payload":{"type":"event.question.requested",""" +
            """"question_id":"$id","session_id":"session_1","turn_id":0,"tool_call_id":"$id",""" +
            """"questions":[{"id":"q_0","question":"Colors?","header":"Palette",""" +
            """"options":[{"id":"opt_0_0","label":"Red"},{"id":"opt_0_1","label":"Green"},""" +
            """{"id":"opt_0_2","label":"Blue"}],"allow_other":true,"multi_select":true}],""" +
            """"created_at":"2026-07-20T06:06:49.808Z","agentId":"main","sessionId":"session_1"}}"""

    /** Three items carrying `multi_select` as true, false and absent (090 parsing). */
    private fun multiFlagQuestionRequestedFrame(id: String): String =
        """{"type":"event.question.requested","seq":9,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-20T06:06:49.808Z","payload":{"type":"event.question.requested",""" +
            """"question_id":"$id","session_id":"session_1","turn_id":0,"tool_call_id":"$id",""" +
            """"questions":[""" +
            """{"id":"q_0","question":"Multi?","header":"M",""" +
            """"options":[{"id":"opt_0_0","label":"a"}],"allow_other":false,"multi_select":true},""" +
            """{"id":"q_1","question":"Single?","header":"S",""" +
            """"options":[{"id":"opt_1_0","label":"b"}],"allow_other":false,"multi_select":false},""" +
            """{"id":"q_2","question":"Absent?","header":"A",""" +
            """"options":[{"id":"opt_2_0","label":"c"}],"allow_other":false}],""" +
            """"created_at":"2026-07-20T06:06:49.808Z","agentId":"main","sessionId":"session_1"}}"""

    private fun questionAnsweredFrame(id: String): String =
        """{"type":"event.question.answered","seq":11,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-20T06:08:12.548Z","payload":{"type":"event.question.answered",""" +
            """"question_id":"$id","answers":{"Red or blue?":"blue"},""" +
            """"resolved_at":"2026-07-20T06:08:12.548Z","agentId":"main","sessionId":"session_1"}}"""

    private fun questionDismissedFrame(id: String): String =
        """{"type":"event.question.dismissed","seq":28,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-20T06:12:37.844Z","payload":{"type":"event.question.dismissed",""" +
            """"question_id":"$id","dismissed_at":"2026-07-20T06:12:37.844Z",""" +
            """"agentId":"main","sessionId":"session_1"}}"""
}
