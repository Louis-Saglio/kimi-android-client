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
