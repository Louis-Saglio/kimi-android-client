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
import fr.louissaglio.kimi.android.testutil.testSnapshotSync
import fr.louissaglio.kimi.android.testutil.testSubagentStreamStore
import fr.louissaglio.kimi.android.testutil.webSocketUpgradeResponse
import fr.louissaglio.kimi.android.testutil.testTodoParser
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Ticket 064: the sub-agent store is fed by exactly one session-socket collection at
 * a time — the chat's while its gate holds the session, otherwise the store's own
 * keep-alive collection while a page is open. The keep-alive is what streams on a
 * sub-agent page restored after process death (the chat entry of the back stack is
 * never composed, so no ChatViewModel exists). Runs against the real data layer on a
 * [MockWebServer] (the ChatViewModelTest pattern): the socket is real I/O, so waits
 * use real-time timeouts, not runTest's clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubagentStreamStoreKeepAliveTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** 0 ms window: sub-agent frames apply on arrival, no flush timer involved. */
    private lateinit var store: SubagentStreamStore
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var dataStore: FakePreferencesDataStore

    /** Shared by the store and the ChatViewModel (the production singleton shape):
     * one SessionSocket, one cursor map — the hand-off resumes, never replays twice. */
    private lateinit var repository: ChatRepository

    /** The session's cold-start owner (ticket 085), shared by the store's keep-alive
     * and the ChatViewModels — one snapshot sync per session per process. */
    private lateinit var snapshotSync: SnapshotSync

    /** The served /snapshot body (ticket 085): the keep-alive's cold start awaits it
     * before subscribing. Default: empty roster, watermark seq 18 (just below the
     * tests' first frame, seq 19). */
    @Volatile
    private var snapshotBody = SNAPSHOT_BODY

    @Volatile
    private var serverSocket: WebSocket? = null

    /** Every frame the client sent over any WS connection of the test. */
    private val clientFrames = CopyOnWriteArrayList<String>()

    /** Every ChatViewModel started in a test — their viewModelScopes (gate collector,
     * socket collect) are cancelled in [tearDown] before resetMain (ticket 049). */
    private val startedViewModels = CopyOnWriteArrayList<ChatViewModel>()

    /** The store's platform I/O never runs on the JVM (the ChatViewModelTest twin). */
    private class FakeAttachmentStore : AttachmentStore {
        override fun read(uri: Uri): AttachmentStore.ReadResult = throw UnsupportedOperationException()

        override fun writeToCache(name: String, bytes: ByteArray): Uri? = throw UnsupportedOperationException()
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target.orEmpty()
                return when {
                    // The startup GET burst of the real ChatViewModel used by the
                    // hand-off test; none of these bodies touches the sub-agent store.
                    path == "/api/v1/sessions/session_1" && request.method == "GET" ->
                        MockResponse(body = SESSION_BODY)
                    path == "/api/v1/sessions/session_1/snapshot" -> MockResponse(body = snapshotBody)
                    path.startsWith("/api/v1/sessions/session_1/messages") ->
                        MockResponse(body = MESSAGES_BODY)
                    path == "/api/v1/sessions/session_1/status" -> MockResponse(body = STATUS_BODY)
                    path.startsWith("/api/v1/sessions/session_1/questions") ->
                        MockResponse(body = QUESTIONS_BODY)
                    path == "/api/v1/models" -> MockResponse(body = MODELS_BODY)
                    path == "/api/v1/config" -> MockResponse(body = CONFIG_BODY)
                    path.startsWith("/api/v1/ws") ->
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
                    else -> MockResponse(code = 404)
                }
            }
        }
        dataStore = FakePreferencesDataStore(
            preferencesOf(stringPreferencesKey("server_url") to server.url("/").toString()),
        )
        repository = testChatRepository(dataStore)
        settingsRepository = SettingsRepository(dataStore, appScope)
        snapshotSync = testSnapshotSync(repository, settingsRepository) { store }
        store = testSubagentStreamStore(
            streamCoalesceWindowMillis = 0,
            chatRepository = repository,
            settingsRepository = settingsRepository,
            snapshotSync = snapshotSync,
        )
    }

    @After
    fun tearDown() {
        // Cancel the ViewModels AND the store's keep-alive collection, joining both
        // while Main is still set and the server still up: a leaked socket coroutine
        // resuming between tests crashes on the dispatcher thread (ticket 049).
        runBlocking {
            startedViewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
            val keepAlive = store.keepAliveJob(SESSION_ID)
            store.release(SESSION_ID)
            keepAlive?.join()
        }
        Dispatchers.resetMain()
        appScope.cancel()
        server.close()
    }

    @Test
    fun `pageOpened without a chat collection starts a socket collection that feeds the store`() = runBlocking {
        // The process-death scenario: the restored back stack composes only the
        // sub-agent page — no ChatViewModel exists, so the store must feed itself.
        store.pageOpened(SESSION_ID)

        assertNotNull(store.keepAliveJob(SESSION_ID))
        val socket = awaitServerSocket()
        socket.send(subagentSpawnedFrame())
        socket.send(subagentStartedFrame())
        socket.send(subAssistantDeltaFrame("live after process death", seq = 23))

        awaitCondition { entry("agent-12")?.items?.isNotEmpty() == true }
        val state = entry("agent-12")!!
        assertEquals("explore", state.name)
        assertEquals(SubagentStatus.Working, state.status)
        assertEquals(
            listOf(ChatItem.AssistantMessage("live_0", "live after process death", thinking = "")),
            state.items,
        )

        // Closing the page stops the store's collection — nothing outlives the need.
        store.pageClosed(SESSION_ID)
        assertNull(store.keepAliveJob(SESSION_ID))
    }

    @Test
    fun `opening the chat hands the feed over with exactly one collection`() = runBlocking {
        // Restored-on-the-page state: the keep-alive is the feeder.
        store.pageOpened(SESSION_ID)
        val keepAliveSocket = awaitServerSocket()
        keepAliveSocket.send(subagentSpawnedFrame())
        keepAliveSocket.send(subAssistantDeltaFrame("from the keep-alive", seq = 23))
        awaitCondition { entry("agent-12")?.items?.size == 1 }

        // The user backs out to the chat: its entry composes, the gate opens (the
        // open page alone holds it), signals the store, and collects.
        val viewModel = newChatViewModel()
        viewModel.onScreenStarted()

        // The hand-off: the keep-alive is cancelled before the chat's collection
        // feeds — two concurrent collections would double-apply replayed events.
        awaitCondition { store.keepAliveJob(SESSION_ID) == null }
        val chatSocket = awaitNewServerSocket(after = keepAliveSocket)
        // The chat's subscribe resumes with the cursor the keep-alive reached (one
        // shared SessionSocket — the 048 machinery): no replay gap, no overlap.
        awaitCondition { clientFrames.size == 2 && "\"seq\":19" in clientFrames[1] }

        // Consecutive deltas append to the one streaming bubble (live_0) — a
        // double-feed would show up as the text applied twice.
        chatSocket.send(subAssistantDeltaFrame(" from the chat", seq = 24))
        awaitCondition {
            entry("agent-12")?.items ==
                listOf(ChatItem.AssistantMessage("live_0", "from the keep-alive from the chat", thinking = ""))
        }

        // A straggler frame on the cancelled keep-alive connection goes nowhere: the
        // cancelled collector never applies again — no double-feed.
        keepAliveSocket.send(subAssistantDeltaFrame("ghost", seq = 25))
        delay(300)
        assertEquals(
            listOf(ChatItem.AssistantMessage("live_0", "from the keep-alive from the chat", thinking = "")),
            entry("agent-12")!!.items,
        )

        // Clean end state: page closed, screen stopped — no collection of any kind.
        store.pageClosed(SESSION_ID)
        viewModel.onScreenStopped()
        awaitCondition { store.keepAliveJob(SESSION_ID) == null }
    }

    @Test
    fun `the keep-alive runs exactly while a page is open and no chat collection is`() = runBlocking {
        // Neither page nor chat: nothing.
        assertNull(store.keepAliveJob(SESSION_ID))

        // Page open, no chat: the store feeds itself. A second page of the same
        // session reuses the same collection.
        store.pageOpened(SESSION_ID)
        val job = store.keepAliveJob(SESSION_ID)
        assertNotNull(job)
        store.pageOpened(SESSION_ID)
        assertSame(job, store.keepAliveJob(SESSION_ID))

        // The chat collection takes over — with a page open and after it closes.
        store.chatCollectionStarted(SESSION_ID)
        assertNull(store.keepAliveJob(SESSION_ID))
        store.pageClosed(SESSION_ID)
        assertNull(store.keepAliveJob(SESSION_ID))

        // The chat collection ends with a page still open: the keep-alive resumes.
        store.chatCollectionStopped(SESSION_ID)
        assertNotNull(store.keepAliveJob(SESSION_ID))

        // The last page closes: nothing.
        store.pageClosed(SESSION_ID)
        assertNull(store.keepAliveJob(SESSION_ID))
    }

    @Test
    fun `the keep-alive cold start seeds the store from the snapshot roster before subscribing`() = runBlocking {
        // Process death with the page on top (ticket 064 path — no ChatViewModel
        // exists): the store's keep-alive runs the snapshot sync (ticket 085) BEFORE
        // its first subscribe, so a running roster entry lands the identity, the
        // precise Working status and the resume cursor first.
        snapshotBody = """{"code":0,"msg":"success","data":{"as_of_seq":18,"epoch":"ep_1",""" +
            """"session":{"id":"session_1","busy":true,"usage":{"context_tokens":0,"context_limit":1048576}},""" +
            """"messages":{"items":[],"has_more":false},"in_flight_turn":null,""" +
            """"subagents":[{"id":"agent-12","kind":"subagent","subagent_phase":"working",""" +
            """"subagent_type":"explore","description":"doc-verify","parent_tool_call_id":"tool_agent",""" +
            """"run_in_background":false,"created_at":"2026-08-01T10:00:00.000Z",""" +
            """"started_at":"2026-08-01T10:00:00.100Z"}],""" +
            """"pending_approvals":[],"pending_questions":[]},"request_id":"r"}"""

        store.pageOpened(SESSION_ID)

        // The subscribe frame carrying the snapshot watermark proves the sync (cursor
        // seed AND roster seed) completed before it — gapless stitching.
        awaitCondition { clientFrames.isNotEmpty() }
        val subscribe = clientFrames.first()
        assertTrue(subscribe, subscribe.contains("\"seq\":18"))
        assertTrue(subscribe, subscribe.contains("\"epoch\":\"ep_1\""))
        val seeded = entry("agent-12")!!
        assertEquals("explore", seeded.name)
        assertEquals("doc-verify", seeded.description)
        assertEquals(SubagentStatus.Working, seeded.status)

        // Live frames accumulate from the very first one — no "spawn missed" drop.
        val socket = awaitServerSocket()
        socket.send(subAssistantDeltaFrame("from frame one", seq = 23))
        awaitCondition {
            entry("agent-12")?.items ==
                listOf(ChatItem.AssistantMessage("live_0", "from frame one", thinking = ""))
        }

        store.pageClosed(SESSION_ID)
    }

    private fun newChatViewModel(): ChatViewModel = ChatViewModel(
        repository,
        testSessionsRepository(),
        settingsRepository,
        FakeAttachmentStore(),
        store,
        snapshotSync,
        Chat("session_1"),
        todoParser = testTodoParser(),
        streamCoalesceWindowMillis = 0,
    ).also { startedViewModels += it }

    private fun entry(subagentId: String): SubagentStreamStore.SubagentState? =
        runBlocking { store.entry(SESSION_ID, subagentId).first() }

    private suspend fun awaitServerSocket(): WebSocket {
        awaitCondition { serverSocket != null }
        return serverSocket!!
    }

    /** The NEXT connection after [after]: the hand-off's fresh socket, not the stale one. */
    private suspend fun awaitNewServerSocket(after: WebSocket): WebSocket {
        awaitCondition { serverSocket != null && serverSocket !== after }
        return serverSocket!!
    }

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

    private fun subAssistantDeltaFrame(delta: String, seq: Int): String =
        """{"type":"assistant.delta","seq":$seq,"epoch":"ep_1","volatile":true,"offset":0,""" +
            """"session_id":"session_1","timestamp":"2026-07-28T03:37:36.100Z",""" +
            """"payload":{"type":"assistant.delta","turnId":0,"delta":"$delta","agentId":"agent-12","sessionId":"session_1"}}"""

    private companion object {
        const val SESSION_ID = "session_1"

        const val SNAPSHOT_BODY = """{"code":0,"msg":"success","data":{"as_of_seq":18,"epoch":"ep_1",""" +
            """"session":{"id":"session_1","busy":false,"usage":{"context_tokens":0,"context_limit":1048576}},""" +
            """"messages":{"items":[],"has_more":false},"in_flight_turn":null,"subagents":[],""" +
            """"pending_approvals":[],"pending_questions":[]},"request_id":"r"}"""

        const val SESSION_BODY = """{"code":0,"msg":"success","data":{"id":"session_1","title":"Fix the bug",""" +
            """"updated_at":"2026-07-20T00:00:00.000Z","busy":false,""" +
            """"metadata":{"cwd":"/home/ubuntu/kimi-android-client"}},"request_id":"r"}"""

        const val MESSAGES_BODY =
            """{"code":0,"msg":"success","data":{"items":[],"has_more":false},"request_id":"r"}"""

        const val STATUS_BODY = """{"code":0,"msg":"success","data":{"busy":false,"context_tokens":0,""" +
            """"max_context_tokens":1048576,"context_usage":0.0},"request_id":"r"}"""

        const val QUESTIONS_BODY = """{"code":0,"msg":"success","data":{"items":[]},"request_id":"r"}"""

        const val MODELS_BODY = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"provider":"managed:kimi-code","model":"kimi-code/kimi-for-coding",""" +
            """"display_name":"K2.7 Coding","max_context_size":262144}""" +
            """]},"request_id":"r"}"""

        const val CONFIG_BODY =
            """{"code":0,"msg":"success","data":{"default_model":"kimi-code/kimi-for-coding"},"request_id":"r"}"""
    }
}
