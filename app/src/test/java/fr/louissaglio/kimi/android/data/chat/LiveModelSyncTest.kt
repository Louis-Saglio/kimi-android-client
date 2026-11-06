package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.network.ApiClient
import fr.louissaglio.kimi.android.data.network.BearerTokenInterceptor
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Ticket 107 live check: the data layer read against the REAL server (skipped unless
 * `KIMI_LIVE_SERVER_URL` and `KIMI_LIVE_SERVER_TOKEN` are set — no credentials in the
 * repo). Pins the contract the chip depends on: the models catalog resolves the bound
 * ids to display names. (The binding itself rides the socket's `agent` frame since
 * server 2.5.0 — ticket 177 — covered by SessionSocketTest/ChatViewModelTest.)
 */
class LiveModelSyncTest {

    private val serverUrl = System.getenv("KIMI_LIVE_SERVER_URL")
    private val token = System.getenv("KIMI_LIVE_SERVER_TOKEN")

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient =
        OkHttpClient.Builder()
            .addInterceptor(BearerTokenInterceptor { settings() })
            .build()
    private val modelsRepository = ModelsRepository(ApiClient(httpClient, json))

    private fun settings() = ServerSettings(serverUrl = serverUrl!!, token = token!!)

    private fun assumeLive() = assumeTrue(serverUrl != null && token != null)

    @Test
    fun `catalog resolves the bound ids and the server default`() = runBlocking {
        assumeLive()
        val models = modelsRepository.models(settings()).getOrThrow()
        assertTrue(models.any { it.model == "deepseek/deepseek-v4-pro" && it.displayName.isNotBlank() })
        assertEquals("Kimi Platform/kimi-k3", modelsRepository.defaultModel(settings()))
    }
}
