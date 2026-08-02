package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot cold start (ticket 085) on the shared [ChatViewModelTestBase] harness:
 * `GET .../snapshot` is the single cold-start entry point — history, busy/context
 * state, pending approvals/questions, the sub-agent roster and the resume cursor all
 * arrive in the one bundle the gate fetches before the first socket subscribe.
 */
class SnapshotColdStartTest : ChatViewModelTestBase() {

    /** A /snapshot body with the 085 acceptance knobs; watermark seq 42 / "ep_snap"
     * stands out from the harness's default (derived) watermark. */
    private fun snapshotBundle(
        messages: String = """{"items":[],"has_more":false}""",
        busy: Boolean = false,
        subagents: String = "[]",
        pendingApprovals: String = "[]",
        pendingQuestions: String = "[]",
    ): String = """{"code":0,"msg":"success","data":{"as_of_seq":42,"epoch":"ep_snap",""" +
        """"session":{"id":"session_1","busy":$busy,"usage":{"context_tokens":0,"context_limit":1048576}},""" +
        """"messages":$messages,"in_flight_turn":null,"subagents":$subagents,""" +
        """"pending_approvals":$pendingApprovals,"pending_questions":$pendingQuestions},"request_id":"r"}"""

    @Test
    fun `cold open links a running sub-agent row from the snapshot roster`() = runBlocking {
        // The reported bug (Louis, 2026-08-01): a foreground sub-agent still running
        // while the app cold-opens — no live subagent.spawned was witnessed and the
        // result's agent_id: marker does not exist yet. The roster's
        // parent_tool_call_id is the linkage: the Agent row is tappable (opens the
        // page) and the store entry seeded Working, all before any result lands.
        snapshotBody = snapshotBundle(
            busy = true,
            messages = """{"items":[""" +
                """{"id":"msg_1","session_id":"session_1","role":"assistant",""" +
                """"content":[{"type":"tool_use","tool_call_id":"tool_agent","tool_name":"Agent",""" +
                """"input":{"subagent_type":"explore","description":"doc-verify","prompt":"Reply ok"}}],""" +
                """"created_at":"2026-08-01T10:00:00.000Z"}],"has_more":false}""",
            subagents = """[{"id":"agent-12","kind":"subagent","subagent_phase":"working",""" +
                """"subagent_type":"explore","description":"doc-verify","parent_tool_call_id":"tool_agent",""" +
                """"run_in_background":false,"created_at":"2026-08-01T10:00:00.000Z",""" +
                """"started_at":"2026-08-01T10:00:00.100Z"}]""",
        )

        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.ToolGroup>()
                ?.flatMap { it.tools }
                ?.any { it.id == "tool_agent" && it.subagentId == "agent-12" } == true
        }
        assertTrue(viewModel.uiState.value.isWorking)
        val entry = subagentStore.entry("session_1", "agent-12").first()!!
        assertEquals("explore", entry.name)
        assertEquals("doc-verify", entry.description)
        assertEquals(SubagentStatus.Working, entry.status)
    }

    @Test
    fun `cold open renders the snapshot's pending approval card`() = runBlocking {
        // The first REST read approvals ever had (ticket 085): the item is the legacy
        // event.approval.requested shape verbatim — the same card the live event shows.
        snapshotBody = snapshotBundle(
            pendingApprovals = """[{"approval_id":"ap_1","session_id":"session_1","turn_id":0,""" +
                """"tool_call_id":"ap_1","tool_name":"Bash","action":"Running: sleep 90",""" +
                """"tool_input_display":{"kind":"command","command":"sleep 90"},""" +
                """"created_at":"2026-08-01T10:00:00.000Z","expires_at":"2026-08-01T10:01:00.000Z"}]""",
        )

        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { it.pendingApprovals.isNotEmpty() }
        assertEquals(
            listOf(ChatViewModel.PendingApproval("ap_1", "Bash", "sleep 90")),
            viewModel.uiState.value.pendingApprovals,
        )
    }

    @Test
    fun `cold open renders the snapshot's pending question card`() = runBlocking {
        // Same shape as the /questions poll's items (verified live, ticket 085) — the
        // same card the live event.question.requested shows.
        snapshotBody = snapshotBundle(
            pendingQuestions = """[{"question_id":"tool_q1","session_id":"session_1","turn_id":0,""" +
                """"tool_call_id":"tool_q1",""" +
                """"questions":[{"id":"q_0","question":"Red or blue?","header":"Color",""" +
                """"options":[{"id":"opt_0_0","label":"red"},{"id":"opt_0_1","label":"blue"}],""" +
                """"allow_other":false}],"created_at":"2026-08-01T10:00:00.000Z"}]""",
        )

        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
        val question = viewModel.uiState.value.pendingQuestions.single()
        assertEquals("tool_q1", question.questionId)
        assertEquals("Red or blue?", question.items.single().text)
    }

    @Test
    fun `the first subscribe after a cold start resumes at the snapshot watermark`() = runBlocking {
        // Gapless stitching (ticket 085): the cursor seeded from the snapshot makes the
        // very first subscribe a resume — the window between the REST fetch and the
        // subscribe replays instead of being lost.
        snapshotBody = snapshotBundle()

        viewModel.onScreenStarted()
        awaitServerSocket()

        awaitCondition { clientFrames.isNotEmpty() }
        val subscribe = clientFrames.first()
        assertTrue(subscribe, "\"type\":\"subscribe\"" in subscribe)
        assertTrue(subscribe, subscribe.contains("\"seq\":42"))
        assertTrue(subscribe, subscribe.contains("\"epoch\":\"ep_snap\""))
    }

    @Test
    fun `a failed snapshot surfaces the load error and no legacy burst fires`() = runBlocking {
        snapshotBody = """{"code":50000,"msg":"boom","data":null,"request_id":"r"}"""

        viewModel.onScreenStarted()

        // The ordinary load-error state, same as a failed initial history load.
        awaitState(viewModel.uiState) { it.error != null }
        assertNull(viewModel.uiState.value.items)

        // No fallback to the pre-085 ad-hoc burst: no /messages, /status, /questions.
        val paths = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < deadline) {
            server.takeRequest(100, TimeUnit.MILLISECONDS)?.let { paths += it.target.orEmpty() }
        }
        assertTrue(paths.toString(), paths.none { "/messages" in it || "/status" in it || "/questions" in it })

        // The retry re-runs the snapshot sync — still no legacy burst.
        snapshotBody = snapshotBundle()
        viewModel.onRetry()
        awaitRequest("/snapshot")
        awaitState(viewModel.uiState) { it.items != null && it.error == null }
    }
}
