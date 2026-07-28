# Transcript-backed history for the sub-agent page

- **ID:** 046
- **Created:** 2026-07-28
- **Depends on:** 045

## Context

Ticket 045's sub-agent page accumulates only what streams in while the app is
subscribed: opening a past session shows finished `Agent` rows whose pages are
empty, and mid-run reconnects lose everything before the cursor
(`subagent.spawned` is never replayed — `docs/server-api.md`, Sub-agents).

The server has a per-agent transcript endpoint that closes exactly this gap,
verified live on 0.29.2 (2026-07-28) against a finished sub-agent, including
its cold-rebuild path:

```
GET /api/v1/sessions/{sid}/transcript?agent_id=<id>&page_size=<n>&before_turn|after_turn=<cursor>
```

It pages by **turn** (`page_size` default 20, max 100), answers for any agent
id (main or sub-agent, finished or running), and works for cold sessions from
persisted wire records. With it, the detail page gets the same
committed-history + live-tail architecture as the main chat (005/043).

## Description

1. **Spike first, then pin DTOs.** Capture real responses on a live server
   (a finished sub-agent, a running one, a paged second page) and pin the
   response DTOs from the captures: turn → step → frame structure (frames seen
   2026-07-28: `thinking`, `text` with role, plus tool-call frames), the
   pagination contract (`has_more`/cursor field names — do not guess from
   upstream source), and the unpaginated global state the endpoint ships with
   every page (`tasks`, `todos`, `agents`, `meta`… per the route's doc
   comment). Add a Transcript section to `docs/server-api.md` with the
   captures (019/045-precedent: doc updates ride along).
2. **Data layer.** `ChatApi` gains
   `transcript(sessionId, agentId, beforeTurn: String?, pageSize: Int)`
   through `ApiClient` like every other call, returning a domain page
   (turns + hasMore). `ChatRepository` maps DTO → domain with `Result`/
   `DataError` as usual.
3. **Committed/live merge.** The sub-agent reducer from 045 gains
   `replaceHistory(turns, keepLive)` semantics mirroring `ChatTimeline`:
   transcript pages become committed rows (turn prompts → user bubbles,
   thinking/text frames → assistant bubbles, tool-call frames → tool rows);
   the live tail keeps applying on top; a turn-end-equivalent (subagent
   completed) refetch reconciles.
4. **Pagination.** Scroll-to-top loads older turns with `before_turn`
   (prepend, dedup by turn id, in-flight Job guard, header spinner,
   scroll-position anchoring) — the 043 mechanics applied to turn cursors.
5. **Cold open.** Opening a finished sub-agent's page from a past session
   (row linked via the `agent_id:` result line, 045) fetches its newest
   transcript page directly; status comes from the transcript's turn states
   when no live events exist.

## Acceptance criteria

- [ ] DTOs pinned from live captures (committed to the test fixtures);
      MockWebServer tests: request sends `agent_id` + cursor params, response
      parses turns/frames/pagination flags.
- [ ] Unit tests: `replaceHistory` merges committed turns with an in-flight
      live tail without duplicates or lost deltas; prepend dedups by turn id
      and stops at `has_more: false`.
- [ ] On-device: open a **past** session with a finished sub-agent → its page
      shows the full transcript without any live stream having been observed.
- [ ] On-device: a sub-agent with >20 turns paginates backwards without
      scroll jumps (manufacture one with a multi-step prompt if needed).
- [ ] `docs/server-api.md` gains the Transcript section.
- [ ] `./gradlew :app:assembleDebug` succeeds, unit tests green.

## Out of scope

- Using `/transcript` for the **main** agent's history (the messages
  endpoint + 043 pagination stays; a unified transcript migration is a
  separate product decision).
- `/transcript/ops` catch-up, `/transcript/user-messages`, `/transcript/plan`
  (no need identified).
- Rendering the response's global `todos`/`tasks` state as widgets.
- `after_turn` forward paging (the live stream covers the newest end).

## Notes

- Route reference (upstream): `packages/kap-server/src/routes/transcript.ts`
  in MoonshotAI/kimi-code — informative only; the live captures rule.
- The transcript response is turn-granular, not message-granular: do not
  force it through `ChatMessage`; a small turn/step/frame domain model is
  expected, mapped into the same `ChatItem` rows.
- Ticket 043 (`todo/043-chat-history-pagination.md`) is the pagination
  template; ticket 010's in-flight-Job re-entrancy guard applies verbatim.

## Completion notes (2026-07-28)

- **Spike (live, 0.29.2):** captured a finished sub-agent cold-rebuilt from
  wire records (`agent-15`, session from 045), a running-then-finished
  sub-agent (`agent-23`, throwaway session `session_59566ec0-...`, left in
  place — no delete endpoint), a two-page `before_turn` walk with
  `page_size=1`, and main-agent pages with `swarm.enter`/`exit` and `undo`
  markers. Contract pinned: `agent_id` **required** (`40001` when omitted;
  unknown id answers `code:0` + empty items, not an error); `page_size`
  default 20 / max 100, counting **turns** (markers ride along); cursor-less
  = newest page, **ascending within the page** (opposite of `/messages`);
  past the oldest → `items:[]` + `has_more:false`. Warm answers add
  `startedAt`/`endedAt`/`usage`/`timing`/`finishReason` and tool
  `inputText`/`display`/`progress`; the cold rebuild ships core fields only.
  User-origin turns carry a top-level `prompt`; a sub-agent's
  system-triggered turns never do — **the task prompt is not in the
  transcript** (the 045 seed keeps carrying it). All in the new
  docs/server-api.md Transcript section + verification-log entry.
- **Data layer:** DTOs in `ChatDtos.kt` (flat turn/step/frame/item shapes,
  global state `tasks`/`todos`/`agents`/`meta`/`seq` pinned but unconsumed);
  turn/step/frame domain model in `data/chat/Transcript.kt` (deliberately
  not `ChatMessage`); mapping in `ChatMessageMapper` (markers dropped, tool
  frames get ToolPreview + pretty input + progress-tail output);
  `ChatApi.transcript(sessionId, agentId, beforeTurn, pageSize)` through
  `ApiClient` as usual, `ChatRepository.getTranscript` delegate.
- **Merge:** `ChatTimeline` gains `replaceTranscript(turns, keepLive)`
  (newest page replaces committed, live tail survives deduped — same
  tool-id/content pruning as `replaceHistory`, extracted into
  `pruneCommittedLive`) and `prependTranscript(turns)` (dedup by turn id via
  `committedTurnIds`; a replace resets pagination, the 043 refetch-reset
  semantics). Turn flatten: `prompt` → user bubble; consecutive
  thinking/text frames per step → one assistant bubble ("\n"-joined); tool
  frames → grouped rows; todo calls suppressed like the live path. Row ids
  are server frame/turn ids, so pages and refetches dedup cleanly.
- **Store/VM:** `SubagentStreamStore` keeps pagination state per stream
  (`transcriptLoaded`/`oldestTurnId`/`hasMoreTurns`, survives navigation),
  derives status from turn states only while no live lifecycle frame was
  seen (`sawLifecycle`), and creates the entry from the transcript alone
  (deep state loss) — an empty page for an unknown id creates nothing. The
  seeded prompt bubble moved OUT of the timeline (`Stream.seededPrompt`,
  prepended by `toState`) so a refetch can't wipe the page's only user
  bubble; the seeded summary stays in the timeline and gives way to the real
  turns. `SubagentViewModel` fetches the newest page on cold open
  (`hasTranscript` skips it on reopen — the socket keeps the entry fresh),
  refetches `keepLive=false` exactly once on a live transition to
  Completed/Failed (edge-detected; cold-terminal opens skip it), and
  implements 043's scroll-to-top mechanics on `before_turn` cursors
  (in-flight Job guard, header spinner in `MessageList`, key-anchored scroll,
  silent failure with retry on next trigger). Initial-load failure with
  nothing to show gets the full-screen `ErrorState` + retry.
- **Notable non-change:** the main agent's history stays on `/messages` +
  043-style pagination (out of scope).
- **Tests:** 3 MockWebServer transcript tests (request params, frame
  parsing, marker tolerance, running/user-prompt mapping), 4 timeline tests
  (flatten, mid-run keepLive merge, prepend dedup, completion reset), 4
  store tests (entry-from-transcript, lifecycle-wins, merge+prepend,
  seeded-prompt survival), 3 new VM tests (cold open without live events,
  pagination walk + stop, single canonical reconcile) + the 3 existing ones
  rewired to a MockWebServer-backed repository. Full
  `:app:testDebugUnitTest` green (242 tests); one unrelated timing flake in
  ChatViewModelTest's coalescing test on the first full run — green in
  isolation and on the full-suite rerun, pre-existing real-time WS pattern.
- **Builds:** `:app:assembleDebug` and `:app:assembleRelease` green; release
  APK published to the file server.
- **Not run on a physical device** (none on the VPS): the two on-device
  acceptance points (past-session cold open, >20-turn backwards pagination
  without scroll jumps) are covered at MockWebServer/fixture level with
  verbatim 0.29.2 captures; the on-device pass needs Louis's phone. A real
  >20-turn sub-agent session was not manufactured.
