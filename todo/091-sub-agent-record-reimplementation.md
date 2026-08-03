# Sub-agent state reimplementation: first-class record, precedence-merged sources, fresh rebuild per entry

- **ID:** 091
- **Created:** 2026-08-03
- **Depends on:** none

Supersedes two symptomatic slices filed and withdrawn the same day at Louis's
direction ("quick fixes and patches are never ok"; the ~500-line size rule is
explicitly overridden for this ticket, same call as ticket 085). This ticket IS
the root-cause fix; splitting it would re-create the symptomatic-slice problem.

## Context

Two bugs reported by Louis (2026-08-03), investigated the same day:

**Bug 1 — the vanishing task prompt.** Opening a running sub-agent's page shows
the task prompt; a few seconds later it disappears, and never returns on
reopening. It reappears only after the agent finishes AND the session is left
and re-entered. Mechanism (proven with a scratch reproduction test — red
exactly as predicted): the visible prompt is NOT the designed durable bubble
(`Stream.seededPrompt`, kept OUTSIDE the timeline since ticket 046). It is the
live `context.spliced` frame carrying the task prompt as "the sub-agent's first
internal user message" (`docs/server-api.md:1357-1358`), which
`ChatTimeline.appendSpliced` commits into the timeline's committed zone
(`ChatTimeline.kt:274-277, 414-439`). The page's cold-open transcript fetch
then replaces that zone wholesale (`replaceCommitted`,
`ChatTimeline.kt:208-211`) — and the transcript NEVER carries the task prompt
(system-triggered turns; wire-verified in 046, `docs/server-api.md:561-568`).
Repro evidence: store items went from
`[UserMessage(msg_task, "the task prompt"), AssistantMessage(live_0)]` before
the transcript page to `[AssistantMessage(t0.1.f1)]` after. The durable home
stays empty because `seedFromAgentRow` refuses to overwrite an existing entry
(`SubagentStreamStore.kt:298`) — and live-spawned or roster-seeded entries
always exist before the linked-row seed runs. Permanence follows:
`transcriptLoaded=true` blocks refetches, the splice never replays (resume
cursor is past it), the row seed keeps no-oping. Louis's heal path (finish +
leave + re-enter) releases the store (`ChatViewModel.kt:583-585`) so the
finished row's seed finally creates a prompt-carrying entry.

**Bug 2 — the lost row link.** Leave a session while a sub-agent spawned during
that visit is still running, come back, tap the running `Agent` row → it
expands instead of opening the page. Mechanism (code-path analysis): the tap is
decided by `ChatItem.ToolActivity.subagentId`, whose three sources are all dead
on this path — the live `subagent.spawned` link is never replayed (cursor past
it), the `agent_id:` result marker doesn't exist while running, and the
snapshot roster is fetched ONCE PER PROCESS (`SnapshotSync.coldStarts`,
`SnapshotSync.kt:68-84`): re-entry gets `Outcome.AlreadyFresh` with the
pre-spawn roster. The per-ViewModel link map (`ChatTimeline.subagentLinks`)
died with the released ViewModel. Only bites for agents spawned after the
process's first sync — why 085's tests (cold open = fresh fetch) are green and
this slipped through.

**Common structural root cause.** Sub-agent metadata that has NO canonical
representation in the rebuild sources — the task prompt (absent from the
transcript) and the running-agent link (absent from history until the result
lands) — is captured only from one-shot live frames into containers whose
lifetime is shorter than what they must survive: the wipeable committed zone
(bug 1, seconds), the per-ViewModel link map plus the process-cached roster
(bug 2, minutes). Every guard designed to prevent double-application
(`seedFromAgentRow`'s never-overwrite, `AlreadyFresh`'s skip-re-seed, cursor
resume) assumes the first-sight capture already happened — and nothing
re-captures after the capture is destroyed. Patching either symptom leaves the
class intact; the model itself must change.

**Web-client comparison** (upstream `apps/kimi-web`, sparse clone at
/tmp/kimi-code-upstream): the web UI never hits either bug. Its sub-agent panel
is fed from ONE store (live frames + roster) — it never fetches
`/transcript?agent_id=`, so no canonical-replace merge exists to wipe anything.
The task prompt is read from the `Agent` call's ARGS in message history
(`AgentTool.vue:39-51`), which every reload restores. The row link is
re-seeded from a FRESH snapshot roster ON EVERY SESSION OPEN
(`client.ts:529-581`, `useKimiWebClient.ts:1419-1429`, `mappers.ts:379`,
`taskMerge.ts:70-81` — roster authoritative for identity/phase, live content
preserved); no process-lifetime cache exists upstream. When a link is
unresolvable, the open affordance is hidden rather than misleading
(`AgentTool.vue:65-72,100`).

**Design direction (agreed 2026-08-03):** reimplement the sub-agent state
handling around three principles — (1) metadata is not timeline data: prompt,
link, and identity are first-class record fields, never rows in a replaceable
list; (2) precedence-merged sources instead of never-overwrite guards: every
source updates the record through the one funnel, writing the fields it is
authoritative for, so late canonical data HEALS instead of bouncing off a
guard; (3) rebuild = fresh canonical state on every session entry: no
process-lifetime caches of mutable server state.

Alternatives considered and rejected: carving the prompt row out of the
committed-zone replace (breaks the 055/046 wholesale-replace invariant);
mining transcript `undo` markers for the prompt (warm-path only, vanish on the
cold rebuild — `docs/server-api.md:595-598`); persisting links locally
(stale-prone cache duplicating the canonical roster — rejected in 085);
re-fetching the snapshot on every ON_START (wrong granularity — fires on
page→chat back-navigation where ticket 048's resume already heals); server
changes (upstream, not ours).

## Description

Reimplement the sub-agent state layer — `SubagentStreamStore`'s internals, its
seeding APIs, and the `SnapshotSync`/ChatViewModel glue around them — as
follows. KEPT UNCHANGED (the sound 70%): `ChatTimeline`'s committed/live split
and `replaceTranscript`/`keepLive` mechanics, `DeltaCoalescer`, `HistoryPager`,
`SessionSocket`, the single-feeder arbiter (064: pageOpened/pageClosed +
chatCollectionStarted/Stopped + keep-alive), `SubagentViewModel`'s thin role,
the screen composables, and the transcript-backed page itself (richer than the
web client's live-only panel — keep it).

**1. `SubagentRecord` (replaces the internal `Stream`).** First-class,
session-scoped (unchanged: `@Singleton SubagentStreamStore`, released on
session leave) with metadata as durable fields, never inside the timeline:

```
SubagentRecord(
  id, type, description, runInBackground,   // identity
  prompt: String?,                          // the task prompt — rendered by
                                            // toState() above the timeline
  parentToolCallId: String?,                // the row link
  status, resultSummary, error,
  statusPinnedByLive: Boolean,              // was sawLifecycle
  timeline: committed transcript + live tail (ChatTimeline, unchanged),
  transcriptLoaded / oldestTurnId / hasMoreTurns (paging, unchanged),
  coalescer (unchanged),
)
```

**2. The precedence funnel.** Every source reaches the record through ONE
store method per source kind; each writes only the fields it is authoritative
for, per this table (high→low; higher overwrites lower, lower never touches
higher; same content re-applying is a no-op by equality, not by refusal):

| Field | Sources (high→low) | Rule |
| --- | --- | --- |
| identity | `subagent.spawned` → roster → row args/result → transcript placeholder | first non-blank wins; never cleared once set |
| `prompt` | task-turn splice = row/call args | first writer wins (same content, modulo the splice's git-context prefix); never cleared |
| `parentToolCallId` | spawned → roster → result marker | first wins |
| `status` | live lifecycle (pins: `statusPinnedByLive`) → FRESH roster → row result text → transcript turn states | roster/result/transcript update ONLY while not live-pinned |
| `resultSummary`/`error` | live completed/failed → roster `output_preview` → row `[summary]` | with status |
| timeline | live frames (tail) + transcript pages (committed) | unchanged 046 mechanics |

Concretely, the store's seeding API becomes (all idempotent, re-applicable):

- `apply(sessionId, event)` — live funnel (as today) PLUS: the stream's FIRST
  `ContextSpliced` user message (the task-turn splice, identified as the first
  splice observed before any second `TurnStarted`) is lifted into
  `record.prompt` and never reaches the timeline; later splices (result-summary
  nudge turn, steers) keep flowing to the timeline as user bubbles exactly as
  today. Lift only when `record.prompt == null`.
- `applyRoster(sessionId, roster)` (replaces `seedFromSnapshot`) — creates
  missing records; backfills identity/`parentToolCallId`/`output_preview`;
  updates status per the table (a FRESH roster is authoritative for
  non-live-pinned records — this REPLACES 085's "never re-seed" AlreadyFresh
  rule, whose stale-phase rationale is voided by freshness). Never touches
  `prompt` (the roster has none) or timelines. Re-applicable on every entry.
- `applyAgentCall(sessionId, toolCallId, argsJson, resultText?, rowStatus)`
  (replaces `seedFromAgentRow`) — the metadata miner for `Agent` calls from ANY
  carrier (history rows, the snapshot's messages page,
  `in_flight_turn.running_tools`): creates missing records (today's seed
  behavior, including the `[summary]` assistant bubble standing in until the
  transcript lands); on existing records backfills `prompt` from the args (when
  unset) and `description` (when blank); result text feeds link/status/summary
  per the table; never touches an existing record's timeline or a live-pinned
  status.
- `applyTranscriptPage`/`prependTranscriptPage` — unchanged (timeline, paging,
  transcript-derived status while not live-pinned); metadata-safe by
  construction now that the prompt is a field.

**3. Session-scoped link registry.** The `toolCallId → subagentId` map moves
from `ChatTimeline` (per-ViewModel, dies on release) into the store (populated
by the funnel: spawned/roster/result-marker). `ChatTimeline`'s healing
mechanics (`linkSubagentTool`/`reapplySubagentLinks`) stay, but ChatViewModel
re-drives them from `store.linksFor(sessionId)` after every store-affecting
apply (spawn, roster, history/snapshot page, result marker) — one owner,
explicit rebuild. The row affordance stays as-is: linked rows navigate;
unlinked rows expand (honest — the affordance only exists with a real link,
web parity with hiding it).

**4. Fresh rebuild per session entry.** `SnapshotSync` gains
`forget(sessionId)`, called from `SubagentStreamStore.release` (the
session-left signal, `ChatViewModel.onCleared`): the next entry RE-FETCHES
`/snapshot` instead of joining a process-lifetime cache (the web client's
model; one cheap GET per re-entry). Preserved: chat → page → chat never
releases (ViewModel in the back stack), so 048's within-process resume and
`AlreadyFresh` keep serving that path with zero added fetches; process death is
unchanged (empty cache); `resync_required`'s re-fetch is untouched; failed
fetches are still forgotten for retry. `fetchAndApply` additionally mines the
snapshot's `messages` page and `in_flight_turn.running_tools` for `Agent`
calls → `applyAgentCall` — chat-INDEPENDENT, so the page-on-top cold restore
(ticket 064's path, no ChatViewModel) also recovers prompts/links, closing the
gap 085 explicitly deferred.

**5. The resulting rebuild pipeline** (every session entry, either screen):
snapshot sync (fresh after release) → cursor seed (forward-only) → roster →
record funnel; snapshot messages/`running_tools` mine → `applyAgentCall`;
socket subscribes at the watermark → live funnel (spawn/lifecycle/splice/turn
frames); history applies → row mine → `applyAgentCall` (as today); page open →
newest transcript page (`hasTranscript` skip) → committed merge; terminal
transition → one canonical refetch (unchanged `maybeReconcile`).

**6. Renames for model clarity** (cheap, part of making the model explicit):
`Stream`→`SubagentRecord`, `seededPrompt`→`prompt`,
`sawLifecycle`→`statusPinnedByLive`, `seedFromSnapshot`→`applyRoster`,
`seedFromAgentRow`→`applyAgentCall`. Update every architecture comment the
restructure invalidates (the store's class doc, 046/064/085 references) and the
`AGENTS.md` sub-agent paragraphs (the "linked rows seed their store entry" /
"never overwrites" / AlreadyFresh descriptions).

## Acceptance criteria

- [ ] Bug 1 regression test (verified RED on the pre-reimplementation code
      during the investigation): spawn → `turn.started` → `context.spliced`
      (task prompt) → deltas → `applyTranscriptPage(keepLive = true)` → the
      prompt bubble is still the first item; page reopen (`hasTranscript`
      path) keeps it.
- [ ] Bug 2 regression test on `ChatViewModelTestBase`: cold start with an
      EMPTY roster → live `subagent.spawned` links the row → `onCleared` → a
      NEW ChatViewModel's cold start with the roster now serving the running
      agent → the running row's `subagentId` is set BEFORE any result marker,
      and the store holds a Working record for it.
- [ ] Precedence tests: a fresh roster updates a roster/transcript-derived
      status but never a live-pinned one; `prompt` survives every source
      combination (splice-first, args-first, roster-seeded entry backfilled
      from args); a second-turn splice lands in the timeline while the
      task-turn splice does NOT.
- [ ] Page-on-top cold restore test (064's path, no ChatViewModel): the
      keep-alive's snapshot sync seeds the record INCLUDING the prompt, mined
      from the snapshot's `Agent` call args.
- [ ] `SnapshotSync` tests: after `forget`, the next `sync` re-fetches;
      without it, joiners still get `AlreadyFresh` with no extra request; a
      failed fetch is still forgotten; the chat → page → chat round trip
      performs NO extra snapshot fetch (guard against the ON_START
      over-fetch trap).
- [ ] Link registry: links learned from spawned/roster/result-marker are all
      re-applied to timeline rows after a history replace
      (`reapplySubagentLinks` driven from `store.linksFor`), including rows
      loaded after the link was learned.
- [ ] Existing suite green with NO behavior-test changes beyond the two bug
      behaviors and the renamed API surface — in particular tickets 045 (page
      + seeding), 046 (transcript backing + prompt-survival), 048 (resume),
      061/062 (pager races), 064 (keep-alive), 065 (coalescer), 074 (pager),
      085 (snapshot cold start — its AlreadyFresh tests re-pointed to the
      within-process path).
- [ ] `AGENTS.md` and invalidated code comments updated;
      `docs/server-api.md` untouched (no wire change).
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:assembleDebug
      --max-workers=1` (VPS rules) and `:app:testDebugUnitTest` pass; release
      APK published to the file server per project convention.
- [ ] Live verification on-device: (1) open a running sub-agent's page, watch
      the prompt past the transcript fetch and across page reopens; (2) spawn
      a sub-agent, leave to the session list, come back, tap the running row →
      the page opens. — **pending Louis on-device**

## Out of scope

- **Rewriting the kept 70%** (timeline split, coalescer, pager, socket,
  arbiter, screens): they are not part of the defect class.
- **Background sub-agent cold relink**: the roster is foreground-only and REST
  `/tasks` carries no `parent_tool_call_id` — upstream gap (085, unchanged).
  (Background records still get prompt/status from `applyAgentCall` via the
  immediate result, as today.)
- **Mining OLDER `/messages` pages for ancient `Agent` calls**: the snapshot's
  newest page + live frames cover running agents; a call that has scrolled
  past the newest page belongs to a finished run, whose result marker already
  carries the metadata.
- **The web client's single-unmapped-subagent heuristic fallback** — a guess
  that can mis-link swarms; file a follow-up only if the fresh roster proves
  insufficient in practice.
- **Consuming `in_flight_turn` for the MAIN chat's live tail** (085's
  deferral stands); this ticket only mines its `running_tools` args as a
  prompt/link source.
- **Server changes** — the server is upstream (MoonshotAI/kimi-code).

## Notes

- Key code under reimplementation: `ui/chat/SubagentStreamStore.kt` (whole
  internal model), `ui/chat/SnapshotSync.kt` (`coldStarts` 68, `sync` 72-84,
  `resync` 89-95), `ui/chat/ChatViewModel.kt` (sub-agent glue: `onSubagentEvent`
  798-804, `seedSubagentStoreFromLinkedRows` 809-814,
  `applySnapshotRosterLinks` 460-465, cold start 370-390, `onCleared` 583-585),
  `ui/chat/ChatTimeline.kt` (`subagentLinks` 66-70, `linkSubagentTool` 499-502,
  `reapplySubagentLinks` 517-528 — its healing mechanics stay, its link
  OWNERSHIP moves). `SubagentViewModel.kt` unchanged.
- Wire refs: `docs/server-api.md` — Sub-agents (spawn sequence 1349-1364; the
  task-prompt splice 1357-1358 with its optional git-context prefix
  1388-1391), Transcript (no prompt on system-triggered turns 561-568; `undo`
  markers warm-only 595-598), Session snapshot roster (foreground-only,
  `parent_tool_call_id`, reset on the main agent's next turn 1537-1576),
  snapshot top level (messages oldest-first, `in_flight_turn.running_tools`
  verbatim 319-372).
- Ticket 085's `AlreadyFresh` rationale is preserved in spirit: roster seeds
  still never override LIVE-fed data — but now via explicit status pinning +
  freshness, not via a process-lifetime cache. Its doc comment
  (`SnapshotSync.kt:26-33`, 50-58) must be rewritten to the new invalidation
  semantics.
- Related done tickets: 045 (page + seeding), 046 (transcript backing), 048
  (resume), 061/062 (pager races), 064 (keep-alive), 065 (coalescer), 071
  (nested pages), 074 (shared pager), 085 (snapshot sync). The investigation
  transcript (2026-08-03, this session) holds the full symptom→cause chains
  and the web-client findings if more detail is needed.
