package fr.louissaglio.kimi.android.data.sessions

import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.webSocketUpgradeResponse
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Frame tests for [OkHttpSessionListSocket] on the MockWebServer WebSocket harness
 * (same approach as SessionSocketTest): the server-side listener records the client's
 * frames, so subscribe shapes are asserted on what actually went over the wire. Waits
 * run on the real clock — the socket does real I/O.
 */
class OkHttpSessionListSocketTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val socket = OkHttpSessionListSocket(OkHttpClient(), json)
    private val sessionIds = MutableStateFlow<Set<String>>(emptySet())

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun settings() = ServerSettings(serverUrl = server.url("/").toString())

    /** Server-side listener recording the client's frames; [onClientFrame] reacts to them. */
    private fun recordingListener(
        clientFrames: MutableList<String>,
        onClientFrame: (WebSocket) -> Unit = {},
    ) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = Unit

        override fun onMessage(webSocket: WebSocket, text: String) {
            clientFrames += text
            onClientFrame(webSocket)
        }
    }

    private fun subscribeSessionIds(clientFrame: String): Set<String> =
        json.parseToJsonElement(clientFrame).jsonObject
            .getValue("payload").jsonObject
            .getValue("session_ids").jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()

    private fun subscribeFrameId(clientFrame: String): String =
        json.parseToJsonElement(clientFrame).jsonObject.getValue("id").jsonPrimitive.content

    private fun subscribeHasCursors(clientFrame: String): Boolean =
        json.parseToJsonElement(clientFrame).jsonObject
            .getValue("payload").jsonObject
            .containsKey("cursors")

    @Test
    fun `the first subscribe carries exactly the loaded ids in one frame, without cursors`() = runBlocking {
        sessionIds.value = linkedSetOf("session_1", "session_2")
        val clientFrames = CopyOnWriteArrayList<String>()
        server.enqueue(webSocketUpgradeResponse(recordingListener(clientFrames)))
        coroutineScope {
            val job = launch { socket.observe(settings(), sessionIds).collect {} }
            try {
                awaitCondition { clientFrames.size == 1 }
            } finally {
                job.cancel()
            }
        }

        assertEquals(setOf("session_1", "session_2"), subscribeSessionIds(clientFrames.single()))
        // The REST load is the baseline (ticket 087): no resume cursors, ever.
        assertFalse(subscribeHasCursors(clientFrames.single()))
    }

    @Test
    fun `a newly loaded page sends a follow-up subscribe with only the new ids`() = runBlocking {
        sessionIds.value = linkedSetOf("session_1")
        val clientFrames = CopyOnWriteArrayList<String>()
        server.enqueue(webSocketUpgradeResponse(recordingListener(clientFrames)))
        coroutineScope {
            val job = launch { socket.observe(settings(), sessionIds).collect {} }
            try {
                awaitCondition { clientFrames.size == 1 }
                // The next page landed: the ids flow emits the grown set.
                sessionIds.value = linkedSetOf("session_1", "session_3")
                awaitCondition { clientFrames.size == 2 }
            } finally {
                job.cancel()
            }
        }

        assertEquals(setOf("session_1"), subscribeSessionIds(clientFrames[0]))
        // The follow-up subscribes only the delta, under a fresh frame id.
        assertEquals(setOf("session_3"), subscribeSessionIds(clientFrames[1]))
        assertEquals("c_2", subscribeFrameId(clientFrames[1]))
    }

    @Test
    fun `events for unsubscribed sessions are dropped, subscribed ones are delivered`() = runBlocking {
        sessionIds.value = linkedSetOf("session_1")
        val events = CopyOnWriteArrayList<SessionListEvent>()
        server.enqueue(
            webSocketUpgradeResponse(
                recordingListener(
                    CopyOnWriteArrayList(),
                    // Answer the subscribe: two own events and one foreign — the 0.31.0
                    // unsolicited-broadcast caveat (docs/server-api.md).
                    onClientFrame = { webSocket ->
                        webSocket.send(workChangedFrame("session_1", busy = true, pendingInteraction = "approval"))
                        webSocket.send(workChangedFrame("session_2", busy = true, pendingInteraction = "approval"))
                        webSocket.send(metaUpdatedFrame("session_1", "Auto title"))
                    },
                ),
            ),
        )
        coroutineScope {
            val job = launch { socket.observe(settings(), sessionIds).collect { events += it } }
            try {
                awaitCondition { events.size >= 2 }
                // The foreign frame must have arrived and still produce nothing.
                delay(200)
            } finally {
                job.cancel()
            }
        }

        assertEquals(
            listOf(
                SessionListEvent.WorkChanged("session_1", busy = true, pendingInteraction = "approval"),
                SessionListEvent.MetaUpdated("session_1", "Auto title"),
            ),
            events,
        )
    }

    @Test
    fun `a malformed frame is dropped without killing the connection`() = runBlocking {
        sessionIds.value = linkedSetOf("session_1")
        val events = CopyOnWriteArrayList<SessionListEvent>()
        server.enqueue(
            webSocketUpgradeResponse(
                recordingListener(
                    CopyOnWriteArrayList(),
                    onClientFrame = { webSocket ->
                        webSocket.send("this is not json")
                        webSocket.send(workChangedFrame("session_1", busy = false, pendingInteraction = "none"))
                    },
                ),
            ),
        )
        coroutineScope {
            val job = launch { socket.observe(settings(), sessionIds).collect { events += it } }
            try {
                awaitCondition { events.isNotEmpty() }
            } finally {
                job.cancel()
            }
        }

        assertEquals(
            listOf(SessionListEvent.WorkChanged("session_1", busy = false, pendingInteraction = "none")),
            events,
        )
    }

    @Test
    fun `a reconnect resubscribes all ids and emits Reconnected once`() = runBlocking {
        sessionIds.value = linkedSetOf("session_1", "session_2")
        val firstFrames = CopyOnWriteArrayList<String>()
        val secondFrames = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<SessionListEvent>()
        // First connection drops as soon as the subscribe lands; the second stays up.
        server.enqueue(
            webSocketUpgradeResponse(
                recordingListener(firstFrames, onClientFrame = { it.close(1000, "drop") }),
            ),
        )
        server.enqueue(webSocketUpgradeResponse(recordingListener(secondFrames)))
        coroutineScope {
            val job = launch { socket.observe(settings(), sessionIds).collect { events += it } }
            try {
                awaitCondition { events.contains(SessionListEvent.Reconnected) }
                awaitCondition { secondFrames.isNotEmpty() }
                // Settle: exactly one resubscribe and one Reconnected.
                delay(200)
            } finally {
                job.cancel()
            }
        }

        assertEquals(setOf("session_1", "session_2"), subscribeSessionIds(firstFrames.single()))
        // The resubscribe carries the FULL set (fresh connection, nothing subscribed).
        assertEquals(setOf("session_1", "session_2"), subscribeSessionIds(secondFrames.single()))
        assertEquals(1, events.count { it == SessionListEvent.Reconnected })
    }

    private fun workChangedFrame(sessionId: String, busy: Boolean, pendingInteraction: String): String =
        """{"type":"event.session.work_changed","seq":9,"epoch":"ep_1","session_id":"$sessionId",""" +
            """"timestamp":"2026-08-02T00:00:00.000Z","payload":{"type":"event.session.work_changed",""" +
            """"busy":$busy,"main_turn_active":$busy,"pending_interaction":"$pendingInteraction",""" +
            """"agentId":"main","sessionId":"$sessionId"}}"""

    private fun metaUpdatedFrame(sessionId: String, title: String): String =
        """{"type":"session.meta.updated","seq":3,"epoch":"ep_1","session_id":"$sessionId",""" +
            """"timestamp":"2026-08-02T00:00:00.000Z","payload":{"type":"session.meta.updated",""" +
            """"title":"$title","patch":{"title":"$title","isCustomTitle":false,"lastPrompt":"$title"},""" +
            """"agentId":"main","sessionId":"$sessionId"}}"""
}
