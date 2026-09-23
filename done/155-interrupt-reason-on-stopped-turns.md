# Interrupt reason on stopped turns

- **ID:** 155
- **Created:** 2026-09-23
- **Depends on:** none

## Context

`turn.ended` has carried an optional `interruptReason` since 0.31.1:
`"user_cancelled"` / `"aborted"` / `"max_steps"` / `"error"` /
`"filtered"` / `"blocked"` (docs/server-api.md:4723-4724); a REST `:abort`
reports `user_cancelled`. The app's `TurnEndedPayload` reads only
`reason`, so every early stop reads "cancelled" alike — a turn that hit
max steps, errored, or was filtered looks exactly like the user pressing
Stop, which reads as a bug when the agent stops on its own.

## Description

**1. DTO.** Add `interruptReason: String?` to `TurnEndedPayload`; expose
it on the `TurnEnded` event.

**2. Turn footer copy.** Where the chat renders the stopped/interrupted
turn state, map: `user_cancelled` → "Cancelled" (today's copy),
`aborted` → "Aborted", `max_steps` → "Stopped: max steps", `error` →
"Stopped: error", `filtered` → "Stopped: filtered", `blocked` →
"Stopped: blocked"; absent → today's behavior. Keep the visual treatment
of the current interrupted state; only the copy differentiates.

**3. Tests.** Parse test per enum value (frames at docs/server-api.md:3014,
5645); copy-mapping unit test; androidTest: a max_steps turn footer shows
the distinct copy.

## Acceptance criteria

- [ ] Unit + androidTests above pass; existing suite green.
- [ ] On device: user abort shows "Cancelled"; a turn stopped by the
      server shows its distinct reason.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- `turn.ended.stopReason` (0.41.0, never observed live) — add when seen.
- `interruptReason` on the sub-agent page / transcript terminal state
  (0.31.1 transcript `state:"cancelled"`/`error` rendering is a separate
  surface; ticket if wanted).
- Retry affordances per reason (the server owns retry policy).

## Notes

- The interruption-reminder injection (019's envelope stripper) rides
  along with these stops; no change there.
