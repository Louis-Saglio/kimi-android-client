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

- [ ] New tests above pass; existing suite green with no behavior-test
      changes.
- [ ] A live `CronCreate` result capture is recorded in this ticket's
      Notes (or docs/server-api.md) and drove the parse-or-verbatim
      decision; observable on device accordingly: structured →
      humanSchedule + local next-fire line; prose → verbatim block.
- [ ] Observable on device: tapping the chevron on a `TaskOutput`/
      `TaskStop` row opens the job screen for that task (or its error
      state for an unresolvable id).
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

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
