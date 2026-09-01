# Server default model/effort editing in Settings

- **ID:** 109
- **Created:** 2026-09-01
- **Depends on:** none

## Context

Louis (2026-09-01): there is no way to change the server's default model and
thinking effort from the app. The API supports it: `POST /api/v1/config`
accepts `{"default_model": "<model id>"}` and `{"thinking": {"effort":
"<effort>"}}` (the thinking write is live-verified 2026-08-28; the POST body
is snake_case). The write is server-wide — it changes the default for every
client and pushes a global `event.config.changed` frame.

Placement decision (confirmed with Louis): a new "Models" section in
Settings, **not** inside the chat model picker — the picker is per-session
scope and mixing "for this session" with "for the whole server" in one sheet
is confusing. The picker keeps its read-only "Default" badge.

`GET /api/v1/config` also exposes a `providers` map (`type`, `base_url`,
`has_api_key` — credentials redacted), which gives Louis the provider
visibility he asked for; the section lists providers read-only. Managing
providers (add/remove keys) exists upstream (`POST`/`PUT`/`DELETE
/api/v1/providers`, `GET /api/v1/catalog/providers`) but is deliberately
excluded — scope creep for a phone client.

## Description

Data layer (`data/chat/ModelsRepository.kt`, `data/chat/ChatDtos.kt`):

1. Extend `ConfigData`: `thinking: {effort: String?}` and
   `providers: Map<String, ProviderDto>` where `ProviderDto` carries `type`,
   `base_url`, `has_api_key`. All fields defaulted — the existing
   partial-read degradation rule applies.
2. `ModelsRepository` gains a `config(settings)` fetch returning the default
   model, the default thinking effort, and the provider list — reusing the
   existing per-server-URL caching pattern (one cache slot for the whole
   config projection; `defaultModel()` reads from it instead of its own slot).
3. `setDefaultModel(settings, model)` and `setDefaultEffort(settings,
   effort)`: `POST /api/v1/config` with `{"default_model": ...}` /
   `{"thinking": {"effort": ...}}` (snake_case). On success, update the
   cached config so subsequent reads serve the new value without a refetch.
   Failures surface as `Result`/`DataError` like every other call.

UI (`ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`):

4. A "Models" section below the existing server settings, only when the
   config fetch succeeded (graceful degradation, same rule as the chat chip):
   - **Default model** row: current default's display name; tap opens a
     simple selection dialog/sheet listing `GET /api/v1/models` (reuse the
     repository's models cache — no second fetch path).
   - **Default thinking effort** row: options from the default model's
     `support_efforts`; hidden entirely when the default model has none.
   - **Providers**: one read-only row per provider — name, `base_url`, and
     "API key configured"/"no key" from `has_api_key`.
5. A failed write shows a snackbar and leaves the rows at their previous
   values.

## Acceptance criteria

- [x] Unit test: `setDefaultModel` posts `{"default_model": "<id>"}` and the
      repository's cached config serves the new default afterwards.
      (`ModelsRepositoryTest`)
- [x] Unit test: `setDefaultEffort` posts `{"thinking": {"effort": "<e>"}`};
      a failed write surfaces an error and does not touch the cache.
      (`ModelsRepositoryTest`)
- [x] Unit test: the effort row is hidden when the default model has no
      `support_efforts`. (`SettingsViewModelTest` — plus the with-efforts
      case, a failed-write snackbar test, and the fetch-failure degradation)
- [x] `./gradlew testDebugUnitTest` passes (2026-09-01, all suites green).
- [ ] Live check: change the default model in Settings, then verify with
      `curl ... /api/v1/config` that `default_model` changed; restore the
      previous value afterwards (and note the `event.config.changed` frame
      fires — ticket 110 consumes it). (Endpoint level verified 2026-09-01:
      POST `{"default_model": "kimi-code/k3"}` → GET reads it back → restored
      to `Kimi Platform/kimi-k3`; the app sends the identical body, pinned by
      `ModelsRepositoryTest`. The on-phone Settings UI tap is pending Louis.)
- [x] `docs/server-api.md`: document the consumed `POST /api/v1/config`
      fields (`default_model`, `thinking.effort`, providers projection) if
      not already accurate. (New bullet in the Models section.)
- [x] APK built and copied to the file server per AGENTS.md
      (`kimi-android-client-main.apk`, 2026-09-01).

## Out of scope

- Adding/editing/removing providers or API keys — upstream endpoints exist;
  deferred unless Louis asks.
- Invalidating caches on external config changes (`event.config.changed`) —
  ticket 110.
- Per-session anything — the chat picker (107/108) owns that scope.
- A model row in the create-session flow (still rejected, see 031).

## Notes

- `POST /api/v1/config` body is snake_case while the GET echoes some fields
  camelCase (`docs/server-api.md`, Models section) — verify the exact POST
  shape live before wiring the DTO.
- The config write triggers a global `event.config.changed` on every client,
  including this one; the repository's cache update on success must not be
  confused by that frame (110's invalidation just causes a refetch — safe).
- Settings screen currently holds only Server URL / Token / Test connection —
  follow its existing section styling.

## Revision (2026-09-01, after Louis's on-device pass)

- The default model and effort merged into ONE "Default model" row ("K3 ·
  high"); it opens the chat's `ModelPickerSheet` itself — the check marks the
  server default, no "Default" badge in this context. A failed write dismisses
  the sheet so the snackbar is not hidden behind its scrim.
- The providers moved under their own "Providers" section header.

