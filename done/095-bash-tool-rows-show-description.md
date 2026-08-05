# Bash tool rows show the description instead of the command

- **ID:** 095
- **Created:** 2026-08-05
- **Depends on:** none

## Context

Louis reviews tool runs from his phone. For `Bash` calls the row preview shows
the raw shell command — noise next to the call's `description` argument, which
is the human-written label of what the command does (and the label the jobs
widget already uses for background tasks). This matters most for background
bash, whose rows become navigation entries to the job screen (ticket 096), but
Louis decided the rule should apply to **all** Bash rows: one consistent rule,
matching the CLI, which labels background runs by description.

Alternatives considered and rejected:

- **Background rows only** — two preview rules for the same tool; the
  description is equally useful on foreground calls.
- **Adding `description` to `ToolPreview.IDENTIFYING_KEYS` globally** — would
  silently change `Agent`/`AgentSwarm` rows (their args carry `description`
  too). Explicitly out of scope: only Bash rows change.

Wire facts (verified 2026-08-05 on 0.32.0): the Bash `tool_use` input carries
`description` when the agent sets it (optional); the live `tool.call.started`
path currently prefers `display.command`, so the description must win over it
there too. When no description was set, fall back to today's behavior
(command, then compact JSON).

## Description

- `ToolPreview.of` gains the tool name (e.g. `of(toolName: String?, input:
  JsonObject?)`). When `toolName == "Bash"`, the identifying-key order becomes
  `description`, `command`, `path`, then the compact-JSON fallback; the
  existing one-line whitespace collapsing applies to the description too.
  Every other tool keeps today's order (`command`, `path`).
- Update all call sites so both ingestion paths are covered: history/snapshot
  `tool_use` parts and transcript tool frames (`ChatMessageMapper.toDomain`
  variants), and the live `tool.call.started` path (`ChatMessageMapper.preview`),
  where a non-blank Bash `args.description` must beat `display.command`.
- The expanded row's full-input JSON block is unchanged (the command stays
  visible there).
- Tests: `ToolPreview` cases — Bash with description → description; Bash
  without → command; Bash with neither → compact JSON; a non-Bash tool whose
  args carry `description` (e.g. Agent) → unchanged (regression guard against
  the global-key mistake); multi-line description collapses to one line. A
  mapper-level case: live payload with `display.command` + `args.description`
  → description wins.

## Acceptance criteria

- [ ] New `ToolPreview`/mapper unit tests above pass.
- [ ] Existing suite green with no behavior-test changes beyond the new Bash
      preview semantics.
- [ ] Observable on device: a Bash call with a description shows it on the row
      (live and after history reload); one without shows the command; Agent
      rows are unchanged.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes (VPS build
      rules); release APK published to the file server per AGENTS.md.

## Out of scope

- **Other tools' previews** — Agent/AgentSwarm rows keep today's behavior.
- **The job screen** (tap to navigate, output, Stop) — ticket 096.
- **Timeout display** — ticket 097.
- **Expanded-row content** — full input JSON stays as-is.

## Notes

- Files: `data/chat/ToolPreview.kt`, `data/chat/ChatMessageMapper.kt`
  (`preview()`, `toDomain(ContentPartDto)`, transcript-frame mapping),
  `ui/chat/ChatTimeline.kt` if it calls `ToolPreview` directly; tests in
  `app/src/test/.../data/chat/` (and wherever ToolPreview is covered today).
- Verified Bash input shape (0.32.0):
  `{"command": "…", "description": "…", "run_in_background": true}` —
  `description` is absent when the agent doesn't set it (REST then invents a
  `"Bash: <command>…"` task description; that's the task store, not this row).
