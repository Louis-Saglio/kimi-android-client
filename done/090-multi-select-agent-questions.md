# Multi-select agent questions

- **ID:** 090
- **Created:** 2026-08-02
- **Depends on:** none

## Context

The agent's `AskUserQuestion` tool can ask multi-select questions
(`multi_select: true`). The web client renders those with checkboxes and lets
the user tick several options; the Android app always renders radio buttons,
so today a user facing a multi-select question can only ever pick one option
— the app silently sends a `single` answer to a question the agent asked as
multi. That's a correctness bug from the user's seat, not just missing polish.

The wire contract was verified live on 2026-08-02 against server 0.31.1 (and
against the upstream repo `packages/protocol/src/question.ts`) — see
`docs/server-api.md`, Questions section:

- Every question item carries a `multi_select` boolean, present in both
  `true` and `false` form, on every surface (`event.question.requested`,
  `/questions?status=pending`, session snapshot). History `tool_use` input
  carries it too, but with label-only options.
- Answer kinds: `{"kind":"multi","option_ids":[...]}` (min 1 — empty is
  refused with envelope `40001` and the question stays pending) and
  `{"kind":"multi_with_other","option_ids":[...],"other_text":"..."}`.
- The server does NOT validate option ids — a bogus id resolves the question.
  Client-side validation is the only guard.
- `event.question.answered.answers` and `tool.result.output` flatten multi
  answers to labels joined with `", "` (`"Red, Blue"`), free text appended
  the same way for `multi_with_other` (`"Dog, Fish"`).

UX decisions (matching the web client, `apps/kimi-web/.../QuestionCard.vue`):
checkboxes for multi-select items, radio buttons stay for single-select — the
canonical M3 affordance pair; do not unify into one control. A multi item is
answered when ≥1 option is ticked OR the Other text is non-blank; zero
ticked options with no text is not submittable (matches both the web UX and
the server's `40001`). No max-selection cap. No pre-checking of
"(Recommended)" options — deferred (see Out of scope).

Originally discussed as separate data-layer and UI tickets; deliberately kept
as one user-value ticket at Louis's request — keep the diff lean, no
opportunistic refactors of the existing single-select path.

## Description

End to end: when a pending question item has `multi_select: true`, the
question card lets the user tick any subset of its options (plus the Other
field when allowed) and submits a `multi` / `multi_with_other` answer;
resolved multi questions render correctly in the read-only view (ticket 089).

- Data layer: add `multi_select` (default `false`) to `QuestionItemDto` and
  `PendingQuestion.Item`; add `QuestionAnswer.Multi(itemId, optionIds)` and
  `QuestionAnswer.MultiWithOther(itemId, optionIds, otherText)` serializing
  to the verified body shapes above.
- `QuestionWizard` / `ChatViewModel.UiState`: track per-item multi selections
  (e.g. widen `questionSelections` to itemId → set of option ids; keep the
  single-select behavior identical). Update `isQuestionStepAnswered` /
  `isQuestionSubmittable` gating as decided above. On submit, per item:
  non-blank Other text wins alone → `Other`; text + ticks → `MultiWithOther`;
  ticks only → `Multi`; single-select items keep `OptionPick`. Never emit an
  option id that isn't in the item's options.
- Question card (`ui/chat/components/Cards.kt`): render `Checkbox` rows for
  multi items (toggling adds/removes from the item's selection set),
  `RadioButton` rows unchanged for single items. Same row layout, labels and
  descriptions. Read-only rendering (minimized/resolved views) shows the
  checked state, disabled.
- Resolved-question view (`ResolvedQuestionParser`, ticket 089): parse the
  flattened `", "`-joined values — match each segment against the item's
  option labels (history options are label-only); matched segments mark those
  options selected, unmatched segments rejoin as the Other free text.
  Single-select rendering must not regress.
- Tests: unit tests next to the collaborators on `ChatViewModelTestBase`
  (DTO/mapper `multi_select` parsing incl. absent → false; answer body
  shapes; gating; submit mapping) and `QuestionCardTest` androidTest updates
  (checkbox rendering, multi-toggle, submit gating; radio path unchanged).

## Acceptance criteria

- [ ] `./gradlew :app:testDebugUnitTest` passes, including new tests:
  `multi_select` parsed (true/false/absent), `multi` and `multi_with_other`
  bodies serialize exactly as `{"kind":"multi","option_ids":[...]}` /
  `{"kind":"multi_with_other","option_ids":[...],"other_text":"..."}`, and
  the gating rules (multi item unanswered with zero ticks and blank Other;
  answered with ≥1 tick or non-blank Other).
- [ ] `./gradlew :app:connectedDebugAndroidTest` (or the Firebase Test Lab
  equivalent) passes, including a `QuestionCardTest` case that ticks two
  options on a multi item and submits; existing single-select card tests
  still pass unchanged.
- [ ] Manual live check against the VPS server: prompt a throwaway session to
  call `AskUserQuestion` with `multi_select: true`, answer from the app with
  two ticked options, and confirm the agent receives the joined labels
  (`"Red, Blue"` in the `tool.result` output / `event.question.answered`).
- [ ] A resolved multi question opened from its timeline row (089 view)
  shows the ticked options checked and any Other text; a resolved
  single-select question renders exactly as before this ticket.

## Out of scope

- Pre-checking "(Recommended)" options (the web client does this; separate
  follow-up if Louis wants it).
- The `skipped` answer kind and the optional `method`/`note` body fields
  (source-only, never observed live — no client need identified).
- Any redesign of the question card layout, wizard navigation, or the
  minimize/resolved-view behaviors (tickets 088/089).
- Server-side validation of option ids (server behavior, out of our control).

## Notes

- Protocol reference: `docs/server-api.md`, Questions section (updated with
  the 2026-08-02 live verification). Key files: `data/chat/Question.kt`,
  `data/chat/ChatDtos.kt` (`QuestionItemDto`), `ui/chat/QuestionWizard.kt`,
  `ui/chat/components/Cards.kt` (radio rows ~line 174),
  `data/chat/ResolvedQuestion.kt`.
- The web client's resolved-view re-matching logic lives in
  `apps/kimi-web/src/components/chat/tool-calls/askUserToolParse.ts`
  (`resolveAnswer`) — the upstream repo was cloned to `/tmp/kimi-code-src`
  during the spike if a reference is needed.
