package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The store runs with a 0 ms coalescing window here: every delta applies and publishes
 * on arrival, no flush timer involved (the window's semantics are covered by
 * ChatViewModelTest's twin implementation).
 */
class SubagentStreamStoreTest {

    private val store = SubagentStreamStore(Json { ignoreUnknownKeys = true }, streamCoalesceWindowMillis = 0)

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
