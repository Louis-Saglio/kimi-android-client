package fr.louissaglio.kimi.android.data.network

import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.testutil.testConnectionChecker
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ConnectionCheckerTest {

    private val server = MockWebServer()
    private val checker = testConnectionChecker()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun settings(token: String = "") =
        ServerSettings(serverUrl = server.url("/").toString(), token = token)

    @Test
    fun `a token pasted with a trailing newline is trimmed, not reported as invalid url`() = runBlocking {
        server.enqueue(MockResponse(body = """{"code":0,"msg":"success"}"""))

        val result = checker.check(settings(token = "secret-token\n"))

        assertEquals(ConnectionChecker.Result.Success, result)
        assertEquals("Bearer secret-token", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `a malformed server url is reported as invalid url`() = runBlocking {
        val result = checker.check(ServerSettings(serverUrl = "not a url"))

        assertEquals(ConnectionChecker.Result.InvalidUrl, result)
    }

    @Test
    fun `a token containing forbidden header characters is reported as invalid token`() = runBlocking {
        val result = checker.check(settings(token = "secret\ntoken"))

        assertEquals(ConnectionChecker.Result.InvalidToken, result)
    }
}
