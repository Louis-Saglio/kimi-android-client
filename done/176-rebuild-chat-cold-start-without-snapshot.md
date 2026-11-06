# Rebuild chat cold start from transcript REST and session frames

- **ID:** 176
- **Created:** 2026-11-04
- **Depends on:** 175

## Context

Found by the nightly server drift run from 2.4.1 to 2.5.0 on 2026-11-04
(server 2.5.0, upstream commits `8535bd79` and `011b1d41`).
`GET /api/v1/sessions/{id}/snapshot` is a plain HTTP 404 now, and its
`as_of_seq`/`epoch` cursor pair no longer exists. The app's single cold-start
entry point — `SnapshotSync.fetchAndApply` calling
`ChatRepository.getSnapshot` — therefore fails before the chat can open, and
every snapshot-derived seed is gone: history, in-flight turn, pending
approvals/questions, sub-agent roster and the `Agent`-call mining.

The replacement surfaces, all verified live on 2.5.0:

- `GET .../messages?page_size=100` — unchanged flattened history.
- `GET .../transcript?agent_id=main` — the newest turn page (a running turn
  is included with its live steps/frames), plus unpaginated `interactions`,
  `attachments`, `prompts`, `agents` and `seq` (the op watermark).
- `GET .../prompts` — `{active, queued}` for the composer's queue state.
- The frames that follow `subscribe_v2`: `agent`, `usage`, `goal`, `todo`,
  `task.list`, `transcript.reset` (snapshot `items: []`, but `interactions`,
  `attachments`, `prompts`, `hasMoreOlder`), then the ack.
- `GET .../transcript/ops?agent_id=…&since_seq=…` — gap catch-up with
  `complete:false` meaning a full refresh is required.

Tickets 171/172/173 consume the pending-interaction part of this seed.
This ticket owns the chat-side cold start and the resume watermark; the jobs
list is ticket 179 and the sub-agent page is ticket 182.

## Description

1. Replace `SnapshotSync` with a transcript cold start: in parallel fetch
   `GET .../messages` (newest page) and
   `GET .../transcript?agent_id=main&page_size=<N>`; keep the per-session
   in-flight cache and the `forget(sessionId)` release hook so a re-entry
   refetches (the existing freshness rule).
2. Build the chat state from these: messages → bubbles (unchanged mapper);
   transcript turns/steps/frames → the rows that are not in messages yet
   (the live in-flight turn, including a running tool call); running turn
   `state:"running"` seeds the working indicator and the partial assistant
   text.
3. Seed the pending interactions from `transcript.interactions` +
   `pending_interactions` and hand them to tickets 171/172's card stores;
   seed the prompt queue from `GET .../prompts` (or the transcript
   `prompts` entities) so a queued prompt is visible on entry.
4. Seed the agent roster from `transcript.agents`
   (`{agentId,type,parentAgentId}`) for ticket 182; drop the removed
   snapshot `subagents` mapping. Mine `Agent` calls from the same
   messages + running transcript frames the old snapshot mining used, so
   cold-opened spawn rows still link.
5. Record `transcript.seq` as the per-agent resume watermark. The first
   `subscribe_v2` sends `transcript_since:{"main":<seq>}` (plus every child
   agent's seq) instead of the deleted cursor; if any agent's journal cannot
   cover the gap (`GET .../transcript/ops` `complete:false`, or the server
   answers with a fresh `transcript.reset`), refetch the transcript page and
   reset the local fold instead of applying a partial replay.
6. Delete `SessionSnapshot`, `SnapshotData`/`SnapshotApproval` DTOs, the
   snapshot `in_flight_turn` mapper and the `as_of_seq`/`epoch` cursor
   plumbing. Keep the 109/144/091 freshness and Agent-call-mining behavior
   under the new source. Do not add a fallback to the old burst of REST
   calls.

## Acceptance criteria

- [ ] Opening a session with completed history renders the same chat as
      today, with no `/snapshot` request (unit test asserts the URL is
      absent).
- [ ] Opening a session mid-turn shows the running turn's text/tool rows
      from `GET .../transcript` until live ops take over, without a gap or
      duplicate.
- [ ] A pending approval/question on a cold open seeds exactly one card
      (from the transcript interactions) — verified with tickets 171/172.
- [ ] A queued prompt is visible on entry from `GET .../prompts`.
- [ ] The first `subscribe_v2` carries `transcript_since` for main and every
      rostered child agent; a stale/failed journal triggers a full transcript
      refetch (mock `complete:false`).
- [ ] A cold-opened `Agent` spawn row links to the sub-agent page (mining
      preserved).
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- The live op fold itself — ticket 175.
- Jobs/tasks seeding — ticket 179.
- Sub-agent page rendering — ticket 182.
- The `agent`/`usage`/`todo` frame wiring — tickets 177/178.
- Any compatibility with the removed `/snapshot` or seq/epoch cursors.

## Notes

Relevant code: `ui/chat/SnapshotSync.kt`, `data/chat/Snapshot.kt`,
`data/chat/ChatApi.kt` (`snapshot`), `data/chat/ChatRepository.kt`,
`data/chat/ChatMessageMapper.kt` (`toDomain(SessionSnapshot)`),
`ui/chat/ChatViewModel.kt` (snapshot apply), `ui/chat/SubagentStreamStore.kt`
(roster apply), `data/chat/SessionSocket.kt` (`seedCursor`). Wire reference:
`docs/server-api.md` → Transcript, Prompts → Queue snapshot, WebSocket →
Transcript protocol.

## Implementation notes

**2026-11-06 —** `SnapshotSync` is replaced by `TranscriptColdStart` (ui/chat, same
session-scoped singleton, per-session in-flight cache and `forget` release hook): one
cold start is three parallel reads — `GET .../messages`, `GET .../transcript?agent_id=main
&page_size=1` and `GET .../prompts` — and any failed read is the ordinary load error. On
success it seeds the socket (`ChatRepository.seedTranscript` → `SessionSocket.seedTranscript`:
main's `TranscriptFold.seed` + the page's `seq` as watermark), applies the transcript
`agents` roster to the store (identity-only `SubagentRosterEntry`, phase `Unknown`) and
mines `Agent` calls from the messages page and the running turn's tool frames.
`ChatViewModel.applyColdStart` replaces history, seeds the running turn as live rows
(`ChatTimeline.seedRunningTurn`), renders queued prompts as "Queued #N" echoes
(`seedQueuedPrompts`), sets the working state from the running turn / active prompt and
replaces the approval/question queues from the transcript's pending interactions. A chat
that joins someone else's fetch (`AlreadyFresh`) takes its own `resync`; the gate now
claims the feeder role before awaiting the cold start.

- Resume: `SessionSocket` keeps per-agent watermarks (seeded by the cold start, advanced
  by every ops batch and reset) and sends them as `transcript_since` on every subscribe.
  A reset answering a resumed agent resets its fold and emits the new
  `SessionEvent.TranscriptStale`; `ChatViewModel.verifyResume` (ON_START after the first,
  every `Reconnected`) probes `GET .../transcript/ops` via the new `TranscriptSyncApi`
  (`TranscriptCatchUp.isStale`: `complete:false` or `latest_seq` behind the watermark).
  Either stale signal runs `rebuildFromTranscript` (a fresh cold start). This replaces
  048's status-aware REST resync (`resyncHistoryAfterDrop` is deleted).
- Data: `TranscriptPage` gained `seq`/`agents`/`prompts`/`pendingApprovals`/
  `pendingQuestions` (mapped in `ChatMessageMapper` from the 2.5 global state — camelCase
  approval request `TranscriptApprovalRequestDto`, snake_case question request; card id =
  interaction id); transcript tool frames now carry `agentCall`/`failed`, parse a complete
  `inputText` and keep blank assistant text frames. Deleted: `SessionSnapshot`,
  `SnapshotApproval`, `InFlightTurn`/`RunningTool`, `SnapshotData` and its DTOs,
  `ApprovalRequestedPayload`, `ChatApi.snapshot`, `ChatRepository.getSnapshot`,
  `ChatTimeline.seedInFlight`.
- Tests: `TranscriptColdStartTest` (VM harness: no `/snapshot`, mid-turn rows + working
  state, live ops extending the seed, history/seed dedup, one card per pending
  interaction, queued prompt, `transcript_since` for main and a live-seen child,
  `complete:false` and stale-reset rebuilds, covered resume, Agent-row link, roster,
  failure/retry), `TranscriptColdStartSyncTest` (cache/forget/failure, watermark seed,
  mining, roster), `SessionSocketTest` (resume subscribe, per-agent watermarks, stale vs
  baseline reset), `TranscriptFoldTest` (seed/reset), `ChatRepositoryTest` (global-state
  mapping, prompt queue, catch-up), `ChatTimelineTest` (both seeds); the harness serves
  the transcript/prompts/catch-up routes, `awaitRequest` takes a method filter, and
  tearDowns join `TranscriptColdStart.awaitInFlight()` (a resync fetches on the cold
  start's own scope, which no ViewModel cancellation reaches).
- Behaviour that died with the snapshot roster and is not rebuilt here: a running
  foreground sub-agent's row no longer links before its result exists (the transcript
  roster carries no parent tool call) and roster phases/descriptions no longer seed —
  ticket 182 re-feeds both from `task` frames; the snapshot usage ring seed is ticket 177's
  `usage` frame. A child agent first seen on this process subscribes without a watermark
  (it takes the server's baseline reset; its page fetches history after the subscribe).
