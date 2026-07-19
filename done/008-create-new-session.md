# Create new session

- **ID:** 008
- **Created:** 2026-07-17
- **Depends on:** 004

## Context

Without this, starting a session still requires the web client, which breaks the
"phone-first" goal of the clone. Kept minimal: a path text field, not a file
browser.

## Description

- FAB ("+") on the session list opening a create-session dialog:
  - working-directory text field, pre-filled with a sensible default,
  - optional title field,
  - client-side validation: non-empty absolute path.
- Confirm POSTs session creation per `docs/server-api.md`.
- On success: dismiss, navigate to the new session's chat screen.
- On failure: snackbar with the server error; dialog stays open with input
  intact.

## Acceptance criteria

- [x] A session created from the phone appears in the web client's session list
  and opens into an empty chat screen on the phone.
  *(Create POST verified live via curl 2026-07-19: the new session (custom
  title included) is returned by `GET /api/v1/sessions`, the same endpoint the
  web client lists; navigation to the chat screen is driven by the
  `SessionCreated` effect, unit-tested in `SessionListViewModelTest`; the empty
  chat is the ticket-005 flow. On-device verification pending Louis — APK
  published to the file server 2026-07-19.)*
- [x] Empty/relative path → inline validation error, no request sent.
  *(Unit-tested in `SessionListViewModelTest`: both cases set
  `newSessionCwdError`, the dialog stays open, and MockWebServer records zero
  requests.)*
- [x] Server failure → snackbar, dialog input preserved.
  *(Unit-tested in `SessionListViewModelTest` with the real `40409` envelope
  shape: `CreateFailed` carries the server's `msg`, the dialog stays open with
  the typed path intact.)*
- [x] `./gradlew :app:assembleDebug` succeeds. *(2026-07-19: 34 unit tests
  green — 9 new (3 in `SessionsRepositoryTest`, 6 in `SessionListViewModelTest`);
  Firebase Test Lab robo passed on MediumPhone.arm-34; APK published to the
  file server.)*

## Out of scope

Browsing the server filesystem to pick a directory (would need the `fs:*`
endpoints — deferred), workspaces, forking an existing session (`:fork` —
deferred), session templates.

## Notes

- 2026-07-19: the create endpoint accepts an optional top-level `title`
  (verified live — returned in the response and shown in the list); omitted,
  the server defaults to "New Session". A non-existent `cwd` is refused with
  envelope code `40409` ("workspace root ... does not exist") over HTTP 200 —
  surfaced as-is in the failure snackbar. There is no `DELETE /sessions/{id}`
  route (404); unwanted sessions can only be archived from the web client.
- The dialog's default path is hardcoded to `/home/ubuntu` (the server user's
  home); per-session last-used paths were considered and left out as
  over-engineering for v1.
- Ticket 007's caveat still applies to sessions created here: they have
  `agent_config.model` empty, and prompts without a per-prompt `model` override
  fail with `model.not_configured`. The app's `sendPrompt` sends no override,
  so first prompts in a phone-created session may fail until the model is set
  (e.g. from the web client). Not addressed here — candidate for a follow-up
  ticket (pass the server default model per prompt, or expose a model picker).
