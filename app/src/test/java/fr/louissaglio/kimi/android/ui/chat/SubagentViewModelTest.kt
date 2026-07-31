package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.testutil.awaitState
import fr.louissaglio.kimi.android.testutil.testChatRepository
import fr.louissaglio.kimi.android.testutil.testSubagentStreamStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The ViewModel runs against the real [ChatRepository] on a [MockWebServer] (the
 * ChatViewModelTest pattern), so the transcript fetch — the page's history source
 * since ticket 046 — is covered end-to-end. Waits run on the real clock: the GET
 * executes on OkHttp's thread, not the test dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubagentViewModelTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** 0 ms window: sub-agent frames apply on arrival, no flush timer involved. Built
     * in [setUp] against the MockWebServer-backed repository: onScreenStarted drives
     * the store's keep-alive collection (ticket 064), which must hit the mock, never
     * the fallback wiring's unroutable URL. */
    private lateinit var store: SubagentStreamStore

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var repository: ChatRepository

    @Volatile
    private var transcriptBody = EMPTY_TRANSCRIPT_BODY

    /** When set, an older-page (`before_turn`) transcript response is held back
     * server-side until the test releases it — the ticket 061 race harness (the
     * ChatViewModelTest statusResponseGate pattern). */
    @Volatile
    private var olderTranscriptGate: CountDownLatch? = null

    /** The body of older-page (`before_turn`) responses — a dedicated variable (the
     * ChatViewModelTest olderMessagesBody split) because the dispatcher reads bodies
     * at response time: a gated response must still serve the body that was current
     * when its request went out, not a later [transcriptBody]. */
    @Volatile
    private var olderTranscriptBody = ""

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return if (path.startsWith("/api/v1/sessions/session_1/transcript")) {
                    // Each request runs on its own connection thread, so a parked
                    // older-page response never holds a fresh refetch's.
                    if ("before_turn" in path) {
                        olderTranscriptGate?.await(5, TimeUnit.SECONDS)
                        MockResponse().setBody(olderTranscriptBody)
                    } else {
                        MockResponse().setBody(transcriptBody)
                    }
                } else if (path.startsWith("/api/v1/ws")) {
                    // The store's keep-alive collection (ticket 064, started by
                    // onScreenStarted → pageOpened) idles on an open upgrade.
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
        }
        val dataStore = FakePreferencesDataStore(
            preferencesOf(stringPreferencesKey("server_url") to server.url("/").toString()),
        )
        repository = testChatRepository(dataStore)
        settingsRepository = SettingsRepository(dataStore, appScope)
        store = testSubagentStreamStore(streamCoalesceWindowMillis = 0, repository, settingsRepository)
    }

    @After
    fun tearDown() {
        // Cancel the store's keep-alive collection AND join it while Main is still
        // set and the server still up — a leaked socket coroutine outliving the test
        // crashes on the dispatcher thread (the ticket-049 pattern).
        runBlocking {
            val keepAlive = store.keepAliveJob("session_1")
            store.release("session_1")
            keepAlive?.join()
        }
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun newViewModel(subagentId: String) = SubagentViewModel(
        store,
        repository,
        settingsRepository,
        SavedStateHandle(mapOf("sessionId" to "session_1", "subagentId" to subagentId)),
    )

    @Test
    fun `the store entry flows into the ui state`() = runBlocking {
        store.apply("session_1", SessionEvent.SubagentSpawned("agent-12", "explore", "tool_1", "doc-verify", false))
        store.apply("session_1", SessionEvent.SubagentStarted("agent-12"))

        val viewModel = newViewModel("agent-12")
        awaitState(viewModel.uiState) { it.status != null }

        val state = viewModel.uiState.value
        assertEquals("explore", state.name)
        assertEquals("doc-verify", state.description)
        assertEquals(SubagentStatus.Working, state.status)

        store.apply("session_1", SessionEvent.AssistantDelta("hello", "agent-12"))
        awaitState(viewModel.uiState) { it.items.isNotEmpty() }
        assertEquals(
            listOf(ChatItem.AssistantMessage("live_0", "hello", thinking = "")),
            viewModel.uiState.value.items,
        )
    }

    @Test
    fun `an unknown subagent id leaves the state empty`() = runBlocking {
        // The server answers an unknown agent_id with an empty page (not an error —
        // docs/server-api.md, Transcript), which creates no entry.
        val viewModel = newViewModel("agent-99")

        // The entry collector and the transcript fetch run eagerly/real-time; a beat
        // later nothing has arrived — the screen shows its empty state.
        delay(500)
        assertNull(viewModel.uiState.value.status)
        assertEquals(emptyList<ChatItem>(), viewModel.uiState.value.items)
    }

    @Test
    fun `the page registers itself while visible`() = runBlocking {
        val viewModel = newViewModel("agent-12")

        viewModel.onScreenStarted()
        assertEquals(1, store.openPageCount("session_1").first())

        viewModel.onScreenStopped()
        assertEquals(0, store.openPageCount("session_1").first())
    }

    @Test
    fun `a finished sub-agent's page renders its transcript without any live event`() = runBlocking {
        // Cold open (ticket 046): no spawn, no seed, no stream — the transcript fetch
        // is the entry's only data source; status derives from the turn states.
        transcriptBody = FINISHED_TRANSCRIPT_BODY

        val viewModel = newViewModel("agent-12")
        awaitState(viewModel.uiState) { it.status == SubagentStatus.Completed }

        val state = viewModel.uiState.value
        assertEquals(
            listOf(
                ChatItem.AssistantMessage("t0.1.f1", "", thinking = "Plan the listing."),
                ChatItem.ToolGroup(
                    "tool_MxYFRM8czboqPL4FXxWD4rC1",
                    listOf(
                        ChatItem.ToolActivity(
                            "tool_MxYFRM8czboqPL4FXxWD4rC1", "Bash", ToolStatus.Done,
                            "ls -la /tmp/spike", "{\n    \"command\": \"ls -la /tmp/spike\"\n}", "total 0\n",
                        ),
                    ),
                ),
                ChatItem.AssistantMessage("t0.2.f1", "`/tmp/spike` is empty.", thinking = "Empty dir."),
                ChatItem.AssistantMessage("t1.1.f1", "the summary", thinking = ""),
            ),
            state.items,
        )
        assertEquals(false, state.hasMoreHistory)
        assertNull(state.error)
        // The transcript-created entry has no identity metadata (docs/server-api.md:
        // subagent.spawned is its only carrier) — the top bar falls back to the id.
        assertEquals("", state.name)
    }

    @Test
    fun `scrolling to the top prepends older turns and stops at has_more false`() = runBlocking {
        transcriptBody = TRANSCRIPT_PAGE_T1_T2

        val viewModel = newViewModel("agent-12")
        awaitState(viewModel.uiState) { it.items.size == 2 && it.hasMoreHistory }
        // The cursor-less first call fetches the newest page.
        assertEquals(
            "/api/v1/sessions/session_1/transcript?agent_id=agent-12&page_size=20",
            server.takeRequest(2, TimeUnit.SECONDS)?.path,
        )

        olderTranscriptBody = TRANSCRIPT_PAGE_T0
        viewModel.onHistoryTopReached()
        awaitState(viewModel.uiState) { it.items.size == 3 }
        assertEquals(
            "/api/v1/sessions/session_1/transcript?agent_id=agent-12&page_size=20&before_turn=t1",
            server.takeRequest(2, TimeUnit.SECONDS)?.path,
        )

        assertEquals(
            listOf("oldest", "middle", "newest"),
            viewModel.uiState.value.items.map { (it as ChatItem.AssistantMessage).text },
        )
        assertEquals(false, viewModel.uiState.value.hasMoreHistory)

        // has_more: false stops the walk — no further request goes out.
        viewModel.onHistoryTopReached()
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `reaching a terminal state triggers one canonical refetch`() = runBlocking {
        store.apply("session_1", SessionEvent.SubagentSpawned("agent-12", "explore", "tool_1", "doc-verify", false))
        store.apply("session_1", SessionEvent.SubagentStarted("agent-12"))
        transcriptBody = RUNNING_TRANSCRIPT_BODY

        val viewModel = newViewModel("agent-12")
        awaitState(viewModel.uiState) { it.items.isNotEmpty() }
        server.takeRequest(2, TimeUnit.SECONDS)

        // Mid-run fetch merged under the live tail; more text streams in.
        store.apply("session_1", SessionEvent.AssistantDelta("live partial", "agent-12"))
        awaitState(viewModel.uiState) { state -> state.items.any { (it as? ChatItem.AssistantMessage)?.text == "live partial" } }
        assertEquals(SubagentStatus.Working, viewModel.uiState.value.status)

        // Completion flips the chip and refetches the canonical committed run: the
        // live bubble is dropped, history is the finished transcript.
        transcriptBody = FINISHED_AFTER_RECONCILE_BODY
        store.apply("session_1", SessionEvent.SubagentCompleted("agent-12", "the summary"))
        awaitState(viewModel.uiState) { state ->
            state.status == SubagentStatus.Completed &&
                state.items.singleOrNull() == ChatItem.AssistantMessage("t0.1.f1", "final answer", thinking = "")
        }
        server.takeRequest(2, TimeUnit.SECONDS)

        // Exactly one reconcile: no third transcript request ever goes out.
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a stale older page racing the reconcile refetch never prepends`() = runBlocking {
        store.apply("session_1", SessionEvent.SubagentSpawned("agent-12", "explore", "tool_1", "doc-verify", false))
        store.apply("session_1", SessionEvent.SubagentStarted("agent-12"))
        transcriptBody = TRANSCRIPT_PAGE_T1_T2

        val viewModel = newViewModel("agent-12")
        awaitState(viewModel.uiState) { it.items.size == 2 && it.hasMoreHistory }
        server.takeRequest(2, TimeUnit.SECONDS)

        // Scroll to the top: the older-page GET (before_turn=t1) goes out and its
        // response is parked server-side.
        olderTranscriptBody = TRANSCRIPT_PAGE_T0
        val gate = CountDownLatch(1)
        olderTranscriptGate = gate
        viewModel.onHistoryTopReached()
        assertEquals(
            "/api/v1/sessions/session_1/transcript?agent_id=agent-12&page_size=20&before_turn=t1",
            server.takeRequest(2, TimeUnit.SECONDS)?.path,
        )

        // The sub-agent finishes while the older page is in flight: the reconcile
        // refetches the canonical run and replaces the committed rows.
        transcriptBody = RECONCILE_FRESH_PAGE
        store.apply("session_1", SessionEvent.SubagentCompleted("agent-12", "the summary"))
        awaitState(viewModel.uiState) { state ->
            state.items.singleOrNull() == ChatItem.AssistantMessage("t2.1.f1", "reconciled newest", thinking = "")
        }

        // The stale page lands strictly after the refetch: fetched against the OLD
        // timeline, its t0 no longer abuts the fresh page — prepending it would
        // open a permanent hole (ticket 061). The settle beat gives a lost guard
        // the time to prepend if it is going to.
        gate.countDown()
        delay(500)
        assertEquals(
            listOf(ChatItem.AssistantMessage("t2.1.f1", "reconciled newest", thinking = "")),
            viewModel.uiState.value.items,
        )
    }

    private companion object {
        /** An empty transcript page — the server's answer for an unknown agent_id, or
         * for a spawned sub-agent that has not started its first turn (0.29.2). */
        const val EMPTY_TRANSCRIPT_BODY =
            """{"code":0,"msg":"success","data":{"agent_id":"agent-12","items":[],"has_more":false,""" +
                """"tasks":[],"interactions":[],"attachments":[],"todos":[],"meta":{},""" +
                """"agents":[],"pending_interactions":[],"seq":1},"request_id":"r"}"""

        /** A finished sub-agent's two-turn transcript (trimmed 0.29.2 capture, cold
         * rebuild): the task turn, then the forced result-summary turn. */
        const val FINISHED_TRANSCRIPT_BODY =
            """{"code":0,"msg":"success","data":{"agent_id":"agent-12",""" +
                """"items":[""" +
                """{"kind":"turn","turnId":"t0","ordinal":0,"state":"completed",""" +
                """"origin":{"kind":"other","payload":{"kind":"system_trigger","name":"subagent"}},""" +
                """"steps":[""" +
                """{"kind":"step","stepId":"t0.1","turnId":"t0","ordinal":1,"state":"completed",""" +
                """"frames":[""" +
                """{"kind":"thinking","frameId":"t0.1.f1","text":"Plan the listing."},""" +
                """{"kind":"tool","frameId":"t0.1.tool_MxYFRM8czboqPL4FXxWD4rC1",""" +
                """"toolCallId":"tool_MxYFRM8czboqPL4FXxWD4rC1","name":"Bash","state":"done",""" +
                """"input":{"command":"ls -la /tmp/spike"},"output":"total 0\n"}]},""" +
                """{"kind":"step","stepId":"t0.2","turnId":"t0","ordinal":2,"state":"completed",""" +
                """"frames":[""" +
                """{"kind":"thinking","frameId":"t0.2.f1","text":"Empty dir."},""" +
                """{"kind":"text","frameId":"t0.2.f2","role":"assistant","text":"`/tmp/spike` is empty."}]}]},""" +
                """{"kind":"turn","turnId":"t1","ordinal":1,"state":"completed",""" +
                """"origin":{"kind":"other","payload":{"kind":"system_trigger","name":"subagent"}},""" +
                """"steps":[""" +
                """{"kind":"step","stepId":"t1.1","turnId":"t1","ordinal":1,"state":"completed",""" +
                """"frames":[{"kind":"text","frameId":"t1.1.f1","role":"assistant","text":"the summary"}]}]}],""" +
                """"has_more":false,""" +
                """"tasks":[],"interactions":[],"attachments":[],"todos":[],"meta":{},""" +
                """"agents":[],"pending_interactions":[],"seq":1},"request_id":"r"}"""

        private fun textTurnPage(agentId: String, hasMore: Boolean, vararg turns: Pair<String, String>) =
            """{"code":0,"msg":"success","data":{"agent_id":"$agentId",""" +
                """"items":[""" +
                turns.joinToString(",") { (id, text) ->
                    """{"kind":"turn","turnId":"$id","ordinal":${id.removePrefix("t")},"state":"completed",""" +
                        """"origin":{"kind":"other","payload":{"kind":"system_trigger","name":"subagent"}},""" +
                        """"steps":[{"kind":"step","stepId":"$id.1","turnId":"$id","ordinal":1,"state":"completed",""" +
                        """"frames":[{"kind":"text","frameId":"$id.1.f1","role":"assistant","text":"$text"}]}]}"""
                } +
                """],"has_more":$hasMore,""" +
                """"tasks":[],"interactions":[],"attachments":[],"todos":[],"meta":{},""" +
                """"agents":[],"pending_interactions":[],"seq":1},"request_id":"r"}"""

        /** Newest page of a 3-turn sub-agent (turns t1, t2 — ascending within the page). */
        val TRANSCRIPT_PAGE_T1_T2 = textTurnPage("agent-12", true, "t1" to "middle", "t2" to "newest")

        /** The older page: turn t0, then has_more is exhausted. */
        val TRANSCRIPT_PAGE_T0 = textTurnPage("agent-12", false, "t0" to "oldest")

        /** Mid-run page: the single turn still running, one thinking frame committed. */
        const val RUNNING_TRANSCRIPT_BODY =
            """{"code":0,"msg":"success","data":{"agent_id":"agent-12",""" +
                """"items":[""" +
                """{"kind":"turn","turnId":"t0","ordinal":0,"state":"running",""" +
                """"origin":{"kind":"other","payload":{"kind":"system_trigger","name":"subagent"}},""" +
                """"startedAt":"2026-07-28T10:03:06.221Z",""" +
                """"steps":[{"kind":"step","stepId":"t0.1","turnId":"t0","ordinal":1,"state":"running",""" +
                """"frames":[{"kind":"thinking","frameId":"t0.1.f1","text":"Working."}]}]}],""" +
                """"has_more":false,""" +
                """"tasks":[],"interactions":[],"attachments":[],"todos":[],"meta":{},""" +
                """"agents":[],"pending_interactions":[],"seq":1},"request_id":"r"}"""

        /** The canonical refetch at completion: the finished turn's final text. */
        val FINISHED_AFTER_RECONCILE_BODY = textTurnPage("agent-12", false, "t0" to "final answer")

        /** The ticket 061 race's fresh newest page: turn t2 (re-committed, new text),
         * no overlap with the parked older page's t0 so the turn-id dedup cannot mask
         * a stale prepend. */
        val RECONCILE_FRESH_PAGE = textTurnPage("agent-12", false, "t2" to "reconciled newest")
    }
}
