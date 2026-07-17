# Jetpack Compose app foundation

- **ID:** 002
- **Created:** 2026-07-17
- **Depends on:** none

## Context

The project is a fresh skeleton: one `MainActivity` showing a `TextView`, zero
dependencies, minSdk 31 / targetSdk 35. The agreed stack for the clone is native
Kotlin + Jetpack Compose (chosen over plain Views/XML — less boilerplate for a
streaming chat UI), following the house Android baseline: MVVM with an
MVI-style `UiState`/`Effect` split, Hilt + KSP for DI, coroutines/Flow,
`kotlinx.serialization`.

## Description

Convert the `app` module to a Compose app:

- Gradle: Compose BOM, Material 3, `activity-compose`, `lifecycle-runtime-compose`,
  `lifecycle-viewmodel-compose`, `navigation-compose`, Hilt (+ KSP), coroutines.
  Enable the Compose compiler; keep existing SDK levels and Java 17.
- `Application` class annotated `@HiltAndroidApp` (register in manifest).
- `MainActivity` (`@AndroidEntryPoint`) with `setContent`.
- Material 3 theme following system light/dark.
- A `NavHost` with a single placeholder "Sessions" route: app bar titled
  "Kimi Code" and empty-body text. This screen is replaced by ticket 004.

## Acceptance criteria

- [ ] `./gradlew :app:assembleDebug` succeeds.
- [ ] App launches on an emulator/device showing the placeholder Sessions screen;
  dark theme follows the system setting.
- [ ] Firebase Test Lab robo smoke test passes:
  `gcloud firebase test android run --app app/build/outputs/apk/debug/app-debug.apk --type robo --device model=MediumPhone.arm,version=34,locale=en,orientation=portrait --timeout 60s --project=kimi-code-android-client`
- [ ] KSP/Hilt graph compiles; no reflection-fallback warnings in build log.

## Out of scope

Networking, settings, real screens, any repository code, unit-test infrastructure
beyond what the toolchain gives for free.

## Notes

- Required env for builds (already in `~/.bashrc`):
  `JAVA_HOME=/home/ubuntu/tools/jdk-21.0.7+6`, `ANDROID_HOME=/home/ubuntu/tools/android-sdk`.
- Load `android-skills:compose` and `android-skills:android-gradle-logic` when
  implementing.
