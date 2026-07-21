# Switch app authentication from HTTP Basic to bearer token

- **ID:** 018
- **Created:** 2026-07-21
- **Depends on:** none

## Context

The Kimi Code server on the VPS no longer accepts HTTP Basic auth. Caddy's
`basic_auth` blocks in front of the web UI were removed and the server now
enforces its native bearer-token auth on all REST and WebSocket routes
(`--dangerous-bypass-auth` dropped from `kimi-web.service`; token in
`/home/ubuntu/.kimi-code/server.token`). The app still sends
`Authorization: Basic …` (`BasicAuthInterceptor`), so every request now gets
401 — the app is broken until this lands.

Decisions from the product discussion:

- The settings screen gets a **single token field** replacing the
  username + password pair. This matches the new auth model exactly; keeping
  two fields and repurposing `password` was rejected as confusing UX for a
  ~30-line saving. Existing stored credentials are simply dropped — Louis
  pastes the token once.
- Doc alignment (`docs/server-api.md` auth section, repo `AGENTS.md`) is part
  of this ticket so no document contradicts the code after it lands.

Basic auth remains in use only by the Caddy file server
(`https://agent.example.com`), which the app does not talk to.

## Description

Replace Basic auth with bearer-token auth throughout the app:

- `ServerSettings`: replace `username`/`password` with a single `token`
  field; `hasCredentials` becomes `token.isNotBlank()`.
- `SettingsRepository`: store one `token` preference instead of the two
  credential keys (dropping the old keys is fine — no migration).
- Rename `BasicAuthInterceptor` to `BearerTokenInterceptor` (or similar) and
  send `Authorization: Bearer <token>` instead of `Credentials.basic(...)`.
  Keep the existing "don't override an explicit Authorization header" and
  "skip when no token" behaviors.
- `ConnectionChecker`: send the Bearer header the same way.
- `SettingsScreen` / `SettingsViewModel`: one "Token" password-style field
  instead of username + password; keep the connection-test flow, adjusting
  the "wrong username or password" wording.
- `AppModule`: update wiring for the renamed interceptor.
- Update the tests that encode the old behavior, including
  `SettingsFlowTest.kt` which asserts `Authorization: Basic bG91aXM6cHcxMjM=`.
- Update `docs/server-api.md` (auth sections: REST and the WebSocket note)
  and the repo `AGENTS.md` connection description to bearer-token auth.

## Acceptance criteria

- [x] `./gradlew :app:testDebugUnitTest` passes, with a unit test asserting
  the interceptor emits `Authorization: Bearer <token>` and no test
  references Basic auth anymore.
- [x] The settings screen shows a single token field (no username field) and
  persists it across app restarts.
- [x] With the token from `/home/ubuntu/.kimi-code/server.token` entered, the
  connection test succeeds against `https://agent.example.com:8443` and
  `http://100.64.0.1`; with a wrong token it reports an authentication
  failure.
- [x] Session list and chat (REST + WebSocket live streaming) work end to
  end against the live server with the token configured.
- [x] `grep -ri "basic" docs/server-api.md AGENTS.md` shows no remaining
  claim that the Kimi server API uses Basic auth.

## Completion notes (2026-07-21)

- The server-side switchover had silently failed (flag removed from
  `kimi-web.service` at 10:28 but never restarted; the 10:34 systemd-run
  attempt referenced a missing script). Completed via
  `/home/ubuntu/kimi-web-bearer-switchover.sh`; the restart also brought the
  server to 0.28.1. Verified: `/meta` + `/sessions` → 401 no/wrong token,
  200 with token on loopback/Tailscale/public HTTPS; WS handshake 401/101.
- **`GET /api/v1/healthz` is public** (always 200), so the connection test
  probes `GET /api/v1/meta` instead — otherwise a wrong token would never be
  detected. `docs/server-api.md` corrected to match.
- Firebase Test Lab: 7/7 instrumented tests passed (MediumPhone.arm, API 34),
  including the token-field restore-after-recreation test.

## Out of scope

- Encrypted credential storage (already deferred in ticket 003).
- Token rotation / multiple-token management UX.
- Any server-side or Caddy changes (already done on 2026-07-21, see
  `/home/ubuntu/administration.md`).
- The Caddy file server, which keeps Basic auth.

## Notes

- The token is currently delivered via the `#token=` URL fragment sent to
  Louis on Telegram; manual paste into settings is the intended flow.
- Relevant files: `data/network/BasicAuthInterceptor.kt`,
  `data/network/ConnectionChecker.kt`, `data/settings/ServerSettings.kt`,
  `data/settings/SettingsRepository.kt`, `ui/settings/SettingsScreen.kt`,
  `ui/settings/SettingsViewModel.kt`, `di/AppModule.kt`,
  `app/src/androidTest/.../SettingsFlowTest.kt`.
