# Spike: document steer + questions wire protocol

- **ID:** 013
- **Created:** 2026-07-20
- **Depends on:** none

## Context

Product discussion 2026-07-20: MVP is done (tickets 001–012); the goal is
parity with the Kimi Code web client, defined per-feature as "everything
needed to drive and monitor the agent from the phone". First batch is the
"unblock the agent" loop: steer a running turn (014), answer agent questions
(015), approval badge in the session list (016).

`docs/server-api.md` covers only the core loop. The web client's bundle shows
endpoints the doc never verified: `POST /sessions/{id}/prompts:steer`,
`GET/POST /sessions/{id}/questions/{qid}` and
`POST /sessions/{id}/questions/{qid}:dismiss`. Implementing against
unverified shapes is how we got breakage tickets 011/012, so — same pattern
as ticket 001 — we document first, against the live server.

## Description

Verify against the live local server (`http://127.0.0.1:58627`, no auth; use
throwaway sessions as in earlier spikes; websocat at
`~/tools/websocat-bin/websocat`):

1. **Steer** — `POST /api/v1/sessions/{id}/prompts:steer`. Body shape,
   response shape, behaviour when the session is idle vs mid-turn, and the
   exact WS event sequence it produces (does the steered message arrive via
   `context.spliced`? does it start a new step/turn?).
2. **Questions** — how a question surfaces (WS event type + payload, and/or
   `pending_interaction` value), `GET /api/v1/sessions/{id}/questions/{qid}`,
   answering via `POST /api/v1/sessions/{id}/questions/{qid}` (body shape:
   free text? selected option?), and
   `POST /api/v1/sessions/{id}/questions/{qid}:dismiss`. WS events emitted on
   answer/dismiss. To trigger a real question, prompt a session to use its
   user-question tool.
3. Extend `docs/server-api.md` with two new sections (Steer, Questions)
   following the existing style: exact verification commands, verbatim
   payloads, scope note updated.
4. Check `scripts/protocol-update-prompt.md` still matches the doc's
   structure; adjust if the new sections break its assumptions.

## Acceptance criteria

- [ ] `docs/server-api.md` contains Steer and Questions sections with payload
  shapes verified against the live server (commands + verbatim responses
  inline), and the header scope note no longer lists steer/questions as
  skipped.
- [ ] Every shape claimed in the new sections was observed live (curl/websocat
  output in the ticket or doc), not inferred from the web bundle.
- [ ] `scripts/protocol-update-prompt.md` reviewed; updated if needed.

## Out of scope

Any app code (014/015 consume this doc), the approval badge (016 — protocol
already documented), all other undocumented endpoints (fs, terminals, tasks,
goal, workspaces, providers — later batches).

## Notes

- Web bundle endpoint references (0.27.0): `prompts:steer`,
  `questions/{id}`, `questions/{id}:dismiss`. Treat as leads, not truth.
- Server version moves; record the version verified against in the doc header
  as previous updates did.
