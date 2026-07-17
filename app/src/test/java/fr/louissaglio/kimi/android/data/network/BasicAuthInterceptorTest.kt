package fr.louissaglio.kimi.android.data.network

import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BasicAuthInterceptorTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun callWith(settings: ServerSettings, request: Request = Request.Builder().url(server.url("/")).build()) {
        val client = OkHttpClient.Builder()
            .addInterceptor(BasicAuthInterceptor { settings })
            .build()
        client.newCall(request).execute().close()
    }

    @Test
    fun `adds Basic Authorization header built from stored settings`() {
        server.enqueue(MockResponse().setBody("{}"))

        callWith(ServerSettings(serverUrl = "", username = "louis", password = "s3cret-p4ss"))

        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Basic bG91aXM6czNjcmV0LXA0c3M=", recorded.getHeader("Authorization"))
    }

    @Test
    fun `adds no header when credentials are blank`() {
        server.enqueue(MockResponse().setBody("{}"))

        callWith(ServerSettings(serverUrl = "", username = "", password = ""))

        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `does not overwrite an Authorization header already set on the request`() {
        server.enqueue(MockResponse().setBody("{}"))
        val request = Request.Builder().url(server.url("/"))
            .header("Authorization", "Bearer token")
            .build()

        callWith(ServerSettings(serverUrl = "", username = "louis", password = "s3cret-p4ss"), request)

        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer token", recorded.getHeader("Authorization"))
    }
}
