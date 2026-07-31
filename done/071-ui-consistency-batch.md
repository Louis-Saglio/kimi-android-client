# UI consistency batch

- **ID:** 071
- **Created:** 2026-07-31
- **Depends on:** 070

## Context

Small findings from the 2026-07-31 UI audit, batched to avoid ticket spam.
Depends on 070 so cosmetic work lands on the verified version bump, not
under it. Batching rationale as in 068: each item is small, none interacts
with another, and the alternative is eight micro-tickets.

## Description

1. **One k/M formatter.** Three scalers with three rounding policies exist:
   `formatFileSize` (`Composer.kt:326-330`), `formatContextSize`
   (`ModelPickerSheet.kt:131-135`), `formatTokenCount`/`scaled`
   (`ContextUsageRing.kt:142-152`) — two of them format the same conceptual
   quantity (context-window sizes) differently. Consolidate into
   `ui/common/Formatters.kt` with one decimal policy; `AGENTS.md` already
   names `Formatters` as the shared home.
2. **Navigation double-tap.** Session-row and sub-agent navigation use no
   `launchSingleTop` (`KimiNavHost.kt:49`, `:74-76`) — a fast double-tap
   stacks duplicate destinations. Add it.
3. **Dead sub-agent row tap.** `SubagentScreen.kt:93-97` renders
   `MessageList` without `onOpenSubagent`, so linked `Agent` rows inside a
   sub-agent transcript are clickable (chevron included) but navigate
   nowhere. Nested sub-agents exist — wire the navigation through to the
   nested page.
4. **Small composable dedup.** Extract a private `StatusBanner`
   (`ChatScreen.kt:353-381`, two copies), a `SheetCheckRow`
   (`ModelPickerSheet.kt:37-124` model/effort rows + `TodosWidget.kt:
   93-114`), and an `InfoDialog` (`CompactionDividerRow.kt:55-63` +
   `ContextUsageRing.kt:91-123`).
5. **Snackbar effect branches.** `ChatScreen.kt:82-106`: 8 of 10 branches
   are identical `showSnackbar(effect.message)` — put `val message: String`
   on the `Effect` interface, keep only the two special cases
   (`SendFailed` with Retry, `OpenFile`).
6. **`ErrorState` retry idiom.** Chat hardcodes `enabled = true`
   (`ChatScreen.kt:385`); sessions passes `enabled = !uiState.isLoading`
   (`SessionsScreen.kt:165`). Align.
7. **Viewer failure state.** `FullScreenImageViewer` (`ImageViewer.kt:
   158-174`) spins forever when `loadImageBytes` returns null; mirror the
   thumbnail sibling's failure fallback (`:50-57`).
8. **Nitpicks.** `SubagentScreen.kt:88` `uiState.error!!` → `?.let`;
   unify `contentType` style (`MessageList.kt:154` vs `SessionsScreen.kt:
   306`); narrow `ModelPickerSheet(uiState)` to the fields it reads.

## Acceptance criteria

- [x] Items 1-8 done.
- [x] `./gradlew :app:testDebugUnitTest` green; androidTests green on
  Firebase Test Lab (42 cases); robo green (2026-07-31).

## Out of scope

- Jump-to-bottom button / unread indicator (deferred since ticket 030).
- String-resource extraction — hardcoded English is the deliberate house
  style.
- Type-safe navigation routes — fine at four destinations; revisit if the
  graph grows.

## Notes

- Keep `LoadMoreFooter` (`SessionsScreen.kt:318-335`) as-is: it's an
  in-list footer, the shared `StateViews` genuinely don't fit — flagging so
  it isn't "fixed" wrongly.
