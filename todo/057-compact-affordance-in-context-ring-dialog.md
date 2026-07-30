# Compact affordance in the context ring dialog at ≥80%

- **ID:** 057
- **Created:** 2026-07-30
- **Depends on:** 056

## Context

056 puts compact behind the chat overflow menu. That is the right permanent
home, but it has a discoverability gap: compact becomes relevant exactly when
the context ring turns red (≥80%), and a user staring at the ring may not
think of opening a menu. The web client closes this gap with a `/compact`
chip that appears in the composer at ≥80% (`apps/kimi-web`
`Composer.vue`/`ContextRing.vue`; noted in 041, deferred there and again in
053).

Decision (2026-07-30, with Louis): close the gap from the ring itself, not
with new composer chrome. The ring's tap already opens the exact-numbers
dialog (041) — at ≥80% that dialog gains a "Compact session" button, so the
action sits next to the numbers that motivate it. Rationale over the web's
chip: no ephemeral composer element (053 just decluttered that row and
squeezed it to ~315–340dp during turns), and the dialog is the natural "what
do I do about this?" surface.

## Description

In `ContextUsageRing`'s exact-numbers dialog (`ui/chat/`):

- When `contextUsage` is ≥ 80% (same threshold as the existing error-color
  rule — share one constant), the dialog shows a "Compact session" button
  below the numbers.
- The button calls the same `ChatViewModel.onCompact()` path as the 056 menu
  item, with the same enabled rules: disabled while `isWorking` or
  `isCompacting`.
- On tap the dialog dismisses; the 056 compacting divider in the timeline
  takes over the feedback. No extra snackbar from the dialog itself.
- Below 80% the dialog is unchanged (numbers only).

## Acceptance criteria

- [ ] At ≥80% context usage the ring dialog shows "Compact session"; below
      80% it does not (threshold logic unit-tested, boundary at 80%).
- [ ] Tapping the button triggers the same compact flow as the overflow menu
      (ViewModel test: one shared entry point) and dismisses the dialog.
- [ ] The button is disabled while a turn runs or a compaction is in
      progress.
- [ ] `./gradlew :app:testDebugUnitTest` passes; release APK built and copied
      to the file server per AGENTS.md.

## Out of scope

- A composer chip or any other always-visible ≥80% affordance (rejected in
  discussion — 056 Context).
- A context-usage caption or compact affordance on session-list rows (still
  deferred from 041).
- Changing the 80% threshold or making it configurable.

## Notes

- The threshold and the error-color rule must not drift apart: extract/share
  the constant rather than duplicating the literal.
- Reuse 056's `onCompact()` verbatim — do not add a second compact entry
  point with its own guards.
