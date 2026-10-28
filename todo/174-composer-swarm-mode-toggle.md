# Composer: swarm mode toggle (revived — the prompt field is live since 2.4.0)

- **ID:** 174
- **Created:** 2026-10-28
- **Depends on:** none

## Context

Improvement found by the nightly server drift run from 2.3.3 to 2.4.0 on
2026-10-28 (server 2.4.0, upstream commit `78b72fe4`, "apply swarm mode from
prompt submissions").

Ticket 140 designed this toggle and was closed without shipping it because
`swarm_mode` in the prompt body was parsed but never applied: on 0.43.1 and
2.3.3 a prompt with `"swarm_mode":true` ran while `/status` stayed `false`.
That release fixed it. Live on 2.4.0: a prompt with `"swarm_mode":true`
leaves `/status` reading `swarm_mode:true`, and a following prompt with
`"swarm_mode":false` flips it back (verified on one thrown-away session;
`docs/server-api.md`, Prompts).

This matters on the phone because the app renders swarms (tickets 134-139:
the swarm card, roster, aggregate) but has no way to start one. Since 2.3.2
the engine refuses `AgentSwarm` unless swarm mode is on, and no app surface
turns it on. The only alternative is `POST /profile
{"agent_config":{"swarm_mode":true}}`, which the app never calls.

Semantics to respect (from the live probe): the field sets the mode before
the prompt is enqueued; **omitting it leaves the current mode unchanged**. A
chip that omits `false` would therefore leave swarm mode on after the first
swarm prompt, so the send path must always send the chip's current value.

## Description

1. Decode `swarm_mode` from `GET /api/v1/sessions/{id}/status` (the server
   always reports it) into `SessionStatusData`, and seed a per-session
   `swarmMode` in `ChatViewModel` from the chat's status seed. Keep it across
   configuration change in `SavedStateHandle` (ticket 140's decision); a
   process-death reset re-seeds from `/status`, which mirrors the server.
2. Add the composer chip from ticket 140 step 3: `ChatInputBar` toggle with a
   sparkles icon and "Swarm" label, selected container color when on,
   `contentDescription` "Toggle swarm mode", a stable `testTag`, and a 48dp
   touch target.
3. Thread `swarmMode` through `ChatRepository.sendPrompt` to
   `ChatApi.postPrompt`; the body always carries `"swarm_mode": <chip>`
   (true and false alike — see the omission semantics above). Keep the other
   body fields unchanged.
4. After a prompt completes, refresh `/status` as today; if it reports a
   different `swarm_mode` than the chip, adopt the server value.
5. No new endpoint, event, or polling loop.

## Acceptance criteria

- [ ] A unit test covers the prompt body: `swarm_mode:true` when the chip is
      on and `swarm_mode:false` when off.
- [ ] `SessionStatusData` decodes `swarm_mode` and the chip seeds from it on
      chat open.
- [ ] Compose/androidTest: the chip toggles, exposes the content description
      and test tag, and a send with the chip on reaches the repository fake
      with `swarmMode = true`.
- [ ] On device against 2.4.0: toggle on, send a parallel-friendly task, and
      the agent runs `AgentSwarm` (the 134 swarm card appears); toggle off and
      the next prompt leaves swarm mode off (`/status`).
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- A `/swarm` slash command (no slash-command infrastructure).
- Persisting the flag across process death beyond the `/status` re-seed.
- Mirroring server-side changes made by another client live (no event exists;
  the post-prompt `/status` refresh is the convergence point).
- Swarm UI changes (card, roster, aggregate) — tickets 134-139 are done.
- `plan_mode`/`tower_mode` toggles, even though the profile route supports
  them; they have no product need on the phone yet.

## Notes

Original design and rationale: `done/140-composer-swarm-mode-toggle.md`
(closed on a stop condition that no longer holds). Relevant code:
`data/chat/ChatApi.kt` (`postPrompt`), `data/chat/ChatRepository.kt`,
`data/chat/ChatDtos.kt` (`SessionStatusData`), `ui/chat/ChatViewModel.kt`
(status seed/send), `ui/chat/components/Composer.kt` (`ChatInputBar`). Wire
reference: `docs/server-api.md` → Prompts (`swarm_mode` bullet) and
`/status` (`swarm_mode`).
