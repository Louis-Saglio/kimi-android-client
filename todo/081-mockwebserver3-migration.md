# Migrate tests from okhttp3.mockwebserver to mockwebserver3

- **ID:** 081
- **Created:** 2026-07-31
- **Depends on:** 078

## Context

078 moves to OkHttp 5 while keeping the legacy
`com.squareup.okhttp3:mockwebserver` artifact (`okhttp3.mockwebserver`
package), which Square still publishes but marks obsolete — it drags a JUnit
4 dependency and will eventually stop being released. This ticket finishes
the OkHttp 5 migration properly.

Known breaking changes in `mockwebserver3` (from the 5.0.0 changelog) that
touch this codebase's 11 MockWebServer test files:

- New package `mockwebserver3`; artifact
  `com.squareup.okhttp3:mockwebserver3` (no JUnit dep; the project's manual
  `server.start()`/`shutdown()` usage doesn't need the junit4/junit5
  integration artifacts).
- `MockResponse` is immutable with a `Builder` — `MockResponse().setBody(x)`
  style calls become `MockResponse(body = x)` / builder calls.
- `RecordedRequest.path` → `target`; `body` is now a nullable immutable
  `ByteString`; `sequenceNumber` → `exchangeIndex`.
- No auto-start: accessors like `port`/`url` throw unless `start()` was
  called — the test bases construct `MockWebServer()` as a field, so audit
  each for implicit-start reliance.
- `Dispatcher`/`SocketPolicy` rework (`SocketEffect`) —
  `ChatViewModelTestBase` uses a custom `Dispatcher` for the WebSocket
  upgrade; expect the most work there.

## Description

1. Replace the `mockwebserver` coordinate with `mockwebserver3` (same version
   ref as `okhttp`) in `gradle/libs.versions.toml` and
   `app/build.gradle.kts`.
2. Migrate imports and API usage in: `ui/chat/ChatViewModelTestBase.kt`,
   `ui/sessions/SessionListViewModelTest.kt`,
   `data/network/BearerTokenInterceptorTest.kt`,
   `data/network/ConnectionCheckerTest.kt`,
   `data/sessions/SessionsRepositoryTest.kt`,
   `SettingsFlowTest.kt` (androidTest), plus any other file the compiler
   flags.
3. No behavior changes to assertions — this is a mechanical API migration.

## Acceptance criteria

- [ ] No `okhttp3.mockwebserver` imports remain; legacy artifact gone from
  the dependency graph (`./gradlew :app:dependencies` spot-check).
- [ ] `./gradlew testDebugUnitTest` green; androidTest green on Firebase
  Test Lab.
- [ ] Release APK published to the file server per `AGENTS.md` (only if
  landed on a branch Louis runs).

## Out of scope

- Adopting `mockwebserver3-junit4`/`@StartStop` lifecycle annotations — keep
  the existing explicit start/stop pattern unless a file already wants it.
- Any production-code change — none should be needed.

## Notes

- Changelog with the full breaking-change list:
  https://square.github.io/okhttp/changelogs/changelog/ (5.0.0 and
  5.0.0-alpha.17 entries).
