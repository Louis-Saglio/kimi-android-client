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

## Implementation notes

**2026-09-16 — step 1 probe: the field is dead on the live 0.43.1 server;
the stop condition fired and the toggle was NOT built.**

Probe (throwaway session `session_40deee77-…` at `/home/ubuntu/spike`,
aborted and `:delete`d afterwards):

- `POST /api/v1/sessions/{id}/prompts` with
  `{"content":[{"type":"text","text":"…"}],"swarm_mode":true}` → acked
  `code:0`, the turn ran to completion ("probe-ok" reply, one step, 15 s).
  `/status` polled during and after the turn: `swarm_mode` stayed `false`
  every read; the transcript carried no `swarm.enter` marker.
- Positive control on the same session: `POST .../profile` with
  `{"agent_config":{"swarm_mode":true}}` flipped `/status` `swarm_mode` to
  `true` — swarm mode itself works on this server; only the prompt-body
  field is inert.
- Upstream confirmation at tag `@moonshot-ai/kimi-code@0.43.1`:
  `packages/kap-server/src/routes/prompts.ts` (submitPrompt) applies only
  `profile`/`model`/`thinking`/`permission_mode`/`disabled_tools` from the
  body; `swarm_mode` parses (`protocol/rest-prompt.ts:28`) but is never
  consumed. The only live consumer is `applySessionAgentConfig`
  (`routes/sessionAgentConfig.ts` → `swarm.enter('manual')`), reached via
  the profile route's `agent_config`.

docs/server-api.md was updated with this finding (Prompts section); the
edit is left UNCOMMITTED in the worktree — per the stop condition nothing
was committed. Steps 2-6 (state, chip, send path, tests) were not
implemented: shipping the toggle would give the composer a chip whose flag
never reaches the agent.

Follow-up path if the feature is still wanted: either wait for an upstream
fix making the prompt-body field live, or re-scope the ticket to drive
swarm mode through `POST .../profile` `agent_config.swarm_mode` (a
session-level toggle with its own UX semantics — the chip would then
read/write server state instead of stamping a per-prompt flag).

**2026-10-28 — re-probe on server 2.3.3: the field is still dead; the ticket is
closed without a toggle.**

Probe (throwaway session `session_472093ba-…`, created under `/home/ubuntu/spike`,
deleted afterwards):

- `POST /api/v1/sessions/{id}/prompts` with
  `{"content":[{"type":"text","text":"Reply with exactly: probe-ok"}],"swarm_mode":true}`
  acked `code:0` and the turn completed; `/status` read `swarm_mode:false` before,
  during and after the turn (context grew to 195 tokens, so the turn really ran).
- Positive control on the same session: `POST .../profile` with
  `{"agent_config":{"swarm_mode":true}}` flipped `/status` `swarm_mode` to `true`.

The upstream submitPrompt handler still ignores the prompt-body field on 2.3.3
(docs/server-api.md keeps the 0.43.1 finding, now re-verified). Per the ticket's
stop condition nothing was shipped: a chip stamping a per-prompt flag would never
reach the agent. The follow-up path stays as written above — either an upstream
fix, or a re-scoped server-state toggle through the profile route.
