package fr.louissaglio.kimi.android.ui.chat

/** The aggregate of one `AgentSwarm` call's member phases (ticket 135). */
internal enum class SwarmAggregateStatus { Running, Failed, Cancelled, Completed }

/**
 * The swarm aggregate (ticket 135): the pure derivation behind the chat timeline's
 * swarm card (ToolRows' SwarmCard) and the roster screen's summary strip
 * (SwarmScreen). Input is the member list plus the call's expected total (the
 * `ToolPayload.AgentSwarm.expectedTotal` from the call's args — known before all
 * members have spawned) and the call's own running flag; output is the per-phase
 * counts, the done/total pair and the aggregate status. Mirroring the web's
 * 0.43.0 swarm-card: a deliberately stopped member (Cancelled) never counts as
 * failed.
 */
internal data class SwarmProgress(
    val queued: Int,
    val working: Int,
    val suspended: Int,
    val completed: Int,
    val failed: Int,
    val cancelled: Int,
    val expectedTotal: Int?,
    val toolRunning: Boolean,
    /** The shared model line ("model · effort") when every member carries the same
     * non-null pair, null when mixed or unknown (ticket 135, web parity). */
    val modelLine: String?,
) {
    val members: Int get() = queued + working + suspended + completed + failed + cancelled

    /** The denominator of the "{done}/{total}" count: the expected total covers
     * members not yet spawned. */
    val total: Int get() = maxOf(members, expectedTotal ?: 0)

    /** Settled members — a cancelled one is done too, just not failed. */
    val done: Int get() = completed + failed + cancelled

    val inProgress: Int get() = queued + working + suspended

    /** The matrix (ticket 135): Running while any member is active or the call
     * itself still runs; then Failed when any member failed; then Cancelled when
     * at least one member was cancelled and none completed; else Completed —
     * including the empty-members settled case. */
    val status: SwarmAggregateStatus
        get() = when {
            inProgress > 0 || toolRunning -> SwarmAggregateStatus.Running
            failed > 0 -> SwarmAggregateStatus.Failed
            cancelled > 0 && completed == 0 -> SwarmAggregateStatus.Cancelled
            else -> SwarmAggregateStatus.Completed
        }

    /** The card's subtitle (web-parity strings): "{N} in progress" while running,
     * "{c} completed · {f} failed" plus " · {k} cancelled" (k > 0) once settled. */
    val subtitle: String
        get() = if (status == SwarmAggregateStatus.Running) {
            "$inProgress in progress"
        } else {
            buildString {
                append("$completed completed · $failed failed")
                if (cancelled > 0) append(" · $cancelled cancelled")
            }
        }

    /** The present phases' counts in phase order ("3 working · 5 completed ·
     * 1 failed") — the roster screen's strip and the progress bar's
     * contentDescription; empty when nothing is known. */
    val countsLine: String
        get() = buildList {
            if (queued > 0) add("$queued queued")
            if (working > 0) add("$working working")
            if (suspended > 0) add("$suspended suspended")
            if (completed > 0) add("$completed completed")
            if (failed > 0) add("$failed failed")
            if (cancelled > 0) add("$cancelled cancelled")
        }.joinToString(" · ")

    companion object {
        fun of(
            members: List<SubagentStreamStore.SubagentState>,
            expectedTotal: Int?,
            toolRunning: Boolean,
        ): SwarmProgress {
            var queued = 0
            var working = 0
            var suspended = 0
            var completed = 0
            var failed = 0
            var cancelled = 0
            for (member in members) {
                when (member.status) {
                    SubagentStatus.Queued -> queued++
                    SubagentStatus.Working -> working++
                    SubagentStatus.Suspended -> suspended++
                    SubagentStatus.Completed -> completed++
                    SubagentStatus.Failed -> failed++
                    SubagentStatus.Cancelled -> cancelled++
                }
            }
            return SwarmProgress(
                queued, working, suspended, completed, failed, cancelled,
                expectedTotal, toolRunning, sharedModelLine(members),
            )
        }

        /** "model · effort" when every member carries the same non-blank pair;
         * null when the list is empty, mixed, or any member's pair is unknown. */
        private fun sharedModelLine(members: List<SubagentStreamStore.SubagentState>): String? {
            val first = members.firstOrNull() ?: return null
            val model = first.model?.takeIf { it.isNotBlank() } ?: return null
            val effort = first.thinkingEffort?.takeIf { it.isNotBlank() } ?: return null
            val uniform = members.all { it.model == model && it.thinkingEffort == effort }
            return if (uniform) "$model · $effort" else null
        }
    }
}
