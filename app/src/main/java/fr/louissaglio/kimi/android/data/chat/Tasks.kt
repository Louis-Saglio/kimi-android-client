package fr.louissaglio.kimi.android.data.chat

import java.time.Instant

// Domain models of the background-task store (docs/server-api.md, Background lifecycle
// and REST /tasks — ticket 092): background sub-agents and background bash. Question
// flows (kind "tool") are excluded — they already surface as question cards in chat.

/**
 * One background task as REST `/tasks` serves it: [kind] and [status] use the REST
 * wire vocabulary (the core one is already mapped server-side). [agentId] links a
 * sub-agent task to the WS sub-agent id for navigation; absent on bash tasks and on
 * servers whose REST shape does not carry it (it arrives via `task.started` instead).
 */
data class BackgroundTask(
    val id: String,
    val kind: Kind,
    val description: String,
    val status: Status,
    val createdAt: Instant,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val agentId: String? = null,
) {
    /** REST wire vocabulary (docs/server-api.md): core `agent`→Subagent,
     * `process`→Bash. Question flows (`tool`) never reach the domain — filtered at
     * the mapping boundary. */
    enum class Kind { Subagent, Bash }

    /** REST wire vocabulary: core `killed`→Cancelled, `timed_out`/`lost`→Failed. */
    enum class Status { Running, Completed, Failed, Cancelled }
}

/**
 * The `info` object of `task.started`/`task.terminated` (docs/server-api.md —
 * [startedAt]/[endedAt] are epoch MILLIS on the wire, unlike REST's ISO strings).
 * [status] keeps the CORE vocabulary (killed/timed_out/lost included) — the collector
 * maps it to the REST wire one. [agentId] is null on bash tasks.
 */
data class TaskInfo(
    val taskId: String,
    val description: String,
    val status: Status,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val stopReason: String? = null,
    val kind: Kind,
    val agentId: String? = null,
    val subagentType: String? = null,
) {
    /** Core vocabulary (docs/server-api.md): `agent` = background sub-agent,
     * `process` = background bash. */
    enum class Kind { Agent, Process }

    /** Core vocabulary — a superset of the REST wire one ([TimedOut] and [Lost]
     * surface as Failed there, [Killed] as Cancelled). */
    enum class Status { Running, Completed, Failed, TimedOut, Killed, Lost }
}
