# Chat re-sync after backgrounding: persistent resume cursor + idle clear-live

- **ID:** 048
- **Created:** 2026-07-29
- **Depends on:** 005, 045

## Context

Louis's on-device report (2026-07-29): send a prompt, watch the turn stream,
switch to another app while the agent works, come back after it finished — the
bottom of the chat shows the final message truncated and mixed with thinking
traces. Going back to the session list and reopening the session renders it
correctly.

Root cause (verified in code, two compounding defects):

- **A — the resume cursor does not survive a background/foreground cycle.**
  `ON_STOP` closes the socket gate (045), which cancels the whole
  `SessionSocket.observe()` flow; the cursor (`seq` + `epoch`) and
  `everConnected` are locals of that flow (`SessionSocket.kt:66-68`) and die
  with it. `ON_START` starts a brand-new flow with `cursor = null`, so the
  subscribe replays nothing (docs/server-api.md: "cursor-resume replays
  nothing earlier than the cursor") and the socket emits `Connected`, not
  `Reconnected` — the `Reconnected` branch's refetch never fires either.
  Frames emitted while the app was away (final deltas, `turn.ended`) are
  silently lost.
- **B — the `keepLive` refetch cannot learn the turn ended while away.**
  `onScreenStarted()` refetches with `clearLive = false`; the canonical
  history now contains the COMPLETE final message, but
  `ChatTimeline.pruneCommittedLive()` drops a live assistant bubble only on
  EXACT text+thinking equality (`ChatTimeline.kt:221-226`). The frozen live
  bubble is a truncated PREFIX of the committed message, so it survives and
  renders below the full message (`items() = committed + live`) — the
  "corrupted" tail Louis sees. The healing path (`turn.ended` →
  `refreshHistory(clearLive = true)`) never runs because `turn.ended` was
  missed. Reopening the screen builds a fresh ViewModel/timeline, which is
  why that "fixes" it.

The cursor-resume machinery only covers drops WITHIN one continuous
collection (screen stays visible). The most common phone usage pattern —
background the app mid-turn — goes through the gate teardown and defeats
both self-healing mechanisms.

Alternatives considered and rejected:

- **Prefix-aware live-bubble dedup in `pruneCommittedLive`:** a heuristic
  patch over defect B; restores neither the missed `turn.ended` side effects
  nor approval/question frames lost in the same window. Unneeded once the
  agreed fix lands.
- **Keep the socket open while backgrounded:** the ON_STOP teardown is a
  deliberate battery/lifecycle design (045); the OS can still kill the
  connection, so the re-sync path must be correct regardless.
- **Always `clearLive = true` on the ON_START refetch:** without a cursor,
  returning mid-turn would wipe legitimately in-flight live rows and leave a
  permanent hole in the stream. Only safe in its status-aware form below.

Agreed (Louis confirmed): persist the resume cursor across gate cycles (the
principled fix — replayed `turn.ended` re-enters the existing healing path,
and missed approval/question events replay too), plus a status-aware
`clearLive` on the ON_START refetch as the cheap belt-and-suspenders for any
gap replay cannot cover.

## Description

- **Persist the cursor across gate cycles (`SessionSocket`):** hoist the
  resume cursor and `everConnected` out of `observe()`'s flow-local state
  into `SessionSocket` instance state keyed by session id (the class is a
  shared singleton; a tiny map, no eviction needed — one entry per opened
  session, in-memory only). A subscribe that carries a stored cursor emits
  `SocketEvent.Reconnected` (not `Connected`): a resumed subscription IS a
  reconnect, and the ViewModel's existing `Reconnected` branch
  (`ChatViewModel.kt:600-606`) then refetches history and re-derives pending
  questions, healing missed approval/question frames through code that
  already exists. The duplicate refetch against `onScreenStarted()`'s own is
  harmless — `refreshHistory` is latest-fetch-wins.
- **Handle `resync_required` in the subscribe ack:** persisting a cursor
  across arbitrarily long background windows makes the server's replay buffer
  limit reachable, so parse the `ack` frame payload (`accepted`, `not_found`,
  `resync_required`, `cursors` — shape in docs/server-api.md) instead of
  ignoring acks wholesale. When our session is listed in `resync_required`:
  drop its stored cursor and run the status-aware resync below (replay will
  not come; without this the same silent-loss bug remains for long absences).
- **Status-aware `clearLive` on ON_START (`ChatViewModel`):** replace
  `onScreenStarted()`'s blind `refreshHistory(clearLive = false)` with a
  sequential resync: GET `/status` first, then
  `refreshHistory(clearLive = !status.busy)`. A finished turn can have no
  legitimate live rows, so ghosts are dropped; a running turn keeps today's
  keep-live behavior and the resumed replay fills the gap. Guard the snapshot
  with the existing `turnEventCount` pattern (as in `seedSessionStatus`): if
  a turn event landed while the `/status` GET was in flight, downgrade to
  `clearLive = false`. A failed `/status` falls back to `clearLive = false`
  (today's behavior). The plain in-collection `Reconnected` refetch stays
  keep-live: its replay delivers `turn.ended`, which heals via the existing
  path — only `ON_START` and `resync_required` use the status-aware variant.
- No `ChatTimeline` change is expected; if the resync needs to drop live
  rows outside a refetch, prefer wiring it through `refreshHistory` rather
  than adding a new public mutator.

## Acceptance criteria

- [ ] Unit test (MockWebServer, data/chat suite — a new `SessionSocketTest`
  if cleaner): after a first `observe()` collection saw seq N and was
  cancelled, a second collection's subscribe frame carries
  `cursors: {<sid>: {seq: N, epoch: <same>}}` and the flow emits
  `Reconnected`, not `Connected`.
- [ ] Unit test (`ChatViewModelTest`): live rows seeded by deltas, screen
  stopped, turn ends while "away" (history now holds the full message,
  `/status` answers `busy: false`); `onScreenStarted()` leaves
  `items == canonical history` — no ghost bubble.
- [ ] Unit test (`ChatViewModelTest`): same setup but `/status` answers
  `busy: true` → live rows are kept (no clear-live), stream state intact.
- [ ] Unit test (`ChatViewModelTest`): `/status` says not-busy but a
  `turn.started` lands before the resync refetch applies → live rows kept
  (turnEventCount guard).
- [ ] Unit test: a subscribe ack listing the session in `resync_required`
  drops the stored cursor (next subscribe carries none) and triggers the
  status-aware resync.
- [ ] On-device (Louis's scenario): send a prompt, background mid-stream,
  wait for the turn to finish, foreground → the final message renders once,
  complete, no truncated/thinking-mixed tail.
- [ ] On-device: background mid-turn, foreground while still running → the
  stream resumes without a hole and finishes cleanly.
- [ ] `./gradlew :app:assembleDebug` succeeds, unit tests green; release APK
  published to the file server per AGENTS.md.

## Out of scope

- Prefix-aware live-bubble dedup in `pruneCommittedLive` (rejected heuristic,
  see Context).
- Handling `not_found` in the subscribe ack (deleted-session UX; unrelated).
- Keeping the socket alive in the background / a foreground service (the
  ON_STOP teardown stays).
- Persisting the cursor to disk to survive process death — process death
  already drops all live rows, so there is nothing to resume into.
- Any change to the sub-agent stream store / page (045/046 machinery); the
  main-timeline resync feeds it as today.
- `docs/server-api.md` updates (no protocol change; behavior only).

## Notes

- Key locations: `SessionSocket.kt:58-94` (reconnect loop + flow-local
  cursor), `SessionSocket.kt:266-267` (acks currently ignored),
  `SessionSocket.kt:292-306` (subscribe frame builder),
  `ChatViewModel.kt:306-313` (`onScreenStarted`), `ChatViewModel.kt:600-606`
  (`Reconnected` branch), `ChatViewModel.kt:774-808` (`refreshHistory`),
  `ChatViewModel.kt:956-975` (`seedSessionStatus`'s count-guard pattern to
  reuse), `ChatTimeline.kt:207-230` (`pruneCommittedLive` — reference only).
- Protocol: `docs/server-api.md` WebSocket section — subscribe/ack shape
  (`accepted` / `not_found` / `resync_required` / `cursors`), and the note at
  line ~1034 that resume replays nothing earlier than the cursor.
- The ViewModel's socket gate (045) cancels/restarts `collectSessionSocket`
  on every ON_STOP/ON_START pair — that cycle is exactly what the cursor
  must survive; unit-test it as two sequential `observe()` collections.
- Repro of the original report is the first on-device criterion; keep the
  scenario steps verbatim when verifying.
