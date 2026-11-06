package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.testutil.TranscriptFrames
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import java.util.concurrent.CopyOnWriteArrayList
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
 * dismissal — since ticket 172 over the flat `interaction` frame and the
 * `.../interactions/{id}` route — driven end-to-end through the ViewModel on the shared
 * [ChatViewModelTestBase] harness.
 */
class QuestionWizardTest : ChatViewModelTestBase() {

    // --- Questions on the `interaction` frame (ticket 172) ----------------------------

    /** Opens the chat, waits for the cold start and sends one pending question frame. */
    private suspend fun openWithQuestion(
        interactionId: String = "question_1",
        questions: String = TranscriptFrames.CAKE_OR_PIE,
    ): okhttp3.WebSocket {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        socket.send(TranscriptFrames.questionFrame(interactionId, questions = questions))
        awaitState(viewModel.uiState) { it.pendingQuestions.any { q -> q.questionId == interactionId } }
        return socket
    }

    private fun assertNoQuestionsRoute() {
        assertTrue(requestedTargets.none { "/questions" in it || "/snapshot" in it })
    }

    @Test
    fun `a live pending question frame surfaces the wizard with no REST poll`() = runBlocking {
        openWithQuestion("question_10025aea")

        val question = viewModel.uiState.value.pendingQuestions.single()
        assertEquals("question_10025aea", question.questionId)
        assertEquals("call_00_question_10025aea", question.toolCallId)
        assertEquals("Cake or pie?", question.items.single().text)
        assertNoQuestionsRoute()
    }

    @Test
    fun `answering a single-select item posts the interaction body and the card clears on the answered frame`() = runBlocking {
        val socket = openWithQuestion("question_10025aea")

        viewModel.onQuestionOptionPicked("q_0", "opt_0_0")
        viewModel.onSubmitQuestion()

        val request = awaitRequest("/interactions/")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/interactions/question_10025aea", request.target)
        assertEquals(
            """{"kind":"question","answers":{"q_0":{"kind":"single","option_id":"opt_0_0"}}}""",
            request.body!!.utf8(),
        )
        // The {outcome, ended_at} ack alone must not clear the card.
        delay(300)
        assertEquals(1, viewModel.uiState.value.pendingQuestions.size)

        socket.send(TranscriptFrames.questionFrame("question_10025aea", status = "ended", outcome = "answered"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isEmpty() }
        // Drafts go with the card.
        assertTrue(viewModel.uiState.value.questionSelections.isEmpty())
        assertNoQuestionsRoute()
    }

    @Test
    fun `multi-select and free-text answers keep the multi, other and multi_with_other mapping`() = runBlocking {
        val questions = """[""" +
            """{"id":"q_0","question":"Colors?","header":"Palette","multi_select":true,"allow_other":true,""" +
            """"options":[{"id":"opt_0_0","label":"red"},{"id":"opt_0_1","label":"blue"}]},""" +
            """{"id":"q_1","question":"Extras?","header":"Extras","multi_select":true,"allow_other":true,""" +
            """"options":[{"id":"opt_1_0","label":"fish"}]},""" +
            """{"id":"q_2","question":"Name?","header":"Name","allow_other":true,""" +
            """"options":[{"id":"opt_2_0","label":"Ada"}]}]"""
        openWithQuestion("question_2", questions)

        viewModel.onQuestionOptionToggled("q_0", "opt_0_1")
        viewModel.onQuestionOptionToggled("q_0", "opt_0_0")
        viewModel.onQuestionOptionToggled("q_1", "opt_1_0")
        viewModel.onQuestionOtherTextChange("q_1", " chips ")
        viewModel.onQuestionOptionPicked("q_2", "opt_2_0")
        viewModel.onQuestionOtherTextChange("q_2", "Grace")
        viewModel.onSubmitQuestion()

        val request = awaitRequest("/interactions/")
        assertEquals("/api/v1/sessions/session_1/interactions/question_2", request.target)
        assertEquals(
            """{"kind":"question","answers":{""" +
                """"q_0":{"kind":"multi","option_ids":["opt_0_0","opt_0_1"]},""" +
                """"q_1":{"kind":"multi_with_other","option_ids":["opt_1_0"],"other_text":"chips"},""" +
                """"q_2":{"kind":"other","text":"Grace"}}}""",
            request.body!!.utf8(),
        )
    }

    @Test
    fun `dismiss posts dismissed true and the card clears on the dismissed frame with no error`() = runBlocking {
        val socket = openWithQuestion()
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onDismissQuestion()

            val request = awaitRequest("/interactions/")
            assertEquals("/api/v1/sessions/session_1/interactions/question_1", request.target)
            assertEquals("""{"kind":"question","dismissed":true}""", request.body!!.utf8())
            socket.send(TranscriptFrames.questionFrame("question_1", status = "ended", outcome = "dismissed"))
            awaitState(viewModel.uiState) { it.pendingQuestions.isEmpty() }
            delay(200)
            assertTrue(effects.none { it is ChatViewModel.Effect.QuestionFailed })
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `re-answering an ended question surfaces 40902 and an unknown id surfaces 40404, the card stays`() = runBlocking {
        openWithQuestion()
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            questionFailureBody =
                """{"code":40902,"msg":"interaction question_1 has ended","data":null,"request_id":"r"}"""
            viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
            viewModel.onSubmitQuestion()
            awaitCondition { effects.isNotEmpty() }

            questionFailureBody =
                """{"code":40404,"msg":"interaction question_1 not found","data":null,"request_id":"r"}"""
            viewModel.onDismissQuestion()
            awaitCondition { effects.size >= 2 }

            assertEquals(
                listOf(
                    ChatViewModel.Effect.QuestionFailed("interaction question_1 has ended"),
                    ChatViewModel.Effect.QuestionFailed("interaction question_1 not found"),
                ),
                effects.toList(),
            )
            assertEquals(1, viewModel.uiState.value.pendingQuestions.size)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `aborting a turn clears its question through the dismissed frame, the turn end polls nothing`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        socket.send(turnStartedFrame())
        socket.send(TranscriptFrames.questionFrame("question_1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() && it.isWorking }

        viewModel.onStop()
        awaitRequest(":abort")
        // The turn end alone keeps the card (a background question outlives its turn);
        // the abort's dismissed frame clears it.
        socket.send(turnEndedFrame("cancelled"))
        awaitState(viewModel.uiState) { !it.isWorking }
        assertEquals(1, viewModel.uiState.value.pendingQuestions.size)
        socket.send(TranscriptFrames.questionFrame("question_1", status = "ended", outcome = "dismissed"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isEmpty() }

        delay(200)
        assertTrue(requestedTargets.none { "/interactions/" in it })
        assertNoQuestionsRoute()
    }

    @Test
    fun `a re-subscribe rebuilds exactly one card from the reset and the re-sent frame and drops one answered while away`() = runBlocking {
        val socket = openWithQuestion("question_1")
        socket.send(TranscriptFrames.questionFrame("question_2"))
        awaitState(viewModel.uiState) { it.pendingQuestions.size == 2 }

        // Away: question_1 is answered elsewhere and its ended frame is missed.
        viewModel.onScreenStopped()
        serverSocket = null
        viewModel.onScreenStarted()
        val resumed = awaitServerSocket()
        awaitCondition { clientFrames.count { "\"type\":\"subscribe_v2\"" in it } >= 2 }

        val interactions = "[" + TranscriptFrames.questionEntity("question_1", state = "answered") + "," +
            TranscriptFrames.questionEntity("question_2") + "]"
        resumed.send(TranscriptFrames.resetFrame(seq = 30, interactions = interactions))
        resumed.send(TranscriptFrames.ackFrame(id = "c_1"))
        resumed.send(TranscriptFrames.questionFrame("question_2"))
        resumed.send(TranscriptFrames.opsFrame(TranscriptFrames.promptOp("msg_9", "completed")))

        awaitState(viewModel.uiState) { it.pendingQuestions.map { q -> q.questionId } == listOf("question_2") }
        delay(200)
        assertEquals(listOf("question_2"), viewModel.uiState.value.pendingQuestions.map { it.questionId })
        assertNoQuestionsRoute()
    }

    @Test
    fun `a resumed subscribe's empty reset keeps the head's drafts for its re-sent frame`() = runBlocking {
        val questions = """[""" +
            """{"id":"q_0","question":"Cake or pie?","header":"Dessert","options":[{"id":"opt_0_0","label":"Cake"}]},""" +
            """{"id":"q_1","question":"Tea or coffee?","header":"Drink","options":[{"id":"opt_1_0","label":"Tea"}]}]"""
        openWithQuestion("question_1", questions)
        viewModel.onQuestionOptionPicked("q_0", "opt_0_0")
        viewModel.onQuestionNext()
        assertEquals(1, viewModel.uiState.value.questionStep)

        // A resumed subscribe (ticket 176's watermark) gets no reset snapshot: the ack
        // empties the queue, the re-sent pending frame refills it.
        viewModel.onScreenStopped()
        serverSocket = null
        viewModel.onScreenStarted()
        val resumed = awaitServerSocket()
        awaitCondition { clientFrames.count { "\"type\":\"subscribe_v2\"" in it } >= 2 }
        resumed.send(TranscriptFrames.ackFrame(id = "c_1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isEmpty() }
        resumed.send(TranscriptFrames.questionFrame("question_1", questions = questions))

        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
        val state = viewModel.uiState.value
        assertEquals(listOf("question_1"), state.pendingQuestions.map { it.questionId })
        assertEquals(mapOf("q_0" to setOf("opt_0_0")), state.questionSelections)
        assertEquals(1, state.questionStep)
        assertNoQuestionsRoute()
    }

    @Test
    fun `a cold open seeds one card from the transcript and the reset plus re-send never double it`() = runBlocking {
        val entity = TranscriptFrames.questionEntity("question_1")
        transcriptBody = transcriptData(interactions = "[$entity]", pendingInteractions = """["question_1"]""")

        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
        socket.send(TranscriptFrames.resetFrame(seq = 2, interactions = "[$entity]"))
        socket.send(TranscriptFrames.ackFrame())
        socket.send(TranscriptFrames.questionFrame("question_1", agentId = "agent-7"))
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.promptOp("msg_9", "completed"), seq = 3))
        awaitState(viewModel.uiState) { it.pendingQuestions.singleOrNull()?.agentId == "agent-7" }
        delay(200)

        val question = viewModel.uiState.value.pendingQuestions.single()
        assertEquals("question_1", question.questionId)
        assertEquals("call_00_question_1", question.toolCallId)
        assertNoQuestionsRoute()
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
