# Toolchain: Gradle 9.6.1 + AGP 9.3.1 + Kotlin 2.4.10 + KSP 2.3.10

- **ID:** 075
- **Created:** 2026-07-31
- **Depends on:** none

## Context

First slice of the 2026-07-31 dependency modernization program (Louis: "latest
stable version of all dependencies, latest Kotlin, Android 16 and up only").
This ticket is the foundation every other slice depends on: AGP 9.3.1 is the
first AGP line supporting compileSdk 37, and Kotlin 2.4 unlocks the markdown
renderer 0.42+ ladder rung (ticket 073's documented next step).

Investigation findings (2026-07-31, sources: developer.android.com AGP 9.0/9.3
release notes, JetBrains AGP-9 migration post, google/ksp releases, Maven
metadata):

- AGP 9.0 is a major version. The one change that bites this project:
  **built-in Kotlin is enabled by default** — applying
  `org.jetbrains.kotlin.android` now errors out ("Cannot add extension with
  name 'kotlin'"). This project is an easy migration: single module, no kapt,
  no `kotlinOptions`, no variant APIs, no custom build logic.
- AGP 9.3.1 (July 2026; 9.4.0 still alpha): max compileSdk 37, min Gradle
  9.5.0, min JDK 17 (we run JDK 21). New `optimization` DSL exists but the
  legacy `isMinifyEnabled`/`proguardFiles` DSL remains supported.
- Kotlin 2.4.10 is the newest stable (2.4.20 is beta). KSP switched to a
  decoupled versioning scheme: **2.3.10** is the current release and its
  changelog explicitly fixes Kotlin 2.4.0 module names and "R-class resolution
  when AGP 9 built-in Kotlin is enabled".
- Documented escape hatches if the migration hits a wall:
  `android.builtInKotlin=false` and `android.newDsl=false` in
  `gradle.properties` (valid until AGP 10, expected 2026). Prefer migrating
  properly; use the opt-out only as a recorded fallback.

Alternatives considered and rejected: staying on AGP 8.x (caps compileSdk at
35, blocks "Android 16 and up" and the whole program); jumping to AGP
9.4.0-alpha (program rule: stable only).

## Landed (2026-07-31)

- Gradle 8.9 → 9.6.1 (wrapper + `distributionSha256Sum` pin), AGP 8.6.1 →
  9.3.1, Kotlin 2.2.21 → 2.4.10, KSP 2.2.21-2.0.5 → 2.3.10.
- Built-in Kotlin migration landed clean — **no opt-out needed**:
  `org.jetbrains.kotlin.android` removed from both build files, KGP/KSP
  pinned via the root `buildscript { classpath(...) }` block, the app's
  `kotlin { compilerOptions { jvmTarget } }` block untouched and resolving.
  Compose/serialization/KSP/Hilt plugin applications all work on built-in
  Kotlin.
- Forced side-bump: **Hilt 2.57.2 → 2.60.1** — 2.57.2's Gradle plugin looks
  up the removed `BaseExtension` (`IllegalStateException: Android
  BaseExtension not found` in `HiltGradlePlugin.configureCompileClasspath`).
  Pulled forward from ticket 076; 076's table marked accordingly.
- OOM incident: the first parallel unit-test run was killed by the kimi-web
  cgroup OOM killer (CONSTRAINT_MEMCG, 5 GB MemoryMax; a java process at
  ~2.2 GB RSS). kimi-web.service itself was unaffected — the cap did its
  job. Everything rerun with `--max-workers=1`: green; the caution is now
  recorded in `AGENTS.md`.
- No source changes required. Warnings only: pre-existing Compose
  deprecations (`LocalClipboardManager`, `menuAnchor`) and new Kotlin 2.4
  "Expression is unused" warnings in chat test files — left alone (out of
  scope).
- Verification: `testDebugUnitTest` green; `assembleRelease` green (R8 +
  resource shrinking + lintVital + Crashlytics mapping upload); Firebase
  robo **Passed** (MediumPhone.arm, API 34); `kimi-android-client-main.apk`
  (3.9 MB) on the file server.

## Description

1. `./gradlew wrapper --gradle-version 9.6.1` (commit the regenerated wrapper
   files).
2. AGP 8.6.1 → 9.3.1 in `gradle/libs.versions.toml`.
3. Migrate to built-in Kotlin:
   - Remove `alias(libs.plugins.kotlin.android)` from both
     `build.gradle.kts` files (root + app).
   - Pin the newer Kotlin/KSP via the mechanism documented in the AGP 9.0
     release notes — a `buildscript { dependencies { classpath(...) } }` block
     in the **root** `build.gradle.kts`:
     `org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10` and
     `com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.10`.
   - Update `kotlin` and `ksp` in `libs.versions.toml` to 2.4.10 / 2.3.10 so
     the remaining plugin aliases (`kotlin-compose`, `kotlin-serialization`,
     `ksp`, `hilt`) resolve at those versions.
   - Verify the `kotlin { compilerOptions { jvmTarget } }` block in
     `app/build.gradle.kts` still resolves against the built-in-Kotlin
     `kotlin` extension; adjust per the AGP 9 migration guide if not.
4. Fix whatever AGP 9's new defaults/DSL tighten (likely candidates:
   `android.nonTransitiveRClass` property removal warning; nothing else
   expected — the project already sets `targetSdk` explicitly, uses
   `proguard-android-optimize.txt`, declares the androidx test runner).
5. If a plugin proves incompatible (compose/serialization/ksp/hilt on
   built-in Kotlin), fall back to `android.builtInKotlin=false` +
   `android.newDsl=false`, keep Kotlin 2.4.10/KSP 2.3.10 via the legacy
   `kotlin-android` plugin, and record the blocker in this ticket.
6. Keep `compileSdk`/`minSdk`/`targetSdk` unchanged here — that is 079.
7. Update `AGENTS.md` tech-stack section (versions + the built-in-Kotlin
   build structure) to match what landed.

## Acceptance criteria

- [x] `gradle-wrapper.properties` points at 9.6.1; `libs.versions.toml` shows
  AGP 9.3.1, Kotlin 2.4.10, KSP 2.3.10.
- [x] `./gradlew testDebugUnitTest` green (full suite, incl. the MockWebServer
  chat harnesses).
- [x] `./gradlew assembleRelease` green (R8 + resource shrinking).
- [x] Firebase robo green on the release/debug APK (command per `AGENTS.md`).
- [x] Release APK published to the file server per `AGENTS.md`.
- [x] `AGENTS.md` tech-stack section updated; if the built-in-Kotlin opt-out
  was used, the blocker is recorded in this ticket instead.

## Out of scope

- compileSdk/minSdk/targetSdk bumps → 079.
- All library version bumps (Compose BOM, androidx, Hilt, Firebase, …) → 076;
  markdown renderer → 077; OkHttp → 078.
- `jvmTarget` 17 → 21 → 079.

## Notes

- Build env on the VPS: `JAVA_HOME=/home/ubuntu/tools/jdk-21.0.7+6`,
  `ANDROID_HOME=/home/ubuntu/tools/android-sdk` (see `AGENTS.md`).
- `gradle.properties` caps the daemon at 1536 MB and the whole cgroup at 5 GB
  (MemoryMax on kimi-web.service) — Gradle 9 + AGP 9 are new code; watch the
  first builds for OOM and keep `org.gradle.workers.max=2`.
- Verify no newer **stable** AGP/Kotlin/KSP at implementation time; do not
  adopt alphas/betas/RCs.
