package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.PendingQuestion
import fr.louissaglio.kimi.android.data.chat.ServerModel
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ViewModel tests run against the real [ChatRepository] on a [MockWebServer], so the WS
 * frame parsing and the decision POST are covered end-to-end. The socket is real I/O, so
 * waits use real-time timeouts (same pattern as ChatRepositoryTest), not runTest's clock.
 * Determinism comes from the dispatcher, not from timing margins: the WS upgrade is
 * gated behind the six startup GETs ([servedStartupGets]), so a poll response can never
 * land after a socket event the test already sent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var viewModel: ChatViewModel
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var dataStore: FakePreferencesDataStore

    @Volatile
    private var approvalsSucceed = true

    @Volatile
    private var abortsSucceed = true

    @Volatile
    private var answersSucceed = true

    @Volatile
    private var uploadsSucceed = true

    @Volatile
    private var questionsBody =
        """{"code":0,"msg":"success","data":{"items":[]},"request_id":"r"}"""

    @Volatile
    private var messagesBody =
        """{"code":0,"msg":"success","data":{"items":[],"has_more":false},"request_id":"r"}"""

    @Volatile
    private var sessionBody =
        """{"code":0,"msg":"success","data":{"id":"session_1","title":"Fix the bug",""" +
            """"updated_at":"2026-07-20T00:00:00.000Z","busy":false,""" +
            """"metadata":{"cwd":"/home/ubuntu/kimi-android-client"}},"request_id":"r"}"""

    /** The live 0.29.0 model list (docs/server-api.md): two 262k models and K3 (1M). */
    @Volatile
    private var modelsBody =
        """{"code":0,"msg":"success","data":{"items":[""" +
            """{"provider":"managed:kimi-code","model":"kimi-code/kimi-for-coding",""" +
            """"display_name":"K2.7 Coding","max_context_size":262144},""" +
            """{"provider":"managed:kimi-code","model":"kimi-code/kimi-for-coding-highspeed",""" +
            """"display_name":"K2.7 Coding Highspeed","max_context_size":262144},""" +
            """{"provider":"managed:kimi-code","model":"kimi-code/k3","display_name":"K3",""" +
            """"max_context_size":1048576,"support_efforts":["low","high","max"],"default_effort":"high"}""" +
            """]},"request_id":"r"}"""

    @Volatile
    private var configBody =
        """{"code":0,"msg":"success","data":{"default_model":"kimi-code/k3"},"request_id":"r"}"""

    @Volatile
    private var serverSocket: WebSocket? = null

    /** The WS upgrade waits behind the six startup GETs (messages/questions/session/
     * status/models/config): when [awaitServerSocket] returns, every startup poll was
     * served, so a poll response can never reorder after a socket event — the flake
     * this removes. */
    private val servedStartupGets = CountDownLatch(6)

    /** When set, the /status response is held back server-side until the test releases
     * it, so a test can make a socket event win over the in-flight seed on purpose. */
    @Volatile
    private var statusResponseGate: CountDownLatch? = null

    /** Same as [statusResponseGate], for the /questions poll. */
    @Volatile
    private var questionsResponseGate: CountDownLatch? = null

    /** Same as [statusResponseGate], for the /api/v1/files upload (ticket 033): parks
     * the response so a test can observe the send while the upload is in flight. */
    @Volatile
    private var filesResponseGate: CountDownLatch? = null

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
                    path == "/api/v1/sessions/session_1" && request.method == "GET" ->
                        MockResponse().setBody(sessionBody).also { servedStartupGets.countDown() }
                    path.startsWith("/api/v1/sessions/session_1/messages") ->
                        MockResponse().setBody(messagesBody).also { servedStartupGets.countDown() }
                    path == "/api/v1/sessions/session_1/status" -> {
                        // Counted at arrival: the response itself may be parked behind
                        // the test's gate, and the socket must not wait for that.
                        servedStartupGets.countDown()
                        statusResponseGate?.await(5, TimeUnit.SECONDS)
                        MockResponse().setBody(
                            """{"code":0,"msg":"success","data":{"busy":false},"request_id":"r"}""",
                        )
                    }
                    path == "/api/v1/sessions/session_1/prompts" -> MockResponse().setBody(
                        """{"code":0,"msg":"success","data":{"prompt_id":"msg_1","user_message_id":"msg_1",""" +
                            """"status":"running","created_at":"2026-07-20T00:00:00.000Z"},"request_id":"r"}""",
                    )
                    path == "/api/v1/sessions/session_1/prompts:steer" -> MockResponse().setBody(
                        """{"code":0,"msg":"success","data":{"steered":true,"prompt_ids":["msg_1"]},"request_id":"r"}""",
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
                    path.startsWith("/api/v1/sessions/session_1/questions") -> when {
                        path.endsWith(":dismiss") ->
                            if (answersSucceed) {
                                MockResponse().setBody(
                                    """{"code":40909,"msg":"question tool_q1 dismissed",""" +
                                        """"data":{"dismissed":true,"dismissed_at":"2026-07-20T06:17:24.711Z"},"request_id":"r"}""",
                                )
                            } else {
                                MockResponse().setBody(
                                    """{"code":40405,"msg":"question tool_q1 not found","data":null,"request_id":"r"}""",
                                )
                            }
                        request.method == "POST" ->
                            if (answersSucceed) {
                                MockResponse().setBody(
                                    """{"code":0,"msg":"success","data":{"resolved":true,""" +
                                        """"resolved_at":"2026-07-20T06:08:12.548Z"},"request_id":"r"}""",
                                )
                            } else {
                                MockResponse().setBody(
                                    """{"code":40405,"msg":"question tool_q1 not found","data":null,"request_id":"r"}""",
                                )
                            }
                        else -> {
                            // Counted at arrival: the response itself may be parked
                            // behind the test's gate, like the /status one.
                            servedStartupGets.countDown()
                            questionsResponseGate?.await(5, TimeUnit.SECONDS)
                            MockResponse().setBody(questionsBody)
                        }
                    }
                    path == "/api/v1/files" -> {
                        filesResponseGate?.await(5, TimeUnit.SECONDS)
                        if (uploadsSucceed) {
                            MockResponse().setBody(
                                """{"code":0,"msg":"success","data":{"id":"f_1","name":"a.txt",""" +
                                    """"media_type":"text/plain","size":5,""" +
                                    """"created_at":"2026-07-24T00:00:00.000Z"},"request_id":"r"}""",
                            )
                        } else {
                            MockResponse().setBody(
                                """{"code":41301,"msg":"file exceeds the 50 MiB limit","data":null,"request_id":"r"}""",
                            )
                        }
                    }
                    path == "/api/v1/models" ->
                        MockResponse().setBody(modelsBody).also { servedStartupGets.countDown() }
                    path == "/api/v1/config" ->
                        MockResponse().setBody(configBody).also { servedStartupGets.countDown() }
                    path.startsWith("/api/v1/ws") -> {
                        servedStartupGets.await(5, TimeUnit.SECONDS)
                        MockResponse().withWebSocketUpgrade(
                            object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    serverSocket = webSocket
                                }
                            },
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        dataStore = FakePreferencesDataStore(
            preferencesOf(stringPreferencesKey("server_url") to server.url("/").toString()),
        )
        settingsRepository = SettingsRepository(dataStore, appScope)
        viewModel = ChatViewModel(
            ChatRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }, dataStore),
            SessionsRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }),
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

    @Test
    fun `status seed in flight does not clear a turn that just started`() = runBlocking {
        val gate = CountDownLatch(1)
        statusResponseGate = gate
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()

        // The /status response is parked server-side: turn.started lands first.
        socket.send(turnStartedFrame())
        awaitState { it.isWorking }

        // The stale busy:false snapshot arrives after the event and must be dropped.
        gate.countDown()
        delay(200)
        assertTrue(viewModel.uiState.value.isWorking)
    }

    @Test
    fun `context spliced shows the sent prompt bubble without a history refetch`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState { !it.isLoading }
        // Drain the history/status/WS requests recorded so far.
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        socket.send(contextSplicedFrame(id = "msg_1", text = "hello from the phone"))

        awaitState { it.items.isNotEmpty() }
        assertEquals(
            listOf(ChatItem.UserMessage("msg_1", "hello from the phone")),
            viewModel.uiState.value.items,
        )
        // The echo came from the socket frame alone: no HTTP refetch fired.
        delay(200)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `no duplicate user bubble after the turn ended refetch`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState { !it.isLoading }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
        socket.send(contextSplicedFrame(id = "msg_1", text = "hello from the phone"))
        awaitState { it.items.size == 1 }

        // The server committed the message; the turn.ended refetch returns it in history.
        messagesBody = """
            {"code":0,"msg":"success","data":{"items":[
              {"id":"msg_1","session_id":"session_1","role":"user",
               "content":[{"type":"text","text":"hello from the phone"}],
               "created_at":"2026-07-19T00:00:00.500Z"}
            ],"has_more":false},"request_id":"r"}
        """.trimIndent()
        socket.send(turnEndedFrame(reason = "completed"))

        // The refetch replaces committed rows wholesale: still exactly one bubble.
        awaitRequest("/messages")
        delay(300)
        assertEquals(
            listOf(ChatItem.UserMessage("msg_1", "hello from the phone")),
            viewModel.uiState.value.items,
        )
    }

    @Test
    fun `sending while busy steers the queued prompt into the running turn`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(turnStartedFrame())
        awaitState { it.isWorking }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onInputChange("Stop counting")
        viewModel.onSend()

        // docs/server-api.md, Steer: queue the prompt as usual, then steer its id in.
        // The config GET served at screen start makes the server default known (017).
        val prompt = awaitRequest("/prompts")
        assertEquals("POST", prompt.method)
        assertEquals("/api/v1/sessions/session_1/prompts", prompt.path)
        assertEquals("""{"content":[{"type":"text","text":"Stop counting"}],"model":"kimi-code/k3"}""", prompt.body.readUtf8())
        val steer = awaitRequest("prompts:steer")
        assertEquals("POST", steer.method)
        assertEquals("/api/v1/sessions/session_1/prompts:steer", steer.path)
        assertEquals("""{"prompt_ids":["msg_1"]}""", steer.body.readUtf8())

        awaitState { it.inputText.isEmpty() && !it.isSending }
        // The turn keeps running: the working state and Stop action stay up.
        assertTrue(viewModel.uiState.value.isWorking)
    }

    @Test
    fun `sending while idle posts a plain prompt, not a steer`() = runBlocking {
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState { !it.isLoading }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onInputChange("hello")
        viewModel.onSend()

        val request = awaitRequest("/prompts")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/prompts", request.path)
        assertEquals("""{"content":[{"type":"text","text":"hello"}],"model":"kimi-code/k3"}""", request.body.readUtf8())
        // A plain prompt marks the session busy; no steer call follows.
        awaitState { it.isWorking }
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `steered message echo appears from the socket while the turn continues`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(turnStartedFrame())
        awaitState { it.isWorking }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        socket.send(steerEchoSplicedFrame(text = "Stop counting"))

        awaitState { it.items.isNotEmpty() }
        val items = viewModel.uiState.value.items
        assertEquals(1, items.size)
        assertEquals("Stop counting", (items.single() as ChatItem.UserMessage).text)
        // The turn is still running and the echo needed no history refetch.
        assertTrue(viewModel.uiState.value.isWorking)
        delay(200)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `question request event shows the card and answered event clears it`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()

        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState { it.pendingQuestions.isNotEmpty() }
        assertEquals(
            listOf(
                PendingQuestion(
                    questionId = "tool_q1",
                    items = listOf(
                        PendingQuestion.Item(
                            id = "q_0",
                            header = "Color",
                            text = "Red or blue?",
                            options = listOf(
                                PendingQuestion.Option("opt_0_0", "red", "warm color"),
                                PendingQuestion.Option("opt_0_1", "blue", "cool color"),
                            ),
                            allowOther = true,
                        ),
                    ),
                ),
            ),
            viewModel.uiState.value.pendingQuestions,
        )

        // Answered from another client: the card clears without local action.
        socket.send(questionAnsweredFrame("tool_q1"))
        awaitState { it.pendingQuestions.isEmpty() }
    }

    @Test
    fun `questions poll in flight does not clear a question that just arrived`() = runBlocking {
        val gate = CountDownLatch(1)
        questionsResponseGate = gate
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()

        // The /questions response is parked server-side: the event lands first.
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState { it.pendingQuestions.isNotEmpty() }

        // The stale empty snapshot arrives after the event and must be dropped.
        gate.countDown()
        delay(200)
        assertEquals(listOf("tool_q1"), viewModel.uiState.value.pendingQuestions.map { it.questionId })
    }

    @Test
    fun `submit posts the picked option and the card clears on answered`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState { it.pendingQuestions.isNotEmpty() }

        viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
        viewModel.onSubmitQuestion()

        val request = awaitRequest("/questions/")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/questions/tool_q1", request.path)
        assertEquals("""{"answers":{"q_0":{"kind":"single","option_id":"opt_0_1"}}}""", request.body.readUtf8())

        // The ack alone must not clear the card — only the answered event does.
        delay(300)
        assertEquals(1, viewModel.uiState.value.pendingQuestions.size)
        socket.send(questionAnsweredFrame("tool_q1"))
        awaitState { it.pendingQuestions.isEmpty() }
    }

    @Test
    fun `free text answer posts kind other`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState { it.pendingQuestions.isNotEmpty() }

        viewModel.onQuestionOtherTextChange("q_0", "green")
        viewModel.onSubmitQuestion()

        val request = awaitRequest("/questions/")
        assertEquals("""{"answers":{"q_0":{"kind":"other","text":"green"}}}""", request.body.readUtf8())
    }

    @Test
    fun `submit is a no-op while an item is unanswered`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState { it.pendingQuestions.isNotEmpty() }
        // Drain the history/status/questions/WS requests recorded so far.
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onSubmitQuestion()

        delay(200)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        assertEquals(1, viewModel.uiState.value.pendingQuestions.size)
    }

    @Test
    fun `wizard next advances only when the current item is answered`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(multiItemQuestionRequestedFrame(id = "tool_q1"))
        awaitState { it.pendingQuestions.isNotEmpty() }

        assertEquals(0, viewModel.uiState.value.questionStep)
        assertFalse(viewModel.uiState.value.isQuestionStepAnswered)

        // The current item has no answer yet: Next is a no-op.
        viewModel.onQuestionNext()
        assertEquals(0, viewModel.uiState.value.questionStep)

        viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
        assertTrue(viewModel.uiState.value.isQuestionStepAnswered)
        viewModel.onQuestionNext()
        assertEquals(1, viewModel.uiState.value.questionStep)
    }

    @Test
    fun `wizard next is a no-op on the last item`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(multiItemQuestionRequestedFrame(id = "tool_q1"))
        awaitState { it.pendingQuestions.isNotEmpty() }

        viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
        viewModel.onQuestionNext()
        viewModel.onQuestionOptionPicked("q_1", "opt_1_0")
        viewModel.onQuestionNext()
        assertEquals(2, viewModel.uiState.value.questionStep)

        viewModel.onQuestionOptionPicked("q_2", "opt_2_0")
        viewModel.onQuestionNext()
        assertEquals(2, viewModel.uiState.value.questionStep)
    }

    @Test
    fun `wizard back returns to the previous item and keeps the draft`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(multiItemQuestionRequestedFrame(id = "tool_q1"))
        awaitState { it.pendingQuestions.isNotEmpty() }

        viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
        viewModel.onQuestionNext()
        assertEquals(1, viewModel.uiState.value.questionStep)

        viewModel.onQuestionBack()
        assertEquals(0, viewModel.uiState.value.questionStep)
        assertEquals("opt_0_1", viewModel.uiState.value.questionSelections["q_0"])

        // Back on the first item is a no-op.
        viewModel.onQuestionBack()
        assertEquals(0, viewModel.uiState.value.questionStep)
    }

    @Test
    fun `wizard step resets when the queue head advances`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(multiItemQuestionRequestedFrame(id = "tool_q1"))
        socket.send(questionRequestedFrame(id = "tool_q2"))
        awaitState { it.pendingQuestions.size == 2 }

        viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
        viewModel.onQuestionNext()
        assertEquals(1, viewModel.uiState.value.questionStep)

        socket.send(questionAnsweredFrame("tool_q1"))
        awaitState { it.pendingQuestions.map { q -> q.questionId } == listOf("tool_q2") }
        assertEquals(0, viewModel.uiState.value.questionStep)
    }

    @Test
    fun `dismiss posts to the dismiss endpoint and the card clears on dismissed`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState { it.pendingQuestions.isNotEmpty() }

        viewModel.onDismissQuestion()

        val request = awaitRequest(":dismiss")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/questions/tool_q1:dismiss", request.path)
        assertEquals("{}", request.body.readUtf8())

        socket.send(questionDismissedFrame("tool_q1"))
        awaitState { it.pendingQuestions.isEmpty() }
    }

    @Test
    fun `failed answer post keeps the card and emits QuestionFailed`() = runBlocking {
        answersSucceed = false
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState { it.pendingQuestions.isNotEmpty() }

        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
            viewModel.onSubmitQuestion()
            awaitRequest("/questions/")
            withTimeout(5_000) { while (effects.isEmpty()) delay(10) }

            assertEquals(
                ChatViewModel.Effect.QuestionFailed("question tool_q1 not found"),
                effects.single(),
            )
            // No resolved event will come: the card must stay up.
            assertEquals(1, viewModel.uiState.value.pendingQuestions.size)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `pending question survives the turn ended refetch`() = runBlocking {
        // The poll endpoint is the source of truth: it keeps returning the question, so
        // neither the screen-start load nor the turn.ended re-derivation may drop it.
        questionsBody = """
            {"code":0,"msg":"success","data":{"items":[
              {"type":"event.question.requested","question_id":"tool_q1","session_id":"session_1",
               "turn_id":0,"tool_call_id":"tool_q1",
               "questions":[{"id":"q_0","question":"Red or blue?","header":"Color",
                             "options":[{"id":"opt_0_0","label":"red"},
                                        {"id":"opt_0_1","label":"blue"}],
                             "allow_other":false}],
               "created_at":"2026-07-20T06:06:49.808Z","agentId":"main","sessionId":"session_1"}
            ]},"request_id":"r"}
        """.trimIndent()
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState { it.pendingQuestions.isNotEmpty() }
        socket.send(turnStartedFrame())
        awaitState { it.isWorking }
        // Drain the history/status/questions/WS requests recorded so far so the next
        // questions poll observed is the one turn.ended triggers.
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        socket.send(turnEndedFrame(reason = "completed"))

        awaitRequest("/questions")
        delay(300)
        assertEquals(listOf("tool_q1"), viewModel.uiState.value.pendingQuestions.map { it.questionId })
    }

    @Test
    fun `nav arguments seed the top bar before the session fetch lands`() {
        val seeded = ChatViewModel(
            ChatRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }, dataStore),
            SessionsRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }),
            settingsRepository,
            SavedStateHandle(
                mapOf(
                    "sessionId" to "session_1",
                    "title" to "Fix the bug",
                    "cwd" to "/home/ubuntu/kimi-android-client",
                ),
            ),
        )

        assertEquals("Fix the bug", seeded.uiState.value.sessionTitle)
        assertEquals("/home/ubuntu/kimi-android-client", seeded.uiState.value.sessionCwd)
    }

    @Test
    fun `nav arguments with only a cwd seed the basename`() {
        val seeded = ChatViewModel(
            ChatRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }, dataStore),
            SessionsRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }),
            settingsRepository,
            SavedStateHandle(
                mapOf("sessionId" to "session_1", "cwd" to "/home/ubuntu/kimi-android-client"),
            ),
        )

        assertEquals("kimi-android-client", seeded.uiState.value.sessionTitle)
    }

    @Test
    fun `session title and cwd load into the top bar state`() = runBlocking {
        viewModel.onScreenStarted()

        awaitState { it.sessionTitle != null }
        assertEquals("Fix the bug", viewModel.uiState.value.sessionTitle)
        assertEquals("/home/ubuntu/kimi-android-client", viewModel.uiState.value.sessionCwd)
    }

    @Test
    fun `titleless session falls back to the cwd basename in the top bar`() = runBlocking {
        sessionBody =
            """{"code":0,"msg":"success","data":{"id":"session_1","title":"",""" +
                """"updated_at":"2026-07-20T00:00:00.000Z","busy":false,""" +
                """"metadata":{"cwd":"/home/ubuntu/kimi-android-client"}},"request_id":"r"}"""
        viewModel.onScreenStarted()

        awaitState { it.sessionTitle != null }
        assertEquals("kimi-android-client", viewModel.uiState.value.sessionTitle)
    }

    @Test
    fun `failed session lookup leaves the top bar on the session id fallback`() = runBlocking {
        sessionBody = """{"code":40404,"msg":"session not found","data":null,"request_id":"r"}"""
        viewModel.onScreenStarted()

        // Wait until the failed lookup was served before asserting the fallback held.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val request = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: continue
            if (request.path == "/api/v1/sessions/session_1") break
        }
        delay(200)
        assertNull(viewModel.uiState.value.sessionTitle)
        assertNull(viewModel.uiState.value.sessionCwd)
    }

    @Test
    fun `models load into state and the server default drives the chip label`() = runBlocking {
        viewModel.onScreenStarted()

        awaitState { it.modelsAvailable }
        val state = viewModel.uiState.value
        assertEquals(3, state.availableModels.size)
        assertEquals("kimi-code/k3", state.serverDefaultModel)
        assertNull(state.sessionModel)
        // No session choice yet: the chip shows the server default's display name.
        assertEquals("K3", state.currentModelLabel)
    }

    @Test
    fun `failed models fetch hides the chip`() = runBlocking {
        modelsBody = """{"code":50000,"msg":"boom","data":null,"request_id":"r"}"""
        viewModel.onScreenStarted()

        // Wait until the failed fetch was served (the WS is gated on the startup GETs,
        // and a failed models fetch never triggers the config GET — so the socket is
        // not the synchronization point here).
        awaitRequest("/api/v1/models")
        delay(300)

        // The fetch failed: no models, no chip — and the rest of the screen works.
        assertEquals(emptyList<ServerModel>(), viewModel.uiState.value.availableModels)
        assertNull(viewModel.uiState.value.currentModelLabel)
    }

    @Test
    fun `selected model persists and survives ViewModel recreation`() = runBlocking {
        viewModel.onScreenStarted()
        awaitState { it.modelsAvailable }

        viewModel.onModelSelected("kimi-code/kimi-for-coding-highspeed")

        awaitState { it.sessionModel == "kimi-code/kimi-for-coding-highspeed" }
        assertEquals("K2.7 Coding Highspeed", viewModel.uiState.value.currentModelLabel)

        // Recreation over the same DataStore: the choice is reloaded, not lost.
        val recreated = ChatViewModel(
            ChatRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }, dataStore),
            SessionsRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }),
            settingsRepository,
            SavedStateHandle(mapOf("sessionId" to "session_1")),
        )
        recreated.onScreenStarted()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val state = recreated.uiState.value
            if (state.modelsAvailable && state.sessionModel != null) break
            delay(10)
        }
        assertEquals("kimi-code/kimi-for-coding-highspeed", recreated.uiState.value.sessionModel)
        assertEquals("K2.7 Coding Highspeed", recreated.uiState.value.currentModelLabel)
    }

    @Test
    fun `selected effort persists and survives ViewModel recreation`() = runBlocking {
        viewModel.onScreenStarted()
        awaitState { it.modelsAvailable }

        viewModel.onEffortSelected("low")

        awaitState { it.sessionEffort == "low" }
        val state = viewModel.uiState.value
        // K3 (the effective model) offers its efforts; the stored one is selected.
        assertEquals(listOf("low", "high", "max"), state.supportedEfforts)
        assertEquals("low", state.selectedEffort)
        assertEquals("high", state.defaultEffort)

        // Recreation over the same DataStore: the choice is reloaded, not lost.
        val recreated = ChatViewModel(
            ChatRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }, dataStore),
            SessionsRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }),
            settingsRepository,
            SavedStateHandle(mapOf("sessionId" to "session_1")),
        )
        recreated.onScreenStarted()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val current = recreated.uiState.value
            if (current.modelsAvailable && current.sessionEffort != null) break
            delay(10)
        }
        assertEquals("low", recreated.uiState.value.sessionEffort)
        assertEquals("low", recreated.uiState.value.selectedEffort)
    }

    @Test
    fun `stored effort incompatible with the session's model exposes no effort selection`() = runBlocking {
        // Picked on K3, then the session switched to K2.7 (no support_efforts):
        // the sheet shows no effort section and no selection (ticket 032).
        dataStore.edit {
            it[stringPreferencesKey("effort_for_session_session_1")] = "low"
            it[stringPreferencesKey("model_for_session_session_1")] = "kimi-code/kimi-for-coding"
        }
        viewModel.onScreenStarted()
        awaitState { it.modelsAvailable && it.sessionModel != null }

        val state = viewModel.uiState.value
        assertEquals("low", state.sessionEffort)
        assertEquals(emptyList<String>(), state.supportedEfforts)
        assertNull(state.selectedEffort)
        assertNull(state.defaultEffort)
    }

    @Test
    fun `attachment send uploads the file then posts the prompt with its content item`() = runBlocking {
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState { !it.isLoading }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onAttachmentPicked("a.txt", 5, "text/plain", "hello".toByteArray())
        viewModel.onInputChange("see attached")
        viewModel.onSend()

        val upload = awaitRequest("/api/v1/files")
        assertEquals("POST", upload.method)
        val prompt = awaitRequest("/prompts")
        assertEquals("POST", prompt.method)
        assertEquals("/api/v1/sessions/session_1/prompts", prompt.path)
        assertEquals(
            """{"content":[""" +
                """{"type":"file","file_id":"f_1","name":"a.txt","media_type":"text/plain","size":5},""" +
                """{"type":"text","text":"see attached"}],"model":"kimi-code/k3"}""",
            prompt.body.readUtf8(),
        )
        // Chips and text clear on success, like a text-only send.
        awaitState { it.attachments.isEmpty() && it.inputText.isEmpty() && !it.isSending }
    }

    @Test
    fun `send is a no-op while an attachment upload is in flight`() = runBlocking {
        val gate = CountDownLatch(1)
        filesResponseGate = gate
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState { !it.isLoading }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onAttachmentPicked("a.txt", 5, "text/plain", "hello".toByteArray())
        viewModel.onSend()
        awaitState {
            it.isSending && it.attachments.single().status == ChatViewModel.PendingAttachment.Status.Uploading
        }

        // A second tap while the upload is parked server-side must not send again.
        viewModel.onSend()
        gate.countDown()

        awaitRequest("/api/v1/files")
        // Attachments alone are sendable: the prompt goes out without a text item.
        val prompt = awaitRequest("/prompts")
        assertEquals(
            """{"content":[{"type":"file","file_id":"f_1","name":"a.txt","media_type":"text/plain","size":5}],""" +
                """"model":"kimi-code/k3"}""",
            prompt.body.readUtf8(),
        )
        awaitState { it.attachments.isEmpty() && !it.isSending }
        // Exactly one upload and one prompt: the second tap sent nothing.
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `failed upload blocks the send and posts no prompt`() = runBlocking {
        uploadsSucceed = false
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState { !it.isLoading }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onAttachmentPicked("a.txt", 5, "text/plain", "hello".toByteArray())
            viewModel.onSend()
            awaitRequest("/api/v1/files")
            withTimeout(5_000) { while (effects.isEmpty()) delay(10) }

            assertEquals(
                ChatViewModel.Effect.SendFailed("file exceeds the 50 MiB limit"),
                effects.single(),
            )
            // The failed chip stays staged and marked; no prompt was posted.
            val attachment = viewModel.uiState.value.attachments.single()
            assertEquals(ChatViewModel.PendingAttachment.Status.Failed, attachment.status)
            assertTrue(!viewModel.uiState.value.isSending)
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `removing a chip excludes the file from the send`() = runBlocking {
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState { !it.isLoading }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onAttachmentPicked("a.txt", 5, "text/plain", "hello".toByteArray())
        val localId = viewModel.uiState.value.attachments.single().localId
        viewModel.onAttachmentRemoved(localId)
        assertEquals(emptyList<ChatViewModel.PendingAttachment>(), viewModel.uiState.value.attachments)

        viewModel.onInputChange("just text")
        viewModel.onSend()

        val prompt = awaitRequest("/prompts")
        assertEquals(
            """{"content":[{"type":"text","text":"just text"}],"model":"kimi-code/k3"}""",
            prompt.body.readUtf8(),
        )
        // No upload happened for the removed chip.
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
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

    private fun contextSplicedFrame(id: String, text: String): String =
        """{"type":"context.spliced","seq":6,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-19T00:00:00.500Z","payload":{"type":"context.spliced","start":0,""" +
            """"deleteCount":0,"messages":[{"role":"user","content":[{"type":"text","text":"$text"}],""" +
            """"toolCalls":[],"origin":{"kind":"user"},"id":"$id"}],"agentId":"main","sessionId":"session_1"}}"""

    /** Steer echo shape: the spliced steered message carries no id (docs/server-api.md). */
    private fun steerEchoSplicedFrame(text: String): String =
        """{"type":"context.spliced","seq":12,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-20T00:00:01.000Z","payload":{"type":"context.spliced","start":1,""" +
            """"deleteCount":0,"messages":[{"role":"user","content":[{"type":"text","text":"$text"}],""" +
            """"toolCalls":[],"origin":{"kind":"user"}}],"agentId":"main","sessionId":"session_1"}}"""

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

    /** Verbatim 0.27.0 shape (docs/server-api.md, "Core event payloads"). */
    private fun questionRequestedFrame(id: String): String =
        """{"type":"event.question.requested","seq":9,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-20T06:06:49.808Z","payload":{"type":"event.question.requested",""" +
            """"question_id":"$id","session_id":"session_1","turn_id":0,"tool_call_id":"$id",""" +
            """"questions":[{"id":"q_0","question":"Red or blue?","header":"Color",""" +
            """"options":[{"id":"opt_0_0","label":"red","description":"warm color"},""" +
            """{"id":"opt_0_1","label":"blue","description":"cool color"}],"allow_other":true}],""" +
            """"created_at":"2026-07-20T06:06:49.808Z","agentId":"main","sessionId":"session_1"}}"""

    /** Three-item variant of [questionRequestedFrame], for the wizard tests (ticket 035). */
    private fun multiItemQuestionRequestedFrame(id: String): String =
        """{"type":"event.question.requested","seq":9,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-20T06:06:49.808Z","payload":{"type":"event.question.requested",""" +
            """"question_id":"$id","session_id":"session_1","turn_id":0,"tool_call_id":"$id",""" +
            """"questions":[""" +
            """{"id":"q_0","question":"Red or blue?","header":"Color",""" +
            """"options":[{"id":"opt_0_0","label":"red","description":"warm color"},""" +
            """{"id":"opt_0_1","label":"blue","description":"cool color"}],"allow_other":true},""" +
            """{"id":"q_1","question":"Fast or safe?","header":"Mode",""" +
            """"options":[{"id":"opt_1_0","label":"fast","description":"skip checks"},""" +
            """{"id":"opt_1_1","label":"safe","description":"run checks"}],"allow_other":false},""" +
            """{"id":"q_2","question":"Yes or no?","header":"Confirm",""" +
            """"options":[{"id":"opt_2_0","label":"yes","description":"go ahead"},""" +
            """{"id":"opt_2_1","label":"no","description":"stop"}],"allow_other":false}],""" +
            """"created_at":"2026-07-20T06:06:49.808Z","agentId":"main","sessionId":"session_1"}}"""

    private fun questionAnsweredFrame(id: String): String =
        """{"type":"event.question.answered","seq":11,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-20T06:08:12.548Z","payload":{"type":"event.question.answered",""" +
            """"question_id":"$id","answers":{"Red or blue?":"blue"},""" +
            """"resolved_at":"2026-07-20T06:08:12.548Z","agentId":"main","sessionId":"session_1"}}"""

    private fun questionDismissedFrame(id: String): String =
        """{"type":"event.question.dismissed","seq":28,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-20T06:12:37.844Z","payload":{"type":"event.question.dismissed",""" +
            """"question_id":"$id","dismissed_at":"2026-07-20T06:12:37.844Z",""" +
            """"agentId":"main","sessionId":"session_1"}}"""
}
