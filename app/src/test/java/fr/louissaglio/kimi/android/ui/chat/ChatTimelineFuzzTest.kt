package fr.louissaglio.kimi.android.ui.chat

import fr.louissaglio.kimi.android.data.chat.ChatMessage
import fr.louissaglio.kimi.android.data.chat.SessionEvent
import fr.louissaglio.kimi.android.data.chat.TranscriptFrame
import fr.louissaglio.kimi.android.data.chat.TranscriptState
import fr.louissaglio.kimi.android.data.chat.TranscriptStep
import fr.louissaglio.kimi.android.data.chat.TranscriptTurn
import fr.louissaglio.kimi.android.testutil.testChatTimeline
import kotlin.random.Random
import org.junit.Assert.fail
import org.junit.Test

/**
 * Crash-hunt turned regression net (2026-08-02): Crashlytics issue
 * 159e25351be7042db517f3af684684db — `Key "tool_…" was already used` on the
 * MessageList LazyColumn, 7 events since the 085 build. Explores random operation
 * programs against ChatTimeline, asserting the LazyColumn-key invariant (`items()`
 * ids unique) after every call, for both history-backed modes.
 *
 * Inputs stay within the server contract: ids unique per page (a real response never
 * repeats a tool/message id) and results-before-calls only across page boundaries —
 * but pages overlap freely across calls, replays deliver events for calls no page
 * currently holds, and transcript turns arrive in any order. The 2026-08-02 fix
 * (prepend drops the rows a page covers) made the zombie-row collision this found
 * unrepresentable.
 */
class ChatTimelineFuzzTest {

    // 32 ids so one page's sampling without replacement never exhausts the pool
    // (a transcript page can draw up to 3 turns × 2 steps × 3 frames = 18).
    private val toolIds = (0..31).map { "tool_$it" }
    private val msgIds = (1..8).map { "msg_$it" }
    private val turnIds = (0..4).map { "t$it" }
    private val toolNames = listOf("Bash", "Read", "Agent", "TodoList")

    @Test
    fun `items never contain duplicate ids under random interleavings`() {
        val failures = mutableMapOf<String, String>()
        for (seed in 0..5000) {
            for (historyBacked in listOf(true, false)) {
                val key = "historyBacked=$historyBacked"
                if (key in failures) continue
                val log = mutableListOf<String>()
                val failure = runCatching { runProgram(seed, historyBacked, log) }.exceptionOrNull()
                if (failure != null) {
                    failures[key] = "seed=$seed $key\n" + log.joinToString("\n") + "\n" + failure.stackTraceToString().take(1500)
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.values.joinToString("\n\n"))
    }

    private fun runProgram(seed: Int, historyBacked: Boolean, log: MutableList<String>) {
        val rnd = Random(seed)
        val timeline = testChatTimeline(historyBacked)
        var frameCounter = 0
        repeat(150) {
            when (rnd.nextInt(16)) {
                0 -> {
                    log += "TurnStarted"
                    timeline.apply(SessionEvent.TurnStarted())
                }
                1 -> {
                    val id = toolIds.random(rnd)
                    log += "ToolCallDelta($id)"
                    timeline.apply(SessionEvent.ToolCallDelta(id, toolNames.random(rnd)))
                }
                2 -> {
                    val id = toolIds.random(rnd)
                    log += "ToolCallStarted($id)"
                    timeline.apply(SessionEvent.ToolCallStarted(id, toolNames.random(rnd), "preview-$id"))
                }
                3 -> {
                    val id = toolIds.random(rnd)
                    log += "ToolCallResult($id)"
                    timeline.apply(SessionEvent.ToolCallResult(id, rnd.nextBoolean(), "out-$id"))
                }
                4 -> {
                    val id = toolIds.random(rnd)
                    log += "ToolCallProgress($id)"
                    timeline.apply(SessionEvent.ToolCallProgress(id, "chunk"))
                }
                5 -> {
                    log += "AssistantDelta"
                    timeline.apply(SessionEvent.AssistantDelta("text "))
                }
                6 -> {
                    log += "ThinkingDelta"
                    timeline.apply(SessionEvent.ThinkingDelta("think "))
                }
                7 -> {
                    val id = if (rnd.nextBoolean()) msgIds.random(rnd) else ""
                    log += "ContextSpliced(user id='$id')"
                    timeline.apply(
                        SessionEvent.ContextSpliced(
                            listOf(ChatMessage(id, ChatMessage.Role.User, listOf(ChatMessage.Part.Text("splice $id")))),
                        ),
                    )
                }
                8 -> {
                    val keepLive = rnd.nextBoolean()
                    val page = randomPage(rnd)
                    log += "replaceHistory(keepLive=$keepLive, ${pageSummary(page)})"
                    timeline.replaceHistory(page, keepLive)
                }
                9 -> {
                    val page = randomPage(rnd)
                    log += "prependHistory(${pageSummary(page)})"
                    timeline.prependHistory(page)
                }
                10 -> {
                    val keepLive = rnd.nextBoolean()
                    val turns = randomTurns(rnd) { frameCounter++ }
                    log += "replaceTranscript(keepLive=$keepLive, turns=${turns.map { it.id }})"
                    timeline.replaceTranscript(turns, keepLive)
                }
                11 -> {
                    val turns = randomTurns(rnd) { frameCounter++ }
                    log += "prependTranscript(turns=${turns.map { it.id }})"
                    timeline.prependTranscript(turns)
                }
                12 -> {
                    log += "compactionStarted"
                    timeline.compactionStarted()
                }
                13 -> {
                    log += "compactionCompleted"
                    timeline.compactionCompleted("summary", 10, 5)
                }
                14 -> {
                    log += "compactionFailed"
                    timeline.compactionFailed()
                }
                15 -> {
                    val id = toolIds.random(rnd)
                    log += "linkSubagentTool($id)"
                    timeline.linkSubagentTool(id, "agent-1")
                }
            }
            val ids = timeline.items().map { it.id }
            val dup = ids.groupBy { it }.filterValues { it.size > 1 }.keys
            if (dup.isNotEmpty()) {
                throw AssertionError("duplicate row ids $dup in $ids")
            }
        }
    }

    private fun pageSummary(page: List<ChatMessage>) =
        page.map { m ->
            m.parts.joinToString("+") { p ->
                when (p) {
                    is ChatMessage.Part.ToolUse -> "use(${p.toolCallId})"
                    is ChatMessage.Part.ToolResult -> "res(${p.toolCallId})"
                    else -> m.role.toString()
                }
            }
        }

    /** A random history page in the server shape: ids are sampled without
     * replacement — a real response never repeats a tool/message id within one
     * page. Pages still overlap freely across calls. */
    private fun randomPage(rnd: Random): List<ChatMessage> {
        val count = rnd.nextInt(0, 7)
        val toolPool = toolIds.shuffled(rnd).toMutableList()
        fun nextToolId() = toolPool.removeFirstOrNull() ?: toolIds.random(rnd)
        // Message ids are unique per page on any real server response.
        val msgPool = msgIds.shuffled(rnd).toMutableList()
        fun nextMsgId() = msgPool.removeFirstOrNull() ?: msgIds.random(rnd)
        return (0 until count).map {
            when (rnd.nextInt(3)) {
                0 -> ChatMessage(
                    nextMsgId(),
                    ChatMessage.Role.User,
                    listOf(ChatMessage.Part.Text("hello")),
                )
                1 -> {
                    val parts = mutableListOf<ChatMessage.Part>()
                    if (rnd.nextBoolean()) parts += ChatMessage.Part.Text("answer")
                    if (rnd.nextBoolean()) parts += ChatMessage.Part.Thinking("hmm")
                    repeat(rnd.nextInt(0, 3)) {
                        parts += ChatMessage.Part.ToolUse(nextToolId(), toolNames.random(rnd), "p", "i")
                    }
                    ChatMessage(nextMsgId(), ChatMessage.Role.Assistant, parts)
                }
                else -> {
                    val parts = (0 until rnd.nextInt(1, 3)).map {
                        ChatMessage.Part.ToolResult(nextToolId(), rnd.nextBoolean(), "out")
                    }
                    ChatMessage(nextMsgId(), ChatMessage.Role.Tool, parts)
                }
            }
        }
    }

    /** Same server shape for transcript pages: turn ids and tool call ids are
     * unique within one page (a real page never repeats them). */
    private fun randomTurns(rnd: Random, frameId: () -> Int): List<TranscriptTurn> {
        val count = rnd.nextInt(1, 4)
        val pool = toolIds.shuffled(rnd).toMutableList()
        fun nextToolId() = pool.removeFirstOrNull() ?: toolIds.random(rnd)
        val turnIdPool = turnIds.shuffled(rnd).toMutableList()
        return (0 until count).map {
            val turnId = turnIdPool.removeFirstOrNull() ?: turnIds.random(rnd)
            TranscriptTurn(
                id = turnId,
                ordinal = 0,
                state = TranscriptState.Completed,
                prompt = null,
                steps = (0 until rnd.nextInt(1, 3)).map {
                    TranscriptStep(
                        id = "$turnId.s$it",
                        state = TranscriptState.Completed,
                        frames = (0 until rnd.nextInt(0, 4)).map {
                            when (rnd.nextInt(3)) {
                                0 -> TranscriptFrame.Thinking("f${frameId()}", "think")
                                1 -> TranscriptFrame.Text("f${frameId()}", TranscriptFrame.Text.Role.Assistant, "text")
                                else -> TranscriptFrame.Tool(
                                    "f${frameId()}",
                                    nextToolId(),
                                    toolNames.random(rnd),
                                    rnd.nextBoolean(),
                                    "p",
                                    "i",
                                    "o",
                                )
                            }
                        },
                    )
                },
            )
        }
    }
}
