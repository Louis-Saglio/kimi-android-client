# Library batch: Compose BOM 2026.06.01, androidx, Hilt, coroutines, serialization, Firebase

- **ID:** 076
- **Created:** 2026-07-31
- **Depends on:** 075, 079

## Context

Third slice of the 2026-07-31 dependency modernization program: the routine
stable bumps. All are catalog-only changes in `gradle/libs.versions.toml` —
no API changes expected at these version distances for this app's usage.
Ordered after 079 because AGP 9 requires consumers to compile at each
library's own compileSdk (`AarMetadata.minCompileSdk`), and current androidx
releases are built against compileSdk 36/37.

Versions verified against Google Maven / Maven Central on 2026-07-31
(re-verify at implementation time; stable only, no alpha/beta/RC):

| Library | From | To |
|---|---|---|
| composeBom | 2025.11.00 | 2026.06.01 (compose-ui 1.11.4, material3 1.4.0) |
| coreKtx | 1.13.1 | 1.19.0 |
| activityCompose | 1.9.0 | 1.13.0 |
| lifecycle | 2.8.3 | 2.11.0 |
| navigationCompose | 2.7.7 | 2.9.8 (2.10.0 still beta) |
| hilt | 2.57.2 | 2.60.1 — **already landed in 075** (forced: 2.57.2's Gradle plugin is AGP-9-incompatible) |
| hiltNavigationCompose | 1.2.0 | 1.4.0 |
| coroutines | 1.7.3 | 1.11.0 |
| kotlinxSerialization | 1.7.1 | 1.11.0 |
| datastore | 1.1.1 | 1.2.1 (1.3.0 still alpha) |
| firebaseBom | 34.16.0 | 34.17.0 |
| testExtJunit | 1.2.1 | 1.3.0 |

Already latest, verify-and-leave: junit 4.13.2, googleServices 4.5.0,
firebaseCrashlyticsPlugin 3.0.7.

Deliberately NOT here: markdown renderer (077 — ticket 073 reserved that
ladder rung its own ticket), OkHttp (078 — major version, isolated for
regression bisecting).

## Landed (2026-08-01)

- All 11 catalog bumps landed in one pass: composeBom 2026.06.01
  (compose-ui 1.11.4; material3 stays 1.4.0 — that IS the newest stable),
  coreKtx 1.19.0, activityCompose 1.13.0, lifecycle 2.11.0,
  navigationCompose 2.9.8, hiltNavigationCompose 1.4.0, coroutines 1.11.0,
  kotlinxSerialization 1.11.0, datastore 1.2.1, firebaseBom 34.17.0,
  testExtJunit 1.3.0. Hilt 2.60.1 had already landed in 075.
- Versions re-verified against Google Maven / Maven Central at
  implementation time — unchanged from the 2026-07-31 investigation.
- **Zero compile breaks, zero source changes.** No new warnings beyond the
  two pre-existing deprecations (`LocalClipboardManager` in `Bubbles.kt`,
  `menuAnchor()` in `SessionsScreen.kt`) — still deprecation-level at
  compose-ui 1.11.4 / material3 1.4.0, left alone (out of scope).
- junit 4.13.2, googleServices 4.5.0, firebaseCrashlyticsPlugin 3.0.7
  verified already-latest.
- Verification: `testDebugUnitTest` + `assembleRelease` green (single 5 min
  serialized run); Firebase robo **Passed** (MediumPhone.arm, API 36);
  `kimi-android-client-main.apk` (3.9 MB) on the file server.

## Description

1. Bump the versions above in `gradle/libs.versions.toml`.
2. Fix any compile breaks (none expected; material3 1.4 is already in use via
   BOM 2025.11.00, and the 073 migration to `PullToRefreshBox` is done).
3. Update `AGENTS.md` tech-stack versions (Compose BOM note, Hilt note about
   KSP2 crashes stays as history but with the new version).

## Acceptance criteria

- [x] Catalog shows the versions above.
- [x] `./gradlew testDebugUnitTest` + `assembleRelease` green.
- [x] Firebase robo green.
- [x] Release APK published to the file server per `AGENTS.md`.
- [x] `AGENTS.md` updated.

## Out of scope

- Markdown renderer 0.43.0 → 077; `StreamingMarkdownState` adoption → 082.
- OkHttp 5 → 078; mockwebserver3 → 081.
- Any behavior/UX change from the new library versions (e.g. new material3
  components) — bump only.

## Notes

- If one library in the batch breaks the build in a non-trivial way, split it
  out, land the rest, and record the blocker here rather than holding the
  whole batch.
