# Sweep ViewModels to Kotlin 2.4 explicit backing fields

- **ID:** 084
- **Created:** 2026-08-01
- **Depends on:** none

## Context

Found in the 2026-08-01 post-upgrade feature review: explicit backing fields
(`val x: StateFlow<T> field = MutableStateFlow(…)`), experimental since
Kotlin 2.3, went **stable in Kotlin 2.4** — landed via 075 at 2.4.10. The
app's ViewModels/stores are the poster use case, with the classic
`private val _s = MutableStateFlow(…); val s = _s.asStateFlow()` pair in
`ChatViewModel`, `SubagentViewModel`, `SettingsViewModel`,
`SessionListViewModel`, and `SubagentStreamStore`.

Decision (Louis, 2026-08-01): sweep the existing sites in one ticket and
make the new form the convention. Justification for the churn: the diff is
small and mechanical, the unit suite is ViewModel-heavy (the whole chat
harness drives these StateFlows), and keeping two conventions in the
codebase is worse than a one-time sweep.

## Description

1. Convert every `_x`/`x.asStateFlow()` pair to
   `val x: StateFlow<T> field = MutableStateFlow(…)`; internal mutations
   become direct (`x.update { … }` / `x.value = …` — the backing field
   smart-casts to `MutableStateFlow` inside the class).
2. If any site does something the new form can't express (custom
   `asStateFlow()`-time transformation, exposing the same flow under two
   visibilities), leave it and note why in this ticket.
3. Update `AGENTS.md` (MVVM bullet): new ViewModels use explicit backing
   fields; the `_x`/`asStateFlow()` pair is legacy.

## Acceptance criteria

- [ ] No `_asStateFlow()` backing pairs remain (grep), or exceptions are
  documented in this ticket.
- [ ] `./gradlew testDebugUnitTest` green (the chat/session ViewModel
  harnesses are the regression net).
- [ ] Firebase robo green; release APK published per `AGENTS.md`.
- [ ] `AGENTS.md` convention note added.

## Out of scope

- Any state-flow semantics change (scopes, `SharingStarted`, initial
  values).
- Other Kotlin 2.4 features (collection literals, return-value checker,
  map fallbacks) — all experimental, deliberately skipped in the review.
- Migrating `java.time.Instant` → `kotlin.time.Instant` — rejected in the
  review, buys nothing at minSdk 36.

## Notes

- Language reference: https://kotlinlang.org/docs/whatsnew24.html (explicit
  backing fields section).
