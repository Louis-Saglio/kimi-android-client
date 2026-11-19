# Consume the unified 2.7 global events on both sockets

- **ID:** 208
- **Created:** 2026-11-19
- **Depends on:** 206

## Context

Server 2.7.0 (nightly drift run, 2026-11-19; commit `2f33641b`) replaced every
global push with a flat, unified frame (verified live):

- `event.session.created|archived|restored|deleted` →
  `{"type":"session","change":"created"|"updated"|"archived"|"restored"|"deleted","session":<session object>,"event_created_at"}`
- `session.meta.updated` and `event.session.work_changed` → the same
  `session`/`updated` frame, carrying the full session object (`activity`,
  `pending_interaction`, `last_turn_outcome`, `last_prompt`, `title`);
- `event.config.changed`/`event.config.warning` → `{"type":"config",config,warnings?}`;
- `event.model_catalog.changed` and `event.plugin.changed` →
  `{"type":"invalidated","kind":"models"|"plugins"}`.

The envelope (`session_id:"__global__"`, `timestamp`, `payload`) is gone, so
the app's `SessionListWsFrame.payload` decoding and its
`event.session.*`/`session.meta.updated` branches never match. Consequences
on 2.7.0: the list stops live-updating rows (running spinner, pending badge,
title, interrupted badge, unread dot), never removes archived/deleted rows,
never inserts sessions created elsewhere (forks included), and never
invalidates the config/models caches. The chat socket's busy edge
(`event.session.work_changed`, ticket 181) is dead too, so a dropped
compaction never settles.

This ticket supersedes ticket 204 (2.6.3-scoped, never implemented): the
separate `event.session.restored` it planned no longer exists, and the
unified `session`/`restored` frame carries the full session object, so no
follow-up REST fetch is needed.

## Description

Both sockets already multiplex on `frame.type`; add the `session` branch and
delete the dead ones.

- `data/sessions/SessionDtos.kt` / `OkHttpSessionListSocket`:
  - Parse the flat frame (fields beside `type`, no `payload`).
  - `session` + `created`: insert the decoded session (same sorted insert as
    today's `event.session.created`).
  - `session` + `updated`: if the id is in the loaded set, upsert the full
    row (title, `updated_at`, activity→running, `pending_interaction`,
    `last_turn_outcome`→interrupted, `last_prompt` if the row uses it);
    ignore updates for sessions the list never loaded.
  - `session` + `archived`: remove the row; `restored`: insert it from the
    frame without a REST fetch (the frame carries it); `deleted`: remove.
  - `config`: emit the existing `ConfigChanged`; `invalidated` kind
    `models`: `ModelCatalogChanged`.
  - Keep the `observed` foreign-session filter for `updated` only — created,
    archived, restored, deleted, config and invalidated are app-level pushes.
  - Drop `SessionMetaUpdatedPayload`, `WorkChangedPayload`,
    `SessionArchivedPayload`, `SessionDeletedPayload`,
    `SessionCreatedPayload` and the branches that used them.
- `data/chat/SessionSocket`:
  - Replace the `event.session.work_changed` branch with `session` +
    `updated` for the open session: emit `SessionWorkChanged(busy =
    activity != "idle")` and use `last_turn_outcome` for the idle edge.
  - Keep the existing compaction settle logic keyed on the idle edge.

## Acceptance criteria

- [ ] A fake `{"type":"session","change":"created","session":{…}}` for a new
      id inserts one row; an `updated` for a loaded id patches its title,
      running state, pending badge and interrupted badge; an `updated` for an
      unloaded id changes nothing.
- [ ] A fake `archived` removes exactly that row; `deleted` removes it;
      `restored` inserts it with no HTTP request (MockWebServer assertion).
- [ ] `config` invalidates the cached config, `invalidated`/`models`
      invalidates the model catalog.
- [ ] The chat socket maps `session`/`updated` `activity:"turn"` to busy and
      `activity:"idle"` + `last_turn_outcome:"completed"` to the idle edge
      once.
- [ ] No source reference to `event.session.created|archived|restored|
      deleted`, `event.session.work_changed` or `session.meta.updated`
      remains outside comments/history.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- The bare ping and the removed `server_hello` (ticket 196).
- `subscribe`/`response` (ticket 197) and the conversation frames (198).
- `event.host.changed` and the workspaces plane.
- Closing the chat screen on a remote `session`/`deleted` of the open
  session (no such behavior today).

## Notes

- `docs/server-api.md` → WebSocket → Global events (verbatim captures) and
  Sessions → Archive / Restore.
- The full session object in `updated` is an improvement: the row can be
  replaced wholesale instead of re-derived from `busy`/`last_turn_reason`.
- Ticket 206's `SessionDto` changes are the decode target here.
