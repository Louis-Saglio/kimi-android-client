# Rebuild the compaction UI on transcript markers

- **ID:** 181
- **Created:** 2026-11-04
- **Depends on:** 175

## Context

Found by the nightly server drift run from 2.4.1 to 2.5.0 on 2026-11-04
(server 2.5.0, upstream commit `8535bd79`). The `compaction.started` /
`compaction.completed` / `compaction.failed|cancelled|blocked` frames are
gone. On 2.5.0 a manual compaction is a pair of `marker:"compaction"` items
in the subscribed agent's `transcript.ops`, plus one `undo` marker carrying
the wholesale context rewrite. Verbatim capture (see
`docs/server-api.md` → Compact → WebSocket sequence):

```json
{"op":"marker.upsert","item":{"kind":"marker","markerId":"live-m5",
 "marker":"compaction","payload":{"phase":"started","agentId":"main",
 "trigger":"manual"},"at":"…"}}
{"op":"marker.upsert","item":{"kind":"marker","markerId":"live-m6",
 "marker":"undo","payload":{"agentId":"main","start":0,"deleteCount":6,
  "messages":[<kept user messages>,<compaction_summary>,
              <compaction_continuation>],"tokens":23493},"at":"…"}}
{"op":"marker.upsert","item":{"kind":"marker","markerId":"live-m7",
 "marker":"compaction","payload":{"phase":"completed","agentId":"main",
  "result":{"summary":"…","compactedCount":6,"tokensBefore":22745,
            "tokensAfter":23493,"keptUserMessageCount":2}},"at":"…"}}
```

`ChatViewModel` drives its compacting state from
`SessionEvent.CompactionStarted`/`CompactionCompleted`/`CompactionFailed`
and clears it on those frames; on 2.5.0 the compact request succeeds over
REST but the UI never leaves the compacting state. The REST call itself is
unchanged (`POST ...:compact`, both `40910`s).

## Description

1. In the transcript fold (ticket 175), map `marker.upsert` with
   `marker:"compaction"`:
   - `payload.phase == "started"` → `CompactionStarted(trigger)` (the
     `instruction` is not echoed any more; keep the parameter null);
   - `payload.phase == "completed"` → `CompactionCompleted` with the
     `result` block (`summary`, `compactedCount`, `tokensBefore`,
     `tokensAfter`, `keptUserMessageCount`).
2. The `undo` marker that follows carries the summary message
   (`origin.kind:"compaction_summary"`) and the continuation injection; feed
   it through the existing splice/`InternalEnvelopes` path so the divider and
   history render as today.
3. Delete the `compaction.started|completed|failed|cancelled|blocked` frame
   branches and DTOs. No failure marker was observed on 2.5.0; a failed
   compact is visible as an absent completed marker (the REST `40910`
   snackbar covers the pre-start refusals). Clear the compacting state when
   the turn stream goes idle (`agent` `status:"idle"` /
   `event.session.work_changed` `busy:false`) and no completed marker
   arrived.
4. Keep the compact action and its REST error handling unchanged; no new
   request fields.

## Acceptance criteria

- [ ] `POST ...:compact {}` shows the compacting state from the `started`
      marker and clears it on the `completed` marker, with the summary text
      and token delta (unit test feeds the three markers).
- [ ] The summary divider renders from the `undo` marker and is not
      duplicated with the history fetch.
- [ ] A compact refused with `40910` still shows the server message and
      never enters the compacting state.
- [ ] An interrupted/dropped compaction (no completed marker, stream goes
      idle) leaves no stuck progress UI.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- The compaction algorithm, summary text or REST behaviour.
- Auto-compaction UX/notifications (none observed).
- History paging after compaction (the summary is appended as a normal
  message; existing behaviour).
- Goal/todo state around compaction.

## Notes

Relevant code: `data/chat/SessionSocket.kt` (compaction branches),
`data/chat/ChatDtos.kt` (`CompactionStartedPayload`/`CompactionCompletedPayload`),
`data/chat/ChatEvents.kt` (`CompactionStarted`/`Completed`/`Failed`),
`ui/chat/ChatViewModel.kt` (compacting state). Wire reference:
`docs/server-api.md` → Compact and WebSocket → Transcript protocol.

## Implementation notes

**2026-11-06 —** `TranscriptFold.onCompactionMarker` folds the `marker:"compaction"`
items (`CompactionMarkerPayload`/`CompactionResultDto` in `TranscriptOpsDtos.kt`):
`phase:"started"` → `SessionEvent.CompactionStarted(trigger, agentId)`, `phase:"completed"`
→ `CompactionCompleted(summary, compactedCount, tokensBefore, tokensAfter,
keptUserMessageCount, agentId)`. Both are now `AgentScopedEvent`s (a child's go to the
sub-agent store, which ignores them) and dedup by marker id with the `undo` markers, so
a resume replay never re-opens a finished compaction. A completed marker without
`result`, or an unknown phase, emits nothing. The `undo` marker between them goes
through the existing splice path unchanged (kept user messages dedup by id, the
continuation injection is dropped by the fold, the id-less summary by `ChatTimeline`).

- No failure marker: `SessionSocket` now emits the global `event.session.work_changed`
  for its own session as `SessionEvent.SessionWorkChanged(busy)` (`ChatWorkChangedPayload`;
  a payload without `busy` emits nothing). `ChatViewModel.settleDroppedCompaction` ends a
  compacting state still open when the session goes idle — `SessionWorkChanged(false)`
  or the `agent` frame's `AgentBusyUpdated(running = false)`; after a completed marker
  it is a no-op.
- `SessionEvent.CompactionFailed` and the ViewModel branch that sent a failure snackbar
  for it are deleted. `ChatTimeline.compactionFailed` became `compactionAbandoned`,
  which removes only a PENDING live divider (a previous run's done trace stays).
  `Effect.CompactionFailed` now only covers the REST refusal (`40910`), whose handling
  is unchanged.
- `CompactionStarted.instruction` was dropped instead of kept as an always-null field:
  2.5.0 does not echo it, and nothing read it.
- No duplicate divider: `ChatTimeline.pruneCoveredLiveRows` drops a done live divider
  when a keep-live refetch's history carries a divider with the same summary (the
  completed marker's `result.summary` is the bare text history persists). A clear-live
  refetch already replaced it.
- Tests: `TranscriptFoldTest` (the three markers, replay dedup, agent tagging,
  unreadable phases), `SessionSocketTest` (markers + this session's `work_changed`,
  foreign/busy-less frames ignored), `ChatTimelineTest` (abandon keeps a done trace,
  keep-live refetch after a completion shows one divider), `ChatViewModelTest` (full
  marker round with summary and token delta, next history fetch renders one divider,
  idle `work_changed`/`agent` ends a dropped compaction, `40910` never enters the
  compacting state). Shared builders in `testutil/TranscriptFrames`
  (`compactionStartedOp`/`compactionUndoOp`/`compactionCompletedOp`/`workChangedFrame`).
