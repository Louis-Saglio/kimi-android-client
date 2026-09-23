# Turn file before/after viewer

- **ID:** 150
- **Created:** 2026-09-23
- **Depends on:** 149

## Context

`GET /api/v1/sessions/{id}/file-history/content?turn_id=N&path=<p>[&phase=
"start"|"end"]` → `{content:{version, content?, binary?} | null}`
(docs/server-api.md:2742-2744; schema-verified at 0.41.0). Ticket 149
lists a turn's changed files; this ticket shows one file's before/after
content — the phone-side answer to "what exactly did that Edit change?".

## Description

**1. Data layer.** `FileHistoryApi.getContent(sessionId, turnId, path,
phase)` → `FileContent(version, content?, binary?)`, nullable `content`
(when the file did not exist at that phase). Repository wraps with the
`DataError` boundary.

**2. Viewer screen.** Opened from 149's row tap: file name in the title,
a "Before / After" tab toggle (start/end; default After). Body renders
the text in the monospace content idiom of 120's read screen; a null
side renders "File did not exist yet" / "File deleted" instead of empty
code. `binary:true` renders a "Binary file" placeholder, no content
fetch beyond the one already made. Two requests fired in parallel on
open (start + end), error state with retry per side.

**3. Tests.** Repository mapping tests: both phases, `content:null`,
`binary:true`; ViewModel toggle test; androidTest: renders both sides and
the null-side placeholder.

## Acceptance criteria

- [ ] Unit + androidTests above pass; existing suite green.
- [ ] On device: tapping a modified file in 149's list shows its
      before/after; an added file shows "File did not exist yet" on the
      Before tab.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Inline diff highlighting between the two versions (a real diff view is
  a later ticket; this is the before/after pair).
- Image/binary preview.
- Sharing or copying the content (120 may already have copy — reuse, do
  not rebuild).

## Notes

- `path` is a server-local absolute path; URL-encode it as a query param.
- `version` is the file-history snapshot version; display it nowhere,
  just log it for debugging.
