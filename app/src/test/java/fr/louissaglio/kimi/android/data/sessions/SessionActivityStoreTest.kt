package fr.louissaglio.kimi.android.data.sessions

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionActivityStoreTest {

    private val store = SessionActivityStore()

    @Test
    fun `consumeAll returns the recorded instants and clears them`() {
        store.record("session_1", Instant.parse("2026-08-02T05:00:00.000Z"))
        store.record("session_2", Instant.parse("2026-08-02T06:00:00.000Z"))

        assertEquals(
            mapOf(
                "session_1" to Instant.parse("2026-08-02T05:00:00.000Z"),
                "session_2" to Instant.parse("2026-08-02T06:00:00.000Z"),
            ),
            store.consumeAll(),
        )
        assertTrue(store.consumeAll().isEmpty())
    }

    @Test
    fun `a re-send overwrites the earlier instant`() {
        store.record("session_1", Instant.parse("2026-08-02T05:00:00.000Z"))
        store.record("session_1", Instant.parse("2026-08-02T06:00:00.000Z"))

        assertEquals(
            mapOf("session_1" to Instant.parse("2026-08-02T06:00:00.000Z")),
            store.consumeAll(),
        )
    }
}
