# Sub-agent detail page with live streaming

- **ID:** 045
- **Created:** 2026-07-28
- **Depends on:** 044

## Context

Ticket 044 stops sub-agent frames from corrupting the main chat. The follow-up
product decision (2026-07-28, with Louis): tapping an `Agent` tool row opens a
**dedicated read-only page** showing that sub-agent's stream — deliberately
similar to the main chat (user prompts, assistant text, thinking traces,
tool-call rows) but containing only useful items: no composer (you cannot talk
to a sub-agent), no question wizard, no approval cards.

Wire facts that shape the design (`docs/server-api.md`, Sub-agents):

- Sub-agent frames share the session channel, tagged `payload.agentId =
  "<subagentId>"`; 044's filter is extended here to *route* instead of drop.
- `subagent.spawned` is the only carrier of identity metadata
  (`parentToolCallId`, `subagentName`, `description`, `runInBackground`);
  `subagent.started`/`completed`/`failed`/`suspended` carry just `subagentId`
  (+ `resultSummary`/`error`/`reason`).
- Cold linkage: the `Agent` tool result text starts with `agent_id: agent-12`
  (first line, version-pinned format) — parseable when the spawn happened
  before the app subscribed.

Alternatives rejected: a todos-style summary widget (duplicates the tool row;
an `Agent` call is meaningful and stays in the timeline); live-only visibility
without a page (Louis explicitly chose the page); showing internal nudge turns
hidden (Louis chose to show all prompts as user bubbles).

## Description

1. **Socket events.** Add payload DTOs and `SessionEvent`s for
   `subagent.spawned` (subagentId, name, parentToolCallId, description,
   runInBackground), `subagent.started`, `subagent.completed` (resultSummary),
   `subagent.failed` (error), `subagent.suspended` (reason). Carry `agentId`
   on the routed transcript events (044's DTOs already decode it).
2. **Per-sub-agent stream store.** A session-scoped store in the data layer
   (e.g. `SubagentStreamStore`, populated from the socket event flow):
   `StateFlow<Map<String, SubagentState>>` where `SubagentState` holds the
   identity metadata, lifecycle status (`Queued/Working/Suspended/Completed/
   Failed`), the result summary, and a `ChatTimeline`-style reducer fed with
   the sub-agent's own frames (spliced prompts → user bubbles, deltas → live
   bubble, tool calls → tool rows). Reuse `ChatTimeline` if it fits without
   main-specific assumptions; otherwise extract the shared reduction. Delta
   coalescing (~100 ms window, injectable like `streamCoalesceWindowMillis`)
   applies here too — `MarkdownBody` re-parses per emission.
3. **Row linkage.** `ChatTimeline` marks `Agent` tool rows with the linked
   subagentId: live via `subagent.spawned.parentToolCallId`; committed rows
   via the `agent_id: X` first line of the tool result text (absent → row
   stays non-tappable). `ToolRows`: linked rows show an open affordance
   (chevron) and emit a click with the subagentId.
4. **Navigation + screen.** New `KimiRoutes.Subagent(sessionId, subagentId)`
   destination in `KimiNavHost`. `SubagentScreen` + `SubagentViewModel` render
   the store entry read-only: top bar with description + subagent type +
   status chip (Working/Completed/Failed), the timeline (user/assistant
   bubbles, thinking, tool rows — reuse `Bubbles`/`ToolRows`/`MessageList`),
   auto-scroll behavior mirroring chat. No composer or bottom bar. Unknown
   subagentId (deep-ish state loss) → the standard `StateViews` error/empty
   state.
5. **Chat integration.** `ChatViewModel` forwards routed sub-agent events into
   the store and keeps consuming only `agentId == "main"` itself (044
   behavior unchanged); the chat screen navigates on linked-row click.

## Acceptance criteria

- [ ] Unit tests: `subagent.spawned`/lifecycle payloads decode; the store
      creates an entry on spawned, transitions status on started/completed/
      failed, and accumulates only frames tagged with its subagentId.
- [ ] Unit test: an `Agent` tool row gains its subagentId from a live spawned
      event and, in a committed-history fixture, from the `agent_id:` result
      line; a result without that line leaves the row non-tappable.
- [ ] On-device: while a sub-agent runs, tapping its `Agent` row opens the
      page and text/thinking/tool calls stream live; completion flips the
      status chip and freezes the stream; back returns to an uncorrupted main
      chat.
- [ ] Two concurrent sub-agents (`AgentSwarm`) stream into their own pages
      independently.
- [ ] `./gradlew :app:assembleDebug` succeeds, unit tests green.

## Out of scope

- History before the subscription point / cold-open of past sessions
  (transcript REST backing — 046).
- Todo widget inside the sub-agent page (revisit after 046 if missed).
- Background-task list/cancel UI (047) — but the page itself must not care
  whether its sub-agent runs foreground or background.
- Answering sub-agent questions/approvals from the page (they stay in the
  main chat, per 044).

## Notes

- Wire reference: `docs/server-api.md` → Sub-agents (verbatim `subagent.*`
  captures, id namespaces, the sub-agent's internal-turn structure — expect
  one turn for the task plus a forced result-summary turn).
- Reuse over rebuild: `ChatTimeline` (committed/live split), `ToolRows`,
  `Bubbles`, `StateViews`, the delta-coalescing pattern in `ChatViewModel`.
- The store lives at session scope, not screen scope: navigating back to chat
  and reopening the page must not lose the accumulated stream.

## Completion notes (2026-07-28)

- **Wire/parse:** `SubagentSpawnedPayload` + shared `SubagentLifecyclePayload`
  in `ChatDtos.kt`; five `SessionEvent`s (`SubagentSpawned/Started/Completed/
  Failed/Suspended` under a `SubagentLifecycleEvent` sub-interface) in
  `ChatEvents.kt`. Transcript events now implement `AgentScopedEvent`
  (`agentId`, default `"main"` — `TurnStarted` became a data class for this).
  `SessionEvent.isSubagentScoped()` is the single routing predicate.
- **Routing, not filtering:** 044's drop in `SessionSocket.handleFrame` is
  gone; every transcript frame emits its event tagged with the emitting
  agentId (shared `MAIN_AGENT_ID` const in ChatEvents.kt). The 044 socket
  test was rewritten to assert the tagged events (same verbatim 0.29.2
  fixtures) and a new test covers `subagent.*` decode.
- **Store:** `SubagentStreamStore` (ui/chat, `@Singleton` keyed by session)
  holds one non-history-backed `ChatTimeline` per sub-agent (new constructor
  param: `turn.started` seals the finished internal turn's rows instead of
  clearing them) plus the same leading-edge/100 ms trailing-flush coalescing
  as ChatViewModel (injectable window, Main-confined). Placed in ui/chat, not
  the data layer as the ticket sketched: its state is `ChatItem` rows and
  both live beside `ChatTimeline`; the "session-scoped, socket-fed" intent is
  unchanged.
- **Single feeder — no page-owned socket.** The chat's collection feeds the
  store; a socket gate in `ChatViewModel` (`screenVisible || openPages > 0`,
  collectLatest) keeps it alive while a sub-agent page is open — the page
  registers via `SubagentViewModel.onScreenStarted/Stopped` →
  `store.pageOpened/pageClosed`. A second socket would double-feed the store
  on every cursor replay (worst case: the whole page visit re-delivered on
  the way back). Chat-only behavior is unchanged (gate open/close = old
  ON_START/ON_STOP start/cancel; the 47-test ChatViewModelTest suite runs
  green against it). Known accepted gap: frames missed while the OS
  backgrounds the app on the page stay missing until 046's transcript
  backing — same class as today's live-row loss.
- **Linkage:** `ChatItem.ToolActivity.subagentId`; live via
  `ChatTimeline.linkSubagentTool(parentToolCallId, …)` on spawned; cold via
  the `agent_id: X` marker line (first matching line — foreground first line,
  background after the task fields), parsed on both committed and live
  results, `Agent` rows only. Links also live in a reducer-side map
  re-applied after refetches, so a mid-turn refetch committing the running
  Agent call can't drop the link. Linked rows initially navigated on tap
  instead of expanding — follow-up the same day (Louis's on-device feedback):
  the row keeps expand/collapse on tap and the trailing chevron-right is its
  own IconButton that navigates, so the call's input/output stay reachable
  inline (two new androidTests). Second follow-up (Louis, cold-open): the
  store gains `seedFromAgentRow` — after each history refetch (and on live
  `Agent` results) linked rows seed an entry from their args/result
  (identity, `status:` → lifecycle, args `prompt` → a user bubble and
  `[summary]` → a final assistant bubble, mirroring the live page),
  so the page shows the summary instead of the empty state when the whole
  run predates the subscription; seeding a `status: running` background row
  also stops later frames from hitting the unknown-id drop. Seeds never
  overwrite live data. `AGENT_TOOL_NAME` moved to ChatEvents.kt (shared).
  `MessageList`/`ToolRows` thread
  `onOpenSubagent` (default no-op).
- **Page:** `subagent/{sessionId}/{subagentId}` route, `SubagentScreen` +
  `SubagentViewModel`: description + subagent type in the top bar, status
  chip (Queued/Working/Suspended/Completed/Failed), the store timeline
  through the shared `MessageList` (auto-scroll included), no composer/cards.
  Unknown id → the standard `EmptyState`. Nested sub-agent linking is
  deliberately not wired (rows on the page are not tappable).
- **Tests:** 7 store (spawn/lifecycle/isolation/unknown-drop/turn-sealing/
  release/duplicate-spawn), 6 timeline linkage (live, committed cold,
  background shape, live result, absent marker, refetch survival), 3 sub-VM
  (state mapping, unknown id, page registration), 2 socket (tagged routing,
  lifecycle decode), 1 end-to-end ChatViewModel routing test (real WS
  fixtures: main chat untouched, store fed, row linked, completion flips).
  Full `:app:testDebugUnitTest` green (151 tests in the touched suites),
  `:app:assembleDebug` + `:app:assembleRelease` green; release APK published
  to the file server.
- **Not run on a physical device** (none on the VPS): the on-device and
  AgentSwarm acceptance points are covered at fixture level (verbatim 0.29.2
  frames) and by the store's per-agent isolation test.
