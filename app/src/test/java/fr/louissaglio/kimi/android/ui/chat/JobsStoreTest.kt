package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.chat.TaskOutcome
import fr.louissaglio.kimi.android.data.chat.TaskState
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.testChatRepository
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The jobs-widget collaborator on task frames (ticket 179): the frame → [Job] mapping
 * (kinds, outcomes, the background-only filter), the `task.list` merge (running tasks
 * seed, terminal rows linger, unseen ends drop), the full-state `task` upsert, and a
 * stop with no open socket. The socket round trip of `task.stop` is ChatJobsTest's.
 */
class JobsStoreTest {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var store: JobsStore

    @Volatile
    private var uiState = ChatViewModel.UiState(sessionId = "session_1")
    private val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()

    @Before
    fun setUp() {
        uiState = ChatViewModel.UiState(sessionId = "session_1")
        val dataStore = FakePreferencesDataStore(
            preferencesOf(stringPreferencesKey("server_url") to "http://127.0.0.1:9/"),
        )
        store = JobsStore(
            scope = { appScope },
            chatRepository = testChatRepository(dataStore),
            sessionId = "session_1",
            state = { uiState },
            updateState = { transform -> uiState = transform(uiState) },
            sendEffect = { effect -> effects += effect },
        )
    }

    @After
    fun tearDown() = appScope.cancel()

    private fun task(
        taskId: String,
        kind: TaskState.Kind = TaskState.Kind.Shell,
        running: Boolean = true,
        outcome: TaskOutcome? = null,
        detached: Boolean = true,
        description: String = "Bash: sleep 30",
        childAgentId: String? = null,
        endedAt: Instant? = null,
    ) = TaskState(
        taskId = taskId,
        kind = kind,
        status = if (running) TaskState.Status.Running else TaskState.Status.Ended,
        detached = detached,
        description = description,
        outcome = outcome,
        childAgentId = childAgentId,
        startedAt = STARTED,
        endedAt = endedAt,
    )

    @Test
    fun `the frame mapping keeps shell and subagent background tasks and nothing else`() {
        assertEquals(
            Job("bash-1", Job.Kind.Bash, "Bash: sleep 30", Job.Status.Running, STARTED),
            task("bash-1").toBackgroundJob(),
        )
        assertEquals(
            Job("agent-1", Job.Kind.Subagent, "Review", Job.Status.Running, STARTED, agentId = "agent-0"),
            task("agent-1", TaskState.Kind.Subagent, description = "Review", childAgentId = "agent-0").toBackgroundJob(),
        )
        // A background question (kind other) and a foreground task are no jobs.
        assertNull(task("q-1", TaskState.Kind.Other).toBackgroundJob())
        assertNull(task("bash-fg", detached = false).toBackgroundJob())
    }

    @Test
    fun `an ended task's outcome maps to the job status, a timeout keeps its flag`() {
        fun ended(outcome: TaskOutcome?) = task("bash-1", running = false, outcome = outcome, endedAt = ENDED).toBackgroundJob()!!

        assertEquals(Job.Status.Completed, ended(TaskOutcome.Completed).status)
        assertEquals(Job.Status.Cancelled, ended(TaskOutcome.Killed).status)
        assertEquals(Job.Status.Failed, ended(TaskOutcome.Failed).status)
        assertEquals(Job.Status.Failed, ended(TaskOutcome.Lost).status)
        assertEquals(Job.Status.Failed, ended(null).status)
        val timedOut = ended(TaskOutcome.TimedOut)
        assertEquals(Job.Status.Failed, timedOut.status)
        assertTrue(timedOut.timedOut)
        assertEquals(ENDED, timedOut.endedAt)
        assertEquals(false, ended(TaskOutcome.Failed).timedOut)
    }

    @Test
    fun `task frames upsert the full state in place and other-kind tasks never show`() {
        store.onUpdated(task("bash-1"))
        store.onUpdated(task("agent-1", TaskState.Kind.Subagent, childAgentId = "agent-0"))
        store.onUpdated(task("q-1", TaskState.Kind.Other, description = "Pick an option"))
        assertEquals(listOf("bash-1", "agent-1"), uiState.jobs.map { it.taskId })

        store.onUpdated(task("bash-1", running = false, outcome = TaskOutcome.Completed, endedAt = ENDED))

        assertEquals(listOf("bash-1", "agent-1"), uiState.jobs.map { it.taskId })
        assertEquals(Job.Status.Completed, uiState.jobs.first().status)
        assertEquals(ENDED, uiState.jobs.first().endedAt)
    }

    @Test
    fun `task list seeds running jobs and terminal ones from earlier frames linger`() {
        store.onListed(listOf(task("bash-1"), task("agent-1", TaskState.Kind.Subagent, childAgentId = "agent-0")))
        assertEquals(2, uiState.jobs.count { it.isRunning })

        // bash-1 ends live; a reconnect's list no longer names it (the server never
        // re-lists an ended task) — the terminal row stays.
        store.onUpdated(task("bash-1", running = false, outcome = TaskOutcome.Killed, endedAt = ENDED))
        store.onListed(listOf(task("agent-1", TaskState.Kind.Subagent, childAgentId = "agent-0"), task("bash-2")))

        assertEquals(listOf("bash-1", "agent-1", "bash-2"), uiState.jobs.map { it.taskId })
        assertEquals(Job.Status.Cancelled, uiState.jobs.first().status)
    }

    @Test
    fun `a running job the next task list no longer names ended unseen and is dropped`() {
        store.onListed(listOf(task("bash-1"), task("bash-2")))

        // bash-2 ended while no connection watched: its outcome is unknown.
        store.onListed(listOf(task("bash-1")))

        assertEquals(listOf("bash-1"), uiState.jobs.map { it.taskId })
    }

    @Test
    fun `a stop with no open session socket changes nothing and reports an effect`() = runBlocking {
        store.onUpdated(task("bash-1"))

        store.cancel("bash-1")

        awaitCondition { effects.isNotEmpty() }
        assertEquals(Job.Status.Running, uiState.jobs.single().status)
        assertEquals(listOf(ChatViewModel.Effect.JobCancelFailed("Server unreachable")), effects.toList())
    }

    private companion object {
        val STARTED: Instant = Instant.parse("2026-11-04T19:30:00.000Z")
        val ENDED: Instant = Instant.parse("2026-11-04T19:30:30.000Z")
    }
}
