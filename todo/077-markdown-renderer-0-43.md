# Markdown renderer 0.38.1 → 0.43.0 (Kotlin 2.4 ladder rung)

- **ID:** 077
- **Created:** 2026-07-31
- **Depends on:** 076, 079

## Context

This is the "next ladder rung" ticket 073 explicitly deferred: renderer
0.42.0–0.43.0 requires Kotlin 2.4, Compose 1.11.x, compileSdk 37, and ships
Java 21 bytecode — all satisfied once 075/076/079 land. 0.43.0 (2026-06) is
the newest stable as of 2026-07-31 (re-verify at implementation time).

Why bother, beyond "latest": this rung is the one that adds
`StreamingMarkdownState` — incremental append-only parsing — which ticket
073's DeltaCoalescer evaluation identified as the only way to stop paying a
full-document re-parse per streamed snapshot. **Adopting that API is 082,
not this ticket.** This ticket is the plain version bump on the existing
`rememberMarkdownState(content, retainState = true, immediate = true)` usage.

Breaking-change review (073's ladder table + release notes 0.38.1 → 0.43.0):

- 0.40.2: inline-image handling rework (MIGRATION.md) — the app renders no
  inline images in chat markdown; expected no-op. Verify `MarkdownBody.kt`
  compiles unchanged.
- 0.41.0: semantics/a11y overhaul, stability fixes — behavior-compatible.
- 0.42.0: adds `StreamingMarkdownState` rendering, `Flow.asMarkdownState()`
  fixes; existing APIs preserved.
- 0.43.0: adds synchronous `parseMarkdown`; existing APIs preserved.
- The `markdownTypography(textLink=…)` and
  `markdownAnnotatorConfig(eolAsNewLine = true)` usages from 073 still exist
  at 0.43.0 (verify at build time).

## Description

1. `markdownRenderer = "0.38.1" → "0.43.0"` in `gradle/libs.versions.toml`;
   update the adjacent comment (it currently explains the 0.38.1 pin).
2. Compile; adapt `MarkdownBody.kt` only if the release notes' breaking
   changes actually touch the app's usage.
3. Update `AGENTS.md` (renderer version, the ticket-073 ladder note, and the
   DeltaCoalescer rationale paragraph — it currently says incremental parsing
   "needs renderer 0.42 + Kotlin 2.4"; after this ticket that dependency is
   satisfied, with adoption tracked in 082).

## Acceptance criteria

- [ ] Catalog at renderer 0.43.0; chat markdown renders as before (code
  blocks, tables, links, `eolAsNewLine` behavior).
- [ ] `./gradlew testDebugUnitTest` green.
- [ ] androidTest suites green on Firebase Test Lab (markdown + chat tests
  first: `ChatAutoScrollTest`, `ToolRowExpandScrollTest` — the tests that
  caught the 073 first-frame regression), plus robo.
- [ ] Release APK published to the file server per `AGENTS.md`.
- [ ] `AGENTS.md` updated.

## Out of scope

- Switching `MarkdownBody` to `StreamingMarkdownState` (incremental parsing)
  and re-evaluating the 100 ms `DeltaCoalescer` window → 082.
- On-phone streaming-performance eyeball — Louis on the published APK, as in
  073.

## Notes

- Ticket 073 documents the full renderer/Kotlin/Compose ladder and the
  first-frame `immediate = true` rationale — read it before touching
  `MarkdownBody.kt`.
