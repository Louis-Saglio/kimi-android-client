package fr.louissaglio.kimi.android.testutil

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout

/**
 * Polls [condition] every 10 ms of real time until it holds; fails the test after
 * [timeoutMs]. The MockWebServer-based tests do real socket I/O, so waits run on the
 * real clock, not on runTest's virtual one.
 */
suspend fun awaitCondition(timeoutMs: Long = 5_000, condition: () -> Boolean) =
    withTimeout(timeoutMs) { while (!condition()) delay(10) }

/** [awaitCondition] on a StateFlow's current value: `awaitState(viewModel.uiState) { it.x }`.
 * The timeout is overridable for the few resync paths whose multi-round-trip work can
 * exceed the default on a loaded machine. */
suspend fun <T> awaitState(flow: StateFlow<T>, timeoutMs: Long = 5_000, predicate: (T) -> Boolean) =
    awaitCondition(timeoutMs) { predicate(flow.value) }
