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

- [x] Unit + androidTests above pass; existing suite green. (One existing
      test updated for the new enrichment — listed in the implementation
      notes.)
- [ ] On device: spawning an explore sub-agent shows a readable spawn
      row; tapping it opens the sub-agent page (or the job screen for a
      background spawn). (No device run in the implementing session — the
      androidTests are compile-checked only, and the row's tap wiring reuses
      the 045/096 chevron idioms those suites already cover.)
- [x] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes (run with
      `:app:assembleDebugAndroidTest` too); release APK published to the
      file server per AGENTS.md. (Gradle passes; the release APK is
      published by the merging session.)

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

## Implementation notes (2026-09-23)

- **DTO:** `ToolInputDisplayDto` gains `agent_name`/`prompt`/`background`
  (default false) — ChatDtos.kt. The `command` kind and unknown-kind
  tolerance are untouched (kind stays a plain string; an unknown kind
  simply parses to no agent call).
- **Domain:** `AgentCallDisplay(agentName, prompt, background)`
  (ChatEvents.kt), parsed by `ChatMessageMapper.agentCall(display)` —
  null for the command kind, unknown kinds, and a nameless `agent_call`.
  Carried on `SessionEvent.ToolCallStarted.agentCall` and
  `ChatItem.ToolActivity.agentCall` (first-writer-wins in
  `ChatTimeline.upsertTool`, the args rule), so the socket's started
  frame and the snapshot's `running_tools` seed (`RunningTool`, ticket
  144's indistinguishable-seed invariant) both reach the row. History
  `tool_use` parts carry no display (verified docs/server-api.md, the
  verbatim history part), so a refetched spawn row falls back to the
  generic treatment — the wire has no other source.
- **Job link:** `agentTaskIdOf(name, output)` (Tasks.kt, the
  `bashTaskIdOf` shape — FIRST line `task_id: agent-…`, the background
  result contract's head; a foreground result starts at `agent_id:` and
  never carries the line), derived in the one `enriched` pass (098) onto
  `ToolActivity.agentTaskId`.
- **Row:** `AgentSpawnRow` (ToolRows.kt) — title "{agent_name}
  sub-agent", the prompt as a ≤2-line prose preview (the WebSearch
  query's rule), the "Background" badge (the "one-shot" badge's shape),
  the status glyph, and the trailing chevron; never expands inline.
  `agentSpawnTap()` decides the tap: foreground → sub-agent page (the
  045 `subagentId` link), background → job screen (the 096 `onOpenJob`
  link). No new glyph (the ticket names none) — the generic tool icon
  leads.
- **Tests:** 4 parse cases in ChatMessageMapperTest (doc-verbatim JSON,
  background true/false, command/unknown-kind tolerance, nameless);
  AgentSpawnTapTest (the two tap targets + the inert/unlinked cases);
  2 ToolRowTest androidTests (name + prompt + badge render; no badge
  for a foreground spawn).
- **Existing-test change, listed per convention:**
  ChatTimelineTest's `a background agent result does not link the row to
  a job` became `a background agent result links its agent task id but
  never the bash one` — the 157 enrichment adds `agentTaskId` to the
  asserted row; the 096 guard it covered (`bashTaskId` stays null for
  `agent-` ids) is unchanged and still asserted by the full-row
  equality.
