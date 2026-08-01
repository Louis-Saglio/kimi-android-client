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

- [x] No `okhttp3.mockwebserver` imports remain; legacy artifact gone from
  the dependency graph (`./gradlew :app:dependencies` spot-check).
- [x] `./gradlew testDebugUnitTest` green; ~~androidTest green on Firebase
  Test Lab~~ — androidTest **compile** proven (`assembleDebugAndroidTest`);
  on-device execution pending Test Lab quota (~07:00 UTC reset, parent runs
  it from main).
- [ ] Release APK published to the file server per `AGENTS.md` — pending
  (parent publishes from main post-merge).

## Landed (2026-08-01, worktree agent/mockwebserver3)

- Coordinate swapped: `com.squareup.okhttp3:mockwebserver3` (same `okhttp`
  version ref) in catalog + `app/build.gradle.kts` (test + androidTest).
- 15 test files migrated mechanically: imports to `mockwebserver3.*`,
  `MockResponse().setBody(x)` → `MockResponse(body = x)`, `path` →
  `target`, `setBodyDelay` → `newBuilder().bodyDelay().build()`. No
  assertion changes.
- `withWebSocketUpgrade` has no direct equivalent on the immutable
  `MockResponse`: the 9 call sites now go through
  `testutil/webSocketUpgradeResponse` (`MockResponses.kt` —
  `MockResponse.Builder().webSocketUpgrade(l).build()`), keeping every
  call-site shape (and the custom `Dispatcher` in `ChatViewModelTestBase`)
  structurally identical.
- Verification: `testDebugUnitTest` + `assembleRelease` +
  `assembleDebugAndroidTest` green (6m 33s); dependency-graph spot-check
  shows only `mockwebserver3:5.4.0`, zero legacy references.
- Process note: the delegated agent was OOM-killed (06:08 kimi-web.service
  restart) mid-migration; the parent completed the leftover API sites
  (`withWebSocketUpgrade` ×9, `setBody`, `path` ×3, `setBodyDelay`), ran
  the gates, and merged.

## Out of scope

- Adopting `mockwebserver3-junit4`/`@StartStop` lifecycle annotations — keep
  the existing explicit start/stop pattern unless a file already wants it.
- Any production-code change — none should be needed.

## Notes

- Changelog with the full breaking-change list:
  https://square.github.io/okhttp/changelogs/changelog/ (5.0.0 and
  5.0.0-alpha.17 entries).

## Verification update (2026-08-01, post-merge)

- Firebase robo **Passed** (MediumPhone.arm, API 36) on merged main.
- Instrumented androidTest **42/42 passed** (MediumPhone.arm, API 36),
  including `SettingsFlowTest` on mockwebserver3 on-device.
- Release APK published to the file server (`kimi-android-client-main.apk`).
