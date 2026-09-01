# Read/Write tools: file content screen

- **ID:** 120
- **Created:** 2026-09-01
- **Depends on:** 119 (reuses `ToolPayload` and the `ToolContentStore`
  hand-off introduced there)

## Context

`Read` and `Write` are among the most frequent calls in a session, and
today both expand to raw JSON (`content` of a Write is the entire file body
inside a JSON string — escaped newlines and all). The 2026-09-01 design
(detail-level rule: content you'd scroll through goes to a screen) gives
both a shared full-screen file viewer, reusing ticket 119's payload +
store machinery so the slice stays small.

Field decisions (show signal, hide defaults):

- `Read`: row shows the path preview (existing) plus a `lines 40–139` chip
  only when `line_offset`/`n_lines` restrict the read; a full-file read
  gets no chip.
- `Write`: row shows `N lines · 1.2 KB` computed from `content`, plus an
  "append" badge only for `mode: append` (overwrite is the default — no
  badge).

## Description

**1. Payloads.** `ToolPayload.Read(path, offset, limit)` and
`ToolPayload.Write(path, content, append)` added to the 119 sealed type and
parser (`mode: "append"` → `append = true`).

**2. Rows.** Document icon (custom path-data icon per the `WorkspaceIcon`
precedent) for both. Read: line-range chip when restricted. Write: size
chip (lines = `content.lines().size`; bytes via `ui/common/Formatters` if a
size helper exists, else `%.1f KB`) and the append badge. Rows with a
parsed payload never expand inline — tap or trailing chevron opens the
content screen; unparseable rows keep the generic table.

**3. Screen.** One route `FileContent(toolCallId)`, content via
`ToolContentStore` (119's miss → error state applies). `TopAppBar`:
basename title, full path supporting line. Body:

- **Write:** the call's `content`, verbatim, monospace, in a vertically
  scrollable selectable text block.
- **Read:** the call's **output** (the file extract), verbatim monospace —
  the wire format already carries `line-number⇥content` prefixes, so
  verbatim keeps line numbers with zero parsing. If the trailing
  `<system>…</system>` status block parses cleanly (regex-anchored at the
  end), lift it into a caption chip above the content ("truncated", byte
  size, …); otherwise leave the text untouched. Tolerant fallback, never
  crash on shape drift.
- A Read row tapped while the call is still running (blank output) shows a
  "Call still running — no content yet" body instead of content. The screen
  is a snapshot taken at tap time; it does not live-update.

No ViewModel: the screen reads the store entry once, local
`rememberSaveable` for UI state (job-screen precedent minus polling).

**4. Tests.** Payload parsing (offset/limit absent vs present, mode
variants); chip/badge derivation (line range, size formatting, append);
system-block lifting (clean parse, malformed → untouched); row androidTest
(chips, tap navigates); screen androidTest (content renders, running-state
placeholder).

## Acceptance criteria

- [ ] New tests above pass; existing suite green with no behavior-test
      changes.
- [ ] Observable on device: a Write row shows `N lines · size` (+ "append"
      when applicable) and tapping it shows the written content full-screen;
      a ranged Read row shows its `lines a–b` chip and tapping shows the
      extract with line numbers.
- [ ] Tapping a still-running Read row shows the placeholder, not a crash
      or an empty screen.
- [ ] Same navigation from the sub-agent page's tool rows.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Syntax highlighting (revisit if plain monospace proves hard to read).
- Parsing the Read output's line numbers into a separate gutter.
- Editing or saving the file; opening the file at an OS level.
- Live-updating the screen while the call runs (snapshot semantics).

## Notes

- Keep the screen's body composable shared between the two payloads — one
  `FileContentScreen(path, content, caption?)` parameterized by the caller.
- Both row types keep the existing `ToolPreview` path preview unchanged;
  the chips are additions, not a preview rewrite.
