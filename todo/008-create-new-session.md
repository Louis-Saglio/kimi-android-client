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

- [ ] A session created from the phone appears in the web client's session list
  and opens into an empty chat screen on the phone.
- [ ] Empty/relative path → inline validation error, no request sent.
- [ ] Server failure → snackbar, dialog input preserved.
- [ ] `./gradlew :app:assembleDebug` succeeds.

## Out of scope

Browsing the server filesystem to pick a directory (would need the `fs:*`
endpoints — deferred), workspaces, forking an existing session (`:fork` —
deferred), session templates.

## Notes

