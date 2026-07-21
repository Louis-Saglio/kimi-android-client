# Rich tool call rows with input preview and status icon

- **ID:** 022
- **Created:** 2026-07-21
- **Depends on:** none

## Context

Product discussion 2026-07-21 (rendering-parity batch). The app renders tool
activity as a bare `Bash · done` label (`ChatScreen.kt` `ToolRow`). The web
client shows a compact rounded row per call: leading tool icon, a one-line
monospace preview of what the tool is doing (the shell command for Bash), a
green ✓ / red ✗ outcome, and a chevron. The wire protocol already carries
everything needed — history `tool_use` parts include `input`
(`{"command": "echo doc-verify"}`), and the live stream's `tool.call.started`
carries `args`/`display` (`docs/server-api.md`) — the app simply drops it:
`ContentPartDto` declares neither `input` nor `output`, and `ToolCallPayload`
only the id and name. Scanning a long turn on the phone currently means
reading tool names with no idea what they did.

## Description

- Data layer:
  - `ContentPartDto`: parse `input` (keep it as a raw `JsonObject`) on
    `tool_use` parts; thread a derived one-line preview through
    `ChatMessage.Part.ToolUse` → `ChatItem.ToolActivity`.
  - `ToolCallPayload` / `SessionEvent.ToolCallStarted`: parse `args` (and
    `display` if present) so a live row gets its preview as soon as the call
    starts, not only after a history refetch.
  - Preview derivation, one place, unit-tested: Bash → `input.command`;
    other tools → their single most identifying argument (e.g. a path for
    Read/Write/Edit) when obvious, else a compact form of the input JSON.
    Keep it dumb — no per-tool framework.
- UI (`ToolRow`): web-style compact row — leading tool icon (terminal glyph
  for Bash, a sensible default icon otherwise), tool name, one-line monospace
  preview with end-ellipsis, and a status glyph: spinner while running, green
  check on success, red ✗ on error (replacing the `· done` / `· error` text).

## Acceptance criteria

- [ ] Unit test: a history `tool_use` part with `input.command` yields a
  `ToolActivity` whose preview is that command (fixture per
  `docs/server-api.md`).
- [ ] Unit test: `tool.call.started` with args fills the preview of the
  running row live (timeline-level test).
- [ ] Unit test: preview derivation falls back sanely for a tool with no
  obvious identifying argument.
- [ ] Rows show icon + preview + status glyph (spinner/check/✗); verified by
  a semantics test if the harness allows, else on-device against a live turn.
- [ ] `./gradlew :app:assembleDebug` succeeds, unit tests green.

## Out of scope

Expand/collapse and showing tool output — ticket 023 (the chevron lands
there). Live output streaming — ticket 025. Pretty diff rendering for
Edit/Write inputs. Per-tool custom icons beyond the Bash/default pair.

## Notes

- `docs/server-api.md` is the source of truth for the payloads — do not
  re-reverse-engineer the web bundle.
- Result-without-call already renders a row (`ChatTimeline.flatten`); make
  sure it survives this change with an empty preview.
