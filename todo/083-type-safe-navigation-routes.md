# Type-safe navigation routes (navigation-compose 2.8 feature)

- **ID:** 083
- **Created:** 2026-08-01
- **Depends on:** none

## Context

Found in the 2026-08-01 post-upgrade feature review: navigation-compose 2.8
(landed via 076 at 2.9.8) added stable type-safe routes built on
kotlinx.serialization — and this app already applies the serialization plugin.
Today `navigation/KimiNavHost.kt` hand-rolls its routing: string templates
(`"chat/{sessionId}?title={title}&cwd={cwd}"`), manual `Uri.encode` in
`KimiRoutes.chat()`/`subagent()`, string `navArgument` declarations, and
ViewModels reading raw string keys from `SavedStateHandle`. That is exactly
the boilerplate type-safe routes delete, and hand-rolled Uri encoding is the
kind of code that breaks on inputs like a session titled `a/b & c#d%`.

Decision (Louis, 2026-08-01): file it as its own small ticket rather than
folding it into the upgrade batches — it's a rewrite of one file plus the
ViewModel arg reads, not a drop-in.

## Description

Replace the string routing with `@Serializable` routes, keeping behavior
identical:

1. Route declarations (next to `KimiNavHost.kt` or in it):
   `@Serializable object Sessions`, `@Serializable object Settings`,
   `@Serializable data class Chat(val sessionId: String, val title: String? =
   null, val cwd: String? = null)`, `@Serializable data class Subagent(val
   sessionId: String, val subagentId: String)`.
2. `NavHost` entries become `composable<Chat> { … }` etc.; navigation calls
   become `navController.navigate(Chat(session.id, session.title,
   session.cwd)) { launchSingleTop = true }` — keep the `launchSingleTop`
   semantics from ticket 071.
3. ViewModel arg reads switch from raw string keys to
   `savedStateHandle.toRoute<Chat>()` / `toRoute<Subagent>()`
   (`androidx.navigation.toRoute`) in `ChatViewModel` and
   `SubagentViewModel`; delete the now-dead `navArgument` blocks and the
   `KimiRoutes` string builders.
4. The sub-agent page's one-level-deeper navigation (same-session
   `SubagentScreen` → `Subagent`) keeps working — it reads `sessionId` from
   the current entry's arguments today; source it from the typed route
   instead.

## Acceptance criteria

- [x] No string route templates, `Uri.encode`, or `navArgument` blocks
  remain; all four destinations use `@Serializable` routes.
- [ ] Navigation flows verified on-device/emulator: sessions → chat,
  chat → sub-agent page, nested sub-agent (same session), settings, back.
  _(Not run — no emulator/Test Lab quota in the worktree wave; see Landed.)_
- [x] A session whose title/cwd contains `/ & # % ?` and spaces shows the
  correct title in the chat top bar (the case hand-rolled encoding breaks
  on). _(Verified at unit level: `RoutesTest` kotlinx round-trips of exactly
  those characters + the existing seed-to-top-bar ViewModel tests. The
  URI encode/decode leg itself is owned by the nav library on-device.)_
- [ ] `./gradlew testDebugUnitTest` green + Firebase robo green.
  _(testDebugUnitTest green — 308 tests, 0 failures. Robo not run: daily
  Test Lab quota exhausted, per wave instructions.)_
- [ ] Release APK published to the file server per `AGENTS.md`.
  _(Deferred to the merge wave, per wave instructions; `assembleRelease`
  is green in the worktree.)_

## Landed (2026-08-01, worktree agent/type-safe-nav)

- `navigation/KimiNavHost.kt`: `KimiRoutes` object, string templates,
  `Uri.encode` builders and `navArgument` blocks deleted; routes are now
  `@Serializable object Sessions`/`Settings`,
  `@Serializable data class Chat(sessionId, title? = null, cwd? = null)` and
  `@Serializable data class Subagent(sessionId, subagentId)`. Destinations
  use `composable<T>`, navigation calls pass route objects, and the nested
  sub-agent page reads its sessionId via `entry.toRoute<Subagent>()`.
  `launchSingleTop = true` (ticket 071) kept on all three navigate calls.
- `ChatViewModel` / `SubagentViewModel`: production arg read is
  `savedStateHandle.toRoute<Chat>()` / `toRoute<Subagent>()`
  (`androidx.navigation.toRoute`) in the `@Inject` constructor. **Deviation
  from the ticket's literal shape:** each ViewModel now has an `internal`
  primary constructor taking the decoded route object, and the `@Inject`
  secondary constructor just delegates with the `toRoute` call. Reason:
  `toRoute`'s decode path round-trips every arg through
  `bundleOf` + `NavType.get(Bundle)` (`SavedStateHandleArgStore`), and local
  unit tests run against a stub `android.os.Bundle`
  (`unitTests.isReturnDefaultValues = true`), which silently decodes every
  argument to null — 68 tests failed that way on the first run (Google's own
  tests for this API use Robolectric, which this project doesn't have and the
  ticket forbids adding). Tests now pass route objects directly; no
  `SavedStateHandle` is constructed in tests anymore.
- Tests: `ChatViewModelTestBase`, `ChatViewModelTest`, `SubagentViewModelTest`,
  `SubagentStreamStoreKeepAliveTest` migrated from hand-written
  `SavedStateHandle(mapOf("sessionId" to …))` to route objects. New
  `navigation/RoutesTest` covers kotlinx round-trips with `/ & # % ?` and
  spaces. No `SavedStateHandle`-building test helper remains.
- Verified: `./gradlew testDebugUnitTest` green (308 tests, 0 failures) and
  `./gradlew assembleRelease` green (Hilt/KSP accepts the constructor split,
  R8 OK). Not verified: on-device navigation flows, Firebase robo, APK
  publish (wave rules — merge wave owns them).

## Out of scope

- Navigation 3, shared-element transitions, predictive-back customization.
- `SavedStateHandle.getMutableStateFlow` (lifecycle 2.9) — no process-death
  state-restoration need identified.
- Changing any screen behavior or UX.

## Notes

- Type-safe routes still populate named string arguments internally, so the
  current `SavedStateHandle["sessionId"]` reads may keep compiling — switch
  to `toRoute<T>()` anyway; that's the point of the migration.
- Official guide: https://developer.android.com/guide/navigation/design/type-safety
