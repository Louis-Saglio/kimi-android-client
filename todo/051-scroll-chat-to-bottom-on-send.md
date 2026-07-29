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

When the user taps Send / Steer (or the IME Send action), scroll the message
list fully to the bottom alongside dispatching the send:

- Hoist the `LazyListState` to `ChatScreen` — `MessageList` already accepts it
  as a parameter (`components/MessageList.kt:39`); preserve the current
  open-at-the-newest-row default (`initialFirstVisibleItemIndex`).
- Wrap the `onSend` callback at the ChatScreen level: launch the scroll on the
  current items, then call the ViewModel's send. Once the tail is visible,
  `isAtBottom` flips true, so the appended user echo and the streaming reply
  re-pin through the existing `LaunchedEffect(items)` machinery — no new
  follow logic needed.
- `scrollToItem(lastIndex)` alone tops the row; a last bubble taller than the
  viewport (long answer, keyboard-open small viewport) still hides its tail.
  Extract the effect's existing scroll-to-item + overshoot-`scrollBy` sequence
  into an `internal suspend fun LazyListState.scrollToBottom(...)` in
  `MessageList.kt` and call it from both the effect and the send wrapper —
  one implementation, no duplicated overshoot math.
- Keep the keyboard open on send (unchanged; matches the web client).
- Do not route the scroll through the ViewModel's effect channel: it is pure
  list UI state, not a snackbar/intent one-shot, and the echo append already
  re-pins via `isAtBottom` even if the echo lands a frame later.
- Instrumented test (extend
  `app/src/androidTest/java/fr/louissaglio/kimi/android/ui/chat/ChatAutoScrollTest.kt`
  or a sibling): list scrolled up, trigger the send wrapper → the last item
  becomes fully visible and stays pinned as an appended echo streams (RED
  before the fix, GREEN after).

## Acceptance criteria

- [ ] New instrumented test green on Firebase Test Lab (MediumPhone.arm,
  API 34), confirmed RED on pre-fix code.
- [ ] On device: scrolled up with the keyboard open, send → the list jumps to
  the bottom and the echo + streaming reply keep following.
- [ ] On device: same while a turn is running (Steer button).
- [ ] `./gradlew :app:testDebugUnitTest` green.
- [ ] Release APK built and copied to the file server per `AGENTS.md`.

## Out of scope

- The viewport-shrink pin — ticket 050.
- Hiding the keyboard on send.
- Jump-to-bottom button with unread indicator when scrolled up (deferred since
  ticket 030).

## Notes

- Send entry points in `components/Composer.kt`: the `FilledIconButton`/`Steer`
  `Button` and `KeyboardActions(onSend = ...)` — all already funnel through a
  single `onSend`, so one wrapper covers them.
