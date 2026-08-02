package fr.louissaglio.kimi.android.data.sessions

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTest {

    private fun session(title: String?, cwd: String?) = Session(
        id = "session_1",
        title = title,
        cwd = cwd,
        updatedAt = Instant.EPOCH,
        isRunning = false,
        isAwaitingInteraction = false,
    )

    @Test
    fun `title wins when present`() {
        assertEquals("Fix the bug", session("Fix the bug", "/home/ubuntu/project").displayName)
    }

    @Test
    fun `titleless session falls back to the cwd basename`() {
        assertEquals("project", session(null, "/home/ubuntu/project").displayName)
    }

    @Test
    fun `trailing-slash cwd still yields its basename`() {
        assertEquals("project", session(null, "/home/ubuntu/project/").displayName)
    }

    @Test
    fun `root cwd has no basename and falls back to the id`() {
        assertEquals("session_1", session(null, "/").displayName)
    }

    @Test
    fun `no title and no cwd falls back to the id`() {
        assertEquals("session_1", session(null, null).displayName)
    }
}
