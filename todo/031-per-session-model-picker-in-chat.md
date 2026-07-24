# Per-session model picker in chat

- **ID:** 031
- **Created:** 2026-07-24
- **Depends on:** none

## Context

Since ticket 017, the app sends the server's `default_model` with every
prompt — that was a regression fix, not a feature: the user has no way to
choose a model. The server currently offers three (`GET /api/v1/models`,
verified live on 0.29.0): K2.7 Coding (262k ctx), K2.7 Coding Highspeed
(262k ctx), K3 (1M ctx, server default).

Product decisions (confirmed with Louis, 2026-07-24):

- **Granularity: per session.** The protocol only honors model per prompt
  (`agent_config.model` at session creation is ignored by the server — see
  ticket 017), so "session model" is an app-side concept: remember a choice
  per session and stamp it on every prompt. Alternatives rejected: app-wide
  setting only (no per-session flexibility); both app-wide + per-session
  (two sources of truth, more code for no asked-for need).
- **No model row in the create-session flow** — new sessions start on the
  server default, switch in chat if wanted.
- **Placement: a chip in the chat top bar** showing the current model; tap
  opens a bottom sheet with the model list. Alternatives rejected: overflow
  menu (current model invisible), permanent row above the composer (wastes
  vertical space).
- Known limitation to accept: a session opened in another client (web/TUI)
  won't share the choice — inherent to the per-prompt protocol, not fixable
  app-side.

## Description

Data layer (`data/chat/ChatRepository.kt`, `data/chat/ChatDtos.kt`):

1. Add `getModels`: `GET /api/v1/models`, parse `data.items` through the
   existing `Envelope` — fields: `provider`, `model`, `display_name`,
   `max_context_size`, optional `support_efforts`, optional `default_effort`.
   Same error-boundary contract (`Result`/`DataError`) as the other calls.
2. Cache the list in the repository keyed by server URL, exactly like the
   `default_model` config cache from 017. Fetch lazily (first chat-screen
   open that shows the chip), not at app start.
3. Persist the chosen model **per session ID** in DataStore (the app already
   uses it via `data/settings/SettingsRepository.kt` — follow that pattern;
   a simple `model_for_session_<sessionId>` string preference is enough).
   No stored value = current behavior (send the server's `default_model`).
4. `postPrompt` uses the session's stored model when present, else the
   cached server default, else no `model` field. This is the single choke
   point from 017, so `sendPrompt` and `steer` are both covered.

UI (`ui/chat/ChatScreen.kt`, `ui/chat/ChatViewModel.kt`):

5. Top bar: a small chip (e.g. `AssistChip`/outlined chip) showing the
   current model's display name — the session's choice if set, otherwise
   the server default's display name (fall back to the raw model id if the
   models list is unavailable). It must coexist with the session title from
   ticket 026 (title takes remaining space, ellipsized).
6. Tap opens a `ModalBottomSheet` listing the models: display name, context
   size (e.g. "1M context"), a "Default" label on the server's default
   model, and a check/selected state on the current one. Tapping a model
   persists it, dismisses the sheet, and applies to subsequent prompts.
7. Graceful degradation: if the models fetch fails, hide the chip entirely
   and keep the 017 behavior — sending must never be blocked by this
   feature.

Docs:

8. Add a `GET /api/v1/models` section to `docs/server-api.md` (endpoint is
   currently undocumented there) with the verified response shape above,
   including `support_efforts`/`default_effort` on K3.

## Acceptance criteria

- [ ] Unit test: with a model chosen for the session, the `POST /prompts`
  body contains `"model": "<chosen>"` — for both `sendPrompt` and `steer`.
- [ ] Unit test: with no choice stored, the body carries the server default
  (017 behavior preserved); with no choice and config fetch failed, no
  `model` field.
- [ ] Unit test: the models endpoint is called once across several chat
  opens (cache hit), and again after the server URL changes.
- [ ] Unit test: selecting a model in the ViewModel persists it and updates
  the exposed state; the choice survives ViewModel recreation.
- [ ] `./gradlew testDebugUnitTest` passes.
- [ ] Live check against the 0.29.0 server: pick a non-default model in a
  session, send a prompt, and the session's
  `GET /api/v1/sessions/{id}/status` reports that `model`.
- [ ] `docs/server-api.md` documents `GET /api/v1/models`.
- [ ] APK built and copied to the file server per AGENTS.md.

## Out of scope

- Thinking-effort picker — ticket 032 (builds on this one).
- Model row in the create-session flow.
- App-wide default model in Settings.
- Showing the model in the session list.
- Cross-client model sync (impossible: the server ignores model at session
  creation; each client stamps its own per-prompt model).

## Notes

- Verified live 0.29.0 response for `GET /api/v1/models` (trimmed):
  `{"code":0,"data":{"items":[{"provider":"managed:kimi-code","model":"kimi-code/k3","display_name":"K3","max_context_size":1048576,"capabilities":[...],"support_efforts":["low","high","max"],"default_effort":"high"}, ...]}}`.
- `ChatRepository` is a `@Singleton`; `ChatRepository.postPrompt` is the
  only place the prompt body is built.
- Keep the chip read-only while the models list is loading; do not block
  the chat screen on the fetch.
