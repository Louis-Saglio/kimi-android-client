# Agent swarm: result parsing and member activity lines

- **ID:** 136
- **Created:** 2026-09-15
- **Depends on:** 134, 135

## Context

Live swarm state is volatile: `subagent.*` events are never replayed on
cursor resume, and the snapshot roster is cleared when the main agent starts
its next turn (docs/server-api.md:3595). After a process death or a history
reload, the only persisted per-member record of a swarm is the `AgentSwarm`
tool result text, an XML document (upstream `renderSwarmResults` at tag
0.43.0; never captured on this wire, so fixtures come from the source):

```
<agent_swarm_result>
<summary>completed: N, failed: M, aborted: K</summary>
<resume_hint>...</resume_hint>            <!-- optional -->
<subagent agent_id="agent-3" item="..." outcome="completed|failed|aborted"
          [state="not_started"] [stop_reason="..."]>body</subagent>
...
</agent_swarm_result>
```

Without parsing it, a settled swarm reopened from history shows stale or
missing member states, and members that never spawned (interrupted turn)
vanish entirely. The web backfills rows from this result for exactly that
reason.

Second gap closed here: roster rows have no activity line. The web shows one
muted line per member (last streamed text, suspended reason, or output tail),
which is what makes a running swarm readable at a glance.

## Description

**1. Parser** (`data/chat/SwarmResultParser.kt`). Pure function returning
`SwarmResult(completed: Int, failed: Int, aborted: Int, members: List<Member>)`
with `Member(agentId: String?, item: String?, outcome: Outcome, stopReason:
String?, notStarted: Boolean)`. Hand-rolled tolerant parse (the app's parsers
are lightweight; no XML library for one element family). Fixtures cover all
outcomes, `state="not_started"`, a present `resume_hint`, `mode="resume"`
members, and attribute-order variants taken from the upstream source.

**2. Store healing** (`ui/chat/SubagentStreamStore.kt`). The `AgentSwarm`
branch of `applyAgentCall` consumes the parsed result, both from the live
`tool.result` and from history mining (134 widened the mining filter). Match
members to records by `agent_id`: outcome completed -> Completed, failed ->
Failed, aborted -> Cancelled (135's state). Backfill ghost records for
`not_started` items: status Cancelled, label from the `item` excerpt, no
subagent page link. Precedence rule, documented in KDoc: live events and the
roster win; the result parse only fills members the store has no live or
roster state for, and never demotes a record out of a terminal state.

**3. Ghost rows** (`ui/chat/SwarmScreen.kt`). Records without a page link
render without a chevron and are not clickable.

**4. Activity line** (`ui/chat/SwarmScreen.kt` member rows). One muted,
single-line, ellipsized line under the member label: while Working, the last
assistant text line from the record's live timeline; while Suspended, the
suspended reason; when terminal, the first line of `resultSummary` (or the
error). Reuse existing `SubagentRecord` fields; if `SubagentScreen` already
derives such a preview, share the derivation.

**5. Tests.** Parser fixture tests per above; store tests for healing,
precedence (a live-running record is not overwritten by a stale parsed
result), and ghost backfill; a cold-open test through
`ChatViewModelTestBase`: history containing only the `AgentSwarm` call and
its result yields a fully populated roster; androidTest for the activity
line and the disabled ghost row.

## Acceptance criteria

- [ ] New unit + androidTests above pass; existing suite green.
- [ ] Observable on device: kill the app mid-swarm, reopen the chat from
      history; member statuses match the parsed result, aborted and
      not-started members are visible with cancelled styling, and their rows
      are not clickable.
- [ ] While a swarm runs, member rows show a changing one-line activity;
      suspended members show the suspended reason.
- [ ] A swarm viewed live and then reopened from history shows consistent
      final states (no regression from live to parsed).
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Rendering the raw result XML or the per-member `body` anywhere (the roster
  and member pages are the views; no fallback text view).
- Acting on `resume_hint` (a "resume unfinished members" action is a separate
  product idea).
- Parsing the single-`Agent` prose result blocks (`stop_reason:`,
  `resume_hint:` lines, new in 0.43.0); not needed for swarm support.

## Notes

- Upstream source for fixtures: `packages/agent-core-v2` at tag
  `@moonshot-ai/kimi-code@0.43.0`, swarm result rendering in the AgentSwarm
  tool (`renderSwarmResults`). GitHub API access per AGENTS.md.
- Nested `<subagent>` tags inside member bodies must be ignored by the
  parser, same as the web's parser does.
