package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ViewModel tests run against the real [ChatRepository] on a [MockWebServer], so the WS
 * frame parsing and the decision POST are covered end-to-end. The socket is real I/O, so
 * waits use real-time timeouts (same pattern as ChatRepositoryTest), not runTest's clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var viewModel: ChatViewModel

    @Volatile
    private var approvalsSucceed = true

    @Volatile
    private var abortsSucceed = true

    @Volatile
    private var serverSocket: WebSocket? = null

    private class FakePreferencesDataStore(prefs: Preferences) : DataStore<Preferences> {
        private val state = MutableStateFlow(prefs)
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Before
    fun setUp() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/v1/sessions/session_1/messages") -> MockResponse().setBody(
                        """{"code":0,"msg":"success","data":{"items":[],"has_more":false},"request_id":"r"}""",
                    )
                    path == "/api/v1/sessions/session_1/status" -> MockResponse().setBody(
                        """{"code":0,"msg":"success","data":{"busy":false},"request_id":"r"}""",
                    )
                    path == "/api/v1/sessions/session_1:abort" ->
                        if (abortsSucceed) {
                            MockResponse().setBody(
                                """{"code":0,"msg":"success","data":{"aborted":true},"request_id":"r"}""",
                            )
                        } else {
                            MockResponse().setBody(
                                """{"code":40001,"msg":"no running prompt","data":null,"request_id":"r"}""",
                            )
                        }
                    path.startsWith("/api/v1/sessions/session_1/approvals/") ->
                        if (approvalsSucceed) {
                            MockResponse().setBody(
                                """{"code":0,"msg":"success","data":{"resolved":true,"resolved_at":"2026-07-19T00:00:01.000Z"},"request_id":"r"}""",
                            )
                        } else {
                            MockResponse().setBody(
                                """{"code":40401,"msg":"approval does not exist","data":null,"request_id":"r"}""",
                            )
                        }
                    path.startsWith("/api/v1/ws") -> MockResponse().withWebSocketUpgrade(
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                serverSocket = webSocket
                            }
                        },
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val settingsRepository = SettingsRepository(
            FakePreferencesDataStore(
                preferencesOf(stringPreferencesKey("server_url") to server.url("/").toString()),
            ),
            appScope,
        )
        viewModel = ChatViewModel(
            ChatRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }),
            settingsRepository,
            SavedStateHandle(mapOf("sessionId" to "session_1")),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        appScope.cancel()
        server.shutdown()
    }

    @Test
    fun `approval request event shows card and resolved event clears it`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()

        socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: echo hi", command = "echo hi"))
        awaitState { it.pendingApprovals.isNotEmpty() }
        assertEquals(
            listOf(ChatViewModel.PendingApproval("ap_1", "Bash", "echo hi")),
            viewModel.uiState.value.pendingApprovals,
        )

        // Resolved from another client: the card clears without local action.
        socket.send(approvalResolvedFrame("ap_1"))
        awaitState { it.pendingApprovals.isEmpty() }
        assertEquals(emptyList<ChatViewModel.PendingApproval>(), viewModel.uiState.value.pendingApprovals)
    }

    @Test
    fun `multiple requests queue in arrival order`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()

        socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: echo one", command = "echo one"))
        socket.send(approvalRequestedFrame(id = "ap_2", tool = "Write", action = "Writing /tmp/a.txt", command = ""))
        awaitState { it.pendingApprovals.size == 2 }

        val queue = viewModel.uiState.value.pendingApprovals
        assertEquals(listOf("ap_1", "ap_2"), queue.map { it.approvalId })
        // Without a command display, the human-readable action is the summary.
        assertEquals("Writing /tmp/a.txt", queue[1].summary)

        socket.send(approvalResolvedFrame("ap_1"))
        awaitState { it.pendingApprovals.size == 1 }
        assertEquals("ap_2", viewModel.uiState.value.pendingApprovals.single().approvalId)
    }

    @Test
    fun `allow posts approved decision and card clears on resolved`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: echo hi", command = "echo hi"))
        awaitState { it.pendingApprovals.isNotEmpty() }

        viewModel.onAllow()

        val request = awaitRequest("/approvals/")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/approvals/ap_1", request.path)
        assertEquals("""{"decision":"approved"}""", request.body.readUtf8())

        // The ack alone must not clear the card — only the resolved event does.
        delay(300)
        assertEquals(1, viewModel.uiState.value.pendingApprovals.size)
        socket.send(approvalResolvedFrame("ap_1"))
        awaitState { it.pendingApprovals.isEmpty() }
    }

    @Test
    fun `deny posts rejected decision`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: rm -rf /", command = "rm -rf /"))
        awaitState { it.pendingApprovals.isNotEmpty() }

        viewModel.onDeny()

        val request = awaitRequest("/approvals/")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/approvals/ap_1", request.path)
        assertEquals("""{"decision":"rejected"}""", request.body.readUtf8())
    }

    @Test
    fun `failed decision post keeps card and emits ApprovalFailed`() = runBlocking {
        approvalsSucceed = false
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: echo hi", command = "echo hi"))
        awaitState { it.pendingApprovals.isNotEmpty() }

        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onAllow()
            awaitRequest("/approvals/")
            withTimeout(5_000) { while (effects.isEmpty()) delay(10) }

            assertEquals(
                ChatViewModel.Effect.ApprovalFailed("approval does not exist"),
                effects.single(),
            )
            // No resolved event will come: the card must stay up.
            assertEquals(1, viewModel.uiState.value.pendingApprovals.size)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `stop posts abort and turn ended clears the working state`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(turnStartedFrame())
        awaitState { it.isWorking }

        viewModel.onStop()

        val request = awaitRequest(":abort")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1:abort", request.path)
        assertEquals("{}", request.body.readUtf8())

        // The ack alone must not clear the indicator — the server's turn.ended does.
        assertTrue(viewModel.uiState.value.isWorking)
        socket.send(turnEndedFrame(reason = "cancelled"))
        awaitState { !it.isWorking }
    }

    @Test
    fun `stop without a running turn sends no request`() = runBlocking {
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState { !it.isLoading }
        // Drain the history/status/WS requests recorded so far.
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onStop()

        delay(200)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `failed abort emits AbortFailed and keeps the working state`() = runBlocking {
        abortsSucceed = false
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(turnStartedFrame())
        awaitState { it.isWorking }

        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onStop()
            awaitRequest(":abort")
            withTimeout(5_000) { while (effects.isEmpty()) delay(10) }

            assertEquals(
                ChatViewModel.Effect.AbortFailed("no running prompt"),
                effects.single(),
            )
            // The turn is still running: the indicator and the Stop action stay up.
            assertTrue(viewModel.uiState.value.isWorking)
        } finally {
            effectJob.cancel()
        }
    }

    private suspend fun awaitServerSocket(): WebSocket = withTimeout(5_000) {
        while (serverSocket == null) delay(10)
        serverSocket!!
    }

    private suspend fun awaitState(predicate: (ChatViewModel.UiState) -> Boolean) =
        withTimeout(5_000) { while (!predicate(viewModel.uiState.value)) delay(10) }

    /** Drains recorded requests until the approvals POST arrives (history/status/WS came first). */
    private fun awaitRequest(pathSubstring: String): RecordedRequest {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val request = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: continue
            if (request.path.orEmpty().contains(pathSubstring)) return request
        }
        error("Timed out waiting for request to $pathSubstring")
    }

    private fun turnStartedFrame(): String =
        """{"type":"turn.started","seq":20,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-19T00:00:00.000Z","payload":{"type":"turn.started","turnId":1,""" +
            """"origin":{"kind":"user"},"agentId":"main","sessionId":"session_1"}}"""

    private fun turnEndedFrame(reason: String): String =
        """{"type":"turn.ended","seq":21,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-19T00:00:01.000Z","payload":{"type":"turn.ended","turnId":1,""" +
            """"reason":"$reason","durationMs":1000,"agentId":"main","sessionId":"session_1"}}"""

    private fun approvalRequestedFrame(id: String, tool: String, action: String, command: String): String =
        """{"type":"event.approval.requested","seq":7,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-19T00:00:00.000Z","payload":{"type":"event.approval.requested",""" +
            """"sessionId":"session_1","agentId":"main","approval_id":"$id","session_id":"session_1",""" +
            """"turn_id":0,"tool_call_id":"tool_1","tool_name":"$tool","action":"$action",""" +
            """"tool_input_display":{"kind":"command","command":"$command","cwd":"/tmp","language":"bash"},""" +
            """"created_at":"2026-07-19T00:00:00.000Z","expires_at":"2026-07-19T00:01:00.000Z"}}"""

    private fun approvalResolvedFrame(id: String): String =
        """{"type":"event.approval.resolved","seq":8,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-19T00:00:00.500Z","payload":{"type":"event.approval.resolved",""" +
            """"approval_id":"$id","decision":"approved","resolved_at":"2026-07-19T00:00:00.500Z"}}"""
}
