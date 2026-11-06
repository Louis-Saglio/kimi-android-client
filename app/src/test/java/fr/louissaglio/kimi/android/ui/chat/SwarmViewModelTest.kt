package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.SocketEvent
import fr.louissaglio.kimi.android.data.chat.TaskOutcome
import fr.louissaglio.kimi.android.data.chat.TaskState
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.navigation.Swarm
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskResponseFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskState
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import fr.louissaglio.kimi.android.testutil.testChatRepository
import fr.louissaglio.kimi.android.testutil.testSubagentStreamStore
import fr.louissaglio.kimi.android.testutil.webSocketUpgradeResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The roster row's member stop (ticket 137, on the `task.stop` control since ticket
 * 179): the tap sends the member's task stop over the session socket through the same
 * repository call the jobs sheet uses and disables the button immediately (latched on
 * success — the wire settles the row), and an error reply re-enables it and raises the
 * snackbar effect. Runs the ViewModel against the real data layer on a MockWebServer
 * whose socket answers every stop with [stopReply]; waits run on the real clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwarmViewModelTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private lateinit var repository: ChatRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var subagentStore: SubagentStreamStore

    /** The server's reply to a `task.stop`, by request id: the member's ended state. */
    @Volatile
    private var stopReply: (String) -> String = { requestId ->
        taskResponseFrame(
            requestId,
            task = taskState(
                "agent-t1", kind = "subagent", detached = false, status = "ended", outcome = "killed",
                endedAt = "2026-11-04T19:30:30.000Z", extra = ""","child_agent_id":"agent-1","swarm_index":0""",
            ),
        )
    }

    private val stopControls = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.target.orEmpty().startsWith("/api/v1/ws")) {
                    webSocketUpgradeResponse(
                        object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val frame = Json.parseToJsonElement(text).jsonObject
                                if (frame["type"]?.jsonPrimitive?.content == "task.stop") {
                                    stopControls += text
                                    webSocket.send(stopReply(frame["request_id"]!!.jsonPrimitive.content))
                                }
                            }
                        },
                    )
                } else {
                    MockResponse(code = 404)
                }
        }
        val dataStore = FakePreferencesDataStore(
            preferencesOf(stringPreferencesKey("server_url") to server.url("/").toString()),
        )
        repository = testChatRepository(dataStore)
        settingsRepository = SettingsRepository(dataStore, appScope)
        subagentStore = testSubagentStreamStore(
            streamCoalesceWindowMillis = 0,
            chatRepository = repository,
            settingsRepository = settingsRepository,
        )
    }

    @After
    fun tearDown() {
        appScope.cancel()
        server.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel() = SwarmViewModel(subagentStore, repository, Swarm("session_1", "tool_swarm"))

    private fun collectEffects(viewModel: SwarmViewModel): CopyOnWriteArrayList<SwarmViewModel.Effect> {
        val effects = CopyOnWriteArrayList<SwarmViewModel.Effect>()
        appScope.launch { viewModel.effects.collect { effects += it } }
        return effects
    }

    /** The session socket collection the chat would hold, feeding the store. */
    private suspend fun connectSocket() {
        val connected = AtomicBoolean(false)
        appScope.launch {
            repository.observeSession(settingsRepository.current(), "session_1").collect { event ->
                if (event is SocketEvent.Connected) connected.set(true)
                (event as? SocketEvent.Event)?.event?.let { subagentStore.apply("session_1", it) }
            }
        }
        awaitCondition { connected.get() }
    }

    /** One swarm member's main-owned spawn `task` frame (ticket 182). */
    private fun memberFrame(
        id: String,
        taskId: String,
        status: TaskState.Status = TaskState.Status.Running,
        outcome: TaskOutcome? = null,
    ) = SessionEvent.TaskUpdated(
        TaskState(
            taskId, TaskState.Kind.Subagent, status, detached = false, description = "doc-verify",
            outcome = outcome, phase = "working".takeIf { status == TaskState.Status.Running },
            childAgentId = id, parentToolCallId = "tool_swarm",
        ),
    )

    /** Spawns one swarm member (running, task id known — the stoppable shape) and
     * waits for the ViewModel's member collection to show it. */
    private suspend fun spawnMember(viewModel: SwarmViewModel, id: String, taskId: String) {
        subagentStore.apply("session_1", memberFrame(id, taskId))
        awaitState(viewModel.uiState) { state -> state.members.any { it.id == id && it.stoppable } }
    }

    @Test
    fun `a member stop sends task stop, latches the button and the killed reply settles the row`() = runBlocking {
        connectSocket()
        val viewModel = newViewModel()
        val effects = collectEffects(viewModel)
        spawnMember(viewModel, "agent-1", taskId = "agent-t1")

        viewModel.onStopMember("agent-1")

        // The disable is synchronous — no server round trip needed.
        assertEquals(setOf("agent-1"), viewModel.uiState.value.stoppingMemberIds)
        awaitCondition { stopControls.isNotEmpty() }
        val control = Json.parseToJsonElement(stopControls.single()).jsonObject
        assertEquals("agent-t1", control["task_id"]?.jsonPrimitive?.content)
        assertEquals("main", control["agent_id"]?.jsonPrimitive?.content)
        // The replied killed state pins the member Cancelled (ticket 135) — the row
        // settles from the wire; success keeps the latch.
        awaitState(viewModel.uiState) { state -> state.members.single().status == SubagentStatus.Cancelled }
        assertEquals(setOf("agent-1"), viewModel.uiState.value.stoppingMemberIds)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `an error reply re-enables the button and reports an effect`() = runBlocking {
        stopReply = { requestId -> taskResponseFrame(requestId, code = 40406, msg = "task not found") }
        connectSocket()
        val viewModel = newViewModel()
        val effects = collectEffects(viewModel)
        spawnMember(viewModel, "agent-1", taskId = "agent-t1")

        viewModel.onStopMember("agent-1")
        assertEquals(setOf("agent-1"), viewModel.uiState.value.stoppingMemberIds)

        awaitCondition { effects.isNotEmpty() }
        assertEquals(emptySet<String>(), viewModel.uiState.value.stoppingMemberIds)
        assertEquals(listOf(SwarmViewModel.Effect.MemberStopFailed("task not found")), effects.toList())
        assertEquals(SubagentStatus.Working, viewModel.uiState.value.members.single().status)
    }

    @Test
    fun `a terminal or unknown member offers no stop`() = runBlocking {
        connectSocket()
        val viewModel = newViewModel()
        val effects = collectEffects(viewModel)
        subagentStore.apply("session_1", memberFrame("agent-2", "agent-t2"))
        subagentStore.apply(
            "session_1",
            memberFrame("agent-2", "agent-t2", TaskState.Status.Ended, TaskOutcome.Completed),
        )
        awaitState(viewModel.uiState) { state -> state.members.any { it.id == "agent-2" && it.status == SubagentStatus.Completed } }

        viewModel.onStopMember("agent-2") // terminal — never stoppable
        viewModel.onStopMember("agent-99") // unknown

        delay(150)
        assertTrue(stopControls.isEmpty())
        assertEquals(emptySet<String>(), viewModel.uiState.value.stoppingMemberIds)
        assertTrue(effects.isEmpty())
    }
}
