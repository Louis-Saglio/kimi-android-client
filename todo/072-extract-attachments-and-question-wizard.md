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

- [ ] `ChatViewModel.kt` drops below ~700 lines with the attachments and
  question-wizard logic living in their own files.
- [ ] `./gradlew :app:testDebugUnitTest` green; chat androidTests green on
  Firebase Test Lab.

## Out of scope

- Splitting the socket/timeline core further — that's the cohesive heart
  and stays.
- Extracting model/effort pickers or compaction — small enough to leave;
  the next big feature grows a collaborator from day one instead.

## Notes

- Update `AGENTS.md` (`ui/<screen>/` conventions) if the collaborator
  pattern becomes the documented way new chat features are added.
