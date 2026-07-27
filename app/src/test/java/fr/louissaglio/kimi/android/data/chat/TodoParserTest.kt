package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodoParserTest {

    private val parser = TodoParser(Json { ignoreUnknownKeys = true })

    @Test
    fun `a full list parses all three statuses`() {
        val input = """
            {
                "todos": [
                    {"title": "write the parser", "status": "done"},
                    {"title": "wire the widget", "status": "in_progress"},
                    {"title": "ship it", "status": "pending"}
                ]
            }
        """.trimIndent()

        assertEquals(
            listOf(
                Todo("write the parser", Todo.Status.Done),
                Todo("wire the widget", Todo.Status.InProgress),
                Todo("ship it", Todo.Status.Pending),
            ),
            parser.of("TodoList", input),
        )
    }

    @Test
    fun `an empty todos array parses to an empty list, which hides the widget`() {
        assertEquals(emptyList<Todo>(), parser.of("TodoList", """{"todos": []}"""))
    }

    @Test
    fun `a non-todo tool parses to null`() {
        assertNull(parser.of("Bash", """{"todos": [{"title": "x", "status": "done"}]}"""))
    }

    @Test
    fun `malformed or missing input parses to null, never crashes`() {
        assertNull(parser.of("TodoList", ""))
        assertNull(parser.of("TodoList", "   "))
        assertNull(parser.of("TodoList", "{not json"))
        assertNull(parser.of("TodoList", """["todos"]"""))
        assertNull(parser.of("TodoList", """{"todos": "not-an-array"}"""))
        assertNull(parser.of("TodoList", """{"other": []}"""))
    }

    @Test
    fun `an unknown status degrades to pending`() {
        val todos = parser.of("TodoList", """{"todos": [{"title": "x", "status": "blocked"}]}""")

        assertEquals(listOf(Todo("x", Todo.Status.Pending)), todos)
    }

    @Test
    fun `items without a usable title are skipped`() {
        val input = """{"todos": [{"status": "done"}, {"title": "  "}, "junk", {"title": "real", "status": "done"}]}"""

        assertEquals(listOf(Todo("real", Todo.Status.Done)), parser.of("TodoList", input))
    }
}
