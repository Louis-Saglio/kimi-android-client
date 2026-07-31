package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.TranscriptFrame
import fr.louissaglio.kimi.android.data.chat.TranscriptPage
import fr.louissaglio.kimi.android.data.chat.TranscriptState
import fr.louissaglio.kimi.android.data.chat.TranscriptStep
import fr.louissaglio.kimi.android.data.chat.TranscriptTurn
import fr.louissaglio.kimi.android.testutil.testSubagentStreamStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The store runs with a 0 ms coalescing window here: every delta applies and publishes
 * on arrival, no flush timer involved (the window's semantics are covered by
 * ChatViewModelTest's twin implementation).
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
    fun `spawned creates the entry with its identity metadata`() = runBlocking {
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

        val twelve = entry("agent-12")!!.items
        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_1", "task for 12"),
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
    fun `frames for an unknown sub-agent are dropped without creating an entry`() = runBlocking {
        store.apply(SESSION_ID, SessionEvent.AssistantDelta("ghost", "agent-99"))
        store.apply(SESSION_ID, SessionEvent.SubagentStarted("agent-99"))
        store.apply(SESSION_ID, SessionEvent.SubagentCompleted("agent-99", "ghost summary"))

        assertNull(entry("agent-99"))
    }

    @Test
    fun `turn started seals the finished turn above the next spliced prompt`() = runBlocking {
        // Sub-agent internal turns (docs/server-api.md): the task turn, then the forced
        // result-summary turn — each opens with turn.started, its prompt spliced inline.
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
                ChatItem.UserMessage("msg_task", "the task"),
                ChatItem.AssistantMessage("live_0", "working on it", thinking = ""),
                ChatItem.UserMessage("msg_nudge", "summarize the result"),
                ChatItem.AssistantMessage("live_1", "the summary", thinking = ""),
            ),
            entry("agent-12")!!.items,
        )
    }

    @Test
    fun `release drops the session's streams`() = runBlocking {
        store.apply(SESSION_ID, spawned("agent-12"))
        store.release(SESSION_ID)

        assertNull(entry("agent-12"))
    }

    @Test
    fun `seeding from a completed Agent row shows the summary on the page`() = runBlocking {
        // Cold-open (ticket 045): the whole run predates the subscription; history's
        // Agent row is the only artifact left (version-pinned result format).
        store.seedFromAgentRow(
            SESSION_ID,
            agentRow(output = "agent_id: agent-12\nactual_subagent_type: explore\nstatus: completed\n\n[summary]\nthe final report"),
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
    }

    @Test
    fun `seeding never overwrites a live entry`() = runBlocking {
        store.apply(SESSION_ID, spawned("agent-12"))
        store.apply(SESSION_ID, SessionEvent.AssistantDelta("live text", "agent-12"))

        store.seedFromAgentRow(
            SESSION_ID,
            agentRow(output = "agent_id: agent-12\nstatus: completed\n\n[summary]\nlate summary"),
        )

        val untouched = entry("agent-12")!!
        assertEquals(SubagentStatus.Queued, untouched.status)
        assertEquals(null, untouched.resultSummary)
        assertEquals(
            listOf(ChatItem.AssistantMessage("live_0", "live text", thinking = "")),
            untouched.items,
        )
    }

    @Test
    fun `a background running row seeds a working entry that keeps streaming`() = runBlocking {
        // Background shape (docs/server-api.md): the result returns immediately with
        // task fields first and no [summary] section; the run continues afterwards.
        store.seedFromAgentRow(
            SESSION_ID,
            agentRow(subagentId = "agent-13", output = "task_id: agent-7noib8bb\nstatus: running\nagent_id: agent-13"),
        )

        val working = entry("agent-13")!!
        assertEquals(SubagentStatus.Working, working.status)
        assertEquals(null, working.resultSummary)
        assertEquals(listOf(ChatItem.UserMessage("seeded_prompt", "Reply ok")), working.items)

        // The entry existing is what lets frames from the subscription point
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
    fun `an unlinked row seeds nothing`() = runBlocking {
        store.seedFromAgentRow(
            SESSION_ID,
            ChatItem.ToolActivity(
                "tool_1", "Agent", ToolStatus.Done, "doc-verify",
                output = "agent_id: agent-12\nstatus: completed\n\n[summary]\nok",
            ),
        )

        assertNull(entry("agent-12"))
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
    fun `a transcript page creates the entry from nothing, status from the turn states`() = runBlocking {
        // Deep state loss: no spawn, no seed — the page's transcript fetch is the
        // entry's only data source (identity fields stay blank, the top bar falls back
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
    fun `the seeded prompt bubble survives the transcript that replaces the summary`() = runBlocking {
        // 045 seed first (cold open), then 046's fetch lands: the prompt bubble is the
        // page's only user row (the transcript never carries the task prompt), the
        // seeded summary bubble gives way to the real turns.
        store.seedFromAgentRow(
            SESSION_ID,
            agentRow(output = "agent_id: agent-12\nactual_subagent_type: explore\nstatus: completed\n\n[summary]\nthe final report"),
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

    private fun agentRow(
        subagentId: String = "agent-12",
        output: String,
        status: ToolStatus = ToolStatus.Done,
    ) = ChatItem.ToolActivity(
        "tool_1",
        "Agent",
        status,
        preview = "doc-verify",
        input = "{\n    \"subagent_type\": \"explore\",\n    \"description\": \"doc-verify\",\n    \"prompt\": \"Reply ok\"\n}",
        output = output,
        subagentId = subagentId,
    )

    private companion object {
        const val SESSION_ID = "session_1"
    }
}
