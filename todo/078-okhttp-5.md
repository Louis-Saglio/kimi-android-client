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

- [x] Catalog at OkHttp 5.4.0, legacy `mockwebserver` coordinate unchanged.
- [x] `./gradlew testDebugUnitTest` green (the MockWebServer chat/session
  harnesses are the regression net). — 306 tests, 0 failures/errors, 0 skipped.
- [ ] androidTest green on Firebase Test Lab (`SettingsFlowTest` uses
  MockWebServer on-device) + robo. — Not run: Test Lab daily quota was
  exhausted at implementation time; deferred to post-merge verification.
- [ ] Real-server smoke check: release APK connects to the live server,
  streams a chat turn over WebSocket, resumes after backgrounding. (Louis on
  the published APK.) — Pending Louis.
- [ ] Release APK published to the file server per `AGENTS.md`. — Not done in
  the worktree (publishing happens from main after merge, per session scope).

## Out of scope

- Migrating tests to `mockwebserver3` (immutable `MockResponse`,
  `path`→`target`, no auto-start semantics) → 081.
- Adopting OkHttp 5 features (zstd, new EventListener decisions,
  `Request.toCurl()`, interceptor client overrides) — none needed.
- `okhttp-coroutines` artifact — not used today.

## Landed (2026-08-01, worktree agent/okhttp-5)

- `okhttp = "4.12.0" → "5.4.0"` in `gradle/libs.versions.toml` — the only
  change; both `okhttp` and legacy `mockwebserver` coordinates ride the same
  ref. Re-verified 5.4.0 as newest stable on Maven Central (metadata
  lastUpdated 2026-06-08) and legacy `mockwebserver:5.4.0` still published.
- Zero source/test changes: all 13 `okhttp3` imports and the 11
  `okhttp3.mockwebserver.*` test files compiled unchanged against 5.4.0, as
  predicted by the API review.
- `testDebugUnitTest` green: 306 tests, 0 failures/errors/skips — includes
  the `ChatViewModelTestBase` real-`SessionSocket`-vs-MockWebServer
  reconnect/resume harnesses, so the 5.2.0 WebSocket client reimplementation
  shows no regression at unit level.
- `assembleRelease` green (R8 + resource shrinking, no new keep rules
  needed), Crashlytics mapping uploaded. First attempt was killed mid-R8 by a
  sibling agent's `./gradlew --stop` (the per-user stop clobbers parallel
  `--no-daemon` builds in other worktrees); retried after 60 s, passed.
- Runtime note: legacy `mockwebserver:5.4.0` now delegates to `mockwebserver3`
  internally (its own transitive dep) while keeping the `okhttp3.mockwebserver`
  package — the 081 migration remains a pure coordinate/API rename.

## Notes

- Changelog: https://square.github.io/okhttp/changelogs/changelog/ (5.0.0
  entry documents the coordinate split and the "old artifact still
  published" policy).
