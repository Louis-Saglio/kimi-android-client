# Offer session-lock takeover on repeated 40931

- **ID:** 169
- **Created:** 2026-10-23
- **Depends on:** 168

## Context

Found by the nightly server drift run from 2.3.0 to 2.3.1 on 2026-10-23.
Server 2.3.1, upstream commit `c569bab5`, adds
`POST /api/v1/sessions/{id}:takeover`, which evicts the cross-process
occupancy lock so a write that failed with `40931 SESSION_OCCUPIED` can be
retried — the escape hatch for locks left behind by a crashed or exited
client. Commit `0e3980e7` adds an optional `clientDisplayName` to the 40931
`details.holder` ("Kimi Code CLI", "Kimi Code Web", …) and makes the error
message name it. Both verified live: a CLI-held session rejected a server
prompt with 40931 naming "Kimi Code CLI", `:takeover` deleted the lock file,
and the retried prompt then ran.

Ticket 168 already handles 40931/40932 send failures but explicitly excluded
taking over another process and displaying holder metadata. The upstream TUI
shows the intended flow (same release): first 40931 → Retry/Cancel; if the
retry is rejected again → Cancel/Take over, with a warning that taking over a
still-running holder can interleave the history. On the phone, Louis hits
40931 when a session is open in the CLI or web client — today the app can
only tell him to go close it.

## Description

Build on ticket 168's occupied-session message. Name the holding client from
the error envelope's `details.holder.clientDisplayName` when present
(fallback: the server's `msg` text). Keep the first 40931 response a plain
explanation plus Retry.

When a retried send fails with 40931 a second time, add a "Take over" action
next to Retry, styled as destructive, with the interleave warning. Tapping it
calls `POST /api/v1/sessions/{id}:takeover` (body `{}`, success
`{"taken_over":true}`, unknown session `40401`) and then returns the user to
the composer with the draft intact — it never resubmits the prompt itself.
If the takeover call fails, surface that failure and keep the draft.

## Acceptance criteria

- [ ] The occupied message shows the holder's `clientDisplayName` when the
      envelope carries it, and stays correct when the field is absent.
- [ ] "Take over" is offered only after a second consecutive 40931 on the
      same send, never on the first.
- [ ] Take over issues exactly one `:takeover` POST and no prompt POST; the
      composer draft survives the whole flow.
- [ ] A failed takeover reports the failure and leaves the draft intact.
- [ ] Unit tests cover the display-name fallback, the second-40931 gating,
      and takeover success/failure paths; `:app:testDebugUnitTest` passes.

## Out of scope

- Automatic takeover, automatic retry after takeover, or any prompt
  resubmission without an explicit user tap.
- Takeover affordances for abort, compact, archive, delete, or profile
  writes.
- Exposing lock tokens, pids, or hostnames; the display name is enough.
- The `KIMI_CODE_SESSION_LOCK=0` server switch and the 40932 reload path
  (ticket 168).

## Notes

Relevant code: `data/network/Envelope.kt` (the envelope currently drops
`details`), `DataError.kt`, `data/chat/ChatApi.kt`, `ChatRepository.kt`, and
`ui/chat/ChatViewModel.kt`. The envelope needs an optional `details` pass-through
for 40931 only — do not grow a generic details model. The route shape and the
verbatim 40931 envelope are in `docs/server-api.md` (Sessions → Takeover and
Occupancy lock).
