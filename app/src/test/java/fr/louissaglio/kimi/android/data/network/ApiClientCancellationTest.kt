package fr.louissaglio.kimi.android.data.network

import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A cancelled caller stops a GET at once (ticket 185, B7): `execute()` alone would
 * keep it blocked until the response or OkHttp's read timeout. A POST runs to the end. */
class ApiClientCancellationTest {

    private val server = MockWebServer()
    private val release = CountDownLatch(1)
    private val arrived = CountDownLatch(1)
    private val canceledCalls = AtomicInteger()
    private val api = ApiClient(
        OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    canceledCalls.incrementAndGet()
                }
            })
            .build(),
        Json,
    )

    @Before
    fun setUp() {
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                release.await(30, TimeUnit.SECONDS)
                return MockResponse(body = "ok")
            }
        }
    }

    @After
    fun tearDown() {
        release.countDown()
        server.close()
    }

    private fun settings() = ServerSettings(serverUrl = server.url("/").toString(), token = "t")

    @Test
    fun `cancelling a GET aborts the blocked call`() = runBlocking {
        val job = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            api.get(settings(), "/slow") { Result.success(it) }
        }
        withContext(Dispatchers.IO) { assertTrue(arrived.await(5, TimeUnit.SECONDS)) }

        // Without the abort the join waits for the held response (10 s read timeout).
        withTimeout(5_000) { job.cancelAndJoin() }

        assertEquals(1L, release.count)
        assertEquals(1, canceledCalls.get())
    }

    @Test
    fun `cancelling a POST lets the call finish`() = runBlocking {
        val job = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            api.post(settings(), "/slow", api.jsonBody {}) { Result.success(it) }
        }
        withContext(Dispatchers.IO) { assertTrue(arrived.await(5, TimeUnit.SECONDS)) }

        job.cancel()
        release.countDown()
        withTimeout(5_000) { job.join() }

        assertEquals(0, canceledCalls.get())
    }
}
