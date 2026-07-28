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
