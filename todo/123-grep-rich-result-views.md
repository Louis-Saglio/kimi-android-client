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

- [ ] New tests above pass; existing suite green with no behavior-test
      changes.
- [ ] Observable on device: a content-mode Grep expands to per-file groups
      with dimmed line numbers; a `-i -C 2` call shows exactly the
      `ignore case` and `±2` chips; files and count modes render their
      list/badge layouts.
- [ ] Feeding an unrecognized output shape (tests) renders verbatim, no
      crash, no partial structure.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

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
