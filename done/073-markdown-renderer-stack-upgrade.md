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

## Investigation findings (2026-07-31)

Sources: GitHub release notes + per-tag sources of
`mikepenz/multiplatform-markdown-renderer`, MIGRATION.md, Maven Central POMs /
AAR inspection, androidx BOM POMs on dl.google.com.

Ladder (Kotlin / androidx Compose per renderer release):

| Renderer | Kotlin | Compose | Note |
|---|---|---|---|
| 0.26.0 (was pinned) | 2.0.21 | 1.6.8-era | sync parse in composition, no memoization |
| 0.29.0 | 2.1.0 | 1.7.6 | first Kotlin 2.1 requirement |
| 0.30.0 | 2.1.0 | 1.7.6 | Java 17 bytecode |
| 0.31.0 | 2.1.10 | 1.7.8 | hard floor: needs compose ui >= 1.7.0 |
| 0.32.0 | 2.1.10 | 1.7.8 | EOL = space behavior change (opt-out: `eolAsNewLine`) |
| 0.33.0 | 2.1.20 | 1.7.8 | async parsing + memoization; `rememberMarkdownState` |
| 0.35.0 | 2.1.21 | 1.8.2 | last on Compose 1.8 (0.36.0 never hit Maven Central) |
| 0.37.0–0.38.1 | 2.2.x | 1.9.x | 0.38.0 adds `retainState` |
| 0.39.0+ | 2.3.x | 1.10.x | first Kotlin 2.3 |
| 0.42.0–0.43.0 | 2.4.0 | 1.11.x | `StreamingMarkdownState` (incremental append-only parsing); Java 21 bytecode; compileSdk 37 |

Decision: land **Kotlin 2.2.21 + Compose BOM 2025.11.00 (Compose 1.9.4,
material3 1.4.0) + renderer 0.38.1** — the newest clean stop on the ticket's
Kotlin 2.1/2.2 line. 0.43.0 is a different, bigger ladder (Kotlin 2.4,
Compose 1.11, compileSdk 37) and out of this ticket's scope.

Code changes forced by the upgrade:

- `markdownColor(codeText=, linkText=)` removed in 0.36.0 → link color moved
  to `markdownTypography(textLink = TextLinkStyles(...))`; code text inherits
  surrounding color (same visual as before).
- 0.33.0 async parsing: use `rememberMarkdownState(content, retainState = true)`
  + `Markdown(markdownState = ...)` so streamed emissions keep the previous
  snapshot on screen instead of flashing the blank loading slot (the m3 String
  overload does not expose `retainState` at 0.38.1).
- 0.32.0 EOL-as-space: opted out with
  `markdownAnnotatorConfig(eolAsNewLine = true)` — chat markdown reads single
  `\n` as a line break (previous app behavior).
- AGP 8.6.1, minSdk 31, Java 17 bytecode unchanged.

DeltaCoalescer evaluation (ticket step 4): unchanged text no longer re-parses
(memoized since 0.33.0), but every new streamed snapshot still costs one full
re-parse of the whole document — incremental parsing (`StreamingMarkdownState`)
only exists from 0.42.0 and needs Kotlin 2.4. The 100 ms coalescing window
therefore still bounds the re-parse rate and **stays**. Revisit on the next
ladder rung (Kotlin 2.4 + 0.43.0), which is a new ticket.

## Landed (2026-07-31)

- Kotlin 2.0.21 → 2.2.21, KSP 2.2.21-2.0.5, Compose BOM 2024.06.00 →
  2025.11.00 (Compose 1.9.4, material3 1.4.0), renderer 0.26.0 → 0.38.1.
- Forced side-bumps: Hilt 2.51.1 → 2.57.2 (2.51.1's KSP processor crashes
  under KSP2: `KSTypeArgument.type should not have been null … STAR null` in
  dagger's BindsTypeChecker); material3 1.4 removed `PullToRefreshContainer`
  → `SessionsScreen` migrated to `PullToRefreshBox`.
- `MarkdownBody`: `rememberMarkdownState(content, retainState = true,
  immediate = true)` + `Markdown(markdownState = …)`. `immediate = true`
  parses the initial content synchronously in composition (0.26.0 parity) —
  without it the first frame renders zero-height loading placeholders, so
  `MessageList`'s initial `scrollToBottom` clamps to offset 0 and the bottom
  pin detaches once rows grow (this was the root cause of all 6 first-run
  instrumented failures: `ChatAutoScrollTest` ×3, `ToolRowExpandScrollTest`
  ×3 — a real production regression, not test flakiness). Streaming updates
  still re-parse asynchronously; `retainState` keeps the previous snapshot
  visible during re-parse. Link color moved from the removed
  `markdownColor(linkText=)` to `markdownTypography(textLink=…)`;
  `eolAsNewLine = true` opt-out preserved.
- Test matrix: unit suite green; instrumented androidTest 42/42 on Firebase
  Test Lab (MediumPhone.arm, API 34); robo green. R8 prints kotlin-metadata
  warnings (R8 in AGP 8.6.1 predates Kotlin 2.2) — harmless: metadata parsing
  only affects optimization hints, and kotlinx.serialization uses generated
  serializers.
- Ticket step 3 (on-device streaming eyeball on a long markdown-heavy
  session): left to Louis on the published APK — the VPS can't measure phone
  frame drops. Design intent: first-frame parse is synchronous like 0.26.0,
  streaming re-parses are async + coalesced at 100 ms, unchanged content is
  memoized.

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

- [x] Investigation findings recorded in this ticket (versions, blockers,
  decision).
- [x] If upgraded: unit + androidTest + robo green; release APK on the file
  server per `AGENTS.md`; `AGENTS.md` pin note updated.
- [ ] ~~If no-go: `AGENTS.md` pin note updated with why, ticket moved to
  `done/` as investigated-not-landed.~~ (n/a — upgrade landed)

## Out of scope

- Replacing the markdown renderer with a different library — only consider
  if 0.43.0 is a proven dead end, and that's a new ticket with Louis.
- Compose Multiplatform / other platforms — Android only.

## Notes

- 070 must land first: it's the low-risk half of the version ladder and
  verifies the test matrix this ticket will lean on.
