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

- [ ] No string route templates, `Uri.encode`, or `navArgument` blocks
  remain; all four destinations use `@Serializable` routes.
- [ ] Navigation flows verified on-device/emulator: sessions → chat,
  chat → sub-agent page, nested sub-agent (same session), settings, back.
- [ ] A session whose title/cwd contains `/ & # % ?` and spaces shows the
  correct title in the chat top bar (the case hand-rolled encoding breaks
  on).
- [ ] `./gradlew testDebugUnitTest` green + Firebase robo green.
- [ ] Release APK published to the file server per `AGENTS.md`.

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
