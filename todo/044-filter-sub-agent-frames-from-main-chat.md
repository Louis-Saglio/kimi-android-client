# Filter sub-agent frames out of the main chat stream

- **ID:** 044
- **Created:** 2026-07-28
- **Depends on:** none

## Context

Sub-agents stream their whole turn over the **same** WebSocket session channel
as the main agent, tagged with `payload.agentId` = the sub-agent's id
(`agent-12`, …) instead of `"main"` (`docs/server-api.md`, Sub-agents section,
verified live 2026-07-28). The app's payload DTOs ignore `agentId` entirely
(`DeltaPayload` & co. in `ChatDtos.kt`), so when a session spawns a sub-agent
(`Agent`/`AgentSwarm` tool) the main chat corrupts:

- sub-agent `assistant.delta`/`thinking.delta` splice into the main live bubble;
- its `context.spliced` drops its internal system messages (task prompt, summary
  nudges) into the chat as user bubbles;
- its tool calls render as main-agent tool rows;
- its `prompt.completed` (internal prompt ids) and `agent.status.updated`
  (usage/context tokens) hit the main working state and the context-usage ring
  (041) mid-turn.

Product decision (2026-07-28, with Louis): sub-agent activity must get its own
surface (a detail page, ticket 045), never leak into the main transcript. This
ticket is the mandatory first step — stop the corruption; sub-agent work simply
becomes invisible except for the `Agent` tool row and its result.

One deliberate exception: **interaction events stay unfiltered**.
`event.approval.*` / `event.question.*` raised by a sub-agent are actionable —
dropping them would hang the session on an answer nobody can give. They keep
surfacing in the main chat exactly as today.

## Description

- In `SessionSocket.handleFrame`, extract `agentId` from the payload of every
  frame the app consumes (add `agentId: String = "main"` to the payload DTOs in
  `ChatDtos.kt`, or decode a tiny peek data class first — implementer's choice,
  one mechanism only).
- When `agentId != "main"`, do **not** emit a `SessionEvent` for the
  transcript-family frames: `assistant.delta`, `thinking.delta`,
  `tool.call.delta`, `tool.call.started`, `tool.result`, `tool.progress`,
  `context.spliced`, `turn.started`, `turn.ended`, `prompt.completed`,
  `agent.status.updated`.
- Never filter: `event.approval.requested/resolved`,
  `event.question.requested/answered/dismissed` (actionable, see above),
  `event.session.work_changed`, `session.meta.updated`, `prompt.steered`,
  `prompt.aborted` (all main-scoped anyway — filtering is a no-op safeguard).
- Deliberately filtered frames get a `Log.d` (frame type + agentId), not the
  `Log.w` reserved for malformed/dropped frames (AGENTS.md convention) — this
  is intended routing, not data loss.
- Resume-cursor advancement (`frame.seq`) stays as-is: it operates on the
  envelope, before any payload filtering.

## Acceptance criteria

- [ ] Unit tests (socket level, frame fixtures): a frame with
      `payload.agentId = "agent-1"` produces **no** `SessionEvent` for each
      transcript-family type; the same frame with `"main"` (or no `agentId`)
      still emits.
- [ ] Unit test: approval-requested and question-requested frames with a
      sub-agent `agentId` still emit their events.
- [ ] Existing `SessionSocket`/chat unit tests stay green.
- [ ] Manual check on a real session that spawns a sub-agent (e.g. prompt:
      "use your Agent tool once with subagent_type explore…"): the main chat
      shows only the `Agent` tool row and its result — no foreign text, no
      foreign user bubbles, no premature working-state flips.
- [ ] `./gradlew :app:assembleDebug` succeeds, unit tests green.

## Out of scope

- Rendering sub-agent activity anywhere (045 detail page, 047 tasks screen).
- Parsing `subagent.*` / `task.*` lifecycle events (045/047 add them).
- Any REST work (`/tasks`, `/transcript`).

## Notes

- Wire reference: `docs/server-api.md` → Sub-agents (id namespaces, frame
  tagging, verbatim captures).
- Filter point: `SessionSocket.handleFrame` (`data/chat/SessionSocket.kt`,
  ~line 165) — single `when (frame.type)` dispatch; add the agentId guard
  before it rather than inside each branch.
- Sub-agent turn ids, prompt ids and tool-call ids are per-agent and can
  collide with the main agent's; routing by id alone is impossible — the
  `agentId` tag is the only reliable discriminator.
