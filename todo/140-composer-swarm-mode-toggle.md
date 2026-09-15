# Composer: swarm mode toggle

- **ID:** 140
- **Created:** 2026-09-15
- **Depends on:** none

## Context

The monitoring side of swarm support is tickets 134-139. The triggering side
is swarm mode: with it on, the agent prefers `AgentSwarm` for parallel work.
The web exposes it as a composer "Swarm" chip (per-session flag) and a
`/swarm` slash command; the flag rides the prompt body as `swarm_mode`
(docs/server-api.md:2422) and is visible on `/status`
(docs/server-api.md:1547). The app sends prompts via
`ChatRepository.sendPrompt` -> `ChatApi.postPrompt` (ChatApi.kt:139) and
decodes no mode flags today.

Caution the implementer must close first: the `swarm_mode` prompt field is
schema-documented but its live semantics were never captured
(docs/server-api.md notes the gap). Step 1 probes the running server and
confirms the field before any serializer is written.

## Description

**1. Probe.** Confirm the prompt body field on the running 0.43.0 server:
send a prompt with `swarm_mode: true` in a throwaway session and verify via
`/status` or upstream `agent-core-v2` prompt handling at tag 0.43.0 that the
agent enters swarm mode. Update docs/server-api.md with the verified
behavior. If the field turns out to be dead on this server, stop and report
instead of shipping a dead toggle.

**2. State.** Per-session boolean in `ChatViewModel`, kept across
configuration change (SavedStateHandle). Process death resets to off:
acceptable, the web's persistence is client-local as well, and an
unintentionally-sticky swarm mode is worse than a forgotten one.

**3. Composer** (`ui/chat/components/Composer.kt`, `ChatInputBar` :56). Add a
toggle chip, sparkles icon plus "Swarm" label, selected container color when
on (web chip idiom), `contentDescription` "Toggle swarm mode", `testTag`.
Placement beside the existing composer controls, respecting the 48dp touch
target.

**4. Send path.** `postPrompt` gains a `swarmMode: Boolean = false`
parameter; when true the prompt body carries `swarm_mode: true` (omit the
field otherwise, keeping prompt bodies minimal). `ChatRepository.sendPrompt`
passes the flag through from the ViewModel.

**5. No status echo.** The chip is the state indicator, matching the web,
which shows swarm mode in the composer only. Do not touch `/status`
decoding.

**6. Tests.** Serialization test: field present when on, absent when off.
androidTest: chip toggles, and a send with the chip on reaches the
repository fake with the flag set.

## Acceptance criteria

- [ ] The probe in step 1 is documented in the ticket's implementation notes
      (field name, observed behavior), and docs/server-api.md is updated if
      it drifted.
- [ ] New unit + androidTests above pass; existing suite green.
- [ ] Observable on device: toggle on, send a parallel-friendly task (e.g.
      "review these three files"); the agent uses `AgentSwarm`, visible via
      the 134 swarm card. Toggle off and prompting behaves as before.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- The `/swarm` slash command: the app has no slash-command infrastructure,
  and the chip covers the feature.
- Persisting the flag across process death.
- Any server-side default via config.

## Notes

- Web chip strings (0.43.0 bundle): "Swarm", "Turn off Swarm".
- The prompt body reference: docs/server-api.md:2422; `/status` `swarm_mode`:
  docs/server-api.md:1547. Transcript markers `swarm.enter`/`swarm.exit` are
  already tolerated by the parse and stay unrendered (ChatDtos.kt:500-514).
