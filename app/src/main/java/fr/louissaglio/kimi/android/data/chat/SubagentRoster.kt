package fr.louissaglio.kimi.android.data.chat

/**
 * One sub-agent entry of the session roster — the input of the sub-agent stream
 * store's roster funnel (`SubagentStreamStore.applyRoster`). Server 2.5.0 removed the
 * snapshot roster that used to fill every field; the cold-start feeder is now the
 * `GET .../transcript` `agents` array (ticket 176), which carries identity only
 * ([id], via [TranscriptAgent]) — every other field keeps its default, and the
 * [SubagentRosterPhase.Unknown] phase writes no status. Ticket 182 fills the rest
 * from the `task` frames (`kind:"subagent"`: parent tool call, description, phase,
 * swarm index, model/effort).
 *
 * [parentToolCallId] links the spawning `Agent`/`AgentSwarm` tool row;
 * [outputPreview] is a terminal run's result summary tail. [swarmIndex] is the
 * member's position inside an `AgentSwarm` call. [taskId] is the background-task
 * registration id. [model]/[thinkingEffort] are the run's bound model/effort.
 */
data class SubagentRosterEntry(
    val id: String,
    val subagentType: String = "",
    val description: String = "",
    val phase: SubagentRosterPhase = SubagentRosterPhase.Unknown,
    val parentToolCallId: String = "",
    val runInBackground: Boolean = false,
    val outputPreview: String? = null,
    val swarmIndex: Int? = null,
    val taskId: String? = null,
    val model: String? = null,
    val thinkingEffort: String? = null,
)

/** A roster entry's lifecycle phase; [Unknown] covers an entry that says nothing about
 * it (the transcript `agents` roster) or anything newer the server adds — the entry
 * still seeds (identity + frame accumulation), its status falls back to the
 * transcript-derived one. */
enum class SubagentRosterPhase { Queued, Working, Suspended, Completed, Failed, Cancelled, Unknown }
