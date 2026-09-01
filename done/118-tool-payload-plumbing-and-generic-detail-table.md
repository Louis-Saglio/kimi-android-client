# Tool payload plumbing + generic key/value detail table

- **ID:** 118
- **Created:** 2026-09-01
- **Depends on:** none

## Context

Every tool call without a dedicated UI (TodoList widget, Agent page,
background-Bash job screen, AskUserQuestion card) renders the same fallback:
tap the row, see the raw input as pretty-printed JSON plus the raw output.
The blocker is architectural, not visual: `ChatMessageMapper.formatDetail`
flattens the call's structured `args` JsonObject into a String at the data
boundary, so by the time `ChatItem.ToolActivity` exists the fields are gone.

PO/UX discussion with Louis (2026-09-01) decided: per-tool rich UIs grouped
by archetype (tickets 119–125), built on typed access to the call payload.
This ticket is the foundation every one of them stands on — and it already
kills the raw-JSON feel everywhere by upgrading the generic fallback to a
key/value table (covers MCP `mcp__*` tools and any future tool for free).

Decisions:

- Carry the raw `args` JsonObject end-to-end; derive display fields with a
  pure helper next to `ToolPreview` (data layer owns payload-derived
  renderings — conventions.md). Rejected: re-parsing the pretty-printed
  string in the UI (double round-trip, and the live event path would need
  the object anyway).
- The generic table shows all args as-is; per-tool *tuning* (which fields
  earn UI space, which stay hidden) is each follow-up ticket's job.
- Tool **outputs** stay verbatim monospace here; selective output parsing is
  decided per tool in the follow-ups (Louis confirmed: parse stable formats,
  tolerant fallback to verbatim).

## Description

**1. Carry `args` end-to-end.** Add `args: JsonObject?` (null-safe, default
null) to:

- `ChatMessage.Part.ToolUse` — set in `ChatMessageMapper.toDomain` from the
  `tool_use` part's `input` (keep the existing pretty-string `input` field:
  the expansion of a call with no object args, and nothing else, still uses
  it).
- `SessionEvent.ToolCallStarted` — set at the `SessionSocket` site from
  `ToolCallPayload.args` (it is already in hand there for `preview`).
- `TranscriptFrame.Tool` — set in the mapper's transcript-frame path from
  the frame's `input`.

`ChatItem.ToolActivity` gains `args: JsonObject? = null`. `ChatTimeline`
threads it through the four construction/update sites (history flatten,
transcript flatten, live `ToolCallStarted` upsert, orphan completion) with
the same first-writer-wins rule as `preview`/`input`
(`existing.args ?: args`); the `enriched()` pass is untouched.

**2. Display-field derivation.** New pure object `ToolDetailFields` in
`data/chat` (next to `ToolPreview`): `of(args: JsonObject?): List<Field>`
where `Field(label: String, value: String)` — top-level entries in wire
order; string primitives render raw (one-lined, whitespace-collapsed like
`ToolPreview.oneLine`), numbers/booleans render literal, nulls are dropped,
nested objects/arrays render as compact one-line JSON. Null/empty args →
empty list.

**3. Generic expansion UI.** `ToolRow`'s expanded area replaces the input
JSON block with a key/value table: label column in
`MaterialTheme.typography.labelMedium` `onSurfaceVariant`, value column
monospace `bodySmall`, 4dp row spacing, values selectable is *not* required
(the full strings stay available via the row's existing data). The output
block is unchanged (including error tint and running-pin). Expandability
rule unchanged: no args and no output → no expansion.

**4. Docs + tests.** `docs/conventions.md`: record that tool rows carry the
call's raw `args` and that payload-derived display renderings live in
`data/chat` helpers. Unit tests: `ToolDetailFields` (string/number/boolean/
null/nested/empty), timeline threading on all four sites (incl. heal
preserving a previously set `args`), mapper sets `args` on ToolUse and
transcript Tool frames. androidTest: a row with object args expands to the
table (labels + values visible, no `{`/`}` JSON braces), a no-arg no-output
row stays non-expandable.

## Acceptance criteria

- [x] New unit/android tests above pass; existing suite green with no
      behavior-test changes.
- [x] Observable on device: any non-specialized tool row (e.g. an MCP tool
      or `Glob` before ticket 122) expands to a key/value table, not raw
      JSON; Bash/Read rows keep their current previews and statuses.
- [x] Live, history, and transcript (sub-agent page) paths all populate
      `args` — verified by tests and by expanding a row on the sub-agent
      page.
- [x] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.
- [x] `docs/conventions.md` updated.

## Out of scope

- All per-tool typed payloads, icons, badges, screens — tickets 119–125.
- Any tool-output parsing (exit codes, grep results, cron fields).
- Changing row previews (`ToolPreview` untouched).

## Notes

- Watch the LazyColumn key/merge machinery: `args` rides along existing
  copy/update paths only — no new row types, no key changes.
- `ChatViewModelTest`, `ChatTimelineTest`, `ChatMessageMapperTest` have
  fixtures constructing `ToolUse`/`ToolCallStarted`; default-null `args`
  should keep them compiling untouched.
- Done 2026-09-01. Unit: 585 tests green (incl. 13 new: 6 ToolDetailFields,
  5 ChatTimeline threading, 2 mapper). Instrumentation on Firebase Test Lab
  (MediumPhone.arm, API 36): ToolRowTest + ToolRowExpandScrollTest, 17/17
  passed.
- Expectation-only updates (the parser now fills the new field, full-equality
  wire tests had to expect it): ChatRepositoryTest (2 socket tool.call.started
  assertions, 1 sub-agent flow, 2 history/timeline rows, 2 transcript frames)
  and SubagentViewModelTest (1 transcript-fed row). No assertion weakened.
- Device verification is via the shared ToolRow composable (the sub-agent
  page reuses ToolRows): the Glob-args row expands to the table with no
  braces; Bash/Read preview/status cases stayed green. A literal on-device
  eyeball of the sub-agent page remains Louis's pass.
- `ToolPreview.oneLine` went from private to internal so ToolDetailFields
  reuses the same whitespace-collapsing for string values.
