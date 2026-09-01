package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.network.ApiClient
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The server-config surface of [ModelsRepository] (ticket 109 — `GET`/`POST
 * /api/v1/config`, docs/server-api.md). */
class ModelsRepositoryTest {

    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val repository = ModelsRepository(ApiClient(OkHttpClient(), json))

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun settings() = ServerSettings(serverUrl = server.url("/").toString())

    @Test
    fun `config maps the default model, the thinking effort and the providers`() = runBlocking {
        server.enqueue(MockResponse(body = CONFIG_BODY))

        val config = repository.config(settings()).getOrThrow()

        assertEquals("kimi-code/k3", config.defaultModel)
        assertEquals("high", config.defaultEffort)
        assertEquals(
            listOf(
                ServerProvider(
                    name = "managed:kimi-code",
                    type = "kimi",
                    baseUrl = "https://api.kimi.com/coding/v1",
                    hasApiKey = true,
                ),
                ServerProvider(
                    name = "deepseek",
                    type = "openai",
                    baseUrl = "https://api.deepseek.com",
                    hasApiKey = false,
                ),
            ),
            config.providers,
        )
    }

    @Test
    fun `setDefaultModel posts the snake_case body and the cache serves the new default`() = runBlocking {
        server.enqueue(MockResponse(body = CONFIG_BODY))
        assertEquals("kimi-code/k3", repository.defaultModel(settings()))
        server.takeRequest()
        server.enqueue(MockResponse(body = """{"code":0,"msg":"success","data":{}}"""))

        assertTrue(repository.setDefaultModel(settings(), "kimi-code/k3-256k").isSuccess)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/config", request.target)
        assertEquals("""{"default_model":"kimi-code/k3-256k"}""", request.body!!.utf8())
        // Served from the patched cache — no refetch.
        assertEquals("kimi-code/k3-256k", repository.defaultModel(settings()))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `setDefaultEffort posts the thinking object and a failed write leaves the cache untouched`() = runBlocking {
        server.enqueue(MockResponse(body = CONFIG_BODY))
        assertEquals("high", repository.config(settings()).getOrThrow().defaultEffort)
        server.takeRequest()
        server.enqueue(MockResponse(code = 500))

        val error = repository.setDefaultEffort(settings(), "max").exceptionOrNull()

        assertEquals(DataError.Http(500), error)
        val request = server.takeRequest()
        assertEquals("""{"thinking":{"effort":"max"}}""", request.body!!.utf8())
        assertEquals("high", repository.config(settings()).getOrThrow().defaultEffort)
        assertEquals(2, server.requestCount)
    }

    private companion object {
        // Verbatim trimmed capture (0.39.1, 2026-09-01): the consumed fields are
        // snake_case on both the GET and the POST echo.
        const val CONFIG_BODY =
            """{"code":0,"msg":"success","data":{""" +
                """"default_model":"kimi-code/k3",""" +
                """"thinking":{"enabled":true,"effort":"high"},""" +
                """"providers":{""" +
                """"managed:kimi-code":{"type":"kimi","base_url":"https://api.kimi.com/coding/v1","has_api_key":true},""" +
                """"deepseek":{"type":"openai","base_url":"https://api.deepseek.com","has_api_key":false}""" +
                """}},"request_id":"r"}"""
    }
}
