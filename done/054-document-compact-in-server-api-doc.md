# Document the compact feature in the server API doc

- **ID:** 054
- **Created:** 2026-07-30
- **Depends on:** none

## Context

Louis wants a compact action in the Android app (the equivalent of the TUI's
`/compact`): the server summarises a session's history to free up context
window. Before any app work, the wire contract must be pinned down in
`docs/server-api.md`, the protocol reference every client change relies on.
Today the doc does not mention compact at all.

Static reconnaissance (2026-07-30, against the installed `kimi` binary and the
embedded web client bundle — **unverified, to be confirmed live**):

- The web client triggers compact via
  `POST /api/v1/sessions/{session_id}:compact` (`compactSession` in the web
  bundle), with an optional body that carries an `instruction` field
  (`compactSessionRequestSchema` in `packages/protocol`).
- Compaction produces a WebSocket event `event.session.history_compacted`,
  mapped by the web client to
  `{sessionId, beforeSeq, reason, summaryMessageId}` — i.e. payload fields
  `before_seq`, `reason`, `summary_message_id`.
- Other compaction-related strings exist in the binary
  (`compaction.started/completed/failed/cancelled/blocked`,
  `session.history_compacted`, `auto_compact`); whether any of them are
  wire-visible events is unknown and must be checked.

Alternatives considered: reverse-engineering only from the web client source
(rejected — the doc's convention is live verification against the running
server, and payload shapes differ between ACK and history in other endpoints);
implementing the app feature without documenting first (rejected — undocumented
protocol guesses are exactly what the nightly server-drift automation exists to
prevent).

## Description

Add a `## Compact` section to `docs/server-api.md` (place it near
`## Abort`/`## Steer`, the other session-action endpoints), verified live
against the VPS server following the doc's existing conventions (curl +
websocat, real captured payloads, secrets redacted, server version noted,
entry in the `## Verification log`).

Cover, at minimum:

1. **Trigger** — `POST /api/v1/sessions/{id}:compact`: exact request body
   shape (empty vs `{instruction: "..."}`), response envelope and status
   codes. Confirm whether the endpoint exists as found in recon or has a
   different shape.
2. **WebSocket frames** — every event a subscribed client sees during and
   after compaction: `event.session.history_compacted` (all payload fields,
   what `before_seq` refers to, possible `reason` values, what
   `summary_message_id` points at), plus any busy/usage/status side effects
   (`event.session.work_changed`, `agent.status.updated`, usage updates) and
   whether compaction lifecycle events are exposed.
3. **Effect on history and status** — what `GET .../messages` returns after
   compaction (shape of the summary message, what happens to messages at or
   before `before_seq`, pagination behaviour) and how
   `GET .../sessions/{id}/status` (`context_tokens`, `context_usage`) changes.
4. **Constraints and error cases** — compact while a turn is running, while a
   permission/question is pending, on an empty/fresh session; record the exact
   error envelopes or observed behaviour.

If the recon above proves wrong (different endpoint, different event names),
document what the server actually does — the recon is a starting hint, not a
spec.

Keep `scripts/protocol-update-prompt.md` aligned with the doc's structure if
it enumerates sections.

## Acceptance criteria

- [x] `docs/server-api.md` has a `## Compact` section with a live-verified
      `POST :compact` request/response example (token redacted, server version
      noted).
- [x] The section shows real captured WebSocket frames emitted by a
      compaction, including `event.session.history_compacted` with all payload
      fields explained.
- [x] Post-compaction `GET .../messages` and `GET .../status` behaviour is
      documented from live observations.
- [x] At least the busy-session and empty-session cases are probed live and
      their outcomes recorded.
- [x] The `## Verification log` section gains an entry for this verification.

## Out of scope

- Any Android app change (UI, repository, ViewModel) — that is the follow-up
  feature ticket, to be written once this doc lands.
- Auto-compaction behaviour (`auto_compact`) unless it surfaces incidentally.
- Changing the doc's existing structure or re-verifying unrelated sections.

## Notes

- Recon details: web client bundle inside the `kimi` binary contains
  `compactSession(id, instruction?)` →
  `` POST `/sessions/${id}:compact` `` and the event mapping for
  `event.session.history_compacted`; the request/response schemas are
  `compactSessionRequestSchema` / `compactSessionResponseSchema` in
  `packages/protocol` of <https://github.com/MoonshotAI/kimi-code>
  (`apps/kimi-web` is the client source reference per `AGENTS.md`).
- Live probing recipe: create a throwaway session via REST, run a couple of
  prompts to build history, subscribe via websocat, POST `:compact`, capture
  frames, then re-fetch messages/status. Tools and auth header conventions are
  documented at the top of `docs/server-api.md`.
