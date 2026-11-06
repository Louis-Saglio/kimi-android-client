package fr.louissaglio.kimi.android.ui.chat

import android.net.Uri
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.viewModelScope
import fr.louissaglio.kimi.android.data.attachments.AttachmentStore
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.sessions.UnreadSessionsStore
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.navigation.Chat
import fr.louissaglio.kimi.android.testutil.TranscriptFrames
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.testChatRepository
import fr.louissaglio.kimi.android.testutil.testSessionsRepository
import fr.louissaglio.kimi.android.testutil.testColdStart
import fr.louissaglio.kimi.android.testutil.testSubagentStreamStore
import fr.louissaglio.kimi.android.testutil.webSocketUpgradeResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.createTempDirectory
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    protected val unreadStore = UnreadSessionsStore(createTempDirectory("chat-view-model-unread-test").toFile(), Json { ignoreUnknownKeys = true })

    /** 0 ms window: sub-agent frames apply on arrival, no flush timer involved.
     * Shares the test's ChatRepository/TranscriptColdStart (the production singleton
     * shape): its release → forget lands on the SAME cold start the ViewModels use. */
    protected lateinit var subagentStore: SubagentStreamStore

    /** Shared by the ViewModels and the cold start (the production singleton shape):
     * one ChatRepository — one SessionSocket, one watermark map — so the seeded resume
     * watermark lands on the very socket the ViewModel collects. */
    protected lateinit var chatRepository: ChatRepository

    /** The session's cold-start owner (ticket 176), shared by every started ViewModel. */
    protected lateinit var coldStart: TranscriptColdStart

    @Volatile
    protected var approvalsSucceed = true

    @Volatile
    protected var abortsSucceed = true

    /** When set, a question response (`"kind":"question"` on `.../interactions/{id}` —
     * ticket 172) answers this envelope instead of the `{outcome, ended_at}` success. */
    @Volatile
    protected var questionFailureBody: String? = null

    @Volatile
    protected var uploadsSucceed = true

    @Volatile
    protected var compactsSucceed = true

    @Volatile
    protected var forksSucceed = true

    /** When set, `/prompts` answers this envelope body instead of the ack — the
     * 40931/40932 recovery tests (tickets 168/169). */
    @Volatile
    protected var promptFailureBody: String? = null

    @Volatile
    protected var reloadsSucceed = true

    @Volatile
    protected var takeoversSucceed = true

    @Volatile
    protected var messagesBody =
        """{"code":0,"msg":"success","data":{"items":[],"has_more":false},"request_id":"r"}"""

    /**
     * The served main-agent `GET .../transcript` body (ticket 176 — the cold-start read
     * model: the newest turn plus the session's pending interactions, prompt entities,
     * agent roster and `seq` watermark). When null (the default), an idle transcript
     * with no turn, nothing pending, the main agent alone and watermark seq 1. Set it
     * via [transcriptData] to exercise a running turn, interactions or the roster.
     */
    @Volatile
    protected var transcriptBody: String? = null

    /** Parks the main transcript response so a test can make a socket event win over
     * the in-flight cold start on purpose. */
    @Volatile
    protected var transcriptResponseGate: CountDownLatch? = null

    /** The served `GET .../prompts` queue (ticket 176): idle by default. */
    @Volatile
    protected var promptQueueBody =
        """{"code":0,"msg":"success","data":{"active":null,"queued":[]},"request_id":"r"}"""

    /** The served `GET .../transcript/ops` catch-up probe (ticket 176): a journal that
     * covers any watermark by default. */
    @Volatile
    protected var catchUpBody =
        """{"code":0,"msg":"success","data":{"agent_id":"main","batches":[],"latest_seq":1000000,"complete":true},"request_id":"r"}"""

    /** A main transcript envelope: [items] the turn/marker array, the rest the global
     * state (raw JSON). */
    protected fun transcriptData(
        items: String = "[]",
        interactions: String = "[]",
        pendingInteractions: String = "[]",
        prompts: String = "[]",
        agents: String = """[{"agentId":"main","type":"main"}]""",
        seq: Long? = 1,
    ): String = """{"code":0,"msg":"success","data":{"agent_id":"main","items":$items,"has_more":false,""" +
        """"interactions":$interactions,"pending_interactions":$pendingInteractions,""" +
        """"prompts":$prompts,"agents":$agents${seq?.let { ""","seq":$it""" }.orEmpty()}},"request_id":"r"}"""

    private val testJson = Json { ignoreUnknownKeys = true }

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

    /** The `model_switch_confirm` answer (ticket 167); null config = no gate, like a
     * server without the cloud policy. */
    @Volatile
    protected var modelSwitchConfigBody =
        """{"code":0,"msg":"success","data":{"name":"model_switch_confirm","config":null},"request_id":"r"}"""

    /** The `effort_switch_confirm` answer (ticket 170); null config = no policy. */
    @Volatile
    protected var effortSwitchConfigBody =
        """{"code":0,"msg":"success","data":{"name":"effort_switch_confirm","config":null},"request_id":"r"}"""

    /** Every request target the server dispatched, in arrival order — unlike
     * [awaitRequest] it is never drained, so a test can assert a route was NEVER hit
     * (ticket 177: no `/status`). */
    protected val requestedTargets = CopyOnWriteArrayList<String>()

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

    /** When set, the /api/v1/files upload (ticket 033) response is held back
     * server-side until the test releases it, so a test can observe the send while the
     * upload is in flight. */
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
                requestedTargets += path
                // Stamped at arrival, not at response time: the transcript
                // responses may be parked behind a test gate below, and the WS upgrade's
                // quiet window must not wait for those. The WS path is itself a GET but
                // is excluded — the window measures the poll burst only.
                if (request.method == "GET" && !path.startsWith("/api/v1/ws")) {
                    lastGetArrivalNanos.set(System.nanoTime())
                }
                return when {
                    path == "/api/v1/sessions/session_1" && request.method == "GET" ->
                        MockResponse(body = sessionBody)
                    path.startsWith("/api/v1/sessions/session_1/transcript/ops") ->
                        MockResponse(body = catchUpBody)
                    path.startsWith("/api/v1/sessions/session_1/transcript?agent_id=main") -> {
                        transcriptResponseGate?.await(5, TimeUnit.SECONDS)
                        MockResponse(body = transcriptBody ?: transcriptData())
                    }
                    path == "/api/v1/sessions/session_1/prompts" && request.method == "GET" ->
                        MockResponse(body = promptQueueBody)
                    path.startsWith("/api/v1/sessions/session_1/messages") ->
                        MockResponse(body = if ("before_id" in path) olderMessagesBody else messagesBody)
                    path == "/api/v1/sessions/session_1/prompts" ->
                        promptFailureBody?.let { MockResponse(body = it) } ?: MockResponse(body = 
                            """{"code":0,"msg":"success","data":{"prompt_id":"msg_1","user_message_id":"msg_1",""" +
                                """"status":"running","created_at":"2026-07-20T00:00:00.000Z"},"request_id":"r"}""",
                        )
                    path == "/api/v1/sessions/session_1:reload" ->
                        if (reloadsSucceed) {
                            MockResponse(body =
                                """{"code":0,"msg":"success","data":{"id":"session_1",""" +
                                    """"title":"Fix the bug","updated_at":"2026-07-20T00:00:00.000Z",""" +
                                    """"busy":false,"metadata":{"cwd":"/home/ubuntu/kimi-android-client"}},"request_id":"r"}""",
                            )
                        } else {
                            MockResponse(body =
                                """{"code":40901,"msg":"Session \"session_1\" cannot be reloaded while a turn is running",""" +
                                    """"data":null,"request_id":"r"}""",
                            )
                        }
                    path == "/api/v1/sessions/session_1:takeover" ->
                        if (takeoversSucceed) {
                            MockResponse(body =
                                """{"code":0,"msg":"success","data":{"taken_over":true},"request_id":"r"}""",
                            )
                        } else {
                            MockResponse(body =
                                """{"code":40401,"msg":"session session_1 does not exist","data":null,"request_id":"r"}""",
                            )
                        }
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
                    // Ticket 127: the success body mirrors the verbatim 0.42.0 capture
                    // (docs/server-api.md) — "Fork: " default title, inherited cwd.
                    path == "/api/v1/sessions/session_1:fork" ->
                        if (forksSucceed) {
                            MockResponse(body =
                                """{"code":0,"msg":"success","data":{"id":"session_fork",""" +
                                    """"workspace_id":"wd_spike_6baf4c130e63","title":"Fork: Fix the bug",""" +
                                    """"created_at":"2026-09-14T09:27:49.767Z",""" +
                                    """"updated_at":"2026-09-14T09:27:37.222Z","busy":false,"archived":false,""" +
                                    """"metadata":{"cwd":"/home/ubuntu/kimi-android-client"},""" +
                                    """"agent_config":{"model":""},"usage":{"input_tokens":0},""" +
                                    """"permission_rules":[],"message_count":0,"last_seq":0},"request_id":"r"}""",
                            )
                        } else {
                            MockResponse(body =
                                """{"code":40901,"msg":"Session \"session_1\" cannot be forked while a turn is running",""" +
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
                    // The 2.4.0+ response route (docs/server-api.md, Interactions →
                    // Respond — tickets 171/172): success carries {outcome, ended_at};
                    // an approval's failure is the verbatim already-ended 40902, a
                    // question's is [questionFailureBody].
                    path.startsWith("/api/v1/sessions/session_1/interactions/") -> {
                        val body = request.body?.utf8().orEmpty()
                        when {
                            "\"kind\":\"question\"" in body -> questionFailureBody?.let { MockResponse(body = it) }
                                ?: MockResponse(body =
                                    """{"code":0,"msg":"success","data":{"outcome":""" +
                                        (if ("\"dismissed\":true" in body) "\"dismissed\"" else "\"answered\"") +
                                        ""","ended_at":"2026-10-28T19:28:18.858Z"},"request_id":"r"}""",
                                )
                            approvalsSucceed -> MockResponse(body =
                                """{"code":0,"msg":"success","data":{"outcome":"approved",""" +
                                    """"ended_at":"2026-10-28T19:27:20.145Z"},"request_id":"r"}""",
                            )
                            else -> MockResponse(body =
                                """{"code":40902,"msg":"interaction approval_1 has ended","data":null,"request_id":"r"}""",
                            )
                        }
                    }
                    path.startsWith("/api/v1/sessions/session_1/media/") ->
                        MockResponse(body = "session-media-bytes")
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
                    // The two cloud policies (tickets 167/170), keyed by the registered
                    // name in the POST body — the real route serves one per request.
                    path == "/api/v1/client_configs" -> {
                        val name = request.body?.utf8()
                            ?.let { runCatching { testJson.parseToJsonElement(it).jsonObject["name"] }.getOrNull() }
                            ?.jsonPrimitive?.content
                        MockResponse(
                            body = if (name == "effort_switch_confirm") {
                                effortSwitchConfigBody
                            } else {
                                modelSwitchConfigBody
                            },
                        )
                    }
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
        chatRepository = testChatRepository(dataStore)
        coldStart = testColdStart(chatRepository, settingsRepository) { subagentStore }
        subagentStore = testSubagentStreamStore(
            streamCoalesceWindowMillis = 0,
            chatRepository = chatRepository,
            settingsRepository = settingsRepository,
            coldStart = coldStart,
        )
        viewModel = ChatViewModel(
            chatRepository,
            testSessionsRepository(),
            settingsRepository,
            FakeAttachmentStore(),
            subagentStore,
            coldStart,
            Chat("session_1"),
            unreadStore = unreadStore,
            // The suite asserts per-event application: 0 publishes every delta on
            // arrival (the pre-conflation behavior), no timer involved.
            streamCoalesceWindowMillis = 0,
        )
        startedViewModels += viewModel
    }

    @After
    fun tearDown() {
        // Cancel the ViewModels' coroutines AND wait for them to die while Main is
        // still set and the server still up: an in-flight POST's OkHttp execute() is
        // not cancellable (only GETs abort — ApiClient) and resumes its parent on Main
        // when the response comes back — joining after resetMain would crash it on the dispatcher thread
        // ("Module with the Main dispatcher had failed to initialize"). See
        // startedViewModels (ticket 049).
        runBlocking {
            startedViewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
            // A resync (a recreated ViewModel joining the cached cold start) fetches on
            // the cold start's own scope, which no ViewModel cancellation reaches.
            coldStart.awaitInFlight()
        }
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
        chatRepository,
        testSessionsRepository(),
        settingsRepository,
        FakeAttachmentStore(),
        subagentStore,
        coldStart,
        Chat("session_1"),
        unreadStore = unreadStore,
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

    /** Drains recorded requests until one matching [pathSubstring] (and [method], when
     * given) arrives — history/status/WS came first. `/prompts` serves both the cold
     * start's queue GET and the send POST (ticket 176): send assertions pass `POST`. */
    protected fun awaitRequest(pathSubstring: String, method: String? = null): RecordedRequest {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val request = server.takeRequest(100, TimeUnit.MILLISECONDS) ?: continue
            if (request.target.orEmpty().contains(pathSubstring) && (method == null || request.method == method)) {
                return request
            }
        }
        error("Timed out waiting for request to $pathSubstring")
    }

    // --- 2.5.0 transcript frames (ticket 175, TranscriptFrames) ------------------------
    // The helpers keep their pre-2.5.0 names and meaning; each produces the
    // `transcript.ops` batch the server now sends for the same transition.

    /** The current helper turn: [turnStartedFrame] opens a new one, [turnEndedFrame]
     * ends it (turn ids are opaque strings; an ended turn never restarts). */
    private var helperTurn = 0

    private var helperFrame = 0

    protected fun turnStartedFrame(): String =
        TranscriptFrames.opsFrame(TranscriptFrames.turnOp("t${++helperTurn}", "running"))

    /** One assistant text chunk: a fresh text frame per call (the fold emits its text
     * as one delta; the timeline merges consecutive deltas into the live bubble). It
     * rides a turn id the helpers never end, so a chunk sent after a turn end still
     * streams like the old volatile frame did. */
    protected fun assistantDeltaFrame(delta: String, seq: Int): String =
        TranscriptFrames.opsFrame(
            TranscriptFrames.textOp("tx.1.f${++helperFrame}", delta, turnId = "tx"),
            seq = seq.toLong(),
        )

    /** [reason] is the turn's terminal state: completed/cancelled/failed. */
    protected fun turnEndedFrame(reason: String): String =
        TranscriptFrames.opsFrame(TranscriptFrames.turnOp("t$helperTurn", reason, ""","durationMs":1000"""))

    /** A running step carrying the (schema-level) `retry` object (ticket 058). */
    protected fun stepRetryingFrame(nextAttempt: Int): String =
        TranscriptFrames.opsFrame(
            TranscriptFrames.stepOp(
                "t$helperTurn", "t$helperTurn.1", "running",
                ""","retry":{"nextAttempt":$nextAttempt,"maxAttempts":10,""" +
                    """"errorMessage":"429 The engine is currently overloaded, please try again later"}""",
            ),
        )

    /** A new step entering running — clears the retry status. */
    protected fun stepStartedFrame(step: Int, stepId: String): String =
        TranscriptFrames.opsFrame(TranscriptFrames.stepOp("t$helperTurn", stepId, "running"))

    /** The sent prompt's echo: an `undo` marker splicing the user message. */
    protected fun contextSplicedFrame(id: String, text: String): String =
        TranscriptFrames.opsFrame(TranscriptFrames.undoOp("m_${++helperFrame}", TranscriptFrames.userMessage(id, text)))

    /** A queued `prompt.upsert` (ticket 154): the fold derives the queue length from
     * the prompts it holds queued, so [queueLength] queued prompts are sent — fillers
     * first, [promptId] last. */
    protected fun promptQueuedFrame(promptId: String, queueLength: Int): String =
        TranscriptFrames.opsFrame(
            *(List(queueLength - 1) { TranscriptFrames.promptOp("msg_filler_$it", "queued") } +
                TranscriptFrames.promptOp(promptId, "queued")).toTypedArray(),
        )

    /** The prompt began executing: its `prompt.upsert` turns running. */
    protected fun promptStartedFrame(promptId: String): String =
        TranscriptFrames.opsFrame(TranscriptFrames.promptOp(promptId, "running"))

    /** An id-less steered message spliced by an `undo` marker. */
    protected fun steerEchoSplicedFrame(text: String): String =
        TranscriptFrames.opsFrame(TranscriptFrames.undoOp("m_${++helperFrame}", TranscriptFrames.userMessage(null, text)))

    protected fun subTurnStartedFrame(): String =
        TranscriptFrames.opsFrame(TranscriptFrames.turnOp("t0", "running"), agentId = "agent-12")

    /** The sub-agent's first internal prompt, spliced by its own `undo` marker. */
    protected fun subContextSplicedFrame(): String =
        TranscriptFrames.opsFrame(
            TranscriptFrames.undoOp(
                "sub_m1",
                """{"role":"user","content":[{"type":"text","text":"sub task"}],""" +
                    """"origin":{"kind":"system_trigger","name":"subagent"},"id":"msg_sub"}""",
            ),
            agentId = "agent-12",
        )

    protected fun subAssistantDeltaFrame(delta: String): String =
        TranscriptFrames.opsFrame(
            TranscriptFrames.textOp("t0.1.f${++helperFrame}", delta, turnId = "t0"),
            agentId = "agent-12",
        )
}
