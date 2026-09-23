# Live activity status line in chat

- **ID:** 148
- **Created:** 2026-09-23
- **Depends on:** none

## Context

`agent.status.updated` carries a `phase` object since 0.28.1, extended in
0.29.0 (`since` epoch-ms on active kinds, `tool_call` with the tool name,
`awaiting_approval` with the inline approval). Current kinds (2.0.2):
`idle`/`running`/`tool_call`/`retrying`/`awaiting_approval`/`interrupted`/
`ended` — `streaming` was removed in 0.43.0; the phase stays `running`
while tokens stream (docs/server-api.md:4775-4823). The app decodes the
frame's model/usage variant only (`AgentStatusUpdatedPayload`,
ChatDtos.kt:101 — no `phase` field) and the chat shows a generic
`isWorking` spinner, so the user cannot tell "thinking" from "running a
tool" from "waiting for my approval".

Web parity: the web client renders the agent activity phase live.

## Description

**1. DTO/event.** Add `phase` to `AgentStatusUpdatedPayload`
(ChatDtos.kt:101): `{kind, turnId?, step?, stepId?, since?, name?,
toolCallId?, approval?}` — all nullable except `kind`; map `kind` to a
sealed/enum with the seven current values, unknown kinds → null (the
app's ignore-unknown rule). Surface it on the `AgentStatusUpdated` event
(ChatEvents.kt) through the `SessionSocket` mapping.

**2. State.** The chat ViewModel reduces phases to a single
`ActivityStatus?`: `running` → "Thinking…", `tool_call` → "Running
{name}…" (fallback "Running tool…"), `retrying` → "Retrying…",
`awaiting_approval` → "Waiting for approval", `interrupted` → null (the
existing abort UI owns that moment), `idle`/`ended` → null. Carry `since`
for the elapsed readout.

**3. UI.** A status line in the chat (directly above the composer,
replacing the bare spinner): status text + elapsed seconds ticking from
`snapshot` `since` (1 s ticker while visible). The existing spinner stays
as the leading icon.

**4. Tests.** Socket parse tests per kind from the verbatim captures at
docs/server-api.md:4815-4823; ViewModel reduction tests (incl. unknown
kind → null and `interrupted` → null); androidTest: status line shows and
ticks during a tool call, disappears on idle.

## Acceptance criteria

- [ ] Unit + androidTests above pass; existing suite green.
- [ ] On device: a Bash turn shows "Running Bash…" with elapsed seconds;
      an approval request shows "Waiting for approval"; both clear when
      the turn ends.
- [ ] Frames without `phase` (model/usage variant) change nothing.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Phase display on the sub-agent page / swarm card (separate surface;
  the sub-agent socket already parses `agent.status.updated` for
  model/effort).
- `turn.step.retrying` detail (`failedAttempt`/`delayMs` countdown) —
  covered by 058; this ticket is the agent-level phase only.
- Per-step latency fields (`llmFirstTokenLatencyMs` & co) — no perf
  surface exists, rejected in the drift review.

## Notes

- `awaiting_approval`'s inline approval carries `approvalId`/`toolCallId`
  (distinct ids since 0.39.0); this ticket only needs the kind.
- `since` is absent on `ended` (it uses `at`) — elapsed only for active
  kinds.
