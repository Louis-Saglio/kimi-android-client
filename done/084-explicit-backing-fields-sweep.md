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

- [x] No `_asStateFlow()` backing pairs remain (grep), or exceptions are
  documented in this ticket.
- [x] `./gradlew testDebugUnitTest` green (the chat/session ViewModel
  harnesses are the regression net).
- [ ] Firebase robo green; release APK published per `AGENTS.md` — **pending**
  (Test Lab quota resets ~07:00 UTC; robo + publish happen from main
  post-merge today).
- [x] `AGENTS.md` convention note added.

## Landed (2026-08-01, worktree agent/backing-fields)

- All 5 sites swept: `ChatViewModel`, `SubagentViewModel`,
  `SettingsViewModel`, `SessionListViewModel`, `SubagentStreamStore` —
  `val uiState: StateFlow<UiState> field = MutableStateFlow(…)`, mutations
  via direct smart-cast (`uiState.update { … }`), `asStateFlow` imports
  dropped; `ChatViewModelTestBase`'s one `_uiState` reference updated. The
  083 constructor split (internal route-object primary + `@Inject`
  secondary) preserved untouched. Grep for `_uiState`/`asStateFlow` in
  main+test: zero.
- No exceptions needed — every site fit the new form.
- Verification: `testDebugUnitTest` + `assembleRelease` green (5m 22s,
  `--max-workers=1`). Robo + APK publish pending quota (parent handles from
  main).
- Process note: the delegated agent was OOM-killed (2026-08-01 06:08
  kimi-web.service restart); the parent verified the preserved diff, ran
  the gates, and merged it.

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

## Verification update (2026-08-01, post-merge)

- Firebase robo **Passed** (MediumPhone.arm, API 36) on merged main.
- Instrumented androidTest **42/42 passed** (MediumPhone.arm, API 36).
- Release APK published to the file server (`kimi-android-client-main.apk`).
