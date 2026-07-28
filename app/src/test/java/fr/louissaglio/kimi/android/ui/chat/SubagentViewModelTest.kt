package fr.louissaglio.kimi.android.ui.chat

import androidx.lifecycle.SavedStateHandle
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.testutil.awaitState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubagentViewModelTest {

    /** 0 ms window: sub-agent frames apply on arrival, no flush timer involved. */
    private val store = SubagentStreamStore(Json { ignoreUnknownKeys = true }, streamCoalesceWindowMillis = 0)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(subagentId: String) = SubagentViewModel(
        store,
        SavedStateHandle(mapOf("sessionId" to "session_1", "subagentId" to subagentId)),
    )

    @Test
    fun `the store entry flows into the ui state`() = runBlocking {
        store.apply("session_1", SessionEvent.SubagentSpawned("agent-12", "explore", "tool_1", "doc-verify", false))
        store.apply("session_1", SessionEvent.SubagentStarted("agent-12"))

        val viewModel = newViewModel("agent-12")
        awaitState(viewModel.uiState) { it.status != null }

        val state = viewModel.uiState.value
        assertEquals("explore", state.name)
        assertEquals("doc-verify", state.description)
        assertEquals(SubagentStatus.Working, state.status)

        store.apply("session_1", SessionEvent.AssistantDelta("hello", "agent-12"))
        awaitState(viewModel.uiState) { it.items.isNotEmpty() }
        assertEquals(
            listOf(ChatItem.AssistantMessage("live_0", "hello", thinking = "")),
            viewModel.uiState.value.items,
        )
    }

    @Test
    fun `an unknown subagent id leaves the state empty`() = runBlocking {
        val viewModel = newViewModel("agent-99")

        // The entry collector runs eagerly under Unconfined; a beat later nothing has
        // arrived — the screen shows its empty state (status stays null).
        delay(100)
        assertNull(viewModel.uiState.value.status)
        assertEquals(emptyList<ChatItem>(), viewModel.uiState.value.items)
    }

    @Test
    fun `the page registers itself while visible`() = runBlocking {
        val viewModel = newViewModel("agent-12")

        viewModel.onScreenStarted()
        assertEquals(1, store.openPageCount("session_1").first())

        viewModel.onScreenStopped()
        assertEquals(0, store.openPageCount("session_1").first())
    }
}
