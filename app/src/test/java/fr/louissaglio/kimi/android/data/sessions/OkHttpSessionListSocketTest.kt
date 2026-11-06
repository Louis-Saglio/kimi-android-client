package fr.louissaglio.kimi.android.data.sessions

import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.webSocketUpgradeResponse
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Frame tests for [OkHttpSessionListSocket] on the MockWebServer WebSocket harness
 * (same approach as SessionSocketTest): the server-side listener pushes frames on open
 * and records the client's frames, so what goes over the wire — nothing but pongs —
 * is asserted directly. Waits run on the real clock — the socket does real I/O.
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

    /**
     * Server-side listener: [onOpen] pushes the server's frames as soon as the socket
     * is up (the list sends no subscribe to react to), [clientFrames] records whatever
     * the client sends back.
     */
    private fun serverListener(
        clientFrames: MutableList<String> = CopyOnWriteArrayList(),
        onOpen: (WebSocket) -> Unit = {},
    ) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket)

        override fun onMessage(webSocket: WebSocket, text: String) {
            clientFrames += text
        }
    }

    @Test
    fun `a server ping is answered with a pong echoing the nonce`() = runBlocking {
        // 0.35.0 heartbeat (docs/server-api.md, Heartbeat): the answer must go over the
        // wire as exactly one pong with the echoed nonce — and nothing else is sent.
        sessionIds.value = linkedSetOf("session_1")
        val clientFrames = CopyOnWriteArrayList<String>()
        server.enqueue(
            webSocketUpgradeResponse(
                serverListener(clientFrames) { webSocket ->
                    webSocket.send("""{"type":"ping","payload":{"nonce":"abc"}}""")
                },
            ),
        )
        coroutineScope {
            val job = launch { socket.observe(settings(), sessionIds).collect {} }
            try {
                awaitCondition { clientFrames.isNotEmpty() }
                // Settle: no second pong, no other frame.
                delay(200)
            } finally {
                job.cancel()
            }
        }

        assertEquals(listOf("""{"type":"pong","payload":{"nonce":"abc"}}"""), clientFrames)
    }

    @Test
    fun `a ping without a nonce is ignored`() = runBlocking {
        sessionIds.value = linkedSetOf("session_1")
        val clientFrames = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<SessionListEvent>()
        server.enqueue(
            webSocketUpgradeResponse(
                serverListener(clientFrames) { webSocket ->
                    webSocket.send("""{"type":"ping","payload":{}}""")
                    // One deliverable frame after the ping proves the socket survived.
                    webSocket.send(workChangedFrame("session_1", busy = true, pendingInteraction = "none"))
                },
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
        // Nothing ever went out: no pong, no subscribe.
        assertTrue(clientFrames.isEmpty())
    }

    @Test
    fun `no frame is sent on connect nor when the loaded ids grow`() = runBlocking {
        // Server 2.5: the per-session pushes reach every connection unsubscribed, and
        // the old raw subscribe frame is silently ignored (docs/server-api.md, Control
        // frames) — the list socket must stay silent apart from pongs.
        sessionIds.value = linkedSetOf("session_1", "session_2")
        val clientFrames = CopyOnWriteArrayList<String>()
        val opened = CompletableDeferred<Unit>()
        server.enqueue(webSocketUpgradeResponse(serverListener(clientFrames) { opened.complete(Unit) }))
        coroutineScope {
            val job = launch { socket.observe(settings(), sessionIds).collect {} }
            try {
                withTimeout(5_000) { opened.await() }
                // The next page landed: the ids flow emits the grown set.
                sessionIds.value = linkedSetOf("session_1", "session_2", "session_3")
                delay(200)
            } finally {
                job.cancel()
            }
        }

        assertTrue(clientFrames.isEmpty())
    }

    @Test
    fun `events for unloaded sessions are dropped, loaded ones are delivered`() = runBlocking {
        sessionIds.value = linkedSetOf("session_1")
        val events = CopyOnWriteArrayList<SessionListEvent>()
        server.enqueue(
            webSocketUpgradeResponse(
                // Two own events and one foreign: the per-session pushes reach every
                // connection (docs/server-api.md, Global events).
                serverListener { webSocket ->
                    webSocket.send(workChangedFrame("session_1", busy = true, pendingInteraction = "approval"))
                    webSocket.send(workChangedFrame("session_2", busy = true, pendingInteraction = "approval"))
                    webSocket.send(metaUpdatedFrame("session_1", "Auto title"))
                },
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
    fun `a newly loaded page's ids pass the filter, ids dropped from the set keep passing`() = runBlocking {
        sessionIds.value = linkedSetOf("session_1")
        val events = CopyOnWriteArrayList<SessionListEvent>()
        val serverSocket = CompletableDeferred<WebSocket>()
        server.enqueue(webSocketUpgradeResponse(serverListener { serverSocket.complete(it) }))
        coroutineScope {
            val job = launch { socket.observe(settings(), sessionIds).collect { events += it } }
            try {
                val ws = withTimeout(5_000) { serverSocket.await() }
                // Not loaded yet: dropped.
                ws.send(metaUpdatedFrame("session_3", "Too early"))
                ws.send(metaUpdatedFrame("session_1", "First"))
                awaitCondition { events.size == 1 }
                // The next page landed; session_1 left the set (mode switch), but the
                // observed set only grows for the life of the connection.
                sessionIds.value = linkedSetOf("session_3")
                // The socket's id collector runs on this runBlocking event loop: the
                // assignment above queued its resumption, so yielding once lets it add
                // session_3 before the frames below are even sent. No timing margin.
                yield()
                ws.send(metaUpdatedFrame("session_3", "Paged in"))
                ws.send(metaUpdatedFrame("session_1", "Still observed"))
                awaitCondition { events.size == 3 }
            } finally {
                job.cancel()
            }
        }

        assertEquals(
            listOf(
                SessionListEvent.MetaUpdated("session_1", "First"),
                SessionListEvent.MetaUpdated("session_3", "Paged in"),
                SessionListEvent.MetaUpdated("session_1", "Still observed"),
            ),
            events,
        )
    }

    @Test
    fun `the global session archived event is delivered with the payload session id`() = runBlocking {
        // 0.38.0 (docs/server-api.md, Global events — ticket 105): the frame below is
        // the verbatim capture. Its envelope session_id is the __global__ watermark —
        // never a loaded id — so it must survive the foreign-frame filter,
        // and the real id comes from the payload's camelCase sessionId.
        sessionIds.value = linkedSetOf("session_1")
        val events = CopyOnWriteArrayList<SessionListEvent>()
        server.enqueue(
            webSocketUpgradeResponse(
                serverListener { webSocket -> webSocket.send(sessionArchivedFrame) },
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
    fun `the global session deleted event is delivered with the payload session id`() = runBlocking {
        // 0.42.0 (docs/server-api.md, Global events — ticket 126): the frame below is
        // the verbatim capture. Same __global__ envelope as the archived sibling, so it
        // must survive the foreign-frame filter; the real id is the payload's
        // camelCase sessionId.
        sessionIds.value = linkedSetOf("session_1")
        val events = CopyOnWriteArrayList<SessionListEvent>()
        server.enqueue(
            webSocketUpgradeResponse(
                serverListener { webSocket -> webSocket.send(sessionDeletedFrame) },
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
            listOf(SessionListEvent.SessionDeleted("session_0e2d9419-353f-4e08-bb59-dbfdacac1467")),
            events,
        )
    }

    @Test
    fun `the session created event is delivered with the payload session`() = runBlocking {
        // Verified on 0.42.0 (docs/server-api.md, Global events — ticket 129): the
        // frame below is the verbatim capture. Its envelope session_id is the NEW
        // session's own id — never a loaded one — so it must be parsed ahead
        // of the foreign-frame filter, and the payload's nested session is the
        // snake_case REST shape.
        sessionIds.value = linkedSetOf("session_1")
        val events = CopyOnWriteArrayList<SessionListEvent>()
        server.enqueue(
            webSocketUpgradeResponse(
                serverListener { webSocket -> webSocket.send(sessionCreatedFrame) },
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
                serverListener { webSocket ->
                    webSocket.send(configChangedFrame)
                    webSocket.send(modelCatalogChangedFrame)
                },
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
                serverListener { webSocket ->
                    webSocket.send("this is not json")
                    webSocket.send(workChangedFrame("session_1", busy = false, pendingInteraction = "none"))
                },
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
    fun `a reconnect emits Reconnected once and the new connection filters on the loaded ids`() = runBlocking {
        sessionIds.value = linkedSetOf("session_1", "session_2")
        val firstFrames = CopyOnWriteArrayList<String>()
        val secondFrames = CopyOnWriteArrayList<String>()
        val events = CopyOnWriteArrayList<SessionListEvent>()
        // First connection drops as soon as it opens; the second stays up and pushes
        // one loaded and one foreign frame.
        server.enqueue(webSocketUpgradeResponse(serverListener(firstFrames) { it.close(1000, "drop") }))
        server.enqueue(
            webSocketUpgradeResponse(
                serverListener(secondFrames) { webSocket ->
                    webSocket.send(workChangedFrame("session_9", busy = true, pendingInteraction = "none"))
                    webSocket.send(workChangedFrame("session_2", busy = true, pendingInteraction = "none"))
                },
            ),
        )
        coroutineScope {
            val job = launch { socket.observe(settings(), sessionIds).collect { events += it } }
            try {
                awaitCondition { events.size >= 2 }
                // Settle: exactly one Reconnected, the foreign frame still dropped.
                delay(200)
            } finally {
                job.cancel()
            }
        }

        assertEquals(
            listOf(
                SessionListEvent.Reconnected,
                SessionListEvent.WorkChanged("session_2", busy = true, pendingInteraction = "none"),
            ),
            events,
        )
        // Neither connection was sent anything.
        assertTrue(firstFrames.isEmpty())
        assertTrue(secondFrames.isEmpty())
    }

    @Test
    fun `inbound silence past three heartbeats drops the socket and reconnects`() = runBlocking {
        // The shared stall watchdog (ticket 152) rides this socket too: a stalled list
        // connection is closed, and the backoff reconnect's Reconnected drives the
        // ViewModel's silent REST re-sync.
        sessionIds.value = linkedSetOf("session_1")
        val events = CopyOnWriteArrayList<SessionListEvent>()
        server.enqueue(
            webSocketUpgradeResponse(
                serverListener { webSocket ->
                    // 50 ms heartbeat → the stall threshold is 150 ms of silence.
                    webSocket.send("""{"type":"server_hello","payload":{"heartbeat_ms":50}}""")
                    // Then silence forever — the stall.
                },
            ),
        )
        server.enqueue(webSocketUpgradeResponse(serverListener()))
        coroutineScope {
            val job = launch { socket.observe(settings(), sessionIds).collect { events += it } }
            try {
                // The reconnect follows the first backoff step (1 s) after the stall.
                awaitCondition(timeoutMs = 10_000) { events.contains(SessionListEvent.Reconnected) }
                // Settle: the second connection stays up, no further reconnect.
                delay(200)
            } finally {
                job.cancel()
            }
        }

        assertEquals(listOf<SessionListEvent>(SessionListEvent.Reconnected), events)
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

    // Verbatim 0.42.0 capture (docs/server-api.md, "Global events — event.session.deleted").
    private val sessionDeletedFrame =
        """{"type":"event.session.deleted","seq":276,"session_id":"__global__",""" +
            """"timestamp":"2026-09-09T19:33:22.675Z","payload":{"type":"event.session.deleted",""" +
            """"workspace_id":"wd_spike_6baf4c130e63","agentId":"main",""" +
            """"sessionId":"session_0e2d9419-353f-4e08-bb59-dbfdacac1467"},""" +
            """"epoch":"ep_01KX01QDWWRZFHBCEEQ8A5SZ8T"}"""

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
