package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ticket 168's stale-session send recovery: one reload, one fresh snapshot resync,
 * the draft preserved and the prompt never resubmitted. */
class ChatSendRecoveryTest : ChatViewModelTestBase() {

    private val staleBody =
        """{"code":40932,"msg":"Session changed on disk","data":null,"request_id":"r"}"""

    /** Starts the screen, drains the startup burst and attaches an effect collector. */
    private suspend fun CoroutineScope.startForRecoveryTest(
        effects: CopyOnWriteArrayList<ChatViewModel.Effect>,
    ): Job {
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.items != null && it.modelsAvailable }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
        return launch { viewModel.effects.collect { effects += it } }
    }

    @Test
    fun `a 40932 send reloads and resyncs once without resubmitting the prompt`() = runBlocking {
        promptFailureBody = staleBody
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = startForRecoveryTest(effects)
        try {
            viewModel.onInputChange("hi")
            viewModel.onSend()

            awaitCondition { effects.isNotEmpty() }
            assertTrue(effects.single() is ChatViewModel.Effect.SessionReloaded)
            assertEquals("hi", viewModel.uiState.value.inputText)
            assertFalse(viewModel.uiState.value.isSending)

            val requests = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()
            // Exactly one prompt POST, one reload, one fresh snapshot — never a second
            // prompt POST (a retry is always the user's explicit action).
            assertEquals(1, requests.count { it.target == "/api/v1/sessions/session_1/prompts" })
            assertEquals(1, requests.count { it.target == "/api/v1/sessions/session_1:reload" })
            assertEquals(1, requests.count { it.target == "/api/v1/sessions/session_1/snapshot" })
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `a failed reload keeps the draft and reports the failure`() = runBlocking {
        promptFailureBody = staleBody
        reloadsSucceed = false
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = startForRecoveryTest(effects)
        try {
            viewModel.onInputChange("hi")
            viewModel.onSend()

            awaitCondition { effects.isNotEmpty() }
            val failure = effects.single() as ChatViewModel.Effect.SessionRecoveryFailed
            assertTrue(failure.message, "could not be reloaded" in failure.message)
            assertEquals("hi", viewModel.uiState.value.inputText)

            val requests = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()
            assertEquals(1, requests.count { it.target == "/api/v1/sessions/session_1/prompts" })
            assertEquals(1, requests.count { it.target == "/api/v1/sessions/session_1:reload" })
            assertEquals(0, requests.count { it.target == "/api/v1/sessions/session_1/snapshot" })
        } finally {
            effectJob.cancel()
        }
    }
}
