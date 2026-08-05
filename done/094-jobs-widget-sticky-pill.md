# Jobs widget: sticky pill — stay visible with terminal jobs, running/total label

- **ID:** 094
- **Created:** 2026-08-03
- **Depends on:** 092

## Context

Louis tested the 092 jobs widget on-device and found the hide-when-zero-running
behavior wrong: when the last background job completes, the pill vanishes and
with it the only entry point to the job list (the sheet lingers terminal jobs,
but is unreachable once the pill is gone). This reverses 092's "hide when zero"
decision (recorded in `done/092-jobs-widget-background-tasks.md` Context).

Decisions from the PO discussion (2026-08-03, confirmed by Louis):

- **Visibility:** the pill shows whenever the jobs list is non-empty — running
  or not. It stays after the last job completes AND across session re-entries:
  the server keeps terminal tasks listed and `JobsStore` re-seeds from an
  unfiltered REST `listTasks` on every entry, so persistence across visits
  falls out of the existing seed with zero extra logic. Rejected: "this visit
  only" (needs a seen-this-visit flag + filtering the REST seed to running —
  more code for a marginal cleanliness win) and "persist + clear-seen" (most
  logic, overkill).
- **Label:** `Jobs · finished/total` (finished = completed | cancelled |
  failed), the Todos `done/total` polarity — e.g. `Jobs · 2/4` with 2 finished
  of 4. **Amended 2026-08-05 after Louis's on-device test:** the merged
  candidate briefly shipped `running/total` (the original discussion pick);
  Louis found `Jobs · 0/4` for all-done backwards — the count should grow as
  work completes, exactly like Todos. Rejected earlier: running-only
  (`Jobs · 0` on a visible pill reads dead) and total-only (hides whether
  anything is actually running).

Accepted consequence: the pill is effectively permanent for any session that
ever ran a background task; the sheet doubles as the session's job history.

## Description

- `ChatScreen.kt:377`: `jobsVisible` becomes `uiState.jobs.isNotEmpty()` (was
  `any { it.isRunning }`); update the `ChatScreen.kt:374-375` comment.
- `JobsSummaryRow` (`ui/chat/JobsWidget.kt`): label becomes
  `Jobs · $finished/${jobs.size}` (finished = not-running: completed,
  cancelled, failed); semantics contentDescription follows
  ("Jobs, $finished of ${jobs.size} finished, tap to view"). Rewrite the
  `JobsWidget.kt:46-49` doc comment, which currently justifies hide-when-zero
  as "web dock parity" — that rationale is reversed; note the divergence from
  the web dock (whose pills vanish at zero) as deliberate.
- Tests: update/extend the jobs assertions for the new count semantics
  (finished/total label inputs: 2 running of 2 → 0/2, both terminal → 2/2).
  The visibility gate lives in ChatScreen (no unit coverage
  today); cover the count semantics where the existing jobs tests live.
- Conventions doc: the jobs-widget behavior is described in the collaborators
  conventions (092 added it to `AGENTS.md`, since slimmed into
  `docs/conventions.md` on main) — update the visibility/label description
  there.

## Acceptance criteria

- [x] With 2 running jobs the pill reads `Jobs · 0/2`; after both complete it
      still shows, reading `Jobs · 2/2`, and the sheet still opens with the
      terminal rows (observable on device — the UiState semantics it consumes,
      terminal jobs lingering while the running count drops, are covered by
      `ChatViewModelTest.task events feed the jobs list…`).
      (verified by Louis on-device, 2026-08-05)
- [x] Re-entering a session whose background tasks all finished still shows
      the pill (REST seed serves terminal tasks) — observable on device.
      (verified by Louis on-device, 2026-08-05)
- [x] A session that never ran background tasks shows no pill (regression
      guard). (verified by Louis on-device, 2026-08-05)
- [x] Existing suite green with no behavior-test changes beyond the new
      semantics (no test changes at all — the pre-existing
      `ChatViewModelTest.task events feed the jobs list…` already asserts the
      UiState semantics the sticky pill consumes).
- [x] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes (VPS rules);
      release APK built and published per AGENTS.md with the worktree-branch
      file name. (Suite note: the latent 092 flake surfaced during verification —
      `a resync_required ack re-syncs from a fresh snapshot` raced the jobs REST
      seed that legitimately trails the resync snapshot fetch
      (`ChatViewModel.resyncFromSnapshot`). Fixed in this branch: the test now
      drains trailing requests and asserts only `/tasks` may follow, keeping the
      048 `/status`+`/messages` regression guard.)

## Out of scope

- **Any eviction / clear-seen / age-based rule for terminal jobs** — explicit
  acceptance of permanence (see Context); revisit only if the sheet grows
  unwieldy in practice.
- **Sheet content changes** — rows, cancel, navigation unchanged from 092.
- **Todos widget** — untouched.

## Notes

- 092 implementation references: gate at `ui/chat/ChatScreen.kt:377`, pill at
  `ui/chat/JobsWidget.kt:52` (`JobsSummaryRow`), stale comments at
  `JobsWidget.kt:46-49` and `ChatScreen.kt:374-375`, job model `isRunning` in
  `ui/chat/JobsStore.kt`.
- Built in the worktree `agent/jobs-widget-sticky` (Louis's request); APK name
  per AGENTS.md branch rule:
  `kimi-android-client-agent-jobs-widget-sticky.apk`.
