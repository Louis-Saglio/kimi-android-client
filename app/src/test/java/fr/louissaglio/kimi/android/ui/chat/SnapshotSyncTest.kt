package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.testutil.testChatRepository
import fr.louissaglio.kimi.android.testutil.testSnapshotSync
import fr.louissaglio.kimi.android.testutil.testSubagentStreamStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
 * The snapshot cold-start cache semantics (tickets 085/091): one fetch per session
 * ENTRY — joiners within an entry get [SnapshotSync.Outcome.AlreadyFresh] with no
 * extra request, leaving the session ([SubagentStreamStore.release] →
 * [SnapshotSync.forget]) makes the next entry re-fetch, and a failed fetch never
 * counts as fresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotSyncTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private lateinit var store: SubagentStreamStore
    private lateinit var snapshotSync: SnapshotSync
    private lateinit var repository: ChatRepository
    private lateinit var settingsRepository: SettingsRepository

    @Volatile
    private var snapshotBody = SNAPSHOT_BODY

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.target.orEmpty().startsWith("/api/v1/sessions/session_1/snapshot")) {
                    MockResponse(body = snapshotBody)
                } else {
                    MockResponse(code = 404)
                }
        }
        val dataStore = FakePreferencesDataStore(
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
        Dispatchers.resetMain()
        appScope.cancel()
        server.close()
    }

    @Test
    fun `the first sync fetches and joiners get AlreadyFresh without a new request`() = runBlocking {
        val first = snapshotSync.sync(SESSION_ID).getOrThrow()
        assertTrue(first is SnapshotSync.Outcome.Fresh)
        assertEquals(1, server.requestCount)

        val joined = snapshotSync.sync(SESSION_ID).getOrThrow()
        assertTrue(joined is SnapshotSync.Outcome.AlreadyFresh)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `after forget the next sync re-fetches`() = runBlocking {
        snapshotSync.sync(SESSION_ID).getOrThrow()
        assertEquals(1, server.requestCount)

        snapshotSync.forget(SESSION_ID)

        val refetched = snapshotSync.sync(SESSION_ID).getOrThrow()
        assertTrue(refetched is SnapshotSync.Outcome.Fresh)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `releasing the store forgets the sync so the next entry re-fetches`() = runBlocking {
        snapshotSync.sync(SESSION_ID).getOrThrow()
        assertEquals(1, server.requestCount)

        // The session-left signal (ChatViewModel.onCleared) is the store's release.
        store.release(SESSION_ID)

        val refetched = snapshotSync.sync(SESSION_ID).getOrThrow()
        assertTrue(refetched is SnapshotSync.Outcome.Fresh)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a failed fetch is forgotten and retried`() = runBlocking {
        snapshotBody = """{"code":50000,"msg":"boom","data":null,"request_id":"r"}"""
        assertTrue(snapshotSync.sync(SESSION_ID).isFailure)
        assertEquals(1, server.requestCount)

        snapshotBody = SNAPSHOT_BODY
        assertTrue(snapshotSync.sync(SESSION_ID).isSuccess)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `the fetch mines the snapshot's Agent calls into the store`() = runBlocking {
        // The chat-independent seed (ticket 091): the roster entry links and statuses
        // the record, the messages page's Agent call backfills the prompt — the
        // page-on-top cold restore recovers both without any ChatViewModel.
        snapshotBody = """{"code":0,"msg":"success","data":{"as_of_seq":18,"epoch":"ep_1",""" +
            """"session":{"id":"session_1","busy":true,"usage":{"context_tokens":0,"context_limit":1048576}},""" +
            """"messages":{"items":[""" +
            """{"id":"msg_1","session_id":"session_1","role":"assistant",""" +
            """"content":[{"type":"tool_use","tool_call_id":"tool_agent","tool_name":"Agent",""" +
            """"input":{"subagent_type":"explore","description":"doc-verify","prompt":"Reply ok"}}],""" +
            """"created_at":"2026-08-01T10:00:00.000Z"}],"has_more":false},""" +
            """"in_flight_turn":null,""" +
            """"subagents":[{"id":"agent-12","kind":"subagent","subagent_phase":"working",""" +
            """"subagent_type":"explore","description":"doc-verify","parent_tool_call_id":"tool_agent",""" +
            """"run_in_background":false,"created_at":"2026-08-01T10:00:00.000Z",""" +
            """"started_at":"2026-08-01T10:00:00.100Z"}],""" +
            """"pending_approvals":[],"pending_questions":[]},"request_id":"r"}"""

        snapshotSync.sync(SESSION_ID).getOrThrow()

        val record = store.entry(SESSION_ID, "agent-12").first()!!
        assertEquals("explore", record.name)
        assertEquals(SubagentStatus.Working, record.status)
        assertEquals(listOf(ChatItem.UserMessage("seeded_prompt", "Reply ok")), record.items)
        assertEquals(mapOf("tool_agent" to listOf("agent-12")), store.linksFor(SESSION_ID))
    }

    @Test
    fun `the fetch mines the in-flight turn's running Agent tools`() = runBlocking {
        // A foreground Agent call still running at the watermark: no committed
        // message/result carries its args, but in_flight_turn.running_tools does
        // (docs/server-api.md) — and the roster's parent_tool_call_id resolves the
        // call id, so the record gains the prompt without any ChatViewModel mining.
        snapshotBody = """{"code":0,"msg":"success","data":{"as_of_seq":18,"epoch":"ep_1",""" +
            """"session":{"id":"session_1","busy":true,"usage":{"context_tokens":0,"context_limit":1048576}},""" +
            """"messages":{"items":[],"has_more":false},""" +
            """"in_flight_turn":{"turn_id":0,"assistant_text":"","thinking_text":"",""" +
            """"running_tools":[{"tool_call_id":"tool_agent","name":"Agent",""" +
            """"args":{"subagent_type":"explore","description":"doc-verify","prompt":"Reply ok"},""" +
            """"display":{}}],"current_prompt_id":"msg_1"},""" +
            """"subagents":[{"id":"agent-12","kind":"subagent","subagent_phase":"working",""" +
            """"subagent_type":"explore","description":"doc-verify","parent_tool_call_id":"tool_agent",""" +
            """"run_in_background":false,"created_at":"2026-08-01T10:00:00.000Z",""" +
            """"started_at":"2026-08-01T10:00:00.100Z"}],""" +
            """"pending_approvals":[],"pending_questions":[]},"request_id":"r"}"""

        snapshotSync.sync(SESSION_ID).getOrThrow()

        // The roster's parent_tool_call_id resolves the running tool's call id: the
        // record gains the prompt from the mined running_tools args.
        val record = store.entry(SESSION_ID, "agent-12").first()!!
        assertEquals(SubagentStatus.Working, record.status)
        assertEquals(listOf(ChatItem.UserMessage("seeded_prompt", "Reply ok")), record.items)
    }

    private companion object {
        const val SESSION_ID = "session_1"

        const val SNAPSHOT_BODY = """{"code":0,"msg":"success","data":{"as_of_seq":18,"epoch":"ep_1",""" +
            """"session":{"id":"session_1","busy":false,"usage":{"context_tokens":0,"context_limit":1048576}},""" +
            """"messages":{"items":[],"has_more":false},"in_flight_turn":null,"subagents":[],""" +
            """"pending_approvals":[],"pending_questions":[]},"request_id":"r"}"""
    }
}
