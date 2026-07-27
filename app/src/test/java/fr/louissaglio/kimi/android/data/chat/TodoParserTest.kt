package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodoParserTest {

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
            TodoParser.of("TodoList", input),
        )
    }

    @Test
    fun `an empty todos array parses to an empty list, which hides the widget`() {
        assertEquals(emptyList<Todo>(), TodoParser.of("TodoList", """{"todos": []}"""))
    }

    @Test
    fun `a non-todo tool parses to null`() {
        assertNull(TodoParser.of("Bash", """{"todos": [{"title": "x", "status": "done"}]}"""))
    }

    @Test
    fun `malformed or missing input parses to null, never crashes`() {
        assertNull(TodoParser.of("TodoList", ""))
        assertNull(TodoParser.of("TodoList", "   "))
        assertNull(TodoParser.of("TodoList", "{not json"))
        assertNull(TodoParser.of("TodoList", """["todos"]"""))
        assertNull(TodoParser.of("TodoList", """{"todos": "not-an-array"}"""))
        assertNull(TodoParser.of("TodoList", """{"other": []}"""))
    }

    @Test
    fun `an unknown status degrades to pending`() {
        val todos = TodoParser.of("TodoList", """{"todos": [{"title": "x", "status": "blocked"}]}""")

        assertEquals(listOf(Todo("x", Todo.Status.Pending)), todos)
    }

    @Test
    fun `items without a usable title are skipped`() {
        val input = """{"todos": [{"status": "done"}, {"title": "  "}, "junk", {"title": "real", "status": "done"}]}"""

        assertEquals(listOf(Todo("real", Todo.Status.Done)), TodoParser.of("TodoList", input))
    }
}
