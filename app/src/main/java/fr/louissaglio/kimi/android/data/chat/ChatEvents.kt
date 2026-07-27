package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.DataError

/** Events of one session, parsed from the WebSocket stream (docs/server-api.md). */
sealed interface SessionEvent {
    data object TurnStarted : SessionEvent
    data class TurnEnded(val reason: String) : SessionEvent
    data class AssistantDelta(val delta: String) : SessionEvent
    data class ThinkingDelta(val delta: String) : SessionEvent
    data class ToolCallDelta(val toolCallId: String, val name: String) : SessionEvent

    /** [preview] is derived from the started payload's display/args (ToolPreview), so the
     * live row shows what the call is doing from the start, not only after a refetch.
     * [input] is the full arguments as pretty-printed JSON, for the expanded row. */
    data class ToolCallStarted(
        val toolCallId: String,
        val name: String,
        val preview: String = "",
        val input: String = "",
    ) : SessionEvent

    /** [output] is the full result text, shown by the expanded row (ticket 023). */
    data class ToolCallResult(val toolCallId: String, val isError: Boolean, val output: String = "") : SessionEvent

    /** One live output chunk of a running tool (tool.progress, volatile — ticket 025).
     * Never creates a timeline row on its own; the definitive tool.result output
     * replaces the accumulated chunks wholesale. */
    data class ToolCallProgress(val toolCallId: String, val chunk: String) : SessionEvent

    /** Messages spliced into the context inline (0.27.0): carries the just-sent user
     * message, which replaced prompt.submitted as the sent-prompt echo. */
    data class ContextSpliced(val messages: List<ChatMessage>) : SessionEvent

    /** A tool call waits on the user's decision. [summary] is the compact argument
     * preview for the approval card (the command for shell calls, else the action). */
    data class ApprovalRequested(val approvalId: String, val toolName: String, val summary: String) : SessionEvent

    /** The request was decided — by this client or another one (event.approval.resolved). */
    data class ApprovalResolved(val approvalId: String) : SessionEvent

    /** The agent asks the user a structured question (event.question.requested); the
     * payload carries the full question inline — there is no detail endpoint to fetch. */
    data class QuestionRequested(val question: PendingQuestion) : SessionEvent

    /** The question was answered or dismissed — by this client or another one
     * (event.question.answered / event.question.dismissed share the consumed shape). */
    data class QuestionResolved(val questionId: String) : SessionEvent

    /** Live context window fill (agent.status.updated, model/usage variant — ticket 041).
     * Only emitted when the frame carries both token counts; phase-variant frames of the
     * same event type emit nothing and leave the stored value untouched. */
    data class ContextUsageUpdated(val contextUsage: ContextUsage) : SessionEvent
}

/** Context window fill of a session: used tokens out of the model's maximum. */
data class ContextUsage(
    val usedTokens: Long,
    val maxTokens: Long,
)

/** Snapshot of `GET /api/v1/sessions/{id}/status` (ticket 041): [busy] is the mid-turn
 * flag; [contextUsage] is null on older servers that send no token counts. */
data class SessionStatus(
    val busy: Boolean,
    val contextUsage: ContextUsage?,
)

/** What the socket reports to the collector: connection lifecycle + session events. */
sealed interface SocketEvent {
    /** First connection of this collection. */
    data object Connected : SocketEvent

    /** Re-connected after a drop; the server replays events newer than the resume cursor. */
    data object Reconnected : SocketEvent

    /** Connection lost (or never established); a retry with backoff is scheduled. */
    data object Disconnected : SocketEvent

    /** The stream cannot run at all (e.g. an invalid server URL). Surfaced as an event
     * instead of thrown so collectors see every failure in one place. */
    data class Failed(val error: DataError) : SocketEvent

    data class Event(val event: SessionEvent) : SocketEvent
}
