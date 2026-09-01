# Invalidate models/config caches on global change events

- **ID:** 110
- **Created:** 2026-09-01
- **Depends on:** 109

## Context

`ModelsRepository` caches the models list and the config (default model,
default effort, providers) per server URL, and a cache entry lives until the
server URL changes. But the server's catalog and config drift without the app
knowing: nightly server updates have flipped `default_model` several times
(deepseek ↔ k3, see the drift log in `docs/server-api.md`), and any client —
web, TUI, or this app via ticket 109 — can write `POST /api/v1/config`.

Since 0.39.1 the server pushes two global frames on exactly these changes
(both were in the schema earlier but never forwarded; now verified
delivered): `event.config.changed` on every config write (payload carries the
full redacted config projection) and `event.model_catalog.changed` on
provider model refreshes. Both arrive with `session_id: "__global__"` on
every established connection. The app ignores them; its session-list socket
already handles `__global__` frames (`event.session.archived`, ticket 105),
so the delivery path exists.

Depends on 109 because that ticket restructures the config cache into a
single projection; the invalidation targets that shape.

## Description

1. Decode `event.config.changed` and `event.model_catalog.changed` on the
   global socket path (where `event.session.archived` is handled). The
   payloads need no field-level decoding — the frame type alone is the
   signal.
2. On either frame, drop the `ModelsRepository` caches for the current
   server URL (models list + config projection), so the next read refetches.
   Invalidation, not payload application: decoding the full config projection
   from the frame to update the cache in place is more code for no asked-for
   benefit.
3. Wire the socket event to the repository invalidation through the owning
   repository (follow how `event.session.archived` reaches
   `SessionsRepository`), keeping the data layer's error-boundary conventions.

## Acceptance criteria

- [x] Unit test: an `event.config.changed` frame causes the next
      `models()`/config read to refetch (cache miss) for the current server
      URL. (`OkHttpSessionListSocketTest` covers frame → event;
      `SessionListViewModelTest` covers event → refetch of both caches)
- [x] Unit test: same for `event.model_catalog.changed`; unrelated global
      frames (e.g. `event.session.archived`) do not invalidate.
      (`SessionListViewModelTest`)
- [x] `./gradlew testDebugUnitTest` passes (2026-09-01, all suites green).
- [ ] Live check: with the app open, `curl -X POST /api/v1/config
      '{"thinking":{"effort":"low"}}'`, then open the chat model sheet / the
      Settings models section — the new value shows without restarting the
      app. Restore the previous config value afterwards. (Frame delivery was
      already verified live 2026-08-28, see docs/server-api.md; the on-phone
      pass is pending Louis.)
- [x] APK built and copied to the file server per AGENTS.md
      (`kimi-android-client-main.apk`, 2026-09-01).

## Out of scope

- Decoding the frame's `config` projection or `changedFields` to update the
  cache in place (invalidation is enough).
- Invalidating on app-process restarts or TTLs — the event covers the real
  drift sources.
- The Settings "Models" section itself — ticket 109.

## Notes

- `docs/server-api.md`, "Global events — `event.config.changed` /
  `event.model_catalog.changed` (delivered since 0.39.1)".
- `SessionListSocket.kt` already documents that `__global__` frames carry
  their real session id in the payload — these two events need no session id
  at all.
- The app's own config write (109) also triggers `event.config.changed`;
  invalidation then just causes a refetch of the value already cached by the
  write path — harmless.
