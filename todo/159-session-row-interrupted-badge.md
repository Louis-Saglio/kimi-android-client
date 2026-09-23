# Interrupted-last-turn badge on session rows

- **ID:** 159
- **Created:** 2026-09-23
- **Depends on:** none

## Context

Since 0.35.0 the session object's `last_turn_reason` is observable with a
persisted fallback on cold sessions (`"completed"` | `"cancelled"`,
docs/server-api.md:1701-1724), surviving server restarts. The field is
already in `SessionDtos.kt:149` — parsed and never consumed. Ticket 145
deliberately built the unread/running indicators on live WS
`event.session.work_changed` only and shipped neither the web's
`lastTurnReason "failed"`→"aborted" danger badge nor any cold-state
badge. Net effect: a session whose last turn died (abort, crash, server
restart mid-turn) looks identical to a healthy one until opened.

## Description

**1. State.** The session-list row model consumes `lastTurnReason`:
`"cancelled"` → interrupted state; `"completed"` (or absent) → normal.

**2. Badge.** A small warning-toned indicator at the row's trailing edge
when interrupted (icon-only, contentDescription "Last turn did not
complete"). It must compose with the 145 running spinner and unread dot
— spinner wins while running, badge shows only when not running. The
badge clears on the next list refresh after a turn completes (the field
updates server-side).

**3. Tests.** Row-state unit test (cancelled vs completed vs absent;
spinner precedence); androidTest: badge present on an interrupted row,
absent otherwise.

## Acceptance criteria

- [ ] Unit + androidTests above pass; existing suite green.
- [ ] On device: a session whose last turn was aborted shows the badge
      after a cold app start (no WS event needed); completing a turn
      clears it on refresh.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Distinguishing cancel causes on the badge (the REST field only carries
  the coarse reason; 155 covers per-turn detail inside the chat).
- A web-parity "failed"→"aborted" mapping (the REST enum has no
  `"failed"` value).
- Push-style updates (the list refreshes on its existing triggers).

## Notes

- Keep it subtle: this is a triage hint on a crowded row, not an alarm.
