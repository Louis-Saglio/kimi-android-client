package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.AgentCallDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `agent_call` spawn row's tap targets (ticket 157): a foreground spawn opens
 * the sub-agent page, a background one the job screen — the row's own deep links,
 * never an expansion. */
class AgentSpawnTapTest {

    private fun spawn(background: Boolean) = ChatItem.ToolActivity(
        "tool_1",
        "Agent",
        ToolStatus.Running,
        agentCall = AgentCallDisplay("explore", "Reply with the single word: ok", background),
    )

    @Test
    fun `a foreground spawn row opens the sub-agent page`() {
        val row = spawn(background = false).copy(subagentId = "agent-12")

        assertEquals(AgentSpawnTap.Subagent("agent-12"), row.agentSpawnTap())
    }

    @Test
    fun `a background spawn row opens the job screen`() {
        val row = spawn(background = true).copy(agentTaskId = "agent-7noib8bb")

        assertEquals(AgentSpawnTap.Job("agent-7noib8bb"), row.agentSpawnTap())
    }

    @Test
    fun `a spawn row whose link has not landed yet is inert`() {
        // Foreground before subagent.spawned correlates; background before the
        // spawn result's `task_id:` head arrives.
        assertNull(spawn(background = false).agentSpawnTap())
        assertNull(spawn(background = true).agentSpawnTap())
    }

    @Test
    fun `a background spawn row never falls back to the sub-agent page`() {
        val row = spawn(background = true).copy(subagentId = "agent-12")

        assertNull(row.agentSpawnTap())
    }

    @Test
    fun `a row without the agent call display is not a spawn row`() {
        val row = ChatItem.ToolActivity("tool_1", "Agent", ToolStatus.Running, subagentId = "agent-12")

        assertNull(row.agentSpawnTap())
    }
}
