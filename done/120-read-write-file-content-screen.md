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

- [x] New tests above pass; existing suite green with no behavior-test
      changes.
- [x] Observable on device: a Write row shows `N lines · size` (+ "append"
      when applicable) and tapping it shows the written content full-screen;
      a ranged Read row shows its `lines a–b` chip and tapping shows the
      extract with line numbers.
- [x] Tapping a still-running Read row shows the placeholder, not a crash
      or an empty screen.
- [x] Same navigation from the sub-agent page's tool rows.
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

## Implementation notes (2026-09-01)

- `ToolContentStore` entries widened to `Entry(payload, output = "")` — the Read
  viewer's content is the call's OUTPUT at tap time, and the later result viewers
  (121–123) need the same snapshot. `put(id, payload)` call sites keep compiling
  (defaulted param); `consume` now returns `Entry?`, so `EditDiffViewModel` unwraps
  `.payload` and `ToolContentStoreTest` had two assertions updated to compare
  `Entry(...)` — mechanical fixture updates, no behavior change (plus one additive
  output round-trip test).
- Payloads: `Read(path, offset: Int?, limit: Int?)` reads `line_offset`/`n_lines`
  via `intOrNull` (a string-typed number still parses — tolerant like the rest of
  the args handling); `Write(path, content, append)` with `mode: "append"` →
  `append = true` and empty content legitimate. Chip derivations live on the
  payload: `Read.rangeLabel` ("lines 40–139"; limit-only → "lines 1–50";
  offset-only → "lines 40–end" — the ticket only pinned the both-present case) and
  `Write.lineCount`/`byteSize` (UTF-8 bytes; the row assembles the label with
  `Formatters.formatFileSize`, the existing size helper — so "kB/MB/B", not the
  ticket's illustrative "KB").
- `ReadOutputs` (data/chat) lifts the trailing `<system>…</system>` block with
  `\s*<system>([\s\S]*?)</system>\s*$`; mid-text/unclosed/empty blocks leave the
  output untouched with no caption (covered by ReadOutputsTest).
- Screen: `FileContentRoute(toolCallId, entry, onNavigateBack)` picks Write
  content / Read lifted output / running placeholder (`output.isBlank()`) / miss
  state (a non-file payload under the id is equally a miss — EditDiffViewModel
  rule); `FileContentScreen(path, content, caption?)` is the shared body (basename
  + full path top bar, caption chip, verbatim monospace selectable block on
  `surfaceVariant`, 840dp cap). No ViewModel per the ticket: the `FileContent`
  destination consumes the store once into a `remember` (required — a bare call
  would re-consume to null on the next recomposition). Consequence: a rotation
  re-runs the composition on an already-consumed store and lands on the
  missing-content state, unlike EditDiff whose ViewModel survives rotation — the
  ticket's prescribed recovery (Back + re-tap) applies; flagged in case a later
  ticket wants a ViewModel or a rememberSaveable snapshot saver.
- Rows: document glyph `DocumentIcon` (Material Symbols "description" path data),
  Read range chip / Write `N lines · size` chip as plain monospace `onSurfaceVariant`
  text (the Edit stat chip's style), "append" as the "All" badge's
  `secondaryContainer` Surface. Tap precedence: question → Edit → Read/Write →
  generic expansion → sub-agent link → job link (file rows never carry the links).
  Unparseable Read/Write calls keep the generic table (payload null — e.g. a Read
  missing `path`); `ToolPreview` path preview untouched.
- Tests: ToolPayloadsTest +7 (Read/Write parsing incl. mode variants), new
  ToolPayloadTest (chip derivation) + ReadOutputsTest (lifting, clean/malformed);
  ToolRowTest +8 (chips, badge, tap/chevron navigation with the output snapshot,
  unparseable fallback); new FileContentScreenTest (5: Write content, Read caption
  lift, malformed block untouched, running placeholder, miss + Back); RoutesTest +1.
  Existing behavior tests unchanged apart from the ToolContentStoreTest fixture
  updates listed above.
- Gradle (`flock … :app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest --max-workers=1 --no-daemon`): BUILD SUCCESSFUL,
  unit suite green (51 suites, 0 failures).
- Firebase Test Lab (MediumPhone.arm, API 36): 41/41 passed —
  FileContentScreenTest (5), ToolRowTest (28), ToolRowExpandScrollTest (3),
  EditDiffScreenTest (5).
  Matrix: https://console.firebase.google.com/project/kimi-code-android-client/testlab/histories/bh.a7586a76d07966a/matrices/6836904260472297117
- The release APK publication is deferred to the merge session per the worktree
  hand-off, hence the unticked half of the last box.
