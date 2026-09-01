package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.network.ApiClient
import fr.louissaglio.kimi.android.data.network.BearerTokenInterceptor
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Ticket 107 live check: the data layer read against the REAL server (skipped unless
 * `KIMI_LIVE_SERVER_URL` and `KIMI_LIVE_SERVER_TOKEN` are set — no credentials in the
 * repo). Pins the contract the chip depends on: `/status` reports the session's
 * persisted model/effort binding — including for sessions last bound before a server
 * restart (their list entry reads `agent_config.model: ""` while cold) — and the
 * models catalog resolves the bound ids to display names.
 */
class LiveModelSyncTest {

    private val serverUrl = System.getenv("KIMI_LIVE_SERVER_URL")
    private val token = System.getenv("KIMI_LIVE_SERVER_TOKEN")

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient =
        OkHttpClient.Builder()
            .addInterceptor(BearerTokenInterceptor { settings() })
            .build()
    private val chatApi = ChatApi(ApiClient(httpClient, json), ChatMessageMapper(json))
    private val modelsRepository = ModelsRepository(ApiClient(httpClient, json))

    private fun settings() = ServerSettings(serverUrl = serverUrl!!, token = token!!)

    private fun assumeLive() = assumeTrue(serverUrl != null && token != null)

    @Test
    fun `status reports the persisted binding of the reported session`() = runBlocking {
        assumeLive()
        val status = chatApi.sessionStatus(
            settings(),
            "session_626e558d-1969-4ab6-b15e-9855dd0b00e6",
        ).getOrThrow()
        assertEquals("deepseek/deepseek-v4-pro", status.model)
        assertEquals("max", status.thinkingLevel)
    }

    @Test
    fun `status reports bindings persisted before a server restart`() = runBlocking {
        assumeLive()
        // Both were last active 2026-08-25/27, before the 2026-08-28 restart, and still
        // read `agent_config.model: ""` in the sessions list (cold) when picked.
        val deepseek = chatApi.sessionStatus(
            settings(),
            "session_311e11d5-7515-4e07-99fe-a486e3371ca3",
        ).getOrThrow()
        assertEquals("deepseek/deepseek-v4-pro", deepseek.model)
        assertEquals("max", deepseek.thinkingLevel)
        val k3 = chatApi.sessionStatus(
            settings(),
            "session_82cf699c-f20d-4d23-b93a-78d5beb2708e",
        ).getOrThrow()
        assertEquals("kimi-code/k3-256k", k3.model)
        assertEquals("high", k3.thinkingLevel)
    }

    @Test
    fun `status of a never-bound session reports no model`() = runBlocking {
        assumeLive()
        val status = chatApi.sessionStatus(
            settings(),
            "session_9565d62c-43b8-4918-a05f-be13164840cb",
        ).getOrThrow()
        assertNull(status.model)
        assertNull(status.thinkingLevel)
    }

    @Test
    fun `catalog resolves the bound ids and the server default`() = runBlocking {
        assumeLive()
        val models = modelsRepository.models(settings()).getOrThrow()
        assertTrue(models.any { it.model == "deepseek/deepseek-v4-pro" && it.displayName.isNotBlank() })
        assertEquals("Kimi Platform/kimi-k3", modelsRepository.defaultModel(settings()))
    }
}
