package fr.louissaglio.kimi.android.ui.chat

import androidx.lifecycle.viewModelScope
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.testutil.TranscriptFrames
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot cold start (ticket 085) on the shared [ChatViewModelTestBase] harness:
 * `GET .../snapshot` is the single cold-start entry point — history, busy state,
 * pending approvals/questions, the sub-agent roster and the resume cursor all
 * arrive in the one bundle the gate fetches before the first socket subscribe
 * (the ring's context fill seeds from the bundle's real usage, ticket 161).
 */
class SnapshotColdStartTest : ChatViewModelTestBase() {

    /** A /snapshot body with the 085 acceptance knobs; watermark seq 42 / "ep_snap"
     * stands out from the harness's default (derived) watermark. The usage carries
     * a zero limit — the "unknown" wire case, so the ring's fill converges from the
     * /status re-read. */
    private fun snapshotBundle(
        messages: String = """{"items":[],"has_more":false}""",
        busy: Boolean = false,
        subagents: String = "[]",
        pendingApprovals: String = "[]",
        pendingQuestions: String = "[]",
        inFlightTurn: String = "null",
    ): String = """{"code":0,"msg":"success","data":{"as_of_seq":42,"epoch":"ep_snap",""" +
        """"session":{"id":"session_1","busy":$busy,"usage":{"context_tokens":0,"context_limit":0}},""" +
        """"messages":$messages,"in_flight_turn":$inFlightTurn,"subagents":$subagents,""" +
        """"pending_approvals":$pendingApprovals,"pending_questions":$pendingQuestions},"request_id":"r"}"""

    @Test
    fun `cold open links a running sub-agent row from the snapshot roster`() = runBlocking {
        // The reported bug (Louis, 2026-08-01): a foreground sub-agent still running
        // while the app cold-opens — no live subagent.spawned was witnessed and the
        // result's agent_id: marker does not exist yet. The roster's
        // parent_tool_call_id is the linkage: the Agent row is tappable (opens the
        // page) and the store entry seeded Working, all before any result lands.
        //
        // The snapshot says the session is mid-turn; the /status seed of the
        // cold-start apply (fired on every cold start for the 107 model sync) must
        // agree — the default busy:false would race the apply and flip isWorking
        // back off.
        statusBody = """{"code":0,"msg":"success","data":{"busy":true,"context_tokens":0,""" +
            """"max_context_tokens":1048576,"context_usage":0.0},"request_id":"r"}"""
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
    fun `cold open seeds the in-flight turn's pre-watermark live tail`() = runBlocking {
        // The reported bug (Louis, 2026-09-16): opening a LIVE session showed the
        // history, a gap, then the live stream. The resume-cursor replay covers only
        // frames AFTER the snapshot's watermark and the turn's messages commit to
        // history at its end, so the turn's pre-watermark tail — streamed text,
        // thinking, still-running tools — exists only in the bundle's in_flight_turn.
        statusBody = """{"code":0,"msg":"success","data":{"busy":true,"context_tokens":0,""" +
            """"max_context_tokens":1048576,"context_usage":0.0},"request_id":"r"}"""
        snapshotBody = snapshotBundle(
            busy = true,
            messages = """{"items":[""" +
                """{"id":"msg_1","session_id":"session_1","role":"user",""" +
                """"content":[{"type":"text","text":"run sleep 60"}],""" +
                """"created_at":"2026-09-16T10:00:00.000Z"}],"has_more":false}""",
            // Verbatim 0.31.1 busy shape (docs/server-api.md, Session snapshot).
            inFlightTurn = """{"turn_id":1,"assistant_text":"Working on it.",""" +
                """"thinking_text":"Let me see.",""" +
                """"running_tools":[{"tool_call_id":"tool_bash","name":"Bash",""" +
                """"args":{"command":"sleep 60","timeout":90},"description":"Running: sleep 60",""" +
                """"display":{"kind":"command","command":"sleep 60","cwd":"/tmp","language":"bash"}}],""" +
                """"current_prompt_id":"msg_1"}""",
        )

        viewModel.onScreenStarted()

        // The seeded tail stitches onto the history page: user bubble, the aggregated
        // text/thinking bubble, then the still-running tool row (running AT the
        // watermark — the tail's newest activity).
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
    fun `post-watermark frames extend and heal the seeded in-flight tail`() = runBlocking {
        // The seed's continuation contract: the replay/live stream lands on ordinary
        // live rows — a result heals the seeded tool row in place (no duplicate), and
        // a delta after a tool row opens a fresh bubble (the ordinary live rule).
        statusBody = """{"code":0,"msg":"success","data":{"busy":true,"context_tokens":0,""" +
            """"max_context_tokens":1048576,"context_usage":0.0},"request_id":"r"}"""
        snapshotBody = snapshotBundle(
            busy = true,
            messages = """{"items":[""" +
                """{"id":"msg_1","session_id":"session_1","role":"user",""" +
                """"content":[{"type":"text","text":"run sleep 60"}],""" +
                """"created_at":"2026-09-16T10:00:00.000Z"}],"has_more":false}""",
            inFlightTurn = """{"turn_id":1,"assistant_text":"Working on it.","thinking_text":"",""" +
                """"running_tools":[{"tool_call_id":"tool_bash","name":"Bash",""" +
                """"args":{"command":"sleep 60","timeout":90},"description":"Running: sleep 60",""" +
                """"display":{"kind":"command","command":"sleep 60","cwd":"/tmp","language":"bash"}}],""" +
                """"current_prompt_id":"msg_1"}""",
        )

        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { state ->
            state.items?.map(ChatItem::id) == listOf("msg_1", "live_0", "tool_bash")
        }

        socket.send(
            TranscriptFrames.opsFrame(
                TranscriptFrames.toolOp(
                    "tool_bash", "Bash", "done",
                    ""","input":{"command":"sleep 60","timeout":90},"output":"done"""",
                    turnId = "tx",
                ),
            ),
        )
        socket.send(assistantDeltaFrame(" All set.", 44))

        awaitState(viewModel.uiState) { state ->
            state.items?.map(ChatItem::id) == listOf("msg_1", "live_0", "tool_bash", "live_1")
        }
        val items = viewModel.uiState.value.items!!
        val tool = items.filterIsInstance<ChatItem.ToolGroup>().single().tools.single()
        assertEquals(ToolStatus.Done, tool.status)
        assertEquals("done", tool.output)
        assertEquals(
            listOf("Working on it.", " All set."),
            items.filterIsInstance<ChatItem.AssistantMessage>().map { it.text },
        )
    }

    @Test
    fun `cold open renders the snapshot's pending approval card`() = runBlocking {
        // The first REST read approvals ever had (ticket 085): the item is the legacy
        // event.approval.requested shape verbatim — the same card the live event shows.
        // 2.0.2 stamps the asking agent snake_case (ticket 156): a sub-agent's
        // cold-open request resolves through it, no more hard-defaulted "main".
        snapshotBody = snapshotBundle(
            pendingApprovals = """[{"approval_id":"ap_1","session_id":"session_1","turn_id":0,""" +
                """"tool_call_id":"ap_1","tool_name":"Bash","action":"Running: sleep 90",""" +
                """"tool_input_display":{"kind":"command","command":"sleep 90"},""" +
                """"agent_id":"agent-1",""" +
                """"created_at":"2026-08-01T10:00:00.000Z","expires_at":"2026-08-01T10:01:00.000Z"}]""",
        )

        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { it.pendingApprovals.isNotEmpty() }
        assertEquals(
            listOf(ChatViewModel.PendingApproval("ap_1", "Bash", "sleep 90", agentId = "agent-1")),
            viewModel.uiState.value.pendingApprovals,
        )
    }

    @Test
    fun `cold open renders the snapshot's pending question card`() = runBlocking {
        // Same shape as the /questions poll's items (verified live, ticket 085) — the
        // same card the live event.question.requested shows. 0.38.0 dual ids (106):
        // a dedicated question_<uuid> id plus the distinct tool_call_id. 2.0.2 adds
        // the optional agent_id stamp (ticket 156) — a sub-agent's question carries
        // its asking member for the card's badge.
        snapshotBody = snapshotBundle(
            pendingQuestions = """[{"question_id":"question_q1","session_id":"session_1","turn_id":0,""" +
                """"tool_call_id":"tool_q1","agent_id":"agent-1",""" +
                """"questions":[{"id":"q_0","question":"Red or blue?","header":"Color",""" +
                """"options":[{"id":"opt_0_0","label":"red"},{"id":"opt_0_1","label":"blue"}],""" +
                """"allow_other":false}],"created_at":"2026-08-01T10:00:00.000Z"}]""",
        )

        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
        val question = viewModel.uiState.value.pendingQuestions.single()
        assertEquals("question_q1", question.questionId)
        assertEquals("tool_q1", question.toolCallId)
        assertEquals("agent-1", question.agentId)
        assertEquals("Red or blue?", question.items.single().text)
    }

    @Test
    fun `cold open keeps the snapshot page's oldest-first order`() = runBlocking {
        // The reported regression (Louis, 2026-08-02): the snapshot's messages.items
        // come OLDEST-first on the wire (unlike /messages). The mapper's reversal
        // inverted the cold-start timeline — the chat opened pinned mid-session with
        // the tail at the top ("last messages missing"), and every tool result
        // flattened BEFORE its call: orphan "tool" rows done, calls stuck Running.
        snapshotBody = snapshotBundle(
            messages = """{"items":[""" +
                """{"id":"msg_1","session_id":"session_1","role":"user",""" +
                """"content":[{"type":"text","text":"run it"}],""" +
                """"created_at":"2026-08-01T10:00:00.000Z"},""" +
                """{"id":"msg_2","session_id":"session_1","role":"assistant",""" +
                """"content":[{"type":"text","text":"running it"},""" +
                """{"type":"tool_use","tool_call_id":"tool_1","tool_name":"Edit",""" +
                """"input":{"path":"docs/server-api.md"}}],""" +
                """"created_at":"2026-08-01T10:00:01.000Z"},""" +
                """{"id":"msg_3","session_id":"session_1","role":"tool",""" +
                """"content":[{"type":"tool_result","tool_call_id":"tool_1","output":"ok","is_error":false}],""" +
                """"created_at":"2026-08-01T10:00:02.000Z"},""" +
                """{"id":"msg_4","session_id":"session_1","role":"assistant",""" +
                """"content":[{"type":"text","text":"all done"}],""" +
                """"created_at":"2026-08-01T10:00:03.000Z"}""" +
                """],"has_more":false}""",
        )

        viewModel.onScreenStarted()

        // Chronological order, newest row last; the result joined its call (no orphan
        // row — a wrong order would add a fifth row and leave the call Running).
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_1", "msg_2", "tool_1", "msg_4") }
        val tool = viewModel.uiState.value.items!!.filterIsInstance<ChatItem.ToolGroup>().single().tools.single()
        assertEquals("Edit", tool.name)
        assertEquals(ToolStatus.Done, tool.status)
        assertEquals("ok", tool.output)
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

    @Test
    fun `re-entering the session re-links a running sub-agent row from a fresh roster`() = runBlocking {
        // Bug 2 (ticket 091): a sub-agent spawned during a visit is still running when
        // the session is left and re-entered — the spawned link is never replayed, the
        // result's agent_id: marker does not exist yet, and 085's process-lifetime
        // snapshot cache served the pre-spawn roster, so the row expanded instead of
        // opening the page. Now leaving the session forgets the sync (the store's
        // release), so re-entry re-fetches: the FRESH roster re-creates the record and
        // the registry link, re-driven onto the new timeline's row.
        snapshotBody = snapshotBundle(busy = true)

        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        // The first visit's live spawn: the Agent call as a tool frame, the record
        // through the store's lifecycle funnel (the `task` frame carrier is ticket 182's).
        socket.send(
            TranscriptFrames.opsFrame(
                TranscriptFrames.toolOp(
                    "tool_agent", "Agent", "running",
                    ""","input":{"subagent_type":"explore","description":"doc-verify","prompt":"Reply ok"}""",
                    turnId = "tx",
                ),
            ),
        )
        subagentStore.apply(
            "session_1",
            SessionEvent.SubagentSpawned("agent-12", "explore", "tool_agent", "doc-verify", runInBackground = false),
        )
        subagentStore.apply("session_1", SessionEvent.SubagentStarted("agent-12"))
        awaitCondition { subagentStoreEntry("agent-12")?.status == SubagentStatus.Working }

        // The session is left (the chat ViewModel is cleared — its scope dies — and the
        // store is released, forgetting the snapshot sync).
        viewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
        subagentStore.release("session_1")
        assertNull(subagentStoreEntry("agent-12"))

        // Re-entry: the roster now serves the running agent, its Agent call is in the
        // messages page — still no result, hence no agent_id: marker anywhere.
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
        val reopened = newViewModel(streamCoalesceWindowMillis = 0)
        reopened.onScreenStarted()

        // The running row is linked BEFORE any result marker exists, and the store
        // holds the Working record — prompt backfilled from the snapshot-mined args.
        awaitState(reopened.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.ToolGroup>()
                ?.flatMap { it.tools }
                ?.any { it.id == "tool_agent" && it.subagentId == "agent-12" } == true
        }
        val record = subagentStoreEntry("agent-12")!!
        assertEquals(SubagentStatus.Working, record.status)
        assertEquals("explore", record.name)
        assertEquals(
            listOf(ChatItem.UserMessage("seeded_prompt", "Reply ok")),
            record.items,
        )
    }

    @Test
    fun `a screen stop-start round trip performs no extra snapshot fetch`() = runBlocking {
        // The within-process path (ticket 091's guarded economy): chat → page → chat
        // never releases the store, so the sync stays fresh and no /snapshot re-fetch
        // fires — one cheap GET per session ENTRY, not per ON_START.
        snapshotBody = snapshotBundle()
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onScreenStopped()
        viewModel.onScreenStarted()

        val paths = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < deadline) {
            server.takeRequest(100, TimeUnit.MILLISECONDS)?.let { paths += it.target.orEmpty() }
        }
        assertTrue(paths.toString(), paths.none { it.contains("/snapshot") })
    }

    /** Non-suspend read of a store entry, for awaitCondition's plain-lambda condition. */
    private fun subagentStoreEntry(subagentId: String): SubagentStreamStore.SubagentState? =
        runBlocking { subagentStore.entry("session_1", subagentId).first() }
}
