# Minimizable question card

- **ID:** 088
- **Created:** 2026-08-02
- **Depends on:** none

## Context

The pending-question card (`QuestionCard`) renders in the chat `bottomBar` above
the composer and can cover most of the timeline. Today the only ways out are
Submit or Dismiss — and Dismiss is destructive (it POSTs `:dismiss`, resolving
the question server-side). Louis wants to set the card aside to re-read the
agent messages leading up to the question, then come back to it.

Design discussion (2026-08-02, product-owner + UX pass) settled on:

- **Fully hidden while minimized; the timeline's `AskUserQuestion` tool row is
  the reopen handle.** Chosen by Louis over the web client's actual behavior
  (research of `apps/kimi-web/src/components/chat/QuestionCard.vue` showed the
  web collapses to a thin pinned bar, and its transcript row is NOT wired to
  reopen — `toUiQuestion` even drops `toolCallId`). Rationale: `AskUserQuestion`
  blocks the turn, so its tool row stays the last timeline item and is always
  within reach; a fully hidden card keeps the screen clean.
- **Reset (auto-reopen) whenever the surfaced head question changes.** The
  server CAN hold concurrent pending questions (parallel sub-agents each
  calling the tool; the web client stores a FIFO list per session), but only
  the head is ever displayed. When the head resolves and the next question
  slides in, it must appear expanded — never inherit the minimized flag. This
  matches the web client's remount-on-question-id behavior.
- Minimize is **local and ephemeral**: no server call, no persistence; screen
  re-entry / process death shows the card expanded again. Drafts (selections,
  other texts, wizard step) are preserved while minimized.

## Description

Pure client-side UI change; no server or `docs/server-api.md` impact.

1. **State** — add `questionCardMinimized: Boolean = false` to
   `ChatViewModel.UiState`.
2. **Intents** — add `onMinimizeQuestion()` and `onReopenQuestion()` to the
   `QuestionWizard` collaborator (`ui/chat/QuestionWizard.kt`), setting the flag
   via `updateState`; expose one-line delegates on `ChatViewModel` like the
   existing question intents.
3. **Reset on head change** — in `QuestionWizard.prunedQuestionDrafts` (which
   already detects a head-question change to reset `questionStep`), also reset
   `questionCardMinimized = false` when the head changed or the queue emptied.
   This covers: new `question.requested`, head resolved revealing the next
   queued question, resolution from another client, and snapshot/seed rebuilds.
4. **Minimize button** — add an `IconButton` (minus-style Material icon,
   `contentDescription = "Minimize question"`, tinted `onSecondaryContainer`)
   at the trailing end of the `QuestionCard` header row in
   `ui/chat/components/Cards.kt`. Keep the 48dp touch target; do not disturb
   the existing Dismiss/Back/Next/Submit row.
5. **Hide when minimized** — in `ChatScreen.kt`'s `bottomBar`, render the
   `QuestionCard` only when `!uiState.questionCardMinimized`. Nothing replaces
   it (no collapsed bar). The composer stays visible (current behavior,
   unchanged).
6. **Tool-row reopen** — the timeline's `AskUserQuestion` tool row
   (`ToolRow` in `ui/chat/components/ToolRows.kt`, fed by
   `ChatItem.ToolActivity`) reopens the card on tap when its tool call id
   matches the pending head question's id (`questionId == toolCallId`, per
   `data/chat/Question.kt` and docs/server-api.md) AND the card is currently
   minimized. In that case the tap reopens instead of toggling the details
   expansion; the row's chevron still expands details. When no matching
   pending question exists (already answered/dismissed, or a historical row),
   the row keeps its current behavior untouched. Thread the pending head
   question id (null when none or when not minimized) and the reopen callback
   down `ChatScreen` → `MessageList` → `ToolRow` alongside the existing
   `onOpenSubagent` parameter.
7. **Tests** — on the shared `ChatViewModelTestBase` harness: minimize hides
   then reopen restores with drafts intact; flag resets on `question.requested`
   for a new head; flag resets when the head resolves and a queued question
   slides in (expanded); dismiss/submit path unaffected.

## Acceptance criteria

- [ ] Minimize icon button in the `QuestionCard` header hides the card; the
      timeline and composer are fully visible.
- [ ] With the card minimized, tapping the pending `AskUserQuestion` tool row
      in the timeline reopens the card with all draft selections/texts/step
      intact.
- [ ] Tapping a resolved (historical) `AskUserQuestion` tool row still toggles
      its details expansion — no regression for non-pending rows.
- [ ] A new head question (fresh `question.requested`, or next queued question
      after the head resolves) always surfaces expanded.
- [ ] `./gradlew :app:testDebugUnitTest` passes, including the new
      `QuestionWizard`/ViewModel tests.
- [ ] Release APK built and published to the file server per AGENTS.md
      ("Distribution").

## Out of scope

- Thin collapsed bar / chip above the composer (considered, rejected by Louis —
  fully hidden was chosen deliberately).
- Persisting the minimized flag across screen re-entry or process death.
- Replacing/disabling the composer while a question is pending (web behavior;
  the app's composer stays enabled today and that is unchanged).
- Question queue-management UI (answer out of order, jump between pending
  questions).
- Any server or `docs/server-api.md` changes — the protocol already carries
  everything needed.

## Notes

- The pending-question lifecycle already lives in the `QuestionWizard`
  collaborator (ticket 072); keep this feature there — `UiState`/`Effect` stay
  in `ChatViewModel`, intents arrive as calls.
- `PendingQuestion.questionId` IS the tool call id (`data/chat/Question.kt`),
  so the tool-row match is a plain string comparison against
  `pendingQuestions.firstOrNull()?.questionId` — no new mapping.
- Symmetry hint: `prunedQuestionDrafts` (`QuestionWizard.kt:182`) is the single
  choke point through which every queue mutation (events, poll, snapshot)
  flows — resetting the flag there covers all sources at once.
- Web client reference for the general UX (collapsed-bar variant, rejected):
  `apps/kimi-web/src/components/chat/QuestionCard.vue` in MoonshotAI/kimi-code.
