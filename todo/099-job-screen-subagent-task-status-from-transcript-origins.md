# Store-backed job screen: heal terminal status from transcript turn origins

- **ID:** 099
- **Created:** 2026-08-06
- **Depends on:** 098

## Context

Ticket 098 gives the job screen a store-backed mode for sub-agent-owned
background tasks, sourced from `SubagentStreamStore`'s live `task.*` frames.
That mode has one known hole: the store's jobs are **live-only** (REST `/tasks`
never serves sub-agent-owned tasks, so there is no seed). After process death —
or simply when the app never observed the live frames — opening the job screen
from a transcript row shows a job with no reliable status: the row's own output
stub forever reads `status: running`, and nothing heals it.

A durable channel may already exist: since 0.33.0, `turn.started` frames for
task-completion notification turns carry an origin of kind `task` —
`{"kind":"task","taskId":"…","status":"completed","notificationId":"…"}`
(`docs/server-api.md`, background-task notifications) — and the transcript
endpoint ships each turn's `origin` (only `{"kind":"user"}` and
`system_trigger/subagent` shapes are documented so far; the `task` shape on the
REST transcript is **unverified**).

Decision (from the 098 discussion): cold-open healing was split out of 098 as
this follow-up, gated on a one-command wire verification. If the transcript
ships the `task` origin, mine it when applying transcript pages to complete the
record's jobs — the same "canonical history heals live-only state" pattern the
app already uses everywhere. If it does not, render the unknown status honestly
instead of trusting the stale `status: running` stub.

## Description

1. **Verify the wire shape first** (one curl against the local server, on a
   session with a completed sub-agent-owned background task):
   `GET /api/v1/sessions/<sid>/transcript?agent_id=<subagentId>` — check whether
   the notification turn carries `origin: {"kind":"task","taskId":…,"status":…}`.
   Record the verbatim result in `docs/server-api.md` (the drift-check docs
   convention) either way.
2. **If verified**: parse the `task` origin in the transcript DTOs, and have
   `SubagentStreamStore.applyTranscriptPage`/`prependTranscriptPage` upsert the
   named job's terminal status into the record's jobs (first-writer-wins against
   live frames — a live `task.terminated` stays authoritative, carrying
   `stopReason`/`timedOut` the origin lacks). The store-backed job screen then
   shows the true terminal status on a cold open.
3. **If not verified** (no `task` origin on the transcript): the store-backed
   job screen must distinguish "status unknown" from the stub's
   `status: running` text — never present the stale stub as live state (e.g. an
   explicit "last known while watched" / unknown-status treatment). Implement
   whichever variant the verification supports; do not ship both.

## Acceptance criteria

- [ ] `docs/server-api.md` documents the transcript `task`-origin verification
  (verbatim shape, server version, date) — whether it exists or not.
- [ ] If the origin exists — unit test: applying a transcript page whose turn
  origin is kind `task` upserts the named job's terminal status in the record's
  jobs, and a live `task.terminated` observed for the same task is never
  regressed by a later transcript page.
- [ ] If the origin exists — on a cold-opened job screen (app restarted after
  the task finished), the store-backed mode shows the true terminal status.
- [ ] If the origin does not exist — the store-backed job screen never renders
  the row stub's `status: running` as current state when no live frame or
  transcript evidence exists.
- [ ] The main-agent (REST-backed) job screen is untouched.

## Out of scope

- Output tail and Stop for sub-agent-owned tasks — no channel exists
  (see ticket 098's Out of scope).
- Mining task origins from the **main** agent's transcript (main-owned jobs
  already have the REST `/tasks` seed).
- Backfilling job descriptions/commands from anywhere but the tapped row.

## Notes

- `turn.started` origin kind `task` (WS side, verbatim): `docs/server-api.md`
  ~line 1601. Transcript turn `origin` field: same doc, Transcript section
  (~lines 673-680) — only `user` and `system_trigger/subagent` shapes are
  documented today, which is what the verification settles.
- The transcript endpoint answers for any agent id, running or finished, and
  the sub-agent page already paginates it — the mining rides the existing
  `applyTranscriptPage`/`prependTranscriptPage` funnel, no new fetch path.
