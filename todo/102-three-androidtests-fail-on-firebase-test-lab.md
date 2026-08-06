# Three androidTests fail on Firebase Test Lab since before ticket 098

- **ID:** 102
- **Created:** 2026-08-06
- **Depends on:** none

## Context

While verifying ticket 098 on Firebase Test Lab (MediumPhone.arm, API 36),
three instrumented tests failed — and fail identically on the base commit
`f04edfd` (verified 2026-08-06 by a stash + base build + Test Lab run), so
they predate ticket 098 and are unrelated to it:

- `JobStatusLineTest.runningWithAnExplicitBudgetShowsElapsedOverBudgetAndTheFraction`
  — expects `ProgressBarRangeInfo(current=0.25, …)` but the code computes
  fraction 0.5 for elapsed 15 s / budget 30 s
  (`JobDetailScreen.kt`, `elapsedDuration / budgetSeconds`). The 0.25
  expectation has been there since the test's creation (ticket 097, commit
  8c3270b) — either the expectation is simply wrong (should be 0.5f) or an M3
  semantics change altered what `LinearProgressIndicator` reports.
- `JobStatusLineTest.runningWithoutBudgetShowsPlainElapsedAndNoProgressBar`
  — asserts ZERO `ProgressBarRangeInfo` nodes; likely now counts the running
  glyph's `CircularProgressIndicator` (M3 indeterminate indicators gained
  `ProgressBarRangeInfo` semantics in a later version — check the M3 bump
  history and relax the matcher to the determinate bar).
- `QuestionCardTest.readOnlyMultiCardShowsCheckedDisabledBoxes`
  — "Failed to assert count of nodes" (not investigated).

No Firebase run of the androidTest suite is recorded in tickets 089/090/097,
so these may have been broken since their creation or since the last M3
upgrade.

## Description

Make the three tests green again on Firebase Test Lab. Start by pulling the
per-test logcats from the 2026-08-06 base run
(`gs://test-lab-xmt04ty3b13c4-hb4aufbkj2nnm/2026-08-06_23:38:54.079187_ZRsm/MediumPhone.arm-36-en-portrait/`):
the JobStatusLine fraction expectation is very likely just `0.25f` → `0.5f`;
the other two need the actual node trees from the failures. If an M3
semantics change is the cause, prefer updating the matchers over weakening
the assertions.

## Acceptance criteria

- [ ] The three named tests pass on Firebase Test Lab (MediumPhone.arm,
      API 36).
- [ ] The rest of the androidTest suite keeps passing (no regressions).

## Out of scope

- Adding CI for the androidTest suite (the suite only runs on demand today).
