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

/** Ticket 169's occupancy flow: the first 40931 is an explanation, the second offers
 * the destructive takeover, which never resubmits the prompt. */
class SessionTakeoverTest : ChatViewModelTestBase() {

    private val occupiedBody =
        """{"code":40931,"msg":"This session is in use by another Kimi Code CLI instance. Stop the session in that instance or exit it, then try again.",""" +
            """"data":null,"request_id":"r","details":{"sessionId":"session_1","holder":{"version":1,""" +
            """"token":"396620:secret","pid":396620,"hostname":"vps","startedAt":1792784114990,""" +
            """"clientIdentity":"kimi_code_cli","clientDisplayName":"Kimi Code CLI"}}}"""

    /** Starts the screen, drains the startup burst and attaches an effect collector. */
    private suspend fun CoroutineScope.startForTakeoverTest(
        effects: CopyOnWriteArrayList<ChatViewModel.Effect>,
    ): Job {
        viewModel.onScreenStarted()
        awaitState(viewModel.uiState) { it.items != null && it.modelsAvailable }
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
        return launch { viewModel.effects.collect { effects += it } }
    }

    @Test
    fun `a 40931 names the holder and the first answer never reloads or takes over`() = runBlocking {
        promptFailureBody = occupiedBody
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = startForTakeoverTest(effects)
        try {
            viewModel.onInputChange("hi")
            viewModel.onSend()

            awaitCondition { effects.isNotEmpty() }
            val occupied = effects.single() as ChatViewModel.Effect.SessionOccupied
            assertFalse(occupied.canTakeOver)
            assertTrue(occupied.message, "Kimi Code CLI" in occupied.message)
            // The draft stays for the explicit Retry.
            assertEquals("hi", viewModel.uiState.value.inputText)
            assertFalse(viewModel.uiState.value.isSending)

            // One prompt POST and nothing else: no :reload, no :takeover.
            val paths = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.map { it.target }.toList()
            assertEquals(1, paths.count { it == "/api/v1/sessions/session_1/prompts" })
            assertTrue(paths.none { it.endsWith(":reload") || it.endsWith(":takeover") })
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `the second consecutive 40931 offers takeover and falls back to the server message`() = runBlocking {
        promptFailureBody = """{"code":40931,"msg":"This session is in use by another Kimi Code process.","data":null,"request_id":"r"}"""
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = startForTakeoverTest(effects)
        try {
            viewModel.onInputChange("hi")
            viewModel.onSend()
            awaitCondition { effects.size == 1 }
            assertFalse((effects[0] as ChatViewModel.Effect.SessionOccupied).canTakeOver)

            viewModel.onSend()
            awaitCondition { effects.size == 2 }
            val second = effects[1] as ChatViewModel.Effect.SessionOccupied
            assertTrue(second.canTakeOver)
            // No holder metadata: the server's own sentence is the message.
            assertEquals("This session is in use by another Kimi Code process.", second.message)
            assertEquals("hi", viewModel.uiState.value.inputText)

            viewModel.onTakeOverSession()
            awaitCondition { effects.size == 3 }
            assertTrue(effects[2] is ChatViewModel.Effect.TakeoverSucceeded)
            // Exactly one takeover POST, no further prompt POST, draft intact.
            val requests = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()
            assertEquals(1, requests.count { it.target == "/api/v1/sessions/session_1:takeover" })
            assertEquals("{}", requests.first { it.target == "/api/v1/sessions/session_1:takeover" }.body!!.utf8())
            assertEquals(2, requests.count { it.target == "/api/v1/sessions/session_1/prompts" })
            assertEquals("hi", viewModel.uiState.value.inputText)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `a failed takeover reports the failure and keeps the draft`() = runBlocking {
        promptFailureBody = occupiedBody
        takeoversSucceed = false
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = startForTakeoverTest(effects)
        try {
            viewModel.onInputChange("hi")
            viewModel.onSend()
            awaitCondition { effects.size == 1 }
            viewModel.onSend()
            awaitCondition { effects.size == 2 }

            viewModel.onTakeOverSession()

            awaitCondition { effects.size == 3 }
            val failure = effects[2] as ChatViewModel.Effect.SessionRecoveryFailed
            assertTrue(failure.message, "could not take over" in failure.message.lowercase())
            assertEquals("hi", viewModel.uiState.value.inputText)
        } finally {
            effectJob.cancel()
        }
    }
}
