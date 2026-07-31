# Investigation: dependency stack + markdown renderer upgrade

- **ID:** 073
- **Created:** 2026-07-31
- **Depends on:** 070

## Context

The Compose BOM is pinned to 2024.06.00 for the mikepenz markdown renderer
0.26.0 (`AGENTS.md`), which re-parses markdown inside composition on every
emission — the 100 ms delta-coalescing window exists only to mitigate that.
The renderer is now at 0.43.0 (2026-06). This is the app's single biggest
performance lever and its single biggest regression surface: markdown
rendering is the core chat UX.

Decision (Louis, 2026-07-31): file it, do it last, after the rest of the
debt program lands on a stable, fully-tested base.

## Description

Investigate first, upgrade only if clean:

1. Read the renderer's releases 0.26.0 → 0.43.0: required Compose/Kotlin
   versions, changelog entries on incremental parsing/memoization, and
   breaking API changes relative to `MarkdownBody.kt`'s usage.
2. If the path is clean: bump Kotlin (2.1/2.2 line), Compose BOM, and the
   renderer; run the full unit suite, the androidTest suites on Firebase
   Test Lab (markdown and chat tests first), and a robo run.
3. Compare streaming behavior on a long markdown-heavy session (CPU,
  dropped frames by eye is fine — this is a phone app for one user).
4. If the renderer now memoizes parsing, evaluate relaxing or removing the
   100 ms coalescing window (ticket 065's `DeltaCoalescer` makes this one
  constructor argument).

Deliverable: the upgrade landed, **or** a documented no-go with the blocker
recorded in this ticket and `AGENTS.md`'s pin note updated with the current
findings.

## Acceptance criteria

- [ ] Investigation findings recorded in this ticket (versions, blockers,
  decision).
- [ ] If upgraded: unit + androidTest + robo green; release APK on the file
  server per `AGENTS.md`; `AGENTS.md` pin note updated.
- [ ] If no-go: `AGENTS.md` pin note updated with why, ticket moved to
  `done/` as investigated-not-landed.

## Out of scope

- Replacing the markdown renderer with a different library — only consider
  if 0.43.0 is a proven dead end, and that's a new ticket with Louis.
- Compose Multiplatform / other platforms — Android only.

## Notes

- 070 must land first: it's the low-risk half of the version ladder and
  verifies the test matrix this ticket will lean on.
