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

## Implementation notes (2026-09-23)

- **Entry-point deviation (the ticket's premise vs the code):** the app has no
  "turn header overflow" — the chat timeline renders message bubbles and tool
  groups, no turn headers, and no `ChatItem` carries a turn id (`/messages`
  history carries none; only the sub-agent page is transcript-backed, and its
  flattened rows drop the id). `usage.turn_count` on the session object is zeroed
  server-side (verified live 2026-09-23), so it cannot seed one either. The
  affordance therefore lives in the chat top-bar overflow — alongside "Compact
  session"/"Fork session", the app's existing turn/session actions — as "Files
  changed", and resolves the session's NEWEST main turn at tap time from
  `GET .../transcript?agent_id=main&page_size=5` (transcript ordinals ARE the
  file-history `turn_id` numbering; the existing mapper filters markers). A fresh
  session snackbars "No turns yet". Per-turn timeline affordances stay impossible
  without turn-boundary data in `/messages`.
- `FileHistoryApi` is the new data-layer collaborator (the facade-delegate rule):
  `getChanges(sessionId, turnId)` → domain `TurnFileChanges(changes, recorded)` /
  `FileChange(path, status, additions, deletions, binary, oversize)`; the closed
  wire status enum gains `Other` for unknown values (the app's ignore-unknown
  rule). `turnId` rides the typed route as an Int (the transcript ordinal's type).
- The screen splits `TurnChangesRoute` (stateful) / `TurnChangesScreen`
  (stateless, `internal`) — the FileContent precedent — so the androidTest drives
  rows without Hilt or network. `binary`/`oversize` rows show their note in place
  of the counts and carry no click action. Normal row taps report through
  `onOpenFile`, the ticket-150 hook; nothing wires it yet.
- Tests: `FileHistoryApiTest` (documented shape incl. a `binary:true` row,
  `recorded:false`, 40401), `TurnChangesViewModelTest` (empty state),
  `TurnChangesScreenTest` (badges/counts, binary row not clickable + the tap
  hook, empty state). androidTests compile-checked only — no emulator on this
  machine.
