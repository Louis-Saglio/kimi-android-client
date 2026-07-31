# Oversized attachment picks are misreported as "unreadable"

- **ID:** 063
- **Created:** 2026-07-31
- **Depends on:** none

## Context

Found in the 2026-07-31 tech-debt audit. Ticket 059 set the client-side pick
cap to 256 MiB. `AttachmentStore.read` (`AttachmentStore.kt:77-80`) checks the
provider-advertised size first, then streams through `readCapped`
(`:103-113`), which returns `null` both when the stream can't be opened **and**
when the content exceeds the cap. Providers lie about
`OpenableColumns.SIZE` (or omit it → `size = -1`), so a >256 MiB pick with a
wrong/absent advertised size falls through the first guard and surfaces as
`Unreadable` instead of `TooLarge` — the ticket-059 feature misreports its
own main failure mode, and the user is told to retry something that can never
succeed.

## Description

- Make `readCapped` distinguish overflow from I/O failure (e.g. a small
  sealed result, or `null` = unreadable plus an explicit overflow signal —
  implementer's choice, keep it private to `AttachmentStore`).
- Map overflow → `AttachmentError.TooLarge(displayName)`; keep genuine open/
  read failures → `Unreadable`.
- Unit test in `AttachmentStoreTest`: a stream longer than the cap with no
  (or a lying) advertised size yields `TooLarge`; a broken stream still
  yields `Unreadable`.

## Acceptance criteria

- [x] Oversize-with-bad-advertised-size pick reports `TooLarge`
  (unit-tested).
  *(`AttachmentStoreTest.an oversize pick with no advertised size reports
  TooLarge` and `an oversize pick with a lying advertised size reports
  TooLarge` — RED pre-fix (expected TooLarge(video.mp4), got
  Unreadable(video.mp4)), GREEN after; `a pick whose stream breaks mid-read
  still reports Unreadable` and `a pick whose stream cannot be opened still
  reports Unreadable` pin the unchanged failure mapping.)*
- [x] `./gradlew :app:testDebugUnitTest` green.
  *(292 tests, 18 classes, 0 failures.)*

## Implementation notes (2026-07-31)

`readCapped` now returns a sealed `CappedRead` (Ok / Overflow / Failed)
instead of collapsing overflow and open failure into the same null; the
mapping lives in `readPicked`, the ContentResolver-free seam extracted from
`AndroidAttachmentStore.read` so JVM tests can drive it (`max` injectable so
the overflow tests don't stream a real 256 MiB). `Overflow` maps to
`TooLarge`; genuine open/read failures stay `Unreadable` — a throwing
`openInputStream` and an `IOException` mid-read now land there too instead of
escaping the pick coroutine. The flow shape is unchanged (advertised-size
guard first, capped stream read as the real guard), so the
`MAX_PICK_BYTES` comment stays accurate as-is.

## Out of scope

- Reconsidering the 256 MiB cap itself (settled in ticket 059).

## Notes

- `AttachmentStore.kt:49-56` documents why the client-side cap exists at all
  (the server removed its own upload cap in 0.31.0) — keep that comment
  accurate if the flow changes shape.
