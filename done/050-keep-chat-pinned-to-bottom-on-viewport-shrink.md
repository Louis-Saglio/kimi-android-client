# Keep the chat pinned to the bottom when the viewport shrinks (keyboard, approval card)

- **ID:** 050
- **Created:** 2026-07-29
- **Depends on:** none

## Context

Bug report from Louis (2026-07-29): sending a follow-up message while the
keyboard is open does not auto-scroll. He confirmed the mechanism on device:
tapping the input (keyboard opens), then scrolling down again so the tail sits
just above the keyboard, makes auto-scroll work again.

Root cause, confirmed by code inspection:

- The manifest sets `adjustResize` (`app/src/main/AndroidManifest.xml:15`) and
  the composer docks above the keyboard via `Modifier.imePadding()`
  (`components/Composer.kt:73`). `ChatScreen` lays `MessageList` out with
  `Modifier.weight(1f)` above the bottom bar (`ChatScreen.kt:339-361`), so when
  the IME opens, the list viewport shrinks from the bottom by the IME height.
- `LazyColumn` anchors the scroll position on the first visible item's key (no
  `reverseLayout`, see the design comment in `components/MessageList.kt`), so
  the scroll offset does not move: the conversation tail silently slides below
  the shrunken viewport end.
- `isAtBottom` (`components/MessageList.kt:67-77`) is purely positional ("last
  row visible AND its tail within 32.dp of `viewportEndOffset`"). After the
  shrink, the tail sits roughly one keyboard-height beyond the viewport end, so
  `isAtBottom` flips to false and stays false.
- When the follow-up (and then the streaming reply) is appended, the re-pin
  effect (`components/MessageList.kt:78-94`) checks `isAtBottom` → false → no
  scroll. The follow never re-attaches on its own. The keyboard stays open
  after send, so this hits every follow-up sent while typing.

The IME is the dominant trigger, but any bottom-bar growth detaches the follow
the same way (e.g. an approval card appearing while at the bottom).

Options discussed with Louis:

1. Stay pinned on viewport shrink: if the viewport end moves up while at
   bottom, scroll by that delta so the tail stays visible (WhatsApp/Signal
   behavior — the conversation slides up with the keyboard).
2. Latch at-bottom until a user scroll gesture: layout-driven detaches never
   clear the flag. Rejected: leaves the user staring at stale content while
   typing — the tail still slides under the keyboard, it just re-pins later.
3. Force scroll-to-bottom on send: sending is an explicit "go to the newest"
   intent.

Louis chose 1 + 3. This ticket is option 1; option 3 is ticket 051.

## Description

In `MessageList`
(`app/src/main/java/fr/louissaglio/kimi/android/ui/chat/components/MessageList.kt`),
keep the tail visible when the viewport shrinks while at bottom:

- Track `layoutInfo.viewportEndOffset` across layout passes (in the existing
  at-bottom `snapshotFlow` collector, or a sibling collector keyed on
  `listState`). When it **decreases** and the pre-shrink `isAtBottom` value is
  true, `listState.scrollBy(oldEnd - newEnd)` so the tail stays pinned.
- The comparison must use the at-bottom value from **before** the shrink (the
  previous emission), not a fresh read — after the shrink the positional
  computation already returns false. Same ordering argument as the existing
  `LaunchedEffect(items)` re-pin.
- Only the shrink direction needs handling; on growth the pin holds by itself.
- No animation work: the IME inset animates over several frames, so per-frame
  `scrollBy` of each delta produces the smooth slide-up with the keyboard for
  free.
- A scrolled-up reader is untouched: not at bottom → no `scrollBy`, and the
  first-visible-key anchor preserves their reading position.
- Extend `app/src/androidTest/java/fr/louissaglio/kimi/android/ui/chat/ChatAutoScrollTest.kt`
  (fixed-height box, overflowing content):
  - regression: pinned at bottom, shrink the container height → the last item
    stays fully visible (RED before the fix, GREEN after);
  - scrolled up, shrink the container height → the same item stays
    first-visible (no yank).

## Acceptance criteria

- [ ] Extended `ChatAutoScrollTest` green on Firebase Test Lab
  (MediumPhone.arm, API 34), shrink case confirmed RED on pre-fix code.
- [ ] On device: opening the keyboard while at the bottom keeps the tail
  visible above the keyboard; a follow-up echo and its streaming reply keep
  following without any manual scroll.
- [ ] On device: an approval card appearing while at the bottom keeps the pin.
- [ ] `./gradlew :app:testDebugUnitTest` green.
- [ ] Release APK built and copied to the file server per `AGENTS.md`.

## Out of scope

- Force scroll-to-bottom on send — ticket 051.
- Jump-to-bottom button with unread indicator when scrolled up (deferred since
  ticket 030).
- Latching at-bottom until a user gesture (option 2, rejected in the
  discussion).
- Hiding the keyboard on send (unchanged; matches the web client).

## Notes

- Implement before ticket 051: both touch `MessageList.kt` / `ChatScreen.kt`.
- The shrink path also covers the "connection lost" banner and the approval
  card — both grow the chrome around the weighted list, shrinking its viewport
  from the bottom.
