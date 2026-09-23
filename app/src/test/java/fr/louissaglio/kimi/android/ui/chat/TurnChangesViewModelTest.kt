package fr.louissaglio.kimi.android.ui.chat

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.navigation.TurnChanges
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs the ViewModel against the real data layer on a MockWebServer (the
 * JobDetailViewModelTest pattern). */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnChangesViewModelTest {

    private val server = MockWebServer()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private lateinit var viewModel: TurnChangesViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.start()
        val dataStore = FakePreferencesDataStore(
            preferencesOf(stringPreferencesKey("server_url") to server.url("/").toString()),
        )
        viewModel = TurnChangesViewModel(
            testChatRepository(dataStore),
            SettingsRepository(dataStore, appScope),
            TurnChanges("session_1", 2),
        )
    }

    @After
    fun tearDown() {
        server.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `an unrecorded turn lands on the empty state`() = runBlocking {
        // docs/server-api.md: a turn with nothing recorded answers
        // {"changes":[],"recorded":false} — the screen's empty state covers it.
        server.enqueue(
            MockResponse(body =
                """{"code":0,"msg":"success","data":{"changes":[],"recorded":false},"request_id":"r"}""",
            ),
        )

        awaitState(viewModel.uiState) { it.changes != null }

        val state = viewModel.uiState.value
        assertEquals(2, state.turnId)
        assertTrue(state.changes!!.isEmpty())
        assertNull(state.error)
        assertEquals("/api/v1/sessions/session_1/file-history/changes?turn_id=2", server.takeRequest().target)
    }
}
