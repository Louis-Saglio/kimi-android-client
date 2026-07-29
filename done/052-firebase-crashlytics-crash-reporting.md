# Firebase Crashlytics crash reporting

- **ID:** 052
- **Created:** 2026-07-29
- **Depends on:** none

## Context

Louis (2026-07-29): the app sometimes crashes while he tests it on his
phone, and he can't investigate — the phone is not adb-connected to the
VPS, so crashes leave no logs and no stack trace anywhere he can reach.

Options discussed (Louis picked Crashlytics):

- **In-app crash log** (custom `Thread.UncaughtExceptionHandler` writing to
  a file, view/copy from Settings): zero dependencies, offline, paste-into-
  agent-chat workflow. Rejected as the sole mechanism: it only helps if
  Louis remembers to open Settings and copy manually after a crash, and it
  duplicates what Crashlytics already does better (thread dumps, device
  state, crash history, grouping).
- **Firebase Crashlytics** (chosen): the VPS already runs the
  `kimi-code-android-client` Firebase project for Test Lab, so no new
  account or backend is needed. Uncaught exceptions upload automatically
  with full stack traces and device context; Louis reads them in the
  Firebase console from his phone browser, and the agent can be pointed at
  reports too.
- **Both:** deferred — the in-app log adds nothing until Crashlytics proves
  insufficient (see Out of scope).

## Description

- Register the Android app (`fr.louissaglio.kimi.android`) in the
  `kimi-code-android-client` Firebase project (Firebase Management API, VPS
  service account) and commit the downloaded `app/google-services.json`
  (not a secret — it ships inside the APK anyway).
- Gradle wiring, all through the version catalog: plugins
  `com.google.gms.google-services` 4.5.0 and
  `com.google.firebase.crashlytics` 3.0.7 (declared `apply false` in the
  root build file), Firebase BoM 34.16.0, `firebase-crashlytics`
  dependency. No Kotlin code: the SDK auto-initializes via
  `FirebaseInitProvider` and reports uncaught exceptions on its own.
- `app/proguard-rules.pro`: `-dontwarn android.os.ProfilingTrigger` and
  `...$Builder` — BoM 34.16.0 references these API-36 platform classes,
  which are absent from compileSdk 35's android.jar and made R8 fail the
  release build. The call site is guarded by a runtime SDK check, so the
  classes are never touched on older devices.
- Release builds auto-upload the R8 mapping file
  (`uploadCrashlyticsMappingFileRelease` ran), so console stack traces are
  deobfuscated.
- AGENTS.md tech-stack section documents the integration and console URL.

## Acceptance criteria

- [x] `./gradlew :app:assembleDebug`, `:app:assembleRelease` and
  `:app:testDebugUnitTest` all green with the plugins applied.
  *(2026-07-29: green; release R8 first failed on the missing
  `ProfilingTrigger` classes, fixed by the dontwarn rules above; the
  Crashlytics mapping upload task ran in the successful release build.)*
- [x] Firebase Test Lab robo smoke test passes on the release APK
  (MediumPhone.arm, API 34, 60 s) — proves `google-services.json` is
  correct, since a bad config crashes FirebaseInitProvider at startup.
  *(2026-07-29: passed, matrix 4834216024204952148.)*
- [x] Release APK published to the file server per AGENTS.md
  *(~3.5 MB; the SDK added ~0.6 MB over the previous ~2.9 MB).*
- [ ] On-device (Louis): the next real crash appears in
  https://console.firebase.google.com/project/kimi-code-android-client/crashlytics
  with a deobfuscated stack trace.
  *(Only verifiable when a crash actually happens; not shipping a test
  crash trigger for this.)*

## Out of scope

- Breadcrumbs / custom keys (`FirebaseCrashlytics.log`, `setCustomKey`) and
  `recordException` for handled errors (e.g. WebSocket drops, failed
  resyncs) — add when a concrete debugging need shows up.
- The rejected in-app crash-log viewer — revisit only if console access
  proves impractical from the phone.
- ANR reporting (Play Console territory; the app is not distributed via
  Play).
- Bumping compileSdk to 36 so the dontwarn rules can go away — do it with
  the next AGP bump instead.

## Notes

- Firebase Android app id: `1:000000000000:android:0000000000000000`.
- The Crashlytics dashboard self-enables on the first SDK connection — no
  console-side setup was needed.
- `google-services.json` was fetched with
  `GET https://firebase.googleapis.com/v1beta1/projects/kimi-code-android-client/androidApps/<appId>/config`
  (base64 payload), authenticated with the VPS service account.
