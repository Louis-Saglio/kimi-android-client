package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.Todo
import fr.louissaglio.kimi.android.testutil.TranscriptFrames
import fr.louissaglio.kimi.android.testutil.awaitState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 178: the chat's todo widget is the main agent's flat `todo` frame
 * (docs/server-api.md, Todos), applied wholesale — never derived from `TodoList` tool
 * calls, whose rows stay hidden (ticket 042).
 */
class ChatTodosTest : ChatViewModelTestBase() {

    private fun ChatViewModel.UiState.toolNames(): List<String> =
        items.orEmpty().filterIsInstance<ChatItem.ToolGroup>().flatMap { group -> group.tools.map { it.name } }

    @Test
    fun `live todo frames replace the widget list wholesale, never append`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        assertNull(viewModel.uiState.value.todos)

        // The verbatim live sequence: each TodoList call is followed by the frame
        // carrying the full list (empty → in_progress → done).
        socket.send(turnStartedFrame())
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.toolOp("todo_1", "TodoList", "running", turnId = "t1")))
        socket.send(TranscriptFrames.todoFrame())
        awaitState(viewModel.uiState) { it.todos == emptyList<Todo>() }

        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.toolOp("todo_1", "TodoList", "done", ""","output":"ok"""", turnId = "t1")))
        socket.send(
            TranscriptFrames.todoFrame("List files" to "in_progress", "Read a file" to "pending", "Reply done" to "pending"),
        )
        awaitState(viewModel.uiState) {
            it.todos == listOf(
                Todo("List files", Todo.Status.InProgress),
                Todo("Read a file", Todo.Status.Pending),
                Todo("Reply done", Todo.Status.Pending),
            )
        }

        socket.send(TranscriptFrames.todoFrame("List files" to "done", "Read a file" to "done", "Reply done" to "done"))
        awaitState(viewModel.uiState) {
            it.todos == listOf(
                Todo("List files", Todo.Status.Done),
                Todo("Read a file", Todo.Status.Done),
                Todo("Reply done", Todo.Status.Done),
            )
        }
        // The TodoList calls render no row (ticket 042).
        assertTrue(viewModel.uiState.value.toolNames().none { it == "TodoList" })
    }

    @Test
    fun `an undo restores the previous list from the frame the server re-sends`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }

        socket.send(TranscriptFrames.todoFrame("Plan" to "done", "Build" to "in_progress"))
        awaitState(viewModel.uiState) { it.todos?.size == 2 }

        // The undo rewinds the TodoList call; the server answers with the restored list.
        socket.send(TranscriptFrames.opsFrame(TranscriptFrames.undoOp("m_undo")))
        socket.send(TranscriptFrames.todoFrame("Plan" to "in_progress"))
        awaitState(viewModel.uiState) { it.todos == listOf(Todo("Plan", Todo.Status.InProgress)) }
    }

    @Test
    fun `a cold open shows the list of the frame preceding the ack, and no TodoList row`() = runBlocking {
        // The history page carries a TodoList call whose input names a STALE list: it
        // renders no row and derives nothing.
        val staleTodoItems = """{"id":"msg_1","role":"user","content":[{"type":"text","text":"plan it"}]},""" +
            """{"id":"msg_2","role":"assistant","content":[{"type":"text","text":"Planning."},""" +
            """{"type":"tool_use","tool_call_id":"todo_1","tool_name":"TodoList",""" +
            """"input":{"todos":[{"title":"stale","status":"pending"}]}}]},""" +
            """{"id":"msg_3","role":"tool","content":[{"type":"tool_result","tool_call_id":"todo_1",""" +
            """"output":"Todo list updated.","is_error":false}]}"""
        messagesBody = """{"code":0,"msg":"success","data":{"items":[$staleTodoItems],"has_more":false},"request_id":"r"}"""
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()

        // The verbatim subscribe order: state frames, the reset, then the ack.
        socket.send(TranscriptFrames.agentFrame())
        socket.send(TranscriptFrames.todoFrame("Write" to "done", "Verify" to "in_progress"))
        socket.send(TranscriptFrames.resetFrame(seq = 1))
        socket.send(TranscriptFrames.ackFrame())

        val expected = listOf(Todo("Write", Todo.Status.Done), Todo("Verify", Todo.Status.InProgress))
        awaitState(viewModel.uiState) { it.todos == expected && it.items?.any { item -> item.id == "msg_2" } == true }
        assertTrue(viewModel.uiState.value.toolNames().none { it == "TodoList" })

        // A later canonical history replace (the turn-end refetch) leaves the list alone.
        // The refetched page adds msg_4, so its arrival in the state proves the replace
        // was applied before the assertions.
        messagesBody = """{"code":0,"msg":"success","data":{"items":[$staleTodoItems,""" +
            """{"id":"msg_4","role":"assistant","content":[{"type":"text","text":"Planned."}]}""" +
            """],"has_more":false},"request_id":"r"}"""
        while (server.takeRequest(50, TimeUnit.MILLISECONDS) != null) Unit
        socket.send(turnStartedFrame())
        socket.send(turnEndedFrame(reason = "completed"))
        awaitRequest("/messages")
        awaitState(viewModel.uiState) { it.items?.any { item -> item.id == "msg_4" } == true }
        assertEquals(expected, viewModel.uiState.value.todos)
        assertTrue(viewModel.uiState.value.toolNames().none { it == "TodoList" })
    }

    @Test
    fun `a child agent's todo frame never touches the main widget`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        socket.send(TranscriptFrames.todoFrame("Main step" to "pending"))
        awaitState(viewModel.uiState) { it.todos == listOf(Todo("Main step", Todo.Status.Pending)) }

        socket.send(TranscriptFrames.todoFrame("Child step" to "in_progress", agentId = "agent-0"))
        socket.send(TranscriptFrames.todoFrame(agentId = "agent-0"))
        // A later main frame proves the child's were processed (and dropped) first.
        socket.send(TranscriptFrames.todoFrame("Main step" to "done"))
        awaitState(viewModel.uiState) { it.todos == listOf(Todo("Main step", Todo.Status.Done)) }
    }

    @Test
    fun `an empty todo frame clears the widget`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        socket.send(TranscriptFrames.todoFrame("Only step" to "in_progress"))
        awaitState(viewModel.uiState) { it.todos?.size == 1 }

        socket.send(TranscriptFrames.todoFrame())
        awaitState(viewModel.uiState) { it.todos == emptyList<Todo>() }
    }
}
