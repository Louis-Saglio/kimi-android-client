# Fix fork navigation swallowed by launchSingleTop

- **ID:** 130
- **Created:** 2026-09-14
- **Depends on:** 127

## Context

Louis tested ticket 127: forking from the chat overflow menu does NOT open
the fork. He saw "a flash, like a new screen was opening", but the title
stayed the old one. Root cause, confirmed in the androidx source
(navigation 2.9.8):

- `KimiNavHost.kt:126-130` navigates `Chat(source)` → `Chat(fork)` with
  `launchSingleTop = true`, copied from the session list's callback
  (KimiNavHost.kt:84-89) where it guards against double-taps.
- `NavControllerImpl.launchSingleTopInternal` matches singleTop on
  destination identity only (`node.id == currentBackStackEntry.destination.id`).
  Both routes are the `Chat` destination, so the fork navigation takes the
  singleTop path instead of pushing a new entry.
- That path recreates the top entry with `NavBackStackEntry.create(entry,
  newArgs)`, which reuses `entry.id` (navigation-common
  `NavBackStackEntry.kt:261`). The id keys the ViewModelStore, so the
  SAME `ChatViewModel` instance survives — and it read
  `routeArgs.sessionId` once at construction (ChatViewModel.kt:90).
- Net effect: the entry is recreated with the fork's arguments (the flash),
  but the retained ViewModel keeps rendering the source session. The fork
  itself is created server-side and appears in the list correctly.

Structural fix, not a patch: the fork path must push a genuinely new back
stack entry. Making ChatViewModel react to argument changes would fight
the framework; removing `launchSingleTop` is the correct fix. Double-tap
stacking is not a concern on this path — the overflow menu closes on tap.

## Description

**1. Fix.** In `navigation/KimiNavHost.kt`, the `Chat` destination's
`onNavigateToChat` callback (the fork path only): drop
`launchSingleTop = true`, keep a plain `navigate(Chat(...))`. The session
list's own callback keeps its guard untouched. Update the comment at
:124-125 to state why singleTop must NOT be used here (same-destination
navigation reuses the entry id and its ViewModel).

**2. Regression note.** There is no unit-testable seam for NavController
option behavior; `NavHost` instrumentation needs a device, which the VPS
lacks. Verification is manual (acceptance criteria below) plus
`:app:compileDebugAndroidTestKotlin` staying green.

## Acceptance criteria

- [ ] The fork callback in KimiNavHost navigates without
      `launchSingleTop`; the comment explains why.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      --max-workers=1 --no-daemon` passes; androidTest still compiles.
- [ ] Manual (Louis): chat overflow → Fork session lands on the forked
      chat with the `Fork: ` title; system back returns to the source
      session.

## Out of scope

- Changing the session list's launchSingleTop (Sessions→Chat is a
  different-destination navigation; the guard is correct there).
- Reworking ChatViewModel to observe argument changes.
- The list fork's stay-in-list behavior (ticket 128) — unchanged.
