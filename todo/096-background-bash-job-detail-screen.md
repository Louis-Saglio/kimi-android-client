# Job detail screen for background bash (tap row → info + live output + Stop)

- **ID:** 096
- **Created:** 2026-08-05
- **Depends on:** none (related: 095 row descriptions, 097 timeout budget)

## Context

A background `Bash` call's tool result is only a stub (`task_id: bash-…
status: running …`): the command's real output lives in the server's
background-task store. Today tapping the row expands that stub inline —
useless for watching a build from the phone. Louis wants the row to open a
dedicated screen with the job's information and its output (2026-08-05).

Wire facts (all verified live on 0.32.0, 2026-08-05 — spike sessions
`bg-bash-output-spike` and the timeout spike of the same day):

- The background Bash tool result is immediate plain text starting
  `task_id: bash-xxxxxxxx\npid: …\ndescription: …\nstatus: running\n…`.
  History carries the same text in the `tool_result` part, so the row→job
  link works for live and re-entered sessions alike. (Background **Agent**
  results also start with `task_id:` but an `agent-…` id — those rows already
  navigate via `subagentId` and must not be re-linked.)
- `GET .../tasks/{id}?with_output=true` serves everything the screen needs:
  `description`, `status`, `started_at`/`completed_at`, `command` (bash-kind
  tasks carry it on the wire — our `TaskDto` doesn't parse it yet), and
  `output_preview` (tail, default cap 32768 bytes, `output_bytes` override).
  It works **while running** — the preview grows between polls.
- **No `tool.progress` frames exist for background bash** (zero observed
  across two full runs): polling this REST endpoint is the only live-output
  channel. Ticket 092's no-polling doctrine was for the jobs *list*; here the
  poll is scoped to screen visibility.

Decisions (PO discussion 2026-08-05, confirmed by Louis):

- **Interaction:** linked rows never expand — tap navigates (sub-agent-row
  precedent, ticket 045). Entry from the chat row **and** the jobs sheet's
  bash rows. Unlinkable rows (no `task_id: bash-` in the output) keep today's
  inline expand.
- **Stop** on the screen while running (app-bar action) — killing a runaway
  job is the natural reaction while watching its output; cancel reuses the
  existing endpoint with 40904-as-success semantics.
- **Poll ~2 s** while the job runs and the screen is visible; no polling when
  terminal or off-screen.
- Rejected: inline expand of live output (recycled rows + polling don't mix,
  and a long log tunnels the timeline); output tail in the jobs sheet (092
  already rejected the mini-log-viewer sheet); a viewer with
  `output_bytes` controls (deferred — keep the slice under the size rule).

## Description

**1. Row→job link.** `ChatItem.ToolActivity` gains `bashTaskId: String?`.
Set at mapping time (both paths: live tool result in `ChatTimeline`, history
`tool_result` parts in `ChatMessageMapper`): when the tool is `Bash` and the
output's first line matches `task_id:\s*(\S+)` with a `bash-`-prefixed id.
The `bash-` prefix requirement is what keeps background Agent rows untouched.

**2. Row + sheet behavior.** A `bashTaskId` row never expands: tap navigates
to the job screen; trailing chevron-right affordance with its own tap target
and content description ("Open job"), mirroring the sub-agent row. In
`JobsSheet`, `kind == Bash` rows become tappable and navigate the same way
(sub-agent rows already navigate).

**3. REST.** `ChatApi.getTask(sessionId, taskId, withOutput = true)` through
`ApiClient` as always. `TaskDto` learns `command: String?`,
`outputPreview: String?`, `outputBytes: Int?`. `40406` surfaces as a typed
`DataError`; `ChatRepository` gets a thin delegate.

**4. Route, ViewModel, screen** (sub-agent page precedent — ticket 083
type-safe route, own `JobDetailViewModel` like `SubagentViewModel`):

- Route `JobDetail(sessionId, taskId)`; navigation from `ChatScreen` (row) and
  the jobs sheet.
- VM state: loading / ready (description, status, startedAt, completedAt,
  command, outputPreview) / error (typed `DataError` → `toUserMessage()`).
  Fetch on entry; poll every 2 s while `status == Running` and the screen is
  active (`whileSubscribed`/lifecycle-tied); one final state on terminal; a
  1 s elapsed ticker while running. Stop intent → repository cancel: 40904 =
  already-terminal success, other failures → snackbar via the effect channel.
  The WS `task.terminated` reconciles pill/sheet; the screen's own poll sees
  the terminal status.
- Layout (M3): `TopAppBar` with back, title = description (1 line, ellipsis),
  `Stop` text action in `colorScheme.error` while running. Body: status line
  (status glyph + label + elapsed `mm:ss`, `h:mm:ss` past an hour — reuse
  `ui/common/Formatters` if a helper fits); a *Command* section (monospace
  block on `surfaceVariant`, `MaterialTheme.shapes.small`); an *Output*
  section (monospace, internal scroll, pinned to the tail while running — the
  `ToolDetailBlock` recipe; "No output yet" placeholder when blank). Content
  width capped (~840 dp) and centered on large screens; section titles marked
  `semantics { heading() }`; status glyph has a content description.
- Error state (task unknown / fetch failure): the existing `StateViews`
  pattern with back navigation.

**5. Docs.** Record in `docs/server-api.md`: the background Bash tool-result
shape (verbatim, from the 2026-08-05 capture), the `bash-` task-id namespace,
and the `command` field on bash-kind task objects.

**6. Tests.** Link parsing (verbatim result text; an Agent `task_id: agent-…`
output must NOT link; no match → row stays expandable); `ChatApi` MockWebServer
(envelope unwrap, `with_output` query, `output_preview`/`command` parsing,
40406 typed); ViewModel (entry fetch; running → polls; terminal → stops;
cancel 40904 tolerated, other error → effect); `ToolRowTest` androidTest
(linked row doesn't expand, tap + chevron callbacks); jobs-sheet bash-row tap.

## Acceptance criteria

- [ ] New unit/android tests above pass; existing suite green with no
      behavior-test changes.
- [ ] Observable on device: tapping a background Bash row opens the screen —
      description, status, ticking elapsed, command, output tail; output grows
      ~every 2 s while running and pins to the tail; on completion polling
      stops and the final output is shown.
- [ ] Stop on a running job cancels it (server confirms via
      `task.terminated`; the composer pill count drops).
- [ ] The jobs sheet's bash rows open the same screen; a row whose output
      lacks `task_id: bash-` still expands inline as before.
- [ ] Re-entering the session (history path) — the row still navigates.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes (VPS build
      rules); release APK published to the file server per AGENTS.md.
- [ ] `docs/server-api.md` updated (bash result shape, `bash-` namespace,
      `command` field); `docs/conventions.md`/`AGENTS.md` touched if a new
      collaborator or route pattern lands.

## Out of scope

- **Timeout budget UI + "timed out" terminal status** — ticket 097.
- **`output_bytes` control / full-output view** — the 32 KB tail only.
- **Pull-to-refresh** — polling covers liveness while running; terminal
  output is final.
- **Foreground Bash rows** — unchanged (inline expand; they stream via
  `tool.progress`).
- **Background Agent rows** — the sub-agent page already covers them.
- **Notifications on job completion.**

## Notes

- Verbatim background Bash tool result (0.32.0, 2026-08-05):

  ```
  task_id: bash-2q5gu61e
  pid: 502439
  description: tick counter loop (120)
  status: running
  automatic_notification: true
  next_step: …
  ```

- Verbatim bash task object: `{"id":"bash-xbje00tt","kind":"bash",
  "description":"tick counter loop","status":"completed",
  "created_at":"…","started_at":"…","completed_at":"…",
  "command":"for i in …"}`, plus `output_preview`/`output_bytes` with
  `with_output=true`.
- Wire reference: `docs/server-api.md` REST `/tasks` (get-with-output behaves
  identically for running bash tasks; verified 2026-08-05).
- Keep the output viewer minimal (single monospace tail block) to stay under
  the ~500-line budget; the elaborate viewer belongs to a later ticket if the
  tail proves limiting.
