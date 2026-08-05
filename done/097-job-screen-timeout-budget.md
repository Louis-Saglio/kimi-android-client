# Job screen: timeout budget while running, "timed out" terminal status

- **ID:** 097
- **Created:** 2026-08-05
- **Depends on:** 096

## Context

Background Bash calls carry a `timeout` (seconds): the server kills the
process when the budget expires. Watching a job on the phone, "elapsed 2:34"
doesn't answer the real question — *when will the server kill this?* Louis
asked for the timeout integrated into the job screen (2026-08-05).

Wire facts (spiked live on 0.32.0, 2026-08-05 — two throwaway background
commands, one completing, one with `timeout: 5`):

- **REST `/tasks/{id}` never exposes the timeout** — not even when the call
  set it explicitly. The only wire location of the value is the **tool call
  input** (`timeout`, seconds), present in live args and in history `tool_use`
  parts alike.
- **A timed-out job collapses to `status:"failed"` on REST** —
  indistinguishable from a crash (the 5 s task: `failed`,
  `completed_at - started_at` = 5.003 s). Only the live WS path keeps the
  truth: `task.terminated` core status `timed_out` (and a `task.timed_out`
  notification, severity warning). `JobsStore` currently maps `timed_out` →
  Failed and drops that information.
- Harness contract (Bash tool schema): unset `timeout` ⇒ default **600 s**
  for background commands; `disable_timeout: true` ⇒ no kill. The server
  enforced the explicit 5 s timeout exactly (killed at +5.003 s).

Decisions (PO discussion 2026-08-05, confirmed by Louis):

- **Show the effective budget**: explicit timeout when set, else the 600 s
  default (labeled as default); `disable_timeout` ⇒ no budget UI. Accepted
  cost: the app hardcodes the harness's 600 s default — recorded in
  `docs/server-api.md` so the nightly drift check watches it.
- **Terminal distinction**: "Timed out after 0:05" instead of "Failed" when
  known — i.e. for jobs whose `task.terminated` arrived live this session.
  REST-seeded jobs stay "Failed" (the collapse is unrecoverable — inferring
  timeout from `completed_at - started_at` was considered and rejected as
  hacky).
- Separate ticket from 096 to keep both under the size rule.

## Description

**1. Parse at mapping time (096's link sites).** The Bash `tool_use` input
adds two derived fields next to `bashTaskId`: `timeoutSeconds: Int?` (explicit
value) and `timeoutDisabled: Boolean` (`disable_timeout == true`). Both live
and history paths. Effective budget rule:
`timeoutSeconds ?: if (timeoutDisabled) null else 600` — `null` meaning "no
budget UI".

**2. Plumb to the screen.** The effective budget (seconds, nullable) travels
as an optional nav argument on the `JobDetail` route: the chat row passes its
own parsed value; the jobs sheet resolves it from the matching timeline row
(same taskId). Unknown ⇒ the screen shows plain elapsed (096 behavior).

**3. Screen, while running.** Status line becomes `Running · <elapsed> /
<budget>` (same `mm:ss`/`h:mm:ss` formatting), with a determinate
`LinearProgressIndicator` (`progress = elapsed / budget`, coerced to 0..1 —
a job can outlive a stale default). A default-sourced budget is labeled, e.g.
`Running · 2:34 / 10:00 · default`. Respect reduced motion: the bar is
state-driven (1 s ticker), no infinite animation.

**4. "Timed out" terminal status.** `Job` retains that the terminal cause was
a timeout (keep the core status or a `timedOut` flag at the `JobsStore`
mapping point — the core→wire status mapping otherwise stays). The job screen
and the jobs sheet render `Timed out after <budget-or-elapsed>` (error color)
instead of `Failed` when the flag is set; REST-seeded jobs render `Failed` as
today (documented limitation).

**5. Docs.** `docs/server-api.md`: REST `/tasks` does not expose `timeout`;
`timed_out` collapses to `failed` on REST (live WS keeps the core status);
the 600 s background default and `disable_timeout` semantics, marked as the
harness/tool-schema contract (not observable on this wire) so drift check
flags it if the schema changes.

**6. Tests.** Input parsing (explicit / unset / `disable_timeout`);
effective-budget rule; `JobsStore` timed-out retention (and unchanged
`killed`→Cancelled mapping); screen budget text + progress fraction; terminal
"Timed out" vs REST-seeded "Failed".

## Acceptance criteria

- [ ] New unit tests above pass; existing suite green with no behavior-test
      changes beyond the new status label.
- [ ] Observable on device: a background job with `timeout: 30` shows
      `… / 0:30` with a filling progress bar while running; one without an
      explicit timeout shows the budget marked as default; a job started with
      `disable_timeout` shows plain elapsed.
- [ ] A job killed by its timeout shows "Timed out" (not "Failed") on the
      screen and in the jobs sheet within the same session; after leaving and
      re-entering the session it reads "Failed" (REST collapse — documented).
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes (VPS build
      rules); release APK published to the file server per AGENTS.md.
- [ ] `docs/server-api.md` updated per Description §5.

## Out of scope

- **Changing or extending a running job's timeout** — no such endpoint.
- **Countdown/notification when a job nears its budget.**
- **Foreground Bash timeout display** — foreground rows don't navigate to the
  job screen.
- **Recovering timed-out status for REST-seeded jobs** — impossible on this
  wire (REST collapses to `failed`); revisit only if the server exposes it.

## Notes

- Spike evidence (2026-08-05, session `Job output`): `bash-w662jmuf` with
  `timeout: 5` → REST `{… "status":"failed", "started_at":"15:38:31.580Z",
  "completed_at":"15:38:36.584Z"}` and no timeout field; `bash-mm1xr92b`
  (`timeout: 120`, completed) likewise carries no timeout field. The
  `task.timed_out` notification was observed live (severity warning).
- The 096 screen already owns the elapsed ticker and the status line; this
  ticket extends both rather than adding new surfaces.
