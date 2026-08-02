package fr.louissaglio.kimi.android.data.sessions

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-lifetime record of sessions the user messaged from the chat screen
 * (ticket 086): the chat data layer records a successfully sent prompt/steer, the
 * session list consumes the pending entries on return to bump those rows to the
 * top before its silent refresh lands. Recording on success, not attempt — a
 * failed send must not bump a session the server doesn't consider active. Nothing
 * is persisted: after process death the initial load returns server truth anyway.
 */
@Singleton
class SessionActivityStore @Inject constructor() {

    private val pending = LinkedHashMap<String, Instant>()

    /** Records user-message activity in [sessionId] at [at]; a re-send overwrites the earlier instant. */
    fun record(sessionId: String, at: Instant = Instant.now()) = synchronized(pending) {
        pending[sessionId] = at
    }

    /** Returns every pending entry and clears them — consumed once, by the session list. */
    fun consumeAll(): Map<String, Instant> = synchronized(pending) {
        pending.toMap().also { pending.clear() }
    }
}
