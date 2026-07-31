# Kotlin 2.0.21 bump for Compose strong skipping

- **ID:** 070
- **Created:** 2026-07-31
- **Depends on:** none

## Context

Found in the 2026-07-31 tech-debt audit. Kotlin is pinned to 2.0.0
(`gradle/libs.versions.toml:3`), which predates default strong skipping
(2.0.20+). `ChatScreen` carefully `remember`s six callbacks
(`ChatScreen.kt:121-139`, with a comment explaining the compiler won't
auto-remember ViewModel captures) and then passes ~15 **un-remembered**
`viewModel::x` references (`ChatScreen.kt:144-166`) plus an inline `onSend`
lambda into `ChatContent`. Every UiState emission — up to ~10/s while
streaming — produces fresh function instances, so `ChatInputBar`,
`ApprovalCard`, `QuestionCard` never skip even when their data params are
unchanged. The care on six callbacks and the abandonment on fifteen is
internally inconsistent, and the manual-remember approach regresses with
the next added callback.

Decision (Louis, 2026-07-31): bump Kotlin rather than hand-remember ~15
call sites. Constraint check: the mikepenz renderer 0.26.0 pin requires
Kotlin 2.0.x — 2.0.21 stays within it; the Compose BOM stays at
2024.06.00.

## Description

- `gradle/libs.versions.toml`: `kotlin` 2.0.0 → 2.0.21, `ksp` → the
  matching `2.0.21-1.0.x`.
- Verify Hilt/KSP compatibility with the new KSP version.
- Run the full verification matrix: unit suite, the androidTest suites on
  Firebase Test Lab (at minimum the chat ones: `ChatAutoScrollTest`,
  `ToolRowTest`, `MarkdownBubbleTest`), and a robo run.
- Keep the existing manual `remember`s (harmless no-ops under strong
  skipping; removing them is churn).
- Strong skipping changes recomposition behavior app-wide — watch for UI
  tests that implicitly depended on recomposition counts.

## Acceptance criteria

- [ ] Catalog bump only (no app-code changes required).
- [ ] `./gradlew :app:testDebugUnitTest` green.
- [ ] androidTest suites green on Firebase Test Lab; robo run green.
- [ ] Release APK built and copied to the file server per `AGENTS.md`.

## Out of scope

- The full dependency-stack upgrade (Kotlin 2.1+/Compose 1.7+/renderer
  0.43) — ticket 073.
- Rewriting callback passing now that strong skipping exists — the current
  code becomes correct for free.

## Notes

- If the bump breaks the markdown renderer at runtime (it shouldn't — same
  Kotlin line), stop and report rather than working around it; that would
  change the 073 calculus.
