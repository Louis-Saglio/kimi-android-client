# Fork a session from the chat screen

- **ID:** 127
- **Created:** 2026-09-14
- **Depends on:** none

## Context

Server 0.40.1 added session forking (upstream `9c37feb4`); 0.42.0 exposes
it on the wire as `POST /api/v1/sessions/{id}:fork`. Verified live on
2026-09-14 against the running 0.42.0 server:

- Body `{"title"?, "metadata"?}`, both optional; `{}` works.
- Response is the standard session envelope (same shape as create/get).
  The fork inherits the source's `workspace_id` and `metadata.cwd` and
  contains a full copy of the conversation (`/messages` on the fork
  returns the source history).
- Default title when none is passed: `Fork: <source title>`.
- Errors: unknown id → `40401`; fork while a turn is running →
  `40901` `"session … cannot be forked while a turn is running"`.
- Quirk: the fork's `updated_at` is copied from the source, so in a
  recency-sorted list the fork lands next to its origin, not at the top.
- The server publishes a global `event.session.created` WS frame with the
  full session object (forks and plain creates alike). Handling that frame
  is ticket 129, not this one.

The web client ships this exact flow: sidebar row menu + chat header
overflow + `/fork` slash command, all POSTing `:fork` with `{}` (no title
prompt, no confirmation), then inserting the fork into the list and
switching straight to it.

Chosen UX for the app (product decision): entry point is the chat screen's
overflow menu, which today holds only "Compact session" and was built to
grow (`ChatScreen.kt:326-350`). No title prompt and no confirmation —
web parity, and the app has no rename UI anyway. The list-row entry point
is ticket 128. Turn-level fork (`turnIndex`) exists in the upstream SDK
but is NOT exposed on the REST route, so "fork from this message" is
impossible over HTTP and stays out.

## Description

**1. Docs.** Add the `:fork` route to docs/server-api.md (Sessions
section, next to compact/delete): method, path, request body, success
envelope, the `Fork: ` default-title rule, `40401`/`40901` errors with
the verbatim captures from Context, the history-copy semantics, and the
`updated_at` quirk. Header verification log entry included.

**2. Data layer.** Add `forkSession(settings, sessionId)` to
`SessionsRepository` (`data/sessions/SessionsRepository.kt`, next to
`createSession` at :84): `api.post` to `/api/v1/sessions/{id}:fork` with
an empty-json body, parse the session envelope with the existing v1 DTOs
(`data/sessions/SessionDtos.kt`), map to the `Session` domain model.
Error mapping reuses the existing `ApiClient` envelope handling; confirm
`40901` lands in the same `DataError` case as other busy errors.

**3. Chat UI.** Add "Fork session" to the overflow menu in
`ui/chat/ChatScreen.kt` under "Compact session", disabled while
`UiState.isWorking` (forking a running session always fails with `40901`,
so pre-disable instead of erroring). New `ChatViewModel` intent calls
`SessionsRepository.forkSession`; on success emit a navigation effect to
`Chat(fork.id, fork.title, fork.cwd)` (same pattern as the session-list
`Effect.SessionCreated` → `KimiNavHost` route); on failure show the
existing snackbar/error channel with the server's message.

## Acceptance criteria

- [ ] `SessionsRepositoryTest` green with MockWebServer: success capture
      parsed into `Session` (id, `Fork: ` title, cwd from metadata),
      `40401` and `40901` envelopes mapped to the right `DataError` cases.
- [ ] Chat overflow menu shows "Fork session"; it is disabled while a
      turn is running and enabled otherwise.
- [ ] Tapping it on an idle session ends with the app showing the forked
      session's chat; the forked title reads `Fork: <source title>`.
- [ ] A forced error (e.g. fork a deleted session id) surfaces a snackbar
      with the server message, and the user stays on the source session.
- [ ] docs/server-api.md documents the route with the verbatim captures.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      --max-workers=1 --no-daemon` passes.

## Out of scope

- Session-list entry point (ticket 128).
- Handling `event.session.created` on the list socket (ticket 129).
- Title prompt at fork time, rename UI in general.
- Turn-level fork (`turnIndex`) — not exposed on the REST route.
- `/btw` side chats, although they are fork-based upstream.
- Forking from the Subagent screen or any sub-agent session.

## Notes

- Verbatim success capture (2026-09-14, 0.42.0), trimmed:
  `POST /api/v1/sessions/session_…:fork {}` →
  `{"code":0,"msg":"success","data":{"id":"session_…","workspace_id":"wd_spike_…","title":"Fork: Fork source","created_at":"2026-09-14T07:51:37.614Z","updated_at":"2026-09-14T07:51:37.539Z","busy":false,"archived":false,"metadata":{"cwd":"/home/ubuntu/spike"},…,"last_seq":0}}`
  Note `updated_at` predates `created_at`: it is inherited from the source.
- Verbatim busy error:
  `{"code":40901,"msg":"Session \"session_…\" cannot be forked while a turn is running","data":null,…}`
- The web client's call (from the shipped bundle): POST
  `/sessions/{id}:fork` with `{}`, then insert-sorted + select. No title
  is ever sent by the web UI.
