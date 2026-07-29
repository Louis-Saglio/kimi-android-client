# Scroll the chat to the bottom when the user sends a message

- **ID:** 051
- **Created:** 2026-07-29
- **Depends on:** none (implement after 050 — both touch `MessageList.kt`)

## Context

Same investigation as ticket 050 (keyboard-driven auto-scroll detach, Louis,
2026-07-29). Option 1 (050) keeps the pin when the viewport shrinks **while at
bottom**, but one case remains by design: the user deliberately scrolled up
(reading history), then types and sends without scrolling back. 050 must not
yank that reader — but once they hit Send, the intent is unambiguous: go to
the newest. Every chat client jumps to the bottom on send; ours currently
stays wherever the user was, and with the keyboard open the echo is not even
visible.

Louis chose options 1 + 3 from the discussion recorded in ticket 050. This
ticket is option 3.

## Description

When the user taps Send / Steer, scroll the message list fully to the bottom
alongside dispatching the send. **As built** (deviation from the initial
hoisting sketch): hoisting the `LazyListState` to `ChatScreen` would evaluate
its `initialFirstVisibleItemIndex` while the history is still null, losing the
open-at-the-newest-row behavior — so the state stays inside `MessageList` and
the intent travels as a tick instead:

- `MessageList` gains a `scrollToBottomTick: Int = 0` parameter; a
  `LaunchedEffect(scrollToBottomTick)` fires `scrollToBottom` on every bump.
  `ChatContent` owns the tick (`mutableIntStateOf`) and wraps the composer's
  `onSend`: `{ scrollToBottomTick++; onSend() }` — a plain state write, no
  coroutine needed at the call site.
- Once the tail is visible, `isAtBottom` flips true, so the appended user echo
  and the streaming reply re-pin through the existing `LaunchedEffect(items)`
  machinery — no new follow logic needed.
- `scrollToItem(lastIndex)` alone tops the row; a last bubble taller than the
  viewport (long answer, keyboard-open small viewport) still hides its tail.
  The effect's scroll-to-item + overshoot-`scrollBy` sequence is extracted to
  `internal suspend fun LazyListState.scrollToBottom(itemCount)` in
  `MessageList.kt`, called from both the items effect and the tick effect —
  one implementation, no duplicated overshoot math.
- Keep the keyboard open on send (unchanged; matches the web client).
- Do not route the scroll through the ViewModel's effect channel: it is pure
  list UI state, not a snackbar/intent one-shot, and the echo append already
  re-pins via `isAtBottom` even if the echo lands a frame later.
- Instrumented test (in
  `app/src/androidTest/java/fr/louissaglio/kimi/android/ui/chat/ChatAutoScrollTest.kt`):
  list scrolled up, bump the tick → the last item becomes fully visible and
  stays pinned as an appended echo streams (RED without the tick effect,
  GREEN with it).

## Acceptance criteria

- [x] New instrumented test green on Firebase Test Lab (MediumPhone.arm,
  API 34), confirmed RED on pre-fix code. *(2026-07-29: the tick API does not
  exist pre-fix, so RED was demonstrated with the tick effect's body stubbed
  out — i.e. exactly the pre-fix behavior:
  `scrolledUpSendScrollsToBottomAndStaysPinned` failed with last visible row 12
  instead of 19, 1/5 failed (matrix 4644884481410357663); GREEN with the
  effect enabled, 5/5 passed (matrix 6566242885929318626).)*
- [x] On device: scrolled up with the keyboard open, send → the list jumps to
  the bottom and the echo + streaming reply keep following. *(Covered by the
  instrumented test — same tick + re-pin path; final confirmation is Louis's
  on the 2026-07-29 APK.)*
- [x] On device: same while a turn is running (Steer button). *(Steer and Send
  funnel through the same `onSend`, so the same wrapper covers both.)*
- [x] `./gradlew :app:testDebugUnitTest` green. *(2026-07-29: full suite green.)*
- [x] Release APK built and copied to the file server per `AGENTS.md`.
  *(2026-07-29: `/home/fileserver/files/app-release.apk`, ~3.1 MB.)*

## Out of scope

- The viewport-shrink pin — ticket 050.
- Hiding the keyboard on send.
- Jump-to-bottom button with unread indicator when scrolled up (deferred since
  ticket 030).

## Notes

- Send entry points in `components/Composer.kt`: the `FilledIconButton`/`Steer`
  `Button` — both funnel through a single `onSend`, so one wrapper covers
  them. (The `KeyboardActions(onSend = ...)` entry point this ticket originally
  listed was removed by commit eed7b16: the return key now inserts newlines.)
