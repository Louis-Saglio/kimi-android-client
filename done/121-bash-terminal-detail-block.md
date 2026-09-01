# Bash tool: terminal-styled detail block (replaces raw JSON input)

- **ID:** 121
- **Created:** 2026-09-01
- **Depends on:** 118

## Context

Foreground `Bash` rows already have the best collapsed treatment (terminal
`>_` glyph, description-led preview since ticket 095, timeout fields on the
row model since 098) — but tapping one still shows the call input as raw
JSON: `command`, `description`, `timeout`, `cwd` all brace-wrapped. The
2026-09-01 design gives the expanded view a terminal-flavored layout and
one piece of output parsing Louis approved: the harness's
`Command failed with exit code: N` trailer (harness-generated, stable
enough; tolerant fallback to verbatim).

Field decisions: `description` becomes a caption (it's already the preview,
so it only earns space on expansion), `command` gets the command block,
`cwd` a chip when set, timeout a chip only when non-default (explicit
`timeout` ≠ 600s foreground / 120s background, or `disable_timeout`);
`run_in_background: true` rows with a `bashTaskId` keep navigating to the
job screen untouched — this ticket is foreground-only.

## Description

**1. Payload.** `ToolPayload.Bash(command, description, cwd,
timeoutSeconds, timeoutDisabled)` (119's sealed type; `timeout` parsing
reuses the `bashTimeoutOf` semantics — one derivation, no duplicate logic).

**2. Expanded layout** (replaces the generic key/value table for Bash rows
with a parsed payload):

- `description` as a caption line (`bodySmall`, `onSurfaceVariant`) when
  present.
- The command alone in a terminal block: monospace, `$ ` prompt prefix,
  `surfaceContainerHigh` surface, `MaterialTheme.shapes.small`, internally
  scrollable capped height (`ToolDetailBlock` recipe).
- Chips row when any apply: `cwd` (folder glyph + path, monospace), timeout
  (`⏱ Ns` or "no timeout" — the row's existing
  `timeoutSeconds`/`timeoutDisabled` fields), nothing when everything is
  default.
- Output block unchanged, except: when the output ends with the harness
  trailer `Command failed with exit code: N`, strip that line from the
  rendered text and show an `exit N` chip in `colorScheme.error` next to
  the output. Regex-anchored at the end of the string; no match → verbatim,
  no chip. (Status glyph already turns red via `is_error`; the chip adds
  the code.)

**3. Rows** keep their current collapsed look (glyph, description preview,
status) — no changes there.

**4. Tests.** Payload parsing (defaults vs explicit knobs);
exit-code-trailer parsing (strips exactly the trailer line, code extracted,
absent/malformed → untouched); expanded-layout androidTest (command block
shows the bare command without JSON braces, caption and chips render,
background-linked rows still navigate instead of expanding).

## Acceptance criteria

- [x] New tests above pass; existing suite green with no behavior-test
      changes.
- [x] Observable on device: expanding a foreground Bash row shows the
      description caption, a `$`-prompted command block, and the output; a
      failed command shows the `exit N` chip and no trailer line in the
      output text.
- [x] A default call (`./gradlew build`, no description/cwd/timeout) shows
      no chips at all.
- [x] Background-linked Bash rows still open the job screen; timeout chips
      agree with the job screen's budget display for the same call.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- ANSI escape/color parsing in command output.
- Re-running or copying the command (copy via the existing text selection
  only).
- Job-screen changes; background Bash behavior of any kind.
- Streaming-output presentation while running (the existing
  progress-accumulating pinned block stays).

## Notes

- The trailer string is harness prose, not wire protocol — the parser must
  be a pure function with a verbatim fallback so a server wording change
  degrades to today's behavior, never a crash.
- `docs/conventions.md`: one line noting Bash is the first payload-typed
  expanded row (precedent for 122–125).

## Implementation notes (2026-09-01)

- Payload: `ToolPayload.Bash(command, description, cwd, timeoutSeconds,
  timeoutDisabled)` — `command` is the one required key (a call without it
  keeps the generic expansion); blank `description`/`cwd` parse as absent.
  The timeout knobs come from a new `bashTimeoutOf(args: JsonObject)`
  overload in `Tasks.kt` — the ONE derivation, now shared by the string-input
  `bashTimeoutOf(toolName, argsJson)` (which delegates) and the payload
  parser. The shared overload safe-casts (`as? JsonPrimitive`), so a
  non-primitive `timeout` value now parses as absent instead of throwing —
  strictly more tolerant, an untested edge before.
- Expanded layout (ToolRows.kt): caption + `BashCommandBlock` (a dimmed `$ `
  prompt Text beside the command Text — separate nodes, so the prompt is not
  part of the command) + `BashChipsRow`. The timeout chip reads the ROW's
  `timeoutSeconds`/`timeoutDisabled` (the ticket 097 fields) per the ticket,
  not the payload's: same source as the job screen's budget nav arg, so the
  two always agree. The chips row renders nothing when everything is default;
  an explicit timeout wins over `disable_timeout` (the
  `BashTimeout.effectiveSeconds` ordering). A parsed Bash row stays
  inline-expandable (unlike Edit/Read/Write, which navigate);
  background-linked rows never reach the expansion.
- `BashOutputs` (data/chat, the ReadOutputs pattern): end-anchored regex
  `\s*Command failed with exit code: (\d+)\s*$` strips the trailer line and
  extracts the code; a mid-text mention, a non-numeric or overflowing code
  (`toIntOrNull` guard) or wording drift → verbatim, no chip. The `exit N`
  chip is a filled `colorScheme.error` Surface below the output block.
- Fixture updates (additive only, no assertion logic touched): three
  full-row equality expectations on Bash rows with parseable args gained
  `payload = ToolPayload.Bash(...)` — ChatRepositoryTest ×2 (`history tool
  result output and error flag…`, `history tool use input surfaces…`) and
  SubagentViewModelTest ×1 (`a finished sub-agent's page…`).
- Verified: `:app:testDebugUnitTest :app:assembleDebug
  :app:assembleDebugAndroidTest` green (645 unit tests, 0 failures — 6 new
  BashOutputsTest, 6 new ToolPayloadsTest cases); Firebase Test Lab
  MediumPhone.arm API 36: ToolRowTest 35/35 passed (28 existing + 7 new).
  "Observable on device" was verified through those instrumentation
  assertions, not a manual phone check. The release APK is published by the
  merging session, not this one.
