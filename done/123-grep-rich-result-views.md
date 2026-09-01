# Grep: structured result views per output mode

- **ID:** 123
- **Created:** 2026-09-01
- **Depends on:** 118

## Context

`Grep` is the richest read-only tool — and the hardest: its output shape
depends on `output_mode` (`content` → `path:line:match` lines, possibly
context lines and `--` separators; `files_with_matches` → bare path list;
`count_matches` → `path:count`). Split from 122 to keep both slices under
the size rule. Louis approved output parsing with tolerant fallback
(2026-09-01); the grep output format is tool-implementation prose, so every
parser here degrades the WHOLE output to a verbatim block on any line that
doesn't fit — never a half-parsed mix.

Field decisions: the row carries the `pattern` (monospace, slash-delimited)
plus a scope chip (`path`, or `glob` when no path). The expansion shows
only non-default knobs as chips: `ignore case` (`-i`), `±N` context
(`-A`/`-B`/`-C` — the largest of them as ±N, or `+A`/`-B` when
asymmetric), `multiline`, `include hidden`, `type`. `head_limit`/`offset`
never earn UI space; `-n` defaults on in content mode → hidden.

## Description

**1. Payload.** `ToolPayload.Grep(pattern, path, glob, outputMode,
caseInsensitive, contextBefore, contextAfter, multiline, includeIgnored,
type)` on the 119 sealed type + parser tests (absent keys → defaults).

**2. Row.** Magnifier icon (reuse 122's); preview `/pattern/`; trailing
scope chip when `path` or `glob` is set.

**3. Expansion** (parsed payload replaces the generic table):

- Filter-chips row, rendered only when at least one non-default knob
  exists.
- Results by `outputMode`:
  - `content`: parse lines into per-file groups — a file-header row
    (`labelMedium`, `onSurfaceVariant`, the path) above monospace match
    lines with the line number dimmed at the left; context lines (no
    line-number match / `-` separators) render dimmed without a number;
    `--` group separators collapse to 4dp spacing. Cap the block's height
    with internal scroll (`ToolDetailBlock` recipe).
  - `files_with_matches`: plain path list (Glob's 122 component).
  - `count_matches`: rows of `path` + count badge (`labelMedium` on
    `surfaceVariant` pill).
- Whole-output verbatim fallback (shared `ToolDetailBlock`) the moment any
  line fails the mode's grammar.

**4. Tests.** One parser unit-test battery per mode (well-formed →
structured; one bad line → verbatim fallback; context separators; `-C`
context lines; count lines; empty output); chip derivation (defaults
hidden, non-defaults shown); row + expansion androidTests (grouped content
renders file headers, chips row absent for a default call, fallback path).

## Acceptance criteria

- [x] New tests above pass; existing suite green with no behavior-test
      changes (one additive fixture update, listed in the implementation
      notes).
- [ ] Observable on device: a content-mode Grep expands to per-file groups
      with dimmed line numbers; a `-i -C 2` call shows exactly the
      `ignore case` and `±2` chips; files and count modes render their
      list/badge layouts. (The androidTests asserting exactly this are written
      and compiled — `ToolRowTest` grep section — but could not be run: the
      2026-09-01 Firebase Test Lab run failed validation with
      TEST_QUOTA_EXCEEDED, the known daily-quota exhaustion. The unit suite
      and `:app:assembleDebugAndroidTest` are the verification basis here.)
- [x] Feeding an unrecognized output shape (tests) renders verbatim, no
      crash, no partial structure.
- [x] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md. (Gradle passes — including
      `:app:assembleDebugAndroidTest`; the release APK is published by the
      merging session.)

## Out of scope

- Tapping a match to open the file (no file content client-side; would
  need a server fetch — separate idea, not yet ticketed).
- `output_mode: count_matches` bar charts or sorting controls.
- Highlighting the match substring inside result lines.
- Multiline-mode match grouping beyond verbatim lines.

## Notes

- Verify the exact separator/context-line grammar (`--`, `path-line-`)
  against the current server before writing the grammar; record a verbatim
  capture per mode in this file or `docs/server-api.md`.
- The three mode grammars live in one pure `GrepResultParser` object in
  `data/chat` (payload-derived renderings belong to the data layer per
  conventions.md); the UI consumes its sealed result type.

## Implementation notes (2026-09-01)

- Wire-shape verification: the running server is 0.39.1 with `backend: "v2"`
  (`GET /api/v1/meta`), so the authoritative tool source is
  `packages/agent-core-v2/src/agent/tools/os/grep/grepTool.ts`, extracted with
  `strings` from `~/.kimi-code/bin/kimi` (the ticket-122 method; the bundle's
  v1 twin differs only in shorter notice wordings — recorded in
  docs/server-api.md). The raw rg record shapes (`path\0NN:text` /
  `path\0NN-text` records, bare `--` separator lines, NUL-separated `-l`
  paths, `path\0N` count records) were cross-checked live with the bundled
  `~/.kimi-code/bin/rg` using the exact arg set the tool builds. Verbatim
  capture per mode in `docs/server-api.md`, Tools → Lookup tool output text.
- Payload: `ToolPayload.Grep(pattern, path?, glob?, outputMode,
  caseInsensitive, contextBefore?, contextAfter?, multiline, includeIgnored,
  type?)` — `pattern` required; blank `path`/`glob`/`type` parse as absent; a
  string `"true"` is not a flag (the Edit replace_all rule, now one shared
  `flagOf` helper — Edit's and Glob's inline copies folded into it, no
  behavior change). `-C` fills both context sides and wins over `-A`/`-B`
  (the tool's arg building). `outputMode` stays a String with default
  `files_with_matches`: a drifted mode keeps the rich row, only the result
  parse falls back to verbatim. head_limit/offset/`-n` are not carried at all
  (never shown). The chip derivation (`contextChips`: `±N` symmetric,
  `+N`/`-N` asymmetric; `hasNonDefaultKnobs`) lives on the payload — the
  Read.rangeLabel precedent.
- `GrepResultParser` (data/chat, pure): `of(output, mode)` → sealed `Result`
  (`Content` items = per-file `Group`s split by `--` `Separator`s /
  `FileList` / `Counts` with the lifted `Found N total …` summary), null =
  render the WHOLE output verbatim. The content grammar is the server's own
  lazy split (`^(.*?)([:-])(\d+)\2(.*)` — CONTENT_LINE_RE): the first
  `:`/`-`-bracketed digits are the path boundary, so matched text containing
  `:N:` stays safe (only a pathological file name containing `-N-`
  mis-splits, cosmetic). `-n: false` content output (numberless `path:text`)
  fails the grammar → verbatim. The tool's notices (sensitive-filter report,
  pagination notice — in the HEAD for count mode, the two-line rg-output
  truncation marker, the timeout notice) are stripped line-boundary-anchored
  into `Result.notices` BEFORE the grammar runs, so a truncated result still
  structures; the expansion renders them as dimmed captions below the block.
  A notice wording mid-body or drifted is not stripped and fails the grammar
  → verbatim. The status-only/empty/notices-only outputs parse to null, the
  GlobOutputs discipline.
- Row: magnifier `SearchIcon` (testTag `toolIconGrep`), the preview rendered
  slash-delimited (`/pattern/`, one-lined — the row's own reformatting, like
  FetchURL's host-first), the trailing scope chip (`path`, or `glob` when no
  path — plain monospace text, the Read range chip's recipe, weight-bounded).
  `ToolPreview` gained the `Grep → pattern` key. Expandable when the output
  is non-blank or any knob is non-default (a running default Grep stays
  collapsed); an unparseable call keeps the generic args table.
- Expansion: `GrepChipsRow` (nothing rendered when all-default), then per
  mode — content: `labelMedium`/`onSurfaceVariant` file headers above
  monospace lines, match numbers dimmed at the left, context lines dimmed
  without a number, `--` collapsed to 4dp spacing; files: the Glob "N
  matches" header + capped path list; counts: the summary header + rows of
  path + `surfaceVariant` count pill. All blocks capped at 240dp with
  internal scroll (the ToolDetailBlock recipe). Error rows never parse
  (verbatim on the error surface, the Glob rule).
- Fixture updates (additive only, no assertion logic touched):
  ToolPayloadsTest's "another tool never parses as a lookup payload" case
  moved its Grep assertion to Edit (a parseable Grep gets the payload now —
  the ticket's point).
- The type chip renders `type: kt` (the bare word would carry no meaning) —
  the one label the ticket listed as a name rather than a literal.
