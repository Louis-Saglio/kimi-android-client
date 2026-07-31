# Migrate approval events to the v2 permission.approval.* family

- **ID:** 069
- **Created:** 2026-07-31
- **Depends on:** none

## Context

Found in the 2026-07-31 tech-debt audit. `SessionSocket.kt:231-239` consumes
the legacy `event.approval.*` family and deliberately ignores the v2
`permission.approval.*` duplicates. `docs/server-api.md` (0.31.0 captured
round-trip) shows both families emitted — v2 requested at seq 8, legacy at
seq 10, both resolved at seq 12-13 — with the v2 approval id equal to the
`toolCallId`.

The project's stance is "track only the LATEST server API, never keep
compatibility shims" — but here the app rides the *legacy* family. If the
server drops it, approvals break silently: the v2 frames fall into the
ignore branch and no approval card ever appears. This inverts the intended
drift posture.

## Description

- Re-verify the v2 payloads before touching code: `docs/server-api.md`
  (requested/resolved payload shapes, expiry semantics) and the upstream
  web client (`apps/kimi-web` in `MoonshotAI/kimi-code`) — which family it
  renders and with which fields.
- Switch `SessionSocket`/`ChatEvents` to the v2 family and ignore legacy,
  mirroring the current "one family only" structure.
- The REST decision endpoint (`POST .../approvals/{approvalId}`) is
  unchanged; v2's `approvalId == toolCallId` means the approval card can
  correlate with its tool row — check `ChatViewModel`'s approval-card logic
  for assumptions about approval ids before reusing them.
- Update the ticket-007 approval tests to emit v2 frames; keep one test
  asserting legacy frames are ignored.

## Acceptance criteria

- [ ] Approval request/resolve flow works end-to-end on v2 frames
  (unit-tested; manual or robo verification on device against the live
  server).
- [ ] Legacy `event.approval.*` frames are ignored (unit-tested).
- [ ] `./gradlew :app:testDebugUnitTest` green.
- [ ] `docs/server-api.md` client-relevant notes updated if the re-verification
  surfaces drift.

## Out of scope

- Richer approval UX (diff display, always-allow) — this is a wire-family
  migration, behavior stays at parity.
- Removing the legacy family from the server doc — both families are still
  emitted by 0.31.0; the doc describes the server, not the client.

## Notes

- If the re-verification shows the web client still prefers the legacy
  family, stop and report before implementing — the doc's "latest API"
  stance would then need a conversation, not a diff.
