# Turn file-changes list

- **ID:** 149
- **Created:** 2026-09-23
- **Depends on:** none

## Context

Turn file-history routes are live and ungated since server 0.41.0 (commit
`eba23edb`): `GET /api/v1/sessions/{id}/file-history/changes?turn_id=N`
→ `{changes:[{path, status:"added"|"modified"|"deleted", additions,
deletions, binary?, oversize?}], recorded}`; a turn with nothing recorded
answers `{changes:[], recorded:false}` (docs/server-api.md:2735-2741).
From the phone there is currently no answer to "what did the agent just
touch?" without reading every Edit/Write row. This is the natural sibling
of ticket 119's Edit-diff screen and follows the app's tool-row
navigation pattern.

## Description

**1. Data layer.** `FileHistoryApi.getChanges(sessionId, turnId)` →
domain model `FileChange(path, status, additions, deletions, binary,
oversize)` + `recorded`; repository wraps with the `DataError` boundary.
Only declared error is `40401` (unknown session).

**2. Entry point.** Completed turns in the chat timeline gain a "Files
changed" affordance in the turn header overflow (alongside the existing
turn actions): an icon + label that opens the changes screen for that
`turn_id`. Turn ids are already on the timeline items (sub-agent page and
transcript mapping, 046).

**3. Changes screen.** List rows: file name (basename) + parent path in
secondary text, status badge (added/modified/deleted), `+additions
−deletions` counts in the diff-stat idiom (119). `binary`/`oversize` rows
show a note and are not clickable. Empty state: "No file changes recorded
for this turn" (covers `recorded:false`). Tapping a normal row opens the
before/after viewer — ticket 150; until it exists the row can be inert
(keep the navigation hook).

**4. Tests.** Repository mapping test with the doc shape (incl.
`recorded:false` and a `binary:true` row); ViewModel test for the empty
state; androidTest: rows render status badge and counts.

## Acceptance criteria

- [ ] Unit + androidTests above pass; existing suite green.
- [ ] On device: a turn that edited files lists them with correct
      status and counts; a turn with no changes shows the empty state.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- The before/after content viewer — ticket 150 (depends on this one).
- Cross-turn file history / session-level "all changes" view.
- Opening the file in a full editor (120's screen is single-file content
  from tool args, not from file-history).

## Notes

- `turn_id` is the 0-based turn index already used by the transcript
  routes; reuse the timeline's existing turn numbering — do not invent a
  second id.
- `enabled` from the 0.40.1 source-derived shape does not exist in the
  shipped schema; parse only `changes` and `recorded`.
