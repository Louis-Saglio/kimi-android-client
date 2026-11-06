package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the jobs sheet rows (ticket 096): a bash job row navigates
 * to the job detail screen, a linked sub-agent row keeps opening the sub-agent
 * page. Rows merge their descendants, so they are matched by content description
 * (substring — the elapsed time ticks every second). */
@RunWith(AndroidJUnit4::class)
class JobsWidgetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun sheet(
        jobs: List<Job>,
        onCancel: ((String) -> Unit)? = {},
        onOpenJob: ((String) -> Unit)? = null,
        onOpenSubagent: (Job) -> Unit = {},
    ) {
        composeTestRule.setContent {
            KimiTheme {
                JobsSheet(
                    jobs = jobs,
                    onCancel = onCancel,
                    onOpenSubagent = onOpenSubagent,
                    onOpenJob = onOpenJob,
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun tappingBashRowOpensJobDetail() {
        var opened: String? = null
        sheet(
            jobs = listOf(
                Job(
                    taskId = "bash-1",
                    kind = Job.Kind.Bash,
                    description = "tick counter loop",
                    status = Job.Status.Running,
                    startedAt = Instant.now().minusSeconds(5),
                ),
            ),
            onOpenJob = { opened = it },
        )

        composeTestRule
            .onNodeWithContentDescription("tick counter loop, bash, running", substring = true)
            .assertIsDisplayed()
            .performClick()
        assertEquals("bash-1", opened)
    }

    @Test
    fun tappingSubagentRowStillOpensSubagentPage() {
        var openedAgent: Job? = null
        var openedJob: String? = null
        val job = Job(
            taskId = "agent-9",
            kind = Job.Kind.Subagent,
            description = "doc-verify",
            status = Job.Status.Running,
            startedAt = Instant.now().minusSeconds(5),
            agentId = "agent-9",
        )
        sheet(
            jobs = listOf(job),
            onOpenSubagent = { openedAgent = it },
            onOpenJob = { openedJob = it },
        )

        composeTestRule
            .onNodeWithContentDescription("doc-verify, sub-agent, running", substring = true)
            .performClick()
        assertEquals(job, openedAgent)
        assertEquals(null, openedJob)
    }

    @Test
    fun tappingLinklessSubagentRowReportsTheJob() {
        // A transcript-healed sub-agent job carries no agentId (no child_agent_id): the
        // row stays tappable and reports the whole Job — the caller resolves the page
        // id from the timeline.
        var openedAgent: Job? = null
        val job = Job(
            taskId = "agent-9",
            kind = Job.Kind.Subagent,
            description = "doc-verify",
            status = Job.Status.Completed,
            startedAt = Instant.now().minusSeconds(5),
            endedAt = Instant.now(),
        )
        sheet(
            jobs = listOf(job),
            onOpenSubagent = { openedAgent = it },
        )

        composeTestRule
            .onNodeWithContentDescription("doc-verify, sub-agent, completed", substring = true)
            .performClick()
        assertEquals(job, openedAgent)
    }

    @Test
    fun timedOutBashRowReadsTimedOutAfterTheElapsed() {
        // Ticket 097: a live-recorded timeout reads "timed out after <elapsed>" instead
        // of the plain "failed" a failed outcome shows.
        sheet(
            jobs = listOf(
                Job(
                    taskId = "bash-1",
                    kind = Job.Kind.Bash,
                    description = "tick counter loop",
                    status = Job.Status.Failed,
                    startedAt = Instant.now().minusSeconds(5),
                    endedAt = Instant.now(),
                    timedOut = true,
                ),
            ),
        )

        composeTestRule
            .onNodeWithContentDescription("tick counter loop, bash, timed out after", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun nullOnCancelHidesTheStopButton() {
        // The sub-agent page's sheet is read-only (its own task controls are not
        // wired — no ticket owns them yet): no Stop affordance on running rows.
        sheet(
            jobs = listOf(
                Job(
                    taskId = "bash-1",
                    kind = Job.Kind.Bash,
                    description = "tick counter loop",
                    status = Job.Status.Running,
                    startedAt = Instant.now().minusSeconds(5),
                ),
            ),
            onCancel = null,
        )

        composeTestRule
            .onNodeWithContentDescription("Stop tick counter loop")
            .assertDoesNotExist()
    }

    @Test
    fun nullOnOpenJobLeavesTheBashRowPlain() {
        // Same read-only sheet: the job screen's fetch is main-agent-only too, so a
        // bash row gets no tap affordance at all.
        sheet(
            jobs = listOf(
                Job(
                    taskId = "bash-1",
                    kind = Job.Kind.Bash,
                    description = "tick counter loop",
                    status = Job.Status.Running,
                    startedAt = Instant.now().minusSeconds(5),
                ),
            ),
            onOpenJob = null,
        )

        composeTestRule
            .onNodeWithContentDescription("tick counter loop, bash, running", substring = true)
            .assertHasNoClickAction()
    }
}
