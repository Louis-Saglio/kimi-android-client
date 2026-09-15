package fr.louissaglio.kimi.android.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwarmProgressTest {

    private fun member(
        status: SubagentStatus,
        id: String = "agent-${status.name}",
        model: String? = "kimi-code/k3",
        thinkingEffort: String? = "high",
    ) = SubagentStreamStore.SubagentState(
        id = id,
        name = "explore",
        description = "d",
        runInBackground = false,
        status = status,
        model = model,
        thinkingEffort = thinkingEffort,
    )

    private fun progress(
        vararg statuses: SubagentStatus,
        expectedTotal: Int? = null,
        toolRunning: Boolean = false,
    ) = SwarmProgress.of(statuses.map { member(it) }, expectedTotal, toolRunning)

    // --- The aggregate status matrix -------------------------------------------

    @Test
    fun `any active phase makes the aggregate Running`() {
        listOf(SubagentStatus.Queued, SubagentStatus.Working, SubagentStatus.Suspended).forEach { active ->
            val progress = progress(active, SubagentStatus.Completed, SubagentStatus.Failed)
            assertEquals("$active must run", SwarmAggregateStatus.Running, progress.status)
            assertEquals("1 in progress", progress.subtitle)
        }
    }

    @Test
    fun `a still-running call makes the aggregate Running even with no active member`() {
        val settled = progress(SubagentStatus.Completed, toolRunning = true)
        assertEquals(SwarmAggregateStatus.Running, settled.status)
        assertEquals("0 in progress", settled.subtitle)
        // The empty-members case: nothing spawned yet, the call still runs.
        val empty = progress(toolRunning = true)
        assertEquals(SwarmAggregateStatus.Running, empty.status)
        assertEquals("0 in progress", empty.subtitle)
    }

    @Test
    fun `empty members settle to Completed once the call is done`() {
        assertEquals(SwarmAggregateStatus.Completed, progress().status)
    }

    @Test
    fun `a failed member dominates the settled phases`() {
        val progress = progress(
            SubagentStatus.Completed, SubagentStatus.Failed, SubagentStatus.Cancelled,
        )
        assertEquals(SwarmAggregateStatus.Failed, progress.status)
    }

    @Test
    fun `cancelled without any completed is Cancelled`() {
        val progress = progress(SubagentStatus.Cancelled, SubagentStatus.Cancelled)
        assertEquals(SwarmAggregateStatus.Cancelled, progress.status)
    }

    @Test
    fun `cancelled alongside a completed settles to Completed`() {
        val progress = progress(SubagentStatus.Completed, SubagentStatus.Cancelled)
        assertEquals(SwarmAggregateStatus.Completed, progress.status)
    }

    // --- Totals and counts -------------------------------------------------------

    @Test
    fun `the expected total covers members not yet spawned`() {
        val progress = progress(
            SubagentStatus.Working,
            SubagentStatus.Completed,
            SubagentStatus.Failed,
            SubagentStatus.Cancelled,
            expectedTotal = 10,
        )
        assertEquals(4, progress.members)
        assertEquals(10, progress.total)
        assertEquals(3, progress.done)
        assertEquals(1, progress.inProgress)
    }

    @Test
    fun `an overrun of members over the expected total wins the denominator`() {
        val progress = progress(
            SubagentStatus.Completed, SubagentStatus.Completed, SubagentStatus.Completed,
            expectedTotal = 2,
        )
        assertEquals(3, progress.total)
    }

    @Test
    fun `the counts line lists present phases only, in phase order`() {
        val progress = progress(
            SubagentStatus.Working, SubagentStatus.Working,
            SubagentStatus.Suspended,
            SubagentStatus.Completed, SubagentStatus.Completed, SubagentStatus.Completed,
            SubagentStatus.Failed,
            SubagentStatus.Cancelled,
        )
        assertEquals("2 working · 1 suspended · 3 completed · 1 failed · 1 cancelled", progress.countsLine)
    }

    @Test
    fun `the counts line is empty when nothing is known`() {
        assertEquals("", progress().countsLine)
    }

    @Test
    fun `the settled subtitle counts cancelled separately from failed`() {
        val progress = progress(
            SubagentStatus.Completed, SubagentStatus.Completed, SubagentStatus.Completed,
            SubagentStatus.Failed,
            SubagentStatus.Cancelled,
        )
        assertEquals("3 completed · 1 failed · 1 cancelled", progress.subtitle)
    }

    @Test
    fun `the settled subtitle drops the cancelled part when none was cancelled`() {
        val progress = progress(SubagentStatus.Completed, SubagentStatus.Failed)
        assertEquals("1 completed · 1 failed", progress.subtitle)
    }

    // --- The shared model line ----------------------------------------------------

    @Test
    fun `the model line shows when every member agrees`() {
        val progress = SwarmProgress.of(
            listOf(member(SubagentStatus.Working, id = "a"), member(SubagentStatus.Completed, id = "b")),
            expectedTotal = null,
            toolRunning = true,
        )
        assertEquals("kimi-code/k3 · high", progress.modelLine)
    }

    @Test
    fun `the model line hides on mixed models`() {
        val progress = SwarmProgress.of(
            listOf(
                member(SubagentStatus.Working, id = "a", model = "kimi-code/k3"),
                member(SubagentStatus.Working, id = "b", model = "kimi-code/k2"),
            ),
            expectedTotal = null,
            toolRunning = true,
        )
        assertNull(progress.modelLine)
    }

    @Test
    fun `the model line hides when any member's pair is unknown`() {
        val progress = SwarmProgress.of(
            listOf(
                member(SubagentStatus.Working, id = "a"),
                member(SubagentStatus.Working, id = "b", model = null, thinkingEffort = null),
            ),
            expectedTotal = null,
            toolRunning = true,
        )
        assertNull(progress.modelLine)
    }

    @Test
    fun `the model line hides with no members`() {
        assertNull(progress().modelLine)
    }
}
