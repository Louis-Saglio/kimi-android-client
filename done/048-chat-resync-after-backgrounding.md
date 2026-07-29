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

- [x] Unit test (MockWebServer, data/chat suite — a new `SessionSocketTest`
  if cleaner): after a first `observe()` collection saw seq N and was
  cancelled, a second collection's subscribe frame carries
  `cursors: {<sid>: {seq: N, epoch: <same>}}` and the flow emits
  `Reconnected`, not `Connected`.
  *(`ChatRepositoryTest.a fresh collection resumes with the stored cursor and
  emits Reconnected` — two sequential collections, the resume subscribe
  carries `"seq":5`/`"epoch":"ep_1"` and the second collection's first
  emission is `Reconnected`. Lives in `ChatRepositoryTest` next to the other
  socket tests rather than a new file.)*
- [x] Unit test (`ChatViewModelTest`): live rows seeded by deltas, screen
  stopped, turn ends while "away" (history now holds the full message,
  `/status` answers `busy: false`); `onScreenStarted()` leaves
  `items == canonical history` — no ghost bubble.
  *(`a turn that ended while the screen was away leaves no ghost live rows`
  — also asserts the re-subscribe carries the stored cursor; Louis's exact
  scenario.)*
- [x] Unit test (`ChatViewModelTest`): same setup but `/status` answers
  `busy: true` → live rows are kept (no clear-live), stream state intact.
  *(`a turn still running on return keeps its live rows`.)*
- [x] Unit test (`ChatViewModelTest`): `/status` says not-busy but a
  `turn.started` lands before the resync refetch applies → live rows kept
  (turnEventCount guard).
  *(`a turn event racing the resync status fetch downgrades the clear` — the
  /status response is parked server-side behind a latch so the turn event
  deterministically overtakes the snapshot.)*
- [x] Unit test: a subscribe ack listing the session in `resync_required`
  drops the stored cursor (next subscribe carries none) and triggers the
  status-aware resync.
  *(`ChatRepositoryTest.a resync_required ack drops the stored cursor and
  emits ResyncRequired` (cursor gone, event emitted) +
  `ChatViewModelTest.a resync_required ack re-syncs from REST` (a fresh
  /status + /messages pair follows the ack).)*
- [ ] On-device (Louis's scenario): send a prompt, background mid-stream,
  wait for the turn to finish, foreground → the final message renders once,
  complete, no truncated/thinking-mixed tail.
  *(Mechanics unit-tested above; release APK published to the file server —
  on-device verification is Louis's.)*
- [ ] On-device: background mid-turn, foreground while still running → the
  stream resumes without a hole and finishes cleanly.
  *(Same — Louis's.)*
- [x] `./gradlew :app:assembleDebug` succeeds, unit tests green; release APK
  published to the file server per AGENTS.md.
  *(2026-07-29: full unit suite green — 6 new tests (2 in
  `ChatRepositoryTest`, 4 in `ChatViewModelTest`), each re-run 15x to prove
  the resync dedup fixed the racing-guard flake; the suite's only remaining
  flake is the pre-existing recorder race noted below. Firebase Test Lab
  robo passed on MediumPhone.arm-34; release APK published to the file
  server.)*

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
- 2026-07-29 implementation notes (two deliberate refinements of the
  Description above):
  - The `Reconnected` branch ALSO runs the status-aware resync (the ticket
    text had it staying keep-live): `refreshHistory` is latest-fetch-wins,
    and the socket's `Reconnected` fires after `onScreenStarted()`'s resync
    launch, so a keep-live refetch from that branch could clobber the
    ON_START resync's clear-live decision. One unified path removes the
    race; mid-turn reconnects are unchanged (busy → keep-live).
  - Resyncs are latest-wins (`resyncJob` cancels the superseded one):
    ON_START and the resumed socket's `Reconnected` fire together and would
    otherwise run duplicate /status + /messages round trips. Under the unit
    tests' `UnconfinedTestDispatcher` those duplicate refetches resumed
    their `onSuccess` blocks concurrently on two OkHttp threads and
    interleaved `ChatTimeline.committed` mutations (message committed
    twice) — production serializes on Main, but the dedup removes the
    smell, not only the flake.
  - `everConnected` is gone: a subscribe carrying a stored cursor emits
    `Reconnected` by definition, which covers both in-collection reconnects
    and gate-cycle resumes.
- Pre-existing flake, NOT from this ticket (verified at the pre-change
  baseline, ~1 in 3-6 full-suite runs): `ChatViewModelTest.stream deltas
  publish the leading edge then coalesce…` — the assistant-text recorder's
  `CopyOnWriteArrayList` races concurrent StateFlow emissions under
  `UnconfinedTestDispatcher` (symptoms: missing trailing flush emission, or
  a same-contents list-type equals mismatch). Worth its own ticket if it
  gets noisier.
