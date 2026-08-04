# Jobs widget: background-task counter pill + list sheet with elapsed time and cancel

- **ID:** 092
- **Created:** 2026-08-03
- **Depends on:** none

Single vertical slice at Louis's direction (2026-08-03): purely technical
layer-split tickets are forbidden except for refactoring, so the data layer and
the UI ship together; the ~500-line size rule is explicitly overridden, same
call as tickets 085/091.

## Context

Louis drives long-running work from his phone: background sub-agents
(`Agent(run_in_background=true)`) and background bash. Today the app has NO
visibility into them — no REST `/tasks` client, no `task.*` WS event handling;
it only knows a background sub-agent exists via `subagent.spawned`'s
`runInBackground` flag. The web client solved this with a dock of counter pills
(Bash n / Subagents n) opening a task panel with live elapsed times and Stop
buttons. Louis wants the Android equivalent, shaped like the existing Todos
widget (ticket 042): a counter pill pinned above the composer, tap → read-only-ish
bottom sheet.

The `TodosSummaryRow` was explicitly designed for this: it wraps its content so
"future composer widgets (Bash- or subagent-style counters) will share the row"
(`TodosWidget.kt:37`).

Decisions from the PO discussion (2026-08-03, all confirmed by Louis):

- **Job kinds:** background sub-agents (`kind:"subagent"`) + background bash
  (`kind:"bash"`). Question flows (`kind:"tool"`) are excluded — they already
  surface as question cards in chat; listing them as jobs would duplicate UI.
- **Actions:** running rows get a Stop/cancel button
  (`POST .../tasks/{id}:cancel`); tapping a sub-agent row navigates to the
  existing sub-agent page. Rejected alternatives: navigate-only (cancel is
  expected — the web UI has Stop) and view-only.
- **Visibility:** the pill is hidden when zero jobs are running (web dock
  parity; a permanent `Jobs · 0` clutters the composer). Count = running jobs.
- **Sheet content:** status line only — description, kind badge, ticking
  elapsed time, status. Recently finished jobs linger in the list with their
  terminal status. Rejected: output tail (turns the sheet into a mini log
  viewer and needs per-job `with_output` polling — a possible follow-up).
- **Data flow:** event-driven, NO polling — a deliberate divergence from the
  web client's 1 s REST polling (`useTaskPoller.ts`), wasteful on mobile. The
  server pushes `task.started`/`task.terminated` over the session socket; REST
  `GET .../tasks` is only the cold-start sync for tasks spawned before
  subscribe. `task.notified` and the legacy `background.task.started/terminated`
  aliases are ignored (the web client ignores them too — zero references
  upstream).

Wire facts (all verified, `docs/server-api.md`): REST `/tasks` is
envelope-over-200, list supports `?status=running|completed|failed|cancelled`,
terminal tasks stay listed; cancel of a running task → `{"cancelled":true}`,
of a terminal one → `40904`; `task.started`/`task.terminated` carry
`info{taskId, description, status(core vocabulary), startedAt, endedAt?,
stopReason?, kind:"agent"|"process", agentId?, subagentType?}` — `info.agentId`
links the REST task id (`agent-7noib8bb`) to the WS sub-agent id (`agent-13`)
for navigation. Core→wire mapping: status `killed`→`cancelled`,
`timed_out`/`lost`→`failed`; kind `agent`→`subagent`, `process`→`bash`.

## Description

**1. Data layer — REST (`data/chat/`).** `ChatApi` gains
`listTasks(sessionId, status: String?): List<BackgroundTask>` and
`cancelTask(sessionId, taskId)` going through `ApiClient` like every other
call. DTOs in `ChatDtos.kt`; domain `BackgroundTask(id, kind {Subagent, Bash},
description, status {Running, Completed, Failed, Cancelled}, createdAt,
startedAt, completedAt?, agentId?)` next to them or in its own file. Unknown
kind/status values map defensively (dropped with a `Log.w`, never crash).
`ChatRepository` gets thin delegates.

**2. Data layer — WS (`data/chat/SessionSocket.kt` + `ChatEvents.kt`).** Parse
`task.started` / `task.terminated` into new `SessionEvent.TaskStarted(info)` /
`SessionEvent.TaskTerminated(info)` (a shared `TaskInfo` model carrying the
fields above; `agentId` nullable — bash has none). `task.notified`,
`background.task.started`, `background.task.terminated` are NOT parsed (fall
into the existing unknown-type `Log.w` path).

**3. `JobsStore` collaborator (`ui/chat/`, internal, the 065/072 pattern).**
Session-scoped like `SubagentStreamStore` (Hilt singleton keyed by session,
released on session leave). Owns one `List<Job>` per session:

```
Job(taskId, kind, description, status, startedAt, endedAt?, agentId?)
```

Fed by: (a) a cold-start `listTasks(status = null)` fetch on session entry —
unfiltered, so already-terminal jobs linger in the sheet (REST serves terminal
tasks; the running-only pill counts from state); (b) live `TaskStarted` →
upsert; (c) `TaskTerminated` → set terminal status + `endedAt` + map core→wire
vocabulary; (d) `cancelTask` → REST call, optimistic flip to Cancelled, `40904`
treated as already-terminal success (web parity). Terminal jobs linger until
session leave (no eviction policy — lists are tiny). Not a second state source:
updates report to the ViewModel through the constructor state-transform sink,
intents arrive as one-line delegate calls, per AGENTS.md.

**4. ViewModel + screen wiring.** `ChatViewModel` UiState gains `jobs: List<Job>`
(explicit backing field, ticket 084 style) fed by the store; the socket event
funnel routes `TaskStarted`/`TaskTerminated` to the store; cold start (and the
`resync_required` re-sync) triggers the REST fetch. One-shot effect for
cancel failures (snackbar via the effect channel, as always). Navigation to the
sub-agent page reuses the existing route (ticket 083) with `job.agentId`.

**5. UI (`ui/chat/JobsWidget.kt`, mirroring `TodosWidget.kt`).**

- `JobsSummaryRow(jobs, onClick)`: pill in the composer row next to Todos —
  `Jobs · N`, N = `jobs.count { it.status == Running }`, `gone` (not composed)
  when N == 0. Same visual recipe as `TodosSummaryRow`
  (surfaceContainerHigh, 48.dp min height, merged semantics content
  description). Icon: `Icons.Filled.PlayCircle` or similar from
  material-icons-core (check availability; draw a simple glyph otherwise, as
  `TodoStatusIcon` does).
- `JobsSheet(jobs, elapsedNow, onCancel, onOpenSubagent, onDismiss)`:
  `ModalBottomSheet` like `TodosSheet`. Rows: kind badge (small text chip
  `sub-agent` / `bash`), description, status glyph (running = primary ring,
  terminal = muted), elapsed time `mm:ss` (h:mm:ss past an hour) computed from
  `startedAt` → `endedAt ?: now`. Running rows get a trailing Stop
  `IconButton`; tapping a `kind == Subagent` row with non-null `agentId`
  navigates. Sort: running first (oldest first), then terminal (newest first).
- **Elapsed ticker:** while the sheet is visible, a `LaunchedEffect` ticks a
  1 s `now` state (the web client's `taskClock` model) — ticking stops with the
  sheet; the collapsed pill never ticks (count only, no per-job time).

**6. Tests** on the shared harnesses: `JobsStore` unit tests (cold-start seed,
live upsert, terminal mapping `killed`→`cancelled`, `40904` cancel, kind
filtering of `tool`), `SessionSocket` parsing tests for the two new events
(verbatim frames from `docs/server-api.md:1467-1478`), ChatApi MockWebServer
tests (list envelope, cancel 40904), ChatViewModel test on
`ChatViewModelTestBase` (events → UiState.jobs, pill count).

## Acceptance criteria

- [x] `ChatApi.listTasks/cancelTask` MockWebServer tests pass: envelope
      unwrap, `?status=` query, cancel → `{"cancelled":true}`, terminal-task
      cancel → `40904` surfaced as a typed `DataError`.
- [x] `SessionSocket` emits `TaskStarted`/`TaskTerminated` for the verbatim
      doc frames and ignores `task.notified` + both `background.task.*` aliases
      (unknown-type `Log.w`, no crash, no event).
- [x] `JobsStore` tests: REST seed → live `TaskStarted` upsert →
      `TaskTerminated` maps core status to wire (`killed`→`cancelled`,
      `timed_out`→`failed`); `kind:"tool"` entries never appear; cancel is
      optimistic and a `40904` response still ends in a terminal state.
- [x] ChatViewModel test: two `TaskStarted` events → UiState `jobs` has 2
      running; `TaskTerminated` → count drops to 1 and the finished job lingers.
- [x] UI behavior (observable on device / Robo): with zero running jobs no
      pill; with 2 running, `Jobs · 2` appears next to Todos; tap → sheet with
      description, kind badge, ticking elapsed time; Stop cancels (server
      confirms via `task.terminated`); tapping a sub-agent row opens its page.
      (verified by Louis on-device, 2026-08-03)
- [x] Existing suite green with no behavior-test changes.
- [x] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:assembleDebug
      --max-workers=1` and `:app:testDebugUnitTest` pass (VPS build rules);
      release APK published to the file server per AGENTS.md.
- [x] `AGENTS.md` updated (new collaborator + the two new endpoints/events in
      the data-layer conventions); `docs/server-api.md` untouched (no wire
      change).

## Out of scope

- **Output preview / log tail** (`with_output=true`): the follow-up if Louis
  wants bash debugging from the sheet; needs its own UX (expand-in-place vs
  detail view) and polling policy discussion.
- **Foreground sub-agents in the sheet** — they render inline in chat and in
  the existing sub-agent page; the sheet is for background jobs only.
- **`kind:"tool"` entries** (question flows) — already have their cards.
- **Android notifications on job completion** — no local-notification
  infrastructure exists; the main agent's notification turn already announces
  completions in chat.
- **`task.notified` / legacy `background.task.*` handling** — ignored by
  design (web parity).
- **Eviction of terminal jobs mid-session** — lists are tiny; session leave
  clears.

## Notes

- Wire reference: `docs/server-api.md` — REST `/tasks` (1487-1535, task object
  verbatim 1525-1535), `task.started`/`task.terminated` payloads (1467-1485),
  background lifecycle sequence (1424-1466), id namespaces (1283-1293).
- Web-client reference (read-only): `/tmp/kimi-code-web/apps/kimi-web` —
  `src/composables/client/useTaskPoller.ts` (polling we deliberately do NOT
  copy), `src/components/chat/TasksPane.vue` + `ChatDock.vue` (pills/Stop UX),
  `src/lib/taskMerge.ts` (id linking), `useWorkspaceState.ts:2070-2103`
  (cancel + 40904 handling).
- Project conventions to follow: collaborator pattern (AGENTS.md "Chat feature
  collaborators"), `ApiClient` for all REST, `Result`+`DataError` with
  `toUserMessage()`, explicit backing fields (084), one-shot effects via the
  Channel, `ui/common/Formatters` for elapsed-time formatting if a suitable
  helper exists (add one there if not — shared, not one-off).
- Elapsed-time source of truth: `startedAt` from REST (`started_at`) or
  `task.started.info.startedAt` (epoch millis — mind the unit mismatch with
  REST's ISO strings).
