# Tool-row labels from the server-rendered description

- **ID:** 153
- **Created:** 2026-09-23
- **Depends on:** none

## Context

`tool.call.started` has carried `description` — the server-rendered
human-readable action, same text as the approval's `action` — since 0.28.1
(e.g. `"Running: echo doc-verify-0290"`, verbatim at
docs/server-api.md:4669). `ToolCallPayload` (ChatDtos.kt:204) parses only
`toolCallId`/`name`/`args`/`display`, so today only Bash rows get a
readable label, mined from the agent-written `description` *argument*
(095); every other tool row shows raw args JSON. The server field gives
every tool a readable row title, free, with no agent-behavior dependency.

## Description

**1. DTO.** Add `description: String?` to `ToolCallPayload`
(ChatDtos.kt:204); plumb it onto the tool-call-started domain event.

**2. Row title.** The tool-row title resolver becomes: server
`description` → 095's agent-argument description → `display.command` →
the tool name. Keep the agent-argument path as the fallback for frames
where the server description is absent.

**3. Tests.** Socket parse test embedding the doc-verbatim frame
(description lands on the event); resolver precedence unit test; existing
tool-row tests stay green.

## Acceptance criteria

- [ ] Unit tests above pass; existing suite green.
- [ ] On device: non-Bash tool rows (Read, Grep, Edit, …) show a
      readable action line instead of args JSON.
- [ ] Bash rows are visually unchanged (server description is the same
      text 095 mined from the argument).
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Restructuring `display` parsing (122/123/124 own the rich-row kinds;
  this ticket touches the title only).
- Showing the description on rows for streaming/retried calls that never
  re-fire `tool.call.started`.

## Notes

- The approval card already renders the same text as `action`; wording
  parity between the row title and the approval card comes free.
