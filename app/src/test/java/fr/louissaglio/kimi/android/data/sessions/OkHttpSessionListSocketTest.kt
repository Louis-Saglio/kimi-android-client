package fr.louissaglio.kimi.android.data.sessions

import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.webSocketUpgradeResponse
import java.time.Instant
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
    fun `a server ping is answered with a pong echoing the nonce`() = runBlocking {
        // 0.35.0 heartbeat (docs/server-api.md, Heartbeat): the answer must go over the
        // wire as exactly one pong with the echoed nonce.
        sessionIds.value = linkedSetOf("session_1")
        val clientFrames = CopyOnWriteArrayList<String>()
        server.enqueue(
            webSocketUpgradeResponse(
                recordingListener(
                    clientFrames,
                    // Ping once, right after the subscribe lands (answering the ping
                    // would re-trigger this hook otherwise).
                    onClientFrame = { webSocket ->
                        if (clientFrames.size == 1) {
                            webSocket.send("""{"type":"ping","payload":{"nonce":"abc"}}""")
                        }
                    },
                ),
            ),
        )
        coroutineScope {
            val job = launch { socket.observe(settings(), sessionIds).collect {} }
            try {
                awaitCondition { clientFrames.size >= 2 }
                // Settle: no second pong, no other frame.
                delay(200)
            } finally {
                job.cancel()
            }
        }

        assertEquals(2, clientFrames.size)
        assertEquals("""{"type":"pong","payload":{"nonce":"abc"}}""", clientFrames[1])
    }

    @Test
    fun `a ping without a nonce is ignored`() = runBlocking {
        sessionIds.value = linkedSetOf("session_1")
        val clientFrames = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<SessionListEvent>()
        server.enqueue(
            webSocketUpgradeResponse(
                recordingListener(
                    clientFrames,
                    onClientFrame = { webSocket ->
                        if (clientFrames.size == 1) {
                            webSocket.send("""{"type":"ping","payload":{}}""")
                            // One deliverable frame after the ping proves the socket survived.
                            webSocket.send(workChangedFrame("session_1", busy = true, pendingInteraction = "none"))
                        }
                    },
                ),
            ),
        )
        coroutineScope {
            val job = launch { socket.observe(settings(), sessionIds).collect { events += it } }
            try {
                awaitCondition { events.isNotEmpty() }
                // Settle: the malformed ping must have arrived and produced no frame.
                delay(200)
            } finally {
                job.cancel()
            }
        }

        assertEquals(
            listOf(SessionListEvent.WorkChanged("session_1", busy = true, pendingInteraction = "none")),
            events,
        )
        // Only the subscribe ever went out.
        assertEquals(1, clientFrames.size)
    }

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
    fun `the global session archived event is delivered with the payload session id`() = runBlocking {
        // 0.38.0 (docs/server-api.md, Global events — ticket 105): the frame below is
        // the verbatim capture. Its envelope session_id is the __global__ watermark —
        // never in the subscribed set — so it must survive the foreign-frame filter,
        // and the real id comes from the payload's camelCase sessionId.
        sessionIds.value = linkedSetOf("session_1")
        val events = CopyOnWriteArrayList<SessionListEvent>()
        server.enqueue(
            webSocketUpgradeResponse(
                recordingListener(
                    CopyOnWriteArrayList(),
                    onClientFrame = { webSocket -> webSocket.send(sessionArchivedFrame) },
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
            listOf(SessionListEvent.SessionArchived("session_a57d4784-4fe8-4268-bbe1-b5f3e23b37ba")),
            events,
        )
    }

    @Test
    fun `the session created event is delivered with the payload session`() = runBlocking {
        // Verified on 0.42.0 (docs/server-api.md, Global events — ticket 129): the
        // frame below is the verbatim capture. Its envelope session_id is the NEW
        // session's own id — never in the subscribed set — so it must be parsed ahead
        // of the foreign-frame filter, and the payload's nested session is the
        // snake_case REST shape.
        sessionIds.value = linkedSetOf("session_1")
        val events = CopyOnWriteArrayList<SessionListEvent>()
        server.enqueue(
            webSocketUpgradeResponse(
                recordingListener(
                    CopyOnWriteArrayList(),
                    onClientFrame = { webSocket -> webSocket.send(sessionCreatedFrame) },
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
            listOf(
                SessionListEvent.SessionCreated(
                    Session(
                        id = "session_0fb965a3-733b-41a3-b863-05dc44881de5",
                        title = "ticket-129-capture-source-2",
                        cwd = "/home/ubuntu/spike",
                        updatedAt = Instant.parse("2026-09-14T10:18:49.118Z"),
                        isRunning = false,
                        isAwaitingInteraction = false,
                    ),
                ),
            ),
            events,
        )
    }

    @Test
    fun `the global config and model catalog changed events are delivered`() = runBlocking {
        // 0.39.1 (docs/server-api.md, Global events — ticket 110): configChangedFrame
        // is the trimmed verbatim capture; model_catalog.changed was never triggered
        // live, so its frame mirrors the same shape. Both payloads are ignored — the
        // frame type alone is the signal — and both survive the foreign-frame filter
        // on the __global__ envelope, like event.session.archived.
        sessionIds.value = linkedSetOf("session_1")
        val events = CopyOnWriteArrayList<SessionListEvent>()
        server.enqueue(
            webSocketUpgradeResponse(
                recordingListener(
                    CopyOnWriteArrayList(),
                    onClientFrame = { webSocket ->
                        webSocket.send(configChangedFrame)
                        webSocket.send(modelCatalogChangedFrame)
                    },
                ),
            ),
        )
        coroutineScope {
            val job = launch { socket.observe(settings(), sessionIds).collect { events += it } }
            try {
                awaitCondition { events.size >= 2 }
            } finally {
                job.cancel()
            }
        }

        assertEquals(
            listOf(SessionListEvent.ConfigChanged, SessionListEvent.ModelCatalogChanged),
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

    @Test
    fun `inbound silence past three heartbeats drops the socket and reconnects`() = runBlocking {
        // The shared stall watchdog (ticket 152) rides this socket too: a stalled list
        // connection is closed, and the backoff reconnect's resubscribe + Reconnected
        // drive the ViewModel's silent REST re-sync.
        sessionIds.value = linkedSetOf("session_1")
        val secondFrames = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<SessionListEvent>()
        server.enqueue(
            webSocketUpgradeResponse(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        // 50 ms heartbeat → the stall threshold is 150 ms of silence.
                        webSocket.send("""{"type":"server_hello","payload":{"heartbeat_ms":50}}""")
                        // Then silence forever — the stall.
                    }
                },
            ),
        )
        server.enqueue(webSocketUpgradeResponse(recordingListener(secondFrames)))
        coroutineScope {
            val job = launch { socket.observe(settings(), sessionIds).collect { events += it } }
            try {
                // The reconnect follows the first backoff step (1 s) after the stall.
                awaitCondition(timeoutMs = 10_000) { secondFrames.isNotEmpty() }
            } finally {
                job.cancel()
            }
        }

        assertEquals(setOf("session_1"), subscribeSessionIds(secondFrames.single()))
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

    // Verbatim 0.38.0 capture (docs/server-api.md, "Global events — event.session.archived").
    private val sessionArchivedFrame =
        """{"type":"event.session.archived","seq":28,"session_id":"__global__",""" +
            """"timestamp":"2026-08-20T19:31:40.052Z","payload":{"type":"event.session.archived",""" +
            """"workspace_id":"wd_spike_6baf4c130e63","agentId":"main",""" +
            """"sessionId":"session_a57d4784-4fe8-4268-bbe1-b5f3e23b37ba"},"epoch":"ep_01KX01QDWWRZFHBCEEQ8A5SZ8T"}"""

    // Verbatim 0.42.0 capture (docs/server-api.md, "Global events — event.session.created"):
    // note the envelope session_id is the created session itself, not __global__.
    private val sessionCreatedFrame =
        """{"type":"event.session.created","seq":1,"session_id":"session_0fb965a3-733b-41a3-b863-05dc44881de5",""" +
            """"timestamp":"2026-09-14T10:18:49.155Z","payload":{"type":"event.session.created",""" +
            """"session":{"id":"session_0fb965a3-733b-41a3-b863-05dc44881de5",""" +
            """"workspace_id":"wd_spike_6baf4c130e63","title":"ticket-129-capture-source-2",""" +
            """"created_at":"2026-09-14T10:18:49.118Z","updated_at":"2026-09-14T10:18:49.118Z",""" +
            """"busy":false,"main_turn_active":false,"pending_interaction":"none","archived":false,""" +
            """"metadata":{"cwd":"/home/ubuntu/spike"},"agent_config":{"model":""},""" +
            """"usage":{"input_tokens":0,"output_tokens":0,"cache_read_tokens":0,""" +
            """"cache_creation_tokens":0,"total_cost_usd":0,"context_tokens":0,"context_limit":0,""" +
            """"turn_count":0},"permission_rules":[],"message_count":0,"last_seq":0},""" +
            """"agentId":"main","sessionId":"session_0fb965a3-733b-41a3-b863-05dc44881de5"},""" +
            """"epoch":"ep_01M2FPTCY3629JDWB3V096PW34"}"""

    // Trimmed verbatim 0.39.1 capture (docs/server-api.md, "Global events —
    // event.config.changed"): the full redacted config projection rides in
    // payload.config; only the default_model key is kept here.
    private val configChangedFrame =
        """{"type":"event.config.changed","seq":181,"session_id":"__global__",""" +
            """"timestamp":"2026-08-28T19:46:13.649Z","payload":{"type":"event.config.changed",""" +
            """"changedFields":["thinking"],"config":{"default_model":"kimi-code/k3"}},""" +
            """"epoch":"ep_01M14XPS0FE7GX6J2H5NA8MP52"}"""

    // Never triggered live (docs/server-api.md): the shape mirrors event.config.changed's.
    private val modelCatalogChangedFrame =
        """{"type":"event.model_catalog.changed","seq":182,"session_id":"__global__",""" +
            """"timestamp":"2026-08-28T19:46:14.000Z","payload":{"type":"event.model_catalog.changed"},""" +
            """"epoch":"ep_01M14XPS0FE7GX6J2H5NA8MP52"}"""
}
