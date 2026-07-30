# Structural fix for duplicate tool-row keys in the chat timeline

- **ID:** 055
- **Created:** 2026-07-30
- **Depends on:** none

## Context

On 2026-07-30 the app crashed on Louis's phone three times in ~30 minutes,
always on the chat screen (reading a stream, reading a pending
`AskUserQuestion`, tapping submit). Crashlytics shows one root exception for
all three (issues `c0b2cc93c2b29fadef28bf20b3a4be98` and
`c9d3c17280a5d1bc2260dbc2303ff8a8`, app version 1.0):

```
java.lang.IllegalArgumentException: Key "tool_<callId>" was already used.
If you are using LazyColumn/Row please make sure you provide a unique key for each item.
```

Mechanism (verified against the stacks and the code): on reconnect,
`SocketEvent.Reconnected` triggers **both** the WebSocket cursor-resume
replay of missed events **and** `resyncHistoryAfterDrop()`'s REST refetch
(`ChatViewModel.kt:601-609`). The refetch commits recent history — including
in-flight tool calls — into `committed` rows; a replayed tool event for the
same call then lands in `ChatTimeline.upsertTool()` (`ChatTimeline.kt:337`),
which searches **only the live list** and appends a new live row when not
found. The id now exists in both lists, `items() = committed + live` feeds
two rows with the same key to the `LazyColumn`, and the next composition
crashes.

**Decision (Louis, 2026-07-30): no quick fix.** Patching `upsertTool` to also
check `committed` would close this one path, but the underlying defect is
structural: the timeline keeps **two parallel row lists in the same id
space**, and every merge point is responsible for deduplicating against every
other list by hand. The codebase already carries proof that this model keeps
losing paths — `completeOrphanResults` (`ChatTimeline.kt:128`) was added for
the *same crash signature* on the page-boundary path (on-device crash,
2026-07-29), and the merge points keep multiplying (`replaceHistory`,
`prependHistory`, `replaceTranscript`, `prependTranscript`, `TurnStarted`
sealing, splices, live upserts). This ticket exists to eliminate the class,
not the instance.

## Description

Restructure `ChatTimeline` so that **two rows with the same id are
unrepresentable** (or centrally impossible), regardless of the path a row
arrives on — history refetch, history/transcript prepend, cursor replay,
live delta, splice, or turn sealing. The implementer chooses the shape, but
the result must satisfy:

- **Single key space, single owner.** A tool-call id (and equally a message
  id) maps to at most one row by construction — e.g. one id-keyed row store
  with an ordering list and committed/live as row state, or a single
  insertion choke point that upserts across the *whole* timeline while
  preserving position. Ad-hoc pairwise dedup between lists
  (`pruneCommittedLive`, `completeOrphanResults`) should be absorbed into the
  structure, not extended with a third sibling.
- **Late events heal the existing row.** A replayed/late `tool.result` (or
  progress/delta) for an already-committed call must update that row's
  status/output in place, not be dropped and not duplicated — the current
  live-path merge semantics (`name/preview/input` fill blanks, result
  replaces output wholesale) must survive wherever the row lives.
- **Audit the other row types while restructuring.** Assistant bubbles dedup
  by exact text equality and user messages by id-or-text
  (`pruneCommittedLive`, `appendSpliced`) — same class of fragility, no
  observed crash. Cover them by the same invariant if the chosen structure
  allows it without blowing the diff budget; otherwise document in Notes why
  not.
- Keep the externally visible behavior: grouping rules (first-call id as
  group key), scroll-anchor stability on prepend (ticket 043/046 semantics),
  sub-agent transcript backing (045/046), todo-call suppression (042).

## Acceptance criteria

- [x] New unit test reproducing the reported race **fails before the fix and
  passes after**: `replaceHistory(keepLive = true)` commits a tool call, then
  a replayed `ToolCallStarted`/`ToolCallResult` for the same id arrives →
  `items()` contains exactly one row with that id, holding the result's
  status/output.
  *(`ChatTimelineTest.a replayed tool event after a keep-live refetch heals the
  committed row` — verified RED on the pre-fix reducer (duplicate row), GREEN
  after.)*
- [x] An invariant test asserts `items()` never contains duplicate ids after
  scripted interleavings of refetch + replayed events covering the other
  merge paths (turn-end refetch with `keepLive = false` followed by a late
  tool event; prepend vs. live; `TurnStarted` sealing on the sub-agent path).
  *(`ChatTimelineTest.items never contains duplicate row ids across refetch
  and replay interleavings` — five scripts, including transcript refetch vs
  replay; RED on the pre-fix reducer. Plus
  `a late tool event heals the committed row in place, preserving its
  position`.)*
- [x] `./gradlew :app:testDebugUnitTest` green, including all existing chat
  timeline/autoscroll tests unmodified in behavior.
- [ ] Post-release verification: no new `Key ... was already used` events in
  Crashlytics for the next app version (queryable via the
  `firebasecrashlytics.googleapis.com` v1alpha `reports/topIssues` API,
  project `kimi-code-android-client`).

## Implementation notes (2026-07-30)

Chosen shape: **one ordered row list + a boundary**. `ChatTimeline` now holds a
single `rows` list; committed vs live is a *position* (`liveStart`), not a list
membership. All structural mutations go through boundary helpers
(`replaceCommitted`, `prependCommitted`, `appendCommitted`, `appendLiveTool`).
Tool rows have a single insertion choke point: `upsertTool` → `healTool`
searches the WHOLE timeline tail-first, so a late/replayed event for an
already-committed call heals that row in place (position and group preserved);
only an unknown id appends a new live row at the tail. The one remaining
live-vs-committed merge is `pruneCoveredLiveRows`, the replace-time rule that
drops live rows a refetch has since committed (it replaces
`pruneCommittedLive`); `completeOrphanResults` stays as the page-boundary
in-place heal. Bubble audit: assistant/user bubbles are NOT id-covered — live
bubbles carry synthetic `live_N` keys and deltas/splices carry no stable id, so
an id-keyed structure has nothing to match on; they keep content dedup at the
replace choke point. The id-uniqueness invariant still holds for them by
construction (`live_N` counter, unique message ids per refetch), which is what
the LazyColumn crash actually required.

## Out of scope

- Changing the socket/reconnect strategy itself (ticket 048 machinery:
  cursor resume, `resync_required`, ON_STOP/ON_START restarts) — the fix is
  in the reducer, not the transport.
- Reporting invariant violations to Crashlytics as non-fatals
  (`recordException`) — worth a follow-up ticket if the restructure leaves
  any recoverable dedup path worth monitoring.
- Rethinking bubble dedup heuristics beyond what the structure gives for
  free (see the audit bullet above).

## Notes

- Crash evidence lives in Crashlytics issues `c0b2cc93c2b29fadef28bf20b3a4be98`
  (2 events) and `c9d3c17280a5d1bc2260dbc2303ff8a8` (1 event); blame frame
  `MessageList.kt:40` (`scrollToBottom`) for the first.
- The duplicate id in two of the crashes (`tool_6eVjyMIhFUN5ChwAD0hYMqla`)
  was the session's pending `AskUserQuestion` call — a long-running
  in-flight call is the easiest repro: it sits in live rows, gets committed
  by a mid-turn keep-live refetch, then its replayed events re-create it.
- Prior same-class fix for reference: `completeOrphanResults` in
  `ChatTimeline.kt` (page-boundary orphan results, verified on-device
  2026-07-29).
