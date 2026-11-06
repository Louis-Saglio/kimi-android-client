package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.MAIN_AGENT_ID
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.testutil.testChatRepository
import fr.louissaglio.kimi.android.testutil.testColdStart
import fr.louissaglio.kimi.android.testutil.testSubagentStreamStore
import java.util.concurrent.CopyOnWriteArrayList
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The transcript cold-start cache semantics (tickets 085/091 on the 176 reads): one
 * fetch — messages + main transcript + prompt queue — per session ENTRY; joiners
 * within an entry get [TranscriptColdStart.Outcome.AlreadyFresh] with no extra request,
 * leaving the session ([SubagentStreamStore.release] → [TranscriptColdStart.forget])
 * makes the next entry re-fetch, and a failed read never counts as fresh. The fetch
 * applies the session scope itself: the socket watermark, the roster, the mining.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranscriptColdStartSyncTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private lateinit var store: SubagentStreamStore
    private lateinit var coldStart: TranscriptColdStart
    private lateinit var repository: ChatRepository
    private lateinit var settingsRepository: SettingsRepository

    private val paths = CopyOnWriteArrayList<String>()

    @Volatile
    private var messagesBody = EMPTY_MESSAGES

    @Volatile
    private var transcriptBody = transcript()

    @Volatile
    private var promptsBody = EMPTY_QUEUE

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.target.orEmpty()
                paths += path
                return when {
                    path.startsWith("/api/v1/sessions/session_1/messages") -> MockResponse(body = messagesBody)
                    path.startsWith("/api/v1/sessions/session_1/transcript?agent_id=main") ->
                        MockResponse(body = transcriptBody)
                    path == "/api/v1/sessions/session_1/prompts" -> MockResponse(body = promptsBody)
                    else -> MockResponse(code = 404)
                }
            }
        }
        val dataStore = FakePreferencesDataStore(
            preferencesOf(stringPreferencesKey("server_url") to server.url("/").toString()),
        )
        repository = testChatRepository(dataStore)
        settingsRepository = SettingsRepository(dataStore, appScope)
        coldStart = testColdStart(repository, settingsRepository) { store }
        store = testSubagentStreamStore(
            streamCoalesceWindowMillis = 0,
            chatRepository = repository,
            settingsRepository = settingsRepository,
            coldStart = coldStart,
        )
    }

    @After
    fun tearDown() {
        runBlocking { coldStart.awaitInFlight() }
        Dispatchers.resetMain()
        appScope.cancel()
        server.close()
    }

    @Test
    fun `the first sync takes the three reads and joiners get AlreadyFresh without a new request`() = runBlocking {
        val first = coldStart.sync(SESSION_ID).getOrThrow()
        assertTrue(first is TranscriptColdStart.Outcome.Fresh)
        assertEquals(3, server.requestCount)
        assertTrue(paths.toString(), paths.any { it.startsWith("/api/v1/sessions/session_1/messages?page_size=100") })
        assertTrue(paths.toString(), paths.any { it == "/api/v1/sessions/session_1/transcript?agent_id=main&page_size=1" })
        assertTrue(paths.toString(), paths.any { it == "/api/v1/sessions/session_1/prompts" })
        assertTrue(paths.toString(), paths.none { "/snapshot" in it })

        val joined = coldStart.sync(SESSION_ID).getOrThrow()
        assertTrue(joined is TranscriptColdStart.Outcome.AlreadyFresh)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `after forget the next sync re-fetches`() = runBlocking {
        coldStart.sync(SESSION_ID).getOrThrow()
        coldStart.forget(SESSION_ID)

        val refetched = coldStart.sync(SESSION_ID).getOrThrow()
        assertTrue(refetched is TranscriptColdStart.Outcome.Fresh)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `releasing the store forgets the sync so the next entry re-fetches`() = runBlocking {
        coldStart.sync(SESSION_ID).getOrThrow()

        // The session-left signal (ChatViewModel.onCleared) is the store's release.
        store.release(SESSION_ID)

        val refetched = coldStart.sync(SESSION_ID).getOrThrow()
        assertTrue(refetched is TranscriptColdStart.Outcome.Fresh)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `any failed read fails the cold start, which is forgotten and retried`() = runBlocking {
        promptsBody = """{"code":50000,"msg":"boom","data":null,"request_id":"r"}"""
        assertTrue(coldStart.sync(SESSION_ID).isFailure)

        promptsBody = EMPTY_QUEUE
        assertTrue(coldStart.sync(SESSION_ID).getOrThrow() is TranscriptColdStart.Outcome.Fresh)
        assertEquals(6, server.requestCount)
    }

    @Test
    fun `the fetch seeds the socket watermark from the transcript seq`() = runBlocking {
        transcriptBody = transcript(seq = 42)

        coldStart.sync(SESSION_ID).getOrThrow()

        assertEquals(42L, repository.transcriptWatermark(SESSION_ID, MAIN_AGENT_ID))
    }

    @Test
    fun `a transcript without seq leaves no watermark`() = runBlocking {
        // `seq` ships for live sessions only: the subscribe then takes the baseline.
        transcriptBody = transcript(seq = null)

        coldStart.sync(SESSION_ID).getOrThrow()

        assertNull(repository.transcriptWatermark(SESSION_ID, MAIN_AGENT_ID))
    }

    @Test
    fun `the fetch mines a committed Agent call into the store`() = runBlocking {
        // The chat-independent seed (ticket 091): the messages page's Agent call and
        // its result's agent_id: marker create and link the record and backfill its
        // prompt — the page-on-top cold restore recovers both without a ChatViewModel.
        messagesBody = """{"code":0,"msg":"success","data":{"items":[""" +
            """{"id":"msg_2","session_id":"session_1","role":"tool",""" +
            """"content":[{"type":"tool_result","tool_call_id":"tool_agent","output":"agent_id: agent-12\n\nok"}],""" +
            """"created_at":"2026-11-04T10:00:01.000Z"},""" +
            """{"id":"msg_1","session_id":"session_1","role":"assistant",""" +
            """"content":[{"type":"tool_use","tool_call_id":"tool_agent","tool_name":"Agent",""" +
            """"input":{"subagent_type":"explore","description":"doc-verify","prompt":"Reply ok"}}],""" +
            """"created_at":"2026-11-04T10:00:00.000Z"}],"has_more":false},"request_id":"r"}"""

        coldStart.sync(SESSION_ID).getOrThrow()

        val record = store.entry(SESSION_ID, "agent-12").first()!!
        assertEquals("explore", record.name)
        assertEquals(listOf(ChatItem.UserMessage("seeded_prompt", "Reply ok")), record.items)
        assertEquals(mapOf("tool_agent" to listOf("agent-12")), store.linksFor(SESSION_ID))
    }

    @Test
    fun `the fetch mines the running turn's settled Agent frame`() = runBlocking {
        // A spawn settled inside the still-running turn: no committed message carries
        // it yet, the transcript's tool frame does (verbatim args + output).
        transcriptBody = transcript(
            items = """[{"kind":"turn","turnId":"t1","ordinal":1,"state":"running","origin":{"kind":"user"},""" +
                """"steps":[{"kind":"step","stepId":"t1.1","turnId":"t1","ordinal":1,"state":"running","frames":[""" +
                """{"kind":"tool","frameId":"t1.1.tool_agent","toolCallId":"tool_agent","name":"Agent","state":"done",""" +
                """"input":{"subagent_type":"explore","description":"doc-verify","prompt":"Reply ok"},""" +
                """"output":"agent_id: agent-12\n\nok"}]}]}]""",
        )

        coldStart.sync(SESSION_ID).getOrThrow()

        val record = store.entry(SESSION_ID, "agent-12").first()!!
        assertEquals(listOf(ChatItem.UserMessage("seeded_prompt", "Reply ok")), record.items)
        assertEquals(mapOf("tool_agent" to listOf("agent-12")), store.linksFor(SESSION_ID))
    }

    @Test
    fun `the roster seeds a record for every child agent`() = runBlocking {
        transcriptBody = transcript(
            agents = """[{"agentId":"main","type":"main"},{"agentId":"agent-1","type":"sub","parentAgentId":"main"},""" +
                """{"agentId":"agent-2","type":"sub","parentAgentId":"agent-1"}]""",
        )

        coldStart.sync(SESSION_ID).getOrThrow()

        assertEquals("agent-1", store.entry(SESSION_ID, "agent-1").first()?.id)
        assertEquals("agent-2", store.entry(SESSION_ID, "agent-2").first()?.id)
        assertNull(store.entry(SESSION_ID, MAIN_AGENT_ID).first())
    }

    private companion object {
        const val SESSION_ID = "session_1"

        const val EMPTY_MESSAGES = """{"code":0,"msg":"success","data":{"items":[],"has_more":false},"request_id":"r"}"""

        const val EMPTY_QUEUE = """{"code":0,"msg":"success","data":{"active":null,"queued":[]},"request_id":"r"}"""

        fun transcript(
            items: String = "[]",
            agents: String = """[{"agentId":"main","type":"main"}]""",
            seq: Long? = 18,
        ): String = """{"code":0,"msg":"success","data":{"agent_id":"main","items":$items,"has_more":false,""" +
            """"interactions":[],"pending_interactions":[],"prompts":[],"agents":$agents""" +
            """${seq?.let { ""","seq":$it""" }.orEmpty()}},"request_id":"r"}"""
    }
}
