# Move stop button and context usage ring into the composer

- **ID:** 053
- **Created:** 2026-07-30
- **Depends on:** none

## Context

Today the chat `TopAppBar` actions row carries the context usage ring (041),
the working spinner and the stop button (038, 009), while the composer holds
attach, the model chip and send/Steer. The web client puts all of it in the
composer toolbar: its right side is context ring → model pill → stop (only
while running) → send (`apps/kimi-web/src/components/chat/Composer.vue`).
This ticket converges the app back to web parity — the project's default
design compass (tickets 020, 021, 024, 038, 041).

This **deliberately reverses two earlier decisions**: 038 ("the working
spinner and stop button stay in the top bar — they are screen-level status")
and 041 ("the user picked screen-level chrome over web-parity composer
placement"). The reversal rationale (UX discussion, 2026-07-30):

- The ring is model-scoped information: its denominator *is* the model's
  context window (K2.7 = 262k, K3 = 1M). Next to the model chip that
  relationship is self-evident; in the top bar it is unexplained chrome.
- Stop is a turn action, the same category as Send/Steer, which already live
  in the composer. Turn controls should not be split across two screen
  regions.
- One-handed reachability: the composer is closer to the thumb, and while a
  turn streams, attention is already above the composer.
- The top bar declutters to just the session title + cwd.

Decisions taken (confirmed by the user, 2026-07-30):

- The ring goes inside the composer's action row, **right of the model chip**
  (the web has it left of the model pill; ours reads "identity → its
  consumption"). Every existing ring behavior survives the move: hidden until
  the first status data, tap → exact-numbers dialog, ≥80% error color,
  TalkBack description.
- Stop moves immediately **left of the send/Steer button**, composed only
  while `isWorking` — same conditional as today, and it still shows for
  web-started turns. No confirmation dialog (009's "abort is cheap and
  reversible" stands). The mis-tap risk of sitting 8dp from send was
  consciously accepted: the web ships the same pairing with a distinct
  square glyph.
- **The top-bar working spinner is dropped entirely.** Stop's presence, the
  Steer button swap and the "Steer the running turn…" placeholder already
  carry the working signal; a lone spinner in an otherwise empty actions row
  looks orphaned.
- The row gets tighter during a running turn (~315–340dp of fixed content on
  a 360dp screen). The model chip label — already ellipsized — absorbs the
  squeeze; the 48dp touch targets are not shrunk.

## Description

All changes are in `app/src/main/java/fr/louissaglio/kimi/android/ui/chat/`
(`ChatScreen.kt`, `components/Composer.kt`), reusing the existing
`ContextUsageRing.kt` composable as-is. No ViewModel or data-layer changes:
`UiState.contextUsage`, `UiState.isWorking` and `onStop` already exist.

1. `ChatContent` (`ChatScreen.kt`):
   - Remove the entire `actions` block from the `TopAppBar` (ring, working
     spinner, stop button) — the top bar keeps only the back arrow and the
     title/cwd.
   - Pass `uiState.contextUsage` and `onStop` down into `ChatInputBar`.
   - Update `ChatContentPreview`: set a `contextUsage` so the ring renders in
     the preview (it already sets `isWorking = true`, so stop + Steer show
     too).
2. `ChatInputBar` (`components/Composer.kt`):
   - New parameters: `contextUsage: ContextUsage?` and `onStop: () -> Unit`.
   - The action row becomes: attach → model chip → ring (only when
     `contextUsage != null`) → `Spacer(weight 1f)` → stop (only when
     `isWorking`) → send/Steer (existing swap unchanged).
   - Ring: reuse `ContextUsageRing` unchanged (48dp touch target, tap dialog,
     ≥80% error color, `contentDescription` with used/max/pct).
   - Stop: same visuals as the top-bar version — a 14dp filled square
     (`extraSmall` clip, `onSurfaceVariant`) inside a 48dp `IconButton`,
     `contentDescription = "Stop"`, immediately left of the send/Steer
     button.
3. Tests: no existing unit or androidTest asserts the top-bar placement
   (verified by grep, 2026-07-30), so no test changes are required; keep
   `./gradlew :app:testDebugUnitTest` green and verify the idle/working
   composer states via the preview and a manual run.

## Acceptance criteria

- [x] The chat top bar shows only the back arrow and title/cwd — no ring, no
      spinner, no stop — in every state (idle, working, reconnecting).
      *(The `actions` block is gone entirely, so no state can render them —
      code-verified 2026-07-30.)*
- [x] The composer action row shows the ring right of the model chip once
      `/status` data has arrived; it is hidden before that; tapping it opens
      the exact-numbers dialog; at ≥80% the arc uses the error color.
      *(Same `contextUsage?.let` conditional as the old top bar; the
      `ContextUsageRing` composable is reused untouched, dialog and error
      color included — code-verified; its percent logic stays covered by the
      041 unit tests.)*
- [x] While a turn runs — started from the app OR from the web — a stop
      square appears immediately left of the Steer button; tapping it aborts
      the turn within ~1–2 s, and the button disappears when the turn ends.
      No working spinner renders anywhere on the screen.
      *(Placement code-verified; the abort path itself is unchanged — 009's
      `onStop` unit tests still green. The spinner composable no longer
      exists on this screen. On-device tap check left to Louis via the
      published APK, as in 009.)*
- [x] With no turn running, the row is attach → model chip → ring → send, no
      stop. *(Stop composes only inside `if (isWorking)` — code-verified.)*
- [x] All interactive elements keep ≥ 48dp touch targets; TalkBack announces
      the ring with used/max/pct and the stop button as "Stop".
      *(Ring keeps its 48dp target and contentDescription from 041; stop is a
      stock `IconButton` (48dp) with `contentDescription = "Stop"`.)*
- [x] `./gradlew :app:testDebugUnitTest` passes; `ChatContentPreview` renders
      the working state with ring + stop + Steer.
      *(BUILD SUCCESSFUL 2026-07-30; preview sets `contextUsage` at ~78% with
      `isWorking = true`.)*
- [x] Release APK built and copied to the file server per AGENTS.md.
      *(2026-07-30: `app-release.apk`, 3.5 MB — slightly above the usual
      ~2.9 MB because this branch also carries the Crashlytics commit.)*

## Out of scope

- A `/compact` affordance at ≥80% (deferred in 041 — its own feature).
- Context usage caption on session-list rows (deferred in 041).
- Bringing back an ambient working indicator anywhere (spinner, shimmer,
  progress ring around stop). If it turns out to be missed, file a follow-up.
- Reordering the leading cluster (attach / model chip / ring) or merging the
  ring into the model chip.
- Any change to abort behavior itself (no confirmation, partial text kept) —
  009 stands.

## Notes

- Web reference: `apps/kimi-web/src/components/chat/Composer.vue` in
  MoonshotAI/kimi-code — toolbar right side: ring → model pill → stop (while
  running) → send, with the comment "Interrupt lives on a separate Stop
  button so the two can never be confused."
- Supersedes the placement decisions of tickets 038 and 041 — see Context, so
  a future cleanup does not "fix" the placement back.
- Ticket 009's note still applies: material-icons-core has no stop glyph —
  keep the plain filled square.
