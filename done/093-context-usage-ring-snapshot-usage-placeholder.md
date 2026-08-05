# Context usage ring shows 0/0 on cold open (snapshot usage placeholder)

- **ID:** 093
- **Created:** 2026-08-05
- **Depends on:** none

## Context

Bug reported by Louis (2026-08-05): opening a past session shows the composer's
context-usage ring at "0/0 tokens", even in sessions that already used tokens.
The ring fills correctly again after sending a new message.

Investigation (live curl against the 0.32.0 server, real sessions) showed the
root cause is server drift hitting the 085 cold-start design:

- `GET .../snapshot`'s `session.usage` is the server's **all-zero placeholder on
  every read path** — busy sessions included (a busy session with 68005 real
  context tokens per `/status` serves all-zero usage in the bundle; idle
  sessions with 48k/121k real tokens likewise; the sessions list's `usage` is
  also all-zero, `turn_count` included). On 0.31.1 only idle sessions zeroed
  the context pair ("Idle sessions zero both" — the note 085 documented).
- 085 made the snapshot the single cold-start source and dropped the `/status`
  seed on that path ("no legacy burst"). The app mapped the placeholder to
  `ContextUsage(0, 0)` — non-null, so the composer showed the ring with a fake
  0/0 instead of hiding it (its documented "hidden until known" rule).
- Sending a message fires a live `agent.status.updated` frame with the real
  counts, overwriting the fake 0/0 — hence "fills up again after a new message".

The web client (parity reference) already handles this exact behavior:
`isPlaceholderSessionUsage` ("both engines return placeholders for the heavy
session fields on the list/snapshot read paths; the live values arrive via
GET /status and the WS `agent.status.updated` stream") and, on a placeholder
snapshot bundle, a `/status` re-read "so the ring converges on the live value"
(`useKimiWebClient.ts`).

Alternatives considered and rejected: (a) hide the ring on cold open and wait
for live frames — drops the feature for every past session until the next
message, a regression of 041's intent, and the real value is one GET away;
(b) patch the server to populate the snapshot usage — upstream
(MoonshotAI/kimi-code), not ours to patch (085 precedent); (c) unconditional
/status re-read — the conditional one (placeholder only) keeps the bundle's
watermark-atomic value authoritative if the server ever populates it again,
and mirrors the web client exactly.

## Description

- `ChatMessageMapper.toDomain(SnapshotData)`: a zero `context_limit` maps to
  `contextUsage = null` ("unknown"), never a fake `ContextUsage(0, 0)` — the
  composer's "hidden until known" rule applies again.
- `ChatViewModel.applySnapshot`: when the bundle's usage is the placeholder
  (null), re-read `/status` via the existing `seedSessionStatus()` (web
  parity) — covers cold start AND `resync_required`, both of which apply
  snapshots. The seed's guard drops the late response when a live
  `agent.status.updated` frame overtakes the GET. A non-placeholder bundle
  needs no re-read (watermark-atomic, applied as before).
- Test harness models the real 0.32.0 wire: the derived default snapshot and
  the `SnapshotColdStartTest` bundle serve the all-zero usage placeholder.
- Docs: `docs/server-api.md` snapshot section + verification log (0.32.0
  placeholder drift), `docs/conventions.md` cold-start paragraph.

## Acceptance criteria

- [x] Regression tests: cold open with a placeholder-usage snapshot seeds the
  ring from `/status` (`the cold start seeds the ring from status when the
  snapshot usage is the placeholder`); a non-placeholder bundle applies
  without a `/status` re-read; a live usage frame landing before the
  cold-start `/status` response wins (`a live usage frame overtakes the
  cold-start status seed`); mapper: placeholder → null, real usage → mapped
  (`snapshot placeholder usage maps to no context usage`).
- [x] Existing suite green — in particular the 085 "no legacy burst" test
  (a failed snapshot still fires no `/messages`/`/status`/`questions`).
- [x] Live-verified on the VPS server (0.32.0, 2026-08-05): snapshot
  `session.usage` all-zero on busy AND idle sessions; `/status` reports the
  real fill for the same sessions (captures in docs/server-api.md log).
- [x] `./gradlew :app:testDebugUnitTest` (426 tests) and `:app:assembleRelease`
  pass; the release APK is on the file server
  (`kimi-android-client-main.apk`).
- [ ] On-device verification by Louis: cold-open a past session → the ring
  shows the real fill without sending a message.

## Out of scope

- Patching the server to populate the snapshot/list usage (upstream; the
  client-side convergence is the parity-sanctioned structural fix available
  here).
- The sessions LIST's zeroed `usage`/`turn_count` — nothing in the app
  consumes those fields on the list path; revisit if a list-side usage
  feature is ever planned.

## Notes

- Root-cause chain: composer's non-null `ContextUsage(0, 0)` ← mapper
  consuming the placeholder ← 0.32.0 snapshot-usage drift ← 085's
  snapshot-only cold start (sound design; the wire shape changed under it).
- Web parity source: `apps/kimi-web/src/api/daemon/mappers.ts`
  (`isPlaceholderSessionUsage`) and `useKimiWebClient.ts` (`refreshSessionStatus`
  on placeholder bundles, incl. the resync path).
- Related done tickets: 041 (ring), 053 (ring into the composer), 057 (compact
  affordance), 085 (snapshot cold start), 066 (seed guards).
