# Clear the dead session model when the server reports it gone

- **ID:** 213
- **Created:** 2026-11-23
- **Depends on:** 198

## Context

Server 2.7.3 (nightly drift run, 2026-11-23; kap-server commit `2348f531`)
makes a session whose saved model alias was removed from `config.toml` open
again. The main agent's `agent` frame omits `model`/`thinking`, and after the
`turns` page the server sends one warning notice per subscribe:
`{"type":"notice","level":"warning","title":"<removed alias>",
"code":"model.not_configured"}` (source-derived from the 2.7.3 conversation
contract test; not probed live because it needs a config edit and a restart).
The server does not rebind or clear the stored alias; a prompt that does not
override the model runs on the configured default.

Ticket 198 decodes this frame and renders it as the generic dismissible
banner, so the user learns the alias is dead. The app still fails every
following prompt when it has its own pick stored: `ChatRepository.promptModel`
(`app/src/main/java/fr/louissaglio/kimi/android/data/chat/ChatRepository.kt:140`)
returns the per-session preference first and only falls back to
`models.defaultModel(settings)`, so the removed alias is stamped on each
prompt body (`ChatApi.postPrompt`, `model` field) and the turn fails. The
banner explains the first failure; nothing clears the stale preference, so
the failure repeats until the user happens to re-pick a model.

The notice is the authoritative signal and it names the alias. The absent
`agent.model` alone cannot drive this: it also means "never configured", and
clearing the preference on that signal would drop a legitimate pick for a
session whose agent is simply cold.

## Description

- In the chat's notice handling (added by ticket 198), when a main-agent
  notice carries `code:"model.not_configured"` and its `title` equals the
  session's stored model, delete that stored choice. Add
  `SessionPrefsStore.clearModel(sessionId)` (remove the
  `model_for_session_<sessionId>` key); the class currently only has
  `model`/`setModel`.
- Leave the preference alone for any other notice code, any sub-agent notice
  (`agent_id != "main"`), and any title that does not match the stored model
  (the app has no stale pick for that alias; a null preference already falls
  through to the default).
- Keep the ticket-198 banner. This change adds no second banner, snackbar or
  confirmation dialog: the chip's fallback to the server default is the
  visible result, and the banner already explains why.
- Do not clear the stored effort here. Once the effective model changes, the
  existing `promptThinking` compatibility check (`ChatRepository.kt:149`)
  drops an effort the new model does not support; that path is already the
  single owner of effort compatibility.

## Acceptance criteria

- [ ] A main-agent notice `{"code":"model.not_configured","level":"warning",
      "title":"old/model"}` with `old/model` stored as the session model
      removes the stored model; `ChatRepository.promptModel` then returns
      `models.defaultModel(settings)` (or null when the cache has none).
- [ ] The same notice with a different stored model, and any notice with an
      unknown code, leaves the stored model unchanged.
- [ ] A notice stamped with `agent_id:"agent-0"` leaves the stored model
      unchanged.
- [ ] The ticket-198 banner still renders exactly once; this change adds no
      second banner.
- [ ] The next prompt after the clear omits the dead alias from its body and
      the server accepts it (`status:"running"` or `"queued"`).
- [ ] Unit tests cover the matching clear and the three no-op cases;
      `:app:testDebugUnitTest` passes.

## Out of scope

- Decoding and rendering notices at all (ticket 198).
- Picking a replacement model automatically: the app follows the configured
  default, and the user can still choose another through the existing chip.
- Clearing the server-side stored alias (the server deliberately keeps it;
  its contract test asserts `getModel()` still returns the removed alias).
- Clearing on an absent `agent.model` without the notice (see Context).
- The 2.7.3 feedback routes and the MCP OAuth tool-state fields: no app
  surface, and the feedback client enum is `desktop`/`web` only.

## Notes

- `docs/server-api.md` → WebSocket → Session frames (`notice`, `agent`) and
  the 2.7.2→2.7.3 drift block.
- The shape is source-derived from
  `packages/kap-server/test/conversationContract.e2e.test.ts` at tag
  `@moonshot-ai/kimi-code@2.7.3`; the notice follows `turns` and is sent once
  per subscribe.
- `SessionPrefsStore` lives at
  `app/src/main/java/fr/louissaglio/kimi/android/data/chat/SessionPrefsStore.kt`.
