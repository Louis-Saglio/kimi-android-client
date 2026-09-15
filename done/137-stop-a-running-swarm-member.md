# Stop a running swarm member

- **ID:** 137
- **Created:** 2026-09-15
- **Depends on:** 134

## Context

The server supports stopping one subagent without aborting the turn:
`POST /api/v1/sessions/{id}/tasks/{taskId}:cancel` works on running members,
foreground ones included since 0.34.0 (docs/server-api.md:3478-3495). The
cancel sequence ends in `task.terminated` with `status:"killed"` and no
`subagent.completed`/`subagent.failed`. `ChatApi.cancelTask` already exists
(ChatApi.kt:402, ticket 092) for background jobs.

Use case Louis confirmed on 2026-09-15: kill one runaway or stuck member
without aborting the whole turn (the only whole-swarm stop on the wire is
the session abort). The member's task id reaches the store via
`subagent.spawned.taskId` and the roster, both parsed in 134.

The web's affordance is a bare "stop" button on running task rows, no
confirmation. Followed here: the action is deliberate but not destructive to
the session, and the parent agent sees the kill notification and can react.

## Description

**1. Row action** (`ui/chat/SwarmScreen.kt`). Member rows gain a trailing
stop `IconButton` (close icon, `contentDescription` "Stop", `testTag`)
visible while the member status is Queued, Working, or Suspended and
`record.taskId != null`. Terminal members and ghost rows (136) never show it.

**2. Call path.** Reuse the repository-level wrapper the jobs sheet uses for
`cancelTask` (find the existing call site and share it, so error surfacing
stays consistent). On tap: disable the button immediately, fire the call; on
failure re-enable and show the snackbar idiom already used for job cancel
failures.

**3. State settling.** No optimistic status flip beyond disabling the button:
the row goes terminal when `task.terminated` (status killed) arrives, which
the store already routes for member tasks (tickets 098/099). Before 135 the
row settles as Failed, after 135 as Cancelled; both are fine. Verify the
foreground-member case: a cancelled foreground member makes the `AgentSwarm`
call resolve with that member failed while remaining members continue, and
both the roster row and the member's `SubagentScreen` reflect the terminal
state. Wire whatever is missing.

**4. Tests.** Store test: `taskId` presence and status drive button
visibility; repository test for the cancel call (existing mock pattern);
androidTest: tap disables the button, an injected `task.terminated` frame
settles the row, terminal rows have no button.

## Acceptance criteria

- [ ] New unit + androidTests above pass; existing suite green.
- [ ] Observable on device: start a swarm with a long-running member (e.g.
      one item that sleeps), stop it from the roster; the row goes terminal,
      the member's page stops streaming, and the swarm completes with the
      remaining members.
- [ ] Stopping an already-terminal member is impossible (no button).
- [ ] A failed cancel (e.g. race with completion) re-enables nothing harmful:
      the error surfaces and the row reflects the true state.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Stop from the `SubagentScreen` top bar (single-agent pages too): a natural
  follow-up, kept out to protect the diff; ticket separately if wanted.
- Whole-swarm stop: the wire offers only the session-level abort, which
  already exists (ticket 009).
- Resume/retry of a stopped member (the server's `resume` flow is a separate
  feature).

## Notes

- Verbatim killed capture: docs/server-api.md:3382 (`task.terminated` with
  `stopReason:"Aborted by the user"`).
- Foreground cancel makes the `Agent`/`AgentSwarm` tool result carry
  `status: failed` for that member; 136's parser already maps that.
