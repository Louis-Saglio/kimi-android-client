# Meta tools: lean tuned rows (Skill, plan mode, goal, ReadMediaFile)

- **ID:** 125
- **Created:** 2026-09-01
- **Depends on:** 118

## Context

The last family from the 2026-09-01 design: rare meta tools
(`EnterPlanMode`, `ExitPlanMode`, `CreateGoal`, `GetGoal`, `UpdateGoal`,
`SetGoalBudget`, `Skill`, `ReadMediaFile`). The PO call was explicit —
these fire too rarely to justify bespoke screens, but they should still
read as intentional one-liners instead of JSON dumps. Lean rows + the 118
generic key/value expansion; no new screens, no navigation.

Field decisions per tool (what the ROW shows; everything else stays in the
generic expansion):

- `Skill`: the `skill` name as the preview, `args` appended when present.
- `ReadMediaFile`: image icon (custom path-data), path preview, a
  `crop W×H` badge when `region` is set.
- `ExitPlanMode`: preview "Plan ready for review"; the `options` labels as
  chips inside the expansion.
- `CreateGoal`: the `objective` as the preview; `completionCriterion` stays
  in the expansion.
- `UpdateGoal`: the `status` as a small badge — success color for
  `complete`, `colorScheme.error` for `blocked`, `colorScheme.primary` for
  `active`.
- `SetGoalBudget`: `value`+`unit` as the preview ("30 minutes").
- `GetGoal`, `EnterPlanMode`: no fields worth tuning — default row +
  generic expansion.

## Description

**1. Payloads.** `ToolPayload.Skill(skill, args)`,
`ReadMedia(path, regionWidth?, regionHeight?)` (region parsed only when
both dimensions are present), `ExitPlanMode(optionLabels)`,
`CreateGoal(objective, completionCriterion?)`, `UpdateGoal(status)`,
`SetGoalBudget(value, unit)` on the 119 sealed type + parser tests.

**2. Rows.** Preview tuning lands in `ToolPreview`'s per-tool keys where a
string suffices (Skill, SetGoalBudget); badges/chips that need structure
(UpdateGoal status, ReadMediaFile crop, ExitPlanMode options) read the
parsed payload on the row — same pattern as 119's Edit stat chip.
ReadMediaFile gets the image icon; the rest keep the generic tool icon.

**3. Expansion.** All of these use the 118 generic key/value table;
`ExitPlanMode` adds its option-label chips row above the table. Nothing
navigates away.

**4. Tests.** Payload parsing per tool (incl. half-specified `region` → no
badge); `ToolPreview` tuning (Skill with/without args, budget phrasing);
UpdateGoal badge color mapping; row androidTests (badges render, options
chips in expansion, generic rows unchanged).

## Acceptance criteria

- [x] New tests above pass; existing suite green with no behavior-test
      changes (purely additive test additions; the one edited line is a test
      class doc comment). Device run of the new androidTests blocked by
      TEST_QUOTA_EXCEEDED (2026-09-01) — they compile
      (`:app:assembleDebugAndroidTest` green) but did not execute.
- [ ] Observable on device: a `Skill` row reads as the skill name, an
      `UpdateGoal` row shows its colored status badge, a `ReadMediaFile`
      row with a `region` shows the crop badge, an `ExitPlanMode`
      expansion lists the option labels as chips.
      **NOT device-verified: daily Firebase Test Lab quota exhausted**
      (matrix failed during validation, zero executions — the whole
      `ToolRowTest` class was targeted, so the ticket-124 grep fixture
      correction also still awaits its device re-run).
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes (**done** — also
      `:app:assembleDebugAndroidTest`); release APK publish deferred to
      post-merge per session instructions.
- [x] `docs/conventions.md` notes the meta-row tuning lives in
      `ToolPreview` + payload badges (closing the 118–125 arc).

## Out of scope

- Any interaction beyond expand/collapse (approving a plan from the app,
  editing a goal, viewing the image a `ReadMediaFile` call loaded).
- `AgentSwarm` bespoke UI (its rows already get the description preview and
  per-item sub-agent pages; a swarm summary card is a separate idea, not
  yet ticketed).
- MCP-tool-specific renderers.

## Notes

- After this ticket every built-in tool has either a dedicated UI or a
  tuned row; anything still falling back to the plain generic table is by
  design (unknown/future/MCP tools).
