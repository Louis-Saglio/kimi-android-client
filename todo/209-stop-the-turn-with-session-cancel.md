# Stop the turn with `POST :cancel`

- **ID:** 209
- **Created:** 2026-11-19
- **Depends on:** none

## Context

Server 2.7.0 (nightly drift run, 2026-11-19; commit `b9574579`) removed the
`:abort` action. Verified live: `POST /api/v1/sessions/{id}:abort` answers
`40001 "unsupported action: <session>:abort"` through the `{tail}` catch-all.
The replacement is `POST /api/v1/sessions/{id}:cancel` with optional
`{agent_id?, include_background?}`, answering `{"cancelled":true|false}`;
`false` means there was nothing to stop.

The chat's Stop button calls `ChatApi.abortPrompt` → `:abort`, so on 2.7.0 it
fails with a server error and the turn keeps running.

## Description

- Replace `ChatApi.abortPrompt` with `cancelTurn(settings, sessionId)` that
  posts `{}` to `/api/v1/sessions/{id}:cancel` and decodes
  `{cancelled: Boolean}`.
- Rename the repository/ViewModel path (`ChatRepository.abortPrompt` →
  `cancelTurn`, `ChatViewModel.onStop`) to the new name; keep the UI and the
  "Stop" semantics unchanged — the turn teardown still arrives on the
  conversation frames.
- Treat `cancelled:false` as success (the turn already ended); surface other
  failing envelope codes through the existing send-error snackbar.
- Update the unit tests that assert the `/abort` path to assert
  `/cancel` and the `{cancelled}` body.

## Acceptance criteria

- [ ] Tapping Stop sends exactly one `POST
      /api/v1/sessions/{id}:cancel` with `{}`; no request goes to `:abort`.
- [ ] A `{"cancelled":true}` answer leaves the UI waiting for the
      `turn.ended`/`agent` frames (no local teardown).
- [ ] A `{"cancelled":false}` answer shows no error.
- [ ] A `40401`/`50001` envelope surfaces the server message in the existing
      snackbar.
- [ ] `:app:testDebugUnitTest` passes; no `:abort` path remains in
      `ChatApi`/`ChatRepository`.

## Out of scope

- `include_background` (improvement ticket 211).
- Withdrawing a queued prompt (`prompts/{id}:abort`, ticket 195).
- Renaming the domain event or UI strings.

## Notes

- `docs/server-api.md` → Cancel.
- The old idle answer `{"aborted":true}` becomes `{"cancelled":false}`; the
  running answer is `{"cancelled":true}`.
