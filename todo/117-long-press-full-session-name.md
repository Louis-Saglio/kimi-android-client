# Long-press reveals full session name

- **ID:** 117
- **Created:** 2026-09-01
- **Depends on:** none

## Context

Product discussion with Louis (2026-09-01). Session names are ellipsized to
one line in the list with no way to read the full name. Offered options were
two-line rows, long-press reveal, or both; Louis chose long-press — rows stay
compact at one line.

## Description

- `SessionRow` moves from `clickable` to `combinedClickable`: tap keeps
  navigating, long-press opens a dialog.
- The dialog shows the session's full name as selectable text: the title when
  present, the full cwd path when untitled (mirroring the `displayName`
  fallback). Dismiss on tap-outside or an OK button; no actions inside.
- Applies to the row in both flat and grouped (115) modes — it is the same
  composable.

## Acceptance criteria

- [ ] Long-press on a row opens the dialog with the full, untruncated name
  (semantics test: `onLongClick` on the row, dialog text assertion); tap still
  navigates and never triggers the dialog.
- [ ] A titled session shows its title; an untitled one shows its full cwd.
- [ ] On-device: a long server-generated title (e.g. an auto-titled session)
  is fully readable via long-press.
- [ ] `./gradlew :app:assembleDebug` succeeds with all unit tests green (VPS
  rules: `flock /tmp/kimi-android-gradle.lock`, `--max-workers=1`,
  `--no-daemon` when parallel).

## Out of scope

- Two-line titles or marquee — rejected by Louis in favor of long-press.
- Actions inside the dialog (rename, archive) — not requested.
- The chat top bar's title truncation — separate surface, not requested.

## Notes

- Key file: `ui/sessions/SessionsScreen.kt` (`SessionRow`), dialog composable
  alongside it; `SessionRowTest` for the semantics.
- If the row layout changes under 114 before this lands, the long-press rides
  on whatever the row is then — no interaction between the two tickets beyond
  that file.
