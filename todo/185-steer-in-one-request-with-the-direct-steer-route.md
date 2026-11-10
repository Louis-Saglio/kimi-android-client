# Steer in one request with the direct steer route

- **ID:** 185
- **Created:** 2026-11-10
- **Depends on:** none

## Context

Found by the nightly server drift run from 2.5.2 to 2.5.3 on 2026-11-10
(server 2.5.3, upstream commit `e1912c80`, "improve same-turn steer
interactions"). That release added **`POST /api/v1/sessions/{id}/steer`**, a
one-request steer: the body is the prompt-submission schema (`content` with
the same attachment parts, `agent_id`, `prompt_id`, `skills`, `model`,
`thinking`, `permission_mode`, `swarm_mode`, `metadata`, `disabled_tools`,
`profile`) and the success data is the prompt ACK shape. On a busy session
it queues and steers atomically; on an idle session it behaves exactly like
`POST .../prompts` (live verification in `docs/server-api.md`, Steer →
Steer message content).

The app's steer (ticket 014) is two requests: `POST .../prompts` (queues
the prompt while the session is busy), then `POST .../prompts:steer` with
the returned id (`ChatRepository.steer` → `ChatApi.postPrompt` +
`ChatApi.postSteer`). The gap between them is a real failure mode on a
phone: the running turn can finish in that window, so `prompts:steer`
answers `40402 "no active prompt to steer into"` while the prompt the first
call queued stays on the server and later runs as its own turn. The
composer reports a send failure and keeps the text, so the user's retry
duplicates the message. The new route also accepts a steer into a turn
whose prompt is untracked (`steerIfActive` now keys on `activeTurnId`),
which the queue-then-steer flow cannot reach.

The alternative — keep both calls and treat `40402` as success — still
leaves two round trips and silently degrades the steer into a queued turn.
The direct route is the structural fix: one request, one outcome. If the
turn ends before the request lands, the route starts a normal turn instead,
so the race has one benign outcome rather than a spurious failure.

## Description

1. Add a single API method (e.g. `ChatApi.postDirectSteer`) that POSTs to
   `/api/v1/sessions/$sessionId/steer` and decodes `data.prompt_id` like
   `postPrompt`. Reuse the same body builder for both prompt and steer:
   `content` (text + uploaded image/file parts), `model`, `thinking`,
   `permission_mode`, `swarm_mode` — no other fields.
2. Change `ChatRepository.steer` to a single call through that method.
   Remove `ChatApi.postSteer` (its only caller) and the separate prompt POST
   from the steer path.
3. Keep the composer's branch unchanged: idle sends `POST .../prompts`
   (`sendPrompt`), busy sends the direct steer. The direct route's idle
   fallback does not replace `sendPrompt`.
4. Preserve the current failure semantics: a failed steer returns
   `Result.failure(DataError…)`, the ViewModel keeps the input and shows the
   retry snackbar; no optimistic echo is appended before the ACK.
5. Update the tests:
   - `ChatRepositoryTest`: a steer issues exactly one POST to the
     `/api/v1/sessions/{id}/steer` path with the full prompt body and
     returns the ACK id; remove the old two-request assertions and the
     `postSteer` mock.
   - `ChatViewModelTest`: the busy-send test awaits one `/steer` request and
     asserts no `/prompts` or `prompts:steer` request happens; the idle-send
     test stays on `/prompts`.
   - `ChatViewModelTestBase`: serve the `/steer` path (status 200,
     `{"code":0,"data":{"prompt_id":"msg_…"}}`) and drop the `prompts:steer`
     stub if no test uses it.
   - Cover the failure path (non-zero envelope / HTTP error) leaving
     `isSending` false, the input text intact, and no echo row.

## Acceptance criteria

- [ ] Sending while the session is busy issues exactly one POST to
      `/api/v1/sessions/session_1/steer` with the same body the prompt POST
      builds (content parts, model, thinking, permission_mode, swarm_mode);
      no `/prompts` and no `prompts:steer` request is recorded. Unit-tested.
- [ ] Sending while idle still issues exactly one POST to
      `/api/v1/sessions/session_1/prompts`. Existing test unchanged.
- [ ] The steer ACK's `prompt_id` keys the optimistic echo and heals from the
      `prompt.upsert`/undo marker like today (the existing steered-echo test
      passes against the one-request flow).
- [ ] An attached image and an attached file are included as the same content
      parts the prompt POST sends; the upload happens before the steer.
- [ ] A failed steer (`40402`, `40902`, network) surfaces as
      `Result.failure`/`DataError`, keeps the text, and appends no echo.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- The queued-prompt management UI and `POST .../prompts:steer` as a public
  protocol feature (it stays documented; no other caller in the app).
- Per-prompt abort (`POST .../prompts/{id}:abort`).
- Moving the idle send to `/steer`.
- Stale/occupied-session retry UX (ticket 168).
- Any server change; the route already exists on the running server.

## Notes

Relevant code: `data/chat/ChatApi.kt` (`postPrompt` body builder,
`postSteer`), `data/chat/ChatRepository.kt` (`steer`), `ui/chat/
ChatViewModel.kt` (`onSend` busy branch), `test/.../ChatRepositoryTest.kt`,
`test/.../ChatViewModelTest.kt`, `test/.../ChatViewModelTestBase.kt`.
Wire reference: `docs/server-api.md` → Steer → Steer message content, with
the ACK capture and the mid-stream step sequence.
