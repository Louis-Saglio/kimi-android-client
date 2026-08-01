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
    fun `questions poll in flight does not clear a question that just arrived`() = runBlocking {
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
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
        viewModel.onSubmitQuestion()

        val request = awaitRequest("/questions/")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/questions/tool_q1", request.target)
        assertEquals("""{"answers":{"q_0":{"kind":"single","option_id":"opt_0_1"}}}""", request.body!!.utf8())

        // The ack alone must not clear the card — only the answered event does.
        delay(300)
        assertEquals(1, viewModel.uiState.value.pendingQuestions.size)
        socket.send(questionAnsweredFrame("tool_q1"))
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
        assertEquals("opt_0_1", viewModel.uiState.value.questionSelections["q_0"])

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
    fun `dismiss posts to the dismiss endpoint and the card clears on dismissed`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        viewModel.onDismissQuestion()

        val request = awaitRequest(":dismiss")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/questions/tool_q1:dismiss", request.target)
        assertEquals("{}", request.body!!.utf8())

        socket.send(questionDismissedFrame("tool_q1"))
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
        // The poll endpoint is the source of truth: it keeps returning the question, so
        // neither the screen-start load nor the turn.ended re-derivation may drop it.
        questionsBody = """
            {"code":0,"msg":"success","data":{"items":[
              {"type":"event.question.requested","question_id":"tool_q1","session_id":"session_1",
               "turn_id":0,"tool_call_id":"tool_q1",
               "questions":[{"id":"q_0","question":"Red or blue?","header":"Color",
                             "options":[{"id":"opt_0_0","label":"red"},
                                        {"id":"opt_0_1","label":"blue"}],
                             "allow_other":false}],
               "created_at":"2026-07-20T06:06:49.808Z","agentId":"main","sessionId":"session_1"}
            ]},"request_id":"r"}
        """.trimIndent()
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }
        // Drain the history/status/questions/WS requests recorded so far so the next
        // questions poll observed is the one turn.ended triggers.
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        socket.send(turnEndedFrame(reason = "completed"))

        awaitRequest("/questions")
        delay(300)
        assertEquals(listOf("tool_q1"), viewModel.uiState.value.pendingQuestions.map { it.questionId })
    }

    /** Verbatim 0.27.0 shape (docs/server-api.md, "Core event payloads"). */
    private fun questionRequestedFrame(id: String): String =
        """{"type":"event.question.requested","seq":9,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-20T06:06:49.808Z","payload":{"type":"event.question.requested",""" +
            """"question_id":"$id","session_id":"session_1","turn_id":0,"tool_call_id":"$id",""" +
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
