package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.chat.FileContent
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.navigation.TurnFileContent
import fr.louissaglio.kimi.android.testutil.awaitState
import fr.louissaglio.kimi.android.testutil.testChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Runs the ViewModel against the real data layer on a MockWebServer (the
 * TurnChangesViewModelTest pattern). */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnFileContentViewModelTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private lateinit var viewModel: TurnFileContentViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        val dataStore = FakePreferencesDataStore(
            preferencesOf(stringPreferencesKey("server_url") to server.url("/").toString()),
        )
        viewModel = TurnFileContentViewModel(
            testChatRepository(dataStore),
            SettingsRepository(dataStore, appScope),
            TurnFileContent("session_1", 2, "/home/ubuntu/spike/notes.txt"),
        )
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `both phases fetch in parallel on open`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"content":{"version":3,"content":"before text"}},"request_id":"r"}""",
            ),
        )
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"content":null},"request_id":"r"}""",
            ),
        )

        awaitState(viewModel.uiState) { it.before.resolved && it.after.resolved }

        val state = viewModel.uiState.value
        // Both phases fired on open — one request each (order across the two
        // parallel fetches is not deterministic, so compare the target set).
        assertEquals(
            setOf(
                "/api/v1/sessions/session_1/file-history/content?turn_id=2" +
                    "&path=%2Fhome%2Fubuntu%2Fspike%2Fnotes.txt&phase=start",
                "/api/v1/sessions/session_1/file-history/content?turn_id=2" +
                    "&path=%2Fhome%2Fubuntu%2Fspike%2Fnotes.txt&phase=end",
            ),
            setOf(server.takeRequest().target, server.takeRequest().target),
        )
        // Which side got which answer depends on the arrival order; the invariants
        // hold either way: one side carries its snapshot, the other resolved null.
        val snapshots = listOf(state.before.snapshot, state.after.snapshot)
        assertEquals(1, snapshots.count { it == FileContent(version = 3, content = "before text", binary = false) })
        assertEquals(1, snapshots.count { it == null })
        assertNull(state.before.error)
        assertNull(state.after.error)
    }

    @Test
    fun `onTabSelected toggles between Before and After with After the default`() = runBlocking {
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"content":null},"request_id":"r"}""",
            ),
        )
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"content":null},"request_id":"r"}""",
            ),
        )
        awaitState(viewModel.uiState) { it.before.resolved && it.after.resolved }

        assertEquals(TurnFileContentViewModel.Tab.After, viewModel.uiState.value.tab)

        viewModel.onTabSelected(TurnFileContentViewModel.Tab.Before)
        assertEquals(TurnFileContentViewModel.Tab.Before, viewModel.uiState.value.tab)

        viewModel.onTabSelected(TurnFileContentViewModel.Tab.After)
        assertEquals(TurnFileContentViewModel.Tab.After, viewModel.uiState.value.tab)
    }
}
