# Stalled-socket watchdog and REST resync

- **ID:** 152
- **Created:** 2026-09-23
- **Depends on:** none

## Context

The 0.38.0 drift log recorded a live anomaly (3/3 repro): the subscribed
WS silently stalls right after the Agent call's `tool.result` during
background-subagent runs — no close frame, no further events, not even
pings; REST shows the run completing. The drift log's explicit
recommendation: "The Android app should treat a stalled socket as a drop
and resync via REST; no app ticket filed." `SessionSocket.kt:51-55`
disables the read timeout and relies on protocol pings + pongs (103), so
today a silently-stalled socket stays "connected" forever and the chat
just stops streaming. The server pings every `heartbeat_ms:10000` and
closes with `1001 "heartbeat timeout"` after ~20 s of client silence
(docs/server-api.md:4236) — so *inbound* silence of 25–30 s is
unambiguously a stall.

## Description

**1. Watchdog.** On both sockets (session + session-list): any inbound
frame resets an inbound-idle timer (already parsed frames — the pong
answer path from 103 is untouched). If no frame arrives within 3×
`heartbeat_ms` (30 s with the current 10000), treat the socket as
dropped: close it and run the existing reconnect path (backoff per the
current reconnect logic), then resync via REST exactly as the
`resync_required` flow (048/085) does. Reset the watchdog across
reconnects; no frame-timeout during the TCP/TLS connect phase (covered by
the connect timeout).

**2. Instrumentation.** One log line (and a Crashlytics non-fatal breadcrumb
if the project already has that hook) when the watchdog fires, so field
stalls are distinguishable from server closes in reports.

**3. Tests.** Fake-frame-source unit test: silence past the threshold
triggers close + reconnect + resync; any frame resets the timer; a ping
alone keeps the socket alive (server pings count as inbound frames);
reconnect re-arms the watchdog. androidTest not required (timing is
host-side).

## Acceptance criteria

- [ ] Unit tests above pass; existing suite green.
- [ ] On device: during a background-subagent run that stalls the socket
      (the 0.38.0 repro), the chat resumes streaming within ~30 s via
      reconnect + REST resync instead of freezing.
- [ ] Normal long turns with streaming deltas never trip the watchdog
      (frames flow).
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Changing the ping/pong protocol handling (103) or the server's own
  20 s silence close.
- Redesigning the reconnect backoff policy.
- An outbound "am I alive" probe — the server protocol has none; inbound
  silence detection is the whole mechanism.

## Notes

- Prefer implementing the watchdog once in the shared socket machinery
  both sockets use; if they genuinely differ, implement per socket rather
  than forcing a bad abstraction.
- Threshold constant: 3× `heartbeat_ms` from `server_hello`, not a
  hardcoded 30 s.
