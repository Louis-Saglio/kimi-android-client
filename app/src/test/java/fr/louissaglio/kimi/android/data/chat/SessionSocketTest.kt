package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.testutil.awaitCondition
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SessionSocketTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val socket = SessionSocket(OkHttpClient(), json, ChatMessageMapper(json))

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun settings() = ServerSettings(serverUrl = server.url("/").toString())

    /** Same harness as ChatRepositoryTest.runSocketEvents: collects until [eventCount]
     * events arrived, then waits [settleMillis] longer as proof that nothing more comes. */
    private suspend fun runSocketEvents(
        vararg frames: String,
        eventCount: Int = frames.size,
        settleMillis: Long = 0,
    ): List<SocketEvent> = coroutineScope {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        frames.forEach(webSocket::send)
                    }
                },
            ),
        )
        val emitted = CopyOnWriteArrayList<SocketEvent>()
        val job = launch { socket.observe(settings(), "session_1").collect { emitted += it } }
        try {
            awaitCondition { emitted.count { it is SocketEvent.Event } >= eventCount }
            if (settleMillis > 0) delay(settleMillis)
        } finally {
            job.cancel()
        }
        emitted
    }

    @Test
    fun `frames for a foreign session are dropped, own and untagged frames are delivered`() = runBlocking {
        // 0.31.0 caveat (docs/server-api.md): the server sends events for sessions this
        // connection never subscribed to, so the envelope session_id is the filter key.
        // The settle wait covers the foreign frame: it must arrive and still produce no
        // event (it is dropped with a Log.w; unit-test Log is a stub, so the observable
        // assertion is non-delivery).
        val emitted = runSocketEvents(
            """{"type":"assistant.delta","seq":2,"epoch":"ep_1","volatile":true,"offset":0,""" +
                """"session_id":"session_1","timestamp":"2026-07-31T00:00:00.000Z",""" +
                """"payload":{"type":"assistant.delta","turnId":1,"delta":"own ","agentId":"main","sessionId":"session_1"}}""",
            """{"type":"assistant.delta","seq":7,"epoch":"ep_9","volatile":true,"offset":0,""" +
                """"session_id":"session_2","timestamp":"2026-07-31T00:00:00.100Z",""" +
                """"payload":{"type":"assistant.delta","turnId":3,"delta":"foreign","agentId":"main","sessionId":"session_2"}}""",
            // No session_id (control frames carry none): fail-open, delivered.
            """{"type":"assistant.delta","seq":3,"epoch":"ep_1","volatile":true,"offset":4,""" +
                """"timestamp":"2026-07-31T00:00:00.200Z",""" +
                """"payload":{"type":"assistant.delta","turnId":1,"delta":"untagged"}}""",
            eventCount = 2,
            settleMillis = 200,
        )

        assertEquals(
            listOf(
                SocketEvent.Event(SessionEvent.AssistantDelta("own ")),
                SocketEvent.Event(SessionEvent.AssistantDelta("untagged")),
            ),
            emitted.filterIsInstance<SocketEvent.Event>(),
        )
    }
}
