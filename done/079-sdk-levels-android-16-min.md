# SDK levels: minSdk 36 (Android 16+), compileSdk 37, targetSdk 36, jvmTarget 21

- **ID:** 079
- **Created:** 2026-07-31
- **Depends on:** 075

## Context

Second slice of the 2026-07-31 dependency modernization program. Louis's
requirement: "support only Android 16 and up" → `minSdk = 36` (Android 16 =
API 36). Android 17 (API 37) shipped 2026-06-16, so the latest compile target
is 37, which the AGP 9.3.1 from 075 supports.

Decision (2026-07-31 investigation): **targetSdk stays 36, not 37.** Targeting
37 opts into Android 17 behavior changes; the one that matters here is the
mandatory `ACCESS_LOCAL_NETWORK` runtime permission for LAN traffic. The app's
default endpoint is a Tailscale IP in 100.64.0.0/10 (CGNAT), and the official
docs do not enumerate whether CGNAT space is classified as "local network" —
if it is, targeting 37 without the permission breaks the app's connection
with timeouts. compileSdk 37 + targetSdk 36 runs fine on Android 16/17 with
zero behavior-change exposure. Bumping targetSdk to 37 is 080, gated on
verifying the Tailscale-endpoint question.

Other target-37 changes reviewed and irrelevant to this app: large-screen
orientation/aspect-ratio enforcement (the manifest locks no orientation),
OTP SMS delays, contacts PII restrictions, background-audio restrictions,
lock-free `MessageQueue`, blocked `static final` reflection.

No `Build.VERSION_CODES`/`SDK_INT` conditionals exist anywhere in `app/src`
(verified 2026-07-31), so the minSdk bump is purely declarative — no code
simplification follows from it.

## Landed (2026-07-31)

- `app/build.gradle.kts`: compileSdk 35 → 37, minSdk 31 → 36, targetSdk
  35 → 36 (37 deliberately deferred to 080), Java 17 → 21
  (`sourceCompatibility`/`targetCompatibility`/`jvmTarget`).
- SDK install: the VPS `sdkmanager` (cmdline-tools 19.0) can't parse the
  current repository XML (v4) and doesn't see `platforms;android-37` —
  skipped it and let AGP 9.3.1's own SDK manager auto-install
  `$ANDROID_HOME/platforms/android-37.0` at build time. Recorded in
  `administration.md`.
- Merged manifest verified: `minSdkVersion="36" targetSdkVersion="36"`.
- No source changes — the codebase has no `SDK_INT`/`VERSION_CODES`
  conditionals, and nothing at these levels needed adaptation.
- Verification: `testDebugUnitTest` + `assembleRelease` green; Firebase robo
  **Passed** on MediumPhone.arm API 36; `kimi-android-client-main.apk`
  (3.8 MB) on the file server.

## Description

In `app/build.gradle.kts`:

1. `compileSdk = 35 → 37` (install the API 37 SDK + build-tools on the VPS:
   `$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-37"
   "build-tools;36.0.0"` or newer).
2. `minSdk = 31 → 36`.
3. `targetSdk = 35 → 36` (deliberately not 37 — see Context).
4. `jvmTarget` / `sourceCompatibility` / `targetCompatibility` 17 → 21 (the
   toolchain is already JDK 21; Kotlin 2.4 supports JVM target 21; renderer
   0.42+ ships Java 21 bytecode anyway — see 077).
5. Update `AGENTS.md` (minSdk/targetSdk/compileSdk + JDK references).

## Acceptance criteria

- [x] `app/build.gradle.kts` shows compileSdk 37, minSdk 36, targetSdk 36,
  Java 21.
- [x] `./gradlew testDebugUnitTest` + `assembleRelease` green.
- [x] Firebase robo green on an API 36 (or newer) device, e.g.
  `--device model=MediumPhone.arm,version=36,locale=en,orientation=portrait`.
- [x] Release APK published to the file server per `AGENTS.md`.
- [x] `AGENTS.md` updated.

## Out of scope

- `targetSdk = 37` + `ACCESS_LOCAL_NETWORK` handling → 080.
- Library bumps that AGP 9's `minCompileSdk` enforcement may force alongside
  a higher compileSdk → 076/077 (this ticket only raises the ceiling).
- Adopting any Android 16/17 APIs — none needed.

## Notes

- AGP 9 behavior change to keep in mind: libraries may now force consumers to
  compile at their own compileSdk (`AarMetadata.minCompileSdk`). If a
  dependency in 076/077 demands compileSdk 37, landing 079 first satisfies it.
