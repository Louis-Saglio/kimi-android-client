# Send model per prompt

- **ID:** 017
- **Created:** 2026-07-21
- **Depends on:** none

## Context

Since the nightly job upgraded the Kimi server to 0.28.1 (2026-07-21 03:24),
every prompt sent from the app fails instantly: the turn dies in ~5 ms with
`[model.not_configured] Model not set` (`turn.ended` reason `failed`), so the
chat screen shows the echoed user message and no reply. Root cause (verified
against the live server, see event journals under
`~/.kimi-code/server/events/`): 0.28.1 no longer falls back to the server's
`default_model` for sessions with an empty `agent_config.model`. The app has
never sent a model — it relied on that fallback, which worked up to 0.27.0.

Verified on 0.28.1:

- `POST /api/v1/sessions/{id}/prompts` with `"model": "kimi-code/k3"` in the
  body runs and completes normally (docs/server-api.md already documents this
  per-prompt override).
- Passing `agent_config.model` at session creation is ignored by the server
  (returned as `""`), so creation time is not a viable fix.
- `GET /api/v1/config` exposes `default_model` (currently `kimi-code/k3`);
  `GET /api/v1/models` lists the available models.

Decision (confirmed with Louis): the app fetches the server's `default_model`
from `GET /api/v1/config` and sends it as `model` with every prompt.
Alternatives considered and rejected: hardcoding `kimi-code/k3` (breaks
silently when the server default changes); an app-side model setting (useful
later, but out of proportion for unbreaking the app — deferred).

## Description

In the data layer (`data/chat/ChatRepository.kt`, DTOs in
`data/chat/ChatDtos.kt`):

1. Add a `getDefaultModel(settings)` call: `GET /api/v1/config`, parse
   `data.default_model` through the existing `Envelope`, same error-boundary
   contract as the other calls (`Result`/`DataError`).
2. Cache the result in the repository (it is a `@Singleton`): one config
   fetch per server URL per app process — keyed by server URL so a settings
   change refetches. On fetch failure, behave as before the regression: send
   the prompt without a `model` field (keeps pre-0.28 servers working and
   degrades gracefully instead of blocking sending).
3. Include `model` in the prompt body built by `postPrompt` when a default
   model is known. This covers both `sendPrompt` and `steer`, since `steer`
   goes through `postPrompt` too.
4. Trigger the config fetch lazily before the first prompt send of a process
   (not at app start, not on a timer).

Update `docs/server-api.md` only if the implementation reveals a mismatch
with the documented `model` prompt field (it was verified on 0.27.0 and
re-verified live on 0.28.1 during the investigation, so no change is
expected).

## Acceptance criteria

- [ ] Unit test: when `GET /api/v1/config` returns `default_model`, the
  `POST /prompts` body contains `"model": "<default_model>"` — for both
  `sendPrompt` and `steer`.
- [ ] Unit test: the config endpoint is called once across several sends
  (cache hit), and again after the server URL changes.
- [ ] Unit test: when the config fetch fails (network/HTTP/envelope error),
  the prompt is still sent, without a `model` field.
- [ ] `./gradlew testDebugUnitTest` passes.
- [ ] Live check against the 0.28.1 server: a session created from the app
  (or an equivalent curl of the app's request shape) completes a turn instead
  of failing with `model.not_configured`.
- [ ] APK built and copied to the file server per AGENTS.md.

## Out of scope

- Model picker / per-session model selection in the UI (future ticket if
  wanted; `GET /api/v1/models` is available for it).
- Surfacing failed turns in the chat UI (`turn.ended` reason `failed`
  currently clears the working indicator silently — separate UX issue).
- Cleaning up the `debug-*` sessions created on the server during the
  investigation.
- Any server-side or nightly-job changes (the job's post-update verification
  also aborted on 0.28.1 — separate concern, not this repo's app code).

## Notes

- `ChatRepository.postPrompt` is the single place the prompt body is built;
  `steer` reuses it, so one change covers both send paths.
- `Envelope` and the `DataError` mapping already exist; follow the pattern of
  `getPendingQuestions` for the new endpoint.
- Investigation details: server event journal for the failing phone session
  is `~/.kimi-code/server/events/session_17c0f5c8-771f-41e9-b7ef-0049be4c75db.jsonl`.
