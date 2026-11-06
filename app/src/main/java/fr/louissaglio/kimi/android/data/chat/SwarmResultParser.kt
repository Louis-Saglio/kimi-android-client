package fr.louissaglio.kimi.android.data.chat

/**
 * The `AgentSwarm` call's result text (ticket 136): an `<agent_swarm_result>` XML
 * document rendered by the upstream tool (`renderSwarmResults`,
 * packages/agent-core-v2 at tag 0.43.1 — never captured live, the shapes here are
 * pinned from the source and its tests). It is the ONLY persisted per-member record
 * of a settled swarm: the members' ended `task` frames are live-only and
 * `task.list` never re-lists an ended task, so a swarm reopened from history heals
 * its member states from this text.
 *
 * ```
 * <agent_swarm_result>
 * <summary>completed: N, failed: M, aborted: K</summary>   <!-- a count shows only when > 0 -->
 * <resume_hint>...</resume_hint>                           <!-- optional -->
 * <subagent mode="resume" agent_id="agent-3" item="src/a.ts" state="not_started"
 *           outcome="completed|failed|aborted" stop_reason="...">body</subagent>
 * </agent_swarm_result>
 * ```
 *
 * The attribute order above is the renderer's own (`mode`, `agent_id`, `item`,
 * `state`, `outcome`, `stop_reason` — every one optional but `outcome`); the parse
 * is order-agnostic. Attribute values are XML-escaped by the renderer
 * ([unescape]); member bodies are NOT — a member's raw output can itself contain
 * `<subagent>` tags, so body text never produces members: only a depth-0 open tag
 * does, and a body swallows everything up to its own balanced close.
 *
 * Tolerant, like the app's other output parsers: anything that does not fit the
 * document grammar yields no member (a non-XML error output — an Error-row call
 * carries plain prose — parses as none), a `<subagent>` element without a
 * readable `outcome` is skipped, and the summary's counts stay 0 when its line is
 * missing or drifted.
 */
object SwarmResultParser {

    /** The parsed document: the summary's per-outcome counts and the members in
     * document order. */
    data class SwarmResult(
        val completed: Int,
        val failed: Int,
        val aborted: Int,
        val members: List<Member>,
    )

    /** One `<subagent>` element. [agentId] is null for a member that never spawned
     * (an interrupted turn — the renderer omits the attribute then). [item] is the
     * member's swarm item excerpt, null when the renderer had none. [notStarted]
     * is the `state="not_started"` flag: the member was planned but never launched
     * (its [agentId] is always null too). [stopReason] is the completed run's own
     * stop condition, present only when the renderer carried one. */
    data class Member(
        val agentId: String?,
        val item: String?,
        val outcome: Outcome,
        val stopReason: String?,
        val notStarted: Boolean,
    )

    enum class Outcome { Completed, Failed, Aborted }

    /** Parses [text] as an `<agent_swarm_result>` document; null when the text
     * holds no swarm result at all (blank, the plain-prose of an Error row). */
    fun of(text: String): SwarmResult? {
        if (!text.contains(ROOT_OPEN)) return null
        val members = mutableListOf<Member>()
        var rest = text
        while (true) {
            val tagStart = rest.indexOf(MEMBER_OPEN)
            if (tagStart < 0) break
            val tagEnd = rest.indexOf('>', startIndex = tagStart)
            if (tagEnd < 0) break
            val attrs = rest.substring(tagStart + MEMBER_OPEN.length, tagEnd)
            rest.substring(tagEnd + 1).indexOf(MEMBER_CLOSE).let { closeAt ->
                // The body (unescaped member output, itself possibly holding
                // <subagent> tags) runs to the balanced close; only what follows
                // can still hold depth-0 members.
                rest = if (closeAt < 0) "" else rest.substring(tagEnd + 1 + closeAt + MEMBER_CLOSE.length)
            }
            when (attribute(attrs, "outcome")) {
                "completed" -> Outcome.Completed
                "failed" -> Outcome.Failed
                "aborted" -> Outcome.Aborted
                // No outcome, no member — never a half-parsed row.
                else -> continue
            }.let { outcome ->
                members += Member(
                    agentId = attribute(attrs, "agent_id")?.takeIf { it.isNotBlank() },
                    item = attribute(attrs, "item")?.takeIf { it.isNotBlank() },
                    outcome = outcome,
                    stopReason = attribute(attrs, "stop_reason")?.takeIf { it.isNotBlank() },
                    notStarted = attribute(attrs, "state") == "not_started",
                )
            }
        }
        var completed = 0
        var failed = 0
        var aborted = 0
        SUMMARY.find(text)?.let { match ->
            COUNT.findAll(match.groupValues[1]).forEach { count ->
                when (count.groupValues[1]) {
                    "completed" -> completed = count.groupValues[2].toInt()
                    "failed" -> failed = count.groupValues[2].toInt()
                    "aborted" -> aborted = count.groupValues[2].toInt()
                }
            }
        }
        return SwarmResult(completed, failed, aborted, members)
    }

    private const val ROOT_OPEN = "<agent_swarm_result>"
    private const val MEMBER_OPEN = "<subagent"
    private const val MEMBER_CLOSE = "</subagent>"
    private val SUMMARY = Regex("""<summary>(.*?)</summary>""")
    // "completed: 1, failed: 2" — a count shows only when > 0 (renderSwarmSummary).
    private val COUNT = Regex("""(completed|failed|aborted):\s*(\d+)""")

    /** One attribute of an open tag's raw attribute run; null when absent or
     * unterminated. Values are XML-unescaped (the renderer escapes `& " < >`).
     * The leading-space marker keeps a short name (`state`) from matching inside
     * a longer one. */
    private fun attribute(attrs: String, name: String): String? {
        val marker = " $name=\""
        val start = attrs.indexOf(marker)
        if (start < 0) return null
        val valueStart = start + marker.length
        val valueEnd = attrs.indexOf('"', startIndex = valueStart)
        if (valueEnd < 0) return null
        return unescape(attrs.substring(valueStart, valueEnd))
    }

    private fun unescape(value: String): String =
        value.replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
}
