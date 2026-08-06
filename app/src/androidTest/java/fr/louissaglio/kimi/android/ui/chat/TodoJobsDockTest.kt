package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.data.chat.BackgroundTask
import fr.louissaglio.kimi.android.data.chat.Todo
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Semantics tests for the shared todos/jobs dock: the pill row's visibility, the
 * sheets it opens, and the sheet's sub-agent tap resolution against the caller's
 * timeline items (a REST-seeded job carries no agentId — the spawning `Agent` row's
 * `task_id:` stub fills the gap). Rows merge their descendants, so they are matched
 * by content description (substring — the elapsed time ticks every second).
 */
@RunWith(AndroidJUnit4::class)
class TodoJobsDockTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val todos = listOf(
        Todo("write", Todo.Status.Done),
        Todo("verify", Todo.Status.Pending),
    )

    private val bashJob = Job(
        taskId = "bash-1",
        kind = BackgroundTask.Kind.Bash,
        description = "tick counter loop",
        status = BackgroundTask.Status.Running,
        startedAt = Instant.now().minusSeconds(5),
    )

    private fun dock(
        todos: List<Todo>?,
        jobs: List<Job>,
        items: List<ChatItem> = emptyList(),
        onOpenSubagent: (String) -> Unit = {},
    ) {
        composeTestRule.setContent {
            KimiTheme {
                TodoJobsDock(
                    todos = todos,
                    jobs = jobs,
                    items = items,
                    onOpenSubagent = onOpenSubagent,
                )
            }
        }
    }

    @Test
    fun hiddenWhenBothListsAreEmpty() {
        dock(todos = null, jobs = emptyList())

        composeTestRule.onNodeWithContentDescription("Todos", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Jobs", substring = true).assertDoesNotExist()
    }

    @Test
    fun theTodosPillOpensItsSheet() {
        dock(todos = todos, jobs = emptyList())

        composeTestRule
            .onNodeWithContentDescription("Todos, 1 of 2 done, tap to view")
            .performClick()

        composeTestRule.onNodeWithText("verify").assertIsDisplayed()
    }

    @Test
    fun theJobsPillOpensItsSheet() {
        dock(todos = null, jobs = listOf(bashJob))

        composeTestRule
            .onNodeWithContentDescription("Jobs, 0 of 1 finished, tap to view")
            .performClick()

        composeTestRule
            .onNodeWithContentDescription("tick counter loop, bash, running", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun aRestSeededSubagentJobResolvesItsPageFromTheTimeline() {
        var opened: String? = null
        val job = Job(
            taskId = "agent-9",
            kind = BackgroundTask.Kind.Subagent,
            description = "doc-verify",
            status = BackgroundTask.Status.Running,
            startedAt = Instant.now().minusSeconds(5),
        )
        // The spawning `Agent` row of the caller's timeline: linked to its sub-agent,
        // its result stub carrying the matching `task_id:` line (ticket 092).
        val items = listOf(
            ChatItem.ToolGroup(
                "g1",
                listOf(
                    ChatItem.ToolActivity(
                        "t1", "Agent", ToolStatus.Done,
                        output = "task_id: agent-9\nstatus: running",
                        subagentId = "agent-9",
                    ),
                ),
            ),
        )
        dock(todos = null, jobs = listOf(job), items = items, onOpenSubagent = { opened = it })

        composeTestRule
            .onNodeWithContentDescription("Jobs, 0 of 1 finished, tap to view")
            .performClick()
        composeTestRule
            .onNodeWithContentDescription("doc-verify, sub-agent, running", substring = true)
            .performClick()

        assertEquals("agent-9", opened)
    }

    @Test
    fun anUnresolvableSubagentJobTapIsANoOp() {
        var opened: String? = null
        val job = Job(
            taskId = "agent-9",
            kind = BackgroundTask.Kind.Subagent,
            description = "doc-verify",
            status = BackgroundTask.Status.Running,
            startedAt = Instant.now().minusSeconds(5),
        )
        dock(todos = null, jobs = listOf(job), onOpenSubagent = { opened = it })

        composeTestRule
            .onNodeWithContentDescription("Jobs, 0 of 1 finished, tap to view")
            .performClick()
        composeTestRule
            .onNodeWithContentDescription("doc-verify, sub-agent, running", substring = true)
            .performClick()

        assertNull(opened)
    }
}
