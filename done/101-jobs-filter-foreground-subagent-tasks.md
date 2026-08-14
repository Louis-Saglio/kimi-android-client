# Jobs surfaces: filter foreground sub-agents out of REST-seeded jobs

- **ID:** 101
- **Created:** 2026-08-06
- **Depends on:** none

## Context

Server 0.34.0 changed the task store (unannounced; verified live 2026-08-06,
`docs/server-api.md` "Sub-agents"): a **running foreground sub-agent now
registers in the main agent's task store** — listed by REST `/tasks`
(`kind:"subagent"`, `status:"running"`, with the new
`model`/`thinking_effort` fields), fetchable by id even once terminal, and
`:cancel`-able while running. It still emits **no `task.*` WS frames**, and
the list **drops it again once terminal** (`{"items":[]}` after a completed
run, even with `?status=completed`). On 0.33.0 foreground sub-agents never
appeared in REST `/tasks` at all.

The app's jobs surfaces (`JobsStore`, the `TodoJobsDock` jobs sheet) seed
from the REST `/tasks` list. On 0.34.0 they will therefore show **ghost
jobs**: a running foreground sub-agent appears as a subagent-kind row with no
WS lifecycle behind it, then **vanishes from the list without a terminal
state** the moment it finishes. Two further wrinkles: the row duplicates what
the sub-agent roster/page already shows (foreground sub-agents have their own
UI home), and the job screen's Stop button would now actually kill the
foreground sub-agent (`:cancel` works on it since 0.34.0) — a destructive
action on a job the UI is about to lose track of.

REST alone cannot mark these rows: the task object carries no
`run_in_background` flag and no `agentId` (`JobsWidget.kt:141` notes the
omission). The reliable discriminator app-side: background sub-agent tasks
are known from their `task.started` WS frames / the Agent tool result's
`task_id:` marker; a REST-listed subagent-kind task unknown to that set is
foreground.

## Description

Restore the 0.33.0 jobs behavior: exclude foreground sub-agent tasks from the
REST-seeded jobs list. A REST `/tasks` subagent-kind entry is treated as
foreground (and filtered) when its id is **not** in the known-background set
(ids learned from `task.started`/`task.terminated` WS frames and from Agent
tool-result `task_id:` markers — the set `SubagentStreamStore`/`JobsStore`
already maintains). Background sub-agent and bash jobs keep flowing exactly
as today.

Alternative considered and rejected: deliberately surface foreground
sub-agents as jobs (they are cancellable now). Rejected — the list drops them
on completion with no terminal state, so every such row ends as a
disappearing job; the sub-agent roster/page is their proper UI.

## Acceptance criteria

- [ ] Unit test: a REST tasks payload containing a `kind:"subagent"` running
      task whose id is unknown to the WS/marker background set yields **no**
      job row; the same id once learned via a `task.started` frame yields a
      row (background classification wins).
- [ ] Unit test: background sub-agent (`agent-…` id seen on WS) and bash
      (`bash-…`) REST entries are unaffected.
- [ ] On-device/emulator: during a turn with a foreground sub-agent, the jobs
      sheet shows no extra subagent row; the jobs sheet still lists a
      background `run_in_background` sub-agent from the same session.
- [ ] Existing jobs-store and job-screen tests pass unchanged.

## Out of scope

- A Stop/cancel affordance for foreground sub-agents (server supports it
  since 0.34.0; needs a product decision and a terminal-state answer first).
- Surfacing the new `model`/`thinking_effort` task fields in the UI (additive
  data, separate feature).
- The snapshot `subagents` roster and the sub-agent page (unchanged by this
  drift; the roster gained the same additive fields).
