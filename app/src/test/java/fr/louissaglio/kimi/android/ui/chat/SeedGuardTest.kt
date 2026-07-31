package fr.louissaglio.kimi.android.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedGuardTest {

    @Test
    fun `a capture with no event since is warm and the response applies`() {
        val guard = SeedGuard()

        val snapshot = guard.capture()

        assertFalse(guard.isStale(snapshot))
    }

    @Test
    fun `an event landing while the seed is in flight stales the response`() {
        val guard = SeedGuard()
        val snapshot = guard.capture()

        guard.bump()

        assertTrue(guard.isStale(snapshot))
    }

    @Test
    fun `a bump on either edge of the tracked state stales the response`() {
        // Ticket 028: the guard must cover BOTH edges — a one-directional guard lets
        // a stale response through in the unguarded direction (a late "busy: true"
        // re-lighting the indicator after a quick abort's turn.ended).
        val guard = SeedGuard()
        val capturedBeforeFirstEdge = guard.capture()

        guard.bump() // e.g. turn.started
        val capturedBeforeSecondEdge = guard.capture()
        guard.bump() // e.g. turn.ended

        assertTrue(guard.isStale(capturedBeforeFirstEdge))
        assertTrue(guard.isStale(capturedBeforeSecondEdge))
    }

    @Test
    fun `further bumps never un-stale an earlier capture`() {
        val guard = SeedGuard()
        val snapshot = guard.capture()

        guard.bump()
        guard.bump()

        assertTrue(guard.isStale(snapshot))
    }

    @Test
    fun `a capture taken after an event is warm again`() {
        // The next screen-start seed must not be poisoned by the generation a
        // previous event left behind — the guard tracks races, not history.
        val guard = SeedGuard()
        guard.bump()

        val snapshot = guard.capture()

        assertFalse(guard.isStale(snapshot))
    }
}
