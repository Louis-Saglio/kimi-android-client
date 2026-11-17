package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.chat.TaskControl
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskListFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskResponseFrame
import fr.louissaglio.kimi.android.testutil.TranscriptFrames.taskState
import fr.louissaglio.kimi.android.testutil.awaitCondition
import fr.louissaglio.kimi.android.testutil.awaitState
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 179: the chat's jobs widget runs on the main agent's `task.list`/`task`
 * frames (docs/server-api.md, Sub-agents → Task frames and controls) — no REST
 * `/tasks` request ever — and its Stop / "To background" are the flat `task.stop` /
 * `task.background` controls, replied to with `{type:"response",…}` on the same
 * socket. End to end: real socket, real repository, real ViewModel.
 */
class ChatJobsTest : ChatViewModelTestBase() {

    private val json = Json

    /** The task controls the app sent, as JSON objects. */
    private fun sentControls(type: String) = clientFrames
        .map { json.parseToJsonElement(it).jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == type }

    private fun noTasksRequest() = requestedTargets.none { "/tasks" in it }

    @Test
    fun `background bash and sub-agent spawns show from task frames with no tasks request`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }

        socket.send(taskListFrame())
        socket.send(
            taskFrame(
                taskState(
                    "bash-s9yny87s",
                    description = "Bash: sleep 30 && echo bg-ok",
                    extra = ""","parent_tool_call_id":"call_1","command":"sleep 30 && echo bg-ok"""",
                ),
            ),
        )
        socket.send(
            taskFrame(
                taskState(
                    "agent-v93x0tjv",
                    kind = "subagent",
                    description = "Sleep then reply bg-ok",
                    extra = ""","phase":"working","child_agent_id":"agent-1","model":"k3","thinking":"max"""",
                ),
            ),
        )

        awaitState(viewModel.uiState) { it.jobs.size == 2 }
        val jobs = viewModel.uiState.value.jobs
        assertEquals(listOf(Job.Kind.Bash, Job.Kind.Subagent), jobs.map { it.kind })
        assertTrue(jobs.all { it.isRunning })
        assertEquals("agent-1", jobs.last().agentId)
        assertTrue(noTasksRequest())
    }

    @Test
    fun `task list seeds running jobs on open and terminal jobs from earlier frames linger`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }

        // The subscribe-time list seeds what is running.
        socket.send(taskListFrame(taskState("bash-1"), taskState("bash-2", description = "Bash: make")))
        awaitState(viewModel.uiState) { it.jobs.count { job -> job.isRunning } == 2 }

        // bash-1's final frame; a later list (a reconnect) names only bash-2.
        socket.send(
            taskFrame(taskState("bash-1", status = "ended", outcome = "completed", endedAt = "2026-11-04T19:30:30.000Z")),
        )
        socket.send(taskListFrame(taskState("bash-2", description = "Bash: make")))
        socket.send(taskFrame(taskState("bash-3", description = "Bash: sentinel")))

        awaitState(viewModel.uiState) { it.jobs.any { job -> job.taskId == "bash-3" } }
        val jobs = viewModel.uiState.value.jobs
        assertEquals(listOf("bash-1", "bash-2", "bash-3"), jobs.map { it.taskId })
        assertEquals(Job.Status.Completed, jobs.first().status)
        assertTrue(noTasksRequest())
    }

    @Test
    fun `cancel sends task stop and the job flips when the ended state arrives`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        socket.send(taskListFrame(taskState("bash-1")))
        awaitState(viewModel.uiState) { it.jobs.isNotEmpty() }

        viewModel.onCancelJob("bash-1")

        awaitCondition { sentControls("task.stop").isNotEmpty() }
        val stop = sentControls("task.stop").single()
        assertEquals("session_1", stop["session_id"]?.jsonPrimitive?.content)
        assertEquals("main", stop["agent_id"]?.jsonPrimitive?.content)
        assertEquals("bash-1", stop["task_id"]?.jsonPrimitive?.content)
        // No optimistic flip: the job runs until the server says otherwise.
        assertEquals(Job.Status.Running, viewModel.uiState.value.jobs.single().status)

        socket.send(
            taskResponseFrame(
                stop["request_id"]!!.jsonPrimitive.content,
                task = taskState("bash-1", status = "ended", outcome = "killed", endedAt = "2026-11-04T19:30:30.000Z"),
            ),
        )

        awaitState(viewModel.uiState) { it.jobs.single().status == Job.Status.Cancelled }
        assertTrue(noTasksRequest())
    }

    @Test
    fun `an error reply to task stop surfaces and leaves the job unchanged`() = runBlocking {
        val effects = CopyOnWriteArrayList<ChatViewModel.Effect>()
        val effectJob = launch { viewModel.effects.collect { effects += it } }
        try {
            viewModel.onScreenStarted()
            val socket = awaitServerSocket()
            awaitState(viewModel.uiState) { it.items != null }
            socket.send(taskListFrame(taskState("bash-1")))
            awaitState(viewModel.uiState) { it.jobs.isNotEmpty() }

            viewModel.onCancelJob("bash-1")
            awaitCondition { sentControls("task.stop").isNotEmpty() }
            // The error reply carries the (unchanged) state: it must not be applied.
            socket.send(
                taskResponseFrame(
                    sentControls("task.stop").single()["request_id"]!!.jsonPrimitive.content,
                    code = 40933,
                    msg = "task cannot be stopped",
                    task = taskState("bash-1", status = "ended", outcome = "completed"),
                ),
            )

            awaitCondition { effects.isNotEmpty() }
            assertEquals(listOf(ChatViewModel.Effect.JobCancelFailed("task cannot be stopped")), effects.toList())
            assertEquals(Job.Status.Running, viewModel.uiState.value.jobs.single().status)
        } finally {
            effectJob.cancel()
        }
    }

    @Test
    fun `to background sends task background and the replied detached state lists the row`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }
        // A foreground spawn: a task frame exists, but its waiting tool call is its UI.
        socket.send(
            taskFrame(
                taskState("agent-fg", kind = "subagent", detached = false, description = "Explore", extra = ""","child_agent_id":"agent-0""""),
            ),
        )
        socket.send(taskFrame(taskState("bash-sentinel")))
        awaitState(viewModel.uiState) { it.jobs.isNotEmpty() }
        assertEquals(listOf("bash-sentinel"), viewModel.uiState.value.jobs.map { it.taskId })

        // The sub-agent page's "To background" (SubagentViewModel) sends this control.
        val reply = async { chatRepository.sendTaskControl("session_1", TaskControl.Background, "agent-fg") }
        awaitCondition { sentControls("task.background").isNotEmpty() }
        val control = sentControls("task.background").single()
        assertEquals("agent-fg", control["task_id"]?.jsonPrimitive?.content)
        assertEquals("main", control["agent_id"]?.jsonPrimitive?.content)
        val detached = taskState("agent-fg", kind = "subagent", detached = true, description = "Explore", extra = ""","child_agent_id":"agent-0"""")
        socket.send(taskResponseFrame(control["request_id"]!!.jsonPrimitive.content, task = detached))

        val state = reply.await().getOrThrow()
        assertEquals(true, state?.detached)
        awaitState(viewModel.uiState) { it.jobs.any { job -> job.taskId == "agent-fg" } }
        // The server's own frame for the same change keeps ONE row. A trailing sentinel
        // proves that frame was applied before the assertion.
        socket.send(taskFrame(detached))
        socket.send(taskFrame(taskState("bash-sentinel-2")))
        awaitState(viewModel.uiState) { it.jobs.any { job -> job.taskId == "bash-sentinel-2" } }
        assertEquals(listOf("bash-sentinel", "agent-fg", "bash-sentinel-2"), viewModel.uiState.value.jobs.map { it.taskId })
        assertEquals("agent-0", viewModel.uiState.value.jobs.single { it.taskId == "agent-fg" }.agentId)
    }

    @Test
    fun `a 40933 reply to task background fails with the code and lists nothing`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }

        val reply = async { chatRepository.sendTaskControl("session_1", TaskControl.Background, "agent-swarm") }
        awaitCondition { sentControls("task.background").isNotEmpty() }
        socket.send(
            taskResponseFrame(
                sentControls("task.background").single()["request_id"]!!.jsonPrimitive.content,
                code = 40933,
                msg = "task cannot be moved to the background",
                task = taskState("agent-swarm", kind = "subagent", detached = false, extra = ""","swarm_index":0"""),
            ),
        )

        val error = reply.await().exceptionOrNull()
        assertEquals(40933, (error as DataError.Api).code)
        assertTrue(viewModel.uiState.value.jobs.isEmpty())
    }

    @Test
    fun `a background question task is never rendered as a job`() = runBlocking {
        viewModel.onScreenStarted()
        val socket = awaitServerSocket()
        awaitState(viewModel.uiState) { it.items != null }

        socket.send(taskListFrame(taskState("question-1", kind = "other", description = "Pick an option")))
        socket.send(taskFrame(taskState("question-2", kind = "other", description = "Pick another")))
        socket.send(taskFrame(taskState("bash-sentinel")))

        // The sentinel follows the question frames: once it shows, they were applied.
        awaitState(viewModel.uiState) { it.jobs.isNotEmpty() }
        assertEquals(listOf("bash-sentinel"), viewModel.uiState.value.jobs.map { it.taskId })
    }
}
