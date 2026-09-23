# Prompt lifecycle feedback (queued → started)

- **ID:** 154
- **Created:** 2026-09-23
- **Depends on:** none

## Context

`prompt.submitted` and `prompt.queued` have existed since at least 0.38.0
and `prompt.started` shipped in 0.39.0 (docs/server-api.md:928-935). The
round-trip skeleton is `prompt.submitted` → `turn.started` →
`context.spliced` → `prompt.started` → steps → `turn.ended` →
`prompt.completed` (docs/server-api.md:292-293); `prompt.queued` carries
`queueLength`. The app decodes none of the three: a prompt sent while the
agent is busy queues server-side with zero feedback — the message bubble
just sits there looking identical to a running one.

## Description

**1. DTO/events.** Parse the three frames into events next to the
existing prompt events (ChatEvents.kt): `PromptSubmitted {promptId}`,
`PromptQueued {promptId, queueLength}`, `PromptStarted {promptId,
agentId}`. Follow the current ignore-unknown tolerance.

**2. State + UI.** The optimistic user bubble (012's echo machinery)
gains a sub-state: on `prompt.queued` show "Queued #N" (N =
`queueLength`), on `prompt.started` for its promptId flip to the normal
in-flight look. `prompt.submitted` needs no UI (the local echo already
exists) — parse it for correlation only. If the queue position changes
(only one `prompt.queued` per prompt is expected), later frames win.

**3. Tests.** Parse tests with verbatim frames (docs/server-api.md:930
for `prompt.started`; queue shape from the 0.39.0 verification log);
ViewModel test: queued bubble shows "Queued #N", `prompt.started` clears
it; androidTest: queued indicator visible while busy.

## Acceptance criteria

- [ ] Unit + androidTests above pass; existing suite green.
- [ ] On device: sending a second prompt while busy shows "Queued #1" on
      its bubble, then the bubble flips to in-flight when the running
      turn completes and this one starts.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- A queue-management UI (reorder/cancel a queued prompt — the server
  exposes no such route).
- Steer integration (014 owns the steer UX; `prompt.steered` already
  handled).

## Notes

- `prompt.started` fires per agent (`agentId`); only the main agent's
  frames reach the main chat stream (044 filters sub-agent frames).
