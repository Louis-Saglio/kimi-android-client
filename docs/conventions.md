# Layout & conventions

- `data/network/` — `ApiClient` is the shared HTTP scaffold (URL building,
  401/HTTP/IO → `DataError`, envelope unwrap). Every REST call goes through it;
  never hand-roll `Request.Builder` in a repository. `SocketStallWatchdog`
  (ticket 152) is the shared stalled-socket detection both WebSockets run: one
  instance per connection, armed at socket open, fed `heartbeat_ms` from each
  connection's `server_hello`; inbound silence past 3× it unwinds the connection
  as an ordinary failure, so the existing reconnect loop (backoff, cursor resume,
  the collector's REST resync) treats a silently-stalled socket exactly like a
  dropped one.
- `data/chat/` — `ChatRepository` is a thin facade over `ChatApi` (REST verbs),
  `FileHistoryApi` (the turn file-history REST routes, ticket 149 —
  `GET .../file-history/changes?turn_id=N` → domain `TurnFileChanges`/`FileChange`;
  `turn_id` is the main transcript's ordinal, and `binary`/`oversize` rows have no
  comparable content; ticket 150 adds
  `GET .../file-history/content?turn_id=N&path=<p>&phase=start|end` → domain
  `FileContent?` — null when the file did not exist at that phase, the path
  URL-encoded as the query param),
  `SessionSocket` (WebSocket, reconnect, per-session resume cursor held at
  instance level so it survives the ON_STOP/ON_START collection restarts —
  ticket 048; a refused cursor acks as `resync_required` → snapshot re-sync,
  ticket 085),
  `ModelsRepository`,
  `SessionPrefsStore` (per-session model/effort prefs — server-synced since
  ticket 107: `/status` seeds and `agent.status.updated` frames write the
  session's bound model/effort through to it, so the chip and the prompt send
  path — both store-fed — follow bindings made from any client; a pick made
  locally but not yet sent wins over server reports — plus the ticket-141
  permission-mode pick, which is deliberately NOT server-synced: `/status`
  always reports a mode, so write-through would turn follow-the-server
  sessions into stamped ones; the stored pick wins over the report and
  self-heals via the per-prompt `permission_mode` stamp), `ChatMessageMapper`
  (DTO→domain; a tool call's raw `args` JsonObject rides the domain/end-to-end
  models — `tool_use` parts, `tool.call.started` events, transcript tool frames,
  `ChatItem.ToolActivity` — and payload-derived display renderings live in
  `data/chat` helpers: `ToolPreview` for the row preview, `ToolDetailFields` for
  the expanded row's generic key/value args table, ticket 118, `ToolPayloads`
  for the typed per-tool payload parse (ticket 119 — `ToolPayload.Edit` first;
  ticket 120 adds `Read`/`Write`, the payload carrying the row chip derivations:
  Read's `lines a–b` range label, Write's line/byte counts; ticket 121 adds `Bash` —
  the first payload that re-skins the row's INLINE expansion (terminal detail block)
  instead of navigating away, the precedent for 122–125; ticket 122 adds
  `Glob`/`WebSearch`/`FetchUrl` — the read-only lookup payloads, also inline-expanding:
  the counted path list with its truncation chip, the markdown-rendered result, and
  the full-URL + note-caption + verbatim content layouts respectively; ticket 123
  adds `Grep` — the same inline expansion, its `/pattern/` preview and scope chip
  on the row, the filter knobs as chips and the result structured per
  `output_mode`; ticket 124 adds the scheduling/task-control payloads —
  `CronCreate` (inline expansion: the full prompt replaces the args table; the
  result is `key: value` prose on the wire, rendered verbatim — docs/server-api.md),
  `CronDelete` (lean, generic), `TaskOutput`/`TaskStop`/`WaitFor` (a carried
  `task_id` links the row to the job screen, the 096 background-Bash treatment) and
  `TaskList` (generic — a task-less `WaitFor` too). `CronList` has no payload: the
  call carries no args, so its row is name-keyed). Ticket 125 closes the 118–125
  arc with the meta tools (`Skill`, `ReadMediaFile`, `ExitPlanMode`, `CreateGoal`,
  `UpdateGoal`, `SetGoalBudget` — `GetGoal`/`EnterPlanMode` carry no payload by
  design): lean tuned rows, nothing navigates away, and the tuning splits exactly
  two ways — `ToolPreview` string keys where a string suffices (Skill's invocation
  preview, SetGoalBudget's "30 minutes" phrasing, CreateGoal's `objective`) and
  payload-read badges/chips on the row where structure is needed (ReadMediaFile's
  image glyph + `crop W×H` badge — the region parses all-or-nothing, so a
  half-specified one earns none —, UpdateGoal's colored status badge replacing the
  preview, ExitPlanMode's fixed "Plan ready for review" label + option-label chips
  above the generic args table). Ticket 135 adds `AgentSwarm` — the swarm call's
  `description` label plus its expected member total (the `items` /
  `resume_agent_ids` count), feeding the swarm card's aggregate. Ticket 157 adds
  the `Agent` spawn row — the one display-driven row (the 0.31.0
  `tool.call.started` display, kind `agent_call`, carries
  `agent_name`/`prompt`/`background`; history parts carry no display, so only
  witnessed or snapshot-seeded rows get it): the row titles itself "{agent_name}
  sub-agent", previews the prompt and badges a background spawn; the tap follows
  the spawn's kind — the sub-agent page (foreground, the 045 link) or the job
  screen (background, the result's `task_id: agent-…` head, the 096 treatment).
  Ticket 158 adds `AgentResults` — the `Agent` result's continuation blocks
  (0.43.0's foreground `resume_hint:`, 0.31.0's background `next_step:`/
  `resume_hint:`), parsed verbatim onto the sub-agent record (independently of the
  status precedence — the call's result is their only carrier) and, via `enriched`,
  onto the spawn row: the finished sub-agent page (pinned above the dock) and a
  settled spawn row (under the row) show the shared "Next step" section, whose
  Continue pre-fills the chat composer with the hint's suggested
  `Agent(resume="agent-N", prompt="…")` call — from the page, riding a nav result
  on the chat entry's SavedStateHandle. Hint-less results render exactly as before.
  Anything still
  falling back to the plain generic table is by design (unknown/future/MCP tools). `ReadOutputs` (the
  Read output's trailing `<system>…</system>` status block lifted into the file
  screen's caption chip — end-anchored, tolerant fallback; `BashOutputs` does the
  same for the Bash output's `Command failed with exit code: N` trailer — ticket
  121's `exit N` chip; `GlobOutputs` lifts the Glob output's head truncation marker
  into the row's chip and counts the non-blank lines for the "N matches" header,
  `FetchUrlOutputs` lifts the FetchURL output's head note sentence into the row's
  caption — both verified against the 0.39.1 tool source, ticket 122;
  `GrepResultParser` (ticket 123) parses the Grep output per `output_mode` into a
  sealed result (per-file content groups / path list / count entries, the tool's
  notices lifted into captions) — any line failing the mode's grammar degrades
  the whole output to the verbatim block) and
  `EditDiffs` for the unified line-diff model (java-diff-utils Myers on the Edit
  call's old/new strings; rows plus the `+a −b` delta-type counts)), and
  `SessionActivityStore` (`data/sessions/` — process-lifetime
  record of successfully sent prompts/steers; the session list consumes it on
  return to bump those rows, ticket 086 — since ticket 112 no automatic refresh
  follows, so the bump itself is the visible update). New chat
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
- `data/sessions/` — `SessionsRepository` (list/get/create/fork/archive/restore/delete REST
  facade; ticket 132 adds `listArchivedSessions` — the v1 list with
  `archived_only=true&exclude_empty=true` and the same `before_id` cursor, rows
  carrying the `archived_at` the live-list mapping drops), the 087
  `SessionListSocket` (its global-frame path also surfaces `event.session.created` —
  ticket 129, the carried session upserted by the ViewModel at its `updated_at`
  position; only plain creates arrive, fork frames being dropped server-side — and
  `event.config.changed`/`event.model_catalog.changed` — 0.39.1, ticket 110 —
  which the session-list ViewModel turns into a `ModelsRepository` cache
  invalidation, so the next models/config read refetches), the 086
  `SessionActivityStore`, and `SessionListCache`
  (ticket 111): the last-known list as one JSON file per server under
  `filesDir/session-list-cache/` (file name = SHA-256 hex of the base URL), so a
  cold start renders before any network traffic. The session-list ViewModel owns
  both sides — init load, plus one debounced (~500 ms, IO) write-through
  collection covering every mutation path. A corrupt file is deleted and treated
  as absent. The list never refreshes itself (ticket 112): ON_START with rows on
  screen only re-opens the socket — refresh is pull-to-refresh, the error state's
  Retry, the visible initial load when the cache is empty, one silent re-sync on
  socket reconnect, or the archived screen's nav-result flag after a restore
  (ticket 132: restore pushes no WS event, and the ON_STOP/ON_START cycle alone
  would not heal it — a fresh socket collection's first connect emits no
  Reconnected, only a drop within one collection does). The archived screen itself
  (ui/sessions, read-only rows + per-row Restore) mirrors the flat list's
  paging/footer/state idioms; tapping an archived row does nothing. `WorkspaceLabels` (ticket 114) is the pure cwd →
  disambiguated-label function (last path segment, parent segments prepended
  while another cwd labels the same); the flat rows' supporting lines consume it
  via `SessionListViewModel.UiState.workspaceLabels`, the 115 group headers reuse
  it. The grouped view itself (ticket 115) comes from
  `SessionsRepository.listSessionGroups` — the app's one v2 call
  (`/api/v2/sessions?view=by_workspace&group.page_size=5`, one unpaged request,
  epoch-ms v2 DTOs kept separate from v1's). A group holding fewer rows than its
  server-side `total` gets a show-more footer (ticket 116) paging the rest of the
  workspace through the v1 list (`workspace_id` + `before_id`, `page_size=5` like
  the grouped first page), appends deduped by session id, with a per-group Job
  guard like 010's flat pagination and the flat footer's inline error/retry. Once
  rows beyond the first page are on screen the footer also offers show less, which
  hides the last five rows and cancels any in-flight expansion. Group
  headers fold/unfold their rows on tap (state on `UiState.foldedGroupIds`,
  in-memory only) and carry a new-session button that opens the create dialog with
  the group's cwd pre-selected (`SessionListViewModel.onNewSessionClick(cwd)`).
  The custom folder glyph `ui/common/icons/WorkspaceIcon.kt` (Material Symbols
  path data) marks both the headers and the flat → grouped toggle; the toggle's
  icon is always the mode a tap switches TO, with no tint state. The flat/grouped
  toggle persists in
  `SettingsRepository` (flat default, re-read on every screen start); the live
  socket machinery patches rows by id inside their group (groups never reorder),
  and the activity bump / create-insert / fork-insert / created-upsert (ticket 129) /
  archive-drop / delete-drop (ticket 151 — final, no undo; a 40401 answer drops the
  row like a success) are mode-aware in the ViewModel; the fork inherits the source's `updated_at` (ticket 127), so
  its insert (ticket 128, from the long-press row dialog) lands at the sorted
  position next to the source, not at the top like create.
- `data/search/` — ticket 146's message search: `SearchApi` posts
  `/api/v1/search` (query + page_size 20, the other request fields riding their
  server defaults; the opaque v2 keyset `page_token` passed back verbatim —
  docs/server-api.md, Search) and maps DTO→domain (`SearchPage`/`SearchHit`,
  the hit's epoch-ms time → Instant, the `index_state.stale`/`degraded` pair
  folded into `SearchPage.indexStale` — the screen's banner signal; `incomplete`
  deliberately unsurfaced). Ticket 147's optional `container: {session_id}`
  request field scopes the search to one session (`source:"live"` then;
  `container.agent_id` is ignored server-side and never sent).
  `SearchRepository` is the thin facade. The screen
  (`ui/search`, `SearchScreen`/`SearchViewModel`, entered globally from the session
  list's top-bar magnifier or scoped from the chat's top-bar magnifier — the scoped
  `Search` route carrying the session id/title, the title slot's placeholder and
  the prompt naming the scope) submits on the IME search action, shows the hit
  rows (role
  icon, session title, snippet, platform-relative time) and pages on scroll with
  the sessions lists' end-reached/footer idioms — the keyset cursor pins the
  index generation, so no client-side dedup exists. A paged request's envelope
  40001 is the documented restart-from-page-one signal (the loaded rows stay up
  during the refetch, then are replaced); a new query cancels in-flight page
  loads and drops the token, so tokens never cross queries. A hit tap opens the
  session through the plain Chat route — or, scoped, just pops back to the chat
  below (every hit is that session's) — jump-to-message is out of scope.
- `data/attachments/` — `AttachmentStore` owns all platform file I/O (content
  resolver reads capped at 256 MiB — a client-side guard; the server removed
  its own upload cap in 0.31.0, see ticket 059; cache writes;
  FileProvider URIs). Composables never touch `ContentResolver`/`File`.
- Errors: repositories return `Result` + sealed `DataError`; ViewModels map them
  with `toUserMessage()` (kept next to `DataError`). Dropped WebSocket frames
  get a `Log.w` — never swallow silently.
- `ui/<screen>/` — screen composable + ViewModel. Chat building blocks live in
  `ui/chat/components/` (same package, `internal` — androidTests import them; the
  `ModelPickerSheet` doubles as the Settings screen's server-default model/effort
  editor, ticket 109, and the `PermissionPickerSheet` as its server-default
  permission-mode editor, ticket 142).
  `ui/common/` holds shared `StateViews` (loading/error/empty) and
  `Formatters` — reuse them instead of adding one-off copies. Large tool-payload
  content reaches its detail screen through `ToolContentStore` (ui/chat,
  process-lifetime @Singleton — ticket 119): the row's navigation callback `put`s
  the payload (plus, since ticket 120, the call's output snapshot — the entry type
  is `ToolContentStore.Entry`) under the tool call id, and the screen `consume`s it
  on arrival (the Edit diff screen's ViewModel on init; the 120 file content screen
  has no ViewModel — the destination consumes once into a `remember`),
  and a miss (process death with the screen on top) is the screen's
  missing-content state with back navigation. First consumer: the Edit call's
  unified diff screen (`EditDiff` route — toolCallId only — plus `EditDiffScreen`/
  `EditDiffViewModel` in ui/chat), opened from parsed Edit rows on the chat and the
  sub-agent page alike; those rows never expand inline and carry the pencil glyph
  (`ui/common/icons/EditIcon.kt`), the `+a −b` stat chip and the replace_all "All"
  badge. Second consumer (ticket 120): the Read/Write file content screen
  (`FileContent` route — `FileContentRoute`/`FileContentScreen(path, content,
  caption?)` in ui/chat, one shared body for both payloads; a Write shows the call's
  content verbatim, a Read the call's OUTPUT verbatim with its line-number
  prefixes, a still-running Read a "Call still running" placeholder), opened from
  parsed Read/Write rows on the chat and the sub-agent page alike; those rows never
  expand inline and carry the document glyph
  (`ui/common/icons/DocumentIcon.kt`), Read's range chip / Write's size chip and the
  mode-append "append" badge. The ticket-122 lookup rows keep the inline expansion
  and carry their own glyphs — magnifier `ui/common/icons/SearchIcon.kt` for Glob
  (generic on purpose: Grep reuses it in 123), globe `GlobeIcon.kt` for WebSearch,
  link `LinkIcon.kt` for FetchURL — with per-tool previews (pattern monospace,
  query prose, host-first shortened URL via ToolRows' `hostFirstUrl`). The cron rows
  (ticket 124) carry the clock `ui/common/icons/ClockIcon.kt` and a "one-shot"
  badge on `recurring: false`. The ticket-125 meta rows close the 118–125 arc with
  the lean treatment: ReadMediaFile gets the one new glyph
  (`ui/common/icons/ImageIcon.kt`) and a `crop W×H` badge, ExitPlanMode the fixed
  "Plan ready for review" label plus option-label chips above the generic args
  table, UpdateGoal a status badge instead of a preview — colored per the
  payload's `badgeColor`, riding the AA-checked container/content pairs (the diff
  palettes' contents ARE `successColor` and the scheme's error; the primary pair
  for `active`) — and Skill/CreateGoal/SetGoalBudget are pure `ToolPreview` tuning
  (invocation preview, objective, "30 minutes"). The diff rows' added/removed palettes are the
  `LocalDiffColors` container/content pairs (the `LocalSuccessColor` extension
  pattern), AA-checked in both themes. The turn file-changes screen (ticket 149 —
  `TurnChanges` route, `TurnChangesRoute`/`TurnChangesScreen` in ui/chat, the
  FileContent route/stateless-screen split; rows badge the change status on those
  same diff palettes and stat the `+a −b` counts in the 119 idiom) is NOT staged
  through the store: it fetches from `FileHistoryApi`, and is entered from the chat
  overflow's "Files changed", which resolves the session's newest main turn at tap
  time from the main transcript's ordinals (message history carries no turn ids,
  so no per-turn timeline affordance exists). A comparable row's tap opens the
  before/after viewer (ticket 150 — `TurnFileContent` route (session/turn/path,
  nothing staged), `TurnFileContentRoute`/`TurnFileContentScreen` in ui/chat, the
  same route/stateless split): both phases fetch in parallel on open with a
  per-side error/retry, a Before/After segmented toggle (the ModelPickerSheet
  idiom, After the default) switches instantly, and the text rides the 120
  monospace body, extracted as the shared `SelectableCodeBlock`; a null side
  renders "File did not exist yet"/"File deleted" per tab, a `binary: true`
  side a placeholder.
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
  40904 = already-terminal success). The seed lists only `run_in_background:
  true` entries (the flag rides every task since 0.39.0 — ticket 162, replacing
  101's known-background set fed by live `task.*` frames and `Agent` result
  `task_id:` markers): a listed foreground sub-agent carries false and stays
  out of the dock — the sub-agent roster/page is its UI home. The pill is sticky (ticket 094): visible
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
  The live activity status line (ticket 148) is smaller than a collaborator and
  follows the 058 `StepRetry` precedent: the socket maps `agent.status.updated`'s
  phase variant to `SessionEvent.AgentStatusUpdated` (domain `AgentPhase` — the
  seven 2.0.2 kinds; unknown kinds drop at the mapper), the ViewModel reduces it
  to `UiState.activityStatus` (label + the phase's `since`; `interrupted`/
  `idle`/`ended` → null, a turn end clears), and `ActivityStatusLine`
  (ui/chat/components) renders it directly above the composer — spinner leading
  icon, elapsed `mm:ss` ticking from a 1 s ticker that lives only while a phase
  is shown.
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
  busy, pending approvals/questions, and since ticket 144 the in-flight turn's
  pre-watermark live tail — the aggregated text/thinking plus the still-running
  tools, seeded as ordinary live rows the post-watermark replay then
  extends/heals, closing the gap between the last committed message and the
  live stream on a busy cold open) to the calling ChatViewModel. The bundle's
  `session.usage` is real since 0.38.0 and seeds the context ring directly on
  cold start (161 — a 0 or absent limit still maps to null, "unknown"), and
  every cold start still re-reads /status: the session's bound model/effort
  (107) is served only there, and the same response refreshes the ring
  (seed-guarded against live agent.status.updated frames).
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
  transcript pages via `applyTranscriptPage`/`prependTranscriptPage`. Ticket 135
  adds the member `Cancelled` status with four carriers — the live
  `task.terminated` kill (`status` `killed`, keyed on the task's `agentId`,
  pinning like any live lifecycle write), the schema-level `subagent.cancelled`
  frame, the roster's `cancelled` phase, and the jobs REST seed
  (`JobsStore.onTasksSeed` → `applyTaskList`, a background member's only
  cold-start carrier) — plus the member `model`/`thinkingEffort` pair (the
  spawned frame overwrites when non-blank, roster and REST blank-fill). The
  `toolCallId → subagentIds` link registry lives in the store (learned from
  spawned/roster/result-marker); ChatViewModel re-drives `store.linksFor` into its
  timeline after every store-affecting apply. One-to-many since 134: an
  `AgentSwarm` call's members all register under the call's toolCallId in
  spawn-arrival order, `swarmMembers(sessionId, toolCallId)` serves them sorted by
  `swarmIndex` (nulls last), and `applyAgentCall`'s AgentSwarm branch keeps the
  call's own `description` label plus, since 136, the settled call's
  `<agent_swarm_result>` heal (`SwarmResultParser` in data/chat — the upstream
  render pinned from source, never captured live): members match by `agent_id`
  (completed/failed/aborted → Completed/Failed/Cancelled, the member's
  stop_reason riding as the record's error line) at the Result precedence tier.
  Ticket 137 refined that tier's rule: TERMINAL pins keep winning (a live-settled
  record, 135's killed-task Cancelled, a roster's terminal phase), but a
  NON-terminal pin (live or roster) the member's LATEST call owns yields to the
  parse — the result only exists once every member settled server-side, and a
  cancelled FOREGROUND member gets no live terminal frame at all (no
  `task.terminated`, no `subagent.completed`/`failed`), so the parse is its only
  terminal carrier; the new `latestToolCallId` record field (last writer wins,
  unlike the first-writer-wins row link) guards the heal against an old call's
  re-mined result flipping a member since resumed under a new call — and
  `not_started` items backfill as ghost records (Cancelled, item-excerpt label,
  page-less rows). The roster rows' other
  136 piece is `SubagentState.activityLine`: the one-line muted activity (last
  assistant text while Working, the `subagent.suspended` reason while Suspended,
  the resultSummary/error's first line once terminal). Timeline row driving stays single-`Agent`:
  a swarm row never carries `subagentId` — it renders as the swarm card
  navigating to `SwarmScreen` by the tool call id. The card's aggregate (135)
  is pure derivation (`SwarmProgress`) from the member list
  `UiState.swarmMembers` carries — one init collection of the store's
  `swarmMembersByCall(sessionId)`, threaded ChatScreen → MessageList → the
  rows — and the payload's expected total: the status chip, the done/total
  count, the per-phase segmented bar, the running/settled subtitle, and a
  model line when every member agrees; `SwarmScreen` shows the same
  counts/model strip under its top bar and, with `SwarmViewModel`, mirrors the
  sub-agent page's store access (pageOpened/pageClosed included). Ticket 137
  adds the roster row's stop action: a trailing Stop IconButton shows while the
  member is `SubagentState.stoppable` (Queued/Working/Suspended, `taskId` known,
  no ghost), tapping it disables the button and posts the member's
  `tasks/{taskId}:cancel` through the same `ChatRepository.cancelTask` wrapper
  the jobs sheet uses (40904 tolerated as already-terminal success; any other
  failure re-enables and snackbars via the screen's own Effect channel) — no
  optimistic flip, the row settles from the wire (the killed `task.terminated`'s
  135 pin, or the settled call's result per the refined tier above). The store is fed by
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
  The page's one mutation is ticket 138's detach: the top bar's overflow shows
  "To background" (web-parity label) while the record is
  `SubagentState.detachable` (a running foreground member with a known
  `taskId` — `stoppable && !runInBackground`), posting `tasks/{taskId}:detach`
  through `ChatApi.detachTask` (success gates on `data.detached` — a code-0
  `detached:false` refusal snackbars as a failure; the in-flight latch mirrors
  137's stop idiom). The detach's main-owned agent-kind `task.started`
  (`info.agentId` naming the member) routes to the store from `ChatViewModel`
  like 135's `task.terminated` and flips the record's `runInBackground` — no
  duplicate record, the main-owned listing itself stays JobsStore's — which
  removes the affordance.
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
