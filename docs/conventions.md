# Layout & conventions

- `data/network/` — `ApiClient` is the shared HTTP scaffold (URL building,
  401/HTTP/IO → `DataError`, envelope unwrap). Every REST call goes through it;
  never hand-roll `Request.Builder` in a repository.
- `data/chat/` — `ChatRepository` is a thin facade over `ChatApi` (REST verbs),
  `SessionSocket` (WebSocket, reconnect, per-session resume cursor held at
  instance level so it survives the ON_STOP/ON_START collection restarts —
  ticket 048; a refused cursor acks as `resync_required` → snapshot re-sync,
  ticket 085),
  `ModelsRepository`,
  `SessionPrefsStore` (per-session model/effort prefs — server-synced since
  ticket 107: `/status` seeds and `agent.status.updated` frames write the
  session's bound model/effort through to it, so the chip and the prompt send
  path — both store-fed — follow bindings made from any client; a pick made
  locally but not yet sent wins over server reports), `ChatMessageMapper`
  (DTO→domain), and `SessionActivityStore` (`data/sessions/` — process-lifetime
  record of successfully sent prompts/steers; the session list consumes it on
  return to bump those rows before its silent refresh, ticket 086). New chat
  endpoints belong in a collaborator, not the facade. Background tasks (ticket
  092): `ChatApi.listTasks/cancelTask` (REST `/tasks`; a terminal-task cancel
  answers envelope 40904 → typed `DataError.Api`), `task.started`/
  `task.terminated` WS frames → `SessionEvent.TaskStarted/TaskTerminated` (the
  shared `TaskInfo` carries the CORE status vocabulary — killed/timed_out/lost —
  and epoch-millis times, unlike REST's ISO strings; `task.notified` and the
  legacy `background.task.*` aliases are deliberately unparsed). The payload's
  `agentId` stamp — the OWNING agent — is parsed onto the events, making them
  `AgentScopedEvent`s: main-owned tasks feed the chat's `JobsStore`,
  sub-agent-owned ones route to `SubagentStreamStore` (REST `/tasks` serves
  only the main agent's store, so a sub-agent's tasks are live-only and not
  REST-cancellable — docs/server-api.md, Background lifecycle). Domain:
  `BackgroundTask`/`TaskInfo` in `data/chat/Tasks.kt` — kind `tool` (question
  flows) is filtered out at the mapping boundary.
- `data/sessions/` — `SessionsRepository` (list/get/create REST facade), the 087
  `SessionListSocket`, the 086 `SessionActivityStore`, and `SessionListCache`
  (ticket 111): the last-known list as one JSON file per server under
  `filesDir/session-list-cache/` (file name = SHA-256 hex of the base URL), so a
  cold start renders before any network traffic. The session-list ViewModel owns
  both sides — init load, plus one debounced (~500 ms, IO) write-through
  collection covering every mutation path. A corrupt file is deleted and treated
  as absent.
- `data/attachments/` — `AttachmentStore` owns all platform file I/O (content
  resolver reads capped at 256 MiB — a client-side guard; the server removed
  its own upload cap in 0.31.0, see ticket 059; cache writes;
  FileProvider URIs). Composables never touch `ContentResolver`/`File`.
- Errors: repositories return `Result` + sealed `DataError`; ViewModels map them
  with `toUserMessage()` (kept next to `DataError`). Dropped WebSocket frames
  get a `Log.w` — never swallow silently.
- `ui/<screen>/` — screen composable + ViewModel. Chat building blocks live in
  `ui/chat/components/` (same package, `internal` — androidTests import them).
  `ui/common/` holds shared `StateViews` (loading/error/empty) and
  `Formatters` — reuse them instead of adding one-off copies.
- Chat feature collaborators (the 065 `DeltaCoalescer` pattern, generalized by
  072): a cohesive chat feature gets an `internal` class in `ui/chat`
  (`DeltaCoalescer`, `SeedGuard`, `ChatAttachments`, `QuestionWizard`,
  `HistoryPager`, `JobsStore`) owned by the ViewModel. Collaborators are logic
  holders, NOT second state sources —
  the screen's `UiState`/`Effect` stay in the ViewModel; intents arrive as
  calls (one-line delegates keep the screen-facing API) and updates report back
  through constructor callbacks (a state-transform sink, an effect sink, a
  scope provider). New chat features grow a collaborator from day one instead
  of landing in `ChatViewModel`. Feature tests sit next to the collaborator on
  the shared `ChatViewModelTestBase` harness. `JobsStore` (ticket 092) backs the
  composer jobs pill/sheet (`UiState.jobs`, event-driven — REST `/tasks` is only
  the cold-start/`resync_required` seed, never polled; cancel is optimistic,
  40904 = already-terminal success). The seed drops a RUNNING subagent-kind entry
  unknown to the store's known-background set (ids learned from live `task.*`
  frames and `Agent` result `task_id:` markers — ticket 101): since server
  0.34.0 that's a foreground sub-agent, whose UI home is the sub-agent
  roster/page; terminal subagent entries stay listed (REST drops foreground
  tasks once terminal, so a terminal listing is always a background job). The pill is sticky (ticket 094): visible
  whenever the session has ANY background task (running or terminal — the
  unfiltered REST seed resurrects it across re-entries), labeled
  `Jobs · finished/total` (finished = completed/cancelled/failed — the Todos
  done/total polarity); deliberate divergence from the web dock, whose pills
  vanish at zero. The pills and their sheets are shared UI:
  `TodoJobsDock` (ui/chat) bundles the pill row, the sheet-open state, the
  sheets and the sub-agent tap's timeline resolution; the chat mounts it above
  the composer, the sub-agent page as its bottom bar — there with a null cancel
  callback only (REST `/tasks:cancel` is main-agent-only): bash rows open the
  job screen's store-backed mode (ticket 098), wired exactly like the chat's.
- Cold start / resync (085, freshness model 091): `GET .../snapshot` is the single
  entry point — `SnapshotSync` (ui/chat, session-scoped singleton like the store, so
  the page-on-top restore path with no ChatViewModel runs the same rebuild) owns the
  fetch, taken BEFORE the session's first socket subscribe (the chat's gate and the
  store's keep-alive both await it). It seeds `SessionSocket.seedCursor` with the
  `as_of_seq`/`epoch` watermark (gapless first subscribe; the matching `Reconnected`
  skips the redundant 048 resync), applies the roster to the store (`applyRoster`),
  and mines the snapshot's messages page + `in_flight_turn.running_tools` for
  `Agent` calls (`applyAgentCall` — chat-independent, so the page-on-top restore
  recovers prompts/links too), and hands the chat-side bundle (history page,
  busy, pending approvals/questions) to the calling ChatViewModel. The bundle's
  `session.usage` carries the real fill since 0.38.0 (a 0 limit still maps to
  null), and every cold start re-reads /status regardless: the session's bound
  model/effort (107) is served only there, and the ring converges on the live
  value (seed-guarded — web parity: kimi-web does the same).
  The fetch is cached per session only until the session is LEFT:
  `SubagentStreamStore.release` calls `SnapshotSync.forget`, so the next entry
  re-fetches (one cheap GET per entry — no process-lifetime cache of mutable server
  state) while chat → page → chat costs zero fetches (`AlreadyFresh`).
  `resync_required` re-runs it (keepLive per the snapshot's `busy`); a failed fetch
  is the ordinary load-error state, never a legacy-burst fallback. The
  within-process resume (048's cursor replay + /status resync) is untouched.
  The transcript cold status reads the real turn states (`cancelled`/`failed`
  → Failed, 0.31.1) for the window the roster cannot cover.
- Sub-agent streams (045, state model reimplemented in 091): `SessionSocket` tags
  transcript events with the emitting `agentId`; sub-agent-scoped events route to
  `SubagentStreamStore` (ui/chat, session-scoped singleton keyed by session, one
  `SubagentRecord` per sub-agent — metadata (identity, task prompt, row link,
  status) are first-class FIELDS, never rows in the replaceable timeline, so the
  transcript fetch cannot wipe them; delta coalescing via the `DeltaCoalescer`
  shared with ChatViewModel, ticket 065). Every source reaches the record through
  one funnel method per source kind, writing only the fields it is authoritative
  for (precedence high→low: live lifecycle pins `status` → fresh roster → result
  text → transcript turn states; identity/prompt/link first-writer-wins): live
  frames via `apply` (the FIRST turn's first `context.spliced` user message is the
  task prompt — lifted into `record.prompt`, never a timeline row; later splices
  stay user bubbles), the snapshot roster via `applyRoster` (re-applicable on every
  entry — a fresh roster heals non-live-pinned statuses), `Agent` calls from any
  carrier (history rows, snapshot messages, `running_tools`) via `applyAgentCall`,
  transcript pages via `applyTranscriptPage`/`prependTranscriptPage`. The
  `toolCallId → subagentId` link registry lives in the store (learned from
  spawned/roster/result-marker); ChatViewModel re-drives `store.linksFor` into its
  timeline after every store-affecting apply. The store is fed by
  exactly ONE session-socket collection at a time (064): the chat's while its
  socket gate holds the session (ChatViewModel signals
  chatCollectionStarted/Stopped), otherwise the store's own keep-alive
  collection while a sub-agent page is open (SubagentViewModel registers via
  pageOpened/pageClosed; the job screen registers the same page count so the
  socket — and with it the live `task.terminated` truth — survives watching
  a job) — the keep-alive lives in the store because after
  process death with a page on top the chat ViewModel is never recreated. Two
  concurrent collections would double-feed on replay, so the keep-alive never
  runs while a chat collection does; never open a page-owned socket. The
  read-only `SubagentScreen` reuses `MessageList`/`ToolRows`; linked `Agent`
  rows carry `subagentId` — linked rows never expand (the page has all the
  details); tapping the row or its trailing open button navigates to the page.
  The record also owns the page's dock data: the sub-agent's own todo list
  (the record timeline's `todos()` — the chat's ticket-042 mechanism) and its
  own background tasks (the live `task.*` frames stamped with its id, upserted
  into `SubagentRecord.jobs` via the shared `TaskInfo.toJob()` mapping — no
  REST seed exists, REST `/tasks` being main-agent-only; on a cold open the
  transcript pages' `task` turn origins heal the terminal status into the
  record's jobs — ticket 099, a live `task.terminated` stays authoritative
  over the origin); the page mounts the
  shared `TodoJobsDock` as its bottom bar, with the job screen wired
  (store-backed mode, ticket 098) but no cancel (same REST limitation).
  Transcript backing (046): the page's history comes
  from `GET .../transcript?agent_id=…` (`ChatApi.transcript` →
  turn/step/frame domain model in `data/chat/Transcript.kt`, NOT
  `ChatMessage`); `SubagentViewModel` fetches the newest page on cold open
  (keepLive merge under the live tail), refetches canonically when the
  sub-agent reaches a terminal state, and pages older turns with
  `before_turn` on scroll-to-top (prepend, dedup by turn id). That paging
  machinery is NOT a second implementation: both this page and the main chat
  drive the shared `HistoryPager` (ui/chat, internal — ticket 074, the root
  fix for the 061/062 drift), which owns the fetch jobs and their guards
  (newest-page latest-wins, older-page in-flight/newest-page guards) while
  each screen parameterizes the fetch (messages vs transcript), the merge
  (`ChatTimeline` directly vs the store's `applyTranscriptPage`/
  `prependTranscriptPage`, each flushing its own `DeltaCoalescer` before a
  replace), and the cursor/hasMore ownership. The
  task-prompt bubble lives outside the timeline (`SubagentRecord.prompt`) —
  the transcript never carries the task prompt.
- Streaming: `ChatViewModel` and `SubagentStreamStore` conflate assistant/thinking
  deltas through the shared `DeltaCoalescer` (ui/chat, internal — ticket 065):
  leading edge immediately, then one emission per ~100 ms window, flushed before any
  non-delta event (`onEvent` carries the invariant) and before REST refetches
  (`flush()`). With `StreamingMarkdownState` adopted (082), a published
  snapshot costs a tail-only re-parse, so the 100 ms window now bounds
  recomposition/layout churn rather than parse cost — the value stays
  (no regression measured; phone-frame eyeball is Louis's). The
  window is injectable (`streamCoalesceWindowMillis`; 0 in unit tests).
