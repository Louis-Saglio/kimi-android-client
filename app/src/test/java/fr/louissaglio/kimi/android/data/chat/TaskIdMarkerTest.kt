package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `task_id:` stub parsers of background tool results (ticket 101): the bash one
 * links a row to its job screen (ticket 096), the Agent one feeds JobsStore's
 * known-background set so the REST `/tasks` seed keeps a still-running background
 * sub-agent whose `task.started` predates the subscription.
 */
class TaskIdMarkerTest {

    @Test
    fun `a background Agent result yields its agent- task id`() {
        val output = "task_id: agent-7noib8bb\nstatus: running\nagent_id: agent-13\n"

        assertEquals("agent-7noib8bb", agentTaskIdOf("Agent", output))
    }

    @Test
    fun `foreground results, bash ids and non-Agent calls parse to null`() {
        // A foreground run's first line is the agent_id marker — no task id.
        assertNull(agentTaskIdOf("Agent", "agent_id: agent-13\n\n[summary]\ndone"))
        assertNull(agentTaskIdOf("Agent", "task_id: bash-2q5gu61e\nstatus: running"))
        assertNull(agentTaskIdOf("Bash", "task_id: agent-7noib8bb\nstatus: running"))
        assertNull(agentTaskIdOf("Agent", ""))
    }

    @Test
    fun `a detach stub yields its agent- task id`() {
        // The detached foreground Agent call resolves with the background stub plus
        // the note line (docs/server-api.md, REST /tasks :detach — ticket 138): the
        // first line keeps the task_id marker, so the ticket-101 jobs seed keeps a
        // still-running DETACHED sub-agent exactly like a spawned-background one.
        val output = "task_id: agent-7noib8bb\nstatus: running\nagent_id: agent-13\n" +
            "actual_subagent_type: explore\nautomatic_notification: true\n\n" +
            "description: doc-verify\n\n" +
            "note: The user moved this subagent to the background.\nnext_step: …\n"

        assertEquals("agent-7noib8bb", agentTaskIdOf("Agent", output))
    }
}
