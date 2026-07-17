# Server settings and connection check

- **ID:** 003
- **Created:** 2026-07-17
- **Depends on:** 001, 002

## Context

The app reaches the server exactly like the web client: Caddy at
`http://100.64.0.1:80` over Tailscale with Louis's HTTP basic-auth
credentials (chosen over exposing the server publicly with a token — no new
attack surface, no VPS changes). Credentials must be editable on-device and
survive restarts. Every later ticket depends on a working authenticated
`OkHttpClient`.

## Description

- Add dependencies: OkHttp, `kotlinx.serialization`, DataStore (preferences).
- Settings screen (gear icon on the Sessions placeholder app bar):
  - server URL field, default `http://100.64.0.1`
  - username field
  - password field with `PasswordVisualTransformation`
  - Save button persisting to DataStore
  - "Test connection" button calling the auth-verifying endpoint identified in
    ticket 001; shows success (plus server version/name if the endpoint exposes
    one) or an explicit error distinguishing 401 (bad credentials) from
    unreachable host.
- Hilt-provided singleton `OkHttpClient` with an interceptor that adds the
  `Authorization: Basic …` header built from the stored settings, rebuilt when
  settings change.
- MVVM per house pattern (`UiState` `StateFlow` + effects `Channel`).

## Acceptance criteria

- [x] With valid URL + credentials, "Test connection" reports success against the
  real server (manually verified by Louis on-device, 2026-07-17; endpoint contract
  also verified from the VPS with curl, and the success path is covered on-device
  against a loopback MockWebServer in `SettingsFlowTest`).
- [x] Wrong password → explicit "authentication failed"; unreachable host →
  explicit network error. *(Instrumented on Firebase Test Lab.)*
- [x] Settings survive process death: force-stop, relaunch, fields pre-filled.
  *(Verified via activity recreation + DataStore disk read in `SettingsFlowTest`.)*
- [x] Unit test: the interceptor produces the correct `Authorization` header for
  stored settings.
- [x] `./gradlew :app:assembleDebug` succeeds.

## Out of scope

Session list and chat (004+). Encrypted credential storage — basic auth over a
Tailscale-only HTTP endpoint is the accepted risk for now. Tailscale-status
detection, multiple server profiles.

## Notes

- Endpoint shapes come from `docs/server-api.md` (ticket 001), not from
  re-probing the bundle.
- Load `android-skills:datastore` when implementing.
