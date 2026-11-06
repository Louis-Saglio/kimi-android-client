package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.PendingQuestion
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The derived status line (ticket 177): the `agent` frame says only busy/idle, so
 * [ChatViewModel.UiState.activityStatus] reduces the transcript state to the sub-phase.
 */
class ActivityStatusTest {

    private val busySince = Instant.parse("2026-11-06T10:00:00Z")
    private val toolSince = Instant.parse("2026-11-06T10:00:05Z")
    private val busy = ChatViewModel.UiState(sessionId = "session_1", agentActiveSince = busySince, isWorking = true)

    @Test
    fun `an idle agent shows no status line whatever the transcript says`() {
        val idle = busy.copy(
            agentActiveSince = null,
            runningTool = ChatViewModel.RunningTool("Bash", toolSince),
            stepRetry = ChatViewModel.StepRetry(2, 10, "429"),
        )
        assertNull(idle.activityStatus)
    }

    @Test
    fun `a running turn thinks, a running tool names itself, a busy agent without a turn works in background`() {
        assertEquals(ChatViewModel.ActivityStatus("Thinking…", busySince), busy.activityStatus)
        assertEquals(
            ChatViewModel.ActivityStatus("Running Bash…", toolSince),
            busy.copy(runningTool = ChatViewModel.RunningTool("Bash", toolSince)).activityStatus,
        )
        assertEquals(
            ChatViewModel.ActivityStatus("Working in background…", busySince),
            busy.copy(isWorking = false).activityStatus,
        )
    }

    @Test
    fun `waiting on the user beats a retry, which beats a running tool`() {
        val tool = busy.copy(runningTool = ChatViewModel.RunningTool("Bash", toolSince))
        val retrying = tool.copy(stepRetry = ChatViewModel.StepRetry(2, 10, "429"))
        assertEquals(ChatViewModel.ActivityStatus("Retrying…"), retrying.activityStatus)

        val asking = retrying.copy(pendingQuestions = listOf(PendingQuestion("q_1", "call_q", emptyList())))
        assertEquals(ChatViewModel.ActivityStatus("Waiting for your answer"), asking.activityStatus)

        val approving = asking.copy(
            pendingApprovals = listOf(ChatViewModel.PendingApproval("ap_1", "Bash", "rm -rf build")),
        )
        assertEquals(ChatViewModel.ActivityStatus("Waiting for approval"), approving.activityStatus)
    }
}
