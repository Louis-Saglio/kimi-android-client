# Extract attachments and question wizard out of ChatViewModel

- **ID:** 072
- **Created:** 2026-07-31
- **Depends on:** 065, 066

## Context

`ChatViewModel` is 1088 lines owning socket gating, delta conflation,
history paging, approvals, the question wizard, attachments (pick/upload/
open), model+effort pickers, compaction, sub-agent seeding, session info,
context usage, and todos. Both the chat-core and UI audits flag it as the
god-object pressure point: the cohesive core (socket → timeline → UiState)
is good, but every new chat feature lands in this file by default, which is
how it got here — and the size is what invites the duplication fixed in
065/066.

Depends on 065 and 066 so those extractions land first (they shrink the
file and settle the collaborator pattern this ticket follows). The
attachments block (`ChatViewModel.kt:362-450`) and the question wizard
(`:549-605`, `:990-1008`) are the two self-contained features that happen
to live in this file.

## Description

- Extract an attachments collaborator (pick → upload → remove → open
  orchestration against `AttachmentStore`/`ChatApi`) and a question-wizard
  collaborator (pending-question lifecycle, answer submission, dismissal),
  each owned by `ChatViewModel`.
- `UiState` stays the single state holder in `ChatViewModel` — the
  collaborators are logic holders, not second state sources; they receive
  intents and report state updates back (callbacks or flows, following
  whatever pattern 065's `DeltaCoalescer` settles).
- No behavior change. Existing `ChatViewModelTest` coverage must pass with
  unchanged behavior; move feature-specific tests next to the extracted
  collaborators.

## Acceptance criteria

- [x] `ChatViewModel.kt` drops below ~700 lines with the attachments and
  question-wizard logic living in their own files. *(2026-07-31: 1036 → 810,
  with `ChatAttachments.kt` (186 lines) and `QuestionWizard.kt` (183 lines)
  holding the features. Lands above the ~700 estimate: the ticket's 1088-line
  baseline predated 065/066's cuts (actual pre-072 size 1036) and the two
  features held ~275 lines including the state types, polls and seed guard, so
  810 is the floor without dragging approvals/stepRetry into the extraction
  (explicitly "small enough to leave") or splitting the `Effect` sealed
  hierarchy (rejected — `SendFailed` is shared with the send core, so the
  attachment effects would be split across files either way).)*
- [x] `./gradlew :app:testDebugUnitTest` green; chat androidTests green on
  Firebase Test Lab. *(2026-07-31: 306 unit tests, 0 failures — ChatViewModelTest
  47, QuestionWizardTest 12, ChatAttachmentsTest 5. Instrumented suite 42/42
  passed on MediumPhone.arm-34, incl. all chat androidTests.)*

## Out of scope

- Splitting the socket/timeline core further — that's the cohesive heart
  and stays.
- Extracting model/effort pickers or compaction — small enough to leave;
  the next big feature grows a collaborator from day one instead.

## Notes

- Update `AGENTS.md` (`ui/<screen>/` conventions) if the collaborator
  pattern becomes the documented way new chat features are added. *(Done
  2026-07-31: new "Chat feature collaborators" bullet documents the
  logic-holder/callback contract and the grow-a-collaborator-from-day-one
  rule.)*
- 2026-07-31: the collaborators follow 065's callback pattern — constructor
  takes a scope provider, the repositories, a `UiState` transform sink and a
  suspend `Effect` sink (`QuestionWizard` also reads state); `UiState`/`Effect`
  stayed nested in `ChatViewModel`, and 12 one-line delegates kept the
  screen-facing API so `ChatScreen.kt` is untouched. `PendingAttachment`/
  `AttachmentBytes` moved to top level in `ChatAttachments.kt` (UiState exposes
  them publicly, so they could not go `internal`); the derived question
  properties became `internal` extension properties on `ChatViewModel.UiState`
  in `QuestionWizard.kt` (same package — screen and tests resolve them
  unchanged); the question `SeedGuard` moved into the wizard.
- 2026-07-31: test split — the MockWebServer/socket harness became the
  abstract `ChatViewModelTestBase`; the 5 attachment tests went to
  `ChatAttachmentsTest`, the 12 question/wizard tests to `QuestionWizardTest`,
  both driving the ViewModel end-to-end exactly as before (bodies verbatim,
  `ChatViewModel.PendingAttachment` → `PendingAttachment`). The 028/029
  regression tests (`status seed in flight does not clear a turn that just
  started`, `questions poll in flight does not clear a question that just
  arrived`) pass unmodified.
