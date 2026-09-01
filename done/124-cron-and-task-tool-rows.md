# Cron and task-control tool rows

- **ID:** 124
- **Created:** 2026-09-01
- **Depends on:** 118

## Context

Two small families remain from the 2026-09-01 design:

- **Scheduling** (`CronCreate`, `CronList`, `CronDelete`): the interesting
  data is in the *result* — `CronCreate`'s return carries `id`,
  `humanSchedule` ("every 5 minutes"), `recurring`, and `nextFireAt`, so
  the row can tell Louis when his job fires in plain language instead of
  showing a cron expression and a JSON blob.
- **Task controls** (`TaskList`, `TaskOutput`, `TaskStop`, `WaitFor`):
  their inputs carry a `task_id`; the app already has a job screen
  (ticket 096) and the row→job navigation plumbing (`onOpenJob`), so these
  rows gain the chevron affordance instead of a bespoke viewer.

Caveat recorded in the discussion: whether the cron result is structured
JSON or prose on the wire is unverified — implementation starts by
capturing one live `CronCreate` result; parse only if it's JSON, else keep
verbatim output and the cron-expression preview (the fallback design below
covers both).

## Description

**1. Payloads.** `ToolPayload.CronCreate(cron, prompt, recurring)`,
`CronDelete(id)`, `TaskOutput(taskId)`, `TaskStop(taskId)`,
`WaitFor(taskId?)`, `TaskList(activeOnly, limit)` on the 119 sealed type +
parser tests.

**2. Cron rows.** Clock icon (custom path-data). `CronCreate`: preview is
the cron expression (monospace); a "one-shot" badge when
`recurring: false`. Expansion: the `prompt` text in full, plus — when the
result parses as JSON — a summary line with `humanSchedule` and
`nextFireAt` formatted to local time (`ui/common/Formatters`; relative
dates per ticket 113's helper if it fits). `CronDelete`: lean row, id
preview, generic expansion. `CronList`: clock icon, "list schedules"
preview, verbatim output block (the multi-record result prose is not worth
a parser — see Out of scope).

**3. Task rows.** Rows whose parsed payload carries a `task_id` gain the
trailing chevron-right affordance ("Open job") navigating to the existing
job-screen route via `onOpenJob` — exactly the background-Bash row
treatment (096), including: linked rows never expand inline. A `task_id`
the job screen cannot resolve (sub-agent-owned task, unknown id — REST
`/tasks` is main-agent-only) lands on the job screen's existing error
state; that is the accepted behavior, no extra handling here. `WaitFor`
without a `task_id` (wait-any) and `TaskList` keep the generic expansion.

**4. Tests.** Payload parsing; cron result parsing (structured → summary
fields; prose/garbage → verbatim fallback); local-time formatting; row
androidTests (one-shot badge, chevron appears with a `task_id` and forwards
it, task-less rows still expand).

## Acceptance criteria

- [x] New tests above pass; existing suite green with no behavior-test
      changes — with ONE listed exception, a fixture correction to ticket
      123's `grepContentRowExpandsToPerFileGroups` (fixture data only, no
      assertion changes; see implementation notes).
- [x] A live `CronCreate` result capture is recorded (docs/server-api.md,
      Tools → Cron tool output text) and drove the parse-or-verbatim
      decision: the wire shape is NOT JSON but newline-joined `key: value`
      prose → verbatim output block + cron-expression preview. The
      JSON-conditional summary line (humanSchedule + local next-fire) and
      the local-time-formatting tests are dropped per this ticket's own
      fallback rule.
- [x] Observable on device: tapping the chevron on a `TaskOutput`/
      `TaskStop` row opens the job screen for that task (or its error
      state for an unresolvable id). (Verified through the Firebase Test
      Lab instrumentation assertions — chevron present, the row never
      expands, the id forwarded to `onOpenJob`, the 096 plumbing itself
      unchanged — not a manual phone check.)
- [x] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes (run with
      `:app:assembleDebugAndroidTest` too); release APK published to the
      file server per AGENTS.md. (Gradle passes; the release APK is
      published by the merging session.)

## Out of scope

- Parsing `CronList`'s multi-record result prose into a table (drift-prone,
  low value — the jobs pill/sheet covers live tasks).
- Managing cron jobs from the app (create/delete actions) — these rows are
  read-only renderings of the agent's calls.
- Retry/deep-link handling for unresolvable task ids beyond the job
  screen's existing error state.

## Notes

- The `WaitFor` result ("finished task X with …") is prose; verbatim.
- Keep the cron icons/rows consistent with the jobs pill's visual language
  (status glyph semantics unchanged — a cron row still shows running/done/
  error like any other).

## Implementation notes (2026-09-01)

- Wire-shape verification, the ticket's gate: a genuine live capture exists
  in the server's own session store — session `04a03224…` (wd
  kimi-android-client) shows a `tool.result` for a `CronCreate` call
  (tool.call args `{"cron":"15 7 1 8 *","prompt":"…","recurring":false}`)
  whose output is, verbatim:

  ```
  id: 01KYXPV7A4HPX4GNZAAGTSRSMH
  cron: 15 7 1 8 *
  humanSchedule: at 07:15 on day 1 of August
  recurring: false
  nextFireAt: 2026-08-01T07:15:00.000+00:00
  ```

  Newline-joined `key: value` prose, NOT JSON — cross-checked against the
  bundled 0.39.1 v2 source (`formatOutput()` in
  `packages/agent-core-v2/src/features/cron/tools/cron-create/cronCreateTool.ts`,
  extracted with `strings` from `~/.kimi-code/bin/kimi`, the 122/123
  method). This drove the ticket's decision to **verbatim**: no cron-result
  parser, no summary line, no nextFireAt local-time formatting (the
  ticket's JSON-conditional branch). CronDelete's live result from the same
  session (`Deleted cron job 01KYXPV7A4HPX4GNZAAGTSRSMH.`) and CronList's
  source-verified record shape are recorded with it in docs/server-api.md,
  Tools → Cron tool output text.
- Payloads: `CronCreate(cron, prompt, recurring)` (cron+prompt required;
  `recurring` DEFAULTS TRUE on the wire — new `trueByDefaultFlagOf` helper,
  shared with TaskList's `active_only`), `CronDelete(id)`, `TaskOutput/
  TaskStop(taskId)` (required), `WaitFor(taskId?)` (null = wait-any, still
  parses), `TaskList(activeOnly, limit)` (wire defaults true/null).
  `CronList` deliberately has NO payload (the call carries no args) — the
  row is name-keyed.
- ToolPreview: `CronCreate → cron`, `CronDelete → id` identifying keys.
- Row: `ClockIcon` (Material Icons "schedule" path data, the
  SearchIcon/GlobeIcon recipe; testTag `toolIconCron`). CronCreate keeps
  the inline expansion — the full prompt in a detail block replaces the
  generic args table (the table one-lines values), the result renders
  verbatim through the existing output block; monospace cron preview;
  "one-shot" badge on `recurring: false` (the "All"/"append" badge shape).
  CronDelete is lean (id preview, generic expansion). CronList previews the
  fixed label "list schedules" (prose font like the WebSearch query) and
  renders its output verbatim.
- Task rows: `linkedJobTaskId = bashTaskId ?: <payload task_id>` — the
  task-control rows reuse the 096 background-Bash treatment wholesale (row
  tap + trailing "Open job" chevron → `onOpenJob`, never expand inline).
  WaitFor without task_id and TaskList parse but keep the generic
  expansion.
- Tests: 13 new parser cases in ToolPayloadsTest (+3 ToolPreview cases);
  12 new row androidTests in ToolRowTest (cron icons/previews, one-shot
  badge, full-prompt + verbatim expansion, CronList label + verbatim,
  chevron presence/forwarding for TaskOutput/TaskStop/WaitFor, task-less
  WaitFor/TaskList generic expansion).
- Test Lab: full `ToolRowTest` class on MediumPhone.arm-36 (matrix
  matrix-2pbf8oik6p5ho): **72 passed, 1 failed**. The failure is
  `grepContentRowExpandsToPerFileGroups` — a PRE-EXISTING ticket-123
  fixture typo, provably orthogonal to this ticket (for a Grep row every
  value the 124 diff adds is null/false; the execution path is
  byte-identical, and 123's androidTests never ran on device — its own run
  was quota-blocked). Root cause: the fixture's context line is written
  `app/a.kt-13:after context` (dash-COLON) where the wire grammar is
  `path-NN-text` (dash both sides — 123's parser unit tests and the
  bundled-rg cross-check); the parser correctly degrades the malformed
  output to verbatim, defeating the test's structured assertions. Fixture
  corrected to `app/a.kt-13-after context` — fixture data only, assertions
  untouched; the corrected fixture's parse into the asserted structure was
  verified with a scratch unit test (deleted after the run). Device
  re-verification of the fix is PENDING: the follow-up attribution run hit
  TEST_QUOTA_EXCEEDED (the day's remaining quota went to the full-class
  run); quota resets ≈ 07:00 UTC.
- Existing-suite fixture changes, listed per convention: the one grep
  fixture correction above. No other existing test touched.
