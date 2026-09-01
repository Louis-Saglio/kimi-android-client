package fr.louissaglio.kimi.android.ui.settings

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.chat.ModelsRepository
import fr.louissaglio.kimi.android.data.network.ApiClient
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import fr.louissaglio.kimi.android.testutil.testConnectionChecker
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Settings "Models" section (ticket 109): the real [ModelsRepository] on a
 * [MockWebServer], same harness as SessionListViewModelTest. The config fetch runs on
 * real I/O threads, so waits use real-time timeouts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server.start()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        appScope.cancel()
        server.close()
    }

    private fun createViewModel(): SettingsViewModel {
        val settingsRepository = SettingsRepository(
            FakePreferencesDataStore(
                preferencesOf(stringPreferencesKey("server_url") to server.url("/").toString()),
            ),
            appScope,
        )
        return SettingsViewModel(
            settingsRepository,
            testConnectionChecker(),
            ModelsRepository(ApiClient(OkHttpClient(), json)),
        )
    }

    @Test
    fun `the sheet's effort section stays empty when the default model has no support_efforts`() = runBlocking {
        server.enqueue(MockResponse(body = configBody(defaultModel = "deepseek/deepseek-chat")))
        server.enqueue(MockResponse(body = MODELS_BODY))

        val viewModel = createViewModel()
        awaitState(viewModel.uiState) { it.serverConfig != null }

        val state = viewModel.uiState.value
        assertEquals("DeepSeek Chat", state.defaultModelLabel)
        assertTrue(state.effortOptions.isEmpty())
        // No effort levels → the row value is the bare model name.
        assertEquals("DeepSeek Chat", state.defaultModelSummary)
    }

    @Test
    fun `the sheet's effort options follow the default model's support_efforts`() = runBlocking {
        server.enqueue(MockResponse(body = configBody(defaultModel = "kimi-code/k3")))
        server.enqueue(MockResponse(body = MODELS_BODY))

        val viewModel = createViewModel()
        awaitState(viewModel.uiState) { it.serverConfig != null }

        val state = viewModel.uiState.value
        assertEquals("K3", state.defaultModelLabel)
        assertEquals(listOf("low", "high", "max"), state.effortOptions)
        assertEquals("high", state.serverConfig?.defaultEffort)
        assertEquals("K3 · high", state.defaultModelSummary)
    }

    @Test
    fun `a failed default-model write keeps the shown value and emits the snackbar effect`() = runBlocking {
        server.enqueue(MockResponse(body = configBody(defaultModel = "kimi-code/k3")))
        server.enqueue(MockResponse(body = MODELS_BODY))
        val viewModel = createViewModel()
        awaitState(viewModel.uiState) { it.serverConfig != null }
        val effects = CopyOnWriteArrayList<SettingsViewModel.Effect>()
        val collectJob = appScope.launch { viewModel.effects.collect { effects.add(it) } }
        server.enqueue(MockResponse(code = 500))

        viewModel.onDefaultModelSelected("deepseek/deepseek-chat")
        awaitCondition { effects.isNotEmpty() }

        assertEquals("kimi-code/k3", viewModel.uiState.value.serverConfig?.defaultModel)
        val effect = effects.single() as SettingsViewModel.Effect.ConfigWriteFailed
        assertEquals("Unexpected server response (HTTP 500)", effect.message)
        collectJob.cancel()
    }

    @Test
    fun `a failed config fetch hides the Models section`() = runBlocking {
        server.enqueue(MockResponse(code = 401))

        val viewModel = createViewModel()
        awaitCondition { viewModel.uiState.value.isLoaded }
        awaitCondition { server.requestCount >= 1 }

        assertNull(viewModel.uiState.value.serverConfig)
    }

    private fun configBody(defaultModel: String) =
        """{"code":0,"msg":"success","data":{""" +
            """"default_model":"$defaultModel",""" +
            """"thinking":{"enabled":true,"effort":"high"},""" +
            """"providers":{"managed:kimi-code":{"type":"kimi",""" +
            """"base_url":"https://api.kimi.com/coding/v1","has_api_key":true}}""" +
            """},"request_id":"r"}"""

    private companion object {
        const val MODELS_BODY =
            """{"code":0,"msg":"success","data":{"items":[""" +
                """{"provider":"managed:kimi-code","model":"kimi-code/k3","display_name":"K3",""" +
                """"max_context_size":1048576,"support_efforts":["low","high","max"],"default_effort":"high"},""" +
                """{"provider":"deepseek","model":"deepseek/deepseek-chat","display_name":"DeepSeek Chat",""" +
                """"max_context_size":1000000}""" +
                """]},"request_id":"r"}"""
    }
}
