package fr.louissaglio.kimi.android.ui.chat

import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import fr.louissaglio.kimi.android.data.attachments.AttachmentStore
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.ContextUsage
import fr.louissaglio.kimi.android.data.chat.PendingQuestion
import fr.louissaglio.kimi.android.data.chat.ServerModel
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
 * served only once the startup GET burst has gone quiet ([startupQuietNanos]), so a poll
 * response can never reorder after a socket event the test already sent — whatever the
 * exact set of startup GETs is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var viewModel: ChatViewModel
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var dataStore: FakePreferencesDataStore

    /** 0 ms window: sub-agent frames apply on arrival, no flush timer involved. */
    private val subagentStore = SubagentStreamStore(Json { ignoreUnknownKeys = true }, streamCoalesceWindowMillis = 0)

    @Volatile
    private var approvalsSucceed = true

    @Volatile
    private var abortsSucceed = true

    @Volatile
    private var answersSucceed = true

    @Volatile
    private var uploadsSucceed = true

    @Volatile
    private var compactsSucceed = true

    @Volatile
    private var questionsBody =
        """{"code":0,"msg":"success","data":{"items":[]},"request_id":"r"}"""

    @Volatile
    private var messagesBody =
        """{"code":0,"msg":"success","data":{"items":[],"has_more":false},"request_id":"r"}"""

    /** Served for /messages requests carrying a `before_id` cursor (ticket 043). */
    @Volatile
    private var olderMessagesBody =
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
    private var statusBody =
        """{"code":0,"msg":"success","data":{"busy":false},"request_id":"r"}"""

    @Volatile
    private var serverSocket: WebSocket? = null

    /** Every ViewModel whose screen was started in this test. Their viewModelScopes
     * (socket collect + reconnect backoff, gate collector, flush timer) must be
     * cancelled in [tearDown]: left running, a leaked socket coroutine outlives the
     * test and touches Main after [Dispatchers.resetMain] — observed as
     * "Dispatchers.Main is used concurrently with setting it", and as a
     * "Module with the Main dispatcher had failed to initialize" crash on an OkHttp
     * thread when a leaked connection failure resumed between tests (ticket 049). */
    private val startedViewModels = CopyOnWriteArrayList<ChatViewModel>()

    /** Every frame the client sent over any WS connection of the test (ticket 048
     * asserts the resume subscribe carries the stored cursor after a gate cycle). */
    private val clientFrames = CopyOnWriteArrayList<String>()

    /**
     * Arrival time (System.nanoTime) of the latest non-WS GET the server dispatched —
     * every path counts, unconditionally, so a new, moved, or suppressed startup GET
     * needs no change here. Seeded in [setUp] so an upgrade arriving before any GET
     * still waits out the quiet window.
     */
    private val lastGetArrivalNanos = AtomicLong()

    /**
     * Quiet window after the last startup GET before the WS upgrade is served: long
     * enough that no straggler poll can still be in flight (the startup GETs arrive
     * within a few ms of each other), short enough to keep every test well under a
     * second.
     */
    private val startupQuietNanos = TimeUnit.MILLISECONDS.toNanos(150)

    /** Blocks the WS upgrade until the startup GET burst has gone quiet for
     * [startupQuietNanos] — the gate that keeps a poll response from reordering after a
     * socket event. Capped so a stuck burst can never park the dispatcher thread for the
     * whole test timeout. Runs on the WS connection's own dispatch thread, so parked
     * responses elsewhere (the test gates below) do not hold it. */
    private fun awaitStartupGetsQuiet() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (System.nanoTime() - lastGetArrivalNanos.get() >= startupQuietNanos) return
            Thread.sleep(10)
        }
    }

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

    /** The store's platform I/O never runs on the JVM: tests stage chips (and exercise
     * the failure effects) through [ChatViewModel.onAttachmentRead] directly. */
    private class FakeAttachmentStore : AttachmentStore {
        override fun read(uri: Uri): AttachmentStore.ReadResult = throw UnsupportedOperationException()

        override fun writeToCache(name: String, bytes: ByteArray): Uri? = throw UnsupportedOperationException()
    }

    @Before
    fun setUp() {
        server.start()
        lastGetArrivalNanos.set(System.nanoTime())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                // Stamped at arrival, not at response time: the /status and /questions
                // responses may be parked behind a test gate below, and the WS upgrade's
                // quiet window must not wait for those. The WS path is itself a GET but
                // is excluded — the window measures the poll burst only.
                if (request.method == "GET" && !path.startsWith("/api/v1/ws")) {
                    lastGetArrivalNanos.set(System.nanoTime())
                }
                return when {
                    path == "/api/v1/sessions/session_1" && request.method == "GET" ->
                        MockResponse().setBody(sessionBody)
                    path.startsWith("/api/v1/sessions/session_1/messages") ->
                        MockResponse().setBody(if ("before_id" in path) olderMessagesBody else messagesBody)
                    path == "/api/v1/sessions/session_1/status" -> {
                        statusResponseGate?.await(5, TimeUnit.SECONDS)
                        MockResponse().setBody(statusBody)
                    }
                    path == "/api/v1/sessions/session_1/prompts" -> MockResponse().setBody(
                        """{"code":0,"msg":"success","data":{"prompt_id":"msg_1","user_message_id":"msg_1",""" +
                            """"status":"running","created_at":"2026-07-20T00:00:00.000Z"},"request_id":"r"}""",
                    )
                    path == "/api/v1/sessions/session_1/prompts:steer" -> MockResponse().setBody(
                        """{"code":0,"msg":"success","data":{"steered":true,"prompt_ids":["msg_1"]},"request_id":"r"}""",
                    )
                    path == "/api/v1/sessions/session_1:compact" ->
                        if (compactsSucceed) {
                            MockResponse().setBody(
                                """{"code":0,"msg":"success","data":{},"request_id":"r"}""",
                            )
                        } else {
                            MockResponse().setBody(
                                """{"code":40910,"msg":"No messages to compact in current history.",""" +
                                    """"data":null,"request_id":"r"}""",
                            )
                        }
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
                    path == "/api/v1/models" -> MockResponse().setBody(modelsBody)
                    path == "/api/v1/config" -> MockResponse().setBody(configBody)
                    path.startsWith("/api/v1/ws") -> {
                        awaitStartupGetsQuiet()
                        MockResponse().withWebSocketUpgrade(
                            object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: Response) {
                                    serverSocket = webSocket
                                }

                                override fun onMessage(webSocket: WebSocket, text: String) {
                                    clientFrames += text
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
            FakeAttachmentStore(),
            subagentStore,
            SavedStateHandle(mapOf("sessionId" to "session_1")),
            // The suite asserts per-event application: 0 publishes every delta on
            // arrival (the pre-conflation behavior), no timer involved.
            streamCoalesceWindowMillis = 0,
        )
        startedViewModels += viewModel
    }

    @After
    fun tearDown() {
        // Cancel the ViewModels' coroutines AND wait for them to die while Main is
        // still set and the server still up: an in-flight REST call's OkHttp execute()
        // is not cancellable and resumes its parent on Main when the response comes
        // back — joining after resetMain would crash it on the dispatcher thread
        // ("Module with the Main dispatcher had failed to initialize"). See
        // startedViewModels (ticket 049).
        runBlocking { startedViewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        appScope.cancel()
        server.shutdown()
    }

    @Test
    fun `approval request event shows card and resolved event clears it`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()

        socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: echo hi", command = "echo hi"))
        awaitState(viewModel.uiState) { it.pendingApprovals.isNotEmpty() }
        assertEquals(
            listOf(ChatViewModel.PendingApproval("ap_1", "Bash", "echo hi")),
            viewModel.uiState.value.pendingApprovals,
        )

        // Resolved from another client: the card clears without local action.
        socket.send(approvalResolvedFrame("ap_1"))
        awaitState(viewModel.uiState) { it.pendingApprovals.isEmpty() }
        assertEquals(emptyList<ChatViewModel.PendingApproval>(), viewModel.uiState.value.pendingApprovals)
    }

    @Test
    fun `stream deltas publish the leading edge then coalesce until a substantive event flushes`() = runBlocking {
        val vm = newViewModel(streamCoalesceWindowMillis = 100)
        vm.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(vm.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        val (texts, recorder) = assistantTextRecorder(vm)
        try {
            socket.send(assistantDeltaFrame("Hello", seq = 5))
            // The burst's leading edge renders immediately, without waiting out the window.
            awaitCondition { texts == listOf("Hello") }

            socket.send(assistantDeltaFrame(" world", seq = 6))
            socket.send(assistantDeltaFrame("!", seq = 7))
            // In-order stream: the approval frame is processed after both deltas, and
            // pending text flushes before it applies.
            socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: ls", command = "ls"))
            awaitState(vm.uiState) { it.pendingApprovals.isNotEmpty() }

            // The two trailing deltas became ONE emission, published before the approval.
            // Awaited (not read at an arbitrary instant): the recorder's append races the
            // reader thread's queue drain — see assistantTextRecorder (ticket 049).
            awaitCondition { texts == listOf("Hello", "Hello world!") }
        } finally {
            recorder.cancel()
        }
    }

    @Test
    fun `turn ended flushes the accumulated text exactly before the completion applies`() = runBlocking {
        val vm = newViewModel(streamCoalesceWindowMillis = 100)
        vm.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(vm.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        val (texts, recorder) = assistantTextRecorder(vm)
        try {
            socket.send(turnStartedFrame())
            awaitState(vm.uiState) { it.isWorking }
            socket.send(assistantDeltaFrame("Hello", seq = 5))
            awaitCondition { texts == listOf("Hello") }

            socket.send(assistantDeltaFrame(" world!", seq = 6))
            socket.send(turnEndedFrame(reason = "completed"))
            // The turn-end refetch is issued after the flush: once it has run, the
            // accumulated text was necessarily published first, and in full.
            awaitRequest("/messages")
            awaitState(vm.uiState) { !it.isWorking }

            // Awaited (not read at an arbitrary instant): the recorder's append races the
            // reader thread's queue drain — see assistantTextRecorder (ticket 049).
            awaitCondition { texts == listOf("Hello", "Hello world!") }
        } finally {
            recorder.cancel()
        }
    }

    @Test
    fun `window zero publishes every delta on arrival`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        val (texts, recorder) = assistantTextRecorder(viewModel)
        try {
            socket.send(assistantDeltaFrame("H", seq = 5))
            socket.send(assistantDeltaFrame("e", seq = 6))
            socket.send(assistantDeltaFrame("llo", seq = 7))
            // One emission per delta — the pre-conflation behavior the rest of the
            // suite asserts. Awaited (not read at an arbitrary instant): the recorder's
            // append races the reader thread's queue drain — see assistantTextRecorder
            // (ticket 049).
            awaitCondition { texts == listOf("H", "He", "Hello") }
        } finally {
            recorder.cancel()
        }
    }

    @Test
    fun `step retrying shows a transient status cleared by the replacement step started`() = runBlocking {
        // Ticket 058: a 0.31.0 retry storm (provider 429) must read as "retrying
        // (attempt N of 10)", not as a stalled chat.
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }

        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }

        socket.send(stepRetryingFrame(nextAttempt = 2))
        awaitState(viewModel.uiState) { it.stepRetry != null }
        assertEquals(
            ChatViewModel.StepRetry(
                nextAttempt = 2,
                maxAttempts = 10,
                errorMessage = "429 The engine is currently overloaded, please try again later",
            ),
            viewModel.uiState.value.stepRetry,
        )

        // The retry begins: a fresh turn.step.started re-fires with a NEW stepId for
        // the same step number (docs/server-api.md) and ends the status.
        socket.send(stepStartedFrame(step = 1, stepId = "f1ed9c2a-new"))
        awaitState(viewModel.uiState) { it.stepRetry == null }
        // Neither frame created a timeline row.
        assertEquals(emptyList<ChatItem>(), viewModel.uiState.value.items)
    }

    @Test
    fun `step retrying status clears when the turn ends`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }

        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }
        socket.send(stepRetryingFrame(nextAttempt = 9))
        awaitState(viewModel.uiState) { it.stepRetry != null }

        socket.send(turnEndedFrame(reason = "failed"))
        awaitState(viewModel.uiState) { !it.isWorking }
        assertNull(viewModel.uiState.value.stepRetry)
    }

    @Test
    fun `a turn that ended while the screen was away leaves no ghost live rows`() = runBlocking {
        // Louis's report (ticket 048): the stream is watched, the app backgrounds
        // mid-turn, the agent finishes while away, the app comes back.
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        socket.send(turnStartedFrame())
        socket.send(assistantDeltaFrame("partial", seq = 6))
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.singleOrNull()?.text == "partial"
        }
        viewModel.onScreenStopped()

        // While away, the turn ends: history now carries the complete message.
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_1","session_id":"session_1","role":"assistant",""" +
            """"content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":"partial plus the rest"}],""" +
            """"created_at":"2026-07-29T00:00:01.000Z"}""" +
            """],"has_more":false},"request_id":"r"}"""
        serverSocket = null
        viewModel.onScreenStarted()

        // The re-subscribe resumes with the cursor stored before backgrounding
        // (turnStarted's seq 20 — the volatile delta must not move it): the missed
        // window replays instead of being lost.
        awaitCondition { clientFrames.any { "\"cursors\"" in it } }
        val resume = clientFrames.first { "\"cursors\"" in it }
        assertTrue(resume, resume.contains("\"seq\":20"))
        assertTrue(resume, resume.contains("\"epoch\":\"ep_1\""))

        // A finished turn can have no live rows: the status-aware resync clears the
        // truncated ghost bubble and the canonical message renders exactly once.
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()
                ?.singleOrNull()?.let { it.id == "msg_1" && it.text == "partial plus the rest" } == true
        }
        assertEquals(
            listOf("msg_1"),
            viewModel.uiState.value.items.orEmpty().map { it.id },
        )
    }

    @Test
    fun `a turn still running on return keeps its live rows`() = runBlocking {
        statusBody = """{"code":0,"msg":"success","data":{"busy":true},"request_id":"r"}"""
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        socket.send(turnStartedFrame())
        socket.send(assistantDeltaFrame("still streaming", seq = 6))
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.singleOrNull()?.text == "still streaming"
        }
        viewModel.onScreenStopped()

        // The turn is still running: history carries only the user's prompt so far.
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_u","session_id":"session_1","role":"user",""" +
            """"content":[{"type":"text","text":"do the thing"}],""" +
            """"created_at":"2026-07-29T00:00:00.000Z"}""" +
            """],"has_more":false},"request_id":"r"}"""
        serverSocket = null
        viewModel.onScreenStarted()

        // busy:true → keep-live refetch: the committed prompt lands and the
        // in-flight bubble survives (the resumed replay keeps feeding it).
        awaitState(viewModel.uiState) { state ->
            state.items?.any { it is ChatItem.UserMessage } == true
        }
        val bubbles = viewModel.uiState.value.items.orEmpty().filterIsInstance<ChatItem.AssistantMessage>()
        assertEquals(1, bubbles.size)
        assertEquals("still streaming", bubbles.single().text)
    }

    @Test
    fun `a turn event racing the resync status fetch downgrades the clear`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
        socket.send(turnStartedFrame())
        socket.send(assistantDeltaFrame("partial", seq = 6))
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.singleOrNull()?.text == "partial"
        }
        viewModel.onScreenStopped()

        // The first turn ended while away (history carries its full message), but
        // the idle /status snapshot the resync is about to read is overtaken by a
        // NEW turn starting on the resumed socket.
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_1","session_id":"session_1","role":"assistant",""" +
            """"content":[{"type":"text","text":"partial plus the rest"}],""" +
            """"created_at":"2026-07-29T00:00:01.000Z"}""" +
            """],"has_more":false},"request_id":"r"}"""
        statusResponseGate = CountDownLatch(1)
        serverSocket = null
        viewModel.onScreenStarted()

        // The resync's /status GET is parked server-side; the new turn starts and
        // streams its first text — strictly newer than the parked snapshot.
        val socket2 = awaitServerSocket()
        socket2.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }
        socket2.send(assistantDeltaFrame("new turn text", seq = 7))
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.singleOrNull()?.text == "new turn text"
        }

        // Releasing the idle snapshot must NOT clear the new turn's live rows.
        statusResponseGate!!.countDown()
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.any { it.id == "msg_1" } == true
        }
        assertEquals(
            listOf("partial plus the rest", "new turn text"),
            viewModel.uiState.value.items.orEmpty().filterIsInstance<ChatItem.AssistantMessage>().map { it.text },
        )
    }

    @Test
    fun `a resync_required ack re-syncs from REST`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        // The subscribe ack refuses the resume (replay buffer overrun,
        // docs/server-api.md): the missed window heals from REST — a fresh
        // /status-driven resync refetch.
        socket.send(
            """{"type":"ack","id":"c_1","code":0,"msg":"success","payload":{"accepted":[],""" +
                """"not_found":[],"resync_required":["session_1"],"cursors":{}}}""",
        )

        awaitRequest("/status")
        awaitRequest("/messages")
        Unit
    }

    @Test
    fun `sub-agent frames route to the store and never touch the main chat`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        // The main agent spawns a sub-agent (verbatim 0.29.2 shapes, docs/server-api.md):
        // the Agent call row, then the spawned frame that links it to the sub-agent.
        socket.send(
            """{"type":"tool.call.started","seq":17,"epoch":"ep_1","session_id":"session_1",""" +
                """"timestamp":"2026-07-28T03:37:35.000Z","payload":{"type":"tool.call.started","turnId":0,""" +
                """"toolCallId":"tool_agent","name":"Agent","args":{"subagent_type":"explore",""" +
                """"description":"doc-verify","prompt":"Reply ok"},"agentId":"main","sessionId":"session_1"}}""",
        )
        socket.send(subagentSpawnedFrame())
        awaitState(viewModel.uiState) { state ->
            state.items?.filterIsInstance<ChatItem.ToolGroup>()
                ?.flatMap { it.tools }
                ?.any { it.id == "tool_agent" && it.subagentId == "agent-12" } == true
        }

        // The sub-agent's whole turn streams tagged with its own agentId.
        socket.send(subagentStartedFrame())
        socket.send(subTurnStartedFrame())
        socket.send(subContextSplicedFrame())
        socket.send(subAssistantDeltaFrame("sub reply"))
        awaitCondition {
            subagentEntry("agent-12")
                ?.items?.filterIsInstance<ChatItem.AssistantMessage>()?.singleOrNull()?.text == "sub reply"
        }

        // Main chat: the linked Agent row only — no foreign bubble, no working-state flip.
        val mainItems = viewModel.uiState.value.items.orEmpty()
        assertEquals(1, mainItems.size)
        assertTrue(mainItems.single() is ChatItem.ToolGroup)
        assertFalse(viewModel.uiState.value.isWorking)

        // The store accumulated the sub-agent's own stream, prompts as user bubbles.
        val entry = subagentEntry("agent-12")!!
        assertEquals("explore", entry.name)
        assertEquals(SubagentStatus.Working, entry.status)
        assertEquals(
            listOf(
                ChatItem.UserMessage("msg_sub", "sub task"),
                ChatItem.AssistantMessage("live_0", "sub reply", thinking = ""),
            ),
            entry.items,
        )

        // Completion flips the store status and leaves the main chat untouched.
        socket.send(subagentCompletedFrame())
        awaitCondition { subagentEntry("agent-12")?.status == SubagentStatus.Completed }
        assertEquals("done", subagentEntry("agent-12")!!.resultSummary)
        assertEquals(1, viewModel.uiState.value.items.orEmpty().size)
    }

    /** Non-suspend read of a store entry, for awaitCondition's plain-lambda condition. */
    private fun subagentEntry(subagentId: String): SubagentStreamStore.SubagentState? =
        runBlocking { subagentStore.entry("session_1", subagentId).first() }

    @Test
    fun `a sub-agent that ran before the app connected is seeded from history`() = runBlocking {
        // Louis's cold-open case: the app opens after the whole run — no subagent.*
        // frame will ever arrive, but history carries the Agent call and its result
        // (docs/server-api.md, Sub-agents). The page shows the seeded identity, status
        // and summary instead of the empty state.
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_2","session_id":"session_1","role":"tool",""" +
            """"content":[{"type":"tool_result","tool_call_id":"tool_1","is_error":false,""" +
            """"output":"agent_id: agent-12\nactual_subagent_type: explore\nstatus: completed\n\n[summary]\nthe final report"}],""" +
            """"created_at":"2026-07-28T03:37:52.000Z"},""" +
            """{"id":"msg_1","session_id":"session_1","role":"assistant",""" +
            """"content":[{"type":"tool_use","tool_call_id":"tool_1","tool_name":"Agent",""" +
            """"input":{"subagent_type":"explore","description":"doc-verify","prompt":"Reply ok"}}],""" +
            """"created_at":"2026-07-28T03:37:35.000Z"}""" +
            """],"has_more":false},"request_id":"r"}"""

        viewModel.onScreenStarted()

        awaitCondition { subagentEntry("agent-12")?.status == SubagentStatus.Completed }
        val entry = subagentEntry("agent-12")!!
        assertEquals("explore", entry.name)
        assertEquals("doc-verify", entry.description)
        assertEquals("the final report", entry.resultSummary)
        assertEquals(
            listOf(
                ChatItem.UserMessage("seeded_prompt", "Reply ok"),
                ChatItem.AssistantMessage("seeded_summary", "the final report", thinking = ""),
            ),
            entry.items,
        )
    }

    @Test
    fun `onHistoryTopReached prepends the next page deduplicated and stops when has_more is false`() = runBlocking {
        // Newest page (wire order newest-first): msg_2 is the oldest loaded — the cursor.
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_4","role":"user","content":[{"type":"text","text":"fourth"}]},""" +
            """{"id":"msg_3","role":"user","content":[{"type":"text","text":"third"}]},""" +
            """{"id":"msg_2","role":"user","content":[{"type":"text","text":"second"}]}""" +
            """],"has_more":true},"request_id":"r"}"""
        // Older page: msg_2 overlaps the loaded page (boundary race) and must not
        // produce a second row; has_more false stops the paging (ticket 043).
        olderMessagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_2","role":"user","content":[{"type":"text","text":"second"}]},""" +
            """{"id":"msg_1","role":"user","content":[{"type":"text","text":"first"}]}""" +
            """],"has_more":false},"request_id":"r"}"""

        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_2", "msg_3", "msg_4") }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
        assertTrue(viewModel.uiState.value.hasMoreHistory)

        viewModel.onHistoryTopReached()

        val request = awaitRequest("before_id")
        assertEquals("/api/v1/sessions/session_1/messages?page_size=100&before_id=msg_2", request.path)
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_1", "msg_2", "msg_3", "msg_4") }
        assertFalse(viewModel.uiState.value.hasMoreHistory)
        assertFalse(viewModel.uiState.value.isLoadingMoreHistory)

        // has_more was false: the next trigger is a no-op.
        viewModel.onHistoryTopReached()
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a failed older-page load keeps the loaded history and retries on the next trigger`() = runBlocking {
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_2","role":"user","content":[{"type":"text","text":"second"}]}""" +
            """],"has_more":true},"request_id":"r"}"""
        olderMessagesBody = """{"code":50000,"msg":"boom","data":null,"request_id":"r"}"""

        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_2") }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onHistoryTopReached()

        awaitRequest("before_id")
        // The load failed: the spinner clears, the loaded history stays, and the
        // session is still pageable — no error UI, the next trigger retries (043).
        awaitState(viewModel.uiState) { !it.isLoadingMoreHistory }
        assertEquals(listOf("msg_2"), viewModel.uiState.value.items?.map(ChatItem::id))
        assertTrue(viewModel.uiState.value.hasMoreHistory)

        olderMessagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_1","role":"user","content":[{"type":"text","text":"first"}]}""" +
            """],"has_more":false},"request_id":"r"}"""
        viewModel.onHistoryTopReached()

        val retry = awaitRequest("before_id")
        assertEquals("/api/v1/sessions/session_1/messages?page_size=100&before_id=msg_2", retry.path)
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_1", "msg_2") }
    }

    @Test
    fun `a turn-end refetch after paging resets to the newest page`() = runBlocking {
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_2","role":"user","content":[{"type":"text","text":"second"}]}""" +
            """],"has_more":true},"request_id":"r"}"""
        olderMessagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_1","role":"user","content":[{"type":"text","text":"first"}]}""" +
            """],"has_more":true},"request_id":"r"}"""

        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_2") }
        viewModel.onHistoryTopReached()
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_1", "msg_2") }

        // The turn ends: the refetch replaces history with the newest page only —
        // the prepended page is dropped, no row is duplicated (ticket 043).
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_4","role":"user","content":[{"type":"text","text":"fourth"}]},""" +
            """{"id":"msg_3","role":"user","content":[{"type":"text","text":"third"}]}""" +
            """],"has_more":true},"request_id":"r"}"""
        socket.send(turnEndedFrame(reason = "completed"))
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_3", "msg_4") }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        // The next top trigger pages from the NEW oldest message, not the stale cursor.
        olderMessagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_2","role":"user","content":[{"type":"text","text":"second"}]}""" +
            """],"has_more":false},"request_id":"r"}"""
        viewModel.onHistoryTopReached()
        val request = awaitRequest("before_id")
        assertEquals("/api/v1/sessions/session_1/messages?page_size=100&before_id=msg_3", request.path)
        awaitState(viewModel.uiState) { it.items?.map(ChatItem::id) == listOf("msg_2", "msg_3", "msg_4") }
        assertFalse(viewModel.uiState.value.hasMoreHistory)
    }

    @Test
    fun `status poll seeds the context usage ring`() = runBlocking {
        statusBody =
            """{"code":0,"msg":"success","data":{"busy":false,"context_tokens":25050,""" +
            """"max_context_tokens":1048576,"context_usage":0.0239},"request_id":"r"}"""

        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.contextUsage != null }

        assertEquals(ContextUsage(25050, 1048576), viewModel.uiState.value.contextUsage)
    }

    @Test
    fun `usage frames grow the ring live, phase frames leave it untouched`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        // No token counts in the default /status body: the ring stays hidden.
        assertNull(viewModel.uiState.value.contextUsage)

        socket.send(agentStatusPhaseFrame())
        delay(200)
        assertNull(viewModel.uiState.value.contextUsage)

        socket.send(agentStatusUsageFrame(25050, 1048576))
        awaitState(viewModel.uiState) { it.contextUsage != null }
        assertEquals(ContextUsage(25050, 1048576), viewModel.uiState.value.contextUsage)

        socket.send(agentStatusPhaseFrame())
        delay(200)
        assertEquals(ContextUsage(25050, 1048576), viewModel.uiState.value.contextUsage)
    }

    @Test
    fun `multiple requests queue in arrival order`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()

        socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: echo one", command = "echo one"))
        socket.send(approvalRequestedFrame(id = "ap_2", tool = "Write", action = "Writing /tmp/a.txt", command = ""))
        awaitState(viewModel.uiState) { it.pendingApprovals.size == 2 }

        val queue = viewModel.uiState.value.pendingApprovals
        assertEquals(listOf("ap_1", "ap_2"), queue.map { it.approvalId })
        // Without a command display, the human-readable action is the summary.
        assertEquals("Writing /tmp/a.txt", queue[1].summary)

        socket.send(approvalResolvedFrame("ap_1"))
        awaitState(viewModel.uiState) { it.pendingApprovals.size == 1 }
        assertEquals("ap_2", viewModel.uiState.value.pendingApprovals.single().approvalId)
    }

    @Test
    fun `allow posts approved decision and card clears on resolved`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: echo hi", command = "echo hi"))
        awaitState(viewModel.uiState) { it.pendingApprovals.isNotEmpty() }

        viewModel.onAllow()

        val request = awaitRequest("/approvals/")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/approvals/ap_1", request.path)
        assertEquals("""{"decision":"approved"}""", request.body.readUtf8())

        // The ack alone must not clear the card — only the resolved event does.
        delay(300)
        assertEquals(1, viewModel.uiState.value.pendingApprovals.size)
        socket.send(approvalResolvedFrame("ap_1"))
        awaitState(viewModel.uiState) { it.pendingApprovals.isEmpty() }
    }

    @Test
    fun `deny posts rejected decision`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(approvalRequestedFrame(id = "ap_1", tool = "Bash", action = "Running: rm -rf /", command = "rm -rf /"))
        awaitState(viewModel.uiState) { it.pendingApprovals.isNotEmpty() }

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
        awaitState(viewModel.uiState) { it.pendingApprovals.isNotEmpty() }

        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onAllow()
            awaitRequest("/approvals/")
            awaitCondition { effects.isNotEmpty() }

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
        awaitState(viewModel.uiState) { it.isWorking }

        viewModel.onStop()

        val request = awaitRequest(":abort")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1:abort", request.path)
        assertEquals("{}", request.body.readUtf8())

        // The ack alone must not clear the indicator — the server's turn.ended does.
        assertTrue(viewModel.uiState.value.isWorking)
        socket.send(turnEndedFrame(reason = "cancelled"))
        awaitState(viewModel.uiState) { !it.isWorking }
    }

    @Test
    fun `stop without a running turn sends no request`() = runBlocking {
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
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
        awaitState(viewModel.uiState) { it.isWorking }

        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onStop()
            awaitRequest(":abort")
            awaitCondition { effects.isNotEmpty() }

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
        awaitState(viewModel.uiState) { it.isWorking }

        // The stale busy:false snapshot arrives after the event and must be dropped.
        gate.countDown()
        delay(200)
        assertTrue(viewModel.uiState.value.isWorking)
    }

    @Test
    fun `context spliced shows the sent prompt bubble without a history refetch`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        // Drain the history/status/WS requests recorded so far.
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        socket.send(contextSplicedFrame(id = "msg_1", text = "hello from the phone"))

        awaitState(viewModel.uiState) { !it.items.isNullOrEmpty() }
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
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
        socket.send(contextSplicedFrame(id = "msg_1", text = "hello from the phone"))
        awaitState(viewModel.uiState) { it.items?.size == 1 }

        // The server committed the message; the turn.ended refetch returns it in history.
        messagesBody = """
            {"code":0,"msg":"success","data":{"items":[
              {"id":"msg_1","session_id":"session_1","role":"user",
               "content":[{"type":"text","text":"hello from the phone"}],
               "created_at":"2026-07-19T00:00:00.500Z"}
            ],"has_more":false},"request_id":"r"}
        """.trimIndent()
        socket.send(turnEndedFrame(reason = "completed"))

        // The refetch replaces committed rows wholesale: still exactly one bubble,
        // now carrying the server timestamp (ticket 039).
        awaitRequest("/messages")
        delay(300)
        assertEquals(
            listOf(
                ChatItem.UserMessage(
                    "msg_1",
                    "hello from the phone",
                    createdAt = Instant.parse("2026-07-19T00:00:00.500Z"),
                ),
            ),
            viewModel.uiState.value.items,
        )
    }

    @Test
    fun `sending while busy steers the queued prompt into the running turn`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }
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

        awaitState(viewModel.uiState) { it.inputText.isEmpty() && !it.isSending }
        // The turn keeps running: the working state and Stop action stay up.
        assertTrue(viewModel.uiState.value.isWorking)
    }

    @Test
    fun `sending while idle posts a plain prompt, not a steer`() = runBlocking {
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onInputChange("hello")
        viewModel.onSend()

        val request = awaitRequest("/prompts")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/prompts", request.path)
        assertEquals("""{"content":[{"type":"text","text":"hello"}],"model":"kimi-code/k3"}""", request.body.readUtf8())
        // A plain prompt marks the session busy; no steer call follows.
        awaitState(viewModel.uiState) { it.isWorking }
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `steered message echo appears from the socket while the turn continues`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        socket.send(steerEchoSplicedFrame(text = "Stop counting"))

        awaitState(viewModel.uiState) { !it.items.isNullOrEmpty() }
        val items = viewModel.uiState.value.items.orEmpty()
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
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
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
        awaitState(viewModel.uiState) { it.pendingQuestions.isEmpty() }
    }

    @Test
    fun `questions poll in flight does not clear a question that just arrived`() = runBlocking {
        val gate = CountDownLatch(1)
        questionsResponseGate = gate
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()

        // The /questions response is parked server-side: the event lands first.
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

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
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

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
        awaitState(viewModel.uiState) { it.pendingQuestions.isEmpty() }
    }

    @Test
    fun `free text answer posts kind other`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

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
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
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
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

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
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

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
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

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
        awaitState(viewModel.uiState) { it.pendingQuestions.size == 2 }

        viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
        viewModel.onQuestionNext()
        assertEquals(1, viewModel.uiState.value.questionStep)

        socket.send(questionAnsweredFrame("tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.map { q -> q.questionId } == listOf("tool_q2") }
        assertEquals(0, viewModel.uiState.value.questionStep)
    }

    @Test
    fun `dismiss posts to the dismiss endpoint and the card clears on dismissed`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        viewModel.onDismissQuestion()

        val request = awaitRequest(":dismiss")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1/questions/tool_q1:dismiss", request.path)
        assertEquals("{}", request.body.readUtf8())

        socket.send(questionDismissedFrame("tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isEmpty() }
    }

    @Test
    fun `failed answer post keeps the card and emits QuestionFailed`() = runBlocking {
        answersSucceed = false
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        socket.send(questionRequestedFrame(id = "tool_q1"))
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }

        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onQuestionOptionPicked("q_0", "opt_0_1")
            viewModel.onSubmitQuestion()
            awaitRequest("/questions/")
            awaitCondition { effects.isNotEmpty() }

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
        awaitState(viewModel.uiState) { it.pendingQuestions.isNotEmpty() }
        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }
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
            FakeAttachmentStore(),
            subagentStore,
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
            FakeAttachmentStore(),
            subagentStore,
            SavedStateHandle(
                mapOf("sessionId" to "session_1", "cwd" to "/home/ubuntu/kimi-android-client"),
            ),
        )

        assertEquals("kimi-android-client", seeded.uiState.value.sessionTitle)
    }

    @Test
    fun `session title and cwd load into the top bar state`() = runBlocking {
        viewModel.onScreenStarted()

        awaitState(viewModel.uiState) { it.sessionTitle != null }
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

        awaitState(viewModel.uiState) { it.sessionTitle != null }
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

        awaitState(viewModel.uiState) { it.modelsAvailable }
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
        awaitState(viewModel.uiState) { it.modelsAvailable }

        viewModel.onModelSelected("kimi-code/kimi-for-coding-highspeed")

        awaitState(viewModel.uiState) { it.sessionModel == "kimi-code/kimi-for-coding-highspeed" }
        assertEquals("K2.7 Coding Highspeed", viewModel.uiState.value.currentModelLabel)

        // Recreation over the same DataStore: the choice is reloaded, not lost.
        val recreated = ChatViewModel(
            ChatRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }, dataStore),
            SessionsRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }),
            settingsRepository,
            FakeAttachmentStore(),
            subagentStore,
            SavedStateHandle(mapOf("sessionId" to "session_1")),
        )
        startedViewModels += recreated
        recreated.onScreenStarted()
        awaitState(recreated.uiState) { it.modelsAvailable && it.sessionModel != null }
        assertEquals("kimi-code/kimi-for-coding-highspeed", recreated.uiState.value.sessionModel)
        assertEquals("K2.7 Coding Highspeed", recreated.uiState.value.currentModelLabel)
    }

    @Test
    fun `selected effort persists and survives ViewModel recreation`() = runBlocking {
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.modelsAvailable }

        viewModel.onEffortSelected("low")

        awaitState(viewModel.uiState) { it.sessionEffort == "low" }
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
            FakeAttachmentStore(),
            subagentStore,
            SavedStateHandle(mapOf("sessionId" to "session_1")),
        )
        startedViewModels += recreated
        recreated.onScreenStarted()
        awaitState(recreated.uiState) { it.modelsAvailable && it.sessionEffort != null }
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
        awaitState(viewModel.uiState) { it.modelsAvailable && it.sessionModel != null }

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
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        stagePicked("a.txt", 5, "text/plain", "hello".toByteArray())
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
        awaitState(viewModel.uiState) { it.attachments.isEmpty() && it.inputText.isEmpty() && !it.isSending }
    }

    @Test
    fun `send is a no-op while an attachment upload is in flight`() = runBlocking {
        val gate = CountDownLatch(1)
        filesResponseGate = gate
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        stagePicked("a.txt", 5, "text/plain", "hello".toByteArray())
        viewModel.onSend()
        awaitState(viewModel.uiState) {
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
        awaitState(viewModel.uiState) { it.attachments.isEmpty() && !it.isSending }
        // Exactly one upload and one prompt: the second tap sent nothing.
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `failed upload blocks the send and posts no prompt`() = runBlocking {
        uploadsSucceed = false
        viewModel.onScreenStarted()
        awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            stagePicked("a.txt", 5, "text/plain", "hello".toByteArray())
            viewModel.onSend()
            awaitRequest("/api/v1/files")
            awaitCondition { effects.isNotEmpty() }

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
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        stagePicked("a.txt", 5, "text/plain", "hello".toByteArray())
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

    @Test
    fun `over-cap and unreadable picks emit AttachmentPickFailed and stage nothing`() = runBlocking {
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onAttachmentRead(AttachmentStore.ReadResult.TooLarge("big.bin"))
            viewModel.onAttachmentRead(AttachmentStore.ReadResult.Unreadable("broken.bin"))
            awaitCondition { effects.size >= 2 }

            assertEquals(
                listOf(
                    ChatViewModel.Effect.AttachmentPickFailed("big.bin is too large to attach (limit 256 MiB)"),
                    ChatViewModel.Effect.AttachmentPickFailed("Could not read broken.bin"),
                ),
                effects,
            )
            assertEquals(emptyList<ChatViewModel.PendingAttachment>(), viewModel.uiState.value.attachments)
        } finally {
            effectJob.cancel()
        }
    }

    // --- Compact session (ticket 056) ---------------------------------------------------

    @Test
    fun `onCompact posts to the compact endpoint and sets the compacting state`() = runBlocking {
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        viewModel.onCompact()

        val request = awaitRequest(":compact")
        assertEquals("POST", request.method)
        assertEquals("/api/v1/sessions/session_1:compact", request.path)
        assertEquals("{}", request.body.readUtf8())
        awaitState(viewModel.uiState) { it.isCompacting }
    }

    @Test
    fun `onCompact is a no-op while a turn runs or a compaction is in progress`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

        // A running turn disables compact (the server would 40910 it anyway).
        socket.send(turnStartedFrame())
        awaitState(viewModel.uiState) { it.isWorking }
        viewModel.onCompact()
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
        assertFalse(viewModel.uiState.value.isCompacting)

        // A compaction in progress too (a second compact is a silent server no-op).
        socket.send(turnEndedFrame(reason = "completed"))
        awaitState(viewModel.uiState) { !it.isWorking }
        socket.send(compactionStartedFrame())
        awaitState(viewModel.uiState) { it.isCompacting }
        // Drain the turn-end refetch so only a compact POST could arrive next.
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
        viewModel.onCompact()
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a compact POST failure surfaces the server's 40910 message and leaves the state`() = runBlocking {
        // Compacting an empty session: the snackbar shows the server's own sentence.
        compactsSucceed = false
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onScreenStarted()
            awaitState(viewModel.uiState) { it.items != null }
            while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit

            viewModel.onCompact()

            awaitCondition { effects.isNotEmpty() }
            assertEquals(
                ChatViewModel.Effect.CompactionFailed("No messages to compact in current history."),
                effects.single(),
            )
            assertFalse(viewModel.uiState.value.isCompacting)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `compaction frames drive the divider, the state and the snackbars`() = runBlocking {
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_1","session_id":"session_1","role":"user",""" +
            """"content":[{"type":"text","text":"run echo hi"}],""" +
            """"created_at":"2026-07-30T05:44:56.000Z","metadata":{"origin":{"kind":"user"}}}""" +
            """],"has_more":false},"request_id":"r"}"""
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onScreenStarted()
            val socket = awaitServerSocket()
            awaitState(viewModel.uiState) { state -> state.items?.singleOrNull() is ChatItem.UserMessage }

            // Triggered from anywhere (the app, the TUI's /compact): the started frame
            // sets the state and inserts the pending divider at the tail.
            socket.send(compactionStartedFrame())
            awaitState(viewModel.uiState) { state ->
                state.isCompacting &&
                    state.items?.last() == ChatItem.CompactionDivider(LIVE_COMPACTION_DIVIDER_ID, pending = true)
            }

            // The context rewrite, then completion. The frames are processed in order,
            // so once the completion applies, the splice was processed too: the full
            // row list below proves the summary never became a bubble and the kept
            // user message did not duplicate.
            socket.send(compactionSpliceFrame())
            socket.send(compactionCompletedFrame())
            awaitState(viewModel.uiState) { state ->
                !state.isCompacting &&
                    state.items == listOf(
                        ChatItem.UserMessage(
                            "msg_1",
                            "run echo hi",
                            createdAt = Instant.parse("2026-07-30T05:44:56.000Z"),
                        ),
                        ChatItem.CompactionDivider(
                            LIVE_COMPACTION_DIVIDER_ID,
                            pending = false,
                            tokensBefore = 850,
                            tokensAfter = 386,
                            summary = "Completed spike, nothing pending.",
                        ),
                    )
            }
            // The divider flipped to done with the token delta, the snackbar confirms.
            awaitCondition { effects.isNotEmpty() }
            assertEquals(
                ChatViewModel.Effect.CompactionFinished("Context compacted: 850 → 386 tokens"),
                effects.single(),
            )
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `a compaction failure removes the pending divider and emits the error snackbar`() = runBlocking {
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onScreenStarted()
            val socket = awaitServerSocket()
            awaitState(viewModel.uiState) { it.items != null }

            socket.send(compactionStartedFrame())
            awaitState(viewModel.uiState) { it.isCompacting }
            socket.send(compactionFailedFrame())

            // The handler updates the state before sending the effect: once the
            // snackbar arrives, the divider removal is necessarily applied too.
            awaitCondition { effects.isNotEmpty() }
            assertEquals(
                ChatViewModel.Effect.CompactionFailed("summarizer unreachable"),
                effects.single(),
            )
            assertFalse(viewModel.uiState.value.isCompacting)
            assertEquals(
                emptyList<ChatItem.CompactionDivider>(),
                viewModel.uiState.value.items.orEmpty().filterIsInstance<ChatItem.CompactionDivider>(),
            )
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `a compaction_summary history message renders as a divider on cold open`() {
        runBlocking {
            // After killing and reopening the app: the persisted summary (bare text, no
            // token numbers) is a done divider holding the dialog's summary — and the
            // compacting state stays off (no live compaction).
            messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
                """{"id":"msg_2","session_id":"session_1","role":"user",""" +
                """"content":[{"type":"text","text":"Working summary of the task."}],""" +
                """"created_at":"2026-07-30T05:44:56.660Z","metadata":{"origin":{"kind":"compaction_summary"}}},""" +
                """{"id":"msg_1","session_id":"session_1","role":"user",""" +
                """"content":[{"type":"text","text":"run echo hi"}],""" +
                """"created_at":"2026-07-17T04:12:10.000Z","metadata":{"origin":{"kind":"user"}}}""" +
                """],"has_more":false},"request_id":"r"}"""

            viewModel.onScreenStarted()

            awaitState(viewModel.uiState) { state ->
                state.items == listOf(
                    ChatItem.UserMessage("msg_1", "run echo hi", createdAt = Instant.parse("2026-07-17T04:12:10.000Z")),
                    ChatItem.CompactionDivider("msg_2", pending = false, summary = "Working summary of the task."),
                )
            }
            assertFalse(viewModel.uiState.value.isCompacting)
        }
    }

    /** Stages a composer chip the way the pick flow does, without an android.net.Uri
     * (unavailable on JVM unit tests). */
    private suspend fun stagePicked(name: String, sizeBytes: Long, mimeType: String, bytes: ByteArray) =
        viewModel.onAttachmentRead(AttachmentStore.ReadResult.Ok(AttachmentStore.Picked(name, sizeBytes, mimeType, bytes)))

    private suspend fun awaitServerSocket(): WebSocket {
        awaitCondition { serverSocket != null }
        return serverSocket!!
    }

    /** A ViewModel with a non-zero coalescing window (the shared [viewModel] runs with
     * 0 = publish-every-delta). The flush timer never fires in this harness — nothing
     * pumps the test scheduler under runBlocking — so flushes are driven by the
     * substantive events each test sends, deterministically. */
    private fun newViewModel(streamCoalesceWindowMillis: Long): ChatViewModel = ChatViewModel(
        ChatRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }, dataStore),
        SessionsRepository(OkHttpClient(), Json { ignoreUnknownKeys = true }),
        settingsRepository,
        FakeAttachmentStore(),
        subagentStore,
        SavedStateHandle(mapOf("sessionId" to "session_1")),
        streamCoalesceWindowMillis = streamCoalesceWindowMillis,
    ).also { startedViewModels += it }

    /** Records the distinct last-assistant-bubble texts published to the UiState, in
     * order — the observable trace of the delta conflation. Cancel the job in finally.
     *
     * The recorder LAGS the StateFlow by design (ticket 049): its Unconfined collector
     * is resumed from `_uiState.update` calls that themselves run inside the socket
     * collect's inline resume on the OkHttp reader thread; `executeUnconfined` then
     * ENQUEUES the collector to that thread's unconfined queue (stack-overflow
     * avoidance) instead of running it inline, so the append lands only when the frame
     * processing unwinds and the queue drains. The append always lands — the latest
     * state at drain time carries the accumulated text — but never synchronously with
     * the emission. Callers must therefore AWAIT the expected sequence
     * (`awaitCondition { texts == ... }`, hard timeout) instead of asserting the list
     * at an arbitrary instant; a genuinely lost emission still fails the test. */
    private fun CoroutineScope.assistantTextRecorder(
        viewModel: ChatViewModel,
    ): Pair<CopyOnWriteArrayList<String>, Job> {
        val texts = CopyOnWriteArrayList<String>()
        val job = launch(Dispatchers.Unconfined) {
            viewModel.uiState.collect { state ->
                val text = state.items?.filterIsInstance<ChatItem.AssistantMessage>()?.lastOrNull()?.text
                if (text != null && texts.lastOrNull() != text) texts += text
            }
        }
        return texts to job
    }

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

    /** Verbatim 0.29.0 volatile streaming frame (docs/server-api.md, assistant.delta). */
    private fun assistantDeltaFrame(delta: String, seq: Int): String =
        """{"type":"assistant.delta","seq":$seq,"epoch":"ep_1","volatile":true,"offset":0,""" +
            """"session_id":"session_1","timestamp":"2026-07-19T00:00:00.000Z",""" +
            """"payload":{"type":"assistant.delta","turnId":1,"delta":"$delta","agentId":"main","sessionId":"session_1"}}"""

    private fun turnEndedFrame(reason: String): String =
        """{"type":"turn.ended","seq":21,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-19T00:00:01.000Z","payload":{"type":"turn.ended","turnId":1,""" +
            """"reason":"$reason","durationMs":1000,"agentId":"main","sessionId":"session_1"}}"""

    /** Verbatim 0.31.0 frame (docs/server-api.md, Step retrying — ticket 058): an LLM
     * call retried on provider 429; delayMs is a double on the wire. */
    private fun stepRetryingFrame(nextAttempt: Int): String =
        """{"type":"turn.step.retrying","seq":21,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-30T00:00:00.000Z","payload":{"type":"turn.step.retrying","turnId":1,""" +
            """"step":1,"stepId":"d09585d5-old","failedAttempt":${nextAttempt - 1},"nextAttempt":$nextAttempt,""" +
            """"maxAttempts":10,"delayMs":592.08,"errorName":"APIProviderRateLimitError",""" +
            """"errorMessage":"429 The engine is currently overloaded, please try again later","statusCode":429,""" +
            """"agentId":"main","sessionId":"session_1"}}"""

    /** The replacement step start after a retry (docs/server-api.md): new stepId, same step. */
    private fun stepStartedFrame(step: Int, stepId: String): String =
        """{"type":"turn.step.started","seq":22,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-30T00:00:00.600Z","payload":{"type":"turn.step.started","turnId":1,""" +
            """"step":$step,"stepId":"$stepId","agentId":"main","sessionId":"session_1"}}"""

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

    /** Verbatim 0.29.0 model/usage variant (docs/server-api.md, Agent status). */
    private fun agentStatusUsageFrame(used: Long, max: Long): String =
        """{"type":"agent.status.updated","seq":30,"epoch":"ep_1","volatile":true,""" +
            """"session_id":"session_1","timestamp":"2026-07-26T00:00:00.000Z",""" +
            """"payload":{"type":"agent.status.updated","model":"kimi-code/k3","thinkingEffort":"high",""" +
            """"maxContextTokens":$max,"usage":{},"contextTokens":$used,""" +
            """"agentId":"main","sessionId":"session_1"}}"""

    /** Verbatim 0.29.0 phase variant: no token counts, must not clobber the ring. */
    private fun agentStatusPhaseFrame(): String =
        """{"type":"agent.status.updated","seq":31,"epoch":"ep_1","volatile":true,""" +
            """"session_id":"session_1","timestamp":"2026-07-26T00:00:01.000Z",""" +
            """"payload":{"type":"agent.status.updated",""" +
            """"phase":{"kind":"running","turnId":0,"step":0,"stepId":"","since":1784777223312},""" +
            """"agentId":"main","sessionId":"session_1"}}"""

    /** Verbatim 0.29.2 subagent.spawned (docs/server-api.md, Sub-agents). */
    private fun subagentSpawnedFrame(): String =
        """{"type":"subagent.spawned","seq":19,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-28T03:37:35.492Z","payload":{"type":"subagent.spawned",""" +
            """"subagentId":"agent-12","subagentName":"explore","parentToolCallId":"tool_agent",""" +
            """"parentAgentId":"main","callerAgentId":"main","description":"doc-verify",""" +
            """"runInBackground":false,"agentId":"main","sessionId":"session_1"}}"""

    private fun subagentStartedFrame(): String =
        """{"type":"subagent.started","seq":22,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-28T03:37:35.600Z","payload":{"type":"subagent.started",""" +
            """"subagentId":"agent-12","agentId":"main","sessionId":"session_1"}}"""

    private fun subagentCompletedFrame(): String =
        """{"type":"subagent.completed","seq":33,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-28T03:37:51.951Z","payload":{"type":"subagent.completed",""" +
            """"subagentId":"agent-12","resultSummary":"done","usage":{},"contextTokens":14358,""" +
            """"agentId":"main","sessionId":"session_1"}}"""

    private fun subTurnStartedFrame(): String =
        """{"type":"turn.started","seq":20,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-28T03:37:35.493Z","payload":{"type":"turn.started","turnId":0,""" +
            """"origin":{"kind":"system_trigger","name":"subagent"},"agentId":"agent-12","sessionId":"session_1"}}"""

    /** The sub-agent's first internal prompt, spliced as a user message (docs/server-api.md). */
    private fun subContextSplicedFrame(): String =
        """{"type":"context.spliced","seq":21,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-28T03:37:35.494Z","payload":{"type":"context.spliced","start":0,""" +
            """"deleteCount":0,"messages":[{"role":"user","content":[{"type":"text","text":"sub task"}],""" +
            """"toolCalls":[],"origin":{"kind":"system_trigger","name":"subagent"},"id":"msg_sub"}],""" +
            """"agentId":"agent-12","sessionId":"session_1"}}"""

    private fun subAssistantDeltaFrame(delta: String): String =
        """{"type":"assistant.delta","seq":23,"epoch":"ep_1","volatile":true,"offset":0,""" +
            """"session_id":"session_1","timestamp":"2026-07-28T03:37:36.100Z",""" +
            """"payload":{"type":"assistant.delta","turnId":0,"delta":"$delta","agentId":"agent-12","sessionId":"session_1"}}"""

    /** Verbatim 0.30.0 compaction.started (docs/server-api.md, Compact — ticket 056). */
    private fun compactionStartedFrame(): String =
        """{"type":"compaction.started","seq":25,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-30T05:48:54.194Z","payload":{"type":"compaction.started",""" +
            """"trigger":"manual","agentId":"main","sessionId":"session_1"}}"""

    /** Verbatim 0.30.0 compaction.completed (docs/server-api.md, Compact — ticket 056). */
    private fun compactionCompletedFrame(): String =
        """{"type":"compaction.completed","seq":37,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-30T05:44:56.676Z","payload":{"type":"compaction.completed",""" +
            """"result":{"summary":"Completed spike, nothing pending.",""" +
            """"compactedCount":8,"tokensBefore":850,"tokensAfter":386,"keptUserMessageCount":3},""" +
            """"agentId":"main","sessionId":"session_1"}}"""

    /** Unverified shape (protocol bundle only): the permissive decode must still emit. */
    private fun compactionFailedFrame(): String =
        """{"type":"compaction.failed","seq":40,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-30T05:50:00.000Z","payload":{"type":"compaction.failed",""" +
            """"message":"summarizer unreachable","agentId":"main","sessionId":"session_1"}}"""

    /** The compaction context rewrite (docs/server-api.md, Compact): the kept user
     * message verbatim with its original id, then the id-less prefixed summary. */
    private fun compactionSpliceFrame(): String =
        """{"type":"context.spliced","seq":36,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-30T05:44:56.600Z","payload":{"type":"context.spliced",""" +
            """"start":0,"deleteCount":8,"messages":[""" +
            """{"role":"user","content":[{"type":"text","text":"run echo hi"}],""" +
            """"toolCalls":[],"origin":{"kind":"user"},"id":"msg_1"},""" +
            """{"role":"user","content":[{"type":"text","text":"The conversation so far has been compacted…"}],""" +
            """"toolCalls":[],"origin":{"kind":"compaction_summary"},"id":null}],""" +
            """"agentId":"main","sessionId":"session_1"}}"""
}
