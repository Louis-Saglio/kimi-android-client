# Edit tool: unified diff screen (tap row → line diff of old/new)

- **ID:** 119
- **Created:** 2026-09-01
- **Depends on:** 118

## Context

The `Edit` call's input carries everything a diff needs — `path`,
`old_string`, `new_string`, `replace_all` — so the rich view is renderable
the moment the call starts, no result required. Today it expands to a JSON
dump of those three strings.

PO/UX discussion with Louis (2026-09-01), confirmed decisions:

- **Unified (stacked) diff only.** Side-by-side panes rejected: phone
  portrait is the primary form factor and the dual layout roughly doubles
  the renderer.
- **Use a diff library.** `com.googlecode.java-diff-utils:java-diff-utils`
  (small, standard JVM Myers implementation) — writing our own diff
  algorithm rejected as a trap.
- The row gains a `+a −b` stat chip so the timeline itself shows the edit's
  shape; the full diff lives one tap away on a dedicated screen (heavy,
  scrollable content → screen, per the two-level detail rule).

This ticket also introduces the two pieces of shared infrastructure the
later viewer tickets reuse: the `ToolPayload` sealed type and the
process-lifetime `ToolContentStore` for handing large payload content to a
screen without stuffing it into nav arguments.

## Description

**1. Typed payload.** New `sealed interface ToolPayload` in `data/chat`,
first variant `Edit(path, oldString, newString, replaceAll)`. Pure parser
`ToolPayloads.of(name, args): ToolPayload?` (null for anything else).
`ChatItem.ToolActivity` gains `payload: ToolPayload?`, derived in
`ChatTimeline` wherever `args` is set (same first-writer-wins rule).

**2. Content hand-off.** New process-lifetime `ToolContentStore`
(ui-layer, session-scoped singleton in the spirit of `SubagentStreamStore`):
`put(toolCallId, payload)` on navigation, `consume(toolCallId)` on screen
init. A missing entry (process death with the screen on top) renders the
job-screen-style error state with back navigation — the chat cold-restarts
from snapshot anyway, and re-tapping the row reopens the diff.

**3. Row.** Edit rows: pencil icon (custom Material Symbols path-data icon
in `ui/common/icons/`, `WorkspaceIcon.kt` precedent), existing path preview,
trailing stat chip `+a −b` (line counts of new/old, `successColor` /
`colorScheme.error` text), an "All" badge when `replace_all`. An Edit row
with a parsed payload never expands inline — tap or trailing chevron opens
the diff screen (sub-agent row precedent, ticket 045); an unparseable one
keeps the generic expansion.

**4. Diff screen.** Type-safe route `EditDiff(toolCallId)` (083 precedent);
navigation from the chat row and from the sub-agent page's rows. Screen:
`TopAppBar` (back, filename title, full path supporting line, `+a −b` in
the title row). Body: unified line diff between `old_string` and
`new_string` (`DiffUtils.diff` on `.lines()`), rendered as a `LazyColumn`:
removed lines on an error-tinted container with `−` gutter, added lines on
a success-tinted container with `+`, monospace; runs of ≥4 unchanged
context lines collapse into a "N unchanged lines" expander row (tap to
reveal, `rememberSaveable` state). New theme colors `diffAdded` /
`diffRemoved` container pairs via the `LocalSuccessColor` CompositionLocal
precedent, both themes, WCAG AA text contrast. Content width capped and
centered on large screens; reduced motion respected for the expander
animation.

**5. Tests.** Diff row-building (added/removed/context runs, collapse
threshold, empty-old = pure addition, `replace_all` no effect on the diff);
payload parsing (missing keys → null); store put/consume round-trip and
miss; row androidTest (chip text, "All" badge, tap navigates, chevron
target); screen androidTest (colors rows render, context expander toggles).

## Acceptance criteria

- [x] New tests above pass; existing suite green with no behavior-test
      changes.
- [x] Observable on device: an Edit row shows the pencil icon and `+a −b`
      chip (and "All" on a `replace_all` call); tapping it — while running
      or from history — opens the diff screen with colored +/- lines and
      collapsed context; an edit with identical old/new shows "No changes".
- [x] Same behavior from the sub-agent page's tool rows.
- [x] Both themes: added/removed rows readable (contrast checked).
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.
- [x] `docs/conventions.md` updated (ToolPayload + ToolContentStore); the
      new dependency recorded in the version catalog.

## Out of scope

- Side-by-side diff (rejected in discussion).
- Syntax highlighting, intra-line (word-level) highlights, editing.
- Diffing `Write` overwrites (no baseline available client-side).
- Read/Write/other tool screens — tickets 120–125.

## Notes

- Version-catalog entry for java-diff-utils in `gradle/libs.versions.toml`.
- The diff is line-level; `old_string`/`new_string` diffed directly — do
  NOT fetch the file or reconstruct surrounding context.
- Stat chip math: count of `DiffUtils` deltas by type, not raw line splits,
  so an unchanged trailing newline doesn't inflate the numbers.

## Implementation notes (2026-09-01)

- The ticket's `com.googlecode.java-diff-utils:java-diff-utils` coordinate no
  longer resolves on Maven Central (404) — the library relocated to
  `io.github.java-diff-utils:java-diff-utils`; used 4.17 (the current latest
  per maven-metadata). Same `com.github.difflib` API.
- The diff-row builder is `data/chat/EditDiffs` (`object … of(old, new)` →
  `Model(rows, addedCount, removedCount)`), following the ToolPreview/
  ToolDetailFields object-helper precedent. The default `DiffUtils.diff`
  overload emits change deltas only, so context runs are the gaps between
  deltas; runs of ≥4 lines fold into one `Row.Collapsed` carrying its lines.
- `ToolContentStore` reaches the navigation layer by MainActivity field
  injection (`KimiNavHost(toolContentStore)`): the `put` runs in the NavHost's
  `onNavigateToEditDiff` callback, wired identically for the chat and the
  sub-agent page. `EditDiffViewModel` consumes on init (dual-constructor
  pattern, tests pass the route object directly).
- The row's tap precedence is question → Edit payload → generic expansion →
  sub-agent link → job link; Edit rows never carry the two links, so the
  ordering is safe.
- Reduced motion: the expander reveal and its chevron read
  `Settings.Global.ANIMATOR_DURATION_SCALE` (0 → instant swap, `snap()` spec).
- Theme colors verified mathematically (WCAG AA normal text): light added
  4.6:1, light removed 5.6:1, dark added 6.1:1, dark removed 10.1:1 — ratios
  recorded in `ui/theme/Color.kt`. Not eyeballed on a physical device (FTL
  MediumPhone runs the system light theme).
- Existing suite: zero behavior-test changes — only additive test methods
  (ChatTimelineTest payload section, RoutesTest route round-trip, ToolRowTest
  Edit section). One FTL round exposed a test-side bug (preview and args value
  shared the same path string, duplicate-text assertion) — fixed in the test,
  not the code.
- Firebase Test Lab (MediumPhone.arm, API 36): 28/28 passed —
  EditDiffScreenTest (5), ToolRowTest (19), ToolRowExpandScrollTest (4).
  Matrix: https://console.firebase.google.com/project/kimi-code-android-client/testlab/histories/bh.a7586a76d07966a/matrices/4957438428224670614
- The gradle verification command above passed (also with
  `:app:assembleDebugAndroidTest`); the release APK publication is deferred to
  the merge session per the worktree hand-off, hence the unticked half of that
  box.
