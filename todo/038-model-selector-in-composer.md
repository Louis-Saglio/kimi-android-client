# Move model selector into the composer and unify the input bar

- **ID:** 038
- **Created:** 2026-07-26
- **Depends on:** none

## Context

UX review comparing the app with the web UI. The web composer is a single pill
holding attach + input + model/effort selector ("K3-256k · high") + send. The
app exiled the model `AssistChip` to the `TopAppBar` — screen-level chrome for
what is a per-message concern — and renders attach / text field / send as
three loose pieces.

Decisions taken:

- Move the model selector into the composer and visually unify the composer
  into one rounded container, echoing the web.
- Keep M3 touch targets (≥ 48dp) and the Steer behavior. Do **not** copy the
  web's compact desktop hit areas.
- The working spinner and stop button stay in the top bar — they are
  screen-level status and are correctly placed there.

## Description

- Remove the model `AssistChip` from the `TopAppBar` actions in `ChatContent`.
- In `ChatInputBar`, add a small model/effort chip showing the current label
  (e.g. "K3 · high") that opens the existing `ModelPickerSheet`. Placement
  (row above the field, or leading element) is the implementer's choice;
  the chip must keep a ≥ 48dp touch target and stay hidden when
  `currentModelLabel` is null (same rule as today).
- Visually unify attach button + text field + send button in a single rounded
  container on `surfaceContainerHigh`: the `OutlinedTextField` loses its
  outline (plain `TextField` with transparent colors, or a styled
  `BasicTextField`) so the outer container provides the boundary.
- Keep unchanged: the attachments `LazyRow`, `imePadding`, the Steer button
  swap while `isWorking`, and all send/attach enabled logic.

## Acceptance criteria

- [ ] Model chip no longer appears in the top bar; the composer chip opens the
      same `ModelPickerSheet` and reflects the current model/effort selection.
- [ ] Composer renders as one container in both light and dark theme.
- [ ] All interactive elements keep ≥ 48dp touch targets.
- [ ] Steer behavior, attachment chips row, and send enablement unchanged
      (existing `ChatViewModel` tests still pass; `ChatContentPreview`
      updated).

## Out of scope

- Redesigning `ModelPickerSheet` itself.
- Showing effort as a separate chip or adding per-message model overrides —
  the chip opens the same sheet as today.
- Pixel-fidelity with the web pill where it conflicts with M3 touch targets.

## Notes

- All changes are in `ChatScreen.kt` (`ChatContent`, `ChatInputBar`); the
  sheet and the ViewModel need no changes.
