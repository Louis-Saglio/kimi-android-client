package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.chat.BackgroundTask
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.TaskInfo
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.testChatRepository
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The jobs-widget collaborator (ticket 092): REST seed, live task.* upserts with the
 * core→wire vocabulary mapping, and cancellation (optimistic; a 40904 still ends
 * terminal). Drives the real data layer against a MockWebServer, like
 * ChatRepositoryTest — waits run on the real clock.
 */
class JobsStoreTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var store: JobsStore

    @Volatile
    private var uiState = ChatViewModel.UiState(sessionId = "session_1")
    private val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()

    @Before
    fun setUp() {
        server.start()
        uiState = ChatViewModel.UiState(sessionId = "session_1")
        val dataStore = FakePreferencesDataStore(
            preferencesOf(stringPreferencesKey("server_url") to server.url("/").toString()),
        )
        val chatRepository: ChatRepository = testChatRepository(dataStore)
        store = JobsStore(
            scope = { appScope },
            chatRepository = chatRepository,
            settingsRepository = SettingsRepository(dataStore, appScope),
            sessionId = "session_1",
            state = { uiState },
            updateState = { transform -> uiState = transform(uiState) },
            sendEffect = { effect -> effects += effect },
        )
    }

    @After
    fun tearDown() {
        appScope.cancel()
        server.close()
    }

    private fun taskInfo(
        taskId: String,
        status: TaskInfo.Status,
        kind: TaskInfo.Kind = TaskInfo.Kind.Agent,
        agentId: String? = "agent-13",
        description: String = "doc-verify",
        startedAt: Instant = Instant.ofEpochMilli(1785210101939),
        endedAt: Instant? = null,
    ) = TaskInfo(taskId, description, status, startedAt, endedAt, kind = kind, agentId = agentId)

    @Test
    fun `REST seed lists running and terminal tasks, tool-kind entries never appear`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"items":[""" +
                    """{"id":"agent-7noib8bb","session_id":"session_1","kind":"subagent",""" +
                    """"description":"doc-verify-bg","status":"completed",""" +
                    """"run_in_background":true,""" +
                    """"created_at":"2026-07-28T03:40:08.039Z","started_at":"2026-07-28T03:40:08.039Z",""" +
                    """"completed_at":"2026-07-28T03:40:22.372Z"},""" +
                    """{"id":"proc-9xk2","session_id":"session_1","kind":"bash",""" +
                    """"description":"sleep 1000","status":"running",""" +
                    """"run_in_background":true,""" +
                    """"created_at":"2026-07-28T03:41:00.000Z","started_at":"2026-07-28T03:41:00.000Z"},""" +
                    """{"id":"tool_q1","session_id":"session_1","kind":"tool",""" +
                    """"description":"Pick an option","status":"running",""" +
                    """"run_in_background":true,""" +
                    """"created_at":"2026-07-28T03:42:00.000Z","started_at":"2026-07-28T03:42:00.000Z"}""" +
                    """]},"request_id":"r"}""",
            ),
        )

        store.seed()
        awaitCondition { uiState.jobs.isNotEmpty() }

        // Terminal jobs linger (the sheet shows them); kind "tool" was filtered at the
        // mapping boundary. No agent_id on the fixtures — nothing is linked yet.
        assertEquals(
            listOf(
                Job(
                    taskId = "agent-7noib8bb",
                    kind = BackgroundTask.Kind.Subagent,
                    description = "doc-verify-bg",
                    status = BackgroundTask.Status.Completed,
                    startedAt = Instant.parse("2026-07-28T03:40:08.039Z"),
                    endedAt = Instant.parse("2026-07-28T03:40:22.372Z"),
                ),
                Job(
                    taskId = "proc-9xk2",
                    kind = BackgroundTask.Kind.Bash,
                    description = "sleep 1000",
                    status = BackgroundTask.Status.Running,
                    startedAt = Instant.parse("2026-07-28T03:41:00.000Z"),
                ),
            ),
            uiState.jobs,
        )
    }

    @Test
    fun `a live started upserts, terminated maps the core status to the wire one`() = runBlocking {
        store.onStarted(taskInfo(taskId = "agent-7noib8bb", status = TaskInfo.Status.Running))
        store.onStarted(
            taskInfo(
                taskId = "proc-9xk2",
                status = TaskInfo.Status.Running,
                kind = TaskInfo.Kind.Process,
                agentId = null,
                description = "sleep 1000",
            ),
        )
        assertEquals(2, uiState.jobs.count { it.isRunning })

        // killed → cancelled (docs/server-api.md core→wire mapping), with endedAt.
        store.onTerminated(
            taskInfo(
                taskId = "agent-7noib8bb",
                status = TaskInfo.Status.Killed,
                endedAt = Instant.ofEpochMilli(1785210118804),
            ),
        )
        val terminated = uiState.jobs.first { it.taskId == "agent-7noib8bb" }
        assertEquals(BackgroundTask.Status.Cancelled, terminated.status)
        assertEquals(Instant.ofEpochMilli(1785210118804), terminated.endedAt)
        assertEquals(false, terminated.timedOut)
        assertEquals(1, uiState.jobs.count { it.isRunning })

        // timed_out → failed, and the live event retains the timeout cause
        // (ticket 097 — the REST collapse to "failed" can't).
        store.onTerminated(
            taskInfo(
                taskId = "proc-9xk2",
                status = TaskInfo.Status.TimedOut,
                kind = TaskInfo.Kind.Process,
                agentId = null,
                description = "sleep 1000",
                endedAt = Instant.ofEpochMilli(1785210200000),
            ),
        )
        val timedOut = uiState.jobs.first { it.taskId == "proc-9xk2" }
        assertEquals(BackgroundTask.Status.Failed, timedOut.status)
        assertEquals(true, timedOut.timedOut)
        assertEquals(0, uiState.jobs.count { it.isRunning })
        // Both finished jobs linger in the list.
        assertEquals(2, uiState.jobs.size)
    }

    @Test
    fun `a REST seed after a live timed_out keeps the timed-out flag`() = runBlocking {
        // Ticket 097: REST collapses timed_out to failed — within the session the
        // live-recorded flag survives reseeds; a fresh seed (re-entered session) has
        // no live carrier and renders plain "failed" (documented limitation).
        store.onStarted(
            taskInfo(taskId = "bash-1", status = TaskInfo.Status.Running, kind = TaskInfo.Kind.Process, agentId = null),
        )
        store.onTerminated(
            taskInfo(
                taskId = "bash-1",
                status = TaskInfo.Status.TimedOut,
                kind = TaskInfo.Kind.Process,
                agentId = null,
                endedAt = Instant.ofEpochMilli(1785210200000),
            ),
        )
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"items":[""" +
                    """{"id":"bash-1","session_id":"session_1","kind":"bash","description":"doc-verify",""" +
                    """"status":"failed","run_in_background":true,""" +
                    """"created_at":"2026-07-28T03:41:00.000Z",""" +
                    """"started_at":"2026-07-28T03:41:00.000Z","completed_at":"2026-07-28T03:41:05.000Z"}""" +
                    """]},"request_id":"r"}""",
            ),
        )

        store.seed()
        awaitCondition { uiState.jobs.single().endedAt == Instant.parse("2026-07-28T03:41:05.000Z") }

        val reseeded = uiState.jobs.single()
        assertEquals(BackgroundTask.Status.Failed, reseeded.status)
        assertEquals(true, reseeded.timedOut)
    }

    @Test
    fun `a live event heals the sub-agent link a REST-seeded job lacks`() = runBlocking {
        // A terminal REST entry (a background sub-agent that finished while the
        // session was away — a RUNNING run_in_background:false one would be filtered
        // as a foreground ghost, ticket 162): the fixture carries no agent_id.
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"items":[""" +
                    """{"id":"agent-7noib8bb","session_id":"session_1","kind":"subagent",""" +
                    """"description":"doc-verify-bg","status":"completed",""" +
                    """"run_in_background":true,""" +
                    """"created_at":"2026-07-28T03:40:08.039Z","started_at":"2026-07-28T03:40:08.039Z",""" +
                    """"completed_at":"2026-07-28T03:40:22.372Z"}""" +
                    """]},"request_id":"r"}""",
            ),
        )
        store.seed()
        awaitCondition { uiState.jobs.isNotEmpty() }
        assertEquals(null, uiState.jobs.single().agentId)

        // The replayed task.terminated carries info.agentId — the page link
        // (ticket 092).
        store.onTerminated(
            taskInfo(
                taskId = "agent-7noib8bb",
                status = TaskInfo.Status.Completed,
                agentId = "agent-13",
                endedAt = Instant.ofEpochMilli(1785210101939),
            ),
        )

        assertEquals("agent-13", uiState.jobs.single().agentId)
    }

    @Test
    fun `REST seed lists only background-flagged tasks - a foreground sub-agent stays out`() = runBlocking {
        // Since 0.39.0 every REST task carries run_in_background (docs/server-api.md):
        // a RUNNING foreground sub-agent lists with false and is never a job row.
        // The flag replaces ticket 101's known-background set — no live task.* frame
        // or result marker is needed to classify the seed.
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"items":[""" +
                    """{"id":"agent-fg1","session_id":"session_1","kind":"subagent",""" +
                    """"description":"explore the repo","status":"running",""" +
                    """"run_in_background":false,""" +
                    """"created_at":"2026-08-06T10:00:00.000Z","started_at":"2026-08-06T10:00:00.000Z"},""" +
                    """{"id":"bash-1","session_id":"session_1","kind":"bash",""" +
                    """"description":"sleep 1000","status":"running",""" +
                    """"run_in_background":true,""" +
                    """"created_at":"2026-08-06T10:01:00.000Z","started_at":"2026-08-06T10:01:00.000Z"}""" +
                    """]},"request_id":"r"}""",
            ),
        )

        store.seed()
        awaitCondition { uiState.jobs.isNotEmpty() }

        assertEquals(listOf("bash-1"), uiState.jobs.map { it.taskId })

        // A background sub-agent whose task.started predates the subscription is kept
        // by the flag alone — the cold-start case the WS/marker heuristic could only
        // cover partially.
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"items":[""" +
                    """{"id":"agent-fg1","session_id":"session_1","kind":"subagent",""" +
                    """"description":"explore the repo","status":"running",""" +
                    """"run_in_background":false,""" +
                    """"created_at":"2026-08-06T10:00:00.000Z","started_at":"2026-08-06T10:00:00.000Z"},""" +
                    """{"id":"agent-bg1","session_id":"session_1","kind":"subagent",""" +
                    """"description":"doc-verify-bg","status":"running",""" +
                    """"run_in_background":true,""" +
                    """"created_at":"2026-08-06T10:02:00.000Z","started_at":"2026-08-06T10:02:00.000Z"},""" +
                    """{"id":"bash-1","session_id":"session_1","kind":"bash",""" +
                    """"description":"sleep 1000","status":"running",""" +
                    """"run_in_background":true,""" +
                    """"created_at":"2026-08-06T10:01:00.000Z","started_at":"2026-08-06T10:01:00.000Z"}""" +
                    """]},"request_id":"r"}""",
            ),
        )

        store.seed()
        awaitCondition { uiState.jobs.any { it.taskId == "agent-bg1" } }

        assertEquals(setOf("bash-1", "agent-bg1"), uiState.jobs.map { it.taskId }.toSet())
    }

    @Test
    fun `cancel is optimistic and a 40904 response still ends in a terminal state`() = runBlocking {
        store.onStarted(taskInfo(taskId = "agent-7noib8bb", status = TaskInfo.Status.Running))
        // A terminal task answers envelope 40904 (docs/server-api.md, REST /tasks).
        server.enqueue(
            MockResponse(body =
                """{"code":40904,"msg":"task is not running","data":{"cancelled":false,""" +
                    """"details":{"current_status":"completed"}},"request_id":"r"}""",
            ),
        )

        store.cancel("agent-7noib8bb")

        // The optimistic flip is synchronous — no server round trip needed.
        assertEquals(BackgroundTask.Status.Cancelled, uiState.jobs.single().status)
        // Already-terminal is cancel's desired end state (web parity): the flip
        // stands, no effect is raised. The settle lets the (no-op) 40904 response
        // finish processing before the assertions.
        server.takeRequest(5, TimeUnit.SECONDS)
        delay(150)
        assertEquals(BackgroundTask.Status.Cancelled, uiState.jobs.single().status)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `a failed cancel reverts to running and reports an effect`() = runBlocking {
        store.onStarted(taskInfo(taskId = "agent-7noib8bb", status = TaskInfo.Status.Running))
        server.enqueue(MockResponse(code = 500))

        store.cancel("agent-7noib8bb")
        assertEquals(BackgroundTask.Status.Cancelled, uiState.jobs.single().status)

        awaitCondition { effects.isNotEmpty() }
        assertEquals(BackgroundTask.Status.Running, uiState.jobs.single().status)
        assertEquals(
            listOf(ChatViewModel.Effect.JobCancelFailed("Unexpected server response (HTTP 500)")),
            effects.toList(),
        )
    }
}
