package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.SocketEvent
import fr.louissaglio.kimi.android.data.chat.TaskOutcome
import fr.louissaglio.kimi.android.data.chat.TaskState
import fr.louissaglio.kimi.android.data.chat.Todo
import fr.louissaglio.kimi.android.data.chat.ToolPayload
import fr.louissaglio.kimi.android.data.chat.TranscriptAgent
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.navigation.Subagent
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskResponseFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskState
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import fr.louissaglio.kimi.android.testutil.testChatRepository
import fr.louissaglio.kimi.android.testutil.testSubagentStreamStore
import fr.louissaglio.kimi.android.testutil.webSocketUpgradeResponse
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
     * ChatViewModelTestBase transcriptResponseGate pattern). */
    @Volatile
    private var olderTranscriptGate: CountDownLatch? = null

    /** The body of older-page (`before_turn`) responses — a dedicated variable (the
     * ChatViewModelTest olderMessagesBody split) because the dispatcher reads bodies
     * at response time: a gated response must still serve the body that was current
     * when its request went out, not a later [transcriptBody]. */
    @Volatile
    private var olderTranscriptBody = ""

    /** The server's reply to a `task.background` control (ticket 179), by request id:
     * swapped per test — success (the detached state) or a refusal. */
    @Volatile
    private var backgroundReply: (String) -> String = { requestId ->
        taskResponseFrame(
            requestId,
            task = taskState("agent-t1", kind = "subagent", detached = true, extra = ""","child_agent_id":"agent-12""""),
        )
    }

    /** Every `task.background` control the app sent. */
    private val backgroundControls = CopyOnWriteArrayList<String>()

    /** Socket collections a test started (the chat's role), cancelled in [tearDown]. */
    private val socketJobs = CopyOnWriteArrayList<CoroutineJob>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target.orEmpty()
                return if (path.startsWith("/api/v1/sessions/session_1/transcript")) {
                    // Each request runs on its own connection thread, so a parked
                    // older-page response never holds a fresh refetch's.
                    if ("before_turn" in path) {
                        olderTranscriptGate?.await(5, TimeUnit.SECONDS)
                        MockResponse(body = olderTranscriptBody)
                    } else {
                        MockResponse(body = transcriptBody)
                    }
                } else if (path.startsWith("/api/v1/ws")) {
                    // The store's keep-alive collection (ticket 064, started by
                    // onScreenStarted → pageOpened) idles on an open upgrade; a
                    // task.background control (ticket 179) is answered at once.
                    webSocketUpgradeResponse(
                        object : WebSocketListener() {
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val frame = Json.parseToJsonElement(text).jsonObject
                                if (frame["type"]?.jsonPrimitive?.content == "task.background") {
                                    backgroundControls += text
                                    webSocket.send(backgroundReply(frame["request_id"]!!.jsonPrimitive.content))
                                }
                            }
                        },
                    )
                } else {
                    MockResponse(code = 404)
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
            socketJobs.forEach { it.cancelAndJoin() }
            val keepAlive = store.keepAliveJob("session_1")
            store.release("session_1")
            keepAlive?.join()
        }
        server.close()
        Dispatchers.resetMain()
    }

    /** The main-owned spawn `task` frame (ticket 182): the record's identity and
     * status carrier. */
    private fun spawnFrame(
        id: String,
        toolCallId: String = "tool_1",
        description: String = "doc-verify",
        detached: Boolean = false,
        taskId: String = "agent-t1",
        status: TaskState.Status = TaskState.Status.Running,
        outcome: TaskOutcome? = null,
        resultSummary: String? = null,
    ) = SessionEvent.TaskUpdated(
        TaskState(
            taskId, TaskState.Kind.Subagent, status, detached, description,
            outcome = outcome, phase = "working".takeIf { status == TaskState.Status.Running },
            childAgentId = id, parentToolCallId = toolCallId, resultSummary = resultSummary,
        ),
    )

    private fun completedFrame(id: String, summary: String) = spawnFrame(
        id, status = TaskState.Status.Ended, outcome = TaskOutcome.Completed, resultSummary = summary,
    )

    private fun newViewModel(subagentId: String) = SubagentViewModel(
        store,
        repository,
        settingsRepository,
        Subagent("session_1", subagentId),
    )

    @Test
    fun `the store entry flows into the ui state`() = runBlocking {
        store.apply("session_1", spawnFrame("agent-12"))
        // The subagent type is the spawning call's arg (no task frame carries it).
        store.applyAgentCall(
            "session_1", "tool_1", """{"subagent_type":"explore","description":"doc-verify","prompt":"Reply ok"}""",
            resultText = null, rowStatus = ToolStatus.Running,
        )

        val viewModel = newViewModel("agent-12")
        awaitState(viewModel.uiState) { it.status != null }

        val state = viewModel.uiState.value
        assertEquals("explore", state.name)
        assertEquals("doc-verify", state.description)
        assertEquals(SubagentStatus.Working, state.status)
        assertFalse(state.runInBackground)

        store.apply("session_1", SessionEvent.AssistantDelta("hello", "agent-12"))
        awaitState(viewModel.uiState) { it.items.size == 2 }
        assertEquals(
            listOf(
                ChatItem.UserMessage("seeded_prompt", "Reply ok"),
                ChatItem.AssistantMessage("live_0", "hello", thinking = ""),
            ),
            viewModel.uiState.value.items,
        )

        // The dock's data rides the same entry mapping: the sub-agent's own todo list
        // (its `todo` frame — ticket 178) and its own background tasks (its task.*
        // frames).
        store.apply(
            "session_1",
            SessionEvent.TodoUpdated(
                listOf(Todo("write", Todo.Status.Done), Todo("verify", Todo.Status.Pending)),
                agentId = "agent-12",
            ),
        )
        store.apply(
            "session_1",
            SessionEvent.TaskUpdated(
                TaskState(
                    "bash-1", TaskState.Kind.Shell, TaskState.Status.Running, detached = true,
                    description = "nested bg", startedAt = Instant.ofEpochMilli(1785210101939),
                ),
                agentId = "agent-12",
            ),
        )
        awaitState(viewModel.uiState) { it.todos != null && it.jobs.isNotEmpty() }
        assertEquals(
            listOf(Todo("write", Todo.Status.Done), Todo("verify", Todo.Status.Pending)),
            viewModel.uiState.value.todos,
        )
        assertEquals(
            listOf(
                Job("bash-1", Job.Kind.Bash, "nested bg", Job.Status.Running, Instant.ofEpochMilli(1785210101939)),
            ),
            viewModel.uiState.value.jobs,
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
    fun `a finished sub-agent's resume hint flows into the prefill payload`() = runBlocking {
        // Ticket 158: the Agent call's result (mined into the store from the main
        // transcript) carries the `resume_hint:` block — the "Next step" section
        // shows its copy and Continue hands its suggested call to the composer.
        store.applyAgentCall(
            "session_1",
            toolCallId = "tool_1",
            argsJson = """{"subagent_type":"explore","description":"doc-verify","prompt":"Reply ok"}""",
            resultText = "agent_id: agent-12\nactual_subagent_type: explore\nstatus: completed\nstop_reason: completed\n\n" +
                "[summary]\nthe final report\n\n" +
                "resume_hint: Continue with Agent(resume=\"agent-12\", prompt=\"...\"). " +
                "Use agent_id only; do not set subagent_type.",
            rowStatus = ToolStatus.Done,
        )

        val viewModel = newViewModel("agent-12")
        awaitState(viewModel.uiState) { it.resumePrefill != null }

        val state = viewModel.uiState.value
        assertEquals(SubagentStatus.Completed, state.status)
        assertEquals(
            "Continue with Agent(resume=\"agent-12\", prompt=\"...\"). " +
                "Use agent_id only; do not set subagent_type.",
            state.resumeHint,
        )
        assertEquals("Agent(resume=\"agent-12\", prompt=\"...\")", state.resumePrefill)
        assertTrue(state.hasNextStep)

        // A record without a hint gates the section off — "finished" alone is not
        // enough (results without a hint render exactly as before).
        store.applyAgentCall(
            "session_1",
            toolCallId = "tool_2",
            argsJson = """{"subagent_type":"explore","description":"survey","prompt":"Reply ok"}""",
            resultText = "agent_id: agent-13\nstatus: completed\n\n[summary]\ndone",
            rowStatus = ToolStatus.Done,
        )
        val hintless = newViewModel("agent-13")
        awaitState(hintless.uiState) { it.status == SubagentStatus.Completed }
        assertNull(hintless.uiState.value.resumePrefill)
        assertFalse(hintless.uiState.value.hasNextStep)
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
                            args = buildJsonObject { put("command", "ls -la /tmp/spike") },
                            // Ticket 121: a parseable Bash call carries its typed payload.
                            payload = ToolPayload.Bash("ls -la /tmp/spike", null, null, null, false),
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
        // The transcript-created entry has no identity metadata (the spawn task
        // frame and the Agent call carry it) — the top bar falls back to the id.
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
            server.takeRequest(2, TimeUnit.SECONDS)?.target,
        )

        olderTranscriptBody = TRANSCRIPT_PAGE_T0
        viewModel.onHistoryTopReached()
        awaitState(viewModel.uiState) { it.items.size == 3 }
        assertEquals(
            "/api/v1/sessions/session_1/transcript?agent_id=agent-12&page_size=20&before_turn=t1",
            server.takeRequest(2, TimeUnit.SECONDS)?.target,
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
        store.apply("session_1", spawnFrame("agent-12"))
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
        store.apply("session_1", completedFrame("agent-12", "the summary"))
        awaitState(viewModel.uiState) { state ->
            state.status == SubagentStatus.Completed &&
                state.items.singleOrNull() == ChatItem.AssistantMessage("t0.1.f1", "final answer", thinking = "")
        }
        server.takeRequest(2, TimeUnit.SECONDS)

        // Exactly one reconcile: no third transcript request ever goes out.
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a stale child journal refetches the watched page and replaces the partial rows`() = runBlocking {
        store.apply("session_1", spawnFrame("agent-12"))
        transcriptBody = TRANSCRIPT_PAGE_T1_T2
        val viewModel = newViewModel("agent-12")
        awaitState(viewModel.uiState) { it.items.size == 2 }
        server.takeRequest(2, TimeUnit.SECONDS)
        store.apply("session_1", SessionEvent.AssistantDelta("partial", "agent-12"))
        awaitState(viewModel.uiState) { it.items.size == 3 }

        // Ticket 182: the resumed subscribe's reset says the journal could not cover
        // the child's watermark — the page refetches and the canonical page replaces
        // the partial live rows.
        transcriptBody = RECONCILE_FRESH_PAGE
        store.apply("session_1", SessionEvent.TranscriptStale("agent-12"))

        assertEquals(
            "/api/v1/sessions/session_1/transcript?agent_id=agent-12&page_size=20",
            server.takeRequest(2, TimeUnit.SECONDS)?.target,
        )
        awaitState(viewModel.uiState) { state ->
            state.items == listOf(ChatItem.AssistantMessage("t2.1.f1", "reconciled newest", thinking = ""))
        }
        assertEquals(SubagentStatus.Working, viewModel.uiState.value.status)
    }

    @Test
    fun `a stale older page racing the reconcile refetch never prepends`() = runBlocking {
        store.apply("session_1", spawnFrame("agent-12"))
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
            server.takeRequest(2, TimeUnit.SECONDS)?.target,
        )

        // The sub-agent finishes while the older page is in flight: the reconcile
        // refetches the canonical run and replaces the committed rows.
        transcriptBody = RECONCILE_FRESH_PAGE
        store.apply("session_1", completedFrame("agent-12", "the summary"))
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

    // --- Detaching a member to the background (ticket 138) ---

    /** The session socket collection the chat would hold, feeding the store like
     * ChatViewModel does — the channel task controls go out on and replies come back
     * through. Returns once the connection is open. */
    private suspend fun connectSocket() {
        val connected = CountDownLatch(1)
        socketJobs += appScope.launch {
            repository.observeSession(settingsRepository.current(), "session_1").collect { event ->
                if (event is SocketEvent.Connected) connected.countDown()
                (event as? SocketEvent.Event)?.event?.let { store.apply("session_1", it) }
            }
        }
        awaitCondition { connected.count == 0L }
    }

    @Test
    fun `tapping to background sends task background and the replied state hides the affordance`() = runBlocking {
        // A foreground member whose task id is known — the overflow's "To background"
        // shows.
        store.apply("session_1", spawnFrame("agent-12"))
        connectSocket()

        val viewModel = newViewModel("agent-12")
        awaitState(viewModel.uiState) { it.detachable && it.taskId == "agent-t1" }

        val effects = CopyOnWriteArrayList<SubagentViewModel.Effect>()
        appScope.launch { viewModel.effects.collect { effects += it } }

        viewModel.onDetachToBackground()
        assertTrue(viewModel.uiState.value.detaching)
        awaitCondition { effects.isNotEmpty() }
        assertEquals(
            SubagentViewModel.Effect.DetachedToBackground("Moved to background — it keeps running in Jobs"),
            effects.single(),
        )
        val control = Json.parseToJsonElement(backgroundControls.single()).jsonObject
        assertEquals("agent-t1", control["task_id"]?.jsonPrimitive?.content)
        assertEquals("main", control["agent_id"]?.jsonPrimitive?.content)
        assertEquals("session_1", control["session_id"]?.jsonPrimitive?.content)
        // The reply's detached:true state reached the store before the success: the
        // record is background, the affordance gone, the latch never re-enables it.
        awaitState(viewModel.uiState) { !it.detachable }
        assertTrue(store.entry("session_1", "agent-12").first()!!.runInBackground)
        assertTrue(viewModel.uiState.value.detaching)
        assertTrue(drainRequestTargets().none { "/tasks" in it })
    }

    @Test
    fun `a detach refusal re-enables the item and reports the failure`() = runBlocking {
        // A foreground-only task (e.g. a swarm member) answers 40933 with its state
        // (docs/server-api.md, Task frames and controls) — nothing is applied.
        backgroundReply = { requestId ->
            taskResponseFrame(
                requestId,
                code = 40933,
                msg = "task cannot be moved to the background",
                task = taskState("agent-t1", kind = "subagent", detached = false, extra = ""","child_agent_id":"agent-12""""),
            )
        }
        store.apply("session_1", spawnFrame("agent-12"))
        connectSocket()

        val viewModel = newViewModel("agent-12")
        awaitState(viewModel.uiState) { it.detachable }

        val effects = CopyOnWriteArrayList<SubagentViewModel.Effect>()
        appScope.launch { viewModel.effects.collect { effects += it } }

        viewModel.onDetachToBackground()
        awaitCondition { effects.isNotEmpty() }
        assertEquals(
            SubagentViewModel.Effect.DetachFailed("task cannot be moved to the background"),
            effects.single(),
        )
        // The run stays foreground: the latch clears, the affordance remains.
        assertFalse(viewModel.uiState.value.detaching)
        assertTrue(viewModel.uiState.value.detachable)
    }

    @Test
    fun `a detach with no open session socket re-enables the item`() = runBlocking {
        store.apply("session_1", spawnFrame("agent-12"))
        val viewModel = newViewModel("agent-12")
        awaitState(viewModel.uiState) { it.detachable }
        val effects = CopyOnWriteArrayList<SubagentViewModel.Effect>()
        appScope.launch { viewModel.effects.collect { effects += it } }

        viewModel.onDetachToBackground()

        awaitCondition { effects.isNotEmpty() }
        assertEquals(SubagentViewModel.Effect.DetachFailed("Server unreachable"), effects.single())
        assertFalse(viewModel.uiState.value.detaching)
    }

    @Test
    fun `background and id-less members never offer the detach`() = runBlocking {
        // A member spawned in the background (`run_in_background:true` → the spawn
        // task frame's detached:true — the job is already main-owned).
        store.apply("session_1", spawnFrame("agent-12", detached = true))
        val background = newViewModel("agent-12")
        awaitState(background.uiState) { it.status == SubagentStatus.Working }
        assertTrue(background.uiState.value.runInBackground)
        assertFalse(background.uiState.value.detachable)

        // A child known only from the transcript roster (no spawn task frame seen):
        // no `task.background` target.
        store.applyRoster("session_1", listOf(TranscriptAgent("agent-13", isMain = false)))
        store.apply("session_1", SessionEvent.AssistantDelta("streaming", "agent-13"))
        val idless = newViewModel("agent-13")
        awaitState(idless.uiState) { it.status == SubagentStatus.Queued }
        assertNull(idless.uiState.value.taskId)
        assertFalse(idless.uiState.value.detachable)

        // Both intents no-op before any traffic: no control ever goes out.
        background.onDetachToBackground()
        idless.onDetachToBackground()
        delay(300)
        assertTrue(backgroundControls.isEmpty())
        assertFalse(background.uiState.value.detaching)
        assertFalse(idless.uiState.value.detaching)
    }

    /** Drains every recorded request (the pages' transcript fetches included) and
     * returns the targets. */
    private fun drainRequestTargets(): List<String> {
        val targets = mutableListOf<String>()
        while (true) {
            val request = server.takeRequest(300, TimeUnit.MILLISECONDS) ?: break
            targets += request.target.orEmpty()
        }
        return targets
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
