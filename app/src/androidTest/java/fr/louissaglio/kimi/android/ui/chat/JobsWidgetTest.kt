package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.data.chat.BackgroundTask
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
        onOpenJob: (String) -> Unit = {},
        onOpenSubagent: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            KimiTheme {
                JobsSheet(
                    jobs = jobs,
                    onCancel = {},
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
                    kind = BackgroundTask.Kind.Bash,
                    description = "tick counter loop",
                    status = BackgroundTask.Status.Running,
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
        var openedAgent: String? = null
        var openedJob: String? = null
        sheet(
            jobs = listOf(
                Job(
                    taskId = "agent-9",
                    kind = BackgroundTask.Kind.Subagent,
                    description = "doc-verify",
                    status = BackgroundTask.Status.Running,
                    startedAt = Instant.now().minusSeconds(5),
                    agentId = "agent-9",
                ),
            ),
            onOpenSubagent = { openedAgent = it },
            onOpenJob = { openedJob = it },
        )

        composeTestRule
            .onNodeWithContentDescription("doc-verify, sub-agent, running", substring = true)
            .performClick()
        assertEquals("agent-9", openedAgent)
        assertEquals(null, openedJob)
    }
}
