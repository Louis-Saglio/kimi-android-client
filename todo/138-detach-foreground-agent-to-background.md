# Detach a foreground agent to background

- **ID:** 138
- **Created:** 2026-09-15
- **Depends on:** 134

## Context

Server 0.39.0 added `POST /api/v1/sessions/{id}/tasks/{taskId}:detach`: a
running foreground subagent converts to background, the foreground `Agent`
call resolves immediately with a stub, `task.started` and
`background.task.started` fire, and completion follows the background
lifecycle with `task.notified` (docs/server-api.md:3524-3536). The web
exposes this as a "To background" button on foreground running agent cards.

Use case: a member or single agent that turns out to run long. Detaching
lets the main turn continue while the task lands in the jobs dock, which
already links back to the subagent page (ticket 101). The app has no detach
call today (documented gap, never ticketed).

Affordance placement decision: the `SubagentScreen` top bar overflow, not the
timeline card or roster row. One home for the action covers both single
agents and swarm members, since both open the same screen, and it keeps the
card and row layouts simple.

## Description

**1. API** (`data/chat/ChatApi.kt`). `detachTask(settings, sessionId, taskId)`
POSTs `:detach` and unwraps `{"detached":true,"status":"running"}`; surface
envelope errors (unknown task -> 40406; non-foreground or terminal -> the
documented error) through the existing `toResult` idiom.

**2. Menu action** (`ui/chat/SubagentScreen.kt`). Top bar overflow gains
"To background" (web-parity string), visible only while the record status is
Queued, Working, or Suspended, `runInBackground == false`, and
`taskId != null`. On success show the existing snackbar idiom confirming the
move; the page itself stays open and keeps streaming (the subagent id does
not change).

**3. Transition wiring** (`ui/chat/SubagentStreamStore.kt`, `data/chat/Tasks.kt`
as needed). After detach, the member's `task.started` arrives main-owned,
kind agent, with `info.agentId` naming the member. Verify against the
ticket-098/099 routing that this merges into the existing `SubagentRecord`
and flips `runInBackground` instead of creating a duplicate or dropping the
frames; patch what is missing. Verify the jobs dock then lists the task with
its page link (ticket 101's seeding, `agentTaskIdOf`), fixing the seed if
the detached case slips past it.

**4. Tests.** API test for the new call; store test for the
foreground-to-background transition (frames in, record flipped, no
duplicate); androidTest: menu item visibility by state, tap flow with a
faked success, hidden for background records.

## Acceptance criteria

- [ ] New unit + androidTests above pass; existing suite green.
- [ ] Observable on device: run a long foreground `Agent` call, open its
      page, "To background"; the main turn resumes in the chat, the task
      appears in the jobs dock, and the member page keeps streaming to
      completion.
- [ ] The action is absent for background members and for terminal records.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Detach from the swarm card or roster row (the page overflow is the single
  home; revisit if usage asks for it).
- Any special rendering of the stub tool result the foreground call resolves
  with; the generic row is fine.
- Detaching background tasks further (already background; meaningless).

## Notes

- The detach sequence on the wire, verbatim ordering:
  docs/server-api.md:3332-3424 (background lifecycle) and :3524-3536
  (`:detach` itself).
- `subagent.spawned.runInBackground` and the roster's `run_in_background`
  are the state source; both reach the store via 134.
