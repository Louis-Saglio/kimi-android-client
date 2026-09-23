# Decode `run_in_background`; drop the known-background heuristic

- **ID:** 162
- **Created:** 2026-09-23
- **Depends on:** none

## Context

Since 0.39.0 every REST `/tasks` object — list and get, running and
terminal — carries `run_in_background` (commit `6595955b`,
docs/server-api.md:908, 4072). `TaskDto` (ChatDtos.kt:360) does not
decode it, so ticket 101 built a WS/marker-derived "known-background
set" to filter foreground sub-agent ghosts out of the jobs surfaces —
bookkeeping that exists precisely because the flag wasn't on the wire.
It is now on the wire.

## Description

**1. DTO.** `TaskDto` gains `run_in_background: Boolean`.

**2. Filter simplification.** `JobsStore`'s foreground filter uses the
decoded flag directly; delete the known-background set and all of its
update sites (WS spawn events, task markers). Behavior must be
unchanged: foreground (ghost) tasks still never appear in the jobs
dock/surfaces; background tasks appear as today, including REST-seeded
cold starts (which the heuristic could only cover partially).

**3. Comment sweep.** The comments at ChatDtos.kt:354 ("no
subagent_type, no parent_tool_call_id") and JobsStore.kt:109 ("REST
carries no agentId") are stale since 0.36.1 — fix them while here.

**4. Tests.** Unit test: REST list containing `run_in_background:false`
foreground subagents and `true` background bash yields only the bash
rows; existing 101 tests updated from the heuristic to the flag.

## Acceptance criteria

- [ ] Unit tests above pass; existing suite green.
- [ ] On device: the jobs dock shows the same jobs as before the change
      (regression check against a session with both kinds).
- [ ] No trace of the known-background set remains (grep).
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Decoding `subagent_type`/`parent_tool_call_id` for job-row badges and
  deep links (LOW in the drift review; separate ticket if wanted).
- Changing which surfaces list which task kinds.

## Notes

- Pure code-health enabled by the server finally shipping the flag 101
  needed.
