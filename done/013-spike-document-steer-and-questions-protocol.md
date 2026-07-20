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

- [x] `docs/server-api.md` contains Steer and Questions sections with payload
  shapes verified against the live server (commands + verbatim responses
  inline), and the header scope note no longer lists steer/questions as
  skipped.
  *(2026-07-20, server 0.27.0: new "Steer" and "Questions" sections, steer +
  question round-trip sequences and verbatim `prompt.steered` /
  `event.question.*` payloads in the WS section, `pending_interaction`
  gains `"question"` in Sessions and `work_changed`. Scope line now reads
  "core loop + user interaction".)*
- [x] Every shape claimed in the new sections was observed live (curl/websocat
  output in the ticket or doc), not inferred from the web bundle.
  *(All claims come from 2026-07-20 curl/websocat captures. Live findings that
  contradicted the bundle leads: `GET /questions/{qid}` is 404 (only
  `GET /questions?status=pending` exists), a successful dismiss returns HTTP
  200 with non-zero envelope `code:40909`, `question_id` == `toolCallId`, no
  v2 camelCase question events. Bundle hints not observed live (`multi` /
  `multi_with_other` / `skipped` answer kinds, `method`/`note` fields) are
  explicitly marked "not verified live" in the doc.)*
- [x] `scripts/protocol-update-prompt.md` reviewed; updated if needed.
  *(Step 3 now also re-verifies `prompts:steer` and the questions flow so the
  watch catches drift in the new sections.)*

## Out of scope

Any app code (014/015 consume this doc), the approval badge (016 — protocol
already documented), all other undocumented endpoints (fs, terminals, tasks,
goal, workspaces, providers — later batches).

## Notes

- Web bundle endpoint references (0.27.0): `prompts:steer`,
  `questions/{id}`, `questions/{id}:dismiss`. Treat as leads, not truth.
- Server version moves; record the version verified against in the doc header
  as previous updates did.
