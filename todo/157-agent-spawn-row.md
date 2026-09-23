# Agent spawn row (`display.kind: "agent_call"`)

- **ID:** 157
- **Created:** 2026-09-23
- **Depends on:** none

## Context

Since 0.31.0 an `Agent` tool call's `tool.call.started` carries a typed
display: `{"kind":"agent_call","agent_name":"explore","prompt":"…"}`
(plus `"background":true` for background spawns; verbatim at
docs/server-api.md:3709 and 6243). `agent_call` appears nowhere in the
app's code or tickets: Agent spawns today fall back to the generic
args-JSON row, so the chat shows a wall of JSON instead of "explore: …"
at the exact moment a sub-agent starts. Tickets 122/123/124 built the
rich-row family for other tools; the spawn row joins it.

## Description

**1. DTO.** Extend the `display` parsing on `ToolCallPayload`
(ChatDtos.kt:204) with the `agent_call` kind: `agent_name: String`,
`prompt: String`, `background: Boolean = false`. Keep the existing
`command` kind and unknown-kind tolerance untouched.

**2. Spawn row.** A dedicated row for `agent_call`: title
"{agent_name} sub-agent", the prompt as 1–2 line preview, a "Background"
badge when `background`. Tap follows the existing deep links: foreground
spawn → sub-agent page (045/046); background spawn → the job screen
(096/098) for its task. Follow the 122/123 row idioms (same paddings,
badge style).

**3. Tests.** Parse test with the doc-verbatim display (name, prompt,
background true/false); row ViewModel test for the two tap targets;
androidTest: spawn row renders name + preview + badge.

## Acceptance criteria

- [ ] Unit + androidTests above pass; existing suite green.
- [ ] On device: spawning an explore sub-agent shows a readable spawn
      row; tapping it opens the sub-agent page (or the job screen for a
      background spawn).
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Live progress inside the spawn row (the swarm card 135 and sub-agent
  page own progress).
- Parsing the spawn's result prose (`stop_reason:`, `resume_hint:` —
  158 owns the hint).
- `AgentSwarm` spawn rows (the swarm card already aggregates them).

## Notes

- `agent_name` is the subagent type/name as registered server-side;
  correlate with the roster via the `subagent.spawned` event as today
  (134), not via new fields.
