package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.BackgroundTask
import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.SubagentRosterEntry
import fr.louissaglio.kimi.android.data.chat.SubagentRosterPhase
import fr.louissaglio.kimi.android.data.chat.TaskInfo
import fr.louissaglio.kimi.android.data.chat.Todo
import fr.louissaglio.kimi.android.data.chat.TranscriptFrame
import fr.louissaglio.kimi.android.data.chat.TranscriptPage
import fr.louissaglio.kimi.android.data.chat.TranscriptState
import fr.louissaglio.kimi.android.data.chat.TranscriptStep
import fr.louissaglio.kimi.android.data.chat.TranscriptTurn
import fr.louissaglio.kimi.android.testutil.testSubagentStreamStore
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store runs with a 0 ms coalescing window here: every delta applies and publishes
 * on arrival, no flush timer involved (the window's semantics are covered by
 * ChatViewModelTest, which exercises the same shared DeltaCoalescer).
 */
class SubagentStreamStoreTest {

    private val store = testSubagentStreamStore(streamCoalesceWindowMillis = 0)

    private fun spawned(id: String, toolCallId: String = "tool_1") = SessionEvent.SubagentSpawned(
        subagentId = id,
        name = "explore",
        parentToolCallId = toolCallId,
        description = "doc-verify",
        runInBackground = false,
    )

    private fun userSplice(text: String, agentId: String, id: String = "msg_1") = SessionEvent.ContextSpliced(
        listOf(ChatMessage(id, ChatMessage.Role.User, listOf(ChatMessage.Part.Text(text)))),
        agentId,
    )

    private suspend fun entry(subagentId: String) = store.entry(SESSION_ID, subagentId).first()

    @Test
    fun `spawned creates the record with its identity metadata`() = runBlocking {
        store.apply(SESSION_ID, spawned("agent-12"))

        assertEquals(
            SubagentStreamStore.SubagentState(
                id = "agent-12",
                name = "explore",
                description = "doc-verify",
                runInBackground = false,
                status = SubagentStatus.Queued,
            ),
            entry("agent-12"),
        )
    }

    @Test
    fun `a duplicate spawned keeps the accumulated stream`() = runBlocking {
        store.apply(SESSION_ID, spawned("agent-12"))
        store.apply(SESSION_ID, SessionEvent.AssistantDelta("hello", "agent-12"))

        store.apply(SESSION_ID, spawned("agent-12"))

        assertEquals(1, entry("agent-12")!!.items.size)
    }

    @Test
    fun `lifecycle frames transition the status`() = runBlocking {
        store.apply(SESSION_ID, spawned("agent-12"))
        store.apply(SESSION_ID, SessionEvent.SubagentStarted("agent-12"))
        assertEquals(SubagentStatus.Working, entry("agent-12")!!.status)

        store.apply(SESSION_ID, SessionEvent.SubagentSuspended("agent-12"))
        assertEquals(SubagentStatus.Suspended, entry("agent-12")!!.status)

        // started re-fires when a suspended sub-agent resumes (docs/server-api.md).
        store.apply(SESSION_ID, SessionEvent.SubagentStarted("agent-12"))
        assertEquals(SubagentStatus.Working, entry("agent-12")!!.status)

        store.apply(SESSION_ID, SessionEvent.SubagentCompleted("agent-12", "the summary"))
        val completed = entry("agent-12")!!
        assertEquals(SubagentStatus.Completed, completed.status)
        assertEquals("the summary", completed.resultSummary)

        store.apply(SESSION_ID, spawned("agent-13", toolCallId = "tool_2"))
        store.apply(SESSION_ID, SessionEvent.SubagentFailed("agent-13", "boom"))
        val failed = entry("agent-13")!!
        assertEquals(SubagentStatus.Failed, failed.status)
        assertEquals("boom", failed.error)
    }

    @Test
    fun `transcript frames accumulate only into their own sub-agent's stream`() = runBlocking {
        store.apply(SESSION_ID, spawned("agent-12"))
        store.apply(SESSION_ID, spawned("agent-13", toolCallId = "tool_2"))

        store.apply(SESSION_ID, userSplice("task for 12", "agent-12"))
        store.apply(SESSION_ID, SessionEvent.AssistantDelta("reply from 13", "agent-13"))
        store.apply(SESSION_ID, SessionEvent.ThinkingDelta("hmm", "agent-12"))
        store.apply(SESSION_ID, SessionEvent.ToolCallStarted("tool_s", "Bash", "ls", agentId = "agent-12"))
        store.apply(SESSION_ID, SessionEvent.ToolCallResult("tool_s", false, "ok", agentId = "agent-12"))

        // The first splice is the task-turn one (ticket 091): lifted into the prompt
        // field, rendered as the first row — never a replaceable timeline row.
        val twelve = entry("agent-12")!!.items
        assertEquals(
            listOf(
                ChatItem.UserMessage("seeded_prompt", "task for 12"),
                ChatItem.AssistantMessage("live_0", "", thinking = "hmm"),
                ChatItem.ToolGroup(
                    "tool_s",
                    listOf(ChatItem.ToolActivity("tool_s", "Bash", ToolStatus.Done, "ls", output = "ok")),
                ),
            ),
            twelve,
        )
        assertEquals(
            listOf(ChatItem.AssistantMessage("live_0", "reply from 13", thinking = "")),
            entry("agent-13")!!.items,
        )
    }

    @Test
    fun `frames for an unknown sub-agent are dropped without creating a record`() = runBlocking {
        store.apply(SESSION_ID, SessionEvent.AssistantDelta("ghost", "agent-99"))
        store.apply(SESSION_ID, SessionEvent.SubagentStarted("agent-99"))
        store.apply(SESSION_ID, SessionEvent.SubagentCompleted("agent-99", "ghost summary"))

        assertNull(entry("agent-99"))
    }

    @Test
    fun `the task-turn splice is lifted and later splices stay in the timeline`() = runBlocking {
        // Sub-agent internal turns (docs/server-api.md): the task turn, then the forced
        // result-summary turn — each opens with turn.started, its prompt spliced inline.
        // Only the FIRST turn's first splice is the task prompt (ticket 091); the nudge
        // turn's splice is an ordinary user bubble.
        store.apply(SESSION_ID, spawned("agent-12"))
        store.apply(SESSION_ID, SessionEvent.TurnStarted("agent-12"))
        store.apply(SESSION_ID, userSplice("the task", "agent-12", id = "msg_task"))
        store.apply(SESSION_ID, SessionEvent.AssistantDelta("working on it", "agent-12"))
        store.apply(SESSION_ID, SessionEvent.TurnEnded("completed", "agent-12"))
        store.apply(SESSION_ID, SessionEvent.TurnStarted("agent-12"))
        store.apply(SESSION_ID, userSplice("summarize the result", "agent-12", id = "msg_nudge"))
        store.apply(SESSION_ID, SessionEvent.AssistantDelta("the summary", "agent-12"))

        assertEquals(
            listOf(
                ChatItem.UserMessage("seeded_prompt", "the task"),
                ChatItem.AssistantMessage("live_0", "working on it", thinking = ""),
                ChatItem.UserMessage("msg_nudge", "summarize the result"),
                ChatItem.AssistantMessage("live_1", "the summary", thinking = ""),
            ),
            entry("agent-12")!!.items,
        )
    }

    @Test
    fun `release drops the session's records`() = runBlocking {
        store.apply(SESSION_ID, spawned("agent-12"))
        store.release(SESSION_ID)

        assertNull(entry("agent-12"))
    }

    // --- The Agent-call funnel (tickets 045/091) ---------------------------------------

    @Test
    fun `a completed Agent call creates the record with the summary standing in`() = runBlocking {
        // Cold-open (ticket 045): the whole run predates the subscription; history's
        // Agent call + result is the only artifact left (version-pinned result format).
        store.applyAgentCall(
            SESSION_ID,
            toolCallId = "tool_1",
            argsJson = AGENT_ARGS,
            resultText = "agent_id: agent-12\nactual_subagent_type: explore\nstatus: completed\n\n[summary]\nthe final report",
            rowStatus = ToolStatus.Done,
        )

        val seeded = entry("agent-12")!!
        assertEquals("explore", seeded.name)
        assertEquals("doc-verify", seeded.description)
        assertEquals(SubagentStatus.Completed, seeded.status)
        assertEquals("the final report", seeded.resultSummary)
        assertEquals(
            listOf(
                ChatItem.UserMessage("seeded_prompt", "Reply ok"),
                ChatItem.AssistantMessage("seeded_summary", "the final report", thinking = ""),
            ),
            seeded.items,
        )
        // The result marker registered the row link in the session registry.
        assertEquals(mapOf("tool_1" to "agent-12"), store.linksFor(SESSION_ID))
    }

    @Test
    fun `an agent result heals a non-pinned record but never touches its timeline`() = runBlocking {
        // Late canonical data HEALS (ticket 091): the spawn-default Queued yields to the
        // result's completed — the never-overwrite guard was bug 1's permanence vector.
        store.apply(SESSION_ID, spawned("agent-12"))
        store.apply(SESSION_ID, SessionEvent.AssistantDelta("live text", "agent-12"))

        store.applyAgentCall(
            SESSION_ID,
            toolCallId = "tool_1",
            argsJson = AGENT_ARGS,
            resultText = "agent_id: agent-12\nstatus: completed\n\n[summary]\nlate summary",
            rowStatus = ToolStatus.Done,
        )

        val healed = entry("agent-12")!!
        assertEquals(SubagentStatus.Completed, healed.status)
        assertEquals("late summary", healed.resultSummary)
        // The prompt backfills from the args; the live timeline is untouched.
        assertEquals(
            listOf(
                ChatItem.UserMessage("seeded_prompt", "Reply ok"),
                ChatItem.AssistantMessage("live_0", "live text", thinking = ""),
            ),
            healed.items,
        )
    }

    @Test
    fun `a running background call creates a working record that keeps streaming`() = runBlocking {
        // Background shape (docs/server-api.md): the result returns immediately with
        // task fields first and no [summary] section; the run continues afterwards.
        store.applyAgentCall(
            SESSION_ID,
            toolCallId = "tool_1",
            argsJson = AGENT_ARGS,
            resultText = "task_id: agent-7noib8bb\nstatus: running\nagent_id: agent-13",
            rowStatus = ToolStatus.Done,
        )

        val working = entry("agent-13")!!
        assertEquals(SubagentStatus.Working, working.status)
        assertEquals(null, working.resultSummary)
        assertEquals(listOf(ChatItem.UserMessage("seeded_prompt", "Reply ok")), working.items)

        // The record existing is what lets frames from the subscription point
        // accumulate instead of hitting the unknown-sub-agent drop.
        store.apply(SESSION_ID, SessionEvent.AssistantDelta("rest of the run", "agent-13"))
        assertEquals(
            listOf(
                ChatItem.UserMessage("seeded_prompt", "Reply ok"),
                ChatItem.AssistantMessage("live_0", "rest of the run", thinking = ""),
            ),
            entry("agent-13")!!.items,
        )
    }

    @Test
    fun `a call without link or result marker creates nothing`() = runBlocking {
        store.applyAgentCall(
            SESSION_ID,
            toolCallId = "tool_1",
            argsJson = AGENT_ARGS,
            resultText = "status: completed\n\n[summary]\nok",
            rowStatus = ToolStatus.Done,
        )

        assertNull(entry("agent-12"))
        assertTrue(store.linksFor(SESSION_ID).isEmpty())
    }

    // --- The prompt field (ticket 091) --------------------------------------------------

    @Test
    fun `the prompt's first writer wins - the splice before the args`() = runBlocking {
        store.apply(SESSION_ID, spawned("agent-12"))
        store.apply(SESSION_ID, SessionEvent.TurnStarted("agent-12"))
        store.apply(SESSION_ID, userSplice("the task prompt", "agent-12", id = "msg_task"))

        // Lifted: the prompt bubble, not a timeline row.
        assertEquals(
            listOf(ChatItem.UserMessage("seeded_prompt", "the task prompt")),
            entry("agent-12")!!.items,
        )

        // The args mine carries the same text (modulo the splice's git-context prefix):
        // first writer wins, nothing changes.
        store.applyAgentCall(SESSION_ID, "tool_1", AGENT_ARGS, resultText = null, rowStatus = ToolStatus.Running)
        assertEquals(
            listOf(ChatItem.UserMessage("seeded_prompt", "the task prompt")),
            entry("agent-12")!!.items,
        )
    }

    @Test
    fun `the prompt's first writer wins - the args before the splice`() = runBlocking {
        store.apply(SESSION_ID, spawned("agent-12"))
        store.applyAgentCall(SESSION_ID, "tool_1", AGENT_ARGS, resultText = null, rowStatus = ToolStatus.Running)

        store.apply(SESSION_ID, SessionEvent.TurnStarted("agent-12"))
        store.apply(SESSION_ID, userSplice("the task prompt", "agent-12", id = "msg_task"))

        // The splice is still consumed (never a timeline row); the args text stays.
        assertEquals(
            listOf(ChatItem.UserMessage("seeded_prompt", "Reply ok")),
            entry("agent-12")!!.items,
        )
    }

    @Test
    fun `a roster-created record backfills the prompt from the call args`() = runBlocking {
        store.applyRoster(SESSION_ID, listOf(rosterEntry("agent-12", SubagentRosterPhase.Working)))
        assertEquals(emptyList<ChatItem>(), entry("agent-12")!!.items)

        store.applyAgentCall(SESSION_ID, "tool_1", AGENT_ARGS, resultText = null, rowStatus = ToolStatus.Running)

        val backfilled = entry("agent-12")!!
        assertEquals(listOf(ChatItem.UserMessage("seeded_prompt", "Reply ok")), backfilled.items)
        assertEquals(SubagentStatus.Working, backfilled.status)
    }

    @Test
    fun `the task prompt survives the cold-open transcript fetch and page reopens`() = runBlocking {
        // Bug 1 (ticket 091): the visible prompt used to be the spliced task-turn user
        // message committed into the timeline — the cold-open transcript fetch replaced
        // that zone wholesale and the prompt vanished for good. The splice is now
        // lifted into the record's prompt FIELD, outside every replaceable zone.
        store.apply(SESSION_ID, spawned("agent-12"))
        store.apply(SESSION_ID, SessionEvent.TurnStarted("agent-12"))
        store.apply(SESSION_ID, userSplice("the task prompt", "agent-12", id = "msg_task"))
        store.apply(SESSION_ID, SessionEvent.AssistantDelta("working on it", "agent-12"))

        store.applyTranscriptPage(
            SESSION_ID, "agent-12",
            transcriptPage(listOf(finishedTurn("t0", 0, "committed reply"))),
            keepLive = true,
        )

        assertEquals(
            listOf(
                ChatItem.UserMessage("seeded_prompt", "the task prompt"),
                ChatItem.AssistantMessage("t0.1.f1", "committed reply", thinking = ""),
                ChatItem.AssistantMessage("live_0", "working on it", thinking = ""),
            ),
            entry("agent-12")!!.items,
        )

        // The page-reopen path: hasTranscript skips the refetch — the prompt stays.
        assertTrue(store.hasTranscript(SESSION_ID, "agent-12"))
        assertEquals(
            ChatItem.UserMessage("seeded_prompt", "the task prompt"),
            entry("agent-12")!!.items.first(),
        )
    }

    // --- Transcript backing (ticket 046) ----------------------------------------------

    private fun transcriptPage(
        turns: List<TranscriptTurn>,
        hasMore: Boolean = false,
    ) = TranscriptPage("agent-12", turns, hasMore)

    private fun finishedTurn(id: String, ordinal: Int, text: String) = TranscriptTurn(
        id, ordinal, TranscriptState.Completed, null,
        listOf(
            TranscriptStep(
                "$id.1", TranscriptState.Completed,
                listOf(TranscriptFrame.Text("$id.1.f1", TranscriptFrame.Text.Role.Assistant, text)),
            ),
        ),
    )

    @Test
    fun `a transcript page creates the record from nothing, status from the turn states`() = runBlocking {
        // Deep state loss: no spawn, no seed — the page's transcript fetch is the
        // record's only data source (identity fields stay blank, the top bar falls back
        // to the id).
        store.applyTranscriptPage(SESSION_ID, "agent-12", transcriptPage(listOf(finishedTurn("t0", 0, "done")), hasMore = true), keepLive = false)

        val created = entry("agent-12")!!
        assertEquals(SubagentStatus.Completed, created.status)
        assertEquals(true, created.hasMoreTurns)
        assertEquals("t0", store.oldestTurnId(SESSION_ID, "agent-12"))
        assertEquals(listOf(ChatItem.AssistantMessage("t0.1.f1", "done", thinking = "")), created.items)

        // A transcript carrying a running turn derives Working instead.
        store.applyTranscriptPage(
            SESSION_ID, "agent-13",
            TranscriptPage(
                "agent-13",
                listOf(TranscriptTurn("t0", 0, TranscriptState.Running, null, emptyList())),
                hasMore = false,
            ),
            keepLive = false,
        )
        assertEquals(SubagentStatus.Working, entry("agent-13")!!.status)
    }

    @Test
    fun `a live lifecycle status is never derived back by a transcript page`() = runBlocking {
        store.apply(SESSION_ID, spawned("agent-12"))
        store.apply(SESSION_ID, SessionEvent.SubagentStarted("agent-12"))

        // A stale page (all turns completed) must not flip the live Working status.
        store.applyTranscriptPage(SESSION_ID, "agent-12", transcriptPage(listOf(finishedTurn("t0", 0, "stale"))), keepLive = true)

        assertEquals(SubagentStatus.Working, entry("agent-12")!!.status)
    }

    @Test
    fun `a transcript page merges under the live tail and a later page prepends`() = runBlocking {
        store.apply(SESSION_ID, spawned("agent-12"))
        store.apply(SESSION_ID, SessionEvent.AssistantDelta("fresh live text", "agent-12"))

        store.applyTranscriptPage(SESSION_ID, "agent-12", transcriptPage(listOf(finishedTurn("t1", 1, "committed")), hasMore = true), keepLive = true)
        assertEquals(
            listOf(
                ChatItem.AssistantMessage("t1.1.f1", "committed", thinking = ""),
                ChatItem.AssistantMessage("live_0", "fresh live text", thinking = ""),
            ),
            entry("agent-12")!!.items,
        )

        store.prependTranscriptPage(SESSION_ID, "agent-12", transcriptPage(listOf(finishedTurn("t0", 0, "older"))))
        val state = entry("agent-12")!!
        assertEquals(
            listOf("older", "committed", "fresh live text"),
            state.items.map { (it as ChatItem.AssistantMessage).text },
        )
        assertEquals("t0", store.oldestTurnId(SESSION_ID, "agent-12"))
        assertEquals(false, state.hasMoreTurns)
    }

    @Test
    fun `the prompt bubble survives the transcript that replaces the summary`() = runBlocking {
        // 045 seed first (cold open), then 046's fetch lands: the prompt bubble is the
        // page's only user row (the transcript never carries the task prompt), the
        // seeded summary bubble gives way to the real turns.
        store.applyAgentCall(
            SESSION_ID,
            toolCallId = "tool_1",
            argsJson = AGENT_ARGS,
            resultText = "agent_id: agent-12\nactual_subagent_type: explore\nstatus: completed\n\n[summary]\nthe final report",
            rowStatus = ToolStatus.Done,
        )

        store.applyTranscriptPage(SESSION_ID, "agent-12", transcriptPage(listOf(finishedTurn("t0", 0, "the real final text"))), keepLive = false)

        val state = entry("agent-12")!!
        assertEquals("explore", state.name)
        assertEquals("doc-verify", state.description)
        assertEquals(SubagentStatus.Completed, state.status)
        assertEquals(
            listOf(
                ChatItem.UserMessage("seeded_prompt", "Reply ok"),
                ChatItem.AssistantMessage("t0.1.f1", "the real final text", thinking = ""),
            ),
            state.items,
        )
    }

    // --- Snapshot roster (tickets 085/091) ----------------------------------------------

    private fun rosterEntry(
        id: String,
        phase: SubagentRosterPhase,
        outputPreview: String? = null,
        parentToolCallId: String = "tool_1",
    ) = SubagentRosterEntry(
        id = id,
        subagentType = "explore",
        description = "doc-verify",
        phase = phase,
        parentToolCallId = parentToolCallId,
        runInBackground = false,
        outputPreview = outputPreview,
    )

    @Test
    fun `the snapshot roster applies identity and the precise phase`() = runBlocking {
        store.applyRoster(
            SESSION_ID,
            listOf(
                rosterEntry("agent-12", SubagentRosterPhase.Working),
                rosterEntry("agent-13", SubagentRosterPhase.Suspended),
                rosterEntry("agent-14", SubagentRosterPhase.Failed),
                rosterEntry("agent-15", SubagentRosterPhase.Completed, outputPreview = "the tail of the result"),
                rosterEntry("agent-16", SubagentRosterPhase.Queued),
            ),
        )

        val working = entry("agent-12")!!
        assertEquals("explore", working.name)
        assertEquals("doc-verify", working.description)
        assertEquals(SubagentStatus.Working, working.status)
        assertEquals(SubagentStatus.Suspended, entry("agent-13")!!.status)
        assertEquals(SubagentStatus.Failed, entry("agent-14")!!.status)
        val completed = entry("agent-15")!!
        assertEquals(SubagentStatus.Completed, completed.status)
        assertEquals("the tail of the result", completed.resultSummary)
        assertEquals(SubagentStatus.Queued, entry("agent-16")!!.status)
    }

    @Test
    fun `a roster apply never touches a live-pinned record`() = runBlocking {
        store.apply(SESSION_ID, spawned("agent-12"))
        store.apply(SESSION_ID, SessionEvent.SubagentStarted("agent-12"))
        store.apply(SESSION_ID, SessionEvent.AssistantDelta("live text", "agent-12"))

        store.applyRoster(
            SESSION_ID,
            listOf(rosterEntry("agent-12", SubagentRosterPhase.Completed, outputPreview = "stale summary")),
        )

        val untouched = entry("agent-12")!!
        assertEquals(SubagentStatus.Working, untouched.status)
        assertEquals(null, untouched.resultSummary)
        assertEquals(
            listOf(ChatItem.AssistantMessage("live_0", "live text", thinking = "")),
            untouched.items,
        )
    }

    @Test
    fun `a roster-seeded record accumulates live frames from the first one`() = runBlocking {
        // The record exists before the subscribe (ticket 085): no "spawn missed" drop.
        store.applyRoster(SESSION_ID, listOf(rosterEntry("agent-12", SubagentRosterPhase.Working)))

        store.apply(SESSION_ID, SessionEvent.AssistantDelta("frame one", "agent-12"))

        assertEquals(
            listOf(ChatItem.AssistantMessage("live_0", "frame one", thinking = "")),
            entry("agent-12")!!.items,
        )
    }

    @Test
    fun `a transcript page never regresses a roster-applied status`() = runBlocking {
        // Precedence (ticket 091): the roster outranks the transcript — a stale page
        // must not regress its Working to the turns' Completed.
        store.applyRoster(SESSION_ID, listOf(rosterEntry("agent-12", SubagentRosterPhase.Working)))

        store.applyTranscriptPage(SESSION_ID, "agent-12", transcriptPage(listOf(finishedTurn("t0", 0, "committed"))), keepLive = false)

        val state = entry("agent-12")!!
        assertEquals(SubagentStatus.Working, state.status)
        assertEquals(listOf(ChatItem.AssistantMessage("t0.1.f1", "committed", thinking = "")), state.items)
    }

    @Test
    fun `a fresh roster heals a transcript-derived status and is never regressed back`() = runBlocking {
        // Ticket 091's re-applicability: the roster applies on EVERY entry, so a
        // record whose status came from the transcript heals to the fresh phase.
        store.applyTranscriptPage(
            SESSION_ID, "agent-12",
            TranscriptPage("agent-12", listOf(TranscriptTurn("t0", 0, TranscriptState.Running, null, emptyList())), hasMore = false),
            keepLive = true,
        )
        assertEquals(SubagentStatus.Working, entry("agent-12")!!.status)

        store.applyRoster(SESSION_ID, listOf(rosterEntry("agent-12", SubagentRosterPhase.Suspended)))
        assertEquals(SubagentStatus.Suspended, entry("agent-12")!!.status)

        // ...and a stale transcript page never regresses the roster's word.
        store.applyTranscriptPage(SESSION_ID, "agent-12", transcriptPage(listOf(finishedTurn("t0", 0, "done"))), keepLive = false)
        assertEquals(SubagentStatus.Suspended, entry("agent-12")!!.status)
    }

    @Test
    fun `an agent result heals a transcript-derived status but never a live-pinned one`() = runBlocking {
        // Transcript-derived Working: the result's completed outranks it.
        store.applyTranscriptPage(
            SESSION_ID, "agent-12",
            TranscriptPage("agent-12", listOf(TranscriptTurn("t0", 0, TranscriptState.Running, null, emptyList())), hasMore = false),
            keepLive = true,
        )
        store.applyAgentCall(
            SESSION_ID,
            toolCallId = "tool_1",
            argsJson = AGENT_ARGS,
            resultText = "agent_id: agent-12\nstatus: completed\n\n[summary]\nthe report",
            rowStatus = ToolStatus.Done,
        )
        val healed = entry("agent-12")!!
        assertEquals(SubagentStatus.Completed, healed.status)
        assertEquals("the report", healed.resultSummary)

        // Live-pinned Working: the same result changes nothing.
        store.apply(SESSION_ID, spawned("agent-13", toolCallId = "tool_2"))
        store.apply(SESSION_ID, SessionEvent.SubagentStarted("agent-13"))
        store.applyAgentCall(
            SESSION_ID,
            toolCallId = "tool_2",
            argsJson = AGENT_ARGS,
            resultText = "agent_id: agent-13\nstatus: completed\n\n[summary]\nlate",
            rowStatus = ToolStatus.Done,
        )
        val pinned = entry("agent-13")!!
        assertEquals(SubagentStatus.Working, pinned.status)
        assertEquals(null, pinned.resultSummary)
    }

    @Test
    fun `a transcript-only cold open maps terminal turn states precisely`() = runBlocking {
        // Roster empty (it resets on the main agent's next turn): the real turn states
        // (0.31.1) are the cold-status source — no more blanket-Completed heuristic.
        store.applyTranscriptPage(
            SESSION_ID, "agent-12",
            TranscriptPage("agent-12", listOf(TranscriptTurn("t0", 0, TranscriptState.Failed, null, emptyList())), hasMore = false),
            keepLive = false,
        )
        assertEquals(SubagentStatus.Failed, entry("agent-12")!!.status)

        store.applyTranscriptPage(
            SESSION_ID, "agent-13",
            TranscriptPage("agent-13", listOf(TranscriptTurn("t0", 0, TranscriptState.Completed, null, emptyList())), hasMore = false),
            keepLive = false,
        )
        assertEquals(SubagentStatus.Completed, entry("agent-13")!!.status)
    }

    // --- The session link registry (ticket 091) -----------------------------------------

    @Test
    fun `the link registry learns from spawned roster and result markers`() = runBlocking {
        store.apply(SESSION_ID, spawned("agent-12", toolCallId = "tool_1"))
        store.applyRoster(SESSION_ID, listOf(rosterEntry("agent-13", SubagentRosterPhase.Working, parentToolCallId = "tool_2")))
        store.applyAgentCall(
            SESSION_ID,
            toolCallId = "tool_3",
            argsJson = AGENT_ARGS,
            resultText = "agent_id: agent-14\nstatus: completed\n\n[summary]\ndone",
            rowStatus = ToolStatus.Done,
        )

        assertEquals(
            mapOf("tool_1" to "agent-12", "tool_2" to "agent-13", "tool_3" to "agent-14"),
            store.linksFor(SESSION_ID),
        )
    }

    // --- Todos & jobs (the sub-agent page's dock) ---------------------------------------

    private fun taskInfo(
        taskId: String,
        status: TaskInfo.Status,
        kind: TaskInfo.Kind = TaskInfo.Kind.Process,
        endedAt: Instant? = null,
    ) = TaskInfo(taskId, "nested bg", status, Instant.ofEpochMilli(1785210101939), endedAt, kind = kind)

    @Test
    fun `task frames feed the owning sub-agent's jobs list`() = runBlocking {
        store.apply(SESSION_ID, spawned("agent-12"))

        store.apply(
            SESSION_ID,
            SessionEvent.TaskStarted(taskInfo("bash-1", TaskInfo.Status.Running), agentId = "agent-12"),
        )

        assertEquals(
            listOf(
                Job(
                    "bash-1", BackgroundTask.Kind.Bash, "nested bg",
                    BackgroundTask.Status.Running, Instant.ofEpochMilli(1785210101939),
                ),
            ),
            entry("agent-12")!!.jobs,
        )

        // The terminal frame flips the same job — core timed_out maps to the wire
        // Failed and retains the live-only timed-out flag (ticket 097).
        store.apply(
            SESSION_ID,
            SessionEvent.TaskTerminated(
                taskInfo("bash-1", TaskInfo.Status.TimedOut, endedAt = Instant.ofEpochMilli(1785210118804)),
                agentId = "agent-12",
            ),
        )
        val terminated = entry("agent-12")!!.jobs.single()
        assertEquals(BackgroundTask.Status.Failed, terminated.status)
        assertTrue(terminated.timedOut)
        assertEquals(Instant.ofEpochMilli(1785210118804), terminated.endedAt)
    }

    @Test
    fun `main-owned task frames never touch the records`() = runBlocking {
        store.apply(SESSION_ID, spawned("agent-12"))

        // The default agentId (main) — the chat's JobsStore owns those.
        store.apply(SESSION_ID, SessionEvent.TaskStarted(taskInfo("bash-1", TaskInfo.Status.Running)))

        assertTrue(entry("agent-12")!!.jobs.isEmpty())
    }

    @Test
    fun `a task frame for an unknown sub-agent is dropped without creating a record`() = runBlocking {
        store.apply(SESSION_ID, SessionEvent.TaskStarted(taskInfo("bash-1", TaskInfo.Status.Running), agentId = "agent-99"))

        assertNull(store.entry(SESSION_ID, "agent-99").first())
    }

    @Test
    fun `the todo list surfaces from the sub-agent's live TodoList call`() = runBlocking {
        store.apply(SESSION_ID, spawned("agent-12"))

        store.apply(
            SESSION_ID,
            SessionEvent.ToolCallStarted(
                "tool_t", "TodoList",
                input = """{"todos":[{"title":"write","status":"done"},{"title":"verify","status":"in_progress"}]}""",
                agentId = "agent-12",
            ),
        )

        assertEquals(
            listOf(Todo("write", Todo.Status.Done), Todo("verify", Todo.Status.InProgress)),
            entry("agent-12")!!.todos,
        )
    }

    @Test
    fun `the todo list surfaces from a cold-open transcript page`() = runBlocking {
        // The committed path: a finished sub-agent's TodoList call reaches the record
        // through the transcript fetch, exactly like the chat's history (ticket 042).
        val turn = TranscriptTurn(
            "t0", 0, TranscriptState.Completed, null,
            listOf(
                TranscriptStep(
                    "t0.1", TranscriptState.Completed,
                    listOf(
                        TranscriptFrame.Tool(
                            "t0.1.f1", "tool_t", "TodoList", running = false,
                            preview = "2 todos",
                            input = """{"todos":[{"title":"write","status":"done"},{"title":"verify","status":"pending"}]}""",
                            output = "The todos have been updated.",
                        ),
                    ),
                ),
            ),
        )

        store.applyTranscriptPage(SESSION_ID, "agent-12", transcriptPage(listOf(turn)), keepLive = false)

        assertEquals(
            listOf(Todo("write", Todo.Status.Done), Todo("verify", Todo.Status.Pending)),
            entry("agent-12")!!.todos,
        )
    }

    private companion object {
        const val SESSION_ID = "session_1"

        /** The `Agent` call's args as mined from a row/snapshot (raw JSON). */
        const val AGENT_ARGS = """{"subagent_type":"explore","description":"doc-verify","prompt":"Reply ok"}"""
    }
}
