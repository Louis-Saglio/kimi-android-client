# Question wizard UI: one item at a time

- **ID:** 035
- **Created:** 2026-07-26
- **Depends on:** none (fixes a bug in 015's UI)

## Context

Bug report from Louis (2026-07-26, with screenshots). `QuestionCard`
(`app/src/main/java/fr/louissaglio/kimi/android/ui/chat/ChatScreen.kt`) stacks
**all** items of a pending question in a non-scrollable `Column` inside the
Scaffold `bottomBar`. When the agent asks several questions at once (observed:
3 items × 3 options with descriptions + "other" fields), the card grows past
the screen height: the top of the card — including the first options — is
clipped and unreachable, so the question cannot be answered from the phone and
the session is stuck.

The web client solves the same situation with a wizard: one item per step,
"Q1/3" progress, **Next question › / ‹ Back / Dismiss**. Product decision
(2026-07-26): copy that pattern. Alternatives considered and rejected:

- *Scrollable all-at-once card* — smallest diff, but cramped on a phone and
  diverges from web parity.
- *Modal bottom sheet* — more room, but breaks the established inline
  bottomBar pattern (shared with `ApprovalCard`), and swipe-to-dismiss would
  have to be blocked since dismiss posts to the server.

The change is purely presentational: `ChatViewModel` already keys selections
and other-texts by `itemId`, so stepping back and forth loses nothing.

## Description

- `QuestionCard` renders **one item per step** instead of `items.forEach`:
  header, question text, its options, and its "other" field (all unchanged,
  just scoped to the current item).
- Header shows progress within the question, e.g. `Question from Kimi — 2 of 3`,
  in addition to the existing queue indicator (`1 of N`) when several
  questions are pending.
- Footer buttons:
  - **‹ Back** — hidden on the first item; returns to the previous item,
    preserving its selection/other text.
  - **Next ›** — shown on all but the last item; enabled only when the
    current item has a selection or non-blank other text.
  - **Submit** — replaces Next on the last item; enablement stays governed by
    the existing `isQuestionSubmittable`.
  - **Dismiss** — unchanged.
- Track the current step index in `ChatViewModel`'s `UiState` (not
  `rememberSaveable`) so navigation is unit-testable; reset it to 0 whenever
  the displayed `PendingQuestion` changes (new question, queue head advances).
- Safety net: cap the item area with a max height and make it vertically
  scrollable, so even a single item with many options can never push the card
  off-screen again.
- Single-item questions behave exactly as today: Submit directly, no
  Back/Next, no progress indicator.

## Acceptance criteria

- [ ] ViewModel unit tests: stepping Next/Back updates the current index
  within bounds; Next is gated on the current item being answered; the index
  resets when the pending question changes.
- [ ] A multi-item question (e.g. 3 items × 3 options with descriptions)
  renders fully on a phone screen with nothing clipped; the first item's
  options are all reachable (verify with a Compose preview of that state).
- [ ] A single item with a long option list (e.g. 10 options) scrolls inside
  the card without the card exceeding its max height.
- [ ] Answering a multi-item question still submits all items' answers in one
  call, including answers given on earlier steps (existing submit path
  unchanged; covered by existing tests).
- [ ] Single-item questions show no Back/Next and submit as before.
- [ ] `./gradlew :app:assembleDebug` succeeds with all unit tests green.

## Out of scope

- Multi-select questions, free-text-only questions, or any question type the
  current protocol handling does not already support.
- Modal bottom-sheet / full-screen redesign of the question surface.
- Any change to the answer/dismiss wire protocol (`docs/server-api.md`).
- Changes to `ApprovalCard` (same overflow risk in theory, not observed —
  fix only if it actually occurs).

## Notes

- Current implementation: `QuestionCard` at `ChatScreen.kt:683`, wired in the
  `bottomBar` at `ChatScreen.kt:395`.
- Web client reference for the wizard UX: `apps/kimi-web` in
  <https://github.com/MoonshotAI/kimi-code>.
- Keep the existing card styling (secondaryContainer, 12.dp padding); only the
  item pagination and footer buttons change.
