# Layout & conventions

- `data/network/` — `ApiClient` is the shared HTTP scaffold (URL building,
  401/HTTP/IO → `DataError`, envelope unwrap). Every REST call goes through it;
  never hand-roll `Request.Builder` in a repository. `SocketStallWatchdog`
  (ticket 152) is the shared stalled-socket detection both WebSockets run: one
  instance per connection, armed at socket open, fed `heartbeat_ms` from each
  connection's `server_hello`; inbound silence past 3× it unwinds the connection
  as an ordinary failure, so the existing reconnect loop (backoff, re-subscribe,
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
  `SessionSocket` (WebSocket, reconnect, server 2.5.0's session subscription —
  ticket 175: one `subscribe_v2` frame per connection, grade `delta` for every
  agent (`{"*":"delta"}`); the subscription does not survive a drop, so each
  reconnect re-subscribes and emits `SocketEvent.Reconnected` (the collector's
  resume check). Ticket 176 adds the per-agent resume watermarks, held at
  instance level next to the fold: the cold start seeds main's from the REST
  transcript page (`seedTranscript` — the fold is reset to the page, its `seq`
  becomes the watermark; a page without `seq`, a session that is not live, drops
  it), every `transcript.ops` batch and `transcript.reset` advances its agent's,
  and every `subscribe_v2` sends the whole map as `transcript_since`, so the
  server replays the missed batches before the ack. A child never seen on this
  process has no watermark and takes the server's baseline reset — its history
  pages in over REST after the subscribe. A `transcript.reset` for an agent the
  subscribe asked to RESUME is the stale signal: the journal could not cover the
  watermark, so the agent's fold is reset and `SessionEvent.TranscriptStale`
  emitted (the chat rebuilds from REST; the store ignores it — the page's own
  fetch heals);
  an ack listing the session in `not_found` ends the flow with
  `SocketEvent.Failed(DataError.SessionNotFound)`, which the chat surfaces like a
  failed load. `transcript.reset` is accepted and renders nothing — its `items`
  are always empty, so it never touches REST-rendered history. `transcript.ops`
  batches go through `TranscriptFold` (data/chat), one per session held at
  instance level so it survives the ON_STOP/ON_START collection restarts (the
  048 rationale): the fold turns entity upserts into the existing transition
  events — text/thinking frames emit only their unseen suffix (`append`
  offsets dedup, a gap drops), tool frames emit `ToolCallStarted` on first sight
  and again when `input`/`display` become known (a partial `inputText` renders
  no args — the row's first-writer-wins preview must not lock onto a fragment;
  a complete one parses), `ToolCallResult` once on `done`/`error`; turn/step/
  prompt upserts emit `TurnStarted`/`TurnEnded` (cancelled → `user_cancelled`,
  failed → `error` + the turn's error text on the footer), `StepStarted`/
  `StepCompleted` (+ `StepRetrying` from the schema-level `retry` object) and
  `PromptUpdated` (status + derived queue length, replacing 154's
  submitted/queued/started); an `undo` marker is the `ContextSpliced` echo
  (injections dropped). Every event carries the batch's `agent_id`, so
  sub-agent ops route to the store exactly as before. Since 175 the legacy
  frame branches (approval/question/agent-status/task/subagent/compaction
  families) are deleted, and the domain events documented below for those
  features have no live feed until tickets 178–182 re-feed them from
  the 2.5.0 frames (171/172 re-fed approvals and questions, 179 tasks, below). Ticket 177 adds the main agent's flat `agent`/`usage`
  state frames (decoded from the frame text — their fields sit beside `type`;
  a child agent's are ignored until 182): `agent` → `SessionModelUpdated`
  (only when it names a model or effort — a model-less session omits both, a
  blank is "nothing said"), `SessionPermissionUpdated` and `AgentBusyUpdated`
  (`running`/`idle`); `usage` → `ContextUsageUpdated` only with a positive
  `max_context_tokens` (absent/zero = unknown, never a 0/0 ring). They precede
  the subscribe ack, so the first pair is the chat's seed — there is no REST
  status read (`/status` is gone). Ticket 178 decodes the flat `todo` frame
  (`TodoFrame` → `toTodos()`: blank titles skipped, an unknown status is
  Pending) into `SessionEvent.TodoUpdated(items, agentId)` for EVERY agent:
  main's replaces `UiState.todos` wholesale (`[]` clears the widget; the first
  frame precedes the ack, so it is the cold-start seed, and the server re-sends
  it after an undo or a cold restore), a child's is sub-agent-scoped and
  replaces its `SubagentRecord.todos`. The list is never derived from
  `TodoList` tool calls — `ChatTimeline` only suppresses their rows and results
  (ticket 042, by `TODO_LIST_TOOL_NAME`) and holds no todo state. Ticket 179
  decodes the flat task frames for EVERY agent, tagged with the OWNING agent:
  `task.list` (the agent's running tasks, before the ack on every connection) →
  `SessionEvent.TaskListed(tasks, agentId)`, `task` (one task's full state on
  every change) → `TaskUpdated(task, agentId)`, both mapped to the domain
  `TaskState` (`data/chat/Tasks.kt`, `TaskStateDto.toTaskState()` — the frame's
  own vocabulary: kind shell/subagent/other, status running/ended, the
  `TaskOutcome`, `detached`, `child_agent_id`, …; a blank id or unknown status
  drops the entry). The socket also keeps the latest state per (agent, task) at
  instance level (`observeTask` — every frame and control reply upserts, a
  `task.list` drops the agent's running entries it no longer names, ended ones
  stay) and sends the flat task controls (`sendTaskControl` — `task.stop`/
  `task.background`, `{type, request_id, session_id, agent_id, task_id}` on the
  session's open connection): the `{type:"response", request_id, code, msg?,
  task?}` reply is matched by `request_id`, a code-0 reply's task is emitted as
  that agent's `TaskUpdated` (and recorded) BEFORE the call returns it, a
  non-zero code fails with `DataError.Api` and applies nothing, and no
  connection / a dropped one / no reply in 15 s fails with `DataError.Network`.
  Ticket 180 adds the output stream on the same control path: `watchTask`
  sends the flat `task.watch` (`since_offset?`, `tail_bytes?` beside the
  control fields; reply handled like `sendTaskControl`'s), `unwatchTask` the
  flat `task.unwatch` fire-and-forget (its reply is dropped as an unknown
  request — the leaving screen cannot wait), and every `task.output` frame
  (`TaskOutputFrame`) is emitted as a `TaskOutputChunk(agentId, taskId,
  offset, text)` on a hot, replay-less per-session flow (`observeTaskOutput` —
  a watcher subscribes BEFORE sending the watch; never a `SessionEvent`).
  `observeConnection` is the open connection's id (null while none is open):
  a watch dies with its connection, so a new id tells a watcher to re-watch. Ticket 171 decodes the flat `interaction`
  frame (no `payload`, no `seq`; `InteractionFrame`, mapped by the pure
  `ChatMessageMapper.toInteractionEvent`, which dispatches on `kind` to
  `toApprovalEvent`/`toQuestionEvent`): an approval `pending` →
  `ApprovalRequested` (card id = the interaction id, summary over
  `tool_input_display`/`action`, the asking `agent_id`; a `plan_review`
  display — `ExitPlanMode`, ticket 173 — also carries its plan Markdown,
  `ChatMessageMapper.planReview`, which the card renders with the chat's
  `MarkdownBody` in a height-capped scroll area above Allow/Deny; the
  transcript entity's camelCase `display` maps the same way), `ended` →
  `ApprovalResolved` for every outcome — the card closes on that frame, never
  on the POST response, and a turn end does not clear it (an abort ends its
  approvals with `outcome:"cancelled"`; a background sub-agent's outlives the
  main turn). Ticket 172 does the same for questions: `pending` →
  `QuestionRequested` (question id = the interaction id, `tool_call_id` links
  the `AskUserQuestion` row, `agent_id` the asker, `request.questions[]`
  through `QuestionRequestDto`/`QuestionItemDto`), `ended` →
  `QuestionResolved` for every outcome (an abort ends a question
  `outcome:"dismissed"`); a turn end clears nothing and nothing polls — the
  `/questions` routes are gone. Each connection collects the pending
  approvals and questions of its `transcript.reset` snapshots
  (`snapshot.interactions` holds ended entities too —
  `ChatMessageMapper.pendingApprovals`/`pendingQuestions` filter them), and an
  ack accepting the session emits `ApprovalsReset` and `QuestionsReset` with
  them (empty on a resumed subscribe, which sends no reset): the ViewModel
  REPLACES both queues, and the standalone pending frames the server re-sends
  right after the ack dedup into them by interaction id, in place.
  `QuestionWizard` stashes the drafts of a head question the reset dropped and
  restores them when its re-sent frame makes it the head again, so a network
  blip never wipes a half-filled wizard. Both kinds answer through
  `.../interactions/{interaction_id}` (`ChatApi.postApprovalDecision`:
  `{"kind":"approval","decision":…}`; `answerQuestion`:
  `{"kind":"question","answers":{…}}`; `dismissQuestion`:
  `{"kind":"question","dismissed":true}`, an ordinary code-0 success); `40404`/
  `40902` surface as `DataError.Api` with the server message),
  `ModelsRepository` (models/config caches; catalog items map their optional
  `upgrade` retirement guidance — ticket 166 — and its `clientConfigs` fetches
  the two named cloud policies, `model_switch_confirm`/`effort_switch_confirm`,
  fresh per chat model-data load — tickets 167/170; `config(settings)` also maps
  the config `models` alias → upstream id map the switch rules match),
  `SessionPrefsStore` (per-session model/effort prefs — server-synced since
  ticket 107: the `agent` frame — `/status` and `agent.status.updated` before
  ticket 177 — writes the session's bound model/effort through to it, so the chip and the prompt send
  path — both store-fed — follow bindings made from any client; a pick made
  locally but not yet sent wins over server reports — plus the ticket-141
  permission-mode pick, which is deliberately NOT server-synced: the server
  always reports a mode, so write-through would turn follow-the-server
  sessions into stamped ones; the stored pick wins over the report and
  self-heals via the per-prompt `permission_mode` stamp. The `agent` frame's
  `permission_mode` (ticket 177, after 165's live variant) follows the same
  guard for the chip — read-only, never written through; the ViewModel shows
  the stored pick on every start, ahead of the frame), `ChatMessageMapper`
  (DTO→domain; a tool call's raw `args` JsonObject rides the domain/end-to-end
  models — `tool_use` parts, `ToolCallStarted` events, transcript tool frames,
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
  the `Agent` spawn row — the one display-driven row (the tool frame's
  `display`, kind `agent_call`, carries
  `agent_name`/`prompt`/`background`; history parts carry no display, so only
  witnessed or cold-start-seeded rows — a running transcript turn's tool frame,
  ticket 176 — get it): the row titles itself "{agent_name}
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
  endpoints belong in a collaborator, not the facade. Background tasks (tickets
  092/179): there is no REST task API since server 2.5.0 — the session socket's
  task frames and controls (above) are the only channel, exposed through
  `ChatRepository.sendTaskControl` and `observeTask`; ticket 180's output
  stream through `watchTaskOutput`/`unwatchTaskOutput`/`observeTaskOutput`/
  `observeSocketConnection`. Domain: `TaskState`/`TaskOutcome`/`TaskControl` in
  `data/chat/Tasks.kt`; `TaskOutputChunk` and the pure `TaskOutputTail` (the
  bounded byte-offset assembly, below) in `data/chat/TaskOutput.kt`.
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
  missing-content state with back navigation. `CompactionSummaryStore` (ticket
  130) is the same hand-off for a done compaction divider's markdown (`CompactionSummary`
  route — dividerId only — `CompactionSummaryScreen`, no ViewModel): `MarkdownBody`
  in a selectable scroll, the token delta in the top bar only when the live divider
  carried it, the divider row chevroned only while a summary exists. First consumer: the Edit call's
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
  the shared `ChatViewModelTestBase` harness. `JobsStore` (ticket 092, on task
  frames since 179) backs the composer jobs pill/sheet (`UiState.jobs`,
  event-driven, no polling, no REST): the main agent's `task.list` merges
  (`onListed` — authoritative for running tasks only: listed jobs upsert, a
  still-running job the list no longer names ended unseen and is dropped,
  terminal rows stay — the server never re-lists an ended task) and its `task`
  frames upsert the full state (`onUpdated`). The shared pure mapping
  `TaskState.toBackgroundJob()` keeps only BACKGROUND (`detached:true`) shell
  (→ Bash) and subagent (→ Subagent, `child_agent_id` = the page link) tasks:
  `other` tasks (background questions) and foreground tasks (their waiting tool
  call is their UI) are no jobs; an ended task's outcome maps through
  `toJobStatus()` (killed → Cancelled, completed → Completed, the rest → Failed,
  `timed_out` also setting `Job.timedOut`). Stop is `task.stop` with no
  optimistic flip — the ended state (the reply's, then the frame) turns the
  row Cancelled; an error reply (`40933`, …) or no socket snackbars and changes
  nothing. A `task.background` (the sub-agent page's "To background") replies
  `detached:true`, which is what lists the row. The pill is sticky (ticket 094): visible
  whenever the session has ANY background job (running or terminal — terminal
  rows linger for the session's lifetime), labeled
  `Jobs · finished/total` (finished = completed/cancelled/failed — the Todos
  done/total polarity); deliberate divergence from the web dock, whose pills
  vanish at zero. The pills and their sheets are shared UI:
  `TodoJobsDock` (ui/chat) bundles the pill row, the sheet-open state, the
  sheets and the sub-agent tap's timeline resolution (a transcript-healed job
  has no `child_agent_id`); the chat mounts it above
  the composer, the sub-agent page as its bottom bar — there with a null cancel
  callback only (the sub-agent's own task controls are ticket 182's): bash rows open the
  job screen's store-backed mode (ticket 098), wired exactly like the chat's.
  The job screen's main-agent mode (`JobDetailViewModel`) reads the socket's
  task record (`observeTask` — status, timestamps, description, command) and
  stops with `task.stop`; a task this process never saw keeps the route seeds
  with an empty status line. Its Output streams with `task.watch` (ticket 180,
  no poll): while the screen is started and the socket has an open connection,
  one watch per connection (`tail_bytes` 32 KiB; once output is held,
  `since_offset` = its UTF-8 byte end, so a reconnect or ON_STOP→ON_START
  re-entry neither loses nor duplicates). Chunks append into a
  `TaskOutputTail` by BYTE offset (never `String.length`): a chunk ending at or
  before the held end is a duplicate, a straddling one keeps its new bytes, a
  later one opens a segment rendered behind a `[… N bytes missing …]` marker,
  and at most 64 KiB is retained (oldest bytes cut on a character boundary,
  `[… N earlier bytes not shown …]`). ON_STOP sends `task.unwatch` before the
  page count drops; an ended task unwatches once the watch's first chunk (its
  final tail) is in and is never re-watched. A watch error (`40406`, `50003`)
  stops watching for good: `outputError` above the output, which falls back
  to the ended frame's `result_summary`/`error` like a task with no streamed
  text. The store-backed mode sends no watch.
  The live activity status line (ticket 148) is smaller than a collaborator and
  follows the 058 `StepRetry` precedent. Since 2.5.0 (ticket 177) the server
  reports no phase — only the `agent` frame's busy `status` — so
  `UiState.activityStatus` is a computed property over the transcript state:
  null unless `agentActiveSince` is set (`running`; `idle` and a turn end
  clear it), then a pending approval → "Waiting for approval", a pending
  question → "Waiting for your answer", a step retry → "Retrying…", the
  newest still-running main tool (`UiState.runningTool`, fed by the
  ViewModel's `runningTools` map from the live tool events and re-seeded from
  the cold start's running turn) → "Running X…", a running turn →
  "Thinking…", else (busy with no turn — background work) "Working in
  background…"; `ActivityStatusLine`
  (ui/chat/components) renders it directly above the composer — spinner leading
  icon, elapsed `mm:ss` ticking from a 1 s ticker that lives only while a status
  is shown.
- Send-path recovery and cloud switch gates (tickets 167–170): the composer's
  model/effort taps go through `ChatViewModel.onModelPicked`/`onEffortPicked`,
  which consult the two client configs fetched with the model data
  (`model_switch_confirm`, `effort_switch_confirm`; `SwitchConfirm.firstMatch`
  over `SessionAge` — `old` once a timeline user message exists, `new`
  otherwise). A match raises a Continue/Cancel dialog
  (`PendingSwitch` in `UiState`); only Continue calls the unchanged
  `onModelSelected`/`onEffortSelected` commit path, and Cancel writes nothing.
  A null/disabled config, unknown alias, nonmatching rule, running turn or
  same-value pick commits immediately. The effort sheet additionally shows
  `effortDescriptions[effectiveEffort]` for `managed:kimi-code` models only.
  The send failure path (`handleSendFailure`) keeps the draft and never
  resubmits automatically: 40931 `SESSION_OCCUPIED` names the envelope
  `details.holder.clientDisplayName` (fallback: the server message) and offers
  takeover only on the SECOND consecutive 40931 (`onTakeOverSession` posts
  `sessions/{id}:takeover` once); 40932 `SESSION_STALE` posts `:reload` then
  applies one fresh cold-start resync and asks the user to retry. The envelope's
  `details` is carried only by `DataError.Api`, for 40931 only.
- Cold start / resync (085, freshness model 091, rebuilt on the 2.5.0 surfaces by
  176 — `/snapshot` is gone): `TranscriptColdStart` (ui/chat, session-scoped
  singleton like the store, so the page-on-top restore path with no ChatViewModel
  runs the same rebuild) owns the fetch — three parallel reads, `GET .../messages`
  (the newest history page), `GET .../transcript?agent_id=main&page_size=1` (the
  newest turn plus the session's global state: pending interactions, prompt
  entities, the `agents` roster, the `seq` watermark — `TranscriptPage` carries
  them, mapped in `ChatMessageMapper`) and `GET .../prompts` (the queue,
  `TranscriptSyncApi`) — any failed read failing the whole cold start. It is taken
  BEFORE the session's first socket subscribe (the chat's gate and the store's
  keep-alive both await it; the chat claims the feeder role before awaiting, so the
  keep-alive never folds the socket while the chat rebuilds). On success it seeds the
  socket (main's fold + watermark from the transcript page), applies the roster to
  the store (`applyRoster` — the transcript `agents` carry identity only, so
  `SubagentRosterEntry` defaults everything else and the phase is `Unknown`: the
  entries create the child records live ops accumulate into; ticket 182 fills the
  rest from `task` frames) and mines the messages page + the running turn's tool
  frames for `Agent` calls (`applyAgentCall` — chat-independent, so the page-on-top
  restore recovers prompts/links too), then hands the chat-side `Bundle` to the
  calling ChatViewModel: `applyColdStart` replaces history (live tail cleared) and
  seeds the running turn's steps/frames as ordinary live rows
  (`ChatTimeline.seedRunningTurn` — consecutive text/thinking frames share a
  bubble, a tool frame breaks it, settled tools carry their output; what history
  already committed is pruned by the keep-live merge), so the replayed/live ops
  after the watermark extend exactly those rows (no gap, no duplicate: the fold
  knows the seeded frame texts and turn states). The working state is the running
  turn or an active queue prompt; the queued prompts render as "Queued #N" bubbles
  through the 154 echo registry (`seedQueuedPrompts` — the turn-start splice heals
  them in place); the transcript's pending approval/question interactions replace
  the cards' queues (approval request camelCase, question request the snake_case
  question payload; card id = the interaction id), each guarded against a live
  event overtaking the fetch. A turn event landing mid-fetch (a resync with the
  socket open) keeps the live tail as-is. The bound model/effort, permission
  mode, busy state, ring and todo list are not part of the cold start: the
  subscribe's `agent`/`usage` frames seed them (ticket 177), and its `todo` frame
  the widget (ticket 178).
  The fetch is cached per session only until the session is LEFT:
  `SubagentStreamStore.release` calls `TranscriptColdStart.forget`, so the next entry
  re-fetches (one cheap round per entry — no process-lifetime cache of mutable
  server state) while chat → page → chat costs zero fetches. A chat joining someone
  else's fetch (`AlreadyFresh` — the keep-alive restoring a page on top) takes a
  fresh `resync` of its own: the socket may have folded past that bundle. The 40932
  recovery and a stale journal re-run it too (`resync`); a failed fetch is the
  ordinary load-error state, never a fallback to any removed route.
  The within-process resume (048, rebuilt by 176) is the watermark replay: every
  gate reopen and reconnect re-subscribes with `transcript_since`, so a turn that
  ended while away arrives as its own `turn.upsert` (its refetch clears the ghost
  live rows). `verifyResume` (ON_START after the first, and every `Reconnected`)
  probes `GET .../transcript/ops?agent_id=main&since_seq=<watermark>`; a stale
  answer (`complete:false`, or a `latest_seq` behind the watermark — a restarted
  server's fresh journal) or a `TranscriptStale` event rebuilds the chat from a
  fresh cold start instead of trusting a partial replay. A failed probe proves
  nothing; the replay stays the resume.
  The transcript cold status reads the real turn states (`cancelled`/`failed`
  → Failed, 0.31.1) for the window the roster cannot cover.
- Sub-agent streams (045, state model reimplemented in 091): `SessionSocket` tags
  transcript events with the emitting agent (the ops batch's `agent_id` since 175 —
  the server auto-subscribes children; no second `subscribe_v2` is sent); sub-agent-scoped events route to
  `SubagentStreamStore` (ui/chat, session-scoped singleton keyed by session, one
  `SubagentRecord` per sub-agent — metadata (identity, task prompt, row link,
  status) are first-class FIELDS, never rows in the replaceable timeline, so the
  transcript fetch cannot wipe them; delta coalescing via the `DeltaCoalescer`
  shared with ChatViewModel, ticket 065). Every source reaches the record through
  one funnel method per source kind, writing only the fields it is authoritative
  for (precedence high→low: live lifecycle pins `status` → fresh roster → result
  text → transcript turn states; identity/prompt/link first-writer-wins): live
  frames via `apply` (the FIRST turn's first `ContextSpliced` user message — an
  `undo` marker splice since 175 — is the task prompt — lifted into `record.prompt`, never a timeline row; later splices
  stay user bubbles), the session roster via `applyRoster` (re-applicable on every
  entry — a fresh roster heals non-live-pinned statuses), `Agent` calls from any
  carrier (history rows, the cold start's messages page, the running transcript
  turn's tool frames) via `applyAgentCall`,
  transcript pages via `applyTranscriptPage`/`prependTranscriptPage`. Ticket 135
  adds the member `Cancelled` status with three carriers — the member's ended
  `task` frame with `outcome:"killed"` (ticket 179: a subagent-kind task whose
  `child_agent_id` names the record, pinning like any live lifecycle write),
  the schema-level `subagent.cancelled` frame, and the roster's `cancelled`
  phase — plus the member `model`/`thinkingEffort` pair (the spawned frame
  overwrites when non-blank, the roster and the spawn task frame blank-fill).
  Since 179 every subagent-kind `task.list`/`task` entry (main-owned spawns
  above all, foreground ones included) merges into the EXISTING record its
  `child_agent_id` names (`mergeSpawnTask`, no record creation — the rest of
  the task-frame derivation is ticket 182's): the task id (first writer wins —
  the `task.stop`/`task.background` handle, so foreground members are now
  stoppable/detachable), `detached:true` → `runInBackground`, the model/effort
  blank-fill and the killed pin. The
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
  cancelled FOREGROUND member gets no `subagent.completed`/`failed` frame
  (only a deliberate stop's killed task frame), so the parse is its terminal
  carrier; the new `latestToolCallId` record field (last writer wins,
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
  no ghost), tapping it disables the button and sends the member's `task.stop`
  through the same `ChatRepository.sendTaskControl` the jobs sheet uses (an
  already-ended task just replies with its state; an error reply or a dropped
  socket re-enables and snackbars via the screen's own Effect channel) — no
  optimistic flip, the row settles from the wire (the killed ended state's 135
  pin, or the settled call's result per the refined tier above). The store is fed by
  exactly ONE session-socket collection at a time (064): the chat's while its
  socket gate holds the session (ChatViewModel signals
  chatCollectionStarted/Stopped), otherwise the store's own keep-alive
  collection while a sub-agent page is open (SubagentViewModel registers via
  pageOpened/pageClosed; the job screen registers the same page count so the
  socket — and with it the live task frames — survives watching a job) — the keep-alive lives in the store because after
  process death with a page on top the chat ViewModel is never recreated. Two
  concurrent collections would double-feed on replay, so the keep-alive never
  runs while a chat collection does; never open a page-owned socket. The
  read-only `SubagentScreen` reuses `MessageList`/`ToolRows`; linked `Agent`
  rows carry `subagentId` — linked rows never expand (the page has all the
  details); tapping the row or its trailing open button navigates to the page.
  The page's one mutation is ticket 138's detach: the top bar's overflow shows
  "To background" (web-parity label) while the record is
  `SubagentState.detachable` (a running foreground member with a known
  `taskId` — `stoppable && !runInBackground`), sending `task.background`
  through `ChatRepository.sendTaskControl` (ticket 179; an error reply —
  `40933` for a foreground-only task such as a swarm member — snackbars as a
  failure; the in-flight latch mirrors 137's stop idiom). The code-0 reply's
  `detached:true` state reaches the store as a main-owned `TaskUpdated` before
  the call returns (routed there from `ChatViewModel`, or by the keep-alive)
  and flips the record's `runInBackground` — no duplicate record, the
  main-owned job row itself is JobsStore's — which removes the affordance.
  The record also owns the page's dock data: the sub-agent's own todo list
  (`SubagentRecord.todos`, replaced by the child's agent-tagged `todo` frame —
  ticket 178; outside the timeline, so no transcript replace touches it) and its
  own background tasks (the child's own `task.list`/`task` frames, stamped with
  its id: the list merges like JobsStore's, frames upsert into
  `SubagentRecord.jobs` via the shared `TaskState.toBackgroundJob()` mapping;
  on a cold open the transcript pages' `task` turn origins heal the terminal
  status into the record's jobs — ticket 099, a live ended frame stays
  authoritative over the origin); the page mounts the
  shared `TodoJobsDock` as its bottom bar, with the job screen wired
  (store-backed mode, ticket 098) but no cancel (ticket 182's).
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
