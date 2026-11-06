package fr.louissaglio.kimi.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The jobs sheet's tap navigation for sub-agent jobs without an agentId (a
 * transcript-healed job has no `child_agent_id`): the page id is recovered from the spawning `Agent` row's result
 * stub, which carries `task_id: <taskId>` next to the row's sub-agent link. */
class SubagentIdForTaskTest {

    private fun agentRow(taskId: String, subagentId: String?) = ChatItem.ToolActivity(
        id = "call-1",
        name = "Agent",
        status = ToolStatus.Done,
        output = "task_id: $taskId\nstatus: running\nagent_id: ${subagentId.orEmpty()}",
        subagentId = subagentId,
    )

    @Test
    fun `a linked agent row whose stub names the task resolves the page id`() {
        val items = listOf(ChatItem.ToolGroup(agentRow("agent-zrni744l", "agent-13")))

        assertEquals("agent-13", subagentIdForTask(items, "agent-zrni744l"))
    }

    @Test
    fun `a stub naming another task does not resolve`() {
        val items = listOf(ChatItem.ToolGroup(agentRow("agent-zrni744l", "agent-13")))

        assertNull(subagentIdForTask(items, "agent-other99"))
    }

    @Test
    fun `an unlinked row does not resolve`() {
        val items = listOf(ChatItem.ToolGroup(agentRow("agent-zrni744l", null)))

        assertNull(subagentIdForTask(items, "agent-zrni744l"))
    }

    @Test
    fun `no timeline resolves nothing`() {
        assertNull(subagentIdForTask(null, "agent-zrni744l"))
        assertNull(subagentIdForTask(emptyList(), "agent-zrni744l"))
    }
}
