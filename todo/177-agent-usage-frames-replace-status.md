# Drive model, permission and context state from `agent` + `usage` frames

- **ID:** 177
- **Created:** 2026-11-04
- **Depends on:** 175

## Context

Found by the nightly server drift run from 2.4.1 to 2.5.0 on 2026-11-04
(server 2.5.0, upstream commit `011b1d41`, "push agent, usage, goal and todo
state over WebSocket"). `GET /api/v1/sessions/{id}/status` is a plain HTTP
404 now. The app calls it in two places (`ChatViewModel.kt` around lines 1385
and 1676) and models the result as `SessionStatus` (`ChatApi.sessionStatus`),
so the status seed and the "is this session busy?" checks fail on 2.5.0.
`agent.status.updated` — the live carrier of model/effort/permission/context
since tickets 041/107/165 — is also gone.

The replacement is two flat frames on the session subscription, sent before
the ack and again on every change:

- `agent`: `{status:"idle"|"running", model?, thinking?, permission_mode?,
  plan_mode, swarm_mode, tower_mode, agent_id, event_created_at}`. `model`
  and `thinking` are omitted on a model-less session; `permission_mode` is
  the effective `manual`/`yolo`/`auto`.
- `usage`: `{context_tokens, max_context_tokens?, agent_id,
  event_created_at}`, coalesced to a few frames per second.

For main-agent frames the fields map one-to-one onto today's
`SessionEvent.SessionModelUpdated`, `SessionPermissionUpdated`,
`ContextUsageUpdated` and `AgentStatusUpdated` consumers. Child-agent frames
are ticket 182's.

## Description

1. In `SessionSocket`, parse the `agent` and `usage` frames and emit:
   - model/thinking changes → the existing `SessionModelUpdated` path
     (blank/absent fields mean "nothing said", never "unset");
   - `permission_mode` → `SessionPermissionUpdated` (keep the ticket-141
     stored-pick guard);
   - `status` → a busy/activity signal replacing the phase variant: `running`
     while a turn or background work is active, `idle` otherwise;
   - `usage` (when `context_tokens` and a positive `max_context_tokens`
     are present) → `ContextUsageUpdated`; a missing/zero limit stays
     "unknown" and must not render a 0/0 ring.
2. Initialize the chat from the frames that precede the subscribe ack
   instead of a `/status` call: the first `agent`/`usage` pair is the seed.
   Remove both `getSessionStatus` call sites and the `ChatApi.sessionStatus`
   method; keep `SessionStatus` only if a non-status consumer remains,
   otherwise delete it.
3. Treat `agent` as authoritative for the picker defaults: if the user has a
   stored local pick (ticket 141), keep it; otherwise the frame's
   model/thinking is the displayed binding. Do not send a `/status` write.
4. Ignore `goal` and the mode booleans here (ticket 174 owns swarm mode; the
   goal is out of scope).
5. Delete the `agent.status.updated` branch and its DTO; the frame is gone.
   The old phase line (thinking / tool call / retrying / awaiting approval)
   has no direct replacement: derive it from the transcript state (a running
   step with an active tool frame = tool call, `step.retry` = retrying, a
   pending interaction = awaiting approval) or drop the sub-phase line and
   keep `agent.status`. Do not invent a new server field.

## Acceptance criteria

- [ ] Opening a chat shows the bound model, thinking effort, permission mode
      and context ring from the first `agent`/`usage` frames, with no
      `/status` request (unit test asserts the URL is absent).
- [ ] A `POST .../profile {"agent_config":{"permission_mode":"manual"}}`
      from another client flips the chat's permission chip live via the
      `agent` frame.
- [ ] A running turn's context growth updates the ring from `usage` without
      polling; a frame without `max_context_tokens` leaves the ring
      unchanged.
- [ ] A model-less session leaves the model/effort display unbound instead
      of showing blanks or crashing.
- [ ] The stored local pick still wins over the frame (ticket 141 test
      preserved).
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- Reintroducing any `/status`-like REST route (the server has none).
- Swarm/plan/tower controls — ticket 174 owns swarm mode; plan and tower are
  out of scope.
- Goal frames and goal editing.
- Child-agent state rendering — ticket 182.

## Notes

Relevant code: `data/chat/SessionSocket.kt` (`agent.status.updated` branch),
`data/chat/ChatEvents.kt` (`ContextUsageUpdated`, `SessionModelUpdated`,
`SessionPermissionUpdated`, `AgentStatusUpdated`, `SessionStatus`),
`data/chat/ChatApi.kt` (`sessionStatus`), `data/chat/ChatRepository.kt`,
`ui/chat/ChatViewModel.kt` (the two `getSessionStatus` calls, the permission
chip, the context ring). Wire reference: `docs/server-api.md` → WebSocket →
Session frames (`agent`, `usage`).
