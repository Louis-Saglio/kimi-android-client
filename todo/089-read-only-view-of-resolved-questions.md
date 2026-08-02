# Read-only view of resolved questions

- **ID:** 089
- **Created:** 2026-08-02
- **Depends on:** 088

## Context

Follow-up to 088 (minimizable question card). Tapping a resolved
`AskUserQuestion` tool row currently expands raw JSON details. Louis wants it
to open the question card instead, read-only, with the previously given
answer(s) shown as selected.

Design discussion (2026-08-02, product-owner + UX pass) settled on:

- **Uniform row rule**: EVERY `AskUserQuestion` row — pending or resolved —
  loses its chevron and details expansion entirely. Tap on the pending row
  toggles the live card (088); tap on a resolved row opens the read-only
  card. Louis's call: the raw JSON is no longer useful now that the details
  render in a proper UI. This also removes 088's standalone chevron tap
  target on pending rows.
- **Docked card, read-only** — the same dock slot as the live card (chosen
  over a modal bottom sheet and a full-screen dialog), as a `readOnly` mode
  of the existing `QuestionCard`, NOT a second composable: the read-only
  card is a simplified subset of the live one and shares its code.
- **Wizard pagination kept** for multi-item questions (Back/Next, no answer
  gating, no Submit/Dismiss).
- **Dismissed questions included**: same read-only view showing the
  "Dismissed without answering" note — same code path, cheap.
- Data is parsed locally from the row's own strings — no server call, no
  protocol change.

## Description

1. **Parser + model** (`data/chat`, new file, e.g. `ResolvedQuestion.kt`):
   parse a resolved row's `input` string (the call args: `questions[]` with
   `id`/`question`/`header`/`options[{id,label,description?}]`/`allow_other`)
   and `output` string (`{"answers":{"<question text>":"<option label or free
   text>"}}` answered, or `{"answers":{},"note":"..."}` dismissed — shapes per
   docs/server-api.md, Questions) into a `ResolvedQuestion` model: the items
   (reuse `PendingQuestion.Item` if it fits) plus, per item, the given answer
   (matched option label, or free text when no option label matches — the
   output maps question TEXT to label/free-text, so selection marking is a
   label-match heuristic; two items with identical question text are an
   accepted ambiguity) plus the dismissed note. Unparseable input/output
   yields null — the row tap then no-ops.
2. **State** (`ChatViewModel.UiState`): `resolvedQuestionView:
   ResolvedQuestion? = null` and its wizard step (a separate
   `resolvedQuestionStep: Int = 0` — the live `questionStep` stays untouched).
3. **Intents** (`QuestionWizard` collaborator + one-line ViewModel delegates):
   - `onRowTapped(toolCallId: String, input: String, output: String)`: if
     `toolCallId` is the pending head question's id, toggle the live card
     (088's `onToggle` semantics); else parse and show the read-only view
     (step 0); tapping the row of the currently shown resolved view closes
     it (toggle).
   - `onCloseResolvedQuestion()`.
   - Back/Next for the resolved step (mirror `onNext`/`onBack` without the
     answered gating).
4. **Precedence** (dock shows at most one card): while
   `resolvedQuestionView != null` it occupies the dock — a live pending card
   waits behind it and returns when the view closes. In
   `prunedQuestionDrafts` (the 088 choke point every queue mutation flows
   through): when the head question changes, clear `resolvedQuestionView` —
   live input always wins, a newly surfaced question shows expanded.
5. **Card** (`QuestionCard` in `ui/chat/components/Cards.kt`): add a
   `readOnly` mode — radios checked from the parsed answers and disabled,
   free-text answers rendered as plain text (no text field), footer keeps
   only Back/Next (no gating, no Submit/Dismiss), the header swaps the
   minimize chevron for a close (X) `IconButton`
   (`contentDescription = "Close"`). Dismissed state: the note text in place
   of the answers. Single-item questions hide Back/Next as today.
6. **Row** (`ToolRows.kt`): `AskUserQuestion` rows never show a chevron and
   never expand — delete 088's `toggleable` chevron special-case and the
   details branch for question rows; the tap invokes a single
   `onQuestionRowTapped(toolCallId, input, output)` callback threaded from
   `ChatScreen` → `MessageList` → `ToolGroupCard`/`ToolRow` (replacing
   `toggleableQuestionId`/`onToggleQuestion`; the live-vs-resolved decision
   moves into the collaborator). Non-question rows are untouched.
7. **Screen** (`ChatScreen.kt`): `bottomBar` renders `resolvedQuestionView`'s
   read-only card when set, else the 088 live-card logic; a `BackHandler`
   closes the resolved view; wire `onQuestionRowTapped`.
8. **Tests** (no trimming):
   - Parser unit tests: answered single-option, answered free-text (`other`),
     multi-item, dismissed-with-note, malformed input/output → null.
   - ViewModel tests on `ChatViewModelTestBase`: resolved row opens the view
     with the right answer marked; same-row re-tap closes; close intent;
     resolved Back/Next walks items; opening a resolved view while a live
     question is pending hides the live card and closing brings it back; a
     new `question.requested` while the view is open closes it and shows the
     live card; pending-row tap still toggles the live card (088 regression).
   - androidTests (`ToolRowTest.kt` + a card test): question rows have no
     chevron (pending and resolved); read-only card shows the selected
     option checked and disabled, free text as text, dismissed note, X
     closes; non-question rows keep their expand behavior.

## Acceptance criteria

- [ ] Tapping a resolved (answered) `AskUserQuestion` row opens the docked
      card read-only with the previously selected option checked (free-text
      answers shown as text); multi-item questions paginate with Back/Next.
- [ ] Tapping a dismissed question's row shows the questions and the
      dismissed note.
- [ ] The view closes via the header X, re-tapping the same row, and system
      Back.
- [ ] With the view open, a live pending card waits behind it and returns on
      close; a newly surfaced head question closes the view and shows
      expanded.
- [ ] No `AskUserQuestion` row shows a chevron or expands; all other tool
      rows keep their current expand behavior.
- [ ] `./gradlew :app:testDebugUnitTest` passes including the new parser and
      ViewModel tests.
- [ ] Release APK built and published to the file server per AGENTS.md.

## Out of scope

- Persisting the read-only view across re-entry/process death.
- Multi-select answer kinds (`multi`, `multi_with_other`, `skipped` —
  hinted in the web bundle, never emitted by our flow; the parser treats
  unknown shapes as unparseable).
- Changing non-question tool rows or their details expansion.
- Any server or `docs/server-api.md` changes — the protocol already carries
  everything needed.
- Editing answers (re-answering a resolved question is a server 40902).

## Notes

- The row's `input`/`output` strings are pretty-printed but valid JSON
  (`formatDetail`, `ChatMessageMapper.kt:27`), so the parser works on the
  strings the timeline already holds — no new data plumbing.
- `questionId == toolCallId == ToolActivity.id` (ticket 088) — the
  live-vs-resolved decision is one string comparison.
- Estimate ~400–500 lines total diff, roughly half tests; production delta
  ~250 lines since the card is a shared `readOnly` mode, not a copy. If the
  diff threatens 500, split parser+model (ticket 1) from UI (ticket 2).
