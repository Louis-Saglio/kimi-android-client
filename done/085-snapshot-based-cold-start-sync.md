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

Follow-up analysis (2026-08-01, same findings applied to the sub-agent stream)
sharpened the design's placement. The sub-agent page's cold path is independent
of `ChatViewModel` by design (ticket 064: after process death with a page on
top, the chat ViewModel is never recreated — the store's own keep-alive
collection feeds it). A roster seed living only in `ChatViewModel` would never
run on that path, and the stream's cold state is degraded in its own right:

- the store entry exists only via `subagent.spawned` — cold-opens get a
  blank-identity placeholder (`name`/`description` empty, `runInBackground`
  false, no `seededPrompt` — `SubagentStreamStore.applyTranscriptPage`);
- cold status is a heuristic (last transcript turn Running → Working, else
  Completed) that misreports **Failed** and **Suspended** runs as Completed
  (`SubagentStreamStore.kt`), and never populates `resultSummary`;
- live frames for an unknown sub-agent are dropped until the first transcript
  page lands ("spawn missed").

The roster heals all three for foreground sub-agents (identity, precise phase,
`output_preview` summary) — so the fetch+seed must be owned at session scope,
shared by both screens, not inside `ChatViewModel`. For the window the roster
cannot cover (it resets to `[]` when the main agent's next turn starts), the
transcript turn states already distinguish `completed`/`cancelled`/`failed`
(real since 0.31.1) — the store's heuristic just doesn't use them; that
precision fix is part of this ticket. Louis confirmed folding these
implications into 085 in place rather than a follow-up ticket.

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
REST fetch and the subscribe. This serves whichever collection opens first for
the session (the socket is a shared `@Singleton`): the chat gate's or the
store's keep-alive. Note: a seeded first connect currently emits
`SocketEvent.Reconnected` (cursor present) which triggers
`resyncHistoryAfterDrop` — suppress that one redundant resync when the snapshot
apply just landed (e.g. remember the applied watermark and skip the resync whose
subscribe cursor matches it).

**Snapshot ownership — session scope, NOT `ChatViewModel`** (the follow-up
analysis' core correction): a new session-scoped sync holder (e.g. a
`@Singleton SnapshotSync` keyed by session, next to `SubagentStreamStore`)
owns the fetch and the shared apply, so both cold paths — chat open AND
process-death restore with a sub-agent page on top (ticket 064: no
`ChatViewModel` exists there) — run the same rebuild:

- The fetch runs BEFORE the session's first socket subscribe (the chat's gate
  awaits it on its first opening; `SubagentStreamStore.pageOpened`'s keep-alive
  launch awaits it likewise), once per cold start per session (idempotent while
  already fresh); `resync_required` re-runs it.
- On success it seeds `SessionSocket.seedCursor(sessionId, asOfSeq, epoch)`,
  applies the roster into the store (below), and hands the chat-side bundle to
  `ChatViewModel` when one exists (below).
- Failure handling: none beyond the ordinary load-error path — a failed
  snapshot fetch surfaces the same error state a failed initial load does
  today. No fallback to the legacy burst: the app only ever runs against the
  latest server version and never keeps compatibility shims (project
  convention).

**Store seed** (`SubagentStreamStore.seedFromSnapshot(sessionId, rosterEntries)`):

- Creates missing entries with identity from the roster (`subagent_type`,
  `description`, `run_in_background`) and status mapped precisely from
  `subagent_phase`: queued → Queued, working → Working, suspended → Suspended,
  completed → Completed (with `output_preview` as `resultSummary`),
  failed → Failed.
- Idempotent, never overwriting a live-fed (spawn/event-fed) entry; roster-
  seeded entries count as lifecycle-seen (`sawLifecycle = true` — the roster IS
  authoritative lifecycle), so the transcript heuristic below can never regress
  a roster-precise status.
- Because the entry exists before the first subscribe, the "spawn missed"
  frame-drop window closes: live frames accumulate from the start.

**Transcript status precision** (`SubagentStreamStore.applyTranscriptPage`):
replace the Running/else heuristic's terminal branch with the real turn states
(`completed` → Completed; `cancelled`/`failed` → Failed; still Running →
Working) — turn states are real since 0.31.1. This is the cold-status source
when the roster cannot help (it resets to `[]` on the main agent's next turn).

**ChatViewModel cold start + `resync_required`** (consumes the chat-side bundle
via the shared owner; UiState/Effect stay in the ViewModel per ticket 072):

- `timeline.replaceHistory(snapshot.messages, keepLive = false)`;
  `oldestHistoryId`/`hasMoreHistory` from the page;
- `isWorking = snapshot.busy`, `contextUsage`;
- `pendingApprovals` → UiState queue (dedup by approval id);
- questions → `QuestionWizard`;
- roster → `timeline.linkSubagentTool(parent_tool_call_id, id)` per entry (the
  store seed is the owner's job, above — the row link is the chat timeline's);
- `resync_required`: the same apply with keepLive decided by the snapshot's
  `busy` (same semantics as today's status check); the socket connection stays
  open, subsequent live frames carry fresh cursors.

**Docs**: extend the thin snapshot coverage in `docs/server-api.md` with the
live-verified shapes (full top-level key list, `pending_approvals` item,
`in_flight_turn`, roster while running vs completed) from the 2026-08-01
captures, and keep `scripts/protocol-update-prompt.md` aligned.

## Acceptance criteria

- [x] Regression test for the reported bug on `ChatViewModelTestBase`: cold open
  with the snapshot serving a running roster entry whose `parent_tool_call_id`
  matches a running `Agent` row → the row's `subagentId` is set (tappable, opens
  the page) and the store entry is seeded with status Working — all before any
  result/`agent_id:` marker exists. (`SnapshotColdStartTest.cold open links a
  running sub-agent row from the snapshot roster`)
- [x] Snapshot `pending_approvals` item renders the approval card on cold open
  (same card the live `event.approval.requested` shows).
- [x] The first client subscribe frame after a cold start carries the snapshot
  watermark cursor (`seq`/`epoch`), asserted via `clientFrames`.
- [x] Snapshot `pending_questions` items render the question card on cold open
  (same card the live `event.question.requested` shows); a failed snapshot
  request surfaces the ordinary load-error state (no legacy-burst fallback).
- [x] Page-on-top cold restore (the 064 path — no `ChatViewModel` in the test):
  opening a sub-agent page with the snapshot serving a working roster entry
  seeds the store BEFORE the keep-alive subscribes (identity, Working status,
  live frames accumulate from the first one — no "spawn missed" drop), and the
  keep-alive's subscribe carries the snapshot watermark cursor.
  (`SubagentStreamStoreKeepAliveTest.the keep-alive cold start seeds the store
  from the snapshot roster before subscribing`)
- [x] Roster-seeded `suspended`/`failed` phases surface as Suspended/Failed
  (not Completed), `completed` carries `output_preview` as the summary, and a
  later transcript page never regresses a roster-seeded status.
- [x] Transcript-only cold open (roster empty): a terminal turn state of
  `failed`/`cancelled` maps to Failed, `completed` to Completed — no more
  blanket-Completed heuristic. (`TranscriptState.Failed`; store + mapper tests)
- [x] Existing suite green — in particular tickets 048 (resync), 060 (session
  filter), 064 (keep-alive): the within-process resume path is untouched.
  (329 tests, 0 failures)
- [x] `./gradlew :app:assembleDebug` (VPS rules: `flock /tmp/kimi-android-gradle.lock`,
  `--max-workers=1`, `--no-daemon` when parallel) and unit tests pass.
- [ ] Live verification on the VPS server: cold-open (fresh app process) a
  session with a running foreground sub-agent → tapping the Agent row opens the
  sub-agent page immediately; after the fix lands also verify a pending approval
  card survives a process kill. — **pending Louis on-device** (APK on the file
  server; server-side scenarios staged + shapes verified live, see below)
- [x] `docs/server-api.md` snapshot section documents the verified shapes.

## Out of scope

- **Background sub-agent cold relink**: impossible with current server APIs —
  the roster is foreground-only and REST `/tasks` carries no
  `parent_tool_call_id` (`docs/server-api.md`). This is an upstream gap, not an
  app bug; revisit if the server ever exposes the link. (A background sub-agent
  *page* could still be cold-seeded from `/tasks` + transcript — deferred; no
  user-visible defect reported there yet.)
- **Cold-restored page's task-prompt bubble** (`Stream.seededPrompt`): its only
  source is the main timeline's `Agent` row args, reachable chat-side via
  history; the roster does not carry the prompt. A page restored before the
  chat ever opens keeps an absent prompt bubble — cosmetic, deferred.
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
  turn — the transcript turn-state precision fix covers that window (turn
  states `running`/`completed`/`cancelled`/`failed` are real since 0.31.1);
  finished chat rows relink from the `agent_id:` result marker regardless.
- Sub-agent-stream findings that moved the roster seed to session scope
  (2026-08-01): the store entry exists only via `subagent.spawned` — a cold
  open gets a blank-identity placeholder (`SubagentStreamStore.applyTranscriptPage`,
  `name`/`description` empty, `runInBackground = false`, no `seededPrompt`);
  cold status maps any non-Running last turn to Completed, misreporting
  Failed/Suspended; live frames for an unknown sub-agent are dropped as
  "spawn missed" until the first transcript page lands; and the page's cold
  path (ticket 064 keep-alive) deliberately never recreates `ChatViewModel`,
  so any `ChatViewModel`-only seed would never run there.

## Landed (2026-08-01, main checkout)

- Data layer: `Snapshot.kt` (`SessionSnapshot`, `SnapshotApproval`,
  `SubagentRosterEntry`/`SubagentRosterPhase`, `InFlightTurn` — decoded, not
  consumed), DTOs in `ChatDtos.kt`, mapping in `ChatMessageMapper`,
  `ChatApi.snapshot`, `ChatRepository.getSnapshot`/`seedSessionCursor`.
  `session.usage` carries the context fill as `context_tokens`/`context_limit`
  (NOT the `/status` `max_context_tokens` naming — live-verified).
- `SessionSocket.seedCursor` (never moves a same-epoch cursor backward) +
  `SocketEvent.Reconnected(seq, epoch)` carries the cursor the subscribe
  actually resumed with; the chat skips the 048 resync when it matches the
  applied snapshot's watermark (the apply already healed that window).
- `SnapshotSync` (ui/chat, session-scoped singleton): one fetch per session per
  process, awaited before the session's first subscribe by the chat's gate AND
  the store's keep-alive (page-on-top path); seeds cursor + roster store-side,
  hands the bundle to the calling ChatViewModel (`Outcome.Fresh`); joiners get
  `AlreadyFresh` (roster for idempotent row links only — a process-lifetime
  cached roster re-seeding a released store would freeze stale phases behind
  `sawLifecycle`). `resync_required` re-fetches. Failed fetch = ordinary
  load-error state, removed from the cache so retry refetches; no legacy burst.
- Store: `seedFromSnapshot` (roster → identity + precise phase, `sawLifecycle`
  = the roster is authoritative lifecycle, `output_preview` → resultSummary;
  never overwrites live-fed entries); transcript cold status reads the real
  turn states (`TranscriptState.Failed` for `failed`/`cancelled`, 0.31.1).
- ChatViewModel: first gate opening = the snapshot IS the initial load
  (history page, busy/contextUsage, approvals queue replace, questions via
  `QuestionWizard.applySnapshot`, roster row links); keepLive follows the
  snapshot's `busy` with the turn-guard downgrade — the ticket's
  `keepLive=false` and the resync's busy-driven rule converge on a fresh
  timeline, and busy-driven is strictly safer on the retry-over-live-socket
  path. Second+ ON_STARTs keep the 048 resume set untouched. Approval/question
  applies are SeedGuard-guarded against live events overtaking the fetch.
- Tests: new `SnapshotColdStartTest` (5), keep-alive cold-start test, store
  roster/precision tests, mapper turn-state test; existing 048-path tests
  re-pointed to the within-process resume (second ON_START) where they
  exercise the /status seed guards. Suite: 329 tests, 0 failures.
- Live re-verified on 0.31.1 (2026-08-01, throwaway sessions): full top-level
  key list, busy `in_flight_turn` verbatim, roster working vs completed
  (`output_preview` tail), `pending_approvals` item (manual-mode prompt).
- Docs: `docs/server-api.md` gained the full Session snapshot section (Sessions)
  + running/terminal roster captures (Sub-agents); verification-log entry;
  `scripts/protocol-update-prompt.md` aligned; `AGENTS.md` convention bullets.
