# Compaction summary screen

- **ID:** 130
- **Created:** 2026-09-14
- **Depends on:** none

## Context

Tapping a done "Context compacted" divider opens an `AlertDialog` with the
summary as a plain `Text` (`CompactionDividerRow.kt`). On a real session this
fails twice: the summary is long rich markdown rendered as raw symbols, and
the dialog truncates it at the bottom with no way to scroll.

The app settled this class of problem on 2026-09-01 with tickets 119/120:
content you'd scroll through goes to a screen, handed off through a
process-lifetime store (`ToolContentStore`) because it's too large for nav
arguments. The compaction dialog predates that rule (ticket 056, web parity
at the time). This ticket moves the summary to a screen, following the
119/120 pattern.

Decisions (discussion with Louis, 2026-09-14):

- Render the summary with `MarkdownBody` (the chat message renderer, ticket
  020): headings, bullets, code blocks, links. Not the file content screen's
  plain monospace: the summary is model-generated markdown, and `MarkdownBody`
  handles a static document with one synchronous parse at first composition.
- Top bar: title "Compaction summary" (web-parity string, 056), plus a
  supporting line with the token delta when the divider carried it. Live
  dividers have the numbers; history-sourced ones don't (not persisted
  server-side, 056), so the line appears only when both are known.
- The divider row gains a trailing chevron when it holds a summary, the tool
  rows' navigation cue. Today's tappable row has zero affordance.
- Store miss (rotation/process death with the screen on top): the FileContent
  recovery applies, Back + re-tap. Accepted in 120, accepted here. The summary
  IS persisted in history (unlike tool output), so a future ticket could
  re-fetch on a miss; not worth the plumbing now.

Alternatives rejected:

- Scrollable markdown dialog: still height-capped, scrolls inside a scrolling
  chat, keeps the abandoned pattern alive.
- Bottom sheet: sheets are pickers in this app (`ModelPickerSheet`);
  drag-to-dismiss fights vertical scrolling.
- Inline expansion in the timeline: a huge block inside the `LazyColumn`
  breaks the scroll pinning, exactly what 119-123 moved off of.

## Description

**1. Store** (`ui/chat/CompactionSummaryStore.kt`). Mirror `ToolContentStore`:
`@Singleton class CompactionSummaryStore @Inject constructor()` with
`data class Entry(summary: String, tokensBefore: Long?, tokensAfter: Long?)`,
`put(dividerId, entry)`, and `consume(dividerId): Entry?` (read-and-remove: one
screen instance per entry). `MainActivity` injects it and passes it to
`KimiNavHost` alongside `toolContentStore`.

**2. Route + navigation** (`navigation/KimiNavHost.kt`).

- `@Serializable data class CompactionSummary(val dividerId: String)`: only the
  id rides the route, same rationale as `EditDiff`/`FileContent`.
- `composable<CompactionSummary>`: consume the staged entry once into a
  `remember` (the comment on the `FileContent` destination explains why the
  remember is required: a bare call would re-consume to null on the next
  recomposition), pass it to the screen.
- `ChatScreen` gains `onOpenCompactionSummary: (ChatItem.CompactionDivider) -> Unit`;
  the NavHost callback stages `Entry(summary, tokensBefore, tokensAfter)` under
  the divider id and navigates with `launchSingleTop = true`.

**3. Row** (`ui/chat/components/CompactionDividerRow.kt`).

- Delete the `showSummary` state and the `InfoDialog` usage. New param
  `onOpenSummary: () -> Unit`, called on tap; the row stays clickable only when
  `item.summary != null`.
- Trailing chevron when `item.summary != null`: reuse the tool rows' chevron
  icon and placement idiom (`ToolRows.kt`).
- `dividerLabel` and the pending spinner are untouched. Update the row's KDoc
  and the `ChatItem.CompactionDivider` KDoc (both document the dialog).

**4. MessageList threading.** New optional param
`onOpenCompactionSummary: (ChatItem.CompactionDivider) -> Unit = {}` forwarded
to `CompactionDividerRow`. `ChatScreen` passes the nav callback.
`SubagentScreen` passes nothing: compaction dividers only exist in the main
session timeline (`ChatTimeline`), `SubagentStreamStore` never produces one,
and the default keeps that call site compiling.

**5. Screen** (`ui/chat/CompactionSummaryScreen.kt`).

- `CompactionSummaryScreen(entry: CompactionSummaryStore.Entry?, onNavigateBack: () -> Unit)`.
  No ViewModel: static snapshot, the 120 precedent.
- Scaffold + `TopAppBar`: back arrow (match the file content screen's), title
  `Column` with "Compaction summary" and, when both token numbers are
  non-null, a supporting line "26.5k → 386 tokens" via
  `ui/common/Formatters.formatTokenCount` (the divider label's formatter).
- Body, entry non-null: `SelectionContainer` around a vertically scrolling
  content column holding `MarkdownBody(entry.summary)`; match
  `FileContentScreen`'s layout conventions (content padding, centered 840dp
  cap).
- Body, entry null: the missing-content state, same message and Back recovery
  as `FileContentRoute`'s miss branch.
- Snapshot semantics: the screen does not live-update (a done divider's
  summary is immutable anyway).

**6. `InfoDialog` stays**: the context-usage ring dialog still uses it. Only
the compaction path stops using it.

**7. Tests.**

- Unit: `CompactionSummaryStoreTest` (put/consume round-trip, consume-once,
  miss → null).
- androidTest: new `CompactionSummaryScreenTest` (summary markdown renders,
  delta supporting line present when both numbers known and absent when null,
  store-miss state + Back); `CompactionDividerRow` androidTest (chevron only
  when a summary exists, tap fires `onOpenSummary`, no dialog); `RoutesTest`
  +1 for the new route.
- Grep found no existing test asserting the dialog ("Compaction summary");
  verify and update if one surfaces.

## Acceptance criteria

- [ ] New unit + androidTests above pass; existing suite green.
- [ ] Observable on device: tapping a "Context compacted" divider (live and
      history-sourced) opens the screen, the summary renders as markdown and
      scrolls to the bottom without truncation, and its text is selectable. A
      live divider's screen shows the token delta under the title; a history
      one doesn't.
- [ ] The divider row shows a chevron only when it holds a summary; the
      pending row ("Compacting context…") is unchanged and not clickable.
- [ ] Rotating the device on the screen shows the missing-content state;
      Back + re-tap recovers the content.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Re-fetching the summary from history on a store miss (possible since the
  summary is persisted; the plumbing isn't worth it, see Context).
- Any change to the context-usage ring dialog (`InfoDialog` stays for it).
- Live-updating the screen during a running compaction (the summary only
  exists once completed).
- Reworking the divider label or the token delta format.

## Notes

- What the summary is on the wire: ticket 056 and `docs/server-api.md`
  § Compact (`compaction.completed` `result.summary` live; the persisted
  `compaction_summary` message for history).
- `MarkdownBody` for a static document: the streaming machinery seeds
  synchronously at first composition; chat messages of comparable size already
  use it, so a multi-KB summary is fine.
- Chevron idiom source: the Edit/Read/Write rows in `ToolRows.kt` (ticket
  120's "tap or trailing chevron opens the content screen").

## Implementation notes

**2026-10-28 —** `CompactionSummaryStore` (put/consume), the `CompactionSummary`
route + `CompactionSummaryScreen` (MarkdownBody in a selectable scroll, token
delta in the top bar), the divider row's chevron and `onOpenSummary`, and the
MessageList/ChatScreen threading. Tests: `CompactionSummaryStoreTest`,
`CompactionSummaryScreenTest`, `CompactionDividerRowTest`, `RoutesTest`.
