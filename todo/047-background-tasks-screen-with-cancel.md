# Background tasks screen with cancel

- **ID:** 047
- **Created:** 2026-07-28
- **Depends on:** 045

## Context

Background sub-agents (`Agent` with `run_in_background: true`) and background
bash commands are tracked by the server's task store, exposed over REST
`/api/v1/sessions/{sid}/tasks` (list/get/`:cancel`) and live `task.started` /
`task.terminated` / `task.notified` WS events (`docs/server-api.md`,
Sub-agents, verified 2026-07-28). The app currently shows nothing of this:
completion `<notification>` messages are deliberately hidden (019), so a
background task's existence, progress and outcome are invisible until the main
agent mentions them.

Product decision (2026-07-28, with Louis): surface them now, as a **tasks
screen** reached from a chat top-bar icon (chosen over a composer widget — a
navigable list belongs on its own screen, consistent with the sub-agent page
choice in 045). Cancelling a runaway background task from the phone is the
primary action; tapping a sub-agent task opens its 045 detail page.

Known constraint (accepted): the REST task id differs from the WS agent id
(`agent-7noib8bb` vs `agent-13`); the live link comes from
`task.started`'s `info.agentId`. After an app restart that link is gone, so a
cold task row cannot open the streaming page — it degrades to status + result
preview instead. Rejected: guessing the link by description matching.

## Description

1. **Data layer.** `TasksRepository` over `ApiClient`:
   `list(sessionId, status: String?)`, `get(sessionId, taskId, withOutput =
   true)` (result preview), `cancel(sessionId, taskId)` — all
   `Result`/`DataError`, snake_case DTOs pinned from the doc's verbatim
   captures. Socket gains `task.started`/`task.terminated`/`task.notified`
   events (`info.taskId`, `info.kind`, `info.status`, `info.agentId`,
   `info.subagentType`, notification fields); the legacy `background.task.*`
   aliases are ignored (duplicates).
2. **State.** A session-scoped task list state (alongside 045's store):
   REST list as the baseline, live `task.*` events upserting on top,
   `task.started` recording the taskId→agentId link for navigation. A refresh
   fires on screen open; no polling loop in v1.
3. **Entry point.** Chat top bar: tasks icon (e.g. `Icons.Outlined.TaskAlt` or
   similar already-available asset) with a running-count badge, visible only
   when the session has ≥1 task; navigates to
   `KimiRoutes.Tasks(sessionId)`. Badge and icon live-update from the task
   state.
4. **Tasks screen.** One row per task: kind icon (sub-agent vs bash),
   description, status chip (`running`/`completed`/`failed`/`cancelled`),
   duration (`created_at`→`completed_at`, ticking while running). Running rows
   get a cancel action behind a confirm dialog → `POST :cancel`; `40904`
   (already finished) maps to a snackbar via the effect channel, not a crash.
   A sub-agent row with a known agentId navigates to the 045 page; without a
   link, the row expands inline with the `with_output` result preview.
   Terminal states never offer cancel.
5. **Empty/loading/error** via the shared `StateViews`.

## Acceptance criteria

- [ ] MockWebServer tests: list/get/cancel DTOs parse the doc's verbatim
      captures; `40904` maps to a distinct, user-messageable `DataError`.
- [ ] Unit tests: task state merges REST baseline with live `task.*` events;
      the taskId→agentId link is recorded from `task.started` and survives a
      list refresh; badge count reflects running tasks only.
- [ ] On-device: launch a background sub-agent ("…run_in_background true…"),
      see the badge appear, open the screen, watch running → completed (or
      cancel it mid-run and see `cancelled`); the main chat is undisturbed.
- [ ] On-device: after killing and reopening the app, the same session's task
      list still loads, completed rows expand to their result preview.
- [ ] `./gradlew :app:assembleDebug` succeeds, unit tests green.

## Out of scope

- Output streaming into task rows (`with_output` on demand only; the web
  client's 1 s polling is deliberately not replicated).
- Foreground sub-agents in the list (they never appear in REST `/tasks` —
  their surface is the 045 page via the `Agent` row).
- Cold task→agent re-linking via the transcript `agents` roster (revisit if
  the degraded rows prove annoying).
- Background bash task output viewing beyond the preview tail; process-level
  controls other than cancel.

## Notes

- Wire reference: `docs/server-api.md` → Sub-agents → REST `/tasks` (verbatim
  task object, error envelopes `40001`/`40401`/`40406`/`40904`, core→wire
  status mapping `killed`→`cancelled`, `timed_out`/`lost`→`failed`) and the
  `task.*` event captures.
- Follow the one-shot-effect convention for snackbars (AGENTS.md: composables
  never call `snackbarHostState` directly).
- 019 keeps hiding `<notification>` splices in the chat — the tasks screen is
  the sanctioned surface for that information; do not resurface the messages.
