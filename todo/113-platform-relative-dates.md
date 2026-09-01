# Platform relative dates for all date displays

- **ID:** 113
- **Created:** 2026-09-01
- **Depends on:** none

## Context

Product discussion with Louis (2026-09-01). Session list rows show the full
locale short date + time (`09/01/26, 2:32 PM`) — hard to scan; Louis wants
human-friendly relatives like "Yesterday". Two explicit decisions:

- Do **not** hand-roll a humanize function. Use the platform utility,
  `android.text.format.DateUtils.getRelativeTimeSpanString`, which respects
  the system locale and the user's 12/24-hour preference.
- Apply it to **all** date displays in the app, not just the session list.

`formatTimestamp` (`ui/common/Formatters.kt`) has exactly two call sites —
session list rows (`SessionsScreen.kt:338`) and chat bubble captions
(`Bubbles.kt:145`) — so it is the single place to change. Ticket 039's
web-parity argument for the short-date format is superseded by this decision.

## Description

- Reimplement `formatTimestamp` on
  `DateUtils.getRelativeTimeSpanString(instant.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)`.
  The framework owns the exact strings ("5 minutes ago", "Yesterday",
  "3 days ago", absolute date past a week) and their localization; we do not
  branch on the age ourselves. The no-`Context` overload is the right one —
  `Formatters.kt` stays free of Android-context plumbing.
- Both call sites keep calling `formatTimestamp` unchanged.
- No Robolectric: the project runs plain JUnit unit tests and
  `android.text.format.DateUtils` is a framework class. Keep the wrapper a
  one-liner; verification is on-device + the Firebase robo smoke test. Do not
  add a test dependency for this.

## Acceptance criteria

- [ ] `Formatters.kt` no longer contains the `DateTimeFormatter` web-style
  format (grep-verifiable); `formatTimestamp` delegates to `DateUtils`.
- [ ] On-device: a session updated yesterday shows "Yesterday" in the list; a
  session updated minutes ago shows a relative span; chat bubble captions use
  the same relative style.
- [ ] Switching the device between English/French (or 12/24h) changes the
  output accordingly — framework-driven, no app code involved.
- [ ] `./gradlew :app:assembleDebug` succeeds with all unit tests green (VPS
  rules: `flock /tmp/kimi-android-gradle.lock`, `--max-workers=1`,
  `--no-daemon` when parallel).

## Out of scope

- Changing *which* timestamp is displayed (rows keep `updatedAt`, bubbles keep
  `createdAt`).
- Relative-date rendering in future screens — they get it for free via
  `formatTimestamp`.
- Robolectric or screenshot-test infrastructure.

## Notes

- Key file: `app/src/main/java/fr/louissaglio/kimi/android/ui/common/Formatters.kt`.
- `DateUtils.getRelativeTimeSpanString(long, long, long)` works without an app
  context (uses system resources), so the helper stays callable from any
  composable.
