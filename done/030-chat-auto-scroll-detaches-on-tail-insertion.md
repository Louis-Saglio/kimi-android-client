# Chat auto-scroll detaches on every tail insertion (tool calls)

- **ID:** 030
- **Created:** 2026-07-24
- **Depends on:** none

## Context

Bug report from Louis: in an active session, the chat stops following new
content to the bottom every time a tool call occurs. Investigation (confirmed
empirically by Louis on 2026-07-24) showed the cause is broader: **any** tail
insertion detaches the follow behavior; tool calls are just the dominant
mid-stream source of insertions.

`ChatScreen.MessageList` (`ChatScreen.kt:283-301`) relies on
`LazyColumn(reverseLayout = true)` alone to stay pinned to the bottom — there
is no `LazyListState`, no at-bottom tracking, no programmatic scroll anywhere
in the chat screen. That design assumption is wrong when items have stable
keys: on every measure pass `LazyList` re-anchors the scroll position to the
**key** of the previously first-visible item
(`updateScrollPositionIfTheFirstItemWasMoved` → `findIndexByKey`), with no
special case for "already at (0,0)". Streaming text updates the bottom bubble
in place with the same id, so the pin holds; a tool call inserts a new-key row
at index 0 (in reversed order), the old anchor moves to index 1, and the
viewport silently follows the anchor instead of the newest content. It never
recovers because the anchor key always still exists.

Why it went unnoticed: for content shorter than the viewport,
`measureLazyList`'s scroll-back branch re-pins to (0,0) on every insertion, so
the bug only manifests once the conversation is taller than the screen (any
real session).

Alternatives considered and rejected:

- Drop the item keys (index-based default keys hit the `findIndexByKey` fast
  path and keep (0,0)): keys are load-bearing for `ToolRow`'s
  `rememberSaveable` expanded state and reorder stability.
- Keep reverseLayout-only and accept the detach: the current UX is broken in
  exactly the case the app exists for (watching a live turn).

## Description

Add explicit bottom-following to `MessageList` in
`app/src/main/java/fr/louissaglio/kimi/android/ui/chat/ChatScreen.kt`:

- Hoist `val listState = rememberLazyListState()` (injectable as a parameter
  with that default, so tests can observe it).
- Track at-bottom continuously into a remembered `isAtBottom` state via
  `snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= threshold }`
  (small pixel threshold, e.g. 32.dp, so a near-bottom fling still counts).
- `LaunchedEffect(items.size) { if (isAtBottom) listState.scrollToItem(0) }` —
  this must read the **pre-insertion** at-bottom value, which works because
  `LaunchedEffect` runs after composition apply, before the layout pass that
  flips `firstVisibleItemIndex`; do not derive at-bottom lazily at scroll
  time.
- In-place growth of the bottom item (streaming text) needs no handling: with
  `reverseLayout` a (0,0) pin survives growth of item 0.
- Make `MessageList` `internal` and add an instrumented Compose test
  (`app/src/androidTest/.../ui/chat/ChatAutoScrollTest.kt`) rendering it in a
  fixed-height box with overflowing content:
  - regression: pinned at bottom, append an item → still pinned at (0,0)
    (fails before the fix, passes after);
  - scrolled up, append an item → same item stays first-visible (no yank).

## Acceptance criteria

- [x] New `ChatAutoScrollTest` green on Firebase Test Lab (MediumPhone.arm,
  API 34), regression case RED before the fix. *(2026-07-24: RED confirmed on
  pre-fix code — appended tool row never scrolled into view, 1/1 failed
  (matrix 7550721201022096932); GREEN after the fix, 2/2 passed
  (matrix 5547400614401170736).)*
- [x] `./gradlew :app:testDebugUnitTest` green. *(2026-07-24: full suite green.)*
- [x] Release APK built and copied to the file server per `AGENTS.md`.
  *(2026-07-24: `/home/fileserver/files/app-release.apk`, ~4.6 MB.)*

## Out of scope

- Jump-to-bottom button with unread indicator when scrolled up (possible
  follow-up; the current design intentionally has no such affordance).
- Animating the follow scroll (`scrollToItem` is instant; fine for streaming).
- Same treatment for the sessions list (it has no live tail-insertion stream).

## Notes

- Timeline insertions that trigger the detach: `ChatTimeline.upsertTool`
  (tool row), the post-tool `live_N` bubble in `appendDelta`, `appendSpliced`
  (user echo). In-place updates (`appendDelta` on an open bubble,
  `tool.progress`) do not.
