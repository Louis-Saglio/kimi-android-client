# Move the client-config fetch to the GET route

- **ID:** 212
- **Created:** 2026-11-20
- **Depends on:** none

## Context

Server 2.7.0 (nightly drift run, 2026-11-20; commit `b9574579`; verified live on
2.7.2) replaced `POST /api/v1/client_configs` with a body `{"name": …}` by
`GET /api/v1/client-configs/{name}`. The old path is a plain HTTP 404 now; the
`{name, config}` payload is unchanged.

The app still posts the old route: `ModelsRepository.fetchClientConfig`
(`app/src/main/java/fr/louissaglio/kimi/android/data/chat/ModelsRepository.kt:134`)
calls `api.post(settings, "/api/v1/client_configs", …)`. `ApiClient` maps the
404 to `DataError.Http(404)`, and both callers
(`ChatViewModel` lines 1539–1540) discard failures with `getOrNull()`. The
result is a silent regression of tickets 167 and 170: the
`model_switch_confirm` gate never fires and the `effort_switch_confirm`
descriptions disappear, with no error the user can see. No existing ticket
covers this route — tickets 196–211 are the conversation-protocol migration.

This is the structural fix (correct endpoint, one request), not a patch around
the 404: the app has no reason to keep a POST shape the server no longer
serves.

## Description

- Change `ModelsRepository.fetchClientConfig` to `api.get` against
  `"/api/v1/client-configs/<url-encoded name>"` and keep the existing
  `ClientConfigData` decode and mapper.
- URL-encode the name as a single path segment (the two registered names are
  safe today, but the helper takes an arbitrary name).
- Update the KDoc in `ClientConfigs.kt` and `ModelsRepository.kt` that names
  the POST route.
- Leave the null-means-no-policy contract and the fresh-fetch-per-load
  behaviour untouched.

## Acceptance criteria

- [ ] A chat model-data load issues `GET /api/v1/client-configs/model_switch_confirm`
      and `GET /api/v1/client-configs/effort_switch_confirm`; no request goes
      to `/api/v1/client_configs`.
- [ ] Both live payloads decode exactly as before: the model-switch rules
      still gate a switch and the effort descriptions still populate
      (fake server in the unit test; live check in the verification notes).
- [ ] An unregistered name still yields `config:null` → no policy, and any
      HTTP failure still degrades to `null` without surfacing an error.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- Consuming new client-config names or adding a cache/TTL.
- Changing the model-switch or effort-switch UX from tickets 167/170.
- Any other route change from the 2.7.0 migration.

## Notes

- `docs/server-api.md` → Client configs (`GET /api/v1/client-configs/{name}`).
- Upstream commit `b9574579`; live 404 verified on 2.7.2.
