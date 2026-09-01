package fr.louissaglio.kimi.android.navigation

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ticket 083: the route serializers own the wire format now — no hand-rolled
 * Uri.encode remains. The values that broke the old encoding (`/ & # % ?` and
 * spaces in a session title or cwd) must survive a serialization round trip.
 * The SavedStateHandle → toRoute decode leg is not covered here: it requires a
 * real android.os.Bundle, and local unit tests run against a stub android.jar
 * (the ViewModel primary constructors take the route objects directly for the
 * same reason).
 */
class RoutesTest {

    private val special = "a/b & c#d%e?f g"

    @Test
    fun `chat route round-trips through json`() {
        val route = Chat(sessionId = "session_1", title = special, cwd = special)
        val decoded = Json.decodeFromString(Chat.serializer(), Json.encodeToString(Chat.serializer(), route))
        assertEquals(route, decoded)
    }

    @Test
    fun `subagent route round-trips through json`() {
        val route = Subagent(sessionId = "session_1", subagentId = "agent/1 & #2")
        val decoded =
            Json.decodeFromString(Subagent.serializer(), Json.encodeToString(Subagent.serializer(), route))
        assertEquals(route, decoded)
    }

    @Test
    fun `edit diff route round-trips through json`() {
        val route = EditDiff(toolCallId = "tool/1 & #2")
        val decoded =
            Json.decodeFromString(EditDiff.serializer(), Json.encodeToString(EditDiff.serializer(), route))
        assertEquals(route, decoded)
    }

    @Test
    fun `file content route round-trips through json`() {
        val route = FileContent(toolCallId = "tool/1 & #2")
        val decoded =
            Json.decodeFromString(FileContent.serializer(), Json.encodeToString(FileContent.serializer(), route))
        assertEquals(route, decoded)
    }
}
