package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.data.toUserMessage
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.navigation.Swarm
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import fr.louissaglio.kimi.android.testutil.testChatRepository
import fr.louissaglio.kimi.android.testutil.testSubagentStreamStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The roster row's member stop (ticket 137): the tap posts the member's task cancel
 * through the same repository wrapper the jobs sheet uses and disables the button
 * immediately (latched on success — the wire settles the row), a failure re-enables
 * it and raises the snackbar effect, and the 40904 already-terminal answer is
 * tolerated. Runs the ViewModel against the real data layer on a MockWebServer (the
 * JobDetailViewModelTest pattern); waits run on the real clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwarmViewModelTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private lateinit var repository: ChatRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var subagentStore: SubagentStreamStore

    @Volatile
    private var cancelBody = """{"code":0,"msg":"success","data":{"cancelled":true},"request_id":"r"}"""

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.target.orEmpty().endsWith(":cancel")) {
                    MockResponse(body = cancelBody)
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
        server.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel() = SwarmViewModel(
        subagentStore,
        repository,
        settingsRepository,
        Swarm("session_1", "tool_swarm"),
    )

    private fun collectEffects(viewModel: SwarmViewModel): CopyOnWriteArrayList<SwarmViewModel.Effect> {
        val effects = CopyOnWriteArrayList<SwarmViewModel.Effect>()
        appScope.launch { viewModel.effects.collect { effects += it } }
        return effects
    }

    /** Spawns one swarm member (running, task id known — the stoppable shape) and
     * waits for the ViewModel's member collection to show it. */
    private suspend fun spawnMember(viewModel: SwarmViewModel, id: String, taskId: String?) {
        subagentStore.apply(
            "session_1",
            SessionEvent.SubagentSpawned(
                subagentId = id,
                name = "explore",
                parentToolCallId = "tool_swarm",
                description = "doc-verify",
                runInBackground = false,
                taskId = taskId,
            ),
        )
        subagentStore.apply("session_1", SessionEvent.SubagentStarted(id))
        awaitState(viewModel.uiState) { state -> state.members.any { it.id == id && it.stoppable == (taskId != null) } }
    }

    @Test
    fun `a member stop posts the task cancel and latches the button disabled`() = runBlocking {
        val viewModel = newViewModel()
        val effects = collectEffects(viewModel)
        spawnMember(viewModel, "agent-1", taskId = "agent-t1")

        viewModel.onStopMember("agent-1")

        // The disable is synchronous — no server round trip needed.
        assertEquals(setOf("agent-1"), viewModel.uiState.value.stoppingMemberIds)
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertEquals("POST", request?.method)
        assertEquals("/api/v1/sessions/session_1/tasks/agent-t1:cancel", request?.target)
        // Success keeps the latch: no optimistic flip, the wire (task.terminated or
        // the settled call's result) turns the row terminal and hides the button.
        delay(150)
        assertEquals(setOf("agent-1"), viewModel.uiState.value.stoppingMemberIds)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `a failed member stop re-enables the button and reports an effect`() = runBlocking {
        cancelBody = """{"code":40001,"msg":"invalid request","request_id":"r"}"""
        val viewModel = newViewModel()
        val effects = collectEffects(viewModel)
        spawnMember(viewModel, "agent-1", taskId = "agent-t1")

        viewModel.onStopMember("agent-1")
        assertEquals(setOf("agent-1"), viewModel.uiState.value.stoppingMemberIds)

        awaitCondition { effects.isNotEmpty() }
        assertEquals(emptySet<String>(), viewModel.uiState.value.stoppingMemberIds)
        assertEquals(
            listOf(SwarmViewModel.Effect.MemberStopFailed(DataError.Api(40001, "invalid request").toUserMessage())),
            effects.toList(),
        )
    }

    @Test
    fun `an already-terminal answer keeps the latch and raises no effect`() = runBlocking {
        // docs/server-api.md, REST /tasks: a terminal task answers envelope 40904 —
        // already-terminal success (the jobs idiom), the row settles from the wire.
        cancelBody =
            """{"code":40904,"msg":"task is not running","data":{"cancelled":false,""" +
                """"details":{"current_status":"completed"}},"request_id":"r"}"""
        val viewModel = newViewModel()
        val effects = collectEffects(viewModel)
        spawnMember(viewModel, "agent-1", taskId = "agent-t1")

        viewModel.onStopMember("agent-1")

        server.takeRequest(5, TimeUnit.SECONDS)
        delay(150)
        assertEquals(setOf("agent-1"), viewModel.uiState.value.stoppingMemberIds)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `a terminal or task-id-less member offers no stop`() = runBlocking {
        val viewModel = newViewModel()
        val effects = collectEffects(viewModel)
        spawnMember(viewModel, "agent-1", taskId = null)
        subagentStore.apply(
            "session_1",
            SessionEvent.SubagentSpawned(
                subagentId = "agent-2",
                name = "explore",
                parentToolCallId = "tool_swarm",
                description = "doc-verify",
                runInBackground = false,
                taskId = "agent-t2",
            ),
        )
        subagentStore.apply("session_1", SessionEvent.SubagentCompleted("agent-2", "done"))
        awaitState(viewModel.uiState) { state -> state.members.any { it.id == "agent-2" } }

        viewModel.onStopMember("agent-1") // no task id — no :cancel target
        viewModel.onStopMember("agent-2") // terminal — never stoppable
        viewModel.onStopMember("agent-99") // unknown

        delay(150)
        assertEquals(0, server.requestCount)
        assertEquals(emptySet<String>(), viewModel.uiState.value.stoppingMemberIds)
        assertTrue(effects.isEmpty())
    }
}
