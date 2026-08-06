# Sub-agent page: background-bash rows open a (store-backed) job screen

- **ID:** 098
- **Created:** 2026-08-06
- **Depends on:** none

## Context

On the sub-agent page, tapping a background `Bash` tool row expands it inline
instead of opening the background job screen — the behavior the main chat has
since ticket 096. Louis confirmed the row's own output carries the link data
(`task_id: bash-…` on the first line, visible in the expanded details).

Root cause (structural, not a missing line): `ChatItem.ToolActivity` link/budget
fields (`bashTaskId`, `subagentId`, `timeoutSeconds`, `timeoutDisabled`) are
derived at **each row-construction site independently**. Four sites exist in
`ChatTimeline.kt`: the message-history flatten result branch, the live
`ToolCallResult` heal (`linkBashTaskFromResult`/`linkSubagentFromResult`), the
transcript-turn flatten, and `completeOrphanResults`. Ticket 096/097 added the
derivation to the first two; the transcript-turn flatten (added earlier, ticket
046) was left deriving nothing — so every transcript-backed row (the sub-agent
page's entire committed history, and any live row after the terminal reconcile
refetch in `SubagentViewModel.maybeReconcile`) loses its links. The duplication
itself is the bug: any new or forgotten path silently drops link fields. The
same gap also strips `subagentId` (nested `Agent` rows in a cold-opened
sub-agent page are non-tappable) and the timeout budget fields.

A second, protocol-level constraint shapes the job screen half: REST `/tasks`
resolves only the **main** agent's task service — sub-agent-owned tasks are
live-only (`docs/server-api.md` "Background lifecycle": never listed, fetched,
or cancellable; `:cancel` answers `40406`). There is no output channel for them
either (no `tool.progress` for background bash; `/files` is attachments-only).
So pointing the existing REST-backed `JobDetailViewModel` at a sub-agent-owned
task id would land on a full-screen 40406 error.

Decision (discussed with Louis, option A): one ticket that (1) fixes the root
cause by making link derivation a single choke point every row path must pass
through, (2) wires the navigation, and (3) gives `JobDetailViewModel` a
store-backed data mode for sub-agent-owned tasks — status/lifecycle from
`SubagentStreamStore`'s live `task.*` frames plus route-seeded row data; no
output tail and no Stop in that mode (no channel exists).

Rejected alternatives:

- **Quick patch** (add `bashTaskIdOf(...)` to the transcript flatten only):
  leaves the four-site duplication that caused the bug, and skips `subagentId`/
  timeout. Explicitly rejected — Louis asked for the robust long-term fix.
- **Spike-first ticket (option B)**: the only open question (transcript turn
  origins of kind `task`) affects only cold-open status healing — deferred to
  ticket 099, not needed here.
- **Keep inline expansion (option C)**: does not deliver the requested UX.
- **Server change**: the server is the upstream `kimi` binary, not ours.

## Description

### 1. One enrichment choke point for tool rows (root cause)

Introduce a single pure function that derives every output/input-computable
field of a `ChatItem.ToolActivity` — `bashTaskId` (`bashTaskIdOf`), `subagentId`
(the `agent_id:` result marker), `timeoutSeconds`/`timeoutDisabled`
(`bashTimeoutOf`) — with first-writer-wins semantics (never overwrite an
already-set field, so live/registry-learned links survive). Route **all four**
construction/update sites in `ChatTimeline.kt` through it: the history flatten
result branch, the transcript-turn flatten, the live `ToolCallResult` handling
(fold `linkBashTaskFromResult` and the `subagentId` half of
`linkSubagentFromResult` into the one call), and `completeOrphanResults`. After
this change no row path can forget a derivation, and a transcript refetch can no
longer strip a live-learned `bashTaskId`. The `subagentLinks` registry and
`reapplySubagentLinks` stay unchanged (spawned/roster links are not derivable
from result text).

### 2. Navigation wiring

- `SubagentScreen` passes `onOpenJob` into `MessageList`; the `Subagent`
  destination in `KimiNavHost` navigates to the `JobDetail` route. Budget,
  description and command are resolved from the tapped row's input, mirroring
  `ChatScreen`'s `onOpenJob` resolution (`ChatScreen.kt:151-168`).
- Wire the jobs sheet's bash rows on the sub-agent page to the same navigation
  (the `TodoJobsDock` nullable `onOpenJob` affordance left by commit 4596657).

### 3. Store-backed mode in `JobDetailViewModel`

- Add an optional `ownerAgentId: String? = null` argument to the `JobDetail`
  route: `null`/`"main"` = today's REST-backed mode (unchanged); anything else =
  store-backed mode. The navigators know the owner (chat → main; sub-agent page
  → the page's agent id; jobs sheet → the job's owner).
- Store-backed mode sources: title/Command/budget seeded from route args as
  today; status/startedAt/completedAt/timedOut from the owning record's live
  jobs in `SubagentStreamStore` (fed by `task.started`/`task.terminated` since
  commit 4596657), collected while the screen is open. It must **never** call
  `getTask`/`cancelTask` (they 40406 for these ids).
- UI in store-backed mode: the Output section renders an explicit "output not
  available for sub-agent tasks" placeholder instead of the poll placeholder;
  the Stop button is hidden. No full-screen error when REST has nothing to say.
- JobDetailScreen keeps holding the socket gate open (existing
  `pageOpened`/`pageClosed` mechanism) so live `task.terminated` frames reach
  the store while the screen is on top.

## Acceptance criteria

- [ ] Unit test: the single enrichment function derives `bashTaskId` from a
  `Bash` output whose first line is `task_id: bash-…`, `subagentId` from an
  `Agent` result carrying the `agent_id:` marker, and timeout fields from the
  call input — and never overwrites already-set fields.
- [ ] Unit test: `replaceTranscript`/`prependTranscript` rows carry
  `bashTaskId`/`subagentId`/timeout exactly like history-flattened rows
  (regression: transcript flatten previously derived nothing).
- [ ] Unit test: a live `ToolCallResult`-linked row keeps its `bashTaskId`
  across a transcript refetch (`replaceTranscript`, the terminal reconcile
  path).
- [ ] On the sub-agent page (on-device or emulator), tapping a background
  `Bash` row opens the job screen instead of expanding inline; the inline
  expand behavior is unchanged for ordinary rows.
- [ ] The job screen opened from a sub-agent page shows the seeded
  title/Command/budget, reflects a live `task.terminated` (status flip,
  timed-out label) without any REST call, shows the output-unavailable
  placeholder, and offers no Stop button — no full-screen 40406.
- [ ] Tapping a bash row in the sub-agent page's jobs sheet opens the same
  store-backed job screen.
- [ ] Existing job-screen tests pass unchanged for the main-agent (REST) mode;
  new tests cover the mode split on `ownerAgentId`.

## Out of scope

- **Cold-open status healing** (job status after process death / app restart,
  where no live `task.*` frame was observed): ticket 099.
- **Output tail for sub-agent-owned tasks**: no wire channel exists (no
  `tool.progress`, no file-read endpoint, REST `/tasks` 40406). Would require an
  upstream server feature (per-agent tasks endpoint) — not planned.
- **Stop/cancel for sub-agent-owned tasks**: REST `:cancel` answers `40406`;
  no channel exists.
- **REST seeding of sub-agent-owned jobs** into `SubagentStreamStore`: REST
  `/tasks` never serves them (live-only by protocol).
- Foreground (non-background) Bash rows: nothing changes for them.

## Notes

- The bug's anatomy and the four derivation sites: `ChatTimeline.kt` —
  `completeOrphanResults` (~line 150), live result handling (~lines 518-541),
  history flatten result branch (~lines 616-627), transcript-turn flatten
  (~lines 678-693, the one that derives nothing).
- `docs/server-api.md`, "Background lifecycle" (~lines 1614-1668): the
  owning-agent stamp on `task.*` frames and the main-only REST `/tasks`
  limitation — the constraint behind the store-backed mode.
- Commit 4596657 ("todos/jobs dock on the sub-agent page") already routes
  sub-agent-owned `task.*` frames into `SubagentStreamStore` records
  (`SubagentState.jobs`) and left the dock's `onOpenJob` nullable for this
  ticket.
- The command's output is indirectly observable: the completion notification
  makes the owning sub-agent Read the task's `output.log`, which lands in the
  sub-agent's transcript — visible on its page, but not addressable as a job
  output.
