# Snapshot-based cold-start session sync

- **ID:** 085
- **Created:** 2026-08-01
- **Depends on:** none

## Context

Bug reported by Louis (2026-08-01): while a sub-agent is running, tapping its
`Agent` tool row expands the call details instead of opening the sub-agent page;
once the sub-agent finishes, tapping navigates correctly.

Investigation (live WS capture against the 0.31.1 server, throwaway sessions)
showed the tap behavior is decided by `ChatItem.ToolActivity.subagentId`
(`ToolRows.kt`), which has exactly two sources:

1. the live `subagent.spawned` frame (`parentToolCallId` → `ChatTimeline.linkSubagentTool`);
2. the `agent_id:` marker on the first line of the Agent call's **result** text
   (`agentResultSubagentId`) — which only exists after the run finishes.

`subagent.spawned` is never replayed before the resume cursor, and the cursor is
in-memory only (process death loses it). So on a **cold start** (process death,
or the prompt came from another client) there is no linkage source at all while
the sub-agent runs — the row stays unlinked until the result lands. This is not
a sub-agent-specific slip but the symptom of a structural flaw: the cold-start
path was never designed — it is the sum of ad-hoc REST calls (`/messages`,
`/status`, `/questions`) that each ticket happened to add, so any state that
lives only on the wire (never persisted into history, never replayed) is
invisible after a cold start. Today that class contains three known members:
sub-agent identity (this bug), pending approval cards (no REST read exists for
approvals at all — verified: `ChatApi` only POSTs decisions), and volatile tool
progress (transient, acceptable). Every future server feature of the same
"event-sourced, not in history" kind would inherit the hole.

The server already provides the designed fix: `GET /api/v1/sessions/{id}/snapshot`
returns the whole session state as one atomic bundle at an event-stream watermark
(`as_of_seq`/`epoch`) — session/busy state, newest messages page, in-flight turn,
**foreground sub-agent roster** (with `parent_tool_call_id`), `pending_approvals`,
`pending_questions`. The app never calls it (grep-verified). All bundle shapes
were verified live on 0.31.1 during the investigation (captures in Notes).

Decision (Louis, 2026-08-01): fix the structural root cause — adopt `/snapshot`
as the single cold-start/resync entry point — not a roster-only patch. Louis
explicitly overrode the ~500-line size rule for this ticket ("whatever the cost;
quick fixes and patches are never ok"). Alternatives considered and rejected:
(a) fetch only the roster to close the reported bug — symptomatic, leaves the
approval-card hole and the ad-hoc cold path in place; (b) persist sub-agent
links locally — a stale-prone cache duplicating the server's canonical roster;
(c) patch the server — it is upstream (MoonshotAI/kimi-code), not ours to patch.

The within-process resume path (ticket 048: cursor replay + `/status`-driven
refetch) is sound by design and stays as-is — only the two paths that have no
replay (cold start, `resync_required`) move to the snapshot.

## Description

**Data layer** (`data/chat/`):

- `ChatApi.snapshot(settings, sessionId)` → `GET /api/v1/sessions/{id}/snapshot`,
  through `ApiClient` like every call. New DTOs + domain model `SessionSnapshot`:
  - `asOfSeq: Long`, `epoch: String` (the watermark);
  - `busy: Boolean`, `contextUsage: ContextUsage?` (from the `session` object);
  - `messages: MessagePage` (same message DTOs + `has_more` as `/messages`);
  - `pendingApprovals`: decode each item with the existing
    `ApprovalRequestedPayload` (the snapshot item is the legacy
    `event.approval.requested` shape verbatim — verified live), reuse
    `ChatMessageMapper.summary` for the card summary;
  - `pendingQuestions`: decode items with the existing `QuestionRequestedPayload` —
    the snapshot's `pending_questions[]` entries are byte-identical to the poll's
    `data.items[]` (verified live on 0.31.1, 2026-08-01), which that DTO already
    decodes;
  - `subagents`: roster entries `id`, `subagent_type`, `description`,
    `subagent_phase` (queued|working|suspended|completed|failed),
    `parent_tool_call_id`, `output_preview?` — all fields optional/defensive,
    this roster is the newest surface of the API;
  - `in_flight_turn`: decode for completeness (turn_id, assistant_text,
    thinking_text, running_tools, current_prompt_id — verified shape) but do
    NOT consume it (see Out of scope).

**Socket** (`SessionSocket`): add `seedCursor(sessionId, seq, epoch)` writing the
instance-level cursor map, so the first subscribe of a cold collection resumes
AT the snapshot watermark — gapless stitching, no missed window between the
REST fetch and the subscribe. Note: a seeded first connect currently emits
`SocketEvent.Reconnected` (cursor present) which triggers
`resyncHistoryAfterDrop` — suppress that one redundant resync when the snapshot
apply just landed (e.g. remember the applied watermark and skip the resync whose
subscribe cursor matches it).

**ViewModel** (`ChatViewModel`, per the collaborator convention of ticket 072 —
a new `internal` logic holder, e.g. `SnapshotSync`, with UiState/Effect staying
in the ViewModel and updates reported through constructor callbacks):

- Cold start (initial load, no stored cursor): replace the `/messages` +
  `/status` + `/questions` burst with one snapshot fetch, then apply:
  - `timeline.replaceHistory(snapshot.messages, keepLive = false)`;
    `oldestHistoryId`/`hasMoreHistory` from the page;
  - `isWorking = snapshot.busy`, `contextUsage`;
  - `pendingApprovals` → UiState queue (dedup by approval id);
  - questions → `QuestionWizard`;
  - roster: `timeline.linkSubagentTool(parent_tool_call_id, id)` per entry, plus
    a new `SubagentStreamStore.seedFromSnapshot(sessionId, entries)` that seeds
    stream entries with identity (`subagent_type`, `description`) and status
    mapped from `subagent_phase` (working/queued → Working, suspended →
    Suspended, completed → Completed with `output_preview` as resultSummary,
    failed → Failed) — idempotent, never overwriting a live-fed entry;
  - `SessionSocket.seedCursor(sessionId, asOfSeq, epoch)` BEFORE the socket
    gate can open its first collection.
- `resync_required` path: run the same snapshot apply (keepLive decided by the
  snapshot's `busy`, same semantics as today's status check) and re-seed the
  cursor from the new watermark. The socket connection stays open; subsequent
  live frames carry fresh cursors.
- Failure handling: none beyond the ordinary load-error path — a failed snapshot
  fetch surfaces the same error state a failed initial history load does today.
  No fallback to the legacy burst: the app only ever runs against the latest
  server version and never keeps compatibility shims (project convention).

**Docs**: extend the thin snapshot coverage in `docs/server-api.md` with the
live-verified shapes (full top-level key list, `pending_approvals` item,
`in_flight_turn`, roster while running vs completed) from the 2026-08-01
captures, and keep `scripts/protocol-update-prompt.md` aligned.

## Acceptance criteria

- [ ] Regression test for the reported bug on `ChatViewModelTestBase`: cold open
  with the snapshot serving a running roster entry whose `parent_tool_call_id`
  matches a running `Agent` row → the row's `subagentId` is set (tappable, opens
  the page) and the store entry is seeded with status Working — all before any
  result/`agent_id:` marker exists.
- [ ] Snapshot `pending_approvals` item renders the approval card on cold open
  (same card the live `event.approval.requested` shows).
- [ ] The first client subscribe frame after a cold start carries the snapshot
  watermark cursor (`seq`/`epoch`), asserted via `clientFrames`.
- [ ] Snapshot `pending_questions` items render the question card on cold open
  (same card the live `event.question.requested` shows); a failed snapshot
  request surfaces the ordinary load-error state (no legacy-burst fallback).
- [ ] Existing suite green — in particular tickets 048 (resync), 060 (session
  filter), 064 (keep-alive): the within-process resume path is untouched.
- [ ] `./gradlew :app:assembleDebug` (VPS rules: `flock /tmp/kimi-android-gradle.lock`,
  `--max-workers=1`, `--no-daemon` when parallel) and unit tests pass.
- [ ] Live verification on the VPS server: cold-open (fresh app process) a
  session with a running foreground sub-agent → tapping the Agent row opens the
  sub-agent page immediately; after the fix lands also verify a pending approval
  card survives a process kill.
- [ ] `docs/server-api.md` snapshot section documents the verified shapes.

## Out of scope

- **Background sub-agent cold relink**: impossible with current server APIs —
  the roster is foreground-only and REST `/tasks` carries no
  `parent_tool_call_id` (`docs/server-api.md`). This is an upstream gap, not an
  app bug; revisit if the server ever exposes the link.
- **Consuming `in_flight_turn`** (assistant_text/thinking_text/running_tools of
  the active turn): the in-flight turn's content already reaches the timeline
  via history (verified: the running Agent call appears in the messages page
  mid-turn) plus live frames; rendering the snapshot's live tail is a separable
  behavioral change.
- **Rewriting the within-process resume path** (ticket 048's cursor replay +
  `/status` check): sound as-is; only its `resync_required` branch moves to the
  snapshot.
- **Volatile `tool.progress` accumulation** across process death: transient by
  nature, replaced wholesale by the result.

## Notes

- Root-cause analysis and full mechanism: this session's investigation
  (2026-08-01); the symptom→cause chain is `ToolRow`'s `subagentId == null` →
  no live `subagent.spawned` witnessed → no cold linkage source while running.
- Live-verified snapshot shapes (0.31.1, 2026-08-01, throwaway sessions):
  - top level: `as_of_seq`, `epoch`, `session` (rich object: busy,
    main_turn_active, pending_interaction, last_turn_reason, usage, …),
    `messages` (`items` + `has_more`), `in_flight_turn` (null when idle),
    `subagents`, `pending_approvals`, `pending_questions`;
  - roster running: `{"id":"agent-1","kind":"subagent","subagent_phase":"working",
    "subagent_type":"explore","parent_tool_call_id":"tool_hpu2…",
    "run_in_background":false,"created_at":…,"started_at":…}` (no
    `completed_at`/`output_preview` until terminal);
  - `pending_approvals` item: `{"approval_id":"tool_EYGD…","session_id":…,
    "turn_id":0,"tool_call_id":"tool_EYGD…","tool_name":"Bash",
    "action":"Running: sleep 90","tool_input_display":{"kind":"command",…},
    "created_at":…,"expires_at":…}` — identical to the legacy
    `event.approval.requested` payload (`approval_id` == `tool_call_id`);
  - `in_flight_turn`: `{"turn_id":0,"assistant_text":"…","thinking_text":"…",
    "running_tools":[{"tool_call_id":…,"name":"Agent","args":{…},"display":…}],
    "current_prompt_id":"msg_…"}`;
  - `pending_questions` item (verified 2026-08-01 with a live AskUserQuestion):
    byte-identical to the `/questions?status=pending` poll's `items[]` entries
    (`question_id` == `tool_call_id`, `session_id`, `questions[]` with
    `id`/`question`/`options[{id,label}]`/`header`/`allow_other`, `created_at`,
    `turn_id`, `tool_call_id`) — decodes with the existing
    `QuestionRequestedPayload`.
- Related done tickets: 045 (sub-agent page), 046 (transcript backing), 048
  (resync after backgrounding), 060 (session-id frame filter), 064 (keep-alive),
  069 (approval events v2 — the v2 frames are deliberately ignored; the snapshot
  item is the legacy shape, so the app's existing legacy decode is the right
  reuse), 072 (collaborator convention), 074 (shared HistoryPager).
- Server-side roster lifecycle: dropped when the main agent starts its next
  turn — harmless, finished rows relink from the `agent_id:` result marker.
