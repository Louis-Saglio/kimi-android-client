# OkHttp 4.12.0 → 5.4.0 (legacy mockwebserver kept)

- **ID:** 078
- **Created:** 2026-07-31
- **Depends on:** 075

## Context

Fourth slice of the 2026-07-31 dependency modernization program. OkHttp 5 is
the first stable major since 2023 (5.0.0 in 2025-07, 5.4.0 in 2026-06, per
the official changelog). Kept as its own ticket — a major version of the
app's only network stack deserves an isolated, bisectable commit.

Migration surface is small by design:

- Main code uses 13 `okhttp3` imports (`OkHttpClient`, `Request`,
  `Interceptor`, `WebSocket`, `WebSocketListener`, `HttpUrl`, `MediaType`,
  `MultipartBody`, `RequestBody`, `Response`) — all stable APIs in OkHttp 5,
  source-compatible with this usage. Known deltas: `Response.body` is now
  non-null (source-compatible), the WebSocket client was reimplemented in
  5.2.0 behind the same API, OkHttp ships as JVM+Android variants via Gradle
  module metadata (automatic).
- Tests (11 files) use `okhttp3.mockwebserver.*`. The legacy
  `com.squareup.okhttp3:mockwebserver` artifact is **still published at
  5.4.0** (verified 2026-07-31), marked obsolete but maintained — so this
  ticket changes no test code. Migrating to `mockwebserver3` is 081.
- OkHttp 5 requires Android API 23+ — irrelevant at minSdk 36 (079).

Expected benefits relevant to this app: Happy Eyeballs dual-stack connect,
interceptor/timeout fixes, and years of HTTP/2 + WebSocket bug fixes.

## Description

1. `okhttp = "4.12.0" → "5.4.0"` in `gradle/libs.versions.toml` (both
   `okhttp` and `mockwebserver` coordinates ride the same ref — keep the
   legacy `mockwebserver` artifact, do not switch to `mockwebserver3` here).
2. Compile; fix any source breaks (none expected per the API review above).
3. Pay attention to WebSocket behavior in the unit suite —
   `ChatViewModelTestBase` runs the real `SessionSocket` against
   MockWebServer, so reconnect/resume regressions should surface there.
4. Re-verify the version is still the newest stable at implementation time.

## Acceptance criteria

- [ ] Catalog at OkHttp 5.4.0, legacy `mockwebserver` coordinate unchanged.
- [ ] `./gradlew testDebugUnitTest` green (the MockWebServer chat/session
  harnesses are the regression net).
- [ ] androidTest green on Firebase Test Lab (`SettingsFlowTest` uses
  MockWebServer on-device) + robo.
- [ ] Real-server smoke check: release APK connects to the live server,
  streams a chat turn over WebSocket, resumes after backgrounding. (Louis on
  the published APK.)
- [ ] Release APK published to the file server per `AGENTS.md`.

## Out of scope

- Migrating tests to `mockwebserver3` (immutable `MockResponse`,
  `path`→`target`, no auto-start semantics) → 081.
- Adopting OkHttp 5 features (zstd, new EventListener decisions,
  `Request.toCurl()`, interceptor client overrides) — none needed.
- `okhttp-coroutines` artifact — not used today.

## Notes

- Changelog: https://square.github.io/okhttp/changelogs/changelog/ (5.0.0
  entry documents the coordinate split and the "old artifact still
  published" policy).
