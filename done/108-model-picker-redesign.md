# Model picker redesign: provider grouping, steady sheet, effort on chip

- **ID:** 108
- **Created:** 2026-09-01
- **Depends on:** 107

## Context

Louis (2026-09-01): the model selection sheet is "kinda ugly and not
practical". Concretely, from his screenshot:

- Two rows render the identical label "K3-256k" with identical subtitles —
  two providers serving models with the same `display_name`, indistinguishable
  because the row never shows `provider` (the app has parsed it into
  `ServerModel.provider` since 031 and never displays it).
- The sheet is one flat, undifferentiated list: no provider grouping, no model
  id, just display name + context size.
- Every tap dismisses the sheet, so setting model *and* effort takes two
  visits.
- The composer chip shows only the model name; the effective thinking effort
  is invisible without opening the sheet. Ticket 032 decided sheet-only, but
  that was for the old top-bar chip — Louis confirmed revisiting it: the chip
  shows the effort.

Depends on 107 because the picker's displayed state should come from the
server-synced session state it introduces, avoiding conflicting edits in
`ChatViewModel`/`ModelPickerSheet`.

## Description

`ui/chat/components/ModelPickerSheet.kt` and the composer chip:

1. **Group models by provider.** One section per distinct `ServerModel.provider`
   with a small header row (the raw provider id, e.g. `managed:kimi-code`),
   preserving the server's ordering within each group. The existing
   "Thinking effort" section header pattern (`labelMedium`,
   `onSurfaceVariant`) is the style to reuse.
2. **Disambiguate colliding display names.** When two or more models share
   the same `display_name`, append the model id to the subtitle
   ("1M context · kimi-code/k3-256k"); unique names keep the current clean
   subtitle.
3. **Taps no longer dismiss the sheet.** Model and effort taps move the check
   (and persist) but keep the sheet open, so model + effort are settable in
   one visit and the effort section visibly updates when the model changes.
   Dismissal is by scrim/swipe as with any `ModalBottomSheet`. Update the
   sheet's KDoc and the 031/032 behavior comments accordingly.
4. **Effort on the composer chip.** The chip label becomes
   "<model> · <effort>" when the effective effort is known — the session's
   selected effort (post-107 this is server-synced, so usually known), else
   the current model's `default_effort` — and just the model name when the
   model has no `support_efforts`. Update the chip's semantics
   contentDescription accordingly ("Model: K3, effort high").

## Acceptance criteria

- [x] Unit/Compose test: models sharing a `display_name` render in separate
      provider groups with the model id in the subtitle; unique models render
      without it. (`ModelPickerSheetTest`, Firebase Test Lab 2026-09-01)
- [x] Compose test: tapping a model and then an effort keeps the sheet open
      and both checks are visible. (`ModelPickerSheetTest`, same run)
- [x] Unit test: the chip label includes the effort when one is effective,
      and omits it for effort-less models. (`ChatViewModelTest` chip-effort
      test + `ModelChipTest` label/contentDescription pins)
- [x] `./gradlew testDebugUnitTest` passes.
- [ ] Live check on a multi-provider server: the duplicate "K3-256k" rows are
      distinguishable by provider group and model id. (Data side verified live
      2026-09-01: the production server serves "K3-256k" from both
      `managed:kimi-code` and `Kimi Platform`; the grouped/disambiguated
      rendering of exactly that shape is pinned by the instrumented test.
      On-device visual pass: awaiting Louis's retest on the 2026-09-01 APK.)
- [x] APK built and copied to the file server per AGENTS.md.

## Out of scope

- Capability badges (image/video icons) on rows — unrequested.
- Editing the server-wide default from the sheet — ticket 109 puts it in
  Settings.
- Reordering providers or favorites/pinning.
- Any change to how the session model is resolved (107's job).

## Notes

- `ServerModel.provider` is already parsed; no data-layer change needed.
- The sheet's current row composable is `SheetCheckRow`; group headers follow
  the existing "Thinking effort" section header styling.
- `docs/conventions.md` documents the chat feature collaborators — update it
  if the sheet's behavior contract changes.
