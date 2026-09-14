# Session list: handle the global `event.session.created` frame

- **ID:** 129
- **Created:** 2026-09-14
- **Depends on:** 128

## Context

The server broadcasts a global WS frame `event.session.created` carrying
the full session object whenever ANY client creates or forks a session
(`packages/kap-server/src/protocol/events-zod.ts`: payload
`{type, session: sessionSchema}`, published on create and on `:fork`).
The app's `OkHttpSessionListSocket` drops it as unknown today, so a
session created or forked from the web UI only appears in the app on the
next full refresh. This is the same staleness class ticket 126 fixed for
`event.session.deleted`, in the opposite direction.

Depends on 128 because of dedup: once the app itself forks (127/128) or
creates (008) a session, it inserts the row locally AND the server echoes
the frame back — the handler must upsert by session id, never insert a
second row.

## Description

**1. Verify and document first.** Capture a verbatim `event.session.created`
frame from the live server (fork a throwaway session from the web UI while
a websocat client is subscribed) and add it to docs/server-api.md's Global
events section — the payload key casing inside the WS envelope must be
confirmed against the capture before writing the DTO (REST session objects
are snake_case; global-event payloads have used camelCase, e.g.
`SessionArchivedPayload`).

**2. Event.** Add `SessionListEvent.SessionCreated(session)` to
`SessionListSocket` and map `"event.session.created"` in
`OkHttpSessionListSocket.handleFrame`, same placement as ticket 126's
deleted mapping (parsed ahead of the unsubscribed-session filter; the
envelope `session_id` is `__global__`).

**3. Collector.** In `SessionListViewModel.onSocketEvent`
(:206-245), upsert the carried session by id into the flat list and its
workspace group at its `updated_at` position; an id already present is a
no-op (or in-place refresh). Ignore frames whose workspace is unknown to
the current grouped view the same way other patches handle it.

**4. Tests.** `OkHttpSessionListSocketTest`: the verbatim capture maps to
`SessionCreated` despite the `__global__` envelope. ViewModel test: a
frame for an unknown id inserts the row live; a frame for an
already-present id (the local create/fork echo) does not duplicate it.

## Acceptance criteria

- [ ] Verbatim live capture added to docs/server-api.md (Global events).
- [ ] Frame parsed to `SessionListEvent.SessionCreated` — unit test green.
- [ ] A session forked or created from the web UI appears in the app's
      list without a refresh — unit test green.
- [ ] Local create (008) and local fork (127/128) produce exactly one row
      each once the echo frame arrives — dedup unit test green.
- [ ] Full `:app:testDebugUnitTest` run passes; unknown-frame ignore path
      unchanged for other unmapped types.

## Out of scope

- `event.workspace.created` / other lifecycle frames.
- Sorting the new row anywhere but its `updated_at` position.
- Chat-screen behavior when the currently open session is forked from
  elsewhere (nothing breaks; the fork is just a new list row).

## Notes

- Ticket 126 (`event.session.deleted`) is the structural template for
  this one: DTO placement in `data/sessions/SessionDtos.kt`, socket
  mapping, collector handling, test shape.
