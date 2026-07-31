# Socket keep-alive must survive process death on a sub-agent page

- **ID:** 064
- **Created:** 2026-07-31
- **Depends on:** none

## Context

Found in the 2026-07-31 tech-debt audit. The chat's socket collection is the
`SubagentStreamStore`'s ONLY feeder (ticket 045 design,
`SubagentStreamStore.kt:38-43`), and it stays alive while a sub-agent page is
open via a gate in `ChatViewModel.init` (`ChatViewModel.kt:292-299`) fed by
`subagentStore.openPageCount`.

After **process death with a sub-agent page on top**, Navigation restores the
back stack but only composes the top destination — the chat entry's
ViewModel is never created, the gate never opens, no socket exists. The page
still renders (transcript fetch works, `SubagentViewModel.kt:89-92`) and
`pageOpened` still fires, but nothing consumes it: live streaming is
silently absent until the user backs out to chat. The store's single-feeder
invariant thus depends on a screen whose existence is incidental.

## Description

Move the keep-alive ownership to a component whose lifecycle matches the
need — e.g. an application-scoped singleton (the `SubagentStreamStore`
itself, or a small `SessionSocketHolder`) that collects
`SessionSocket.observe` while `openPageCount > 0`, independent of
`ChatViewModel`.

Hard constraint: **exactly one socket collection per session at any time.**
When the chat screen is visible its collection is the feeder (current
design); the new owner must coordinate so the store is never double-fed —
two concurrent collections hold two resume cursors and double-apply replayed
events. Design the hand-off between the chat-driven collection and the
page-driven collection (chat open/close vs page open/close) so one feeding
collection exists in every state combination.

Tests:

- `pageOpened` with no `ChatViewModel` → a socket collection starts and the
  store receives events.
- Opening the chat screen afterwards keeps exactly one collection (no
  double-feed).

## Acceptance criteria

- [ ] Live streaming works on a sub-agent page restored after process death
  without visiting the chat screen first (unit-tested at the owner/store
  level; manual or robo verification on device if feasible).
- [ ] No state combination produces two concurrent collections for one
  session (unit-tested).
- [ ] `./gradlew :app:testDebugUnitTest` green.

## Out of scope

- Changing cursor/resume/resync semantics (ticket 048 machinery) — the fix
  is in collection ownership, not the transport.
- Liveness detection for a wedged-but-open socket (separate audit low;
  revisit only if observed in the field).

## Notes

- Process death with a page on top is an ordinary Android scenario (app
  backgrounded while reading a sub-agent transcript, system reclaims the
  process).
