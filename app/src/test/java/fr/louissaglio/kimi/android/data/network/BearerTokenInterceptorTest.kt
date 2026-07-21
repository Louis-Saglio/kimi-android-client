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

class BearerTokenInterceptorTest {

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun callWith(settings: ServerSettings, request: Request = Request.Builder().url(server.url("/")).build()) {
        val client = OkHttpClient.Builder()
            .addInterceptor(BearerTokenInterceptor { settings })
            .build()
        client.newCall(request).execute().close()
    }

    @Test
    fun `adds Bearer Authorization header built from stored settings`() {
        server.enqueue(MockResponse().setBody("{}"))

        callWith(ServerSettings(serverUrl = "", token = "tok-abc-123"))

        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer tok-abc-123", recorded.getHeader("Authorization"))
    }

    @Test
    fun `adds no header when token is blank`() {
        server.enqueue(MockResponse().setBody("{}"))

        callWith(ServerSettings(serverUrl = "", token = ""))

        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `does not overwrite an Authorization header already set on the request`() {
        server.enqueue(MockResponse().setBody("{}"))
        val request = Request.Builder().url(server.url("/"))
            .header("Authorization", "Bearer other-token")
            .build()

        callWith(ServerSettings(serverUrl = "", token = "tok-abc-123"), request)

        val recorded = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer other-token", recorded.getHeader("Authorization"))
    }
}
