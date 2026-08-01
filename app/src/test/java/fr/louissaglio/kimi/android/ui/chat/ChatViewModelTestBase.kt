package fr.louissaglio.kimi.android.ui.chat

import android.net.Uri
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.viewModelScope
import fr.louissaglio.kimi.android.data.attachments.AttachmentStore
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.navigation.Chat
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.testChatRepository
import fr.louissaglio.kimi.android.testutil.testSessionsRepository
import fr.louissaglio.kimi.android.testutil.testSubagentStreamStore
import fr.louissaglio.kimi.android.testutil.webSocketUpgradeResponse
import fr.louissaglio.kimi.android.testutil.testTodoParser
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
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before

/**
 * Shared harness of the chat-screen test classes (ChatViewModelTest, ChatAttachmentsTest,
 * QuestionWizardTest — ticket 072's split of the old single class): ViewModel tests run
 * against the real [ChatRepository] on a [MockWebServer], so the WS frame parsing and the
 * decision POST are covered end-to-end. The socket is real I/O, so waits use real-time
 * timeouts (same pattern as ChatRepositoryTest), not runTest's clock. Determinism comes
 * from the dispatcher, not from timing margins: the WS upgrade is served only once the
 * startup GET burst has gone quiet ([startupQuietNanos]), so a poll response can never
 * reorder after a socket event the test already sent — whatever the exact set of startup
 * GETs is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class ChatViewModelTestBase {

    protected val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    protected lateinit var viewModel: ChatViewModel
    protected lateinit var settingsRepository: SettingsRepository
    protected lateinit var dataStore: FakePreferencesDataStore

    /** 0 ms window: sub-agent frames apply on arrival, no flush timer involved. */
    protected val subagentStore = testSubagentStreamStore(streamCoalesceWindowMillis = 0)

    @Volatile
    protected var approvalsSucceed = true

    @Volatile
    protected var abortsSucceed = true

    @Volatile
    protected var answersSucceed = true

    @Volatile
    protected var uploadsSucceed = true

    @Volatile
    protected var compactsSucceed = true

    @Volatile
    protected var questionsBody =
        """{"code":0,"msg":"success","data":{"items":[]},"request_id":"r"}"""

    @Volatile
    protected var messagesBody =
        """{"code":0,"msg":"success","data":{"items":[],"has_more":false},"request_id":"r"}"""

    /** Served for /messages requests carrying a `before_id` cursor (ticket 043). */
    @Volatile
    protected var olderMessagesBody =
        """{"code":0,"msg":"success","data":{"items":[],"has_more":false},"request_id":"r"}"""

    @Volatile
    protected var sessionBody =
        """{"code":0,"msg":"success","data":{"id":"session_1","title":"Fix the bug",""" +
            """"updated_at":"2026-07-20T00:00:00.000Z","busy":false,""" +
            """"metadata":{"cwd":"/home/ubuntu/kimi-android-client"}},"request_id":"r"}"""

    /** The live 0.29.0 model list (docs/server-api.md): two 262k models and K3 (1M). */
    @Volatile
    protected var modelsBody =
        """{"code":0,"msg":"success","data":{"items":[""" +
            """{"provider":"managed:kimi-code","model":"kimi-code/kimi-for-coding",""" +
            """"display_name":"K2.7 Coding","max_context_size":262144},""" +
            """{"provider":"managed:kimi-code","model":"kimi-code/kimi-for-coding-highspeed",""" +
            """"display_name":"K2.7 Coding Highspeed","max_context_size":262144},""" +
            """{"provider":"managed:kimi-code","model":"kimi-code/k3","display_name":"K3",""" +
            """"max_context_size":1048576,"support_efforts":["low","high","max"],"default_effort":"high"}""" +
            """]},"request_id":"r"}"""

    @Volatile
    protected var configBody =
        """{"code":0,"msg":"success","data":{"default_model":"kimi-code/k3"},"request_id":"r"}"""

    @Volatile
    protected var statusBody =
        """{"code":0,"msg":"success","data":{"busy":false,"context_tokens":0,""" +
            """"max_context_tokens":1048576,"context_usage":0.0},"request_id":"r"}"""

    @Volatile
    protected var serverSocket: WebSocket? = null

    /** Every ViewModel whose screen was started in this test. Their viewModelScopes
     * (socket collect + reconnect backoff, gate collector, flush timer) must be
     * cancelled in [tearDown]: left running, a leaked socket coroutine outlives the
     * test and touches Main after [Dispatchers.resetMain] — observed as
     * "Dispatchers.Main is used concurrently with setting it", and as a
     * "Module with the Main dispatcher had failed to initialize" crash on an OkHttp
     * thread when a leaked connection failure resumed between tests (ticket 049). */
    protected val startedViewModels = CopyOnWriteArrayList<ChatViewModel>()

    /** Every frame the client sent over any WS connection of the test (ticket 048
     * asserts the resume subscribe carries the stored cursor after a gate cycle). */
    protected val clientFrames = CopyOnWriteArrayList<String>()

    /**
     * Arrival time (System.nanoTime) of the latest non-WS GET the server dispatched —
     * every path counts, unconditionally, so a new, moved, or suppressed startup GET
     * needs no change here. Seeded in [setUp] so an upgrade arriving before any GET
     * still waits out the quiet window.
     */
    private val lastGetArrivalNanos = AtomicLong()

    /** Quiet window after the last startup GET before the WS upgrade is served: long
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
    protected var statusResponseGate: CountDownLatch? = null

    /** Same as [statusResponseGate], for the /questions poll. */
    @Volatile
    protected var questionsResponseGate: CountDownLatch? = null

    /** Same as [statusResponseGate], for the /api/v1/files upload (ticket 033): parks
     * the response so a test can observe the send while the upload is in flight. */
    @Volatile
    protected var filesResponseGate: CountDownLatch? = null

    /** The store's platform I/O never runs on the JVM: tests stage chips (and exercise
     * the failure effects) through [ChatViewModel.onAttachmentRead] directly. */
    protected class FakeAttachmentStore : AttachmentStore {
        override fun read(uri: Uri): AttachmentStore.ReadResult = throw UnsupportedOperationException()

        override fun writeToCache(name: String, bytes: ByteArray): Uri? = throw UnsupportedOperationException()
    }

    @Before
    fun setUp() {
        server.start()
        lastGetArrivalNanos.set(System.nanoTime())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target.orEmpty()
                // Stamped at arrival, not at response time: the /status and /questions
                // responses may be parked behind a test gate below, and the WS upgrade's
                // quiet window must not wait for those. The WS path is itself a GET but
                // is excluded — the window measures the poll burst only.
                if (request.method == "GET" && !path.startsWith("/api/v1/ws")) {
                    lastGetArrivalNanos.set(System.nanoTime())
                }
                return when {
                    path == "/api/v1/sessions/session_1" && request.method == "GET" ->
                        MockResponse(body = sessionBody)
                    path.startsWith("/api/v1/sessions/session_1/messages") ->
                        MockResponse(body = if ("before_id" in path) olderMessagesBody else messagesBody)
                    path == "/api/v1/sessions/session_1/status" -> {
                        statusResponseGate?.await(5, TimeUnit.SECONDS)
                        MockResponse(body = statusBody)
                    }
                    path == "/api/v1/sessions/session_1/prompts" -> MockResponse(body = 
                        """{"code":0,"msg":"success","data":{"prompt_id":"msg_1","user_message_id":"msg_1",""" +
                            """"status":"running","created_at":"2026-07-20T00:00:00.000Z"},"request_id":"r"}""",
                    )
                    path == "/api/v1/sessions/session_1/prompts:steer" -> MockResponse(body = 
                        """{"code":0,"msg":"success","data":{"steered":true,"prompt_ids":["msg_1"]},"request_id":"r"}""",
                    )
                    path == "/api/v1/sessions/session_1:compact" ->
                        if (compactsSucceed) {
                            MockResponse(body = 
                                """{"code":0,"msg":"success","data":{},"request_id":"r"}""",
                            )
                        } else {
                            MockResponse(body = 
                                """{"code":40910,"msg":"No messages to compact in current history.",""" +
                                    """"data":null,"request_id":"r"}""",
                            )
                        }
                    path == "/api/v1/sessions/session_1:abort" ->
                        if (abortsSucceed) {
                            MockResponse(body = 
                                """{"code":0,"msg":"success","data":{"aborted":true},"request_id":"r"}""",
                            )
                        } else {
                            MockResponse(body = 
                                """{"code":40001,"msg":"no running prompt","data":null,"request_id":"r"}""",
                            )
                        }
                    path.startsWith("/api/v1/sessions/session_1/approvals/") ->
                        if (approvalsSucceed) {
                            MockResponse(body = 
                                """{"code":0,"msg":"success","data":{"resolved":true,"resolved_at":"2026-07-19T00:00:01.000Z"},"request_id":"r"}""",
                            )
                        } else {
                            MockResponse(body = 
                                """{"code":40401,"msg":"approval does not exist","data":null,"request_id":"r"}""",
                            )
                        }
                    path.startsWith("/api/v1/sessions/session_1/questions") -> when {
                        path.endsWith(":dismiss") ->
                            if (answersSucceed) {
                                MockResponse(body = 
                                    """{"code":40909,"msg":"question tool_q1 dismissed",""" +
                                        """"data":{"dismissed":true,"dismissed_at":"2026-07-20T06:17:24.711Z"},"request_id":"r"}""",
                                )
                            } else {
                                MockResponse(body = 
                                    """{"code":40405,"msg":"question tool_q1 not found","data":null,"request_id":"r"}""",
                                )
                            }
                        request.method == "POST" ->
                            if (answersSucceed) {
                                MockResponse(body = 
                                    """{"code":0,"msg":"success","data":{"resolved":true,""" +
                                        """"resolved_at":"2026-07-20T06:08:12.548Z"},"request_id":"r"}""",
                                )
                            } else {
                                MockResponse(body = 
                                    """{"code":40405,"msg":"question tool_q1 not found","data":null,"request_id":"r"}""",
                                )
                            }
                        else -> {
                            questionsResponseGate?.await(5, TimeUnit.SECONDS)
                            MockResponse(body = questionsBody)
                        }
                    }
                    path == "/api/v1/files" -> {
                        filesResponseGate?.await(5, TimeUnit.SECONDS)
                        if (uploadsSucceed) {
                            MockResponse(body = 
                                """{"code":0,"msg":"success","data":{"id":"f_1","name":"a.txt",""" +
                                    """"media_type":"text/plain","size":5,""" +
                                    """"created_at":"2026-07-24T00:00:00.000Z"},"request_id":"r"}""",
                            )
                        } else {
                            MockResponse(body = 
                                """{"code":40001,"msg":"unsupported media type","data":null,"request_id":"r"}""",
                            )
                        }
                    }
                    path == "/api/v1/models" -> MockResponse(body = modelsBody)
                    path == "/api/v1/config" -> MockResponse(body = configBody)
                    path.startsWith("/api/v1/ws") -> {
                        awaitStartupGetsQuiet()
                        webSocketUpgradeResponse(
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
                    else -> MockResponse(code = 404)
                }
            }
        }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        dataStore = FakePreferencesDataStore(
            preferencesOf(stringPreferencesKey("server_url") to server.url("/").toString()),
        )
        settingsRepository = SettingsRepository(dataStore, appScope)
        viewModel = ChatViewModel(
            testChatRepository(dataStore),
            testSessionsRepository(),
            settingsRepository,
            FakeAttachmentStore(),
            subagentStore,
            Chat("session_1"),
            todoParser = testTodoParser(),
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
        server.close()
    }

    protected suspend fun awaitServerSocket(): WebSocket {
        awaitCondition { serverSocket != null }
        return serverSocket!!
    }

    /** A ViewModel with a non-zero coalescing window (the shared [viewModel] runs with
     * 0 = publish-every-delta). The flush timer never fires in this harness — nothing
     * pumps the test scheduler under runBlocking — so flushes are driven by the
     * substantive events each test sends, deterministically. */
    protected fun newViewModel(streamCoalesceWindowMillis: Long): ChatViewModel = ChatViewModel(
        testChatRepository(dataStore),
        testSessionsRepository(),
        settingsRepository,
        FakeAttachmentStore(),
        subagentStore,
        Chat("session_1"),
        todoParser = testTodoParser(),
        streamCoalesceWindowMillis = streamCoalesceWindowMillis,
    ).also { startedViewModels += it }

    /** Records the distinct last-assistant-bubble texts published to the UiState, in
     * order — the observable trace of the delta conflation. Cancel the job in finally.
     *
     * The recorder LAGS the StateFlow by design (ticket 049): its Unconfined collector
     * is resumed from `uiState.update` calls that themselves run inside the socket
     * collect's inline resume on the OkHttp reader thread; `executeUnconfined` then
     * ENQUEUES the collector to that thread's unconfined queue (stack-overflow
     * avoidance) instead of running it inline, so the append lands only when the frame
     * processing unwinds and the queue drains. The append always lands — the latest
     * state at drain time carries the accumulated text — but never synchronously with
     * the emission. Callers must therefore AWAIT the expected sequence
     * (`awaitCondition { texts == ... }`, hard timeout) instead of asserting the list
     * at an arbitrary instant; a genuinely lost emission still fails the test. */
    protected fun CoroutineScope.assistantTextRecorder(
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
    protected fun awaitRequest(pathSubstring: String): RecordedRequest {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val request = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: continue
            if (request.target.orEmpty().contains(pathSubstring)) return request
        }
        error("Timed out waiting for request to $pathSubstring")
    }

    protected fun turnStartedFrame(): String =
        """{"type":"turn.started","seq":20,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-19T00:00:00.000Z","payload":{"type":"turn.started","turnId":1,""" +
            """"origin":{"kind":"user"},"agentId":"main","sessionId":"session_1"}}"""

    /** Verbatim 0.29.0 volatile streaming frame (docs/server-api.md, assistant.delta). */
    protected fun assistantDeltaFrame(delta: String, seq: Int): String =
        """{"type":"assistant.delta","seq":$seq,"epoch":"ep_1","volatile":true,"offset":0,""" +
            """"session_id":"session_1","timestamp":"2026-07-19T00:00:00.000Z",""" +
            """"payload":{"type":"assistant.delta","turnId":1,"delta":"$delta","agentId":"main","sessionId":"session_1"}}"""

    protected fun turnEndedFrame(reason: String): String =
        """{"type":"turn.ended","seq":21,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-19T00:00:01.000Z","payload":{"type":"turn.ended","turnId":1,""" +
            """"reason":"$reason","durationMs":1000,"agentId":"main","sessionId":"session_1"}}"""

    /** Verbatim 0.31.0 frame (docs/server-api.md, Step retrying — ticket 058): an LLM
     * call retried on provider 429; delayMs is a double on the wire. */
    protected fun stepRetryingFrame(nextAttempt: Int): String =
        """{"type":"turn.step.retrying","seq":21,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-30T00:00:00.000Z","payload":{"type":"turn.step.retrying","turnId":1,""" +
            """"step":1,"stepId":"d09585d5-old","failedAttempt":${nextAttempt - 1},"nextAttempt":$nextAttempt,""" +
            """"maxAttempts":10,"delayMs":592.08,"errorName":"APIProviderRateLimitError",""" +
            """"errorMessage":"429 The engine is currently overloaded, please try again later","statusCode":429,""" +
            """"agentId":"main","sessionId":"session_1"}}"""

    /** The replacement step start after a retry (docs/server-api.md): new stepId, same step. */
    protected fun stepStartedFrame(step: Int, stepId: String): String =
        """{"type":"turn.step.started","seq":22,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-30T00:00:00.600Z","payload":{"type":"turn.step.started","turnId":1,""" +
            """"step":$step,"stepId":"$stepId","agentId":"main","sessionId":"session_1"}}"""

    protected fun contextSplicedFrame(id: String, text: String): String =
        """{"type":"context.spliced","seq":6,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-19T00:00:00.500Z","payload":{"type":"context.spliced","start":0,""" +
            """"deleteCount":0,"messages":[{"role":"user","content":[{"type":"text","text":"$text"}],""" +
            """"toolCalls":[],"origin":{"kind":"user"},"id":"$id"}],"agentId":"main","sessionId":"session_1"}}"""

    /** Steer echo shape: the spliced steered message carries no id (docs/server-api.md). */
    protected fun steerEchoSplicedFrame(text: String): String =
        """{"type":"context.spliced","seq":12,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-20T00:00:01.000Z","payload":{"type":"context.spliced","start":1,""" +
            """"deleteCount":0,"messages":[{"role":"user","content":[{"type":"text","text":"$text"}],""" +
            """"toolCalls":[],"origin":{"kind":"user"}}],"agentId":"main","sessionId":"session_1"}}"""

    protected fun approvalRequestedFrame(id: String, tool: String, action: String, command: String): String =
        """{"type":"event.approval.requested","seq":7,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-19T00:00:00.000Z","payload":{"type":"event.approval.requested",""" +
            """"sessionId":"session_1","agentId":"main","approval_id":"$id","session_id":"session_1",""" +
            """"turn_id":0,"tool_call_id":"tool_1","tool_name":"$tool","action":"$action",""" +
            """"tool_input_display":{"kind":"command","command":"$command","cwd":"/tmp","language":"bash"},""" +
            """"created_at":"2026-07-19T00:00:00.000Z","expires_at":"2026-07-19T00:01:00.000Z"}}"""

    protected fun approvalResolvedFrame(id: String): String =
        """{"type":"event.approval.resolved","seq":8,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-19T00:00:00.500Z","payload":{"type":"event.approval.resolved",""" +
            """"approval_id":"$id","decision":"approved","resolved_at":"2026-07-19T00:00:00.500Z"}}"""

    /** Verbatim 0.29.0 model/usage variant (docs/server-api.md, Agent status). */
    protected fun agentStatusUsageFrame(used: Long, max: Long): String =
        """{"type":"agent.status.updated","seq":30,"epoch":"ep_1","volatile":true,""" +
            """"session_id":"session_1","timestamp":"2026-07-26T00:00:00.000Z",""" +
            """"payload":{"type":"agent.status.updated","model":"kimi-code/k3","thinkingEffort":"high",""" +
            """"maxContextTokens":$max,"usage":{},"contextTokens":$used,""" +
            """"agentId":"main","sessionId":"session_1"}}"""

    /** Verbatim 0.29.0 phase variant: no token counts, must not clobber the ring. */
    protected fun agentStatusPhaseFrame(): String =
        """{"type":"agent.status.updated","seq":31,"epoch":"ep_1","volatile":true,""" +
            """"session_id":"session_1","timestamp":"2026-07-26T00:00:01.000Z",""" +
            """"payload":{"type":"agent.status.updated",""" +
            """"phase":{"kind":"running","turnId":0,"step":0,"stepId":"","since":1784777223312},""" +
            """"agentId":"main","sessionId":"session_1"}}"""

    /** Verbatim 0.29.2 subagent.spawned (docs/server-api.md, Sub-agents). */
    protected fun subagentSpawnedFrame(): String =
        """{"type":"subagent.spawned","seq":19,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-28T03:37:35.492Z","payload":{"type":"subagent.spawned",""" +
            """"subagentId":"agent-12","subagentName":"explore","parentToolCallId":"tool_agent",""" +
            """"parentAgentId":"main","callerAgentId":"main","description":"doc-verify",""" +
            """"runInBackground":false,"agentId":"main","sessionId":"session_1"}}"""

    protected fun subagentStartedFrame(): String =
        """{"type":"subagent.started","seq":22,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-28T03:37:35.600Z","payload":{"type":"subagent.started",""" +
            """"subagentId":"agent-12","agentId":"main","sessionId":"session_1"}}"""

    protected fun subagentCompletedFrame(): String =
        """{"type":"subagent.completed","seq":33,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-28T03:37:51.951Z","payload":{"type":"subagent.completed",""" +
            """"subagentId":"agent-12","resultSummary":"done","usage":{},"contextTokens":14358,""" +
            """"agentId":"main","sessionId":"session_1"}}"""

    protected fun subTurnStartedFrame(): String =
        """{"type":"turn.started","seq":20,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-28T03:37:35.493Z","payload":{"type":"turn.started","turnId":0,""" +
            """"origin":{"kind":"system_trigger","name":"subagent"},"agentId":"agent-12","sessionId":"session_1"}}"""

    /** The sub-agent's first internal prompt, spliced as a user message (docs/server-api.md). */
    protected fun subContextSplicedFrame(): String =
        """{"type":"context.spliced","seq":21,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-28T03:37:35.494Z","payload":{"type":"context.spliced","start":0,""" +
            """"deleteCount":0,"messages":[{"role":"user","content":[{"type":"text","text":"sub task"}],""" +
            """"toolCalls":[],"origin":{"kind":"system_trigger","name":"subagent"},"id":"msg_sub"}],""" +
            """"agentId":"agent-12","sessionId":"session_1"}}"""

    protected fun subAssistantDeltaFrame(delta: String): String =
        """{"type":"assistant.delta","seq":23,"epoch":"ep_1","volatile":true,"offset":0,""" +
            """"session_id":"session_1","timestamp":"2026-07-28T03:37:36.100Z",""" +
            """"payload":{"type":"assistant.delta","turnId":0,"delta":"$delta","agentId":"agent-12","sessionId":"session_1"}}"""

    /** Verbatim 0.30.0 compaction.started (docs/server-api.md, Compact — ticket 056). */
    protected fun compactionStartedFrame(): String =
        """{"type":"compaction.started","seq":25,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-30T05:48:54.194Z","payload":{"type":"compaction.started",""" +
            """"trigger":"manual","agentId":"main","sessionId":"session_1"}}"""

    /** Verbatim 0.30.0 compaction.completed (docs/server-api.md, Compact — ticket 056). */
    protected fun compactionCompletedFrame(): String =
        """{"type":"compaction.completed","seq":37,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-30T05:44:56.676Z","payload":{"type":"compaction.completed",""" +
            """"result":{"summary":"Completed spike, nothing pending.",""" +
            """"compactedCount":8,"tokensBefore":850,"tokensAfter":386,"keptUserMessageCount":3},""" +
            """"agentId":"main","sessionId":"session_1"}}"""

    /** Unverified shape (protocol bundle only): the permissive decode must still emit. */
    protected fun compactionFailedFrame(): String =
        """{"type":"compaction.failed","seq":40,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-30T05:50:00.000Z","payload":{"type":"compaction.failed",""" +
            """"message":"summarizer unreachable","agentId":"main","sessionId":"session_1"}}"""

    /** The compaction context rewrite (docs/server-api.md, Compact): the kept user
     * message verbatim with its original id, then the id-less prefixed summary. */
    protected fun compactionSpliceFrame(): String =
        """{"type":"context.spliced","seq":36,"epoch":"ep_1","session_id":"session_1",""" +
            """"timestamp":"2026-07-30T05:44:56.600Z","payload":{"type":"context.spliced",""" +
            """"start":0,"deleteCount":8,"messages":[""" +
            """{"role":"user","content":[{"type":"text","text":"run echo hi"}],""" +
            """"toolCalls":[],"origin":{"kind":"user"},"id":"msg_1"},""" +
            """{"role":"user","content":[{"type":"text","text":"The conversation so far has been compacted…"}],""" +
            """"toolCalls":[],"origin":{"kind":"compaction_summary"},"id":null}],""" +
            """"agentId":"main","sessionId":"session_1"}}"""
}
