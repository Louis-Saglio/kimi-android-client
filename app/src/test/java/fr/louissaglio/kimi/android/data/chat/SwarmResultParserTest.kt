package fr.louissaglio.kimi.android.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `<agent_swarm_result>` parsing (ticket 136). The multi-member fixtures are
 * VERBATIM from the upstream tool tests (packages/agent-core-v2
 * test/features/swarm/swarm.test.ts at tag 0.43.1 — the wire never carried this
 * text); the single-member ones vary what those pin down: attribute order,
 * `stop_reason`, XML-escaped attribute values, and nested `<subagent>` tags inside
 * member bodies (ignored, like the web's parser).
 */
class SwarmResultParserTest {

    @Test
    fun `the all-completed document parses every member`() {
        // Verbatim upstream fixture (swarm.test.ts, "renders swarm result summary").
        val result = SwarmResultParser.of(
            """
            <agent_swarm_result>
            <summary>completed: 2</summary>
            <subagent agent_id="agent-explore-1" item="src/a.ts" outcome="completed">explore result a</subagent>
            <subagent agent_id="agent-explore-2" item="src/b.ts" outcome="completed">explore result b</subagent>
            </agent_swarm_result>
            """.trimIndent(),
        )

        assertEquals(
            SwarmResultParser.SwarmResult(
                completed = 2, failed = 0, aborted = 0,
                members = listOf(
                    SwarmResultParser.Member("agent-explore-1", "src/a.ts", SwarmResultParser.Outcome.Completed, null, notStarted = false),
                    SwarmResultParser.Member("agent-explore-2", "src/b.ts", SwarmResultParser.Outcome.Completed, null, notStarted = false),
                ),
            ),
            result,
        )
    }

    @Test
    fun `resume members parse like spawned ones`() {
        // Verbatim upstream fixture (swarm.test.ts, the resume run): mode="resume"
        // members first, then the fresh spawns — the mode attribute is inert here.
        val result = SwarmResultParser.of(
            """
            <agent_swarm_result>
            <summary>completed: 3</summary>
            <subagent mode="resume" agent_id="agent-old-1" item="src/old-a.ts" outcome="completed">result 1</subagent>
            <subagent mode="resume" agent_id="agent-old-2" item="src/old-b.ts" outcome="completed">result 2</subagent>
            <subagent agent_id="agent-new-3" item="src/new.ts" outcome="completed">result 3</subagent>
            </agent_swarm_result>
            """.trimIndent(),
        )

        assertEquals(3, result!!.completed)
        assertEquals(
            listOf("agent-old-1", "agent-old-2", "agent-new-3"),
            result.members.map { it.agentId },
        )
        assertEquals(
            listOf("src/old-a.ts", "src/old-b.ts", "src/new.ts"),
            result.members.map { it.item },
        )
    }

    @Test
    fun `a mixed outcome document with a resume hint parses`() {
        // Verbatim upstream fixture (swarm.test.ts, the partial-failure run): the
        // resume_hint element is present but consumed by nothing (ticket 136, out of
        // scope — acting on it is a separate product idea).
        val result = SwarmResultParser.of(
            """
            <agent_swarm_result>
            <summary>completed: 1, failed: 1</summary>
            <resume_hint>Call AgentSwarm with resume_agent_ids using the agent_id values in this result to continue unfinished work.</resume_hint>
            <subagent agent_id="agent-coder-1" item="src/a.ts" outcome="completed">imports are stable</subagent>
            <subagent agent_id="agent-coder-2" item="src/b.ts" outcome="failed">Agent timed out after 30s.</subagent>
            </agent_swarm_result>
            """.trimIndent(),
        )

        assertEquals(
            SwarmResultParser.SwarmResult(
                completed = 1, failed = 1, aborted = 0,
                members = listOf(
                    SwarmResultParser.Member("agent-coder-1", "src/a.ts", SwarmResultParser.Outcome.Completed, null, notStarted = false),
                    SwarmResultParser.Member("agent-coder-2", "src/b.ts", SwarmResultParser.Outcome.Failed, null, notStarted = false),
                ),
            ),
            result,
        )
    }

    @Test
    fun `failed members that never spawned carry no agent id`() {
        // Verbatim upstream fixture (swarm.test.ts, the spawn-failure run): no
        // agent_id and no state attribute at all.
        val result = SwarmResultParser.of(
            """
            <agent_swarm_result>
            <summary>failed: 2</summary>
            <subagent item="src/a.ts" outcome="failed">Agent did not start.</subagent>
            <subagent item="src/b.ts" outcome="failed">Agent also did not start.</subagent>
            </agent_swarm_result>
            """.trimIndent(),
        )

        assertEquals(0, result!!.completed)
        assertEquals(2, result.failed)
        assertEquals(
            listOf(
                SwarmResultParser.Member(null, "src/a.ts", SwarmResultParser.Outcome.Failed, null, notStarted = false),
                SwarmResultParser.Member(null, "src/b.ts", SwarmResultParser.Outcome.Failed, null, notStarted = false),
            ),
            result.members,
        )
    }

    @Test
    fun `an interrupted run parses the not_started ghost`() {
        // Verbatim upstream fixture (swarm.test.ts, the user-cancellation run):
        // state="started" on the aborted mid-flight member, state="not_started" and
        // no agent_id on the one the interrupt beat to the launch.
        val result = SwarmResultParser.of(
            """
            <agent_swarm_result>
            <summary>completed: 1, aborted: 2</summary>
            <resume_hint>Call AgentSwarm with resume_agent_ids using the agent_id values in this result to continue unfinished work.</resume_hint>
            <subagent agent_id="agent-coder-1" item="src/a.ts" outcome="completed">imports are stable</subagent>
            <subagent agent_id="agent-coder-2" item="src/b.ts" state="started" outcome="aborted">The user manually interrupted this subagent batch before this subagent finished.</subagent>
            <subagent item="src/c.ts" state="not_started" outcome="aborted">The user manually interrupted this subagent batch before this subagent was started.</subagent>
            </agent_swarm_result>
            """.trimIndent(),
        )

        assertEquals(
            SwarmResultParser.SwarmResult(
                completed = 1, failed = 0, aborted = 2,
                members = listOf(
                    SwarmResultParser.Member("agent-coder-1", "src/a.ts", SwarmResultParser.Outcome.Completed, null, notStarted = false),
                    SwarmResultParser.Member("agent-coder-2", "src/b.ts", SwarmResultParser.Outcome.Aborted, null, notStarted = false),
                    SwarmResultParser.Member(null, "src/c.ts", SwarmResultParser.Outcome.Aborted, null, notStarted = true),
                ),
            ),
            result,
        )
    }

    @Test
    fun `the attribute order is free and stop_reason parses`() {
        // The renderer's own order is mode/agent_id/item/state/outcome/stop_reason
        // (renderSwarmResults); the parse reads attributes by name. stop_reason rides
        // a completed member whose run stopped on its own condition.
        val result = SwarmResultParser.of(
            """
            <agent_swarm_result>
            <summary>completed: 1</summary>
            <subagent stop_reason="Max turns reached" outcome="completed" item="src/a.ts" agent_id="agent-1">partial result</subagent>
            </agent_swarm_result>
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                SwarmResultParser.Member(
                    "agent-1", "src/a.ts", SwarmResultParser.Outcome.Completed,
                    stopReason = "Max turns reached", notStarted = false,
                ),
            ),
            result!!.members,
        )
    }

    @Test
    fun `attribute values are xml unescaped`() {
        // The renderer escapes & " < > in attribute values (escapeXmlAttribute).
        val result = SwarmResultParser.of(
            """
            <agent_swarm_result>
            <summary>failed: 1</summary>
            <subagent item="a &quot;b&quot; &amp; &lt;c&gt;" outcome="failed">boom</subagent>
            </agent_swarm_result>
            """.trimIndent(),
        )

        assertEquals("""a "b" & <c>""", result!!.members.single().item)
    }

    @Test
    fun `a nested subagent tag inside a member body produces no member`() {
        // Bodies are the members' raw outputs — NOT escaped by the renderer — so a
        // member reporting on swarm XML itself nests the tag family. Only depth-0
        // elements are members (the web's parser rule).
        val result = SwarmResultParser.of(
            """
            <agent_swarm_result>
            <summary>completed: 2</summary>
            <subagent agent_id="agent-1" item="src/a.ts" outcome="completed">the wire shape is <subagent outcome="failed">nested noise</subagent> literally</subagent>
            <subagent agent_id="agent-2" item="src/b.ts" outcome="completed">clean</subagent>
            </agent_swarm_result>
            """.trimIndent(),
        )

        assertEquals(
            listOf("agent-1", "agent-2"),
            result!!.members.map { it.agentId },
        )
    }

    @Test
    fun `an empty member body still parses the member`() {
        // A completed member without a result renders with an empty body.
        val result = SwarmResultParser.of(
            """
            <agent_swarm_result>
            <summary>completed: 1</summary>
            <subagent agent_id="agent-1" item="src/a.ts" outcome="completed"></subagent>
            </agent_swarm_result>
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                SwarmResultParser.Member("agent-1", "src/a.ts", SwarmResultParser.Outcome.Completed, null, notStarted = false),
            ),
            result!!.members,
        )
    }

    @Test
    fun `a subagent element without a readable outcome is skipped`() {
        val result = SwarmResultParser.of(
            """
            <agent_swarm_result>
            <summary>completed: 1</summary>
            <subagent agent_id="agent-1" item="src/a.ts">no outcome at all</subagent>
            <subagent agent_id="agent-2" item="src/b.ts" outcome="completed">ok</subagent>
            </agent_swarm_result>
            """.trimIndent(),
        )

        assertEquals(listOf("agent-2"), result!!.members.map { it.agentId })
    }

    @Test
    fun `non result texts parse as null`() {
        // An Error row's plain prose (the tool's validation failures) is not XML.
        assertNull(SwarmResultParser.of(""))
        assertNull(SwarmResultParser.of("AgentSwarm requires at least 2 items unless resume_agent_ids is provided."))
        // A member fragment without the document root is not a result either.
        assertNull(SwarmResultParser.of("""<subagent agent_id="agent-1" outcome="failed">x</subagent>"""))
    }
}
