# Send crash: context.spliced beats the prompt ACK, echo twins the row

- **ID:** 164
- **Created:** 2026-09-25
- **Depends on:** none

## Context

Louis's report 2026-09-25: the app crashes on every sent message, starting
with the server 2.1.0/2.1.1 nightly updates. Reproduced on the emulator with
Louis's exact release APK on his failing session ("Integrate Kimi Code with
Junie AI"): `IllegalArgumentException: Key "msg_…" was already used` from
`LazyListState.scrollToItem` (the send's scroll-to-bottom forcing the
measure that hits the duplicate key).

Instrumented capture shows the mechanism: server 2.1.x broadcasts
`context.spliced` within milliseconds of writing the POST /prompts response,
and the warm socket's dispatch lands BEFORE the REST continuation —
`splice applied 4ms before the ACK's echo` (logcat, release build). The
splice commits the canonical user-message row; `appendLocalEcho` then ran
with no dedup and appended a second row under the same id → duplicate
LazyColumn key → crash. Debug builds process the ACK first (slower
pipelines), which is why the Firebase Test Lab repro passed.

## Description

`ChatTimeline.appendLocalEcho` now checks for an existing row with the ACK's
prompt id before appending. If the splice already committed it, the existing
row is adopted into `localEchoes` (prompt.queued/started marks keep healing
in place, the refetch's echo survival keeps working) instead of appending a
twin.

Regression tests (ChatTimelineTest): `a splice that beats the prompt ACK
leaves exactly one bubble`, `a splice-adopted echo still takes the queued
mark` — both RED on the pre-fix code, GREEN after.

Verified on-device (emulator, fixed release APK, the same session): two
consecutive sends, no crash, exactly one bubble each.
