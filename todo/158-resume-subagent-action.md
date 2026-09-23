# Resume-subagent action from the result's `resume_hint`

- **ID:** 158
- **Created:** 2026-09-23
- **Depends on:** none

## Context

Since 0.31.0 the `Agent` tool accepts a `resume` parameter
(`resume="agent-N"` — the agent id, explicitly *not* task_id), and the
foreground result prose gained `next_step:`/`resume_hint:` blocks telling
the orchestrator how to continue that subagent (docs/server-api.md:3718-3766;
0.43.0 added the `stop_reason:` line). Ticket 136 explicitly deferred
"parsing the single-`Agent` prose result blocks" and "acting on
`resume_hint`". Today a finished sub-agent offers no way to continue it:
the hint text is on the wire but the app renders none of it.

## Description

**1. Parser.** Extend the foreground-Agent result parsing in
`SubagentStreamStore` (the `statusFromResult` area, :1100) to extract the
`resume_hint:` (and `next_step:` when present) block, keeping the
existing `stop_reason:`/status parsing intact.

**2. Surface.** On the finished sub-agent page (and the spawn row 157
adds), show a "Next step" section with the hint's text, plus a Continue
action. The action opens the composer pre-filled with the hint's
suggested `Agent(resume="agent-N", prompt="…")` call text — the user
reviews and sends; the server executes it as the next prompt's tool
call. Do not invent a dedicated resume endpoint (none exists).

**3. Tests.** Parser tests for the blocks (verbatim samples at
docs/server-api.md:3730/3748, incl. a result with no hint); ViewModel
test for the prefill payload; androidTest: finished sub-agent shows the
section, Continue prefills the composer.

## Acceptance criteria

- [ ] Unit + androidTests above pass; existing suite green.
- [ ] On device: a finished sub-agent shows the parsed next-step copy;
      Continue pre-fills the composer with the resume call; sending it
      continues the same subagent (server-side resume).
- [ ] Results without a hint render exactly as today.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Swarm-level resume (`AgentSwarm resume_agent_ids`, 3766) — separate
  ticket if wanted.
- Auto-resume without user review (no).
- Changing how the orchestrator model phrases hints (server-side).

## Notes

- The resume hint's prose says to use the agent_id ("agent-N") — surface
  it verbatim; do not translate ids.
