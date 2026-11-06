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

    private val resolvedInput = """{"questions":[{"question":"Red or blue?","header":"Color",""" +
        """"options":[{"label":"red"},{"label":"blue"}],"multi_select":false}]}"""
    private val resolvedOutput = """{"answers":{"Red or blue?":"blue"}}"""
    private val multiItemResolvedInput = """{"questions":[""" +
        """{"question":"Red or blue?","header":"Color","options":[{"label":"red"},{"label":"blue"}],"multi_select":false},""" +
        """{"question":"Fast or safe?","header":"Mode","options":[{"label":"fast"},{"label":"safe"}],"multi_select":false}]}"""
    private val multiItemResolvedOutput = """{"answers":{"Red or blue?":"red","Fast or safe?":"fast"}}"""
}
