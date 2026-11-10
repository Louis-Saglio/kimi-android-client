# Kimi Code server wire protocol (core loop)

Wire protocol of the HTTP+WebSocket server embedded in the `kimi` binary, as used
by Kimi Code Web. Documented and verified against the live server on 2026-11-10
(server version `2.5.3`, `"backend": "v2"`, WS `protocol_version: 2`).

Scope: core loop + user interaction — auth check, sessions, messages, prompts,
steer, interactions (approvals and questions), abort, WebSocket events, file attachments,
tool-result media, sub-agents (foreground lifecycle + background task store),
todos, the tools catalog, the client-configs proxy and the session-rating
rules, plus
the workspace list used by the mobile create-session flow. Explicitly skipped:
`fs:*`, terminals, cron, goals' write path, providers, skills, plugins and
remote environments.

Every core-loop endpoint below was executed against the live server unless
its paragraph says **source-derived**. The exact commands are shown with each
section. Secrets are redacted (`<token>`).

**Protocol history:** this document previously described server `2.5.2`
(verified 2026-11-06; see git history), `2.5.1`
(verified 2026-11-05; see git history), `2.5.0`
(verified 2026-11-04; see git history), `2.4.1`
(verified 2026-10-30; see git history), `2.4.0`
(verified 2026-10-28; see git history), `2.3.3`
(verified 2026-10-28; see git history), `2.3.1`
(verified 2026-10-23; see git history), `2.3.0`
(verified 2026-10-22; see git history), `2.2.0`
(verified 2026-10-14; see git history), `2.1.1`
(verified 2026-09-24; see git history), `2.0.2`
(verified 2026-09-19; see git history), `2.0.1`
(verified 2026-09-18; see git history), `2.0.0`
(verified 2026-09-17; see git history), `0.43.1`
(verified 2026-09-15; see git history), `0.43.0`
(verified 2026-09-14; see git history), `0.42.0`
(verified 2026-09-09; see git history), `0.41.0`
(verified 2026-09-04; see git history), `0.40.1`
(verified 2026-09-02; see git history), `0.39.1`
(verified 2026-08-28; see git history), `0.39.0`
(verified 2026-08-27; see git history), `0.38.0`
(verified 2026-08-20; see git history), `0.37.2`
(verified 2026-08-18; see git history), `0.36.1`
(verified 2026-08-14; see git history), `0.36.0`
(verified 2026-08-13; see git history), `0.35.0`
(verified 2026-08-12), `0.34.0`
(verified 2026-08-06), `0.33.0`
(verified 2026-08-05), `0.32.0`
(verified 2026-08-05), `0.31.1`
(verified 2026-07-31), `0.31.0`
(verified 2026-07-30), `0.29.2`
(verified 2026-07-27/28), `0.29.1` before that, `0.29.0`
before that, `0.28.1` before
that, `0.27.0` before that, and `0.23.3` before that.

The drift from 2.5.2 to 2.5.3 is **small and steer-shaped: one new REST route
(`POST /api/v1/sessions/{id}/steer`), a new steer execution model that
interrupts the active LLM step instead of waiting for it, append-only steer
history, and a `reset` transcript op that now reaches the wire — no route,
frame type or field was removed, and nothing the Android app parses breaks**.
The nightly job's `pending-changelog.md` covered 2.5.2→2.5.3 (5 commits).
The protocol-bearing commit is `e1912c80` ("improve same-turn steer
interactions"): the new direct-steer route in `routes/prompts.ts`, the
`steerIfActive` path through `agent-core-v2`'s loop, turn and prompt-channel
state machines, and the projection change in `agent/loop/projection.ts`; the
API-surface snapshot gains exactly that route. `1b5359d0` ("prune divergent
live transcript steps") makes the transcript service emit a whole-agent
`reset` op when the cold history omits live steps or frames. `c50a55bf` is
Windows-only, `e3c3a120` the web-dist sync and `5af9942e` the release commit.
Live verification used server `2.5.3`, started `2026-11-10T19:23:55.224Z`.

- **New route `POST /api/v1/sessions/{id}/steer`** (commit `e1912c80`;
  verified live 2026-11-10 — see Steer → Steer message content): the body is
  the prompt-submission schema (`content`, `agent_id`, `prompt_id`,
  `skills`, `model`, `thinking`, `permission_mode`, `disabled_tools`, …) and
  the success data is the same `{prompt_id, user_message_id, status,
  content, created_at}`. On an idle session it behaves exactly like the
  prompt POST (fresh turn, ACK `status:"running"`); on a busy session it
  queues and steers in the same request, with no separate submit-then-steer
  round trip. Errors are the prompt POST's (`40401` unknown session with
  `stack`, `40001` invalid/empty `content`, idempotency `40927`, media
  `40407`, the `40931`/`40932` session-conflict family). The ACK reads
  `status:"running"` even when the prompt is actually queued and steered —
  the transcript's `prompt.upsert` is the truth.
- **Steer now interrupts the active LLM request** (same commit; verified
  live). A text/thinking stream is cut at the next drain boundary: the
  current step's `step.upsert` becomes `state:"interrupted"` with the new
  **`endReason:"steered"`**, the steered message lands as a user-role text
  frame in a **new step of the same turn** (`promptIds`, `origin.kind:
  "user"`), the turn stays `running` and later completes normally. A steer
  that arrives while a tool call is streaming is held until that tool
  finishes (the `turn.steer` gate refuses a streaming tool call), then the
  same same-turn continuation happens. There is no `turn.ended` on this
  wire, so no `reason:"steered"` frame; the turn header never leaves
  `running` until its final completion.
- **Steer history is append-only** (same commit; verified live). The message
  projection no longer merges an in-turn user message into the preceding
  user message (`canMergeUserMessage` excludes `origin.inTurn`), so
  `GET .../messages` lists the steered message as its own `role:"user"`
  entry with its own id, instead of folding its text into the prompt
  bubble. The transcript already carried it as a separate frame; only
  history changes.
- **`transcript.ops` carries `reset` ops now** (commit `1b5359d0`; verified
  live). When an ended turn's live transcript has steps or frames the cold
  history omitted (routinely: a steered step, an empty thinking frame), the
  transcript service replaces the agent's transcript with one op:
  `{"op":"reset","agentId":"main","snapshot":{"items":[<full turns with
  steps and frames>],…}}`, followed by the usual heal ops. The op was
  already in `packages/transcript`'s union; 2.5.3 is the first release
  observed emitting it (on every completed turn of this run, plain and
  steered, main and child agents).
- **`steerIfActive` no longer requires a tracked prompt** (source-derived
  from the same commit): the skill-activation and direct-steer paths steer
  whenever a turn is active (`activeTurnId !== undefined`), so an untracked
  turn (background continuation, skill activation mid-turn) accepts a steer
  too. Live, a skill activation during a streaming step produced an
  internal empty-content prompt entity with `status:"completed"` and
  `steeredAt` next to the active prompt's `steeredAt`.
- **App impact: none breaks — one improvement ticket.** The app's
  two-request steer (`POST /prompts` then `prompts:steer`) still works, but
  it can lose the steer to a race (the prompt stays queued and runs as a
  later turn while the UI reports failure) and needs the extra round trip;
  ticket 185 moves it onto the direct route. The `reset` op is dropped by
  `TranscriptFold` (unknown op) — harmless because a turn's ops are pruned
  on `turn.upsert` completion and the chat refetches history on turn end;
  `endReason:"steered"` is not read by the app (it only maps the
  `interrupted` state). No breakage ticket.

Everything else re-verified unchanged live on 2.5.3 (see the verification
log): auth 401s, `/meta` (5 flags, 17 features, same capabilities), config,
models (12), tools (36 = 27 builtins + 9 MCP), sessions create/get/list/
profile/fork (`turn_index`)/reload/takeover/archive/restore, the v2 session
table, messages (newest-first; the image rewrite to `session_media` +
`name`), the full prompt round-trip, the `prompts:steer` queued flow, abort
(interrupted step with `endReason:"aborted"`, cancelled turn, the
`interruption` marker plus the undo reminder, aborted prompt), both
compaction bodies (the `started` marker echoes `instruction` when one was
sent; `tokensAfter` matches the following `usage` frame; both `40910`s with
`stack`), questions (answer → `answered`, re-answer `40902`, dismiss →
`dismissed`, unknown `40404`, the removed `/questions` route 404s),
approvals (approve → `approved`, re-answer `40902`), foreground/background
sub-agents (`task` frames with `kind:"subagent"`, `child_agent_id`,
`detached` true/false; child `transcript.reset` + `agent` frames; REST
`/tasks` 404), the `TodoList` write and read contracts
(`display:{"kind":"todo_list"}`), files (upload/download/`Range` 206/
delete → `40407`; session media 200 and surviving the upload's delete), the
`task.watch`/`task.stop`/`task.unwatch` frames, WS
hello/`subscribe_v2`/ack, unknown-session `not_found`, malformed `code:1`,
`transcript_since` resume, `unsubscribe_v2`, search (`50001`, still
disabled) and session rating (`{"appearance":null}`).

The drift from 2.5.1 to 2.5.2 is **engine-internal — no route, WebSocket frame
type, request field, response shape or enum changed** (verified live
2026-11-06). The nightly job's `pending-changelog.md` covered 2.5.1→2.5.2
(3 commits): `7b906ea3` ("defer same-name tool updates") is the only code
change and touches `packages/agent-core-v2` only (MCP service, tool registry,
tool-select, tool contract); `packages/kap-server` and the wire definitions are
untouched, `ef10107d` is the web-dist sync and `1c142a00` the release commit.
Live verification used server `2.5.2`, started `2026-11-06T19:23:39.783Z`.

- **Same-name MCP tool updates are pinned for the running session** (commit
  `7b906ea3`; verified live 2026-11-06). When an MCP server re-registers a
  tool under a name the session already publishes, the active schema and
  executor stay in place for that session; instead of a remove+add the model
  gets a reminder, once per service instance: `Tool "<name>" has an update,
  but the update is not applied in this session. Continue using the currently
  registered version.` The reminder is a user-role injection, so if it fires
  it reaches the wire as the usual `<system-reminder>` message with
  `metadata.origin:{"kind":"injection","variant":"tool_update_pending"}`
  (source-derived from `features/reminder/reminderService.ts`; not triggered
  here — this server's MCP tool sets do not change mid-session). The tools
  catalog stays at **36 entries — 27 builtins + 9 MCP**. The app strips
  `<system-reminder>` envelopes from user text, so the new variant would
  render exactly like the existing date/permission reminders — additive and
  harmless.
- **Content drift, not shape:** `/api/v1/meta` still reports 5 flags and 17
  features; `GET /api/v1/config` still reads
  `default_model:"deepseek/deepseek-v4-flash"`, `thinking.effort:"max"`,
  `default_permission_mode:"auto"` and the same 3 providers; the models
  catalog still has **12 entries**; the tools catalog reads **36 entries —
  27 builtins + 9 MCP** (`ReadMediaFile` present). The managed `kimi-code/k3`
  alias still fails after the POST with `provider.auth_error` (step
  `interrupted`/`endReason:"error"`, turn `failed` with the subscription
  message); `Kimi Platform/kimi-k3` completed a turn live.
- **App impact: none — no breakage ticket, no improvement ticket.** Every
  shape the app parses is byte-identical to 2.5.1; the outstanding 2.5.0
  migration tickets (171–184) are unchanged.

Everything else re-verified unchanged live on 2.5.2 (see the verification
log): auth 401s, `/meta`, config, models, tools, session create/get/list/
profile, the v2 session table, fork (`turn_index` accepted, and the
`event.session.created` broadcast), archive/restore, goal and warnings,
messages (newest-first; image attachments keep the `session_media` + `name`
rewrite), the full prompt round-trip (prompt.upsert → turn.upsert → undo
marker → step.upsert → frame.upsert/append → completed step/turn/prompt; a
synchronous Bash call emits `task` running/ended frames with
`detached:false`), steer (queued → completed with `steeredAt`; the steered
text as a user-role frame with `promptIds` in the same turn), abort
(interrupted step, cancelled turn, interruption marker, aborted prompt; idle
`:abort` → `{"aborted":true}`), both compaction bodies (`{}` and
`{"instruction":…}`, instruction echoed in the `started` marker; `tokensAfter`
equal to the following `usage` frame; both `40910`s with `stack`), questions
(pending/ended `interaction` frames, `interaction.upsert`, answer →
`outcome:"answered"`, re-answer `40902`, dismiss → `outcome:"dismissed"`,
unknown → `40404`), approvals (manual Bash, pending/ended `interaction`
frames; `40902` on a re-answer), foreground and background sub-agents
(`transcript.reset` and `agent` frames for `agent-0`/`agent-1`, `task` frames
with `kind:"subagent"`, `child_agent_id` and `detached` true/false; no
`subagent.*` frames; REST `/tasks` → 404), the `TodoList` write contract and
read query (`input:{}` → `Current todo list:` output,
`display:{"kind":"todo_list","items":[…]}`), files (upload/download/`Range`
206/delete → `40407`; session media 200 and surviving the upload's delete),
the `task.watch`/`task.stop` control frames (byte-offset `task.output`,
`response` carrying the task), WS hello/`subscribe_v2`/ack, unknown-session
`not_found`, malformed `code:1`, `transcript_since` resume (from seq 197 the
server sent batches 198–200 then the ack, with no `transcript.reset`; a
future cursor falls back to `transcript.reset`), `unsubscribe_v2`, search
still `50001`, transcript `/ops` + `/user-messages` + `/plan`, and session
rating (`{"appearance":null}`).

The drift from 2.5.0 to 2.5.1 is **small and additive on the core loop: media
tool results carry structured `session_media` parts again, and session lists
hide sessions whose workspace was deleted; no route, frame type or field was
removed**. The nightly job's `pending-changelog.md` covered 2.5.0→2.5.1 (5
commits). The protocol-bearing commits are `6dad2c8d` ("preserve session media
in tool results" — `services/messages/messageProjection.ts` and the transcript
cold fold in `packages/transcript/src/history/groupTurns.ts`) and `b4e4a84f`
("hide sessions of deleted workspaces" — `routes/sessions.ts`,
`routes/v2/sessions.ts`, new `services/workspaceVisibility.ts`); `2af56d29`
is Kimi-provider request serialization (omit empty system tool content) and
`2391a77f`/`1b02030d` are the web bundle and the release commit. Live
verification used server `2.5.1`, started `2026-11-05T19:23:54.678Z`.

- **Media tool results are structured `session_media` parts again** (commit
  `6dad2c8d`; verified live 2026-11-05). The 2.5.0 document recorded media
  tool results as hidden behind a `notice` frame; 2.5.1 projects daemon file
  references (`kimi-file://…`) to the same `session_media` shape user images
  use, inside the tool frame's **`output` array** — identical on the live
  `transcript.ops` stream, the warm REST transcript and
  `GET .../messages` history (cold-fold parity is source-derived from the
  same commit's `groupTurns.ts` change). Verbatim `ReadMediaFile` result for a 1×1
  png (tool frame, trimmed):

  ```json
  {"kind":"tool","frameId":"t1.1.ReadMediaFile_0_731303c4",
   "toolCallId":"ReadMediaFile_0_731303c4","name":"ReadMediaFile","state":"done",
   "input":{"path":"/home/ubuntu/spike/one.png"},
   "output":[
     {"type":"text","text":"<image path=\"/home/ubuntu/spike/one.png\">"},
     {"type":"image","source":{"kind":"session_media","file_id":"f_13df9359…"}},
     {"type":"text","text":"</image>"}]}
  ```

  The history projection carries the same array as
  `{"type":"tool_result","tool_call_id":"ReadMediaFile_0_731303c4","output":[…]}`.
  `GET /api/v1/sessions/{id}/media/{file_id}` serves the bytes (verified live,
  200). Only daemon refs are structured: base64/data URLs, `https` media and
  audio stay on the text path (`audio_url` is never mapped, and an audio-only
  cold result stays text). The projection emits `name` when the daemon ref
  carries one; `ReadMediaFile` here supplied none. The Android app decodes
  tool `output` as `JsonElement` and pretty-prints arrays, so nothing breaks;
  it can now show the image instead of JSON (ticket 184).
- **Session lists hide sessions of deleted workspaces** (commit `b4e4a84f`;
  verified live 2026-11-05). `GET /api/v1/sessions` and
  `GET /api/v2/sessions` — flat, `view=by_workspace` and the
  `fields=id,archived` projection — skip every session whose `workspace_id`
  is not a registered workspace or one of its aliases. A deleted workspace's
  sessions disappear from the list, its group disappears from
  `view=by_workspace`, and `total`/`has_more` count only the visible set; the
  session itself stays readable: `GET /api/v1/sessions/{id}` still answers
  `code:0` with its `metadata.cwd` (verified: create a workspace + session,
  delete the workspace, list → 0 occurrences, get → normal session object).
  List filtering only — no shape change. The app never creates or deletes
  workspaces, so it is unaffected.
- **Content drift, not shape:** `/api/v1/meta` still reports 5 flags and 17
  features; `GET /api/v1/config` still reads
  `default_model:"deepseek/deepseek-v4-flash"`, `thinking.effort:"max"`,
  `default_permission_mode:"auto"` and the same 3 providers; the models
  catalog still has **12 entries**; the tools catalog reads **36 entries —
  27 builtins + 9 MCP** on the checked sessions (`ReadMediaFile` present).
  The Kimi provider is usable through its platform alias:
  `Kimi Platform/kimi-k3` completed turns live. The managed `kimi-code/k3`
  alias was re-probed and still fails after the POST with
  `provider.auth_error` (step `interrupted`/`endReason:"error"`, turn
  `failed`, prompt `failed`) — a subscription state, not a protocol change.
- **App impact: none — no breakage ticket.** The tool-output arrays decode
  into the app's existing `JsonElement` fields, the session-list filter is
  server-side, and no event type or route changed. One improvement ticket:
  **184** renders `ReadMediaFile` results instead of pretty-printing them.

Everything else re-verified unchanged live on 2.5.1: auth, `/meta`, config,
models, tools, session create/get/list/profile, the v2 session table,
messages (newest-first; image attachments keep the `session_media` + `name`
rewrite), the full prompt round-trip (prompt.upsert → turn.upsert → undo
marker → step.upsert → frame.upsert/append → completed step/turn/prompt; a
synchronous Bash call emits `task` running/ended frames with
`detached:false`), steer (queued → completed with `steeredAt`; the steered
text as a user-role frame with `promptIds` in the same turn), abort
(interrupted step, cancelled turn, interruption marker, aborted prompt; idle
`:abort` → `{"aborted":true}`), both compaction bodies (`{}` and
`{"instruction":…}`, instruction echoed in the `started` marker; the empty-
and busy-session `40910`s with `stack`; `tokensAfter` equal to the following
`usage` frame), questions (pending/ended `interaction` frames,
`interaction.upsert`, answer → `outcome:"answered"`, re-answer `40902`,
dismiss → `outcome:"dismissed"`), approvals (manual Bash, pending/ended
`interaction` frames), foreground and background sub-agents (`transcript.reset`
and `agent` frames for `agent-0`/`agent-1`, `task` frames with
`kind:"subagent"`, `child_agent_id` and `detached` true/false; no
`subagent.*` frames), the `TodoList` write contract and read query
(`input:{}` → `Current todo list:` output), files
(upload/download/`Range`/delete → `40407`; session media 200), WS
hello/`subscribe_v2`/ack, the unknown-session `not_found` and malformed
`code:1` acks, and `transcript_since` resume (from seq 55 the server sent
batches 56–60 then the ack, with no `transcript.reset`).

The drift from 2.4.1 to 2.5.0 is **a breaking rewrite of the WebSocket layer
plus the removal of three core REST routes — the session stream the Android app
is built on no longer exists in its old form**. The nightly job's
`pending-changelog.md` covered 2.4.1→2.5.0 (69 commits). The protocol-bearing
commits are `8535bd79` (`subscribe_v2` becomes the only session subscription;
the raw `subscribe`/`unsubscribe` path, the seq/epoch journal, cursor replay,
`resync_required`, agent filters and legacy-event suppression are deleted),
`ebc2a9f9` (the REST task API is dropped; `task.list`/`task` frames and the
`task.watch`/`task.stop`/`task.background` control frames replace it),
`011b1d41` (`agent`/`usage`/`goal`/`todo` frames; `GET /status` removed) and
`e24e1e19` (fork by `turn_index`). The rest is engine, TUI, plugin-marketplace,
remote-environment and web-bundle work. Live verification used server `2.5.0`,
started `2026-11-04T19:23:46.695Z`.

- **One session subscription: `subscribe_v2`.** The old
  `{"type":"subscribe","payload":{"session_ids":[…]}}` frame is now **silently
  ignored** — no ack, no error, no events (verified live: a socket that sent it
  received nothing while the same session ran a turn). `subscribe_v2` takes ONE
  session and a per-agent transcript grade:
  `{"type":"subscribe_v2","id":"c1","payload":{"session_id":"session_…",
  "transcript":{"*":"delta"},"transcript_since":{"*":30}}}`. The ack is
  `{"type":"ack","id":"c1","code":0,"msg":"success","payload":{"accepted":
  ["session_…"],"not_found":[]}}`; an unknown session answers `code:0` with it
  in `not_found`, and a malformed payload answers `code:1` `"invalid
  subscribe_v2 payload"` (not `40001`). There is no multi-session subscribe:
  one frame per session. `unsubscribe_v2` takes `{session_id, agent_ids?}` and
  acks with the session id in `accepted`.
- **The seq/epoch event journal is gone.** No session event carries `seq` or
  `epoch` any more, `resync_required` no longer exists, and there is no
  server-side replay of turn events. Resume state is the per-agent
  **`transcript_since`** map (see Transcript protocol); omitted, the server
  sends a fresh `transcript.reset`. Global events were never replayed.
- **The per-turn event family is gone.** `turn.started`, `turn.ended`,
  `assistant.delta`, `thinking.delta`, `tool.call.*`, `tool.progress`,
  `tool.result`, `context.spliced`, `prompt.submitted`/`queued`/`started`/
  `completed`/`aborted`, `agent.created`/`disposed`, all `subagent.*`,
  `task.started`/`terminated`, `background.task.*`, `compaction.*`,
  `event.approval.*`, `event.question.*` and `permission.approval.*` were
  **all removed from the wire** (zero such frames in every 2.5.0 capture).
  Their content now rides the transcript op stream (text/thinking/tool frames,
  turn/step state, markers) and the new flat state frames below.
- **New flat session frames** (sent only to `subscribe_v2` subscribers of that
  session, one per agent): `task.list`, `task`, `agent`, `usage`, `goal`,
  `todo`, `interaction`, plus `transcript.reset`/`transcript.ops` and the
  watch-only `task.output`. See WebSocket → Session frames for shapes and
  verbatim captures. A newly spawned sub-agent announces itself on the same
  connection with its own `transcript.reset` (`agent_id:"agent-0"`, `seq:0`),
  `agent` and, when applicable, the other state frames; no `subagent.*`
  event marks the spawn — the main agent's `task` frame with
  `child_agent_id` does.
- **Two session events are now app-level pushes to every connection**:
  `session.meta.updated` and `event.session.work_changed` arrive without any
  subscription (verified live on a socket that sent nothing but `pong`). The
  other global events (`event.session.created|archived|deleted`,
  `event.workspace.*`, `event.config.*`, `event.model_catalog.*`) keep their
  old payloads but lose `seq`/`epoch`. This is why the app's session-list
  socket still works while its chat socket sees nothing.
- **REST removals.** `GET /api/v1/sessions/{id}/status`,
  `GET /api/v1/sessions/{id}/snapshot` and every `/api/v1/sessions/{id}/tasks…`
  route are plain Fastify 404s now (verified live, GET and POST). The snapshot's
  data moved to `GET .../transcript` (turns, interactions, attachments,
  prompts, agents roster, `seq`) plus the flat frames; `/status` moved to the
  `agent` + `usage` frames. The `/approvals` and `/questions` routes were
  already gone in 2.4.0 and stay gone; `POST .../{tail}` catch-all answers
  `40001 "unsupported action: …"` for those words instead of 404.
- **Compaction is a transcript marker now.** No `compaction.started`/`.completed`
  frames. The `transcript.ops` stream carries `marker.upsert` items with
  `marker:"compaction"`: `{phase:"started",agentId,trigger}` and
  `{phase:"completed",agentId,result:{summary,compactedCount,tokensBefore,
  tokensAfter,keptUserMessageCount}}`, plus an `undo` marker carrying the
  summary message (`origin.kind:"compaction_summary"`) and the continuation
  injection. REST `:compact` itself is unchanged, including both `40910`s.
- **Steer, abort and prompts are prompt/turn ops.** A prompt is a
  `prompt.upsert` entity (`status` `running`/`queued`/`completed`/`aborted`/
  `blocked`/`failed`, optional `steeredAt`); a steer flips the queued prompt to
  `completed` with `steeredAt`, marks the active prompt `steeredAt`, and the
  steered text lands as a user-role text frame carrying `promptIds`. An abort
  sets the step `state:"interrupted"`, the turn `state:"cancelled"`, the prompt
  `status:"aborted"`, and splices the interruption reminder via an `undo`
  marker. REST `:abort`, `:steer` and the prompt POST are unchanged.
- **Interactions survive, but only on a subscription.** The flat `interaction`
  frame is unchanged in shape and still never journaled; it is now delivered
  only to connections that subscribed with `subscribe_v2`, and the pending
  list is re-sent TWICE after the ack: first inside
  `transcript.reset.snapshot.interactions`, then as standalone `interaction`
  frames. The transcript also carries an `interaction.upsert` op (full state)
  and `GET .../transcript` returns `interactions` + `pending_interactions`
  (replacing the snapshot's `pending_approvals`/`pending_questions`).
- **Todos are a real frame now.** `todo` carries the agent's full list
  (`items:[{title,status}]`) and fires on every change, including undo. The
  `TodoList` tool contract (`{"todos":[{status,title}]}` full-list writes)
  is otherwise unchanged and its transcript tool frame now also carries
  `display:{"kind":"todo_list","items":[…]}`.
- **Tasks: REST gone, frames in.** `task.list` (all running tasks when an
  agent joins) and `task` (full state on every change) use
  `{task_id,kind:"shell"|"subagent"|"other",status:"running"|"ended",
  outcome?,phase?,detached,description?,child_agent_id?,
  parent_tool_call_id?,swarm_index?,command?,started_at?,ended_at?,
  result_summary?,error?,state_reason?,model?,thinking?}`. `task.watch`
  streams `task.output` chunks by UTF-8 byte offset; `task.stop` and
  `task.background` replace REST `:cancel` and `:detach`. All four are flat
  control frames (fields beside `type`, `request_id` instead of `id`) with a
  `{type:"response",request_id,code,msg?,task?}` reply.
- **Global search is off by default** (commit `be89803b`):
  `POST /api/v1/search` now answers `50001 "global search is disabled; set
  [database] global_search = true or KIMI_CODE_GLOBAL_SEARCH=1 to enable it"`.
  The Android search screen surfaces that message as a full-screen error.
- **Fork by turn.** `POST ...:fork` accepts optional `turn_index` (a completed
  engine turn id, gaps allowed) and now **broadcasts `event.session.created`**
  for the fork — a fork made by another client appears live in the session
  list (the old doc said forks were dropped server-side).
- **Prompt history is awaited before acceptance** (commit `b5f14f7e`): a POST
  against a cold session waits for the agent's persisted history to load
  before enqueueing; the HTTP response simply arrives a little later.
- **Provider state, not protocol:** a `kimi-code/k3` prompt is still accepted
  by the POST (`code:0`, `status:"running"`) and fails ~2 s later with
  `provider.auth_error` ("Your current subscription does not have access to
  Kimi Code right now…"). In the 2.5.0 representation there is **no standalone
  `error` frame and no `turn.ended`**: the step's `step.upsert` reads
  `state:"interrupted"`, `endReason:"error"`, `endMessage:"[provider.auth_error] …"`;
  the turn's `turn.upsert` reads `state:"failed"` with an `error` message; the
  prompt's `prompt.upsert` reads `status:"failed"`. The checks in this run
  used `deepseek/deepseek-v4-flash`.
- **Content drift, not shape:** tools catalog reads **36 entries — 27 builtins
  + 9 MCP** (`ReadMediaFile` back); models catalog **12 entries**; `/meta` still
  5 flags and 17 features; `GET /api/v1/config` still
  `default_model:"deepseek/deepseek-v4-flash"`, `thinking.effort:"max"`,
  `default_permission_mode:"auto"`.
- **App impact: the chat is completely cut off.** `SessionSocket` sends the
  now-ignored `subscribe`, so no transcript, prompt, tool, interaction, task,
  compaction or sub-agent frame reaches the app; `SnapshotSync` and
  `ChatViewModel` call `/snapshot` and `/status`, both 404; the jobs store and
  its detail screen call `/tasks`, 404; the approval and question routes were
  already dead (tickets 171/172); search now fails by default. The session
  list's socket still receives the global `session.meta.updated` /
  `event.session.work_changed` / lifecycle events, so the list screen keeps
  updating. Tickets 175–183 cover the migration; 171/172/173 are updated for
  the removed snapshot.

The drift from 2.4.0 to 2.4.1 is **engine-only: no route, WebSocket event,
request field, response shape or enum changed** (historical note: 2.5.0 below
removed the `/status`, `/snapshot` and `/tasks` routes this section still
mentions as live; treat them as 2.4.1-era).
request field, response shape or enum changed**. The nightly job's
`pending-changelog.md` covered 2.4.0→2.4.1 (4 commits, 140 changed files);
none touches `packages/kap-server/src` or the wire definitions — the changes
are `packages/agent-core-v2` tool/prompt code and the prebuilt web bundle.
Live verification used server `2.4.1`, started `2026-10-30T19:23:52.832Z`.

- **Read now caps one call at about 25k tokens** (commit `9140ea0d`:
  `DEFAULT_MAX_CHARS_LIMIT` 500k → 100k; configurable with `[read]
  max_chars`): a 210,000-character file read on 2.4.1 returned **99,613
  characters** ending mid-file, where 2.4.0 would return the whole file. The
  truncation notice (`Next Read: …`) rides the model-facing `note`, **not the
  wire** — `tool.result` carried only `output`, with no `note` field and no
  `<system>` block, so the app's file-content screen shows the shorter
  extract unchanged. Content drift, not shape.
- **Edit/Write results gained a model-facing `note`** (same commit family,
  `editTool.ts`/`writeTool.ts`): `<system>The change is applied; there is no
  need to Read the file back to check it.</system>`. Live capture confirms
  the wire `tool.result` output is unchanged (`Replaced 1 occurrence in …`,
  `Wrote 10 bytes to …`) and carries no `note` — engine→LLM only.
- **The compaction summary prefix** (see Compact) reads on both 2.4.0 and
  2.4.1: "Do not redo work it reports as done or re-run checks it reports as
  passed; check anything it marks as unverified before relying on it." The
  earlier quote in this document ("Treat it as notes, not proof…") was
  stale. The `result` block, splice message keys and
  `compaction_continuation` injection are unchanged; the app keys the
  summary divider on `origin.kind:"compaction_summary"`, so the wording is
  cosmetic.
- **Steer ordering nit** (observed 2026-10-30): `prompt.steered` →
  `turn.step.completed` → `context.spliced` (the steered message) →
  `turn.steer` → the next `turn.step.started` in the same turn. `turn.steer`
  can therefore arrive *after* the splice rather than before step
  completion; payload shapes are unchanged and `prompt.completed` still
  carries the original active prompt id.
- **`kimi-code/k3` still fails on this server's subscription, but not at the
  POST** (verified live 2026-10-30, same envelope on 2.4.0): the prompt POST
  answers `code:0` with `status:"running"`, then the turn fails ~4 s later —
  `turn.ended {"reason":"failed","error":{"code":"provider.auth_error",
  "message":"403 Your current subscription does not have access …"},
  "interruptReason":"error"}`, a standalone `error` frame with the same
  object, and `prompt.completed {"reason":"failed"}`.

Everything else re-verified unchanged live on 2.4.1: `/meta` (5 flags,
17 features, same capabilities), config (`default_model`
`deepseek/deepseek-v4-flash`, thinking `max`, permission `auto`, 3 providers,
`read:{}`, no `disable_feedback_survey`/`auto_session_title` keys), the
12-model catalog item shape, the tools catalog (empty before a session runs,
then 35 = 26 builtins + 9 MCP, `ReadMediaFile` absent), session
create/get/list/status/snapshot/messages, the v2 session table (flat and
`by_workspace`), workspaces, search, the full prompt round-trip
(`agent.created` when the agent is cold, then `prompt.submitted` →
`session.meta.updated` → `turn.started` → `event.session.work_changed` →
`context.spliced` → `prompt.started` → steps/deltas → `turn.ended` →
`prompt.completed`), the TodoList write contract, steer, abort (mid-approval
→ `outcome:"cancelled"`; idle `{"aborted":true}`), both compaction bodies and
both `40910`s, approvals and questions on the `interaction` frame + response
route, foreground/background sub-agents (`subagent.*`/`task.*`, REST
`/tasks` list/get/`:cancel`, the snapshot roster), files
(upload/download/`Range`/session media/delete → `40407`, prompt file/image
rewriting), and WS hello/subscribe/ack, cursor replay and future-cursor
`resync_required`. **App impact: none — no ticket.**

The drift from 2.3.3 to 2.4.0 is **breaking for the interaction surface and
additive elsewhere: approvals and questions moved onto a single flat
`interaction` WebSocket frame and a single response route, while both old
event families and all five old routes were removed; nothing else the app
parses changed**. The nightly job's `pending-changelog.md` covered
2.3.1→2.4.0 (49 commits); the post-2.3.3 slice is 10 commits, of which
exactly two touch `packages/kap-server`'s protocol: `461c6e92` (interaction
frames) and `78b72fe4` (`swarm_mode` applied on prompt submission). The rest
is engine/TUI/web-bundle/CI work. Live verification used server `2.4.0`,
started `2026-10-28T19:23:50.157Z`.

- **Approvals and questions now arrive as one flat `interaction` frame**
  (commit `461c6e92`; verified live 2026-10-28 — verbatim captures in
  Interactions). It carries no `seq`, `timestamp`, or `payload` wrapper and
  is **never journaled or replayed**: `{type:"interaction", session_id,
  agent_id, event_created_at, interaction_id, status:"pending"|"ended",
  outcome?, ended_at?, tool_call_id?, kind:"approval"|"question", request,
  response?}`. An approval's `request` is
  `{tool_name, action, tool_input_display?, expires_at}`; a question's is
  `{questions[]}` with the same item shape as before. The ended frame adds
  `outcome` (`approved`/`rejected`/`cancelled`, or
  `answered`/`dismissed`/`cancelled`) and an approval `response`
  (`{decision, scope?, feedback?, selected_label?}`); an answered question's
  `response` is `{answers:{<question text>: <flattened answer>}}`. The server
  **re-sends every pending interaction right after each accepted
  `client_hello` / `subscribe` / `subscribe_v2` ack**, so clients rebuild the
  pending list from the frames that follow the ack instead of polling.
- **The legacy v2 `permission.approval.requested` / `.resolved` frames are
  still delivered** (camelCase, unchanged; verified live) and still carry the
  same `approval_<uuid>` id. The `event.approval.*` / `event.question.*`
  families are **gone** — zero such frames occurred in this run's captures.
- **One response route replaced five** (same commit): `POST
  /api/v1/sessions/{id}/interactions/{interaction_id}` with
  `{kind:"approval", decision, scope?, feedback?, selected_label?}` or
  `{kind:"question", answers?|dismissed:true, method?, note?}`. Success is
  `{outcome, ended_at}`. `GET .../approvals`, `GET .../questions`,
  `POST .../approvals/{id}`, `POST .../questions/{id}` and
  `POST .../questions/{id}:dismiss` are **removed** (all answer plain Fastify
  HTTP 404s, verified live). Error codes: unknown id → **`40404`
  `interaction … not found`** (was `APPROVAL_NOT_FOUND`/`QUESTION_NOT_FOUND`);
  already ended → **`40902` `interaction … has ended`** (was
  `APPROVAL_ALREADY_RESOLVED`/`QUESTION_DISMISSED`); a `kind` mismatch or
  missing `kind` → `40001`. The old successful-dismiss `40909` is gone —
  dismissal is `code:0` with `outcome:"dismissed"`.
- **`swarm_mode` on the prompt POST is applied now** (commit `78b72fe4`;
  verified live): `{"swarm_mode":true}` enters swarm mode before the prompt is
  enqueued, `false` exits it, and `GET .../status` reflects the change
  (`swarm_mode:true`→`false` across the two probes). Up to 2.3.3 the field was
  accepted by the schema and silently ignored; `POST .../profile`'s
  `agent_config.swarm_mode` was, and remains, the other way to set it. The
  app has no swarm-mode control today (ticket 140 was closed when the field
  was dead) — ticket 174 revives that toggle on the now-live field.
- **Plan mode is user-only and every plan is reviewed** (commit `0325b755`,
  engine behavior; verified live): entering plan mode through
  `POST .../profile {"agent_config":{"plan_mode":true}}` and prompting an
  `ExitPlanMode` call produced an approval whose `tool_input_display` reads
  `{"kind":"plan_review","plan":"<markdown>","path":"<plan file>"}` (verbatim
  capture in Interactions). The agent can no longer enter plan mode by
  itself, and `auto` no longer approves plans silently.
- **Idle sessions are reclaimed** (commit `366fafbf`): a session idle for 30
  min unloads from memory and resumes transparently on the next REST/WS
  access; `KIMI_CODE_SESSION_IDLE_RECLAIM_MS` overrides the timeout. A cold
  session reports not-busy with no pending interaction (same rule the session
  object always had for unloaded sessions), so the wire is unchanged.
- **Question history maps engine cancellations to `cancelled`** (same
  interaction commit): the transcript interaction entity now ends a
  turn-cancelled question as `cancelled`, not `answered` (REST transcript
  only; the live frame for an abort was observed as `dismissed` — see
  Interactions).
- **Content drift, not shape:** the tools catalog reads **35 entries — 26
  builtins plus 9 MCP tools** (`ReadMediaFile` absent); the models catalog
  still has **12 entries**; `GET /api/v1/config` still reads
  `default_model:"deepseek/deepseek-v4-flash"`, `thinking.effort:"max"`,
  `default_permission_mode:"auto"`, and no `disable_feedback_survey` /
  `auto_session_title` keys. `/api/v1/meta` is unchanged apart from
  `server_version`/`started_at` (same 5 flags, same 17 features).
- **Provider state, not protocol:** a `kimi-code/k3` prompt still fails
  immediately with `provider.auth_error` / HTTP 403 ("Your current
  subscription does not have access to Kimi Code right now") — the same
  envelope documented on 2.3.3. The checks below used
  `deepseek/deepseek-v4-flash`.

Everything else re-verified unchanged live on 2.4.0: the session
create/get/list/status/snapshot shapes, `40401` envelopes, `:reload`,
`:takeover`, `:archive`/`:restore`, `:delete` plus the global
`event.session.created`/`deleted` frames, the full prompt round-trip (the
documented skeleton on DeepSeek, no `streaming` phase), prompt queueing and
steer (`prompt.queued`, `prompt.steered`, `turn.steer` with the
`inTurn:true` origin and id reuse), running and idle `:abort` (approval abort
→ `outcome:"cancelled"`; question abort → `dismissed`), both compaction
bodies and both `40910`s, files (upload with sniffed `media_type`, download
200, `Range` 206, prompt `session_media` rewrite, delete, media survival),
the TodoList write contract, foreground and background sub-agents with the
`subagent.*`/`task.*` sequence, REST `/tasks`, WS hello/subscribe/ack and
cursor-resume (including future-cursor `resync_required`), the v2 session
table, workspaces and global search. **App impact: the app cannot see live
approvals or questions, cannot answer them, and its question poll now 404s —
tickets 171 (approvals) and 172 (questions) restore both on the new frame;
ticket 173 brings the plan text the mandatory plan review now requires, and
ticket 174 revives the swarm-mode toggle the now-live prompt field unblocks.
No other breakage.**

The drift from 2.3.1 to 2.3.3 is **additive-only on the wire: no route or
WebSocket event was added or removed, while both registered client configs
changed shape and `effort_switch_confirm` was added; nothing breaks the
Android app**. The nightly job's `pending-changelog.md` fetch worked this
time. The GitHub compare reports 13 commits between the tags. Only one
client-facing protocol file changed in `packages/kap-server`
(`src/services/clientConfigs.ts`, commits `f895f305` and `0051ef10`); the
remaining changes are engine prompts, swarm gating, TUI dialogs, and the web
bundle. Live verification used server `2.3.3`, started
`2026-10-26T19:23:51.707Z`.

- **`model_switch_confirm` rules gained `session_type`, wildcards, and
  `warning`** (commit `f895f305`, verified live 2026-10-28): every rule now
  requires `session_type: "old" | "new" | "all"`. `from` and `to` accept the
  literal `*` wildcard, and a rule may carry `warning: {en?, zh?}` next to
  `hint`. The live config is enabled and now publishes one `new`-session
  price warning for `k3.1-pro-highspeed`, one `old`-session warning for the
  same model, and one `old`-session rule for the other K3.1 targets. The
  capture is in Client configs. Rules without `session_type` are
  dropped by the server-side parser. Ticket 167 was updated for the new
  shape; the app has no client-config consumer yet, so there is no decode
  break.
- **New client config `effort_switch_confirm`** (commit `0051ef10`, verified
  live 2026-10-28): registered alongside `model_switch_confirm`. It carries
  `descriptions: {enable, items: {low?, high?, max?: {en?, zh?}}}` and
  `confirm: {enable, rules}` using the same rule shape as the model config.
  The live response enables descriptions for all three efforts and one
  `old`-session, wildcard-to-wildcard confirmation rule. See Client configs
  for the verbatim payload. Ticket 170 consumes it in the app's effort row.
- **Engine and TUI behavior, no wire-shape change:**
  - `AgentSwarm` now runs only while swarm mode is on, and only the user can
    turn swarm mode on (commits `c9a48369`/`f36c127a`/`fd2e2c7e`). A call
    made while it is off returns an error tool result and starts no
    subagents; a rejected call with no spawned members is hidden from the
    transcript. The model is also told to launch `Agent` subagents only when
    the user, `AGENTS.md`, or a skill asks for them.
  - The TUI prints a static high-usage warning when `/swarm` activates swarm
    mode (commit `07a34fe9`). This is local TUI prose, not a server event.
  - The remaining TUI changes merge duplicate permission notices and update
    model-switch dialog copy. None changes REST or WebSocket payloads.
- **Content drift, not shape:** the catalog still has **12 models**, with
  `kimi-code/k3.1-pro-highspeed` now displayed as "K3.1 Pro Highspeed 2x
  Usage Cost" and `default_effort:"high"` on both K3.1 entries. The
  server-wide default model moved to **`deepseek/deepseek-v4-flash`** (1M
  context) from `kimi-code/k3-256k`. The initialized tool catalog reads
  **36 entries: 27 builtins plus 9 MCP tools**; `ReadMediaFile` is absent
  again. `GET /api/v1/config` still has `thinking.effort:"max"`,
  `default_permission_mode:"auto"`, and no `disable_feedback_survey` or
  `auto_session_title` keys.
- **Provider state, not protocol:** a `kimi-code/k3` prompt under the
  managed provider failed immediately with
  `provider.auth_error` / HTTP 403 "Your current subscription does not have
  access to Kimi Code right now" (verbatim error frames captured in the
  verification log). The core-loop checks below then used the server's
  configured `deepseek/deepseek-v4-flash` model and completed normally.
  This is an account subscription state; the error envelope and turn
  failure shapes are unchanged.

Everything else re-verified unchanged live on 2.3.3: `/api/v1/meta` (same 5
flags and 17 features, only `server_version` and `started_at` differ), the
config projection, the models catalog shape, sessions create/get/list/status/
snapshot (`last_seq:1`, `as_of_seq:2` on a fresh session), `40401` without
`stack` on the get path, `:reload` on an idle and an unknown session, a full prompt
round-trip (the documented skeleton, now on
DeepSeek), the TodoList write contract, prompt queueing plus `prompt.steered`
and the deferred steered turn after abort, running and idle `:abort`, both
compaction bodies with `tokensAfter` matching post-compact `/status`, both
`40910`s with `stack`, questions (pending poll, answer, re-answer `40902`,
dismiss `40909`, re-dismiss `40902`), the manual approval round-trip
(`tool_call_id` rejected `40404`, approval resolved), file upload/download/
`Range`/delete and session-media survival, foreground and background
sub-agents with task cancel (`40904` on re-cancel), `/tasks`, session rating
(`evaluate` returns `{"appearance":null}`), `:takeover` on an idle session,
WS hello/subscribe/ack/cursor replay, future-cursor `resync_required`, and
the global `event.session.deleted` on session deletion. **App impact: none.
Ticket 167 was updated for the new model-switch schema, ticket 170 was added
for effort guidance, and no breakage ticket was needed.**

The drift from 2.3.0 to 2.3.1 is **additive-only on the wire — one new route
(`POST :takeover`), one optional field on the 40931 holder
(`clientDisplayName`) with a matching message-text change, and one env
switch (`KIMI_CODE_SESSION_LOCK`); the workspace-trust decoupling is a
behavioral fix on unchanged shapes. Nothing breaks the Android app**. The
nightly job's `pending-changelog.md` fetch worked this time (4 upstream
commits between the annotated tags, reviewed in full via the GitHub API —
`packages/kap-server` touched in four files: the takeover action in
`routes/sessions.ts`, its schema in `protocol/rest-session.ts` and
`openapi/transforms.ts`, and the trust resolver in `routes/workspaces.ts`;
the API-surface snapshot gained exactly the one route; no route or WS event
type removed). The client-facing wire changed in exactly these places:

- **New route `POST /api/v1/sessions/{id}:takeover`** (commit `c569bab5`;
  verified live 2026-10-23 — see Sessions → Takeover): evicts the occupancy
  lock another process holds, so a write that failed with 40931 can be
  retried. Idle → `{"taken_over":true}` (a no-op success); unknown → `40401`
  (with `stack`, unlike the get-session path's); held by a live CLI turn →
  the lock file is deleted and the retried prompt runs. The CLI-side flow
  this serves (retry first, offer takeover on a repeated 40931) is ported to
  the app by ticket 169.
- **40931 holder gained `clientDisplayName`** (commit `0e3980e7`; verified
  live 2026-10-23 — verbatim envelope in the Occupancy-lock section): the
  lock file and the `details.holder` echo carry the optional display name
  ("Kimi Code CLI" / "Kimi Code Desktop" / "Kimi Code for VS Code" /
  "Kimi Code Web" — the embedded server's own holds read "Kimi Code Web"),
  and the 40931 `msg` names it when known ("This session is in use by
  another Kimi Code CLI instance. …" — was a fixed "another Kimi Code
  process" sentence). `code`, `details.sessionId` and the rest of the holder
  block are unchanged — additive on an envelope the app maps generically.
- **Zombie-occupancy fix** (same commit `c569bab5`; observed live): a lock
  whose holder pid is dead (now including zombie-state processes, checked
  via `ps`) no longer blocks — observed mid-probe: a CLI that took the lock
  and immediately crashed out left the file behind, and the next server
  prompt reclaimed the session and ran. Also first-time itemization: the
  create route itself briefly holds a server-side lock (holder
  `clientDisplayName:"Kimi Code Web"`), released as soon as the session goes
  idle — matches the 2.3.0 note that an idle session's lock file is gone.
- **Workspace trust decoupling** (commit `c569bab5`; wire shapes unchanged):
  `GET|POST .../trust` / `:untrust` now read/write a standalone trust store
  instead of materializing the workspace runtime — they no longer fail when
  the runtime environment is unavailable. The round-trip re-verified live
  (false → trust → true → untrust → false, all `{"trusted":…}` envelopes).
- **Config/content drift (not shape):** the models catalog reads **12
  entries** — `kimi-code/k3.1-pro` ("K3.1 Pro", 1048576 ctx,
  `default_effort:"max"`) and `kimi-code/k3.1-pro-highspeed` are new; no
  item publishes `upgrade`. The `model_switch_confirm` client config is
  **populated now** (`enable:true`, rules gating k3*/kimi-for-coding* →
  k3.1-pro* switches, bilingual hints — verbatim capture in Client configs;
  it was `config:null` on 2.3.0), so ticket 167's gate is live. Tools
  catalog: 36 entries on a fresh session (27 builtins + 9 MCP), consistent
  with the documented fresh/initialized split.
- **Wire-invisible here:** `KIMI_CODE_SESSION_LOCK=0` disables the lock
  server-wide (env switch), the workspace-trust runtime decoupling's
  internals (trust store, MCP-config recovery), the TUI occupancy dialogs,
  and the web-dist sync (`c445cae0`).

Everything else re-verified unchanged live on 2.3.1 (see the verification
log): `/api/v1/meta` (only `server_version` differs — same 5 flags, same 17
features), the config projection (`default_model` `kimi-code/k3-256k`,
`thinking.effort` `"max"`, `default_permission_mode` `"auto"`; no
`disable_feedback_survey`/`auto_session_title` keys), sessions create/get/list
(`last_seq` `1` on a fresh detail, `0` on the list), `40401` without `stack`
on the get path, `/status`, the snapshot (top-level keys, real
`session.usage`, busy `in_flight_turn` with `thinking_text`/`running_tools`/
`current_prompt_id`), messages history (newest-first), prompts POST + ACK
(`prompt_id` == `user_message_id`, `status` `running`/`queued`), the full
prompt round-trip skeleton (`prompt.submitted` → `session.meta.updated` →
`turn.started` with `promptId` → `context.spliced` → `prompt.started` →
steps/deltas → `turn.step.completed` → `turn.ended` with `traceId` →
`work_changed` → `prompt.completed`; no `streaming` phase), prompt queueing +
steer (`prompt.queued` `queueLength`, single-steer id reuse, `prompt.steered`
before `turn.steer` with the `inTurn:true` origin), mid-run and idle `:abort`
(`turn.step.interrupted` `aborted` → `turn.ended` `cancelled`/
`user_cancelled` → interruption splice → `prompt.aborted`; idle
`{"aborted":true}`), compact with `{"instruction":…}` (instant ACK,
instruction echo in `compaction.started`, wholesale `context.spliced`,
`compaction.completed` with the full `result` block, `tokensAfter` 30415) and
both `40910`s with `stack` (empty + busy), the questions flow end-to-end
(pending poll, answer → `resolved:true`, dismiss → `40909` +
`dismissed:true`, re-dismiss AND answer-after-dismiss → `40902`, the
`event.question.*` frames with `agent_id`), the manual-approval round-trip
(`approval_<uuid>` ≠ `tool_call_id`, `tool_call_id` POST → `40404`,
`approved` → both resolved frames), files (upload with sniffed
`media_type:"image/png"`, the `session_media` projection with `name` in the
ACK AND history, download 200, `Range` 206, delete → `{"deleted":true}`,
`40407` at HTTP 404 after delete), the TodoList contract (verbatim
`{"todos":[{status,title}]}` full-list writes in history `tool_use` parts),
foreground + background sub-agent lifecycles (`subagent.spawned` with
`taskId`/`model`/`thinkingEffort:"max"`, `subagent.started`/`completed`,
`task.started` → `background.task.started`), REST `/tasks` list (all the
0.39.0/0.36.1 fields incl. `run_in_background`/`agent_id`/`subagent_type`/
`parent_tool_call_id`), the workspaces list, global search (`index_state`
intact), the v2 grouped session table, the lifecycle events
(`event.session.created`, `:delete` → `{"deleted":true}` +
`event.session.deleted` with camelCase `sessionId` on the `__global__`
envelope), and WS hello/subscribe/ack/cursor-resume (`protocol_version: 2`,
`heartbeat_ms` 10000; replay from seq 2 re-delivered 3–15 in order before the
ack; future-seq cursor → standalone `resync_required` `epoch_changed` then
the ack listing it; the heartbeat-timeout silence re-observed — frames stop
~30 s in when pongs go unanswered, and a 5 s pong feeder keeps the socket
alive indefinitely). **App impact: none — both 2.3.1 changes are additive on
payloads the app parses defensively. One improvement ticket filed (169 —
offer `:takeover` on a repeated 40931, naming the holder from
`clientDisplayName`); ticket 167's context updated (its gate is live now); no
breakage tickets.**

The drift from 2.2.0 to 2.3.0 is **additive-only on the wire — three new
routes (`:reload`, `/rating`, `/client_configs`), two new error codes
(`40931` occupied / `40932` stale) riding a new cross-process session lock,
one optional model-catalog field (`upgrade`), one optional field on
`event.model_catalog.changed` (`retired_default`), and one config key
(`disable_feedback_survey`); nothing breaks the Android app**. The nightly
job's `pending-changelog.md` fetch failed again (missing tag or network), so
the 33 upstream commits between the tags were enumerated from a blobless
clone of the public repo (both annotated tags confirmed) and the 20 commits
touching `packages/kap-server`/`packages/agent-core-v2` reviewed in full
(the server's own API-surface snapshot gains exactly these three routes; no
route or WS event type removed). The client-facing wire changed in exactly
these places:

- **Cross-process session occupancy lock** (commit `df0ff3c5` — the
  release's biggest change; verified live 2026-10-22): a session now has a
  lock file under `~/.kimi-code/locks/sessions/<id>.lock` while a process
  works it, content `{version, token, pid, hostname, startedAt,
  clientIdentity}`. A write against a session another process holds fails
  with the new **40931 `SESSION_OCCUPIED`** — verbatim envelope, captured by
  holding the session with a `kimi -p` CLI turn and then POSTing a server
  prompt:

  ```json
  {"code":40931,"msg":"This session is in use by another Kimi Code process. Stop the session in that process or exit it, then try again.","data":null,"request_id":"01M5JKEA2ESGF2Y6C2W9FHGVEY","stack":"Error2: …","details":{"sessionId":"session_074efb5c-…","holder":{"version":1,"token":"368178:b549243e-…","pid":368178,"hostname":"host.example","startedAt":1792699474275,"clientIdentity":"kimi_code_cli"}}}
  ```

  Note the top-level **`details`** envelope field — the error transport
  already carried one on Zod `40001`s; 2.3.0 exposes it on Error2
  envelopes for 40931/40932 only. The lock is held only while a turn runs: an idle
  session's lock file is gone, and after the CLI exited the same prompt POST
  succeeded. The sibling code **40932 `SESSION_STALE`** fires when the
  session's on-disk state moved under the holder — but kap-server wraps
  prompt submit, skill activation and `:compact` in `reloadOnStale`: the
  server closes+resumes the session and **retries internally, so those
  routes never return 40932** (verified live: a `kimi -p` CLI turn appended
  a turn to the session, then a server prompt POST answered `code:0` and ran
  normally; the compact route's busy-40910 stack trace visibly passes
  through `reloadOnStale`). Only `:undo` still surfaces 40932 after
  reloading (not probed — the app has no undo). The WS error-code enum
  (`kimiErrorCodeSchema`) gained `session.occupied` (registered retryable)
  and `session.stale`. The Android error boundary preserves both codes as
  `DataError.Api` — no decode breakage; ticket 168 adds the recovery UX.
- **New route `POST /api/v1/sessions/{id}:reload`** (commit `18c6d642`;
  verified live 2026-10-22 — see Sessions → Reload): closes+resumes the
  session server-side (config/plugins/workspace catalogs reload; engine
  change notifications then ride the session stream as ordinary messages).
  Idle → `code:0` with the standard session DTO; busy (turn or compaction in
  flight) → **40901** `Session "…" cannot be reloaded while a turn is
  running` (with `stack`).
- **New route `POST /api/v1/sessions/{id}/rating`** (commit `ce0834a8`;
  verified live 2026-10-22 — see Session rating): the CLI/web feedback
  surveys over REST — request union `{op:"evaluate",turn_id}` /
  `{op:"shown"|"respond"|"abandon",appearance_id,…}`; every `evaluate` probe
  here answered `{"appearance":null}` (four turn-id spellings, no error —
  an unknown or non-latest turn id is not an error). No WS involvement.
- **New route `POST /api/v1/client_configs`** (commit `3f80896a`; verified
  live 2026-10-22 — see Client configs): server-proxied cloud client
  configs, `{"name":"…"}` → `{"name", "config": <object>|null}`. The one
  registered name, `model_switch_confirm`, answered `config:null` here (the
  gate is off for this server); an unregistered name answers the same
  `config:null`.
- **Model-migration metadata** (commit `0327e8e9`; schema-derived — the
  live catalog publishes neither): catalog items may carry optional
  **`upgrade: {model, migration_markdown, retirement_at?}`** (the
  replacement alias plus user-facing migration Markdown), and
  `event.model_catalog.changed` may carry optional **`retired_default:
  {from, to, from_display_name}`** (fired when the server's default model
  was retired and auto-migrated). None of the 10 live catalog items has
  `upgrade`; no `retired_default` frame observed. Tickets 166/167 consume
  these.
- **Config key `disable_feedback_survey`** (same rating commit; GET and
  PATCH schemas): **absent from this server's GET projection when unset** —
  verified live, no key in `data` (same pattern as `auto_session_title`).
- **WS event-state eviction on close/archive** (commit `5710c549`;
  source-derived, probed live): the broadcaster drops per-session WS event
  state (and the on-disk session event journal) when a session closes or is
  archived. Live on 2.3.0: an archived session answers subscribe with
  **`not_found`** (pre-existing behavior), and after `:restore` a
  cursor-resume from seq 200 **still replayed 201–230 in order before the
  ack** — the eviction does not break journal replay on this deployment.
  The replay also re-observed **`agent.disposed`** frames (payload `{type,
  agentId, sessionId}`, no `time`) firing when the engine tears the main
  agent down (the stale-reload and the archive each produced one) — an
  engine-lifecycle event the app already ignores.
- **Error-mapping additions** (the lock commit): `session.fork_active_turn`
  → 40901, `compaction.unable` → the 40910 family, `file.not_found` →
  40407, skill not-found/name/type errors → their own codes,
  `validation.failed` → 40001 — paths that previously fell through to
  50001. No live trigger exercised; all are envelopes the app already maps
  generically.
- **Content drift (not shape):** the tools catalog reads **37 entries on an
  initialized agent — 28 builtins + 9 MCP** (2.2.0 had 36/27:
  `ReadMediaFile` is back in the catalog; `select_tools` present as before);
  `NotifyUser` still gated off. Plugin skills register under qualified
  `pluginId:skillName` names (commit `3f0857eb` — the skills routes are out
  of scope). The v2 session table's `activity.model` read `null` (not `""`)
  for one never-opened session — a value nuance; the field stays optional.
- **Wire-invisible here:** the default-workspace saga (`47002256`/
  `e5e56bc5` added routes plus error 40930, `83c7909be` reverted the lot
  within the release — net zero; the released tags skip 40930), the unified
  upstream `/models` schema parse (`9f0693be` — managed-catalog contents
  derive from `limit.context`/`modalities`/`reasoning`/`tools.dynamic_load`
  now; the REST item shape is unchanged), MCP `default_deferred` config
  (`f28c51c0`), `dangerous_command_guard=false` now suppressing all built-in
  guard ask policies (`6e7cc909` — the guard stays on here), tool-prose
  tweaks (`e4c99a3b`/`da5918c0`/`85077a41`), hooks running in the session
  cwd (`c1459a61`), Gemini thinking-level mapping (`20b59915`/`b2603264`),
  remote-control tunnel chunking (`ef727e51` — out of scope), and
  TUI/vscode/dist/CI churn.

Everything else re-verified unchanged live on 2.3.0 (see the verification
log): `/api/v1/meta` (only `server_version` differs — same 5 flags, same 17
features), the config projection (`default_model` `kimi-code/k3-256k`,
`thinking.effort` `"max"`, `default_permission_mode` `"auto"`), the models
catalog (10 entries, same item shape), sessions create/get/list (`last_seq`
`1` on a fresh detail, `0` on the list), `40401` without `stack`, `/status`,
the snapshot (top-level keys, real `session.usage`, busy `in_flight_turn`
with `running_tools`, the completed roster entry with `output_preview`),
messages history (newest-first; the steered message keeping the queued
prompt's id; the `date_change`/`permission_mode` injections), prompts POST +
ACK (`prompt_id` == `user_message_id`, `status` `running`/`queued`), the
full prompt round-trip skeleton (no `streaming` phase), prompt queueing +
steer (single-steer id reuse, `prompt.steered` before `turn.steer`, the
`inTurn:true` origin), mid-run and idle `:abort` (full interrupted sequence
with the `ownerPromptId` interruption splice), compact with `{}` and
`{"instruction":…}` (instant ACK, instruction echo, wholesale splice with
the id-less `compaction_summary` + `compaction_continuation`, `tokensAfter`
30205 == post-compact `context_tokens`, both `40910`s with `stack`), the
questions flow end-to-end (pending poll with `agent_id`, answer →
`resolved:true`, re-answer `40902`, dismiss `40909` + `dismissed:true`,
re-dismiss `40902`, all three `event.question.*` frames), the
manual-approval round-trip (`approval_<uuid>` ≠ `tool_call_id`,
`tool_call_id` POST → `40404`, both resolved frames), files (upload with
sniffed `media_type`, the `session_media` projection with `name` in the ACK
AND history, download 200, `Range` 206, delete → `40407` at HTTP 404, the
media copy surviving the upload's delete), the TodoList contract (verbatim
`{"todos":[{status,title}]}`), foreground + background sub-agent lifecycles
(`subagent.spawned` with `taskId`/`model`/`thinkingEffort:"max"`,
`subagent.completed` with `resultSummary`/`usage`/`contextTokens`,
`task.started` → `background.task.started` → `task.terminated` →
`background.task.terminated`), REST `/tasks` list (all the 0.39.0/0.36.1
fields), the per-agent transcript (steps carry `usage` + `llmTiming`), the
turn file-history routes (`{changes[], recorded}`, no `enabled`), the tools
catalog, the workspaces list, global search (`index_state` intact), the v2
grouped session table, the lifecycle events (`event.session.created`,
`event.session.deleted` with camelCase `sessionId` on the `__global__`
envelope), and WS hello/subscribe/ack/cursor-resume (`protocol_version: 2`,
`heartbeat_ms` 10000; replay from seq 12 re-delivered 13–16 in order before
the ack; future-seq cursor → standalone `resync_required` `epoch_changed`
then the ack listing it; the heartbeat-timeout silence re-observed — frames
stop ~30 s in when pongs go unanswered). **App impact: none — every 2.3.0
change is additive on payloads the app parses with `ignoreUnknownKeys`, or
self-heals server-side. The three improvement tickets this release warrants
were filed by this drift run's earlier session (166 — consume the model
catalog's `upgrade`; 167 — honor `model_switch_confirm`; 168 — recover chat
sends from stale/occupied sessions); no breakage tickets.**

The drift from 2.1.1 to 2.2.0 is **additive-only on the wire —
`agent.status.updated` gained a `permission` variant that fires when the
session's permission mode changes, history text parts gained an optional
`meta` object, and the interruption injection's origin gained
`ownerPromptId`; everything else is engine behavior or out of scope, and
nothing breaks the Android app**. The upstream diff between the two tags
(36 commits, reviewed via the GitHub API; `packages/kap-server` touched in
five files — `prompts.ts`, `start.ts`, `coreEventMap.ts`,
`transcriptService.ts`, plus the remote-control and fs-list routes)
touched the client-facing wire in exactly these places:

- **`agent.status.updated` gained an optional `permission` field** (commit
  `a940f2ff` — "publish permission mode changes on agent.status.updated";
  verified live 2026-10-14): the event now fires when the session's
  permission mode changes (a `POST .../profile` write, or the engine's own
  goal-start mode set), carrying the new mode next to the usual usage
  block. Verbatim 2.2.0 frame, captured on a
  `POST .../profile {"agent_config":{"permission_mode":"manual"}}` write
  (usage buckets trimmed):

  ```json
  {"type":"agent.status.updated","seq":90,"volatile":true,
   "payload":{"type":"agent.status.updated","time":1792006993469,"agentId":"main",
              "permission":"manual",
              "usage":{"byModel":{"kimi-code/k3":{"inputOther":16013,"output":1763,
                                                 "inputCacheRead":202240,"inputCacheCreation":0}},
                       "total":{...},"currentTurn":{...}},
              "contextTokens":30317,"maxContextTokens":1048576,"model":"kimi-code/k3",
              "sessionId":"session_..."}}
  ```

  The reverse write (`"auto"`) produced the twin frame. The app decodes
  `agent.status.updated` with `ignoreUnknownKeys` and handles `permission`
  since ticket 165: the chat permission chip follows the frame live, under
  the ticket-141 guard (a stored local pick wins).
- **History text parts gained an optional `meta` object** (commit
  `06ebfc82` — UserPromptSubmit hook output folded into the prompt
  message; source-derived, **not observed live** — this server runs no
  UserPromptSubmit hooks and the run's prompts activated no skills):
  `text` parts may now carry `meta: {source, contentType?, activationId?}`
  marking skill-activation blocks and hook-injected text, and the
  kap-server prompt snapshot projection filters those parts by
  `meta.source` instead of the old positional prefix-slice (sessions with
  hook injections no longer shift turns or lose replies). On a hook-less
  server the wire is byte-identical; the app's `ContentPartDto` ignores
  unknown fields — no ticket.
- **The interruption injection's origin gained `ownerPromptId`** (commit
  `e3bf50c0` — remove the interruption reminder when its turn is undone;
  verified live 2026-10-14 in `GET .../messages`): the reminder's
  `metadata.origin` now reads
  `{"kind":"injection","variant":"interruption","ownerPromptId":"msg_..."}`
  — the link the TUI undo path needs. Additive; the app reads only
  `origin.kind`/`variant` off injections — no ticket.
- **WaitFor capped at 90 s** (commit `20a2cea7`; engine behavior, wire
  shape unchanged): longer timeouts are rejected, repeated waits are
  discouraged, and a new incoming prompt ends the wait immediately (the
  0.43.0 steer-interrupts-WaitFor rule generalized). WaitFor result prose
  may differ; the app never parses it — no ticket.
- **Behavioral — auto permission mode suppresses `AskUserQuestion`**
  (first live observation 2026-10-14; pre-existing engine behavior made
  visible by the config flip below): a session running `auto` permission
  mode receives a model-facing reminder not to call `AskUserQuestion` —
  this run's cake-or-pie prompt under `auto` completed without ever
  surfacing a question, and the same prompt after switching the session to
  `manual` surfaced the full questions flow. The questions wire contract
  itself is unchanged (see the re-verification list) — auto sessions
  simply produce no questions to answer.
- **Config drift (not shape):** `default_permission_mode` reads
  **`"auto"`** (was `"yolo"` on every previous verification) — fresh
  sessions now start in auto mode (see the suppression above); a fresh
  session's `/status` `permission` reads `"auto"`, `max_context_tokens`
  still 262144. `default_model` still `kimi-code/k3-256k`,
  `thinking.effort` still `"max"`. The models catalog still reads 10
  entries with the same item shape.
- **Tools-catalog content drift** (server/model-state, not the release's
  wire): **36 entries on an initialized agent — 27 builtins + 9 MCP
  tools** (2.1.1 had 28/37: `ReadMediaFile` is out of the catalog again);
  `NotifyUser` still gated off (`notify_user: false`). See Tools.
- **Wire-invisible here:** the opt-in NotifyUser progress display
  (`0a3a2fba`/`7091138f`/`4fbe0654` — flag `notify_user: false`;
  kap-server's host identity gained an engine-internal `uiCapabilities`
  declaration), tower deterministic orchestration (`c9f01f07`/`09af3b48` —
  flag `tower: false`), the remote-control device management + deletion
  routes (`c69bd6c5`/`c1ec3725` — out of scope), `fs:list`
  `allow_ignored_globs` (`5b936697` — fs routes out of scope),
  forked-subagent agent-type parity and the prompt-cache-probe fork
  report (`f0bc988b`/`fed19d24` — engine-internal), the media-tools
  restore after session resume (`419aced0`), in-flight video-upload
  cancellation (`c15638b0`), mid-turn progress notes (`7091138f` —
  model-facing prose), the kaos MCP executor removal (`cbd75404` — config
  validation), per-model survey overrides (`eec9bbd9` — TUI),
  `KIMI_CODE_TRUST_WORKSPACE` + trust summaries (`1f6f0b1f`/`395d5372`),
  `KIMI_CODE_LLM_HEADERS_TIMEOUT_MS` (`c151d5a3`), the max-tokens omission
  fix (`21406fb4` — engine↔LLM), cron clearing at fork (`f409caa2`),
  `kimi app` desktop launch (`242ac230`), and TUI/CI churn.

Everything else re-verified unchanged live on 2.2.0 (see the verification
log): `/api/v1/meta` (only `server_version` differs — same 5 flags, same
17 features), the config projection, the models catalog (10 entries),
sessions create/get/list (`last_seq` `1` on a fresh detail, `0` on the
list), `40401` without `stack`, `/status`, the snapshot (top-level keys,
real `session.usage`, busy `in_flight_turn` with `running_tools`, empty
pending/roster lists, `messages` as `{items, has_more}`), messages history
(newest-first), prompts POST + ACK (`prompt_id` == `user_message_id`), the
full prompt round-trip skeleton (no `streaming` phase; the `date_change`
AND `permission_mode` injections), prompt queueing + steer (the 2.0.1
message-identity set intact — single-steer id reuse, `prompt.steered`
before `turn.steer`), mid-run and idle `:abort` (full interrupted
sequence; idle `{"aborted":true}`), compact with `{}` and
`{"instruction":...}` (instant ACK, instruction echo, wholesale splice
with the id-less `compaction_summary` + `compaction_continuation`,
`tokensAfter` == post-compact `context_tokens`, both `40910`s with
`stack`), the questions flow end-to-end (pending poll with `agent_id`,
answer → `resolved:true`, re-answer `40902`, dismiss `40909` +
`dismissed:true`, re-dismiss `40902`, all three `event.question.*`
frames), the manual-approval round-trip (pending poll with `agent_id`,
`tool_call_id` POST → `40404`, `approved` → both resolved frames), files
(upload with sniffed `media_type`, download 200, `Range` 206, delete →
`40407` at HTTP 404, the `session_media` projection with `name` in the ACK
AND history, the media copy surviving the upload's delete), the TodoList
contract (verbatim `{"todos":[{status,title}]}`), foreground + background
sub-agent lifecycles (`subagent.spawned` with
`taskId`/`model`/`thinkingEffort:"max"`, `subagent.completed` with
`resultSummary`/`usage`/`contextTokens`, the cancel sequence verbatim —
`subagent.cancelled` → `task.terminated` `"killed"` →
`background.task.terminated` → `task.notified` `"task.killed"` with the
"Wall time:" prefix), REST `/tasks` list/get `with_output`/`:cancel`
(`40904` on an already-finished task), the v2 grouped session table
(`groups[]`, `activity.model`), the per-agent transcript (completed steps
carry `usage` + `llmTiming`), the tools catalog (36 entries), the
lifecycle events (`event.session.created` at seq 1,
`event.session.deleted` with camelCase `sessionId`), and WS
hello/subscribe/ack/cursor-resume (`protocol_version: 2`, `heartbeat_ms`
10000; replay from seq 290 re-delivered 291+ in order before the ack;
future-seq cursor → standalone `resync_required` `epoch_changed` then the
ack listing it; the heartbeat-timeout close re-observed — pings at +10 s
and +20 s, close before +30 s). **App impact: none — every 2.2.0 change
is additive on payloads the app parses with `ignoreUnknownKeys`, or
engine-internal. One improvement ticket filed (165 — consume the new
`permission` variant to keep the chat permission chip in sync live).**

The drift from 2.0.2 to 2.1.1 is **additive-only on the wire — transcript
steps renamed `timing` to `llmTiming` and now carry `usage` (cold fold
included), `subagent.*` lifecycle events became durable journal records (they
now ride the cursor-resume replay), task notifications gained a "Wall time:"
prose prefix, and the config grew an `auto_session_title` boolean; nothing
breaks the Android app**. The upstream diff between the two tags (28 commits,
reviewed via the GitHub API; `packages/kap-server` touched in five files —
`rest-config.ts`, the transcript service trio, `start.ts`) touched the
client-facing wire in exactly these places:

- **Transcript steps: `timing` → `llmTiming`, plus `usage`** (commit
  `b428bfd0` — "include step timing and usage in cold-folded steps";
  verified live 2026-09-24): every step of a warm transcript now carries
  `usage:{inputOther,output,inputCacheRead,inputCacheCreation}` and
  `llmTiming:{llmFirstTokenLatencyMs,llmStreamDurationMs}` where 2.0.2
  shipped `timing` (same inner shape). The **cold fold now projects both
  too** (source-derived: `foldFacts.ts` reads them off the journaled
  `context.append_message` records — 2.1.1-written journals carry them
  because sealed assistant messages now record usage/timing — and
  `groupTurns.ts` ships them on cold steps). Verbatim 2.1.1 warm step
  (frames trimmed):

  ```json
  {"kind":"step","stepId":"t0.1","turnId":"t0","ordinal":1,"state":"completed",
   "usage":{"inputOther":10967,"output":300,"inputCacheRead":15360,"inputCacheCreation":0},
   "llmTiming":{"llmFirstTokenLatencyMs":4217,"llmStreamDurationMs":6019},
   "frames":[…]}
  ```

  The app parses only `kind`/`stepId`/`turnId`/`ordinal`/`state`/`frames`
  off steps (`TranscriptStepDto`) — no ticket.
- **`subagent.*` events are durable now** (commit `895e9d9b` — swarm member
  restore; verified live 2026-09-24): `subagent.spawned`/`started`/
  `completed`/`failed`/`cancelled` persist to the session journal, so a
  **cursor-resume replay now delivers them** (a resume from seq 50 replayed
  `subagent.spawned`/`started`/`cancelled` verbatim at seqs 68/69/84 — up
  to 2.0.2 they were live-only, and the app healed misses from REST
  `/tasks` + the roster). Same shapes, no new fields; the app parses the
  events already, so the gain is automatic — no ticket. The same commit's
  transcript fold marks a `running` sub-agent task whose member agent is
  not live as **`state:"lost"`** in the transcript `tasks[]`
  (`markLostSubagentTasks`; the app never reads transcript `tasks`).
- **Task notifications gained a "Wall time:" prose prefix** (commit
  `0dc4d4fb`; verified live 2026-09-24): `task.notified`'s `body` and the
  `<notification>` history XML now open with `Wall time: N.NNN seconds`
  (verbatim: `"body":"Wall time: 14.559 seconds\nBackground agent sleep
  was stopped by user.…"`), and the task tools (`TaskList`/`TaskOutput`/
  `TaskStop`/`WaitFor`) prepend the same header to their output text. The
  app never parses `task.notified`, strips `<notification>` envelopes, and
  links task rows from tool-call args — invisible. **The same header on
  model-facing tool results is engine→LLM only** (the context projector
  prepends it to Agent/AgentSwarm/Bash/FetchURL/Glob/Grep/WebSearch/MCP
  results): verified live ABSENT from the wire — WS `tool.result.output`
  and history `tool_result.output` are byte-identical to 2.0.2, and the
  background `task_id:` stubs still head their results, so the app's
  first-line stub parsers stay safe.
- **New config boolean `auto_session_title`** (commit `6a52dd78`; the REST
  schema accepts it on GET/POST `/api/v1/config`; upstream doc: "Whether
  clients may automatically generate session titles"): **absent from this
  server's GET projection when unset** (verified live — no key, and titles
  generate by default; `session.meta.updated` re-verified). The app reads
  only `default_model`/`thinking.effort`/`providers` off config — no
  ticket.
- **Behavioral, wire shape unchanged:** forked sessions without an
  explicit title now inherit the source's `titleKind` instead of resetting
  to `replaceable` (`994287ac` — the app never forks); cold transcript
  reads drain the live wire first (`drainLiveWire` — fresher cold reads);
  the `[tools]` config hot-reloads (`toolPolicyService`); a wireCache
  stack overflow on large journals is fixed (`6a214b85`).
- **Wire-invisible here:** tower protocol invariants + mission token usage
  (`f7012aa2`/`65ae3e36` — flag `tower:false`), the
  `KIMI_CODE_REPEAT_BREAKER` env switch (`a54e6f6a`), telemetry plugin
  reporting (`0523bafb`), MCP OAuth `offline_access` (`e796bb5d`),
  remote-control region picking (`b3212fd9` — out of scope), the
  trust-boundary hardening add-then-revert pair (`6451f1e0`/`929403b6`),
  the filesystem-watch default off-then-on pair (`f17a22eb`/`c7dd8412`),
  CLI startup perf (`bb96d807`), and TUI/web-bundle churn.

Everything else re-verified unchanged live on 2.1.1 (see the verification
log): `/api/v1/meta` (only `server_version` differs), the config projection,
sessions create/get/list (`last_seq` `1` on a fresh detail, `0` on the
list), `40401` without `stack`, `/status`, the snapshot (top-level keys,
real `session.usage`, busy `in_flight_turn`, the `pending_approvals` item
with `agent_id`, empty roster once terminal), messages history
(newest-first), prompts POST + ACK (`prompt_id` == `user_message_id`), the
full round-trip skeleton (no `streaming` phase), prompt queueing + steer
(the 2.0.1 message-identity set intact — a single steer reuses the queued
id), mid-run and idle `:abort`, compact (instruction echo, wholesale splice
with the id-less `compaction_summary` + `compaction_continuation`,
`tokensAfter` == post-compact `context_tokens`, both `40910`s with
`stack`), the questions flow end-to-end (pending poll with `agent_id`,
answer → `resolved:true`, dismiss → `40909`, re-answer/re-dismiss `40902`,
all `event.question.*` frames), the manual-approval round-trip
(`tool_call_id` POST → `40404`, `approved` → both resolved frames, the
snapshot item), files (upload, download 200, `Range` 206, delete → `40407`
at HTTP 404, the `session_media` projection with `name` in the ACK AND
history, the media copy surviving the upload's delete), the TodoList
contract (verbatim `{"todos":[{status,title}]}`), foreground + background
sub-agent lifecycles (spawned with `taskId`/`model`/`thinkingEffort:"max"`,
completed with `resultSummary`/`usage`/`contextTokens`, the cancel sequence
verbatim), REST `/tasks` list, the v2 grouped session table, the models
catalog (10 entries), the lifecycle events (`event.session.created` at seq
1, `event.session.deleted` with camelCase `sessionId`), and WS
hello/subscribe/ack/cursor-resume (`protocol_version: 2`, `heartbeat_ms`
10000; replay before the ack; future-seq cursor → standalone
`resync_required` `epoch_changed` then the ack listing it; the
heartbeat-timeout close re-observed). Tools-catalog content drift (host MCP
config, not the release): 37 entries on an initialized agent, 36 on a fresh
one — the same 28/27 builtins, 9 MCP tools (tavily lost `tavily_feedback`,
back to 5; kiln 4). **App impact: none — every 2.1.1 change lands on
payloads the app either never parses (transcript step extras,
`task.notified`, the notification XML) or parses defensively (`subagent.*`
events, whose new durability it exploits for free). No tickets filed.**

The drift from 2.0.1 to 2.0.2 is **additive-only on the v2 surface — the
interaction payloads (approvals and questions) gained an `agent_id` field
everywhere they appear — plus one removal the app never used: the v3
protocol is gone**. The upstream diff between the two tags (12 commits,
reviewed via the GitHub API) touched the client-facing wire in exactly these
places:

- **`agent_id` on approval wire items** (commit `2cedfaf1` — "deliver
  session-level interaction events past agent filters"; verified live
  2026-09-19): the snapshot `pending_approvals` items and the legacy
  `event.approval.requested` frame now carry a required **`agent_id`**
  (`"main"` for the main agent, the asking sub-agent's id otherwise) — in
  addition to the camelCase `agentId` stamp the WS frame already had. This
  also retro-fills ticket 139's gap: the REST/snapshot read path now names
  the asking agent too. Verbatim 2.0.2 frame:

  ```json
  {"type":"event.approval.requested","seq":67,
   "payload":{"type":"event.approval.requested","agentId":"main","sessionId":"session_...",
              "approval_id":"approval_58b631f8-2537-4ed6-bdca-b3f06be56c71","session_id":"session_...",
              "agent_id":"main","turn_id":2,"tool_call_id":"tool_aTSHHtVv3uhqcac3axTAXWBV",
              "tool_name":"Bash","action":"Running: rm -rf /home/ubuntu/spike/approval-probe-2",
              "tool_input_display":{"kind":"command","command":"rm -rf /home/ubuntu/spike/approval-probe-2",
                                    "cwd":"/home/ubuntu/spike","language":"bash"},
              "created_at":"2026-09-19T19:33:50.309Z","expires_at":"2026-09-20T19:33:50.309Z"}}
  ```

- **`agent_id` on question wire items** (same commit; optional in the
  schema, present on every 2.0.2 live capture): the
  `/questions?status=pending` items, the snapshot `pending_questions` items,
  and the `event.question.requested` frame carry it too. Verbatim 2.0.2
  pending-poll item:

  ```json
  {"question_id":"question_be59fa0e-0fe6-4437-b407-29cc6b7dd723","session_id":"session_...",
   "questions":[{"id":"q_0","question":"Cake or pie?",
                 "options":[{"id":"opt_0_0","label":"Cake"},{"id":"opt_0_1","label":"Pie"}],
                 "header":"Dessert","allow_other":true}],
   "created_at":"2026-09-19T19:32:06.261Z","agent_id":"main","turn_id":1,
   "tool_call_id":"tool_5gF7ty3rd1ssoctEgV3yoPuQ"}
  ```

- **Interaction events bypass agent filters** (same commit): a WS subscribe
  carrying an `agent_ids` filter still receives the session-level
  `event.approval.*` / `event.question.*` frames. The app subscribes
  unfiltered — invisible to it.
- **The v3 protocol is removed** (commit `2502d215` — a revert of the 0.43.0
  addition, which had only ever shipped unused): `WS /api/v3/ws` refuses the
  handshake and `GET /api/v1/sessions/{id}/history` answers a plain Fastify
  404 (both verified live 2026-09-19). The v2 endpoint is byte-untouched
  (`server_hello` still numeric `protocol_version: 2`, subscribe/ack/cursors
  re-verified). The app never used v3 — see the updated v3 section below.
- **Behavioral, wire shape unchanged:** an unconsumed steer that seeds the
  next turn no longer records a `turn.steer` (`97212596` — in-turn consumed
  steers still do; re-verified live 2026-09-19 with a single-prompt steer:
  `prompt.steered` (`messageId` == the queued prompt's id) → `turn.steer`
  with the 2.0.1 message-identity set, unchanged); turn ids are floored at
  the wire-wide max and cold transcripts fold over the active branch
  (`9df7a9cc` — transcript/catch-up internals); compaction pre-shrinks
  history to the effective model window (`88a7d932` — the
  `compaction.started` → `context.spliced` → `compaction.completed` sequence
  is unchanged, re-verified live 2026-09-19 with both `{}` and
  `{"instruction":"..."}` triggers).

Everything else spot-checked unchanged live on 2.0.2 (see the verification
log): `/api/v1/meta` (only `server_version` differs), session
create/get/list, `/status` (`permission`/`plan_mode`/`swarm_mode`/
`context_usage` fields all present), messages history (`items` newest-first
with `has_more`), the snapshot's top-level keys, the questions flow
end-to-end (pending poll, answer → `resolved:true`, dismiss → `40909` +
`dismissed:true`, all `event.question.*` frames), the approval round-trip
(pending poll, `{"decision":"approved"}` → `resolved:true`,
`event.approval.resolved` + the `permission.approval.resolved` v2
duplicate), abort, WS hello/subscribe/ack and the heartbeat-timeout close.
One error-schema observation: the approvals POST `40001` for a bad decision
now lists `"approved"|"rejected"|"cancelled"` as the valid enum (the doc has
only ever exercised the first two). **App impact: none — every 2.0.2 change
is additive on payloads the app parses with `ignoreUnknownKeys`, or removes
a protocol the app never used. No tickets filed.**

The drift from 2.0.0 to 2.0.1 is **additive-only — `turn.ended` gained an
optional `traceId`, and the steer frames gained a message-identity set
(`messageId` on `prompt.steered`; `messageId`/`promptIds`/`turnId` and an
`inTurn:true` origin on `turn.steer`; the spliced steered message and history
carry that same id and origin). A single steer now reuses the queued prompt's
own id as the steered message's id; a multi-prompt steer still mints a fresh
ULID for the merged message. Nothing breaks the Android app — it ignores both
steer events entirely and reads only `reason` off `turn.ended`**. The upstream
diff between the two tags (21 commits, reviewed via the GitHub API) touched
the client-facing wire in exactly these places:

- **`turn.ended` gained optional `traceId`** (commit `3cc6b2a3` — the turn
  trace id, carried for rating-survey telemetry; verified live 2026-09-18 —
  every `turn.ended` of this run's round-trips carried it, main and sub-agent
  turns alike). Verbatim frame:

  ```json
  {"type":"turn.ended","seq":26,
   "payload":{"type":"turn.ended","time":1789760155505,"turnId":0,"reason":"completed",
              "durationMs":14175,"traceId":"365206127174fa1a7979ea31f2e51554",
              "agentId":"main","sessionId":"session_..."}}
  ```

  The app's `TurnEndedPayload` reads only `reason` — no ticket.
- **Steer message-identity pairing** (commits `53e5e3fc`, `60f2a632`,
  `73ebe9ab`; verified live 2026-09-18 — see Steer and the verbatim frames in
  WebSocket): `prompt.steered` gained **`messageId`**; `turn.steer` gained
  **`messageId`**, **`promptIds`** and **`turnId`**, and its origin now reads
  `{"kind":"user","inTurn":true}`; the `context.spliced` steered user message
  carries that same `messageId` as its `id` plus the `inTurn:true` origin,
  and so does message history. **A single steer reuses the queued prompt's
  own id** as `messageId` (`60f2a632` — observed `prompt.steered`
  `promptIds:["msg_01M2V0F8B40WBPS5J5QC2RPZJ9"]` with
  `messageId:"msg_01M2V0F8B40WBPS5J5QC2RPZJ9"`, and the spliced message
  carries that same id); **a multi-prompt steer mints a fresh ULID** for the
  merged message (`promptIds` lists both queued ids, `messageId` is a third,
  fresh one). The event sequence is unchanged since 2.0.0: `prompt.steered` →
  the current step runs to `turn.step.completed` → `context.spliced` →
  `turn.steer` → a new `turn.step.started` in the same turn → `turn.ended` →
  a single `prompt.completed` with the **original** prompt id. The app
  ignores `prompt.steered`/`turn.steer` entirely, never local-echoes a sent
  prompt (history is splice-driven, so the single-steer id reuse cannot
  duplicate anything), and `MessageOriginDto` reads only `kind` — no ticket.
- **Provider config/catalog fields** (commits `c5ad17f0`, `92c3c59b` —
  source-reviewed; these routes are out of scope here): provider config
  responses may carry an optional **`api_key_env`** (the name of an env var
  holding the credential), and the models.dev catalog provider items gained a
  required-nullable **`base_url`**. The app never calls the provider-catalog
  routes and reads `data.providers` with `ignoreUnknownKeys` — no ticket.
- **Behavioral, wire shape unchanged:** yolo permission mode now
  auto-approves bash commands the analyzer cannot parse (`86e08208` —
  dangerous verdicts still ask); session `:delete` no longer hangs while a
  turn is shutting down (`e3f48a22`); the search index sync caps at 500
  sessions per cycle (`5108cad9` — perf; `/api/v1/search` is a route the app
  never calls).
- **Undo behavior change** (TUI-only; source-derived from the upstream
  `sessions.test.ts` diff, not probed live): an `:undo` count of 2/3 reaching
  into a steered turn now answers `40911` instead of partially undoing. The
  app has no undo.
- **Wire-invisible here:** `7dc253c5` restores thinking for the openrouter
  reasoning dialect (engine↔LLM `reasoningKey` — verified absent from this
  run's WS frames), `f233f9de`/`5acc863a`/`1fddc16e`/`910aba27` touch tool
  prose and system-prompt text only, `a80fe31c` is workspace watchers,
  `b0d0a80c`/`bbcdde0b` telemetry plumbing, `c1047a6b` CLI print mode, and
  `25dd4ce9`/`00bd3c22`/`caf7d4e2` are CLI/docs/CI churn.

Everything else re-verified unchanged live on 2.0.1 (see the verification
log): auth 401s (missing + wrong token), `/api/v1/meta` (5 flags, 17 features
— only `server_version` differs), the models catalog (10 entries), config
(`default_model` `kimi-code/k3-256k`, `thinking.effort` `"max"`,
`default_permission_mode` `"yolo"`), sessions create/get/list (`last_seq` `1`
on a fresh detail, `0` on the list), `40401` without `stack`, `/status`, the
snapshot (top-level keys, real `session.usage`, the sub-agent roster), the
full prompt round-trip skeleton (no `streaming` phase), the questions flow
end-to-end (pending poll, answer → `resolved:true`, re-answer/re-dismiss
`40902`, dismiss `40909` + `dismissed:true`, all three `event.question.*`
frames), the manual-approval round-trip (`approval_<uuid>` split,
`tool_call_id` POST → `40404`, both resolved frames, the snapshot item),
files (upload, download 200, `Range` 206, delete, `40407` at HTTP 404 after
delete, the `session_media` projection with `name` in the ACK and history,
the media copy surviving the upload's delete), the TodoList contract
(verbatim `{"todos":[{status,title}]}`), foreground + background sub-agent
lifecycles (verbatim `subagent.cancelled`, unchanged shape), REST `/tasks`
list/get `with_output`, the v2 grouped session table, the v3 spot-check (WS
hello `protocol_version:"3"` + `/history` `{messages, has_more}`), the
lifecycle events (`event.session.created` at seq 1, `event.session.deleted`
with camelCase `sessionId`), WS hello/subscribe/ack/cursor-resume
(`protocol_version: 2`, `heartbeat_ms` 10000; replay before the ack;
future-seq cursor → standalone `resync_required` `epoch_changed` then the ack
listing it), compact (instruction echo, wholesale splice with the id-less
`compaction_summary` + `compaction_continuation`, `tokensAfter` 31496 ==
post-compact `context_tokens`, both `40910`s with `stack`), and the
transcript's steered frames keeping `promptIds`. **App impact: none — every
2.0.1 change is additive on payloads the app either ignores wholesale or
parses with `ignoreUnknownKeys`. No tickets filed.**

The drift from 0.43.1 to 2.0.0 is **additive-only for the core loop — one new
WS event (`subagent.cancelled`), one new prompt-POST field (`metadata`), one
event-order swap (`prompt.steered` before `turn.steer`), and one
routinely-present optional step field (`llmClientBlockedMs`); nothing breaks
the Android app, which ignores unknown event types and fields**. The upstream
diff between the two tags (29 commits, reviewed via the GitHub API) touched
the client-facing wire in exactly these places:

- **New WS event `subagent.cancelled`** (commit `7d174ac9` — subagent scope
  LRU + the new event; verified live 2026-09-17 — see Sub-agents): payload
  `{type, time, subagentId, agentId:"main", sessionId}`. Verbatim frame:

  ```json
  {"type":"subagent.cancelled","seq":25,
   "payload":{"type":"subagent.cancelled","time":1789673862080,
              "subagentId":"agent-0","agentId":"main","sessionId":"session_..."}}
  ```

  It fires on REST `:cancel` of a running foreground AND background sub-agent
  — previously cancels produced no terminal `subagent.*` event at all.
  Observed sequences: foreground cancel → `prompt.aborted` →
  `subagent.cancelled` → the Agent `tool.result` (`status: failed`,
  `stop_reason: cancelled`, `isError:true`); background cancel →
  `subagent.cancelled` → `task.terminated` (`status:"killed"`) →
  `background.task.terminated` → a task-origin notification turn +
  `task.notified` (`notificationType:"task.killed"`). After a cancel the
  snapshot `subagents` roster entry reads
  `"status":"cancelled","subagent_phase":"cancelled"` — `cancelled` is a new
  live-observed roster phase enum value (see the snapshot roster) — and REST
  `/tasks/{id}` shows `status:"cancelled"`. **The Android data layer already
  parses `subagent.cancelled` defensively and maps roster phase `"cancelled"`
  — no ticket.**
- **Prompt POST `metadata` field** (commit `41eac5d2`; verified live
  2026-09-17 — see Prompts): an arbitrary client JSON object accepted in the
  prompt POST body; echoed on the HTTP ACK as `data.metadata`; rides
  `prompt.submitted`/`prompt.queued` as `clientMetadata:[<metadata>]`, the
  `context.spliced` origin, history `metadata.origin.clientMetadata`, and the
  transcript prompts[] / user-turn origin payloads. Absent everywhere when not
  sent. Additive; the app sends none and ignores unknown fields — no ticket.
- **Steer event order swap** (commit `2bb6e12c`; verified live 2026-09-17 —
  see Steer): `prompt.steered` now precedes `turn.steer` (observed seq 42 then
  43; was the reverse since 0.43.0). Everything else about steer is unchanged
  (`queueLength` on `prompt.queued`, the fresh-ULID splice, same-turn
  continuation, `prompt.completed` with the original prompt id). The app
  treats both frames as order-insensitive notifications — no ticket.
- **`turn.step.completed` now routinely carries `llmClientBlockedMs`**
  (schema-optional since 0.43.1; first live observation this run — every step
  frame of this run's round-trips carried it, e.g. `"llmClientBlockedMs":47`).
  Additive; ignored — no ticket.
- **Doc correction (not 2.0.0 drift):** the legacy `GET /api/v1/files/{id}`
  route answers **HTTP 404** with body `{"code":40407,"msg":"file not found"}`
  for a deleted file — this doc previously claimed an HTTP-200 envelope. The
  route source is identical at tags 0.43.1 and 2.0.0; the old claim was wrong
  (verified live 2026-09-17 — see Files). The media download route behaves
  the same (HTTP 404 + `40407`).
- **Tools catalog content drift** (server-config only, not wire shape): 38
  tools on an initialized agent (28 builtins incl. `ReadMediaFile` + 10 MCP —
  tavily gained `tavily_feedback`, kiln has 4); 37 on a fresh never-prompted
  session (27 builtins + 10 MCP). See Tools.
- **Wire-invisible here:** image uploads now ride to the LLM as file refs
  (`5653c739` — engine↔LLM internal; the prompt-side `session_media`
  projection re-verified byte-identical), kosong gained optional media names
  (`540133f0`), four transcript-projection fixes landed (`1c7e996a`,
  `cafd9b5a`, `0cf413f7`, `bd061789` — history rendering internals), and the
  remaining commits are wire-invisible refactors.

Everything else re-verified unchanged live on 2.0.0 (see the verification
log): auth 401s (missing + wrong token), `/api/v1/meta` (5 flags, 17 features —
only `server_version` differs), the models catalog (10 entries, same content),
config (`default_model` `kimi-code/k3-256k`, `thinking.effort` `"max"`,
`default_permission_mode` `"yolo"`), sessions create/get/list (`last_seq`
real on the detail path — `1` fresh; list still `0`), `40401` envelopes
without `stack`, `/status` (fresh session `thinking_level` `""`,
`max_context_tokens` 262144), the full snapshot (top-level keys, real
`session.usage`), the full prompt round-trip skeleton (`session.meta.updated`
→ `prompt.submitted` → `turn.started` with `promptId` → `context.spliced` →
`prompt.started` → steps → `tool.*` → `turn.ended` → `prompt.completed`; no
`streaming` phase; the `date_change` injection), mid-run and idle `:abort`,
compact with `{"instruction":…}` (instant ACK, instruction echo, wholesale
splice with `compaction_summary` + `compaction_continuation`, `tokensAfter`
== post-compaction `context_tokens`, both `40910`s), the questions flow
end-to-end (pending poll, answer, dismiss `40909`, re-answer/re-dismiss
`40902`, the `multi_select` omission rule), the manual-approval round-trip
(`approval_<uuid>` split, `tool_call_id` POST → `40404`, both resolved
frames), files (upload with sniffed `media_type`, download 200, `Range` 206,
delete, the image `session_media` projection with `name`, the media copy
surviving the upload's delete), the TodoList contract, foreground +
background sub-agents (full lifecycles: `subagent.spawned` with
`taskId`/`model`/`thinkingEffort:"max"`, `subagent.completed` with
`resultSummary`/`usage`/`contextTokens`), REST `/tasks` list/get
`with_output`, the v2 grouped session table, the v3 spot-check (WS hello
`protocol_version:"3"` + `/history` `{messages, has_more}`), the session
lifecycle events (`event.session.created` at seq 1, `:delete` →
`event.session.deleted` with camelCase `sessionId`), and WS
hello/heartbeat/subscribe/ack/cursor-resume (`protocol_version: 2`,
`heartbeat_ms` 10000; replay before the ack; future-seq cursor → standalone
`resync_required` `epoch_changed` then the ack listing it). **App impact:
none — the Android data layer already parses `subagent.cancelled` defensively
and maps roster phase `"cancelled"`; prompt `metadata`, the steer order swap
and `llmClientBlockedMs` are all ignored safely (`ignoreUnknownKeys`). No
tickets filed.**

The drift from 0.43.0 to 0.43.1 is **nil for the core loop — one behavioral
search fix on a route the app never calls, everything else engine-internal
or out of scope**. The upstream diff between the two tags (14 commits, most
of it TUI/web-bundle/oauth churn) changed the client-facing wire in exactly
one place:

- **Indexed search results are now verified against session sources**
  (commit `1336be38`; verified live 2026-09-15 — see Search): hits whose
  source session no longer exists are filtered at query time. A throwaway
  session's unique token was findable via container search while the
  session lived; after `:delete` the token never surfaced in the global
  index across two refresh cycles, while a live session's identical token
  was indexed and found normally (user + assistant hits). The
  request/response shapes and `index_state` are unchanged (`stale:true`
  observed across the refresh cycles; no multi-page token walk this run).
  **The Android app never calls `/api/v1/search` — no ticket.**
- **Wire-invisible here:** the session event bus was sharded by agent id
  (`f2486241` — the v1 broadcaster file and the bus publish path are
  byte-identical between the tags; this run's capture re-observes the
  documented sub-agent `prompt.completed` frames), swarm cancellation no
  longer crashes the CLI (`82ec469e`), a tower-mode roster identity fix
  (`0725ef1c` — the `tower` flag is `false` here), a
  `[secondary_model].default_effort` config validation (`513e71ed`), the
  managed-usage OAuth payload moved to the quota model (`bb0c66a1` — the
  `/oauth` routes are out of scope), and the pi-tui re-baseline
  (`486dcd26`) plus web-dist sync (`26a28419`).
- **First-time live itemizations this run** (all additive, all predate
  0.43.1 in the engine source — recorded because earlier runs did not call
  them out): the compact splice's trailing `compaction_continuation`
  injection (see Compact), a single-select question item carrying
  `"multi_select":false` verbatim from the model's tool input (the
  omission rule is input-dependent — see Questions), and a cross-turn
  question re-resolve answering `40405` where a same-turn one answers
  `40902` (see the verification log).

Everything else re-verified unchanged live on 0.43.1 (see the verification
log): auth 401s (missing + wrong token), meta (5 flags, 17 features —
byte-similar apart from `server_version`), models catalog (10 entries, same
content), config (`default_model` `kimi-code/k3-256k`, `thinking.effort`
`"max"`, `default_permission_mode` `"yolo"`), sessions create/get/list
(`last_seq` real on the detail path — `1` fresh; list still `0`), `40401`
envelopes without `stack`, both compact `40910`s with `stack`, `/status`,
the full snapshot (top-level keys, real `session.usage` omitting
`total_cost_usd`/`turn_count`, busy `in_flight_turn` with `running_tools`,
empty pending/roster lists), messages history (newest-first), prompts POST
+ ACK (`prompt_id` == `user_message_id`), one full prompt round-trip (the
documented skeleton, no `streaming` phase), prompt queueing + steer
(`prompt.queued` `queueLength`, `turn.steer` `{input, origin}`,
`prompt.steered`), mid-run `:abort` (full interrupted sequence) plus idle
`:abort` `{"aborted":true}`, compact with `{"instruction":…}` (instant ACK,
instruction echo, wholesale splice, `tokensAfter` == post-compaction
`context_tokens`, the persisted `compaction_summary` message), the
questions flow end-to-end (`question_<uuid>` ≠ `tool_call_id`, pending
poll, `single` answer → `{"resolved":true}`, dismiss `40909`,
re-dismiss/re-answer `40902`, all three `event.question.*`), the
manual-approval round-trip (`approval_<uuid>` ≠ `tool_call_id` on all
surfaces, `tool_call_id` POST → `40404`, `approved` → both resolved frames,
snapshot `pending_approvals` item), files upload/download/`Range`
206/delete/`40407` plus the image `session_media` projection with `name` in
the ACK echo AND history, the upload-id reuse, and the media copy surviving
the upload's delete, foreground + background sub-agents (full lifecycles:
`subagent.spawned` with `taskId`/`model`/`thinkingEffort:"max"`,
`subagent.started`/`completed`, `task.started` → `background.task.started`
→ `task.terminated` → `background.task.terminated`, mid-turn notification
splice + `task.notified`, REST `/tasks` list with all the
0.39.0/0.36.1 fields), the TodoList contract
(`{"todos":[{status,title}…]}` verbatim), the tools catalog (37 entries on
an initialized agent — 28 builtins incl. `ReadMediaFile` + 9 MCP; 36 on a
fresh never-prompted session, the documented liveness caveat), the v2
grouped session table, the v3 protocol spot-check (`WS /api/v3/ws` hello +
`GET .../history`), the session lifecycle events (`event.session.created`
on the new session's own stream, `:delete` → `{"deleted":true}` + global
`event.session.deleted`), and WS hello/heartbeat/subscribe/ack/
cursor-resume (10 s pings; replay from seq 236 re-delivered 237+ in order
before the ack; future-seq cursor → standalone `resync_required` then the
ack listing it). **App impact: no breakage, no new tickets.**

The drift from 0.42.0 to 0.43.0 is **small for the core loop — one removed WS
phase kind (`streaming`), one removed error code (`40903`), one new event type
(`turn.steer`), and an additive opt-in v3 protocol** the app does not use. The
upstream diff between the two tags (38 commits, much of it agent-core-v2
loop/LLM-pipeline refactoring and web-bundle churn; `packages/kap-server`
gained the v3 transport + history service (`64505e36`) and the
prompt-queue/error-surface rework (`46233953`)) touched the client-facing
wire in exactly these places:

- **New opt-in v3 protocol** (commit `64505e36`; verified live 2026-09-14 —
  see WebSocket → The v3 protocol): a separate flat-entity WS endpoint
  `WS /api/v3/ws` (hello `{"type":"hello","protocol_version":"3",...}` —
  note the string version and the `hello` frame type) and a new history
  route `GET /api/v1/sessions/{id}/history` serving the same flat entity
  messages (`{messages, has_more, in_flight?}`). **Fully additive: the
  `/api/v1/ws` v2 protocol is untouched** (`server_hello` still
  `protocol_version: 2`; subscribe/ack/cursor-resume re-verified
  byte-identical). The app uses neither — no ticket; it is a potential
  future migration target (the flat entities match the app's consumption
  style better than v1's nested events).
- **`agent.status.updated` lost the `streaming` phase kind** (commit
  `56ed9549`, "remove AgentActivityView and project v1 agent phase at
  kap-server edge"; verified live 2026-09-14 — zero `streaming` phases
  across this run's full round-trips, deltas streaming throughout; the
  phase stays `running` while tokens stream): the phase is now projected
  at the kap-server edge from domain events (`LegacyActivityTracker`, with
  dedup); the remaining kinds are `idle`/`running`/`tool_call`/`retrying`/
  `awaiting_approval`/`interrupted`/`ended`. **The app only consumes the
  model/usage variant (token counts, ticket 041) and never modeled the
  phase kinds — no ticket.**
- **`:abort` on an idle session no longer errors** (commit `46233953`;
  verified live 2026-09-14): the `40903` `prompt.already_completed` code
  is removed from the error enum and both prompt routes — aborting an
  idle session (or an already-completed prompt) now answers
  `{"code":0,"data":{"aborted":true}}` instead of
  `{"code":40903,"data":{"aborted":false}}`. The same commit **removed the
  prompt-POST auth prechecks**: `AUTH_PROVISIONING_REQUIRED`,
  `AUTH_TOKEN_MISSING`, `AUTH_TOKEN_UNAUTHORIZED` and
  `AUTH_MODEL_NOT_RESOLVED` are no longer returned at submit time — auth
  failures surface inside the turn stream instead. The app references none
  of these codes — no ticket.
- **New WS event `turn.steer`** (first live capture 2026-09-14 — the type
  sat in the v1 broadcaster's forward list since 0.42.0 but was never
  observed on the wire before; the engine now emits it when a steer is
  applied to the turn, from the prompt-queue fold `46233953`): fires right
  before `prompt.steered`, carrying `{input: [<content parts>], origin:
  {"kind":"user"}}` (see Steer). The rest of the steer sequence is
  unchanged. Additive; the app ignores unknown frames — no ticket.
- **`GET .../messages` omits hidden thinking summaries** (commits
  `2d4d799a`/`cd0d4a1d`; verified live 2026-09-14 that kimi-code/k3
  thinking parts project exactly as before): the v1 history fold now
  filters `think` content parts flagged `hidden` — the OpenAI-compat
  `reasoning_details` summary duplicates that rode next to
  `reasoning_content`. The app treats thinking parts as optional — no
  ticket.
- **AI session titles graduated** (commit `6126472c`): the
  `auto_session_title` experimental flag is **gone from `/meta`** (5 flags
  now — see Meta) and title generation is always on; no new WS event (the
  existing `session.meta.updated` broadcast is reused). No live title
  rewrite observed this run — both throwaway sessions kept their
  first-prompt titles with `isCustomTitle:false`. The app already handles
  `session.meta.updated` — no ticket.
- **`40401` envelopes dropped `stack`** (verified live 2026-09-14 on both
  a deleted-session get and a never-existed-session get; the `40910`
  compact errors still carry `stack` — the error transport is unchanged,
  the session-not-found error instance simply no longer has one captured).
  The app maps envelope codes and never reads `stack` — no ticket.
- **New injection origin variant `date_change`** (verified live
  2026-09-14): the date-change reminder is a whole-message
  `<system-reminder>` user injection carrying
  `origin: {"kind":"injection","variant":"date_change","disclosure":
  {"kind":"date","renderGeneration":1,"localDate":"2026-09-14","timeZone":"UTC"}}`
  — spliced live on the session's first turn of the day and persisted in
  history (verbatim capture in Messages). The app's tag-based envelope
  stripper covers it — no ticket.
- **Foreground Agent tool-result prose gained lines** (verified live
  2026-09-14): a `stop_reason: completed` line after `status:` and a
  trailing `resume_hint:` block (the background result's `task_id:`-first
  text is byte-similar to 0.42.0). Tool prose, not wire shape — the app
  links background tasks from the tool-call args, not the result text.
  No ticket.
- **Behavioral, shapes unchanged:** steer now interrupts a pending
  `WaitFor` tool wait early (`5e452fc2` — the WaitFor `tool.result`
  arrives immediately on steer, then the normal steer flow); `rm -rf`
  whose operands all sit under `/tmp`/`/temp` no longer triggers the
  dangerous-command approval (`565093f7`); turn/step id generation was
  centralized (`68f8984a` — same id formats in the same payload
  locations); the model-catalog runtime rewire (`27b7349e`) and the
  event-sourced human-agent/wire-persistence rework (`aad4a7df`/
  `7d7e8de8`) are wire-invisible; task notifications are suppressed before
  loop quiescence on remove (`221d112f`) and late task settlement stays
  silent after teardown (`c250a31d`). The loopback-only debug xstate
  stream `WS /api/v1/debug/ws` (`6299cfda`) is **not registered on this
  deployment** (verified live — handshake refused).
- **Config drift (not shape):** `default_model` reads `kimi-code/k3-256k`
  again (was `kimi-code/kimi-for-coding` on 0.42.0) — a fresh model-less
  session's `/status` `max_context_tokens` stays **262144**;
  `thinking.effort` is still `"max"`. `loop_control` may now carry
  `compaction_max_attempts` (int ≥1, default 5 — commit `775a6c35`;
  absent here). The models catalog still reads 10 entries, with content
  churn: `kimi-code/kimi-for-coding` is now "K2.8 Preview" with
  `max_context_size: 1048576` and gained `support_efforts`/
  `default_effort: "max"` (see Models); the new `dynamically_loaded_tools`
  capability string (commit `9296e687`) rides on `kimi-for-coding`, `k3` and
  `k3-256k` here, and MCP server configs may gain an optional `deferred`
  flag (not observed here).

Everything else re-verified unchanged live on 0.43.0 (see the verification
log): auth 401s (missing + wrong token), `40401` envelopes (sans `stack`
now, above), v1 sessions create/get/list (`last_seq` real on the detail
path — `1` fresh, `217` after this run's turns; list still `0`),
`/status`, the full snapshot (top-level keys, real `session.usage`
omitting `total_cost_usd`/`turn_count`, busy `in_flight_turn` capture,
oldest-first message page, empty pending/roster lists), messages history
(newest-first; media parts with `name`; the `date_change` injection),
prompts POST + ACK (`prompt_id` == `user_message_id`), one full prompt
round-trip (`session.meta.updated` → `prompt.submitted` → `turn.started`
with always-on `promptId` → `context.spliced` → `prompt.started` →
steps/deltas → `tool.call.started`/`tool.progress`/`tool.result` →
`turn.ended` → `prompt.completed` — no `streaming` phase anywhere),
prompt queueing + steer (`prompt.queued` `queueLength`, the new
`turn.steer`, `prompt.steered`, fresh-ULID splice, `prompt.completed` with
the original id), `:abort` (`{"aborted":true}`, `turn.step.interrupted` →
`turn.ended` `cancelled`/`user_cancelled` → interruption-reminder splice →
`prompt.aborted`), compact with `{"instruction":…}` (instant empty ACK,
instruction echo in `compaction.started`, wholesale `context.spliced` —
summary message still without an `id` key, the 0.42.0 prefix sentence
intact —, `compaction.completed` with the full `result` block,
`tokensAfter` == post-compaction `context_tokens`; both `40910`s with
`stack`), the questions flow end-to-end (`question_<uuid>` + distinct
`tool_call_id`, pending poll, `single` answer → `{"resolved":true}`,
dismiss `40909`, re-answer/re-dismiss `40902`, all three
`event.question.*`), the manual-approval round-trip (`approval_<uuid>` ≠
`tool_call_id` on all three surfaces, `tool_call_id` POST → `40404`,
`approved` → both resolved frames, snapshot `pending_approvals` item),
files upload (sniffed `media_type`)/download/`Range` 206/delete/`40407`
after delete, the image `session_media` projection with `name` in the ACK
echo AND history plus the byte-identical session-media route, foreground +
background sub-agents (full lifecycles: `subagent.spawned` with
`taskId`/`model`/`thinkingEffort:"max"`, `subagent.started`/`completed`,
`task.started` → `background.task.started` → `task.terminated` →
`background.task.terminated`, `task.notified`, the mid-turn notification
splice AND the idle-path task-origin notification turn — both captured),
REST `/tasks` list/get `with_output` (all the 0.39.0/0.36.1 fields;
a running foreground sub-agent listed with `run_in_background:false`),
the TodoList contract (`{"todos":[{status,title}…]}` verbatim), the v2
grouped session table (`groups[]`, `activity.model`), the tools catalog
(37 entries — 28 builtins + 9 MCP, `NotifyUser` still gated off), the
session lifecycle events (`event.session.created` on the new session's own
stream, `:delete` → `{"deleted":true}` + global `event.session.deleted`),
and WS hello/heartbeat/subscribe/ack/cursor-resume (replay from seq 212
re-delivered 213–217 in order before the ack; future-seq cursor →
standalone `resync_required` then the ack listing it).

The drift from 0.41.0 to 0.42.0 is **small for the core loop — one new
capability (session deletion), one new global WS event, additive fields
elsewhere**. The upstream diff between the two tags (55 commits, much of it
the agent-core-v2 human-layer absorption (`485594f6`), the legacy
agent-core v1 removal (`bb16383a`), and web-bundle churn; the
`packages/protocol` package itself was **deleted** — `9188d03c` relocated
its wire types into `packages/agent-core-v2`, so that folder is the
ground-truth reference from 0.42.0 onward) touched the client-facing wire
in exactly these places:

- **Session deletion is exposed now** (commit `fa87d040`; verified live
  2026-09-09 — see Sessions → Delete): `POST /api/v1/sessions/{id}:delete`
  answers `{"code":0,...,"data":{"deleted":true}}`; unknown session →
  `40401` (with `stack`); the bare `DELETE` HTTP method still answers plain
  404. A **new global WS event `event.session.deleted`** rides the
  `__global__` envelope exactly like `event.session.archived` — payload
  `{workspace_id, agentId, sessionId}` with the real id camelCase
  (verbatim capture in WebSocket → Global events). Deleted sessions drop
  off the list and answer `40401` on get. **The Android app's session-list
  socket handles both removal events since ticket 126: an archived or
  deleted row leaves the list live, without a manual refresh.**
- **Media attachment names are preserved** (commit `baf17a8f`; verified
  live 2026-09-09): image/video content parts in the prompt ACK echo AND
  `GET .../messages` history now carry an optional **`name`** — verbatim
  live capture: `{"type":"image","source":{"kind":"session_media","file_id":"f_…"},"name":"one.png"}`
  (both surfaces). Additive; the app decodes with `ignoreUnknownKeys` and
  does not display attachment names — no ticket.
- **HEIC/HEIF/BMP acceptance is not reachable via the upload flow**
  (commit `80480c01`, engine-side for Kimi models; verified live
  2026-09-09): the upload sniffer still returns
  `application/octet-stream` for a BMP, so an `image` part referencing the
  upload is rejected up front with `40001` (`file f_… is
  application/octet-stream, not an image`) — the same caveat as video
  since 0.29.0. No wire shape change. `ReadMediaFile` is **back in the
  tools catalog** on this server (28 builtins now — see Tools).
- **`/meta` flags graduated and features churned** (commits `f0434f2d`,
  `e831fd1e`, `55685c58`; verified live 2026-09-09): `experimental_flags`
  reads only 6 entries — `remote-control`, `persistence_minidb_readmodel`,
  `search_worker` and `secondary-model` are **gone** (graduated/removed),
  `notify_user` is **new** (`false` here — gates the new `NotifyUser`
  tool, which is therefore NOT advertised in the tools catalog).
  `features` reads 17 entries — **`contextBudget` is gone** (the context
  budget reminders were removed, `55685c58`) and **`notify` is new**.
  The app reads only `code`/`msg` off `/meta` — no ticket.
- **Compaction** (commit `f12d59e0`; verified live 2026-09-09): the splice
  prefix gained a final sentence — "**The summary records which earlier
  requests were already addressed.**" (full verbatim capture in Compact);
  the summarizer instruction file was reworded (note → summary, the
  first-person handoff framing dropped — content, not wire); compaction
  resumption is anchored on the latest user message (behavioral). All
  shapes and the WS sequence unchanged.
- **Behavioral, shapes unchanged:** deduplication of repeated tool-call
  ids in the loop (`00265b09`/`ccf3d5d6`), text-delta merging across empty
  reasoning parts (`73ff2bf6`), event-journal writes stop after server
  close (`c1cac343`), skill-root watching (`fb0353a8`). The huge
  human-layer/loop-facade refactor (`485594f6`) is invisible on the wire —
  the full round-trip skeleton fired in the documented order.
- **Invisible here / out of scope:** the remote-control runtime toggle API
  (`48f5dc97` — remote control is out of scope), the legacy agent-core v1
  removal (`bb16383a`), the v2-turn-boundary SDK fix (`f1e91527`), the
  updater zstd manifest (`f8c606e7`).
- **Config drift (not shape):** `default_model` reads
  `kimi-code/kimi-for-coding` again (was `kimi-code/k3-256k` on 0.40.1) —
  a fresh model-less session's `/status` `max_context_tokens` is back to
  **262144**; `thinking.effort` is still `"max"`; the models catalog still
  reads 10 entries. A new `database` config section appeared (the minidb
  graduation, `f0434f2d`) — the app consumes none of it.

Everything else re-verified unchanged live on 0.42.0 (see the verification
log): auth 401s (missing + wrong token) and `40401` envelopes with
`stack`, v1 sessions create/get/list (`last_seq` real on the detail path —
`147` after this run's turns, list still `0`), `/status`, the full
snapshot (top-level keys, real `session.usage` omitting
`total_cost_usd`/`turn_count`, busy `in_flight_turn` capture, empty
pending/roster lists), messages history (incl. the new media `name`),
prompts POST + ACK, prompt queueing + steer (`{"steered":true}`,
`prompt.steered` frame), `:abort` (`{"aborted":true}` + `prompt.aborted`),
compact (both bodies, the WS sequence, the empty-session `40910` with
`stack`), the questions flow end-to-end (`question_<uuid>` + distinct
`tool_call_id`, pending poll, `single` answer), the manual-approval
round-trip (the `approval_<uuid>` split intact, `tool_call_id` POST still
`40404`, both resolved frames), files upload/download/`Range` 206/delete +
`40407`, foreground + background sub-agents (full lifecycles,
`model`/`thinking_effort` `"max"` throughout), REST `/tasks` list/get, the
TodoList contract (`{"todos":[{status,title}…]}` verbatim), the v2
grouped session table, the tools catalog, and WS
hello/heartbeat/subscribe/ack/cursor-resume (replay re-delivered 58–132 in
order before the ack; future-seq cursor → standalone `resync_required`
then the ack).

The drift from 0.40.1 to 0.41.0 is **small for the core loop — nothing the
app parses broke**. The upstream diff between the two tags (30 commits, most
of it telemetry/CLI/TUI/web-bundle churn; `packages/protocol` untouched, the
`packages/kap-server` diffs are one line dropping the file-history
experimental gate plus a logger/boot fix) touched the client-facing wire in
exactly these places:

- **`/meta`:** `experimental_flags` **dropped `file_history`** (the feature
  graduated — commit `eba23edb` removed the gate) and `features` reads 19
  entries: **`staleGuard` is gone** (feature removed, commit `a0209469`),
  **`contextBudget` is new** (commit `b199e332` — the model gets a
  context-budget reminder; no wire-visible event observed, the context was
  nowhere near the limit this run). Verified live 2026-09-04. The app reads only
  `code`/`msg` off `/meta` — no ticket.
- **The turn file-history routes are live now** (same commit `eba23edb`;
  verified live 2026-09-04 — see the Files section): no flag, and the
  current response schema has **no `enabled` field** — `{changes[],
  recorded}` only. The Android app never calls these routes.
- **Background questions survive turn end** (commit `523d35b5`; verified
  live 2026-09-04 — see Questions): an `AskUserQuestion` call with
  `background: true` stays pending after its turn ends (up to 0.40.1 it was
  cancelled at turn end), the session reads `busy: true` /
  `main_turn_active: false` / `pending_interaction: "question"` while it
  waits, and the answer is delivered as a **task-origin notification turn**
  (`task.terminated` with core kind `question`, `task.notified`
  "Background question answered", `turn.started` origin
  `{"kind":"task","taskId":"question-…",…}`) — the full background-task
  delivery path. REST `/tasks` lists it with wire kind **`tool`**. The
  background-question tool-result text shrank to
  `task_id:`/`status:`/`next_step:` lines (prose, not protocol).
- **Repeat-breaker settle reasons on sub-agent tasks** (commit `b6b9b374`;
  schema-level, not triggered live): a sub-agent stopped by the
  repeat breaker settles `completed` with a `stopReason` ("stopped by the
  repeat breaker after issuing the same tool call repeatedly; its output is
  a handoff, not a finished result") on `task.terminated`'s `info` — the app
  already decodes optional `stopReason` there. The upstream wire manifest
  also gained optional **`stopReason` on `turn.ended`** (forced stops only —
  every live `turn.ended` this run, completed and cancelled alike, omitted
  it), and the engine tracks a `stopCode` per sub-agent task that **no
  kap-server projection serves** (checked the 0.41.0 REST/snapshot/task
  sources — engine-internal state only).
- **Transcript: `triggerPromptId` is gone from sub-agent internal turns**
  (verified live 2026-09-04 — a regression from the 0.40.1 projection,
  presumably the `runAgentTurn` rewrite in `b6b9b374`): main-agent user
  turns still carry it, `system_trigger/subagent` turns no longer do. The
  app decodes the field nowhere — no ticket.
- **Behavioral, shapes unchanged:** the dangerous-command guard no longer
  applies in **auto** permission mode (commit `b184b314` — the policy
  returns early when the mode is `auto`; dangerous/unanalyzable commands run
  ungated there). Manual and yolo keep the 0.40.1 ask behavior. Expect
  *fewer* `event.approval.requested` frames in auto — the app already
  handles both.
- **Config drift (not shape):** the models catalog gained two
  `Kimi Platform/*` entries (10 total: 4 `kimi-code/*`, 2 `Kimi Platform/*`,
  4 `deepseek/*`) — provider-side content, not protocol.

Everything else re-verified unchanged live on 0.41.0 (see the verification
log): auth 401s, meta otherwise, v1 sessions list/create/get (`last_seq`
real on the detail path — a fresh session reads `1` now, `20` after turns;
the list still emits `0`), `/status`, the full snapshot (top-level keys,
real `session.usage`, `in_flight_turn` busy capture, running roster entry),
messages history, prompts POST + ACK, one full prompt round-trip, prompt
queueing + steer (`steeredAt` provenance + `promptIds` text frames intact),
`:abort`, compact (both bodies, the WS sequence, both `40910`s with
`stack`), the questions flow end-to-end (foreground surface/poll/answer/
dismiss + the background lifecycle above), the manual-approval round-trip
(the `approval_<uuid>` split intact; `tool_call_id` POST still `40404`),
files upload/download/`Range`/delete + path attachments, foreground +
background sub-agents, REST `/tasks` list/get, the TodoList contract, the v2
grouped session table, and WS hello/heartbeat/subscribe/ack/cursor-resume
(both the replay and the refused-cursor paths).

The drift from 0.39.1 to 0.40.1 is **small and additive for the core loop —
nothing the app parses broke**. The upstream diff between the two tags (56
commits, much of it CLI/vscode/web-bundle churn; `packages/protocol` touched
in exactly one schema line; the `packages/kap-server` diffs are the new
routes below, the `last_seq` one-liner, and DI-refactor churn) touched the
client-facing wire in exactly these places:

- **`GET /api/v1/sessions/{id}` reports the real event watermark in
  `last_seq`** (commit `0f39b2cf`; verified live 2026-09-02): a fresh
  session's first get already reads `"last_seq": 4`, a session after two
  turns reads `20` — up to 0.39.1 the field was hardcoded `0` on every read
  path. The **list endpoint still emits `0`** (the fix wires only the detail
  path; fork/child responses too). The app never reads `last_seq` (its WS
  resume cursor comes from the snapshot's `as_of_seq`) — no ticket.
- **New route `POST /api/v1/workspaces/{id}/add-dir`** (commit `a23ff563`;
  verified live 2026-09-02 — shapes and all three error paths in the
  Workspaces section). The app only ever lists workspaces — no ticket.
- **Transcript prompt-identity additions** (commits `cbe0a77f`/`58b74cfe`;
  all verified live 2026-09-02 — see Transcript): every turn carries
  optional **`triggerPromptId`** (the prompt record id that opened the
  turn); user-role text frames gained optional **`origin`**
  (`{"kind":"user"}`, schema also allows `skillActivations` provenance —
  not triggered); `prompts[]` entities gained **`steeredAt`**, and the
  steered-into prompt's `content` now shows the steered text (steer
  provenance). Cold replays can additionally mark steps
  `state:"interrupted"` with `endReason`/`endMessage` (commits `742e1197`/
  `9e881528` made the step-interrupt and prompt-resolution records durable —
  schema/projection only, not triggered live). All additive; the app decodes
  none of these fields.
- **WS `turn.started` now always carries `promptId`** (same prompt-identity
  work; verified live 2026-09-02 on a plain prompt with no client-sent
  `prompt_id`: `"promptId":"msg_01M1HT2N…"` rides next to `origin`/`prompt`).
  Up to 0.39.1 the field appeared only when the submitter had sent
  `prompt_id` (and was absent in the 0.38.0 live capture). The app decodes
  only `agentId` from this payload — no ticket.
- **File-store "file not found" moved `40406` → `40407`** (verified live
  2026-09-02): `GET /api/v1/files/{id}` on a deleted file AND the
  session-media route on an unknown id both answer
  `{"code":40407,"msg":"file not found"}` (the session-media route keeps its
  plain-HTTP-404 transport; the legacy route its HTTP-200 envelope; neither
  carries `stack`). `40406` survives for unknown **tasks**
  (`"task … does not exist in session …"`, verified live). The app maps the
  binary 404 without reading the envelope code — no ticket.
- **`/meta`:** `experimental_flags` gained **`file_history: false`** (the
  experimental turn-level file-history snapshots, commit `650d12f6` — two
  flag-gated routes, source-derived shapes in the Files section) and
  **`secondary-model` flipped to `true`** (graduated out of experimental,
  commit `971a8b24`; a default, config can still override). `features`
  gained **`fileHistory`** (18 entries here). All additive; the app reads
  only `code`/`msg` off `/meta`.
- **`agent_config` gained optional `tower_base`** (string, min 1 — the
  release's only `packages/protocol` change, commit `c3bf6f9d` tower-mode
  rework): accepted and echoed wherever `agent_config` rides (session
  create/get/list, the agent-config route). Source-derived — not sent or
  observed live (the `tower` flag is `false` here). The same commit reworded
  the `SESSION_TOWER_MODE_INVALID` error message (code unchanged).
- **Behavioral, shapes unchanged:** dangerous bash commands now require
  approval in **all** permission modes (commit `4b9888b7`; new config key
  `permission.dangerous_command_guard`, default `true`) — expect
  `event.approval.requested` in yolo/auto where turns previously ran
  straight through. (**0.41.0 amendment, commit `b184b314`:** the guard no
  longer applies in **auto** mode — the policy returns early there, so
  dangerous/unanalyzable commands run ungated; manual and yolo keep the ask
  behavior. Verified in the upstream source.) The CLI's permission-mode
  rename (commit `08a2fb00`) is **labels-only** —
  the wire enum stays `manual`/`yolo`/`auto` (verified in the upstream
  schema; this server's `/status` still reports `"permission":"yolo"`).
  The label mapping, recorded once here and referenced from `/status`:
  `manual` = "Always Ask", `yolo` = "Ask When Needed", `auto` = "Never Ask"
  — non-positional (`yolo` is the middle option).
- **Config drift (not shape):** `default_model` reads `kimi-code/k3-256k`
  (fresh-session `/status` `max_context_tokens` is back to **262144**), and
  `thinking.effort` is `"max"` — so `thinking_level`, the
  `agent.status.updated` `thinkingEffort`, and every sub-agent
  `thinking_effort` field read `"max"` where the 0.39.x captures read
  `"high"`/`"low"`. Values, not protocol.
- **Invisible here:** the agent-runtime DI refactor (`15bf5855` — same
  routes/shapes), session forking without materializing the target
  (`9c37feb4`), and the remote-control relay env override (`55b422aa` —
  remote control is out of scope).

Everything else re-verified unchanged live on 0.40.1 (see the verification
log): auth 401s, meta, models catalog (27 builtins), v1 sessions
list/create/get, `/status`, the full snapshot (top-level keys, real
`session.usage`, `in_flight_turn`, pending-approval/question items, running
roster), messages history, prompts POST + ACK, one full prompt round-trip,
prompt queueing + steer (WS sequence + transcript projection), `:abort`,
compact (both bodies, the WS sequence, both `40910`s with `stack`), the
questions flow end-to-end, the manual-approval round-trip (the 0.39.0 id
split intact; `tool_call_id` POST still `40404`), files
upload/download/`Range`/delete + path attachments, foreground + background
sub-agents, REST `/tasks` list/get/`:cancel`, the TodoList contract, the v2
grouped session table, `event.config.changed` on a real config write, and
WS hello/heartbeat/subscribe/ack/cursor-resume (both the replay and the
refused-cursor paths).

The drift from 0.39.0 to 0.39.1 is **additive for the core loop — the one
breaking change is on a route the app does not call** (`/api/v1/auth`). The
upstream diff between the two tags (11 commits, mostly web-bundle and CLI
churn) touched the client-facing wire in exactly these places:

- **`GET /api/v1/auth` broke its shape** (commit `2bf7ed22`; verified live
  2026-08-28): the summary now reads
  `{"models_ready":true,"providers_count":2,"managed_provider":{"name":"managed:kimi-code","status":"authenticated"}}`
  — the `ready` and `default_model` fields are **gone** (config values come
  from `/config` alone; upstream flags it BREAKING). **The Android app never
  calls `/api/v1/auth`** (its credential check is `/meta`) — no ticket.
- **Server-local path attachments** (commit `4e7738b7`; every behavior below
  verified live 2026-08-28 — see Prompts and Files):
  - The image/video `source` union gained a 5th kind
    `{"kind":"path","path":"<server-local absolute path>"}`, and the `file`
    content part now takes **either** `file_id` (with `name`/`media_type`/
    `size` as before) **or** `path` (all three metadata fields optional —
    the daemon fills `name` from the basename, `media_type` defaults to
    `application/octet-stream`, `size` from stat). Both or neither → `40001`
    "exactly one of file_id or path is required".
  - Validation: a relative path → `40001` "attachment path must be absolute:
    …" (an Error2 envelope — it **carries `stack`**, unlike Zod `40001`s); a
    missing path → **`40407`** "file not found: …" (a new code on this
    route, distinct from the file store's `40406`). The sensitive-file check
    did NOT reject `/home/ubuntu/.kimi-code/server.token` (attached without
    complaint — do not rely on it).
  - A `path` **file** part rewrites to the same `Attached file "…"` notice
    text as uploads, but referencing the **original path in place** (no copy
    under `<sessionDir>/attachments/`); a `path` **image** is read,
    compressed if needed, and saved into the session-media store — the ACK
    echo and history project it as `session_media` exactly like an upload.
  - **Every** prompt file attachment (path or uploaded `file_id`) is now
    recorded on the prompt origin: the ACK echo and the history user message
    carry `metadata.origin: {"kind":"user","attachments":[{"name",
    "mediaType","size","path"}]}` (camelCase; `path` is the original path
    for `path` parts, the materialized `<sessionDir>/attachments/…` copy for
    uploads), and the WS `turn.started` origin carries the same
    `attachments` array. `turn.started.promptAttachments` is still NOT
    emitted live (the schema gained a `kind:"file"` variant, but the field
    never fired on any capture).
  - The transcript's global `attachments[]` now includes typed **file**
    entries — `{attachmentId, mediaType, name, size}` with **no `source`**
    (media entries keep `source:{kind:"session_media",fileId}`); attachment
    ids are inconsistent (`att_1` on the turn-0 entry, `t2.att1` later) —
    treat them as opaque.
- **Session list/get `agent_config.model` is real now** (commit `2bf7ed22`;
  verified live): a live session's v1 object serves its bound model
  (`"kimi-code/k3"`) instead of the hardcoded `""`; cold sessions keep `""`.
  The v2 table's `activity` gained a `model` field (`""` when unset). The
  app reads `agent_config.model` (ModelsRepository falls back to
  `default_model` when empty) — an improvement, no ticket.
- **`event.config.changed` and `event.model_catalog.changed` are actually
  delivered now** (commit `2bf7ed22`; `event.config.changed` verified live):
  both were published internally but never forwarded to WS clients before.
  Every config write pushes a global `event.config.changed` frame
  (`session_id:"__global__"`) with camelCase `changedFields` (e.g.
  `["thinking"]` — was the raw snake_case request keys, a breaking change
  for that event) and a **full config projection** with credentials redacted
  to `has_api_key` (providers/models/services). `event.config.warning`
  (delivered since 0.32.0) is now registered in the schema unions.
  `event.config.changed`/`event.model_catalog.changed` are handled since
  ticket 110 (models/config cache invalidation); `event.config.warning`
  stays unknown-frame territory the app ignores.
- **Invisible/behavioral:** the cold transcript now folds only user-origin
  steers by content (`dc6028dc` — the live projection is unchanged; not
  observed); a resumed session reminds the model once about background tasks
  killed by the previous app exit (`23921e9f` — engine-internal); the config
  response schema is now passthrough (no client-visible change).
- **Config drift (not shape):** `default_model` reads `kimi-code/k3` again
  (it was `deepseek/deepseek-v4-pro` since 0.37.2), so a fresh model-less
  session's `/status` `max_context_tokens` is back to **1048576**.

Everything else re-verified unchanged live on 0.39.1 (see the verification
log): auth 401s, v1 sessions list/create/get, `/status`, the full snapshot,
messages history, prompts POST + ACK, one full prompt round-trip, prompt
queueing + steer, `:abort`, compact (both bodies, the WS sequence, both
`40910`s), the questions flow end-to-end, the manual-approval round-trip
(the 0.39.0 id split intact; `tool_call_id` POST still `40404`), files
upload/download/`Range`/delete, foreground + background sub-agents, REST
`/tasks`, the TodoList contract, and WS
hello/heartbeat/subscribe/ack/cursor-resume.

The drift from 0.38.0 to 0.39.0 is **additive everywhere — no break for the
app**. The upstream diff between the two tags (66 commits, much of it the
agent-runtime refactor and web-bundle churn) touched the client-facing wire
in exactly these places:

- **Approval ids split from tool-call ids** (the interaction/agent-runtime
  refactors — `9a715820`/`4e16f2a8`; the route/service files are unchanged —
  verified live 2026-08-27): `permission.approval.requested` now carries a
  dedicated **`id: "approval_<uuid>"`**, the legacy
  `event.approval.requested` carries it as `approval_id`, and the
  `awaiting_approval` phase's `approval.approvalId` — all **distinct from the
  `tool_call_id`/`toolCallId`** (they were equal up to 0.38.0, same split as
  questions in 0.38.0). `POST .../approvals/{id}` now accepts **only** the
  `approval_<uuid>` id — posting the `tool_call_id` answers
  `{"code":40404,"msg":"approval tool_… not found"}` (verified live).
  **The Android app keys everything off the legacy `approval_id` field, so
  the split is transparent to it** — no ticket.
- **REST `/tasks` objects gained `run_in_background`** (commit `6595955b`):
  a boolean on every task, list and get, running and terminal
  (`false` on a running foreground sub-agent, `true` on background ones).
  `parent_tool_call_id` is now also emitted on `kind:"bash"` (process) tasks
  when set (commit `692bb0a4`), not only on subagent ones.
- **New task action `:detach`** (commit `692bb0a4`):
  `POST /api/v1/sessions/{id}/tasks/{task_id}:detach` converts a running
  **foreground** task into a background one →
  `{"detached":true,"status":"running"}`; already-background or terminal
  tasks answer `{"detached":false,"status":<current>}` (both verified live).
  The detach resolves the foreground `Agent` tool call immediately
  (`tool.result` stub: "note: The user moved this subagent to the
  background."), emits `task.started` + `background.task.started` for the
  task (foreground tasks have no `task.*` frames until detached), and the run
  then lives the full background lifecycle (`task.terminated`, notification
  turn). See the Tasks section. The app detaches from the sub-agent page's
  overflow ("To background", ticket 138).
- **`/status` gained `tower_mode`** (boolean; the new tower mode, commits
  `0f44537c`/`d3b27cc7`, flag `tower: false` here) next to
  `plan_mode`/`swarm_mode`. Additive; the app decodes none of the mode flags.
- **New WS event `prompt.started`** (the prompt-entities work, commit
  `2f124693`): fires when a submitted prompt actually starts executing —
  `{"type":"prompt.started","promptId":"msg_…","agentId":"main",…}`. Also
  correction: **`prompt.submitted` and `prompt.queued` exist since at least
  0.38.0** (present in the 0.38.0 broadcaster source; this document wrongly
  claimed `prompt.submitted` did not exist — the round-trip skeleton was last
  byte-verified on 0.31.0). `prompt.submitted` echoes the submission
  (`promptId`/`userMessageId`/`status`/`content`); `prompt.queued` carries
  `queueLength`. All additive; the app ignores them.
- **Transcript gained a top-level `prompts` array** (commit `2f124693`):
  prompt entities `{promptId, status, userMessageId, content, createdAt,
  finishedAt}` for the session's turn-opening inputs. Prompts compacted out
  of history vanish from it. Additive; the app doesn't decode it.
- **Steered messages now project as live user frames in the transcript**
  (commit `5634cb55`): the steered text appears as a
  `{"kind":"text","role":"user","text":…,"promptIds":["msg_…"]}` frame inside
  the running turn's next step (`promptIds` is new on text frames). Only the
  **main** agent's transcript is affected (steer targets the active main
  turn), and the app only renders sub-agent transcripts — no visible change.
- **Pending-question transcript entities carry the wire question shape**
  (commit `b3f08b68`): `interactions[]` question entries' `request` is now
  the full `event.question.requested` payload (stable `q_N`/`opt_N_M` ids,
  `question_id`, `tool_call_id`, `created_at`) instead of the raw
  `AskUserQuestion` tool input. Transcript `tasks[]` entities gained optional
  `model`/`thinkingEffort` (commit `2f124693`). The app decodes neither.
- **Sub-agent transcript turns now carry the orchestrator's `prompt`**
  (commit `f143130c`; verified live: `git-context` prefix + task text on
  `origin.system_trigger/subagent` turns) — previously the task text was not
  in the transcript turns at all. Additive.
- **The tools catalog now lists MCP tools** (the unified MCP management
  plane, commit `d723cc47`): entries with `source:"mcp"` and a new
  `mcp_server_id` field ride alongside the 27 builtins (36 here — count is
  server-config-dependent). New routes `GET /api/v1/mcp/servers` and
  `POST /api/v1/mcp/servers/{id}:restart` (see the Tools section). The app
  never calls the catalog.
- **`/meta` `experimental_flags` gained `subagent_fork: false`,
  `remote-control: false`, `tower: false`**; `features` gained `skill`,
  `cron`, `interaction`, `reminder` (17 entries). All additive.
- **Invisible here:** the `Agent` tool gained a `fork` parameter
  (`f6736d7c`, flag-gated) and `AgentSwarm` a `timeout_ms` (`496bb6ce`) —
  input schemas are not on this wire; oversized tool outputs now spill to
  files engine-internally (`4b044926` — no transcript/history shape change);
  prompt attachments fall back to a `kimi-file://` URL when the session-media
  projection is unavailable (`75c94c4a` — the verified upload round-trip
  still projects `session_media`); undone turns are dropped from the live
  transcript projection (`c4885860` — the app has no undo); the WS fs-watch
  moved to the engine service (`09858919` — `fs:*` is out of scope).

Everything else re-verified unchanged live on 0.39.0 (see the verification
log): auth 401s, v1 sessions list/create/get, `/status`, the full snapshot,
messages history, prompts POST + ACK, one full prompt round-trip, prompt
queueing + steer (WS sequence byte-similar, transcript projection called out
above), `:abort`, compact (both bodies, the WS sequence, both `40910`s), the
questions flow end-to-end (surface/poll/answer/dismiss + all three events;
the answer body's `kind`-tagged union predates 0.39.0 — the legacy
`{"selected":[…]}` shape is rejected with `40001`), the approvals round-trip
(the id split above), files upload/download/session-media/delete + the
`session_media` attachment projection, foreground + background sub-agents,
REST `/tasks` list/get/`:cancel`/`:detach`, the TodoList contract, WS
hello/heartbeat/subscribe/ack/cursor-resume, the tools catalog, and
`event.session.archived`.

The drift from 0.37.2 to 0.38.0 is **small, mostly additive — with one
behavioral break for the app** (the question-id split below). The upstream
diff between the two tags (38 commits) touched the
client-facing wire in exactly these places:

- **`/meta` gained `features`** (commit `cb8a7e5f`, "expose engine feature
  list on /api/v1/meta"): a `[{name, state, meta}]` list of the engine's
  feature units (all `"Active"` here — `btw`, `plan`, `externalHooks`,
  `goal`, `todo`, …). Additive; the app ignores it. `experimental_flags`
  also gained **`wait_for: true`** (the new `WaitFor` tool's switch).
- **`GET /api/v2/sessions` gained a workspace-grouped view** (commit
  `15da8460`): new params `meta.has_prompt` (`true` = the v1
  `exclude_empty` equivalent), `view=flat|by_workspace`, and
  `group.page_size` (1–100, default 5; only valid with the grouped view).
  Under `view=by_workspace` the page becomes `{groups:[{workspace,
  sessions[], total}], total, has_more, next_page_token}` — see the v2
  section. The Android app uses it since ticket 115 (the grouped-by-workspace
  session list).
- **New global WS event `event.session.archived`** (commit `15da8460`):
  fires on both the live and the cold archive path, no subscription needed;
  the envelope `session_id` is the global watermark `__global__` and the real
  session id rides in the payload as camelCase `sessionId`:
  `{"type":"event.session.archived","session_id":"__global__",...,"payload":{"type":"event.session.archived","workspace_id":"wd_…","agentId":"main","sessionId":"session_…"}}`
  (verified live 2026-08-20 via `POST /api/v2/sessions:archive`). There is no
  session-deleted event. The Android app handles it since ticket 105: the list
  socket reads the payload `sessionId` (the `__global__` envelope would fail the
  unsubscribed-session filter) and the session list drops the row live.
- **Snapshot `session.usage` is real now** (commit `4ff06f17`, "serve real
  session usage in snapshot"): the all-zero placeholder served since 0.32.0
  is gone — after a turn the snapshot reports the actual
  `input_tokens`/`context_tokens`/…. In the same commit the usage schema made
  **`total_cost_usd`, `context_limit`, and `turn_count` optional**: the
  snapshot's usage omits `total_cost_usd` and `turn_count` (the list/create/get
  session object still emits all eight fields). The snapshot `session` also
  gained **`agent_config: {model}`** (`""` when unset). See the Snapshot
  section. The app defaults every usage field, so omission decodes fine; it
  still takes the live context fill from `/status` + `agent.status.updated`.
- **`/status` `context_usage` is now optional** (same commit): omitted when
  the context limit is unknown, like `max_context_tokens` since 0.34.0. The
  app doesn't decode `context_usage` at all.
- **`turn.started` gained optional `promptAttachments`** (commit `38a5a934`):
  `[{kind:"image"|"video"|"audio", fileId}]` — the session-media references
  carried by the prompt, for transcript attachment projection. Schema-derived;
  **not observed live on 0.38.0** — a prompt whose image resolved to
  `session_media` still emitted `turn.started` without the field (and without
  `promptId`). Additive either way; the app ignores it.
- **`question_id` split from `tool_call_id`** (one of the 0.38.0
  interaction/agent-domain refactors — the route/service files are unchanged;
  verified live 2026-08-20): pending questions and `event.question.requested`
  now carry a dedicated `question_<uuid>` `question_id` plus a **distinct**
  `tool_call_id` (they were equal before). The answer/dismiss routes keyed on
  `question_id` work unchanged — **but the app's question wizard correlated a
  pending question with its transcript tool row via the old equality, so the
  row tap no longer reopens the card** — ticket 106.
- **`subagent.spawned` gained optional `taskId`** (commits `be8e0175` /
  `3b69765c`): the background-task id the run registered under, and the event
  now fires **after** task registration (so cancel/status actions can bind
  immediately); the duplicate launch-time `subagent.spawned` emission was
  removed. Verified live on foreground and background spawns. Additive; the
  app binds background tasks via `task.*` frames.
- **Every event payload now emits a top-level epoch-ms `time`** (observed on
  `assistant.delta`/`thinking.delta`, `subagent.*`, `task.*`, `tool.call.*`,
  `compaction.*` — extending the 0.37.2 `turn.*`/`prompt.completed` fields).
  All additive; the app ignores them.
- **Tool catalog:** the new **`WaitFor`** tool (commits `8440801d`/`01eeacb5`,
  flag `wait_for: true`) brings the catalog back to 27 entries, and
  **`ReadMediaFile` is no longer advertised** to non-multimodal models
  (commit `f13f3790`) — it is absent from this server's catalog.
- **Invisible here:** cron tasks are now persisted as durable wire records
  (`eac9ea88` — cron is out of scope), the multi-action routes were
  re-dispatched table-driven (`c843d3a7` — no wire change), and the rest is
  web-bundle/TUI/vscode/oauth/datasource work.

Everything else re-verified unchanged live on 0.38.0 (see the verification
log): auth 401s, sessions list/create/get/status, v2 flat table + batch
archive/restore, messages history, prompts POST + steer + `:abort`, the
questions flow mechanics (surface/answer/dismiss; the id drift is called out
above), compact (both bodies, the WS sequence, both
`40910`s), files upload/download/delete + session media, WS
hello/heartbeat/subscribe/ack/cursor-resume, one full prompt round-trip with
image attachments, foreground + background sub-agents, REST `/tasks`, the
TodoList contract, and the full snapshot.

The drift from 0.36.1 to 0.37.2 is **minor for the core loop, with one real
break**: the image/video source projection changed. The upstream diff between
the two tags (43 commits) touched the client-facing wire in exactly these
places:

- **Image/video `source` gained a 4th kind `session_media`** (commit
  `d833a1a8`, "engine-native image references via kimi-file:// media
  resolver"): history (`GET .../messages`) and the prompt ACK echo now project
  uploaded images and videos as
  `{"type":"image","source":{"kind":"session_media","file_id":"f_…"}}` instead
  of the 0.36.1 shapes (ACK base64 / history `url:data:…`). The `file` kind
  stays as the transient upload form accepted on submission. A new endpoint
  `GET /api/v1/sessions/{id}/media/{file_id}` serves the canonical
  Session-owned copy. **The Android app's image mapper only knows
  `base64`/`url`/`file`, so history/splice image parts are dropped** — ticket
  104. Verified live: a 1×1 png uploaded then prompted produced
  `kind:"session_media"` in both the ACK and history, and both
  `/api/v1/files/{f_…}` and `/api/v1/sessions/{id}/media/{f_…}` returned the
  70 bytes (the upload id is reused as the session-media id for uploaded
  files).
- **`turn.ended` gained optional `time`** (commit `3ded0808`, "expose turn
  ended event time") — an epoch-ms number in the payload, declared in the
  protocol schema. Live, `turn.started` and `prompt.completed` also emit a
  top-level `time` (epoch ms) without a matching schema field. All additive;
  the app ignores them.
- **`turn.started` gained optional `promptId`** (commit `d833a1a8`): the prompt
  record id echoed when the submitter sent `prompt_id`.
- **Prompt POST gained `skills` and `prompt_id`** (commits `d6021fa0`/
  `61591bce` and `d833a1a8`): `skills` is `[{name, args?}]` (bundled skill
  activations), `prompt_id` a client-chosen record id echoed on the ACK
  `prompt_id` / `user_message_id`, the history message id, and
  `turn.started.promptId`. Both optional; the app sends neither.
- **`/meta` gained optional `web_title`** (commit `09976b09`): present only
  when the server was started with `--web-title`; absent here (verified live).
- **New error code `40927`** (`PROMPT_ID_CONFLICT`, reason
  `prompt.id_conflict`) when a client-chosen `prompt_id` is already used in the
  agent's history.
- **`/api/v2/sessions` grew** (commit `eaa3969d`): a stateless 1-based `page`
  param, a `fields=id,archived` item projection (`page_size` ceiling relaxed to
  10000), `meta.updated_before`, a `total` count on every page, and new
  `POST /api/v2/sessions:archive` / `:restore` batch endpoints. The Android app
  does not use v2 (doc-only).
- **Config drift (not shape):** `GET /api/v1/config` now reports
  `default_model: "deepseek/deepseek-v4-pro"` and `thinking.effort: "high"`;
  the models catalog gained four `deepseek/*` entries
  (`max_context_size: 1000000`); a fresh model-less session's `/status` now
  reports `max_context_tokens: 1000000` (the default model's context, was
  262144). All values, not protocol.

Everything else spot-verified unchanged live: v1 session object/list/create/
get/status, WS `server_hello`/heartbeat/`subscribe`/`ack`, and one full prompt
round-trip emitting the complete event stream for two turns
(`turn.started`/`context.spliced`/deltas/`turn.ended`/`prompt.completed`).

The drift from 0.36.0 to
0.36.1 is again **minor** for the core loop — additive fields only. The
upstream diff between the two tags (25 commits) shows four kap-server commits,
of which two touch the client-facing wire, both on the **REST `/tasks`
responses** (list, get, `with_output`):

- **Subagent-kind task objects gained three optional fields** (commits
  `325913a5` and `cb30a779`): `agent_id` (the sub-agent registry id,
  `"agent-0"`-style — the same id the WS `subagent.*`/snapshot roster uses),
  `subagent_type` (`"explore"`, …), and `parent_tool_call_id` (the main
  agent's `Agent` tool-call id). All three are emitted only for
  `kind:"subagent"` tasks; bash/tool tasks omit them. Verified live —
  see the Tasks section. The doc's earlier note that the REST shape has "no
  `subagent_type`, no `parent_tool_call_id`" no longer holds.
- **Invisible here:** the other two kap-server commits are additive route
  additions outside the core loop — the plugin marketplace / capability REST
  routes (`741708f9`) and the 64 MiB web session-export limit removal
  (`eb72aebe`). The remaining commits are web-bundle, TUI, vscode, oauth,
  minidb and test-only changes; `packages/protocol` is otherwise untouched.
- **Meta flag drift** (config, not protocol): `experimental_flags` gained
  `auto_session_title: false` (upstream `6be26978`, titles via a managed
  `chat_title` tool — off here).
- **Everything else byte-identical**, spot-verified live: meta, session
  create/get, `/status` (k3 session → `max_context_tokens:1048576`,
  `thinking_level:"low"`), WS `server_hello` (`protocol_version:2`,
  `heartbeat_ms:10000`), prompts POST, `:abort` ACK.

The drift from 0.35.0 to
0.36.0 is **minor** for the core loop — every shape the Android app parses
still decoded. The upstream diff of `packages/protocol` and
`packages/kap-server` between the two tags (19 commits) shows exactly five
kap-server commits, of which only two touch the client-facing wire:

- **Session object gained optional `archived_at`** (commit `43c68f58`, "keep
  session updatedAt stable across meta management writes"): an ISO timestamp
  set when the session is archived, cleared on restore; **absent for
  never-archived sessions** (verified live — the fresh throwaway session
  carries no `archived_at` key in create/list/get). The same commit makes
  rename/archive non-bumping for `updated_at` (behavioral, not shape).
  Source-derived; archiving is not exercised by the app.
- **`GET /api/v1/config` shape** (commit `c9bfe8b2`, "replace the
  secondary-model experiment with a declarative subagent model pool"): the
  `secondary_model` key is **gone** from this server's response (was `{}` —
  the experiment stays off, `experimental_flags["secondary-model"]: false`),
  and a new **`subagent`** object appears (`{"timeoutMs": 7200000}` here —
  the subagent idle-timeout config section, camelCase like the rest of the
  response). The app consumes neither.
- **Invisible here:** the other three kap-server commits are internal —
  the debug-RPC dispatcher restriction (`26a37f30`), the agent-RPC
  aggregation-layer removal (`23e68eee`; the `:abort` route now calls
  `IAgentLoopService.cancelFromUser` — the REST ACK and the WS abort
  sequence re-verified byte-identical), and the swarm scope reorganization
  (`314b3948`; `events-zod.ts`/`coreEventMap.ts` diffs are import moves
  only). The `c9bfe8b2` secondary-model work also touched
  `legacyStatus.ts`/`modelCatalog.ts`/`errors.ts`, but every change is
  gated on the experiment or the derived model entry: `/status` and
  `/models` re-verified byte-identical with the experiment off, and the
  only error-side change is `Error2(config.invalid)` now mapping to
  envelope code `40001` instead of `50001` (a broken user config on session
  resume — not triggered live, no config here produces it).
- **Config-derived values observed this run** (not shape drift): the
  session's thinking effort is `low` on this server now
  (`GET /api/v1/config` `thinking.effort: "low"`), so `thinking_level`,
  `thinkingEffort`, and the `thinking_effort` fields on
  `subagent.spawned`/`task.*` info/REST `/tasks`/the snapshot roster all
  read `"low"` where the 0.34.0 captures read `"high"` — the spawned
  sub-agent inherits the session's effort along with its model.
- **Everything else byte-identical**, re-verified live: meta
  (`experimental_flags` gained `search_worker: true` and
  `persistence_minidb_readmodel` flipped to `true` — flag values are
  config, not protocol), models catalog, sessions list/create/get/status
  (fresh `thinking_level: ""`, `max_context_tokens: 262144` present),
  snapshot (top-level keys, roster `model`/`thinking_effort`, all-zero
  `session.usage` placeholder), error envelopes (`40401` + `stack`, unknown
  list cursor → `{"items":[],"has_more":false}`, `40922` bad v2 page token,
  empty-session compact `40910`, terminal-task cancel `40904` with
  `details.current_status`), WS hello (`heartbeat_ms: 10000`) / ack /
  server pings, one full prompt round-trip with a foreground AND a
  background sub-agent spawn (`agent.created`, `subagent.spawned` with
  `model`/`thinkingEffort`, `task.started`/`task.terminated` + the
  `background.task.*` aliases, `task.notified`, the `task`-origin
  completion turn, REST `/tasks` list/get/cancel), a mid-turn `:abort`
  (`interruptReason: "user_cancelled"` + the reminder splice), and a
  compact with `instruction` (`compaction.started` echo +
  `compaction.completed` + the history `compaction_summary` message).

The drift from 0.34.0 to
0.35.0 is a **single wire change** for the core loop: the upstream diff of
`packages/protocol` and `packages/kap-server` between the two tags (24
commits) shows `packages/protocol` untouched and exactly three kap-server
commits, of which only one touches the client-facing wire:

- **WebSocket heartbeat is back** (commit `619564dc`, "survive proxy idle
  timeouts"): `server_hello` now always carries `heartbeat_ms: 10000`, the
  server sends app-level `ping` frames every 10 s, and closes the socket
  (code `1001`, reason `"heartbeat timeout"`) after ~2 cycles (~20 s) of
  total client silence. Clients should answer `ping` with
  `{"type":"pong","payload":{"nonce":<echo>}}` — any inbound frame resets
  the server's liveness timer, so ordinary control traffic also counts.
  Details in the WebSocket → Heartbeat section. The other two kap-server
  commits are internal: the debug-RPC dispatcher now also resolves services
  from the global decorator registry (`86035497`), and the full-text search
  index moved into a worker thread (`7cd64766`) — the search REST
  request/response shapes and error codes are unchanged.
- **`last_turn_reason` persisted fallback is now observable:** sessions that
  ran turns under 0.34.0 report their persisted outcome after the restart
  (verified on the pre-upgrade sessions — see the session-object section).

The drift from 0.33.0 to 0.34.0 was again **minor** for the core loop: every
shape the Android app parses still decoded. An upstream diff of
`packages/protocol` and `packages/kap-server` between the two tags (24
commits) showed the protocol-relevant work: sub-agent model/effort surfacing
(`events.ts`/`task.ts`), the `/status` `max_context_tokens` optionality, a
persisted `last_turn_reason` fallback for cold sessions, a new
`/api/v2/sessions` table endpoint, MCP-server tombstoning (one new enum
value), and skill activation attachments (out of scope). The real changes:

- **Sub-agent model/effort surfacing (the shaped drift):**
  `subagent.spawned` gained optional **`model`** and **`thinkingEffort`**
  (camelCase); agent-kind `task.*` `info` payloads gained the same pair;
  REST `/tasks` subagent objects **and** the snapshot `subagents` roster
  gained **`model`**/**`thinking_effort`** (snake_case). Observed
  `"kimi-code/k3"`/`"high"` throughout — the spawned child inherits the
  session's bound model. All additive; the app ignores them.
- **Foreground sub-agents register in the task store while running**
  (unannounced): a `running` foreground sub-agent is listed by REST
  `/tasks`, stays fetchable by id once terminal, and `:cancel` works on it
  while running — still with **no `task.*` WS frames**, and the list drops
  it again once terminal. Details in the Sub-agents section.
- **Session status:** `max_context_tokens` became **omittable** when the
  context limit is unknown (upstream schema change — never observed omitted:
  fresh sessions report the 262144 default, k3 sessions 1048576). Minor:
  fresh sessions now report `thinking_level: ""` (was `"off"`).
- **Session object:** `last_turn_reason` gained the **persisted** fallback
  for cold sessions (see the session-object section).
- **Questions:** `multi_select` is present on a question item **only when
  true** (single-select items omit it).
- **Tools catalog:** `ReadMediaFile` came **back** — 27 builtin tools on
  initialized agents (26 on fresh never-prompted sessions; the catalog is
  resolved from the live agent's state).
- **Sub-agent first message:** the `<git-context status="unavailable"
  reason="not-a-repo"/>` prefix (added in 0.31.0) is **gone** — the first
  spliced text is the raw prompt verbatim.
- **New endpoint (out of the app's use):** `GET /api/v2/sessions` — a
  v1-enveloped, keyset-paginated session table for the kimi-inspect tool,
  with the new error code `40922` for bad page tokens. New section under
  Sessions.
- **Additive/minor:** a new `agent.status.updated` phase kind
  **`interrupted`** fires during abort; a new user-message injection origin
  variant **`permission_mode`** rides auto-mode turns; the MCP
  `mcp.server.status` enum gained **`removed`** (source-derived; MCP events
  are out of the core loop); image url-kind sources may carry a
  provider-issued **`id`** (never observed in the upload→prompt flow); skill
  `:activate` now accepts `attachments` (skills stay out of scope).
- **Everything else byte-identical**, re-verified live: meta (incl.
  `experimental_flags`), models, sessions list/create/get/status/snapshot
  (multi-page pagination walks, unknown cursor), workspaces + trust,
  messages, transcript, prompts/steer/abort (full WS sequences), compact
  (both variants + both `40910`s), questions (surface/poll/answer/dismiss/
  `40909`/`40902`), approvals (full manual round-trip, v2 + legacy frames),
  TodoList write contract, files (upload/download/`Range` 206/delete + the
  attachment rewrite, em dash intact), sub-agent lifecycles + legacy
  `background.task.*` aliases + cancel/`40904`, WS hello/ack/cursor-resume/
  resync, and one full prompt round-trip — every event payload's field set
  matched.

The drift from 0.32.0 to 0.33.0 was again **minor** for the core loop: every
shape the Android app parses still decoded. An upstream tree-diff of
`packages/protocol` and `packages/kap-server` between the two tags showed only one protocol file
(`events.ts`, an optional field on an MCP tool update) and three kap-server
areas touched (search, sessions-list pagination internals, skills listing).
The real changes:

- **Search (the only shaped drift):** page tokens are now **v2 keyset
  cursors** pinned to an index generation (`{"v":2,"f":…,"g":1,"b":[score,
  time,key]}` — opaque to clients either way); `index_state` gained optional
  **`stale`** (observed live `true` during an index rebuild) and
  **`degraded`** (never observed; source-derived); the `incomplete` enum
  gained `postings_budget`/`deadline` (source-derived, never observed); the
  `40001` search-error envelopes (`pageToken …`) are Error2 domain errors and
  carry a `stack` string, unlike schema-validation `40001`s. Details in the
  Search section.
- **Sessions list pagination internals rewritten** on the same keyset-cursor
  machinery — **wire shape unchanged**: same params, same `{items, has_more}`
  envelope, unknown cursor still answers `{"items":[],"has_more":false}`.
  Re-verified live (multi-page `before_id`/`after_id` walks).
- **Tools catalog:** `GET /api/v1/tools` now lists **26** builtin tools —
  **`ReadMediaFile` is gone** (was 27 since 0.31.0). Content drift, not
  shape drift.
- **`lastPrompt` redaction (new):** session-dir absolute paths in the
  session object's `last_prompt` (REST) and `session.meta.updated`'s
  `patch.lastPrompt` (WS) are scrubbed by the server to
  `/home/ubuntu/.[redacted].txt`. `turn.started.prompt`, `context.spliced`
  and `GET .../messages` history still carry full paths — display-only.
- **Everything else byte-identical**, re-verified live: auth, meta (incl.
  `experimental_flags`), models, sessions create/get/status/snapshot,
  workspaces + trust, messages, transcript, prompts/steer/abort, compact
  (both variants + all `40910`s), questions, approvals (full manual
  round-trip), TodoList write contract, files (incl. the attachment rewrite),
  sub-agents foreground + background, WS hello/ack/cursor-resume/resync.
- **Upstream, out of the core loop's sight:** the CLI surfaces now default
  to the agent-core-v2 engine (the kap-server API already ran on it), and
  `apps/kimi-web` was **deleted** — the web client ships as a prebuilt
  bundle (`apps/kimi-code/dist-web/`), so the web-client source is no longer
  a protocol reference from 0.33.0 onward.

The drift from 0.31.1 to 0.32.0 was likewise **minor** for the core loop.
Its changes:

- **Meta:** `GET /api/v1/meta` gained an **`experimental_flags`** object (the
  effective server-side experiment switches; `{"secondary-model":false,
  "tool-select":false,"persistence_minidb_readmodel":false}` here) — additive.
- **WebSocket (new event):** `event.config.warning` — a **global** frame
  (`session_id: "__global__"`, its own global epoch) pushed to every
  connection whenever the set of config warnings (deprecated keys/env vars in
  use) changes; `warnings: []` means the last warning cleared. Verbatim
  captures in the WebSocket section. The app's "ignore unknown frame types"
  rule already covers it.
- **Error envelopes:** the server-side **`stack`** string now rides on every
  Error2 domain-error envelope (observed on `40401`, `40402`, `40910`,
  `50001`; the workspace-trust route was the only one seen leaking it before).
  Validation (`40001`) and `40406` envelopes still have no `stack`. Additive —
  ignore it.
- **Error-code vocabulary:** the upstream enum gained
  `agent.already_exists`/`already_running`/`not_a_subagent`/`not_owned`/
  `type_not_allowed`/`max_tokens_exceeded`, `skill.parse_failed`/
  `nested_too_deep`, `task.limit_exceeded`, `mcp.oauth_failed`,
  `storage.permission_denied`/`disk_full`, `wire.migration_missing`,
  `cron.expression_invalid`, `web.invalid_url`/`private_address`/`fetch_failed`
  (plus `os.*`/`storage.*`/`wire.*` codes the v1 edge already knew). Clients
  must keep treating unknown codes generically.
- **Status/token counting:** context-size reporting moved to a new upstream
  `tokenCounting` service. Observable deltas: a draft session (no model bound)
  now reports the **default model's** `max_context_tokens` instead of a
  placeholder, and upstream types make `maxContextTokens` omittable when the
  limit is unknown (`0` is never pushed) — never observed omitted live; every
  `/status` and `agent.status.updated` capture below still carries it.
- **Questions:** `POST .../questions/{qid}` now accepts question ids
  containing **colons** (some OpenAI-compatible providers emit tool-call ids
  like `AskUserQuestion:0`; the `:dismiss`-suffix parse used to eat them) —
  invisible with this provider's ULID ids.
- **Invisible refactors:** v1 message-history and snapshot assembly moved from
  the engine into kap-server (upstream #2562) — every affected shape
  (`/messages`, `/snapshot`, transcript) re-verified byte-for-byte; an
  agent-core-v2 lifecycle ledger/hook-events wave and a new ACP server
  (`kimi acp-v2`) landed out of the core loop's sight.

The drift from 0.31.0 to 0.31.1 was likewise **minor** for the core loop:
every shape the Android app parses still decoded. Its changes:

- **WebSocket:** `turn.ended` gained an optional `interruptReason`
  (`"user_cancelled"` / `"aborted"` / `"max_steps"` / `"error"` / `"filtered"`
  / `"blocked"`); a REST `:abort` reports `interruptReason:"user_cancelled"`.
- **Interruption reminder (new):** after a user-cancelled turn the server
  appends a user-role `<system-reminder>` injection to the context
  (`metadata.origin: {"kind":"injection","variant":"interruption"}`) — it shows
  up in `GET .../messages` and as a live `context.spliced` echo (see Messages
  and Abort). Clients that already hide/strip injection envelopes (the app
  does) see no change.
- **Transcript:** new `interruption` markers between turns, and turn items now
  carry their real terminal `state` (`"cancelled"` / `"failed"`) plus
  `endedAt` / `durationMs` / `error` even on the cold rebuild — the engine
  persists `turn.ended` records now; journals written before 0.31.1 keep the
  old `completed` default.
- **Tools catalog:** `GET /api/v1/tools` answers `{"tools":[]}` when NO
  session is live (e.g. right after a server restart) — the route resolves the
  catalog from a live session's agent.
- **New endpoints (out of the app's scope):** `GET /api/v1/oauth/userinfo`
  (managed-account profile), `POST /api/v1/workspace/fs:search` (session-less
  file search — the web client switched its draft @-mention to it), and the
  workspace-trust routes under `/workspaces/{id}` (see Workspaces).
- **Invisible here:** the Agent/AgentSwarm tool schemas dropped their `model`
  parameter while the secondary-model experiment is off — not observable
  through `/api/v1/tools` (`input_schema: null`).

The drift from 0.29.2 to 0.31.0 was likewise **minor**; its changes:

- **Files:** the 50 MiB upload cap was **removed** (upstream commit "remove the
  50 MiB upload size cap and stream uploads to disk"). `POST /api/v1/files` no
  longer emits 413/41301 — uploads stream to disk; a 55 MiB upload returns
  HTTP 200 with the exact `size` (verified live). `41301` remains in the
  error-code table for session export only.
- **Config:** `GET`/`POST /api/v1/config` gained a `secondary_model` field
  (additive; `{}` on this server).
- **New endpoints:** `POST /api/v1/search` (global message search — new
  section) and an undocumented-till-now `GET /api/v1/tools` (tool catalog,
  `input_schema: null`).
- **WebSocket:** `agent.created` now fires for the **main** agent at the head
  of the first prompt; new event type `turn.step.retrying` (LLM retry, e.g.
  provider 429); `agent.status.updated` gained a nested-usage variant and
  `stream:"assistant"`; right after `subagent.spawned` the server republishes
  the sub-agent's `agent.status.updated`; background-task completion is now
  delivered as a **new main turn** whose `turn.started` origin is
  `{"kind":"task",...}`, and the notification splice message has **no `id`**
  and origin kind `task`; the background Agent tool result text gained
  `next_step:`/`resume_hint:` blocks (the Agent tool gained a `resume`
  parameter); and the compaction summary message in `context.spliced` no
  longer carries `id: null` — the `id` key is **absent** (backward compatible
  for clients that treat spliced-message ids as optional, as advised since
  0.29.0).
- **Environment note:** the `kimi-web.service` unit now runs with
  `PrivateTmp=yes`, so host-side `/tmp` directories (the old throwaway cwd)
  are invisible to the server —
  the throwaway-session examples below use `/home/ubuntu/spike` instead.

Server updates are installed by the
`kimi-nightly-update` systemd timer (see `scripts/`), which re-verifies this
protocol after every update.

## Base URL & auth

| Path | Base URL | Auth |
|---|---|---|
| Local (this VPS) | `http://127.0.0.1:58627` | Bearer token (server-native) |
| Remote (Tailscale) | `http://100.64.0.1` | Bearer token, via Caddy |
| Remote (public HTTPS) | `https://agent.example.com:8443` | Bearer token, via Caddy |

- REST API root is `<base>/api/v1`.
- Auth is the server's native bearer-token auth (`--dangerous-bypass-auth` is NOT
  used): `Authorization: Bearer <token>` on every request, i.e.
  `curl -H "Authorization: Bearer <token>" http://100.64.0.1/api/v1/...`.
  The token lives in `/home/ubuntu/.kimi-code/server.token` (mode 600); rotate
  with `kimi web rotate-token`. Caddy proxies the header through untouched. The
  same header must be present on the WebSocket handshake.
- **`GET /api/v1/healthz` is public** — it answers `200` with no token at all,
  so it cannot validate credentials. Use **`GET /api/v1/meta`** as the
  credential check endpoint: `200` ⇒ token valid, `401` ⇒ missing or invalid.

Verified (server 0.31.0, 2026-07-30):

```bash
# No token -> rejected on authenticated routes
curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:58627/api/v1/meta
# 401

# Wrong token -> rejected
curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer wrong" http://127.0.0.1:58627/api/v1/meta
# 401

# Valid token -> allowed
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:58627/api/v1/healthz
# {"code":0,"msg":"success","data":{"ok":true},"request_id":"01KY6G4GA..."}
```

### Response envelope

Every REST response is JSON wrapped in an envelope:

```json
{"code": 0, "msg": "success", "data": { ... }, "request_id": "01KY6G4GABMJ083QVRBW0TQNH9"}
```

- `code: 0` = success; the payload is in `data`.
- **Errors come back as HTTP 200 with a non-zero `code`** — check `code`, not
  the HTTP status. Example: a non-existent `cwd` on session create returns
  `{"code":40409,"msg":"workspace root /tmp/definitely-not-here-xyz does not exist","data":null,...}`
  over HTTP 200. Error envelopes may also carry `details` (validation errors)
  and a server-side `stack` string — since 0.32.0 `stack` rides on every
  Error2 domain-error envelope (verified on `40401`, `40402`, `40910`,
  `50001`), and since 0.33.0 that includes the search route's `40001`-coded
  `GlobalSearchError`s (`pageToken …`); schema-validation `40001`s (Zod,
  with `details`) and the `40406`/`40407` not-found envelopes still omit it
  (re-verified 2026-09-02 on 0.40.1). **0.43.0 (verified live 2026-09-14):**
  `40401` envelopes no longer carry `stack` (the session-not-found error
  instance simply has none captured — the transport is unchanged; `40910`
  still carries it). Ignore it either way.

## Meta

`GET /api/v1/meta` — server identity and capabilities. Requires the token.

```bash
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:58627/api/v1/meta
```

```json
{
  "code": 0, "msg": "success",
  "data": {
    "server_version": "0.43.0",
    "capabilities": {"websocket": true, "file_upload": true, "fs_query": true, "mcp": true, "tasks": true, "terminal": true},
    "server_id": "01M2GP32AY8TH41WNEHWR31TXB",
    "started_at": "2026-09-14T19:25:19.070Z",
    "open_in_apps": [],
    "dangerous_bypass_auth": false,
    "backend": "v2",
    "experimental_flags": {"notify_user": false, "subagent_fork": false, "wait_for": true, "tower": false, "tool-select": false},
    "features": [
      {"name": "skill", "state": "Active", "meta": {}},
      {"name": "btw", "state": "Active", "meta": {}},
      {"name": "plan", "state": "Active", "meta": {}},
      {"name": "todo", "state": "Active", "meta": {}}
    ]
  },
  "request_id": "01M2GRZ4C1ABTTVHSH21D1JY3X"
}
```

(`features` trimmed to 4 of 17 entries; the full list here is `skill`,
`btw`, `dateChange`, `plan`, `fileHistory`, `externalHooks`, `debugEvents`,
`swarm`, `goal`, `tower`, `usage`, `cron`,
`reminder`, `tokenCounting`, `sessionInit`, `todo`, `notify` — all
`"Active"` with empty `meta`. **0.42.0:** `contextBudget` AND
`interaction` are **gone** (the context-budget reminders were removed
upstream, commit `55685c58`; `interaction` folded away in the human-layer
absorption, `485594f6`-era churn) and **`notify`** (the `NotifyUser` tool
feature, commit `f6a9c39e`) is new — 17 entries. **0.41.0:** `staleGuard` is **gone** (the
feature was removed, commit `a0209469`) and `contextBudget` was new (the
context-budget reminder unit, commit `b199e332`). **0.40.1:** `fileHistory`
is new (the experimental file-history feature unit, commit `650d12f6`).
**0.39.0:** `skill`,
`cron`, `interaction` and
`reminder` are new — the agent-runtime featurization, commit `9a715820`.)

- **`experimental_flags` is new in 0.32.0** (additive): the effective
  server-side experiment switches. Flag *values* are config, not protocol —
  on 0.37.2 (2026-08-18) `persistence_minidb_readmodel` and `search_worker`
  read `true`, and `auto_session_title` (`false` here) gates server-side
  session-title generation. Treat every flag as optional. **0.42.0
  (verified live 2026-09-09): the set shrank to 6 entries** — the
  graduated `remote-control`, `persistence_minidb_readmodel`,
  `search_worker` and `secondary-model` keys are **gone**, and
  **`notify_user` is new** (`false` here — it gates the new `NotifyUser`
  tool, which is absent from the tools catalog while the flag is off).
  **0.43.0 (verified live 2026-09-14): the set shrank to 5 entries** —
  `auto_session_title` is gone: AI session-title generation graduated out
  of the flag and is always on (commit `6126472c`). **2.3.0 (verified live
  2026-10-22): unchanged** — the same 5 flags
  (`notify_user`/`wait_for`/`subagent_fork`/`tower`/`tool-select`) and the
  same 17 features; only `server_version` differs. **2.3.3 (verified live
  2026-10-28):** still the same 5 flags and 17 features; `server_version` and
  `started_at` differ.
- **`web_title` is new in 0.37.2** (additive): the instance's `--web-title`
  browser-tab label, present only when the server was started with that flag;
  omitted here (verified live 2026-08-18). Treat it as an optional instance
  label.
- **`features` is new in 0.38.0** (additive; upstream commit `cb8a7e5f"): the
  engine's feature-unit list, `[{name, state, meta}]`. Diagnostic metadata —
  treat every entry as optional. `experimental_flags` gained `wait_for: true`
  in the same release (the `WaitFor` tool's switch; flag values are config,
  not protocol). **0.39.0:** the flags gained `subagent_fork` (the `Agent`
  tool's fork parameter), `remote-control` (the web remote-control tunnel)
  and `tower` (tower mode) — all `false` here. **0.40.1:** the flags gained
  **`file_history`** (`false` here — the experimental turn-level
  file-history snapshot feature, commit `650d12f6`), and
  **`secondary-model` now defaults to `true`** (the subagent model pool
  graduated out of experimental, commit `971a8b24` — value drift, the key
  exists since 0.36.0). **0.41.0:** **`file_history` is gone again** — the
  feature graduated and the gate was dropped (commit `eba23edb`), so the
  routes are always on (see the Files section).

## Models

### List — `GET /api/v1/models`

The models the server can run, for model pickers. Requires the token.

```bash
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:58627/api/v1/models
```

Verified live on 0.31.0 (2026-07-30, `capabilities` arrays trimmed; catalog
unchanged from 0.29.2):

```json
{
  "code": 0, "msg": "success",
  "data": {
    "items": [
      {"provider": "managed:kimi-code", "model": "kimi-code/kimi-for-coding",
       "display_name": "K2.7 Coding", "max_context_size": 262144,
       "capabilities": ["thinking", "always_thinking", "image_in", "video_in", "tool_use"]},
      {"provider": "managed:kimi-code", "model": "kimi-code/kimi-for-coding-highspeed",
       "display_name": "K2.7 Coding Highspeed", "max_context_size": 262144,
       "capabilities": ["thinking", "always_thinking", "image_in", "video_in", "tool_use"]},
      {"provider": "managed:kimi-code", "model": "kimi-code/k3",
       "display_name": "K3", "max_context_size": 1048576,
       "capabilities": ["thinking", "always_thinking", "image_in", "video_in", "tool_use"],
       "support_efforts": ["low", "high", "max"], "default_effort": "high"},
      {"provider": "managed:kimi-code", "model": "kimi-code/k3-256k",
       "display_name": "K3-256k", "max_context_size": 262144,
       "capabilities": ["thinking", "always_thinking", "image_in", "tool_use"],
       "support_efforts": ["low", "high", "max"], "default_effort": "high"}
    ]
  },
  "request_id": "01KYBNP3X8A93HA8HT9CG27H3E"
}
```

- `model` is the id to send as the per-prompt `"model"` field
  (`POST /api/v1/sessions/{id}/prompts`).
- `support_efforts` / `default_effort` are present only on models with
  thinking-effort levels (K3 on 0.29.0); absent elsewhere. The values are the
  accepted per-prompt `"thinking"` strings (plus `"off"` — see
  `POST .../prompts`); a model *without* `support_efforts` still accepts an
  effort string but degrades it to plain thinking-on (`thinking_level: "on"`).
- The server-wide default model is *not* in this response — it comes from
  `GET /api/v1/config` (`data.default_model`). This is a config value, not
  protocol: it read `kimi-code/kimi-for-coding` on 0.29.1 (2026-07-25),
  `kimi-code/k3` on 0.29.2 (2026-07-27), `kimi-code/k3-256k` on 0.31.0
  (2026-07-30), and **`deepseek/deepseek-v4-pro` on 0.37.2** (2026-08-18) —
  the server may change it independently of protocol drift. **0.42.0
  (verified live 2026-09-09): it reads `kimi-code/kimi-for-coding` again**
  (was `kimi-code/k3-256k` on 0.40.1), so a fresh model-less session's
  `/status` `max_context_tokens` is back to 262144. The catalog above
  is the 0.31.0 capture; **0.37.2 adds four `deepseek/*` entries**
  (`deepseek/deepseek-v4-flash`, `-pro`, `-chat`, `-reasoner`; all
  `max_context_size: 1000000`, no `support_efforts`) — additive.
  **0.41.0 (verified live 2026-09-04):** the catalog reads **10 entries** —
  two new `Kimi Platform/*` ones (`Kimi Platform/k3-256k`,
  `Kimi Platform/kimi-k3`) ride alongside the 4 `kimi-code/*` and 4
  `deepseek/*`. Provider-side content drift, not protocol.
  **0.43.0 (verified live 2026-09-14):** still 10 entries, but
  `kimi-code/kimi-for-coding` now displays **"K2.8 Preview"** with
  `max_context_size: 1048576` (was 262144) and gained
  `support_efforts: ["low","high","max"]` + `default_effort: "max"`; the
  highspeed entry renamed to "K2.7 Code Highspeed"; `kimi-for-coding`,
  `k3` and `k3-256k` gained a **`dynamically_loaded_tools`** capability
  string (commit `9296e687`); and the deepseek v4-flash/v4-pro and
  `Kimi Platform/kimi-k3` entries gained `support_efforts` arrays. The
  server-wide default model reads **`kimi-code/k3-256k` again** (was
  `kimi-code/kimi-for-coding` on 0.42.0). All content drift, not
  protocol — the item shape is unchanged.
  **2.3.1 (verified live 2026-10-23):** the catalog reads **12 entries** —
  two new `managed:kimi-code` models ride alongside: **`kimi-code/k3.1-pro`**
  ("K3.1 Pro", `max_context_size: 1048576`) and
  **`kimi-code/k3.1-pro-highspeed`**. None of the 12 publishes `upgrade`.
  Content drift, not protocol.
  **2.3.3 (verified live 2026-10-28):** still 12 entries. Both K3.1 entries
  now report `default_effort: "high"`, and the highspeed display name reads
  **"K3.1 Pro Highspeed 2x Usage Cost"**. The server-wide default model moved
  to **`deepseek/deepseek-v4-flash`** (`max_context_size: 1000000`), so a
  fresh model-less session reports `max_context_tokens: 1000000`. The pair is
  what the live `model_switch_confirm` rules gate (see Client configs).
- **2.3.3 default-model drift (verified live 2026-10-28):**
  `GET /api/v1/config` reports `default_model:"deepseek/deepseek-v4-flash"`
  (was `kimi-code/k3-256k` on 2.3.1). `thinking.effort` stays `"max"` and
  `default_permission_mode` stays `"auto"`. The app reads this default on the
  send path, so it follows the server without a code change.
- `GET`/`POST /api/v1/config` also carries a **`secondary_model`** field (new
  in 0.31.0, additive): `{}` on this server (2026-07-30). It is writable via
  `POST /api/v1/config`; per upstream, the POST body is snake_case while the
  GET echoes camelCase for some fields. The Android app consumes neither.
  **0.36.0:** the key is **absent** from this server's GET response (the
  experiment is off and nothing is configured), and the response gained a
  **`subagent`** object (`{"timeoutMs": 7200000}` here — the subagent
  idle-timeout section, camelCase; upstream commit `c9bfe8b2`).
- **Config fields the app consumes (ticket 109, verified live 2026-09-01 on
  0.39.1):** `data.default_model` and `data.thinking.effort` — both writable
  via `POST /api/v1/config` with a snake_case body (`{"default_model": "<id>"}`,
  `{"thinking": {"effort": "<e>"}}`; the thinking write was already verified
  2026-08-28, the model write on 2026-09-01). The POST response echoes the full
  config projection (same consumed fields snake_case). `data.providers` is a
  map of provider name to `{"type", "base_url", "has_api_key"}` with
  credentials redacted — surfaced read-only in Settings. **2.0.1, additive:**
  the values may also carry an optional **`api_key_env`** (the name of an env
  var holding the credential; commit `c5ad17f0` — parsed with
  `ignoreUnknownKeys`, no ticket). Every config write
  pushes a global `event.config.changed` frame (see WebSocket, Global events);
  the app drops its models/config caches on it (ticket 110).
- **`default_permission_mode`** (verified live 2026-09-15 on 0.43.0): the
  server-wide default permission mode, `"yolo"` here. Writable via
  `POST /api/v1/config` — a current-value write
  (`{"default_permission_mode":"yolo"}`) answered `code:0` and the GET
  read-back was unchanged, so the server default was never moved. A fresh
  session's `/status` `permission` starts from it (`"yolo"` observed on a
  never-touched throwaway session); the per-session override is
  `POST .../sessions/{id}/profile` (see Sessions). The Settings default
  picker will consume it (ticket 142). **2.2.0 (verified live
  2026-10-14): it now reads `"auto"`** — config drift, not protocol;
  fresh sessions start in auto mode, which suppresses the model's
  `AskUserQuestion` calls (see Questions). **2.3.0 (verified live
  2026-10-22): still `"auto"`.**
- **Model-migration metadata (new in 2.3.0, commit `0327e8e9`):** catalog
  items may carry an optional **`upgrade`** object —
  `{model, migration_markdown, retirement_at?}` naming the replacement
  alias with user-facing migration Markdown — and the global
  `event.model_catalog.changed` frame may carry optional
  **`retired_default: {from, to, from_display_name}`** when the server's
  default model was retired and auto-migrated. Schema-derived: **none of
  the 10 live catalog items publishes `upgrade`** and no `retired_default`
  frame fired (verified live 2026-10-22). The same release re-parses the
  managed provider's upstream `/models` payload from a unified schema
  (commit `9f0693be`) — catalog *contents* (capabilities/efforts) may shift
  with it; the item shape above is unchanged. Ticket 166 consumes
  `upgrade`; the app already drops its models cache on
  `event.model_catalog.changed` (ticket 110).
- **`disable_feedback_survey`** (new in 2.3.0, commit `ce0834a8`): a
  boolean gating the feedback surveys (see Session rating), accepted by the
  GET/PATCH config schemas. **Absent from this server's GET projection when
  unset** (verified live 2026-10-22 — no key in `data`). The app reads no
  such field.
- **`auto_session_title`** (new in 2.1.1, commit `6a52dd78`; verified live
  2026-09-24): a boolean gating automatic AI session-title generation ("Whether
  clients may automatically generate session titles" per the upstream doc).
  **Absent from this server's GET projection when unset** — no key in `data`,
  and titles generate by default (`session.meta.updated` on the first prompt
  re-verified this run). The REST schema accepts it on both GET and POST.
  The app reads no such field — no ticket.

## Client configs

### Fetch — `POST /api/v1/client_configs`

**New in 2.3.0** (commit `3f80896a`; verified live 2026-10-22). Server-proxied,
cloud-controlled client configs. These are named JSON blobs the managed
service distributes (from the region or `KIMI_CODE_BASE_URL`), so Kimi can
change client behavior without a release. Body `{"name":"<config name>"}`;
the answer is `{"name":<echo>, "config": <object>|null}`. An unregistered
name returns `config:null` without a network call, and a registered name
returns `null` when the cloud value is absent or malformed. Every request
fetches the cloud value again; there is no server-side cache.

```bash
curl -s -X POST -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json' \
  -d '{"name":"model_switch_confirm"}' http://127.0.0.1:58627/api/v1/client_configs
```

Two names are registered on 2.3.3: **`model_switch_confirm`** and
**`effort_switch_confirm`**.

### `model_switch_confirm`

Rules asking clients to confirm a model switch. The shape changed in 2.3.3
(commit `f895f305`): every rule now requires `session_type`, wildcards are
supported, and `warning` is optional.

```json
{"enable": true, "rules": [
  {"from": "*", "to": ["k3.1-pro-highspeed"], "session_type": "new",
   "hint": {"en": "Note: K3.1 Pro Highspeed consumption is approximately 2x that of K3.1 Pro", "zh": "…"}},
  {"from": "*", "to": ["k3.1-pro-highspeed"], "session_type": "old",
   "hint": {"en": "Switching to K3.1 Pro Highspeed in this session may affect output quality. Choose how to continue", "zh": "…"},
   "warning": {"en": "Note: K3.1 Pro Highspeed consumption is approximately 2x that of K3.1 Pro", "zh": "…"}},
  {"from": ["k3", "k3-256k", "kimi-for-coding", "kimi-for-coding-highspeed"],
   "to": ["k3.1-pro", "k3.1-pro-highspeed"], "session_type": "old",
   "hint": {"en": "Switching to a K3.1 series model in this session may affect output quality. Choose how to continue", "zh": "…"}}
]}
```

The live envelope, captured 2026-10-28 with Chinese strings trimmed to `…`
here (`request_id` `01M60TRX5D93V10HE1ANXF08W8`):

```json
{"code":0,"msg":"success","data":{"name":"model_switch_confirm","config":{"enable":true,"rules":[{"from":"*","to":["k3.1-pro-highspeed"],"session_type":"new","hint":{"en":"Note: K3.1 Pro Highspeed consumption is approximately 2x that of K3.1 Pro","zh":"…"}},{"from":"*","to":["k3.1-pro-highspeed"],"session_type":"old","hint":{"en":"Switching to K3.1 Pro Highspeed in this session may affect output quality. Choose how to continue","zh":"…"},"warning":{"en":"Note: K3.1 Pro Highspeed consumption is approximately 2x that of K3.1 Pro","zh":"…"}},{"from":["k3","k3-256k","kimi-for-coding","kimi-for-coding-highspeed"],"to":["k3.1-pro","k3.1-pro-highspeed"],"session_type":"old","hint":{"en":"Switching to a K3.1 series model in this session may affect output quality. Choose how to continue","zh":"…"}}]}},"request_id":"01M60TRX5D93V10HE1ANXF08W8"}
```

- `from` and `to` are a string or a non-empty string array. A string `*`
  matches any upstream model id on that side.
- `session_type` is `old`, `new`, or `all`. Upstream treats a session as
  `old` once its conversation has at least one real user message, and `new`
  otherwise. It matches the first enabled rule whose session type is `all`
  or equal to the current type. This matching behavior is source-derived; the
  config shape and values are live.
- Ids are **upstream model ids** such as `k3` and `kimi-for-coding`, not the
  picker aliases. `GET /api/v1/config`'s `models` map resolves an alias to
  its upstream `model` value.
- `hint` and `warning` are optional localized strings. The TUI displays
  `en`, and `warning` is highlighted separately.

The 2.3.0 and 2.3.1 history: both probes answered `config:null` on 2.3.0
(2026-10-22), then 2.3.1 (2026-10-23) served the old rules without
`session_type`. The current shape above is the 2.3.3 one. Ticket 167 consumes
it.

### `effort_switch_confirm`

**New in 2.3.3** (commit `0051ef10`, verified live 2026-10-28). It supplies
effort descriptions and optional confirmation rules for changing the
thinking effort on a managed Kimi model:

```json
{"descriptions": {"enable": true,
  "items": {
    "low": {"en": "Light reasoning for simple tasks and quick iterations", "zh": "…"},
    "high": {"en": "Deeper reasoning for complex tasks, with higher token usage", "zh": "…"},
    "max": {"en": "Maximum reasoning for the hardest tasks, with longer response times and higher token usage", "zh": "…"}}},
 "confirm": {"enable": true, "rules": [
   {"from": "*", "to": "*", "session_type": "old",
    "hint": {"en": "Switching thinking effort may affect output quality. It may also invalidate the current prompt cache and increase token usage", "zh": "…"}}]}}
```

Verbatim live envelope, Chinese strings trimmed to `…` (`request_id`
`01M60TRXWWX8PV21YJP6FY1215`):

```json
{"code":0,"msg":"success","data":{"name":"effort_switch_confirm","config":{"descriptions":{"enable":true,"items":{"low":{"en":"Light reasoning for simple tasks and quick iterations","zh":"…"},"max":{"en":"Maximum reasoning for the hardest tasks, with longer response times and higher token usage","zh":"…"},"high":{"en":"Deeper reasoning for complex tasks, with higher token usage","zh":"…"}}},"confirm":{"enable":true,"rules":[{"from":"*","to":"*","session_type":"old","hint":{"en":"Switching thinking effort may affect output quality. It may also invalidate the current prompt cache and increase token usage","zh":"…"}}]}}},"request_id":"01M60TRXWWX8PV21YJP6FY1215"}
```

Descriptions are keyed by the effort wire value and are independent of the
model. Upstream shows `descriptions.items[effort].en` only for models whose
provider is `managed:kimi-code`. The `confirm.rules` use the same rule shape
as `model_switch_confirm`, with effort values in `from`/`to` instead of model
ids. Both behaviors are source-derived; only the config payload was captured
live. Ticket 170 consumes this config.

An unregistered name still answers `config:null` (verified 2026-10-28:
`{"name":"definitely_not_registered","config":null}`).

## Sessions

### List — `GET /api/v1/sessions`

Query params (all optional): `page_size` (default 20), `before_id`, `after_id`
(pagination cursors — `before_id` paging verified 2026-07-22, list shape
re-verified 2026-07-30 on 0.31.0), `status`, `include_archive`, `archived_only`,
`exclude_empty`, `workspace_id`.

```bash
curl -s -H "Authorization: Bearer <token>" 'http://127.0.0.1:58627/api/v1/sessions?page_size=2'
```

Session object shape (2.5.0, captured 2026-11-04 and re-verified unchanged on
2.5.1, 2026-11-05; fields trimmed for
brevity — note the absent `last_seq` and the filled `agent_config.model`):

```json
{
  "id": "session_…",
  "workspace_id": "wd_…",
  "title": "Reply with exactly: legacy-off-probe",
  "created_at": "2026-11-04T19:24:31.649Z",
  "updated_at": "2026-11-04T19:25:55.341Z",
  "busy": false,
  "main_turn_active": false,
  "pending_interaction": "none",
  "last_turn_reason": "completed",
  "archived": false,
  "last_prompt": "Reply with exactly: delta-probe",
  "metadata": {"cwd": "/path/to/project"},
  "agent_config": {"model": "deepseek/deepseek-v4-flash"},
  "usage": {"input_tokens": 0, "output_tokens": 0, "cache_read_tokens": 0,
            "cache_creation_tokens": 0, "total_cost_usd": 0, "context_tokens": 0,
            "context_limit": 0, "turn_count": 0},
  "permission_rules": [],
  "message_count": 0
}
```

- Runtime state fields: `busy` (bool), `main_turn_active` (bool),
  `pending_interaction` (`"none"` | `"approval"` | `"question"`),
  `last_turn_reason` (`"completed"` | `"cancelled"`). **`last_turn_reason` is
  absent entirely on sessions that have not run a turn yet** (re-verified
  2026-07-23). **0.34.0:** the field gained a **persisted** last-turn
  outcome fallback for *cold* sessions (no live handle) — previously only
  warm sessions reported it. Not observable right after the 0.34.0 upgrade
  (pre-0.34.0 sessions had nothing persisted); **observable since 0.35.0**
  on the sessions that ran turns under 0.34.0 — e.g. a pre-restart session
  now lists `"last_turn_reason": "completed"` cold (verified 2026-08-12).
- **0.36.0 (additive, source-derived):** optional **`archived_at`** (ISO
  timestamp) — set when the session is archived, cleared on restore;
  **absent for never-archived sessions** (verified live 2026-08-13: the
  fresh throwaway session carries no `archived_at` key in create/list/get).
  Upstream commit `43c68f58`; clients fall back to `updated_at` for display.
- **0.39.1 (verified live 2026-08-28):** `agent_config.model` is **real** on
  live sessions — the bound model (`"kimi-code/k3"`) instead of the
  hardcoded `""` served up to 0.39.0 (upstream commit `2bf7ed22`). Cold
  sessions still report `""`. The app reads this field (ModelsRepository
  falls back to `default_model` when empty) — strictly an improvement.
  **2026-09-01 (ticket 107 spike, verified live):** the binding is
  **persisted** — a session cold since server start reports `""` here, but
  its first `/status` call already serves the real `model`/`thinking_level`
  and warms it: a subsequent get then reports `agent_config.model` too. So
  `/status`, not list/get, is the reliable read of a session's bound model.
  Persistence covers bindings made under older server versions too: sessions
  last active 2026-08-25/27 (0.38-era), still cold after the 2026-08-28
  restart to 0.39.1, answered their first `/status` with their persisted
  binding (verified 2026-09-01).
- `message_count` is still present but observed stuck at `0`
  (re-verified 2026-07-23 after several turns) — do not rely on it.
  **2.5.0: `last_seq` is gone from every session object** (list, create, get,
  fork, restore). The op watermark lives at
  `GET .../transcript.seq` and in `transcript.reset.seq`; the old
  `as_of_seq`/`epoch` cursor pair no longer exists.
- The list envelope carries `has_more` for pagination (verified with
  `page_size=2&before_id=<last id of page>`; re-verified 2026-08-05 on
  0.33.0 after the upstream keyset-cursor rewrite — multi-page
  `before_id`/`after_id` walks, and an unknown cursor still answers
  `{"items":[],"has_more":false}`).
- Since 0.33.0 the server **redacts session-dir absolute paths** in
  `last_prompt` (and in `session.meta.updated`'s `patch.lastPrompt`):
  `/home/ubuntu/.kimi-code/sessions/…/attachments/f_…-name.txt` becomes
  `/home/ubuntu/.[redacted].txt` (verified live 2026-08-05, server-emitted).
  Display-only — `turn.started.prompt`, `context.spliced` and
  `GET .../messages` history still carry the full path.
- **2.5.1: sessions of deleted workspaces are hidden from the list** (commit
  `b4e4a84f`; verified live 2026-11-05). A summary whose `workspace_id` is no
  longer a registered workspace (or one of its aliases) is skipped before
  the page is built, so it does not consume a page slot and is not counted by
  `has_more`. The session is **not deleted**: `GET /api/v1/sessions/{id}`
  still serves it with its `metadata.cwd`. See the drift section.

### Create — `POST /api/v1/sessions`

Body: `{"metadata": {"cwd": "<working directory>"}}`, optional top-level
`"title"`. A `cwd` is expected; the server auto-creates a workspace for new
cwds. A non-existent `cwd` is refused with envelope code `40409` over HTTP 200.
**Note for this VPS:** the `kimi-web.service` unit runs with `PrivateTmp=yes`,
so the server's `/tmp` is a private namespace — host-side `/tmp` directories
are invisible to it. Use a path outside `/tmp` instead; the throwaway-session
examples in this document use `/home/ubuntu/spike` (pre-created, owned by the
`ubuntu` user the service runs as).

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"metadata":{"cwd":"/home/ubuntu/spike"}}'
```

Returns the session object above. Fresh sessions created without a `title`
come back with `"title": ""` — clients should fall back to their own
placeholder. **0.42.0: sessions CAN be deleted now** (commit `fa87d040` —
see Delete below); the bare `DELETE /api/v1/sessions/{id}` HTTP method
still answers plain HTTP 404 (re-verified 2026-09-09) — deletion is a
`:delete` action.

### Get one — `GET /api/v1/sessions/{id}`

Returns the same session object as the list. **2.5.0 removed `last_seq`** (and
`agent_config.model` is now filled on live sessions — see the list notes). The
old `last_seq`/`epoch` pair is replaced by the transcript op watermark:
`GET .../transcript.seq` (and `transcript.reset.seq`) is the value to pass as
`transcript_since`.

### Delete — `POST /api/v1/sessions/{id}:delete`

**New in 0.42.0** (commit `fa87d040`, "expose session deletion with
serialized cleanup"; every behavior below verified live 2026-09-09). Body
`{}` (or empty). Answers
`{"code":0,"msg":"success","data":{"deleted":true},"request_id":"…"}`.
After the delete: `GET /api/v1/sessions/{id}` answers
`{"code":40401,"msg":"session … does not exist",…}` (**0.43.0:** without
`stack` — see Response envelope), the
session drops off the list, and — like archiving — a **global WS event
`event.session.deleted`** fires on every live connection (see WebSocket →
Global events). Unknown id → `40401` "session … does not exist". The
cleanup is serialized server-side (the session's background work is
drained first). **The Android app has no delete UI and ignores the event
today — ticket 126 covers handling it.**

### Fork — `POST /api/v1/sessions/{id}:fork`

Forking copies a session — workspace, cwd and full message history — into a
new session (upstream `9c37feb4`, added in 0.40.1; the web client exposes it
in the sidebar row menu, the chat header overflow and the `/fork` slash
command, all posting `{}`; every behavior below verified live 2026-09-14 on
0.42.0, ticket 127). Body `{"title"?, "metadata"?}` — both optional; `{}`
works.

```bash
curl -s -X POST 'http://127.0.0.1:58627/api/v1/sessions/session_d6c12410-...:fork' \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' -d '{}'
```

Answers the standard session envelope (same shape as create/get) — verbatim
capture, trimmed:

```json
{"code":0,"msg":"success","data":{"id":"session_4536c55c-...","workspace_id":"wd_spike_6baf4c130e63","title":"Fork: Fork source","created_at":"2026-09-14T09:27:49.767Z","updated_at":"2026-09-14T09:27:37.222Z","busy":false,"archived":false,"metadata":{"cwd":"/home/ubuntu/spike"},"agent_config":{"model":""},"usage":{…},"permission_rules":[],"message_count":0,"last_seq":0},"request_id":"01M2FKX17KGY62AT5CXNJYMRJH"}
```

- Default title when none is passed: `Fork: <source title>`.
- The fork inherits the source's `workspace_id` and `metadata.cwd` and
  carries a full copy of the conversation — `GET .../messages` on the fork
  returns the source history verbatim (verified 7/7 messages, same
  roles/order; the sequential `msg_<session>_NNNNNN` ids are re-issued under
  the fork's id, the ULID-style ones are kept).
- Quirk: the fork's `updated_at` is inherited from the source, so it can
  predate its own `created_at` (as in the capture) — in a recency-sorted
  list the fork lands next to its origin, not at the top.
- A plain `POST /api/v1/sessions` (any client) broadcasts an
  `event.session.created` WS frame carrying the full session object — see
  WebSocket → Global events for the verbatim capture and its non-`__global__`
  envelope. The fork's own such frame never reaches the wire (dropped
  server-side: the forked session is not loaded), so a fork made from ANOTHER
  client appears in the app only on the next refresh. The Android app handles
  the create frame since ticket 129; its own forks insert the row locally.
- Errors: unknown id → `40401` "session … does not exist" (**0.43.0:**
  without `stack` — see Response envelope);
  a turn running on the source → `40901`, verbatim
  `{"code":40901,"msg":"Session \"session_…\" cannot be forked while a turn is running","data":null,…}`
  (with `stack`).

### Archive / Restore — `POST /api/v1/sessions/{id}:archive` / `:restore`

Single-session archive and restore (the v2 batch variants are under Session
table (v2) below; the 0.42.0 web bundle's row "Archive" action and its toast's
Undo call these). Body `{}`. Every behavior below verified live 2026-09-14 on
0.42.0 (ticket 131).

```bash
curl -s -X POST 'http://127.0.0.1:58627/api/v1/sessions/session_bfc5feb1-...:archive' \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' -d '{}'
```

Archive answers a bare flag — verbatim capture:

```json
{"code":0,"msg":"success","data":{"archived":true},"request_id":"01M2FSTW4QJCB0KY2JQ5ZNTH7Z"}
```

Restore answers the standard v1 session envelope (same shape as create/get)
with `archived:false` — verbatim capture, trimmed:

```json
{"code":0,"msg":"success","data":{"id":"session_bfc5feb1-...","workspace_id":"wd_spike_6baf4c130e63","title":"ticket-131-archive-capture","created_at":"2026-09-14T11:11:17.099Z","updated_at":"2026-09-14T11:11:17.099Z","busy":false,"archived":false,"metadata":{"cwd":"/home/ubuntu/spike"},"agent_config":{"model":""},"usage":{…},"permission_rules":[],"message_count":0,"last_seq":0},"request_id":"01M2FSTWB0RDBAP9YH9J6BGEXW"}
```

- Archive pushes the global `event.session.archived` WS frame (WebSocket →
  Global events); **restore pushes no WS event at all** — no
  `event.session.restored`, no `event.session.updated`. Other clients learn
  about restores by refetching.
- Archiving never bumps `updated_at` (upstream commit `43c68f58`): the capture
  above keeps the pre-archive stamp after archive+restore, so a client-side
  undo can reinsert the row at its old recency position.
- While archived the session object carries `archived:true` plus the 0.36.0
  `archived_at` timestamp; restore clears it back to `archived:false` with the
  `archived_at` key absent again — a restored session is indistinguishable
  from a never-archived one (both in the capture).
- No busy guard: archive is accepted in every probed state (idle,
  just-prompted) — unlike fork's `40901`, a running session archives fine.
- Errors: unknown id → `40401` "session … does not exist" (**0.43.0:**
  without `stack` — see Response envelope), on
  both actions.

### Reload — `POST /api/v1/sessions/{id}:reload`

**New in 2.3.0** (commit `18c6d642`; verified live 2026-10-22). Closes and
resumes the session server-side — config, plugins and workspace catalogs are
re-read, and engine change notifications (AGENTS.md/skills/sub-agent changes)
are injected into the agent context afterwards, surfacing as ordinary session
messages on the stream. Body `{}`. Answers the **standard session envelope**
(same shape as create/get) on success:

```json
{"code":0,"msg":"success","data":{"id":"session_074efb5c-…","workspace_id":"wd_spike_6baf4c130e63","title":"…","busy":false,"metadata":{"cwd":"/home/ubuntu/spike"},"agent_config":{"model":""},"usage":{…},"permission_rules":[],"message_count":0,"last_seq":0},"request_id":"01M5JJ2APWHHDGYTYWZWZHCFNQ"}
```

- Busy guard: a turn or compaction in flight anywhere in the session →
  **40901**, verbatim
  `{"code":40901,"msg":"Session \"session_…\" cannot be reloaded while a turn is running","data":null,…}`
  (with `stack`).
- Unknown id → `40401`.
- The reload clears the broadcaster's in-flight-turn tracker and sub-agent
  roster (the lock commit's `reattachSession`), so post-reload snapshots and
  resyncs no longer report stale running turns/sub-agents.
- The Android app uses it for stale-session send recovery: a 40932 send
  failure posts `:reload`, then applies one fresh snapshot resync (ticket 168).

### Takeover — `POST /api/v1/sessions/{id}:takeover`

**New in 2.3.1** (commit `c569bab5`; verified live 2026-10-23). Evicts the
cross-process occupancy lock another Kimi Code process holds on the session,
so a write that failed with **40931** can be retried — the escape hatch for
locks left behind by a crashed or exited process. Body `{}`. Success answers
`{"taken_over": true}`:

```json
{"code":0,"msg":"success","data":{"taken_over":true},"request_id":"01M5N3S6YYRS51A8W7HVHJ6H50"}
```

- Verified live 2026-10-23 end-to-end: a `kimi -S <id> -m kimi-code/k3 -p`
  CLI turn held the lock (`clientDisplayName:"Kimi Code CLI"`); a server
  prompt POST answered 40931; `:takeover` answered the envelope above and
  **deleted the lock file**; the same prompt POST then ran (`status:
  "running"`). If the other process is still alive, both write to the
  session until it goes idle — the upstream doc warns the history can
  interleave, so take over only when the holder is known gone.
- Idle session (no lock anywhere) → the same `{taken_over:true}` — a no-op
  success, not an error.
- Unknown id → `40401` `session <id> does not exist`. Note this route's
  `40401` **carries `stack`** (a fresh Error2 from the action handler),
  unlike the get-session path's stack-less `40401`.
- Takeover by the process that itself holds the lock is a silent no-op
  (`heldSessions` short-circuit — verified live: the server's own hold was
  left in place).
- The TUI flow this route serves (same commit): first 40931 → Retry/Cancel;
  a retried 40931 → Cancel/Take over ("danger" tone). Ticket 169 ports it.
- The lock can be turned off server-wide with `KIMI_CODE_SESSION_LOCK=0`
  (same commit; env switch, wire-invisible).

### Occupancy lock — 40931 / 40932

**New in 2.3.0** (commit `df0ff3c5`; verified live 2026-10-22). Sessions are
now guarded by a cross-process lock: while a process works a session turn, a
lock file lives at `~/.kimi-code/locks/sessions/<session-id>.lock` —
verbatim content (the holder block the 40931 envelope echoes):

```json
{"version":1,"token":"368178:b549243e-a8b0-4c6e-8504-4d279dc1e4db","pid":368178,"hostname":"host.example","startedAt":1792699474275,"clientIdentity":"kimi_code_cli"}
```

**2.3.1 (commit `0e3980e7`; verified live 2026-10-23):** the holder block —
in the lock file AND echoed in the 40931 `details.holder` — gained an
optional **`clientDisplayName`**, and the 40931 `msg` names it when known
(was a fixed "another Kimi Code process" sentence). The mapping:
`kimi_code_cli` → "Kimi Code CLI", `kimi_code_desktop` → "Kimi Code
Desktop", `kimi_code_vscode` → "Kimi Code for VS Code", and the embedded
server (`kimi_code_cli` + user-agent suffix `web`) → **"Kimi Code Web"**.
Verbatim 2.3.1 capture, the holder a live `kimi -S <id> -p` CLI turn:

```json
{"code":40931,"msg":"This session is in use by another Kimi Code CLI instance. Stop the session in that instance or exit it, then try again.","data":null,"request_id":"01M5N459GDSZ6VXF4G6S099YQ6","stack":"Error2: …","details":{"sessionId":"session_1c9f1b2b-…","holder":{"version":1,"token":"396620:1162edc7-…","pid":396620,"hostname":"host.example","startedAt":1792784114990,"clientIdentity":"kimi_code_cli","clientDisplayName":"Kimi Code CLI"}}}
```

The envelope's `code`/`details.sessionId`/`details.holder` positions are
unchanged — additive field plus a message-text change. Also verified live on
2.3.1: a lock whose holder pid is **dead** no longer blocks — the server
reclaimed one mid-probe and ran the prompt (the release's zombie-occupancy
fix, commit `c569bab5` — `pidAlive` now also rejects zombie-state processes
via `ps`; the create route itself briefly holds a server-side lock,
`clientDisplayName:"Kimi Code Web"`, released as soon as the session goes
idle).

- **40931 `SESSION_OCCUPIED`** — a write (prompt POST, profile write, …)
  against a session another process currently holds fails with a fixed
  English message and a top-level **`details` envelope field** (new on
  Error2 envelopes in 2.3.0, exposed for 40931/40932 only — Zod `40001`s
  already carried `details`) naming the holder — verbatim capture in the
  drift section above. The lock is held only while the other process's turn runs:
  an idle session's lock file is gone, and once the holder exits the same
  write succeeds (both verified live with a `kimi -p` CLI turn as the
  holder).
- **40932 `SESSION_STALE`** — the session's on-disk state moved under the
  holder (another process wrote to it). kap-server wraps prompt submit,
  skill activation and `:compact` in `reloadOnStale`: the server
  closes+resumes the session and **retries internally — those routes never
  return 40932** (verified live: after a CLI turn appended to the session,
  a server prompt POST answered `code:0` and ran normally). Only `:undo`
  still returns 40932 after reloading (not probed; the app has no undo).
- The WS error-code enum gained `session.occupied` (registered retryable)
  and `session.stale`; both can therefore also appear in WS error payloads.
- The same commit widened the error mapping: `session.fork_active_turn` →
  40901, `compaction.unable` → the 40910 family, `file.not_found` → 40407,
  skill not-found/name-empty → their skill codes, `validation.failed` →
  40001 — these paths previously surfaced `50001`.

### Session profile — `POST /api/v1/sessions/{id}/profile`

Writes session-level agent config — the per-session permission-mode override
the chat permission-mode picker (ticket 141) writes through as
`agent_config.permission_mode`. Verified live 2026-09-15 on 0.43.0 with a
throwaway session created and deleted for the probe.

```bash
curl -s -X POST 'http://127.0.0.1:58627/api/v1/sessions/session_ff2d15fc-.../profile' \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"agent_config":{"permission_mode":"manual"}}'
```

Answers the standard session envelope (same shape as create/get) — verbatim
capture, trimmed:

```json
{"code":0,"msg":"success","data":{"id":"session_ff2d15fc-...","workspace_id":"wd_spike_6baf4c130e63","title":"ticket-143-profile-capture","created_at":"2026-09-15T16:37:19.540Z","updated_at":"2026-09-15T16:37:19.540Z","busy":false,"main_turn_active":false,"pending_interaction":"none","archived":false,"metadata":{"cwd":"/home/ubuntu/spike"},"agent_config":{"model":""},"usage":{…},"permission_rules":[],"message_count":0,"last_seq":0},"request_id":"01M2JYW67APC4C06A2Y9RXEX96"}
```

- The write goes through live: **2.5.0** pushes an `agent` frame carrying
  the new `permission_mode` (the `/status` read-back no longer exists;
  `GET /api/v1/config`'s `default_permission_mode` is the fresh-session
  default). Historical: **2.2.0** pushed an `agent.status.updated` frame
  with `"permission":"manual"` (commit `a940f2ff`) — that frame is gone.
- Quirk: the response's `agent_config` does **not** echo the written
  `permission_mode` (stays `{"model":""}` in the capture) — `/status`'s
  `permission` is the read-back, not the session object.
- The accepted enum is the wire enum `manual`/`yolo`/`auto` (label mapping
  recorded once at the 0.40.1 drift notes, commit `08a2fb00`; the per-prompt
  `permission_mode` override in Prompts takes the same values).

### Session goal — `GET|PATCH /api/v1/sessions/{id}/goal`

**New in 2.5.0** (`GET` verified live 2026-11-04; `PATCH` source-derived).
`GET` returns `data: null` when the session has no goal; otherwise the goal
object: `{objective, status:"active"|"paused"|"blocked"|"complete",
completion_criterion?, turns_used, tokens_used, wall_clock_ms}`. The same
object rides the `goal` WS frame. `PATCH` edits the current goal's objective
in place (commit `07723966`); the app does not call either route.

### Session warnings — `GET /api/v1/sessions/{id}/warnings`

**New in 2.5.0**; returns `{"warnings":[]}` on a healthy session. Entries
are session-level config/plugin warnings (shape not exercised live beyond
the empty list). Diagnostic; unused by the app.

### Title generation — `POST /api/v1/sessions/{id}/title/generate`

**New in 2.5.0** (unavailable path verified live; success source-derived);
asks the managed provider's `chat_title` tool to (re)title the session. Body
`{force?: boolean, source?: "user_prompts"|"first_turn"|"digest"}`; success
`{"title": "…"}` and a `session.meta.updated` broadcast.
Unavailable without a managed OAuth login, prompt content, or with an
existing title and no `force` → **`40923`** (verified live:
`"session title generation is unavailable (no managed OAuth login, no prompt
yet, or the backend request failed)"`). Unused by the app (titles are
generated automatically).

### Live status — `GET .../status` (**removed in 2.5.0**)

**2.5.0: this route is gone** — `GET /api/v1/sessions/{id}/status` is a plain
Fastify HTTP 404. The same state is pushed on the session subscription: the
`agent` frame carries `status` (`idle`/`running`), `model`, `thinking`,
`permission_mode`, `plan_mode`/`swarm_mode`/`tower_mode`; the `usage` frame
carries `context_tokens` + `max_context_tokens`. A cold client seeds the same
values by subscribing (the frames arrive before the ack). The historical shape
below is kept for older journals only.

Lightweight runtime state:

```bash
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:58627/api/v1/sessions/session_b6be6715.../status
```

```json
{
  "code": 0, "msg": "success",
  "data": {
    "busy": false,
    "model": "kimi-code/k3",
    "thinking_level": "high",
    "permission": "manual",
    "plan_mode": false,
    "swarm_mode": false,
    "tower_mode": false,
    "context_tokens": 25050,
    "max_context_tokens": 1048576,
    "context_usage": 0.023889541625976562
  },
  "request_id": "01KY6GB8PGD0PV9FGE5XPX55Z3"
}
```

`busy: true` ⇔ the session is mid-turn. `model` is omitted entirely when the
session has no model configured (re-verified 2026-09-01 on a fresh
never-prompted session: no `model` key, `thinking_level: ""`). A prompt
carrying `model`/`thinking` binds the session: afterwards `/status` reports
both (verified 2026-09-01, `kimi-code/k3-256k` + `"low"`). The binding is
**persisted across the cold/warm boundary**: a session untouched since server
start reports `agent_config.model: ""` on list/get, yet its first `/status`
call already serves the real `model`/`thinking_level` and warms the session
(verified 2026-09-01, ticket 107). `thinking_level` and `permission` reflect
the session's current config (`"off"`/`"yolo"` observed on a fresh session;
**0.34.0:** a fresh session now reports `thinking_level: ""`). `permission`
is the session's effective permission mode (wire enum `manual`/`yolo`/`auto`;
the CLI label mapping is recorded once at the 0.40.1 drift notes, commit
`08a2fb00`) — it starts from the server's `default_permission_mode` and is
overridden per session via `POST .../profile` (see Session profile above).
The app does not consume it yet; the chat permission-mode picker reads and
writes it (ticket 141). **0.34.0:**
`max_context_tokens` is now **omittable when the context limit is unknown**
(upstream schema change — `0` is the engine's "unknown" marker, never a real
limit); never observed omitted live — a fresh model-less session reports the
default model's context (262144 on 0.34.0–0.36.1; 1000000 on 0.37.2–0.39.0,
the `deepseek/deepseek-v4-pro` default; 1048576 on 0.39.1, the
`kimi-code/k3` default; **262144 on 0.40.1** — the default model is
`kimi-code/k3-256k` now; config drift, not protocol) and a k3 session 1048576 (re-verified
2026-08-18). **0.38.0:** **`context_usage` is now omittable too** (same
commit `4ff06f17`, same "unknown" rule — upstream schema made it optional;
still present on this server, `0` on a fresh session, re-verified 2026-08-20).
Clients should
still treat an absent/zero limit as "unknown", never render a 0/0 ring.
**0.39.0:** `tower_mode` (boolean) rides next to `plan_mode`/`swarm_mode` —
the new tower mode (commits `0f44537c`/`d3b27cc7`, flag `tower`; `false`
here, verified live 2026-08-27). Additive; the Android app decodes none of
the mode flags.

### Session snapshot — `GET .../snapshot` (**removed in 2.5.0**)

**2.5.0: this route is gone** — a plain Fastify HTTP 404. There is no single
replacement bundle. The cold-start surfaces are:

- `GET .../transcript?agent_id=<id>` for the newest turn page plus the
  unpaginated session state: `interactions`, `pending_interactions`,
  `attachments`, `prompts`, `agents` (roster) and `seq` (the op watermark for
  `transcript_since`). A running turn is included with `state:"running"`.
- `GET .../messages` for the flattened history page (unchanged).
- The frames that follow an accepted `subscribe_v2`: `agent`, `usage`, `goal`,
  `todo`, `task.list`, `transcript.reset` (whose `snapshot` carries
  `items: []`, plus `interactions`, `attachments`, `prompts`, `hasMoreOlder`)
  and standalone `interaction` re-sends.
- The `subagents` roster is now the `agents` array on `/transcript`
  (`{agentId,type:"main"|"sub",parentAgentId?}`) plus `task` frames with
  `kind:"subagent"`; `in_flight_turn` has no REST replacement — the running
  turn (steps/frames) comes from `/transcript` and the live op stream.

The historical shape below is kept for older journals only.

The whole session state as one atomic bundle at an event-stream watermark —
designed as the client's cold-start/resync entry point (adopted by the Android
app in ticket 085): everything the event stream never replays
(`subagent.spawned` is never replayed before the resume cursor) or never
persists into history arrives here, and the watermark seeds the WS resume
cursor so the first subscribe stitches gaplessly onto the bundle. All shapes
verified live on 0.31.1 (2026-08-01). Top-level keys of `data`:

- `as_of_seq` (long), `epoch` (string): the watermark the bundle is consistent
  at — resume the WS subscribe with exactly this cursor.
- `session`: the rich session object (superset of the list/get one: `busy`,
  `main_turn_active`, `pending_interaction`, `last_turn_reason`, `usage`, …).
  When it carries a real fill, the context numbers live at
  `session.usage.context_tokens` / `session.usage.context_limit` — NOT the
  `/status` naming (`context_tokens`/`max_context_tokens`). **0.38.0 drift
  (commit `4ff06f17`): the all-zero placeholder is gone — the snapshot now
  serves REAL usage** (verified live 2026-08-20: after a turn
  `input_tokens`/`context_tokens` report actuals and `context_limit` the real
  window, e.g. 262144; on a fresh session `context_tokens: 0` with
  `context_limit` already set). In the same commit the usage schema made
  **`total_cost_usd`, `context_limit`, and `turn_count` optional** — the
  snapshot's usage omits `total_cost_usd` and `turn_count` entirely (the
  list/create/get session object still emits all eight fields, zeroed).
  The snapshot `session` also gained **`agent_config: {model}`** (`""` when
  unset). Verbatim 0.38.0 snapshot `session` after two completed turns
  (2026-08-20, k3 session; `title`/`last_prompt` trimmed):

  ```json
  {"id":"session_59e4fb8e-…","workspace_id":"wd_spike_6baf4c130e63",
   "busy":false,"main_turn_active":false,"pending_interaction":"none",
   "last_turn_reason":"completed","archived":false,
   "metadata":{"cwd":"/home/ubuntu/spike"},
   "agent_config":{"model":"kimi-code/k3"},
   "usage":{"input_tokens":6646,"output_tokens":286,"cache_read_tokens":95232,
            "cache_creation_tokens":0,"context_tokens":25843,"context_limit":1048576},
   "permission_rules":[],"message_count":0,"last_seq":0}
  ```

  History: **0.32.0** introduced the all-zero placeholder on every
  read path (verified live 2026-08-05 on busy AND idle sessions
  with 48k–121k real context tokens per `/status`); the 0.38.0 real-usage
  change reverses that. The Android app still takes the live context fill
  from `/status` and the WS `agent.status.updated` stream and defaults every
  usage field, so the optional-field omission decodes cleanly.
- `messages`: the newest history page (~100 items per page) with `has_more` —
  same item shape as `/messages` but **opposite order: `items` are OLDEST-first**
  (ascending ids/`created_at`; live-verified on 0.31.1, 2026-08-02 — `/messages`
  pages are newest-first). Do not reverse them client-side.
- `in_flight_turn`: `null` when idle; while a turn runs, its live tail —
  verbatim busy capture (2026-08-01):

  ```json
  {"turn_id":1,"assistant_text":"","thinking_text":"Simple. Run sleep 60. …",
   "running_tools":[{"tool_call_id":"tool_eIsX6WQ4HS0YXdIR7yJTtNrQ","name":"Bash",
     "args":{"command":"sleep 60","timeout":90},"description":"Running: sleep 60",
     "display":{"kind":"command","command":"sleep 60","cwd":"/home/ubuntu/spike","language":"bash"}}],
   "current_prompt_id":"msg_01KYYX1AMRSFSE8XAQWGRP8F6N"}
  ```

- `subagents`: the foreground sub-agent roster at the watermark — see
  [Session snapshot roster](#session-snapshot-roster) under Sub-agents.
- `pending_approvals`: the pending permission requests. The snapshot keeps
  the **pre-2.4.0 REST item shape** (the live event is the flat `interaction`
  frame now): the old `event.approval.requested` payload minus the camelCase
  `agentId` stamp (up to 0.38.0 `approval_id` == `tool_call_id`; **0.39.0:**
  `approval_id` is a dedicated `approval_<uuid>` id distinct from
  `tool_call_id` — see Interactions). **2.0.2:** the items now carry
  **`agent_id`** (snake_case, required — `"main"` or the asking sub-agent's
  id; verified live 2026-09-19), so the asking agent is no longer WS-only
  (this retro-fills ticket 139's gap). Verbatim 2.0.2 item:

  ```json
  {"approval_id":"approval_58b631f8-2537-4ed6-bdca-b3f06be56c71","session_id":"session_…",
   "agent_id":"main","turn_id":2,"tool_call_id":"tool_aTSHHtVv3uhqcac3axTAXWBV",
   "tool_name":"Bash","action":"Running: rm -rf /home/ubuntu/spike/approval-probe-2",
   "tool_input_display":{"kind":"command","command":"rm -rf /home/ubuntu/spike/approval-probe-2",
                         "cwd":"/home/ubuntu/spike","language":"bash"},
   "created_at":"2026-09-19T19:33:50.309Z","expires_at":"2026-09-20T19:33:50.309Z"}
  ```

  Pre-2.0.2 capture (no agent field at all — the items were built by
  kap-server's `toWireApproval`, which had none; verified upstream at
  0.43.1 — ticket 139):

  ```json
  {"approval_id":"tool_EYGD…","session_id":"session_…","turn_id":0,
   "tool_call_id":"tool_EYGD…","tool_name":"Bash","action":"Running: sleep 90",
   "tool_input_display":{"kind":"command","command":"sleep 90","cwd":"/tmp","language":"bash"},
   "created_at":"2026-08-01T…","expires_at":"2026-08-01T…"}
  ```

- `pending_questions`: the pending `AskUserQuestion` requests, in the same
  item shape the pre-2.4.0 `event.question.requested` frame carried (the
  per-session poll route is gone; this snapshot and the `interaction` frames
  are the only surfaces) — verified live with a pending question on 2.4.0,
  and again on 2.4.1 (2026-10-30, options carried the optional
  `description`): `question_id`, `session_id`, `questions[]` with
  `id`/`question`/`options[{id,label,description?}]`/`header`/`allow_other`/`multi_select`,
  `created_at`, `turn_id`. **0.38.0:** `question_id` is a dedicated
  `question_<uuid>` interaction id and the item also carries a distinct
  `tool_call_id` (was: `question_id` == `tool_call_id` — see Interactions).
  **2.0.2:** the item also carries `agent_id` (snake_case; verified live
  2026-09-19).

### Session table (v2) — `GET /api/v2/sessions`

**New in 0.34.0** (upstream #2640/#2644, built for the kimi-inspect tool's
spreadsheet-like session table). GET-only (`POST` → plain 404), wrapped in
the **v1 response envelope**. A leaner, cold-storage-friendly session
listing: epoch-**ms** timestamps, `null` (not absent) unset fields, no usage
block. The Android app uses it since ticket 115 — the grouped-by-workspace
session list calls `view=by_workspace&group.page_size=5` (verified live
2026-08-06).

```bash
curl -s -H "Authorization: Bearer <token>" \
  'http://127.0.0.1:58627/api/v2/sessions?page_size=1'
```

```json
{"code":0,"msg":"success",
 "data":{"items":[
   {"id":"session_3cc24efe-f493-425f-8b27-ff571884a1ce",
    "workspace":{"id":"wd_kimi-android-client_479bb0bcfde4","cwd":"/home/ubuntu/kimi-android-client"},
    "meta":{"title":null,"last_prompt":null,"created_at":1786044249808,
            "updated_at":1786044440384,"archived":false},
    "activity":{"status":"idle"}}],
  "has_more":true,
  "next_page_token":"eyJ2IjoxLCJmIjoi…"},
 "request_id":"01KZC8QQD9DPQWP6HGBWTHTMV3"}
```

- Query params (0.38.0 additions marked): `page_size` (1–100, default 50;
  `0`/over-100 → `40001`; **with `fields=id,archived` the ceiling relaxes to
  10000**; **0.38.0: under `view=by_workspace` it counts groups per page**),
  `sort` (`meta.updated_at_desc` default | `meta.updated_at_asc` |
  `meta.created_at_desc`; anything else → `40001` listing the options),
  `page_token` (opaque base64 **v1-fingerprint keyset cursor** — decodes to
  `{"v":1,"f":"<fingerprint>","k":[<updated_at_ms>,"<session_id>"]}`;
  multi-page continuation verified, no overlap), **`page` (0.37.2: stateless
  1-based page number; mutually exclusive with `page_token` — combining them →
  `40001`; `next_page_token` is always `null` in this mode)**,
  **`meta.updated_before` (0.37.2: epoch ms upper bound on `meta.updated_at`)**,
  **`fields` (0.37.2: only `id,archived`, trimming each item to
  `{id, archived}`; any other field/combination → `40001`; not combinable with
  `include=git`)**, **`meta.has_prompt` (0.38.0: `true` keeps only sessions
  that carry a user prompt — the v1 `exclude_empty` equivalent; `false` keeps
  only empty ones)**, **`view` (0.38.0: `flat` default | `by_workspace`, see
  below)**, and **`group.page_size` (0.38.0: sessions per workspace under the
  grouped view, 1–100, default 5, up to 10000 with the `id,archived`
  projection; rejected without the grouped view → `40001`)**. The 0.39.1 web
  bundle additionally filters by **`workspace.id=<id>`** (one workspace's
  sessions) and **`activity.status=<value>`** (sessions in that live state) —
  from the bundle's `listSessionGroupsV2`, 2026-09-01. Items are ordered by
  `meta.updated_at` desc by default. Unknown params (`limit`, `cursor`,
  `workspace_id`, `archived`, `q`, …) are **silently ignored**.
- Bad page tokens are a **new error code `40922`** (HTTP 200, envelope;
  distinct from search's `40001`): undecodable → `"page_token is corrupted;
  discard it and restart from the first page"`; valid-base64-but-foreign →
  `"page_token does not match the query conditions; …"`.
- `activity.status` value space: **`"idle"` / `"running"` / `"approval"` /
  `"question"`** — it carries the pending-interaction state (`"running"`
  verified live 2026-09-01, the rest from the 0.39.1 web bundle's mapper; the
  earlier "observed only as idle" claim was stale). `title` / `last_prompt`
  are `null` when unset. **0.39.1 (additive, verified live):**
  `activity` gained a **`model`** field — the session's bound model, `""`
  when unset (commit `2bf7ed22`).
- **0.37.2:** every page carries **`total`** (the size of the filtered set);
  with `fields=id,archived` each item is just `{id, archived}` (verified live).
- **0.38.0: `view=by_workspace`** (commit `15da8460`; verified live
  2026-08-20): the same filtered, sorted set re-projected into per-workspace
  groups. `data` becomes `groups[]` + `total` (the group count) + `has_more` +
  `next_page_token`; each group is `{workspace:{id,cwd}, sessions[],
  total}` with the workspace's first `group.page_size` sessions under the
  requested `sort` and the workspace's full matching-session count. Only
  workspaces with at least one matching session appear; groups order by their
  first session's sort key. `page`/`page_token` paginate over groups with the
  same fingerprint binding (the token also covers `view` and the grouping
  parameters — flipping them mid-pagination → `40922`). Verbatim (trimmed):

  ```json
  {"code":0,"msg":"success",
   "data":{"groups":[
     {"workspace":{"id":"wd_spike_6baf4c130e63","cwd":"/home/ubuntu/spike"},
      "sessions":[
        {"id":"session_a42f8560-…",
         "workspace":{"id":"wd_spike_6baf4c130e63","cwd":"/home/ubuntu/spike"},
         "meta":{"title":null,"last_prompt":null,"created_at":1787253993333,
                 "updated_at":1787253993333,"archived":false,"archived_at":null},
         "activity":{"status":"idle"}}],
      "total":42}],
    "total":7,"has_more":true,"next_page_token":"eyJ2IjoxLCJmIjoi…"},
   "request_id":"…"}
  ```

  (Note: the 0.38.0 v2 item `meta` now carries **`archived_at: null`** when
  never archived — present-but-null, unlike the v1 session object where the
  key is absent for never-archived sessions.)
- **2.5.1: deleted-workspace sessions are filtered out** (commit
  `b4e4a84f`; verified live 2026-11-05). Flat lists, `view=by_workspace`
  groups and the `fields=id,archived` projection all skip a session whose
  `workspace_id` is no longer registered (aliases count as registered), and
  `total` is computed after the filter; a workspace whose only sessions are
  orphaned disappears from the grouped view entirely. `GET
  /api/v1/sessions/{id}` still serves the hidden session.

### Batch archive/restore — `POST /api/v2/sessions:archive` / `:restore`

**New in 0.37.2** (commit `eaa3969d`; verified live). Body
`{"ids":["session_…"]}` — non-empty, ≤5000 unique ids (duplicates collapse).
Only a body-validation failure fails the whole request (`40001`); otherwise the
response is per-item: `data.results` keeps input order with `{id, ok}` or
`{id, ok:false, error}` (an unknown id reports `40401` in its own item), plus
`succeeded`/`failed` counts:

```json
{"code":0,"msg":"success",
 "data":{"results":[{"id":"session_…","ok":true}],"succeeded":1,"failed":0},
 "request_id":"…"}
```

## Workspaces

### List — `GET /api/v1/workspaces`

Returns every workspace the server knows about (verified live 2026-07-30,
server `0.31.0`; re-verified unchanged on `0.31.1`, 2026-07-31). Requires the token.

```bash
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:58627/api/v1/workspaces
```

```json
{
  "code": 0, "msg": "success",
  "data": {
    "items": [
      {
        "id": "wd_ubuntu_ce4fda485303",
        "root": "/home/ubuntu",
        "name": "ubuntu",
        "created_at": "2026-07-08T05:03:06.127Z",
        "last_opened_at": "2026-07-23T03:24:59.583Z",
        "session_count": 83
      }
    ]
  },
  "request_id": "01KY6G58BMA06WSJC5C8YKTPA6"
}
```

- Items come back roughly recency-ordered but **clients must not rely on the
  server order** — sort by `last_opened_at` descending client-side.
- `name` may be empty (observed: a workspace with root `/`); clients should
  fall back to `root` for display.

### Trust — `GET|POST /api/v1/workspaces/{id}/trust`, `POST .../untrust`

**New in 0.31.1** (upstream "Workspace domain" work). `GET` reads the trust
state, `POST .../trust` marks trusted, `POST .../untrust` revokes; all answer
`{"trusted": true|false}` (verified live 2026-07-31 — a healthy workspace
reads `{"code":0,"data":{"trusted":false}}`; sessions run fine either way,
trust only gates project-level MCP config files). Error paths: unknown
workspace → `40410`; a workspace whose root no longer exists → `50001` whose
envelope **leaks a `stack` field** (the first route observed doing so; since
0.32.0 every Error2 domain-error envelope carries one — see Response
envelope).

### Add a directory — `POST /api/v1/workspaces/{id}/add-dir`

**New in 0.40.1** (upstream commit `a23ff563`; every behavior below verified
live 2026-09-02 on 0.40.1). Adds an extra root directory to a workspace.
Body: `{"path": "<dir>", "persist": <bool, optional>}` — `path` accepts a
relative path (resolved against the workspace root) and `~`/`~/…`
(expanded); `persist` defaults to `true` and appends the dir to
`<root>/.kimi-code/local.toml` (`[workspace] additional_dir = [...]`).

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/workspaces/wd_spike_6baf4c130e63/add-dir \
  -H "Authorization: Bearer <token>" -H 'Content-Type: application/json' \
  -d '{"path":"subdir-a"}'
# {"code":0,"msg":"success","data":{"project_root":"/home/ubuntu/spike",
#  "config_path":"/home/ubuntu/spike/.kimi-code/local.toml",
#  "additional_dirs":["/home/ubuntu/spike/subdir-a"],"persisted":true},"request_id":"…"}
```

Errors (all HTTP-200 envelopes): empty `path` → `40001` with `details`;
missing/non-directory path → **`40409`** `"path <p> does not exist"` (same
code as session-create's unknown `cwd`, different message); unknown
workspace → `40410` (same code as the trust route's). The Android app never
calls this route.

## Messages

### History — `GET /api/v1/sessions/{id}/messages`

Query params (all optional): `page_size`, `before_id`, `after_id`, `role`.
**Items are returned newest-first**; page backwards with `before_id`.

```bash
curl -s -H "Authorization: Bearer <token>" 'http://127.0.0.1:58627/api/v1/sessions/session_b6be6715.../messages?page_size=10'
```

Message shapes (0.29.0, verbatim from one turn — user prompt, assistant tool
call, tool result, assistant text):

```json
{"id":"msg_01KY6G6B4A1EM8X8XRA02KJ3ZR","session_id":"session_b6be6715-...","role":"user",
 "content":[{"type":"text","text":"Run exactly this one command, then reply done: echo doc-verify-0290"}],
 "created_at":"2026-07-23T03:27:03.317Z","metadata":{"origin":{"kind":"user"}}}

{"id":"msg_session_b6be6715-e7f3-450c-a103-a53463686d45_000001","session_id":"session_b6be6715-...","role":"assistant",
 "content":[{"type":"thinking","thinking":"Simple."},
            {"type":"tool_use","tool_call_id":"tool_RMgBIy1asIorg1eV8P3ZhHiL","tool_name":"Bash",
             "input":{"command":"echo doc-verify-0290"}}],
 "created_at":"2026-07-23T03:27:03.329Z"}

{"id":"msg_session_b6be6715-e7f3-450c-a103-a53463686d45_000002","session_id":"session_b6be6715-...","role":"tool",
 "content":[{"type":"tool_result","tool_call_id":"tool_RMgBIy1asIorg1eV8P3ZhHiL","output":"doc-verify-0290\n"}],
 "created_at":"2026-07-23T03:28:28.486Z"}

{"id":"msg_session_b6be6715-e7f3-450c-a103-a53463686d45_000003","session_id":"session_b6be6715-...","role":"assistant",
 "content":[{"type":"thinking","thinking":""},{"type":"text","text":"done"}],
 "created_at":"2026-07-23T03:28:28.493Z"}
```

Roles observed: `user`, `assistant`, `tool`. Content is a list of parts; part
types observed:

- `{"type": "text", "text": "..."}` — user or assistant text. **2.2.0
  (additive, commit `06ebfc82`; source-derived, not observed live):** may
  also carry an optional **`meta`** object (`{source, contentType?,
  activationId?}`) marking skill-activation blocks and UserPromptSubmit
  hook output — the kap-server projections filter those parts by
  `meta.source`, so on a hook-less server they never appear.
- `{"type": "thinking", "thinking": "..."}` — assistant reasoning (may be empty).
- `{"type": "tool_use", "tool_call_id": "tool_...", "tool_name": "Bash", "input": {...}}` — assistant tool call.
- `{"type": "tool_result", "tool_call_id": "tool_...", "output": "..."}` — in a
  `role: "tool"` message; may also carry `"is_error": true`.
  **2.5.1 (commit `6dad2c8d`; verified live 2026-11-05):** `output` may be an
  **array of content parts** when the tool returned daemon media. A
  `ReadMediaFile` result for `/home/ubuntu/spike/one.png` captures verbatim as:

  ```json
  {"id":"msg_session_…_000006","role":"tool",
   "content":[{"type":"tool_result","tool_call_id":"ReadMediaFile_0_731303c4",
     "output":[
       {"type":"text","text":"<image path=\"/home/ubuntu/spike/one.png\">"},
       {"type":"image","source":{"kind":"session_media","file_id":"f_13df9359…"}},
       {"type":"text","text":"</image>"}]}],
   "created_at":"2026-11-05T19:27:50.461Z"}
  ```

  The same array structure rides the transcript tool frames (see Transcript).
  Only daemon file refs are structured; base64/data URLs, `https` media and
  audio stay on the plain-string path. `name` is present when the ref carries
  one. Fetch the bytes with `GET /api/v1/sessions/{id}/media/{file_id}`.

**Message id format (since 0.28.1):** user messages keep the ULID form
(`msg_01KY...`), but server-generated messages (`assistant`, `tool`) get
sequential per-session ids `msg_<session_id>_<NNNNNN>` (zero-padded counter).
Treat ids as opaque strings — do not parse them. `metadata` is only present on
user messages (carries `origin`).

An aborted turn commits an assistant message with **empty `content`** when the
abort landed before any assistant text was produced (verified 2026-07-22).

### Internal envelopes in user messages

The server injects harness traffic into the transcript as **user-role text
messages** wrapped in generated XML envelopes (agent-core `ContextMemory` /
`renderNotificationXml` / `renderCronFireXml`). The web client hides them (by
`metadata.origin` for whole messages); the app strips the envelope blocks from
user text instead and drops the bubble when nothing remains (`InternalEnvelopes`).

Observed tags, all paired with attributes allowed on the opening tag:

- `<system-reminder>…</system-reminder>` — system reminders, hook outputs,
  image-compression captions (older transcripts inline the caption as
  `<system>Image compressed to fit model limits:…</system>` inside user text).
- `<notification id="…" category="…" type="…" source_kind="…" source_id="…">…</notification>` —
  background-task completions/failures; the body can nest `<output-file>` /
  `<output-preview>` blocks.
- `<local-command-stdout>…</local-command-stdout>`, `<bash-input>…</bash-input>`,
  `<bash-stdout>…</bash-stdout><bash-stderr>…</bash-stderr>` — TUI shell
  passthrough.
- `<kimi-skill-loaded name="…" trigger="…">…</kimi-skill-loaded>` — skill
  activation bodies (the web client surfaces only the invocation args).
- `<cron-fire …><prompt>…</prompt></cron-fire>` — cron injections (the web
  client renders the inner prompt as a dedicated cron turn; the app hides the
  envelope entirely for now).

**0.31.1 addition — interruption reminder:** after a user-cancelled turn
(`:abort`, or Esc in the TUI) the server appends a whole-message
`<system-reminder>` user message — verbatim body: "The previous turn was
interrupted by the user before completion; any partial output shown above is
incomplete. The user's next message continues the conversation." — carrying
`metadata.origin: {"kind":"injection","variant":"interruption"}` (verified
live 2026-07-31: present in `GET .../messages` after each abort, and pushed
live as a `context.spliced` echo right after `turn.ended`). It repeats per
cancelled turn and persists in history. **2.2.0 (additive, commit
`e3bf50c0`; verified live 2026-10-14):** the origin also carries
**`ownerPromptId`** — the aborted prompt's id, the link the TUI undo path
uses to drop the reminder with its turn
(`{"kind":"injection","variant":"interruption","ownerPromptId":"msg_..."}`
read straight out of `GET .../messages`). Sibling variant observed on **0.34.0**
(2026-08-06): `{"kind":"injection","variant":"permission_mode"}` — a
`<system-reminder>` user message injected when a turn runs under auto
permission mode (additive; the same envelope-stripping rule covers it).
Sibling variant observed on **0.43.0** (2026-09-14):
`{"kind":"injection","variant":"date_change","disclosure":{"kind":"date",
"renderGeneration":1,"localDate":"2026-09-14","timeZone":"UTC"}}` — a
whole-message `<system-reminder>` user injection the server splices when a
turn starts under a calendar date different from the previous turn's (it
fired on the throwaway session's first turn of the day), persisted in
history like the others (additive; the same envelope-stripping rule covers
it).

## Transcript

### Per-agent transcript — `GET /api/v1/sessions/{id}/transcript`

Turn-granular committed history for ONE agent — `"main"` or any sub-agent id —
which makes it the history backing of the sub-agent page (the `/messages`
history only ever carries the main agent). **Since 2.5.0 this is also the
cold-start read model**: it returns the session's pending interactions, the
prompt entities, the agent roster and the op watermark, replacing the removed
snapshot. Verified live on 0.29.2 (2026-07-28), including a running sub-agent
and the cold-session rebuild path; re-verified through 0.31.1 and again on
2.5.0 (2026-11-04), including a running turn and a pending question.

Query params:

- `agent_id` — **required**; omitted → `40001` ("expected string, received
  undefined"). Answers for any agent id, finished or running. An UNKNOWN id is
  not an error: `code:0` with `items: []`, `has_more: false` (an unknown
  session is `40401` as usual).
- `page_size` — default 20, min 1, max 100 (`0` or `>100` → `40001`). Counts
  **turns**; interleaved `marker` items ride along (a 20-turn page shipped 33
  items: 20 turns + 13 markers).
- `before_turn` / `after_turn` — turn-id cursors. Paging backwards past the
  oldest turn answers `items: []` with `has_more: false` (not an error).

Pagination contract (verified with `page_size=1` against a 2-turn sub-agent):
the cursor-less call returns the **newest** page, `has_more` tells whether
older turns exist, and `before_turn=<turnId>` returns the next older page.
**Within a page turns are ascending by `ordinal`** (oldest-first) — the
opposite of `/messages` intra-page order. `after_turn=t0&page_size=2` pages
forward (ascending, `has_more` true while newer turns remain).

`data` fields beyond `agent_id`/`items`/`has_more` — `interactions`,
`attachments`, `prompts`, `agents`, `pending_interactions`, `seq` (the 2.5.0
set) — are the session's **unpaginated global state**, shipped with every page.
**2.5.0 dropped `tasks`, `todos` and `meta`** from this projection; tasks are
WS `task` frames, todos are the `todo` frame. The `agents` roster is
`{agentId, type: "main"|"sub", parentAgentId?}` and now includes every child
agent. `pending_interactions` is an array of interaction ids; `interactions`
is the full entity list (see Interactions). `seq` is the agent's op-batch
watermark for `transcript_since` (live sessions only).
**0.39.0 (commit `2f124693`): `prompts` is new** — prompt entities
`{promptId, status, userMessageId, content, createdAt, finishedAt}` for the
session's turn-opening inputs, including steered-in prompts (status
`"completed"` once their host turn ends); prompts whose messages were
compacted away vanish from the list. **0.40.1 (commit `cbe0a77f`, "preserve
steer provenance"; verified live 2026-09-02):** a prompt that was steered
into a running turn carries **`steeredAt`**, and the *active* prompt it
merged into reports the steered text as its `content` (verified: the active
prompt `msg_…SX` showed the steered `"Also reply with the single word
steered."` instead of its own submitted text — the turn entity's `prompt`
still holds the original). Also **0.39.0:** `interactions[]`
question entries now carry `request` as the full wire question payload
(stable `q_N`/`opt_N_M` ids, `question_id`, `tool_call_id`, `created_at` —
commit `b3f08b68`; verified live on a pending question 2026-08-27) instead of
the raw `AskUserQuestion` tool input, and `tasks[]` entries gained optional
`model`/`thinkingEffort` (camelCase). **0.39.1 (commit `4e7738b7`; verified
live):** `attachments[]` entries for prompt **file** attachments are typed
file records — `{attachmentId, mediaType, name, size}` with **no `source`**
(media attachments keep `source:{kind:"session_media",fileId}`), so web
clients render chips without parsing the notice text; attachment ids are
inconsistent (`att_1` on the turn-0 entry, `t2.att1` later) — opaque. The
Android app consumes none of them.

`items` has two entry kinds: `turn` and `marker`.

Turn → step → frame structure (verbatim keys, running sub-agent capture):

```json
{"kind":"turn","turnId":"t0","ordinal":0,"state":"running",
 "origin":{"kind":"other","payload":{"kind":"system_trigger","name":"subagent"}},
 "startedAt":"2026-07-28T10:03:06.221Z",
 "steps":[
  {"kind":"step","stepId":"t0.1","turnId":"t0","ordinal":1,"state":"running",
   "startedAt":"2026-07-28T10:03:06.237Z",
   "frames":[
    {"kind":"thinking","frameId":"t0.1.f1","text":"The user wants me to run a sleep 45 command…"},
    {"kind":"tool","frameId":"t0.1.tool_4eOtxCMTEBHdpOehIhgnYAAe","toolCallId":"tool_4eOtxCMTEBHdpOehIhgnYAAe",
     "name":"Bash","state":"running",
     "input":{"command":"sleep 45 && echo awake","timeout":60},
     "inputText":"{\"command\":\"sleep 45 && echo awake\",\"timeout\":60}",
     "display":{"kind":"command","command":"sleep 45 && echo awake","cwd":"/home/ubuntu/kimi-android-client","language":"bash"}}]}]}
```

- Turn: `turnId` (`"t0"`, `"t1"`, …), `ordinal`, `state` (`"running"` /
  `"completed"` / `"cancelled"` / `"failed"` — the terminal two are real since
  0.31.1, see below), `origin`, `steps`. **0.40.1 (additive, commit
  `58b74cfe` "preserve turn prompt identity"; verified live 2026-09-02 on
  main and sub-agent turns alike):** every turn carries
  **`triggerPromptId`** — the prompt record id (`msg_…`) that opened it.
  **0.41.0 regression (verified live 2026-09-04):** sub-agent internal turns
  (origin `system_trigger/subagent`) **no longer carry `triggerPromptId`** —
  main-agent user turns still do. The app decodes the field nowhere.
  `origin` is `{"kind":"user"}`
  for user-prompted turns — those additionally carry a top-level **`prompt`**
  field with the prompt text (seen on main-agent turns) — or
  `{"kind":"other","payload":{"kind":"system_trigger","name":"subagent"}}` for
  a sub-agent's internal turns (the task turn and the forced result-summary
  turn; **0.39.0:** these now carry the orchestrator's **`prompt`** too —
  the `git-context` prefix + the task text, commit `f143130c`, verified live
  2026-08-27; up to 0.38.0 the task text was not in the transcript turns)
  — or a **`task` origin** on a background-task completion-notification turn
  (see the verification below).
- Step: `stepId` (`"t0.1"`), `turnId`, `ordinal`, `state`, `frames`.
- Frames: `thinking` (`frameId`, `text`), `text` (`frameId`, `role` —
  `"assistant"`/`"user"` — `text`, optional `promptIds` on a steered-in
  frame), `tool` (`frameId`, `toolCallId`, `name`, `state`
  `"running"`/`"done"`/`"error"` (2.5.0 added `"error"`), `input` (args
  object), `output`, optional `approvalId`/`taskId`/`display`/`progress`),
  and `notice` (2.5.0: `level`/`message`, used when a tool result has no
  displayable content — commit `b4da9215`). **2.5.1 (commit `6dad2c8d`;
  verified live 2026-11-05): a daemon-media tool result's `output` is an
  array of parts**, not a string —
  `[{"type":"text","text":"<image path=\"…\">"}, {"type":"image",
  "source":{"kind":"session_media","file_id":"f_…"}}, {"type":"text",
  "text":"</image>"}]` — on the live ops stream and the warm transcript (the
  history `tool_result.output` carries the same array; the same projection is
  applied in the cold fold per the commit's `groupTurns.ts` change —
  source-derived, not probed cold here).
  Non-daemon media and audio stay on the text path, and no `notice` frame
  accompanied the media result in the 2.5.1 capture.
  A step can hold several frames of the same kind (two `text` frames seen).
  **0.40.1 (additive, commit `cbe0a77f`; verified live 2026-09-02):**
  user-role text frames carry an optional **`origin`** — `{"kind":"user"}`
  observed on a steered-in frame; the schema also allows
  `skillActivations` provenance there (not triggered live).
  **0.39.0 (commit `5634cb55`): steered-in prompts project as mid-turn
  `{"kind":"text","role":"user","text":…}` frames** in the running turn's
  next step, carrying the new optional **`promptIds`** array (verified live
  2026-08-27:
  `{"kind":"text","frameId":"t7.2.f1","role":"user","text":"Also reply with exactly: steered-ok","promptIds":["msg_01M12C2W0M9YBYF3QQHPYWRK6P"]}`);
  up to 0.38.0 the steered text never appeared in the transcript. Only the
  main agent's transcript is affected — steer targets the active main turn.
- **Warm vs cold rebuild:** answers computed from the live session add
  `startedAt`/`endedAt` on turns and steps, `finishReason`/`usage`/timing on
  steps, and `inputText`/`display`/`progress` on tool frames; the cold-session
  rebuild from persisted wire records ships only `turnId`/`ordinal`/`state`/
  `origin`/`steps` on turns and the core frame fields (`input`+`output` on
  finished tool frames). Treat every field outside the core set as optional.
  **2.1.1 (commit `b428bfd0`; verified live 2026-09-24 on the warm path):**
  the step timing field is renamed **`timing` → `llmTiming`**
  (`{llmFirstTokenLatencyMs, llmStreamDurationMs}`, same inner shape) and
  steps carry **`usage`** (`{inputOther, output, inputCacheRead,
  inputCacheCreation}`) — and the **cold fold now projects both** from the
  journaled records (2.1.1-written journals only; older journals still ship
  neither). The app parses neither field.
  **0.31.1:** the engine now persists `turn.ended` records, so the cold
  rebuild also reports a turn's real terminal `state` (`"cancelled"` /
  `"failed"`; `"blocked"` folds into `"failed"`) plus `endedAt` / `durationMs`
  / `error` (message only) — previously every cold turn read `"completed"`.
  Journals written before 0.31.1 lack the records and keep the old default.

**`task` turn origin — verified live on 0.34.0 (2026-08-07, ticket 099):**
the background-task completion-notification turn ships its origin on the REST
transcript too (owning agent's transcript — here a sub-agent's,
`GET .../transcript?agent_id=agent-0`, session with two completed
sub-agent-owned background bash tasks; verbatim turn, steps trimmed):

```json
{"kind":"turn","turnId":"t1","ordinal":1,"state":"completed",
 "origin":{"kind":"task","taskId":"bash-xhlawxzk",
           "payload":{"kind":"task","taskId":"bash-xhlawxzk","status":"completed",
                      "notificationId":"task:bash-xhlawxzk:completed"}},
 "prompt":"<notification id=\"task:bash-xhlawxzk:completed\" category=\"task\" type=\"task.completed\" source_kind=\"background_task\" source_id=\"bash-xhlawxzk\">\nTitle: Background process completed\n…\n</notification>"}
```

Two differences from the WS `turn.started` origin (Background lifecycle): the
transcript shape wraps the origin in a **`payload`** object (the WS one is
flat) and additionally flattens `taskId` at the origin's top level; `status`
and `notificationId` live only inside `payload`. Only `"completed"` is
verified for `status` (the `notificationId` embeds it:
`task:<taskId>:<status>`) — a killed task whose notification turn never
landed ships no such turn at all. Note the turn carries a top-level `prompt`
(the `<notification …>` XML body) even though its origin is not `user` —
`prompt` is not user-origin-only.

Markers are interleaved between turns and must be tolerated, not rendered:

```json
{"kind":"marker","markerId":"m1","marker":"swarm.enter","payload":{"trigger":"tool"},"at":"2026-07-28T06:17:58.498Z"}
{"kind":"marker","markerId":"live-m1","marker":"undo",
 "payload":{"start":0,"deleteCount":0,"messages":[{"role":"user","content":[{"type":"text","text":"…"}],"id":"msg_01KYM2V437…","origin":{"kind":"system_trigger","name":"subagent"}}]}}
{"kind":"marker","markerId":"m1","marker":"interruption","payload":{"turnId":0,"target":"active","reason":"user_cancelled"},"at":"2026-07-31T19:29:25.032Z"}
```

**2.5.0:** `marker:"compaction"` brackets a manual compaction
(`payload:{phase:"started",agentId,trigger}` then
`payload:{phase:"completed",agentId,result:{…}}`), and the compaction's
wholesale splice is an `undo` marker whose messages include the summary
(`origin.kind:"compaction_summary"`) and the continuation injection
(`origin.kind:"injection", variant:"compaction_continuation"`). See Compact.

`swarm.enter`/`swarm.exit` bracket AgentSwarm runs on the main transcript;
`undo` markers carry context splices (the sub-agent's task prompt message —
git-context envelope included — shows up here, full `MessageDto` shape) and
persist after completion on the warm path. They vanish on the cold rebuild.
**0.39.0 (commit `c4885860`):** the live projection also drops the **undone
turns themselves** after an `undo` — previously they stayed visible until the
cold rebuild (schema-level; undo is TUI-only, not triggered live this run).
**0.31.1 adds `interruption` markers** — one per user-cancelled turn, on both
the warm and the cold path (verified live 2026-07-31 after two `:abort`s; the
`turnId` in the payload is the engine turn id, the cold path maps it to the
same numbering the turn items use).

Finished sub-agent, cold-rebuild path (throwaway session left from 045,
`agent_id=agent-15`, `page_size=3`, trimmed): two turns, both
`state:"completed"`, origin `system_trigger/subagent`, frames limited to
`thinking`/`text`/`tool` with `input`+`output`; `has_more:false`.

### Transcript catch-up — `GET /api/v1/sessions/{id}/transcript/ops`

The REST counterpart of `transcript_since`: `?agent_id=<id>&since_seq=<n>`
returns `{agent_id, batches:[{seq, ops}], latest_seq, complete}`. `complete:
true` means every batch up to `latest_seq` is present; `false` means the
journal cannot reach back to `since_seq` (or the session is not live) and the
client must do a full `GET .../transcript` refresh. Verified live: since 30
returned seq 31+ in order; since 0 returned seq 1+.

### Turn-opening inputs — `GET .../transcript/user-messages`

`?agent_id=<id>` (optional; default every rostered agent) returns
`{agents:[{agent_id, messages, attachments}]}` with every turn-opening input,
unpaginated. Each message is `{turn_id, ordinal, state, origin, prompt,
attachment_ids?, started_at?}`; `state` is `queued`/`running`/`completed`/
`failed`/`cancelled`. Verified live: the two turns of a throwaway session
listed in order with `origin:{kind:"user",payload:{kind:"user"}}`.

### Plan content — `GET .../transcript/plan`

`?agent_id=<id>` (required) and optional `tool_call_id` returns
`{agent_id, plans:[{tool_call_id, turn_id, source, plan, path?, options?,
review?}]}` in timeline order. `source` is `interaction`/`display`/`output`
(where the content was recovered from); `review` is
`{state:"pending"|"approved"|"rejected"|"cancelled", selected_option?,
feedback?}`; `options` are `{label, description?}`. Verified live: an empty
`plans:[]` on a session with no `ExitPlanMode` call. `40416` if a
`tool_call_id` names no `ExitPlanMode` call.

### Read one message — `GET /api/v1/sessions/{id}/messages/{message_id}`

Returns the same message object as the history page; `40403` when the id is
not in the session. Additive and unused by the app.

## Search

### Global message search — `POST /api/v1/search`

New in 0.31.0 (verified live 2026-07-30; re-verified 2026-08-05 on 0.33.0 —
two-page token walk, same-generation replay, tampered/foreign-fingerprint
token rejections). Global full-text search over user
messages, assistant replies and session titles, across all sessions.

**2.5.0: the global search index is off by default** (commit `be89803b`;
verified live 2026-11-04). Every call now answers
`{"code":50001,"msg":"global search is disabled; set [database] global_search = true or KIMI_CODE_GLOBAL_SEARCH=1 to enable it","data":null,…}`
with a `stack`, before any query validation. The request/response shapes below
apply only when the operator enables the index.

Request body (all fields optional except `query`):

- `query` (required) — the search text.
- `mode` — `terms` (default) | `literal`.
- `op` — `AND` | `OR` (how multiple terms combine).
- `container` — `{session_id, agent_id?}`: scope the search to one session
  (and optionally one agent). **Only `container.session_id` filters** —
  unknown top-level fields are silently ignored.
- `role` — `user` | `assistant` | `title`.
- `start_time` / `end_time` — epoch-ms bounds on the hit time.
- `sort` — `score` (default) | `time_desc` | `time_asc`.
- `page_size` — 1–50.
- `page_token` — continue from a previous response.

Response `data`:

- `items[]` — `{session_id, workspace_id, session_title, agent_id, role,
  snippet, time, turn?, step_id?, score}`. `agent_id` is `"main"`, a sub-agent
  id, or `""` for title hits; `role` is `user` | `assistant` | `title`;
  `time` is epoch ms; `turn` is 0-based and absent on title hits; `step_id`
  appears on assistant hits; `score` is a float (0 in `literal` mode).
- `has_more`, `page_token?` — the token is opaque base64 and present only
  when `has_more` is true. Since 0.33.0 it is a **v2 keyset cursor** pinned
  to an index generation (decodes to `{"v":2,"f":"<query fingerprint>",
  "g":<generation>,"b":[<score>,<time>,<key>]}`); legacy v1 offset tokens
  (`{"f":…,"s":…}`) are still accepted for a transition window, and responses
  always issue v2 tokens back. Treat it as opaque either way. Replaying a
  token within the same generation works (verified live); a token from an
  older generation is rejected (source-derived — the documented signal to
  restart the search); a tampered token answers HTTP 200 with
  `{"code":40001,"msg":"pageToken is malformed"}` (an Error2
  `GlobalSearchError` — it carries a `stack` string, unlike Zod-validation
  `40001`s; verified live), and a token whose query fingerprint doesn't
  match the request answers `40001 "pageToken does not match the query
  conditions; query conditions must not change mid-pagination"`.
- `incomplete?` — present when the result set was truncated; one of
  `"candidate_cap"` | `"postings_budget"` | `"deadline"` (the last two are
  new in 0.33.0, source-derived — no truncated search occurred live).
- `index_state` — `{state ("building"|"ready"|"readonly"), indexed_sessions,
  total_sessions, documents}`; since 0.33.0 it can also carry **`stale:
  true`** (the served page comes from an index generation known to be
  behind — observed live during a rebuild, absent otherwise) and
  **`degraded: "<error>"`** (the last background refresh failed; never
  observed, source-derived).
- `source` — `"index"` for global searches, `"live"` for container-scoped
  single-session scans.
- **0.43.1 (behavioral, commit `1336be38`; verified live 2026-09-15):**
  indexed hits are verified against their session sources at query time —
  results whose source session no longer exists are filtered out. Verified
  with a unique token planted in two sessions: after one was `:delete`d,
  the global search returned only the live session's user/assistant hits
  across two index refresh cycles, and a container-scoped search on the
  deleted session answered `code:0` with zero items. Before the fix,
  indexed hits from deleted sessions could linger until the index rebuilt.
  Shapes unchanged.

Verbatim `data` meta (items elided, verified 2026-08-05 on 0.33.0 — the
index was mid-rebuild, hence `stale:true`):

```json
{"has_more": true, "page_token": "eyJ2IjoyLCJmIjoiU0R1ZlRzcHVzNzJxX056dSIsImciOjEsImIiOlsxLjQz…",
 "index_state": {"state": "ready", "indexed_sessions": 291, "total_sessions": 291,
                 "documents": 6313, "stale": true},
 "source": "index"}
```

Validation: an empty query answers HTTP 200 with
`{"code":40001,"msg":"query: Too small: expected string to have >=1 characters","data":null,...,"details":[{"path":"query","message":"..."}]}`
(the usual envelope-plus-`details` validation shape).

## Files (attachments)

File upload backs message attachments: upload the bytes first, then reference
the returned `file_id` in a prompt's `content` items (see Prompts below).
**0.39.1:** prompts may alternatively attach **server-local absolute paths**
directly (no upload) — see Prompts; the upload flow below is unchanged. The
endpoints and the prompt-side rewriting were verified live on 0.29.0
(2026-07-24, session `attach-spike`) and re-verified on 0.31.0 (2026-07-30:
upload/download/`Range` 206/delete of a text file, plus a `file` attachment
prompt — the ACK echo and history carried the `Attached file "…"` notice
byte-for-byte, em dash included, and the bytes materialized under
`<sessionDir>/attachments/`); only the video rewrite and the
unsupported-image-format fallback remain source-derived (see caveats below).

### Upload — `POST /api/v1/files`

`multipart/form-data`, one file per request. Fields: `file` (binary, required),
`name` (optional display-name override), `expires_in_sec` (optional TTL).

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/files \
  -H "Authorization: Bearer <token>" \
  -F "file=@attach-check.txt" -F "name=attach-check.txt"
```

```json
{"code": 0, "msg": "success",
 "data": {"id": "f_e49777a6-…", "name": "attach-check.txt",
          "media_type": "text/plain", "size": 27,
          "created_at": "2026-07-24T11:56:55.720Z"},
 "request_id": "01KY9ZRNGF3Y5EA7V12YY8D7GX"}
```

- File ids are `f_` + ULID/UUID. Any MIME type is accepted. **There is no
  upload size cap anymore** — the 50 MiB limit was removed upstream ("remove
  the 50 MiB upload size cap and stream uploads to disk") and uploads stream
  to disk; the 413 / envelope-`41301` "upload too large" error no longer
  occurs on this route (the code survives for session export only). Verified
  live on 0.31.0 (2026-07-30) with a 55 MiB binary — HTTP 200 with the exact
  `size`:

```json
{"code":0,"msg":"success","data":{"id":"f_d3528325-75f6-482e-aaac-14c127488913","name":"big-55m.bin","media_type":"application/octet-stream","size":57671680,"created_at":"2026-07-30T19:28:29.936Z"},"request_id":"01KYT7ZTETCPH10NV0NHB0068N"}
```

- When `expires_in_sec` is sent, the FileMeta response adds an **`expires_at`**
  ISO timestamp; the field is absent otherwise.
- The returned `media_type` is **sniffed from the bytes**; the `name` field and
  file extension are ignored. On 0.29.0 the sniffer recognizes images and text
  but not video: even a valid ffmpeg-generated mp4 and a WebM upload come back
  as `application/octet-stream` (verified 2026-07-24), which makes the video
  prompt path unreachable via upload in this version (see Prompts).
  **0.42.0 (verified live 2026-09-09): BMP is likewise unrecognized** — a
  1×1 BMP upload comes back `application/octet-stream`, so the HEIC/HEIF/BMP
  acceptance the engine gained for Kimi models (upstream `80480c01`) is
  **not reachable through the upload flow** (an `image` part referencing such
  an upload is rejected `40001` "not an image", exactly like the video case).
- The server advertises this as `capabilities.file_upload: true` in
  `GET /api/v1/meta`.

### Download — `GET /api/v1/files/{file_id}`

Binary stream; supports `Range` (`accept-ranges: bytes` verified).
`content-disposition: inline` for image/video/audio, `attachment` otherwise.

### Delete — `DELETE /api/v1/files/{file_id}`

Response: `{"code":0,"msg":"success","data":{"deleted":true},…}` (verified).

### Session media — `GET /api/v1/sessions/{id}/media/{file_id}`

**New in 0.37.2** (commit `d833a1a8`; verified live 2026-08-18). Serves the
Session-owned canonical copy of prompt media (images/videos) referenced by a
`session_media` content source. Same binary semantics as the file download:
`Range` supported (`206`, `accept-ranges: bytes`), `content-disposition` inline
for image/video/audio. Errors return **HTTP 404** (not the usual HTTP-200
envelope): unknown session → `40401` "session not found", unknown file →
**`40407`** "file not found" (**0.40.1 drift** — `40406` up to 0.39.1;
verified live 2026-09-02. The legacy `GET /api/v1/files/{id}` route moved
the same way — **correction, verified live 2026-09-17 on 2.0.0:** a deleted
file answers **HTTP 404** with body `{"code":40407,"msg":"file not found"}`
on BOTH routes (the legacy files route and the media route alike); the route
source is identical at tags 0.43.1 and 2.0.0, so this was already true on
0.43.1 — this doc's earlier "HTTP-200 envelope" claim for the legacy route
was wrong. `40406` survives for unknown **tasks** only). For media uploaded through `POST /api/v1/files` the
`file_id` is the same `f_…` id, so the legacy `GET /api/v1/files/{id}` still
serves it (verified live: both returned the same 70 bytes for a 1×1 png); the
session-media route is the canonical path and the only one that works for media
that never passed through the daemon upload store.

### Turn file history (new in 0.40.1; live since 0.41.0)

Two routes that serve per-turn file-change snapshots. Introduced in 0.40.1
behind the `file_history` experimental flag (commit `650d12f6` — source-derived
only then, flag off); **0.41.0 dropped the gate** (commit `eba23edb`, "drop the
experimental gate from turn-level file history" — the flag is gone from
`/meta`), so they answer on this server now (verified live 2026-09-04):

- `GET /api/v1/sessions/{id}/file-history/changes?turn_id=N` →
  `{"changes":[{"path","status":"added"|"modified"|"deleted","additions",
  "deletions","binary"?,"oversize"?}],"recorded":<bool>}` — a turn with
  nothing recorded answers `{"changes":[],"recorded":false}` (verified).
  **The 0.40.1 source-derived shape also carried `enabled` — the shipped
  schema (`packages/kap-server/src/protocol/rest-file-history.ts` at
  0.41.0) has only `changes` and `recorded`.**
- `GET /api/v1/sessions/{id}/file-history/content?turn_id=N&path=<p>[&phase=
  "start"|"end"]` → `{"content":{"version":<n>,"content"?,"binary"?} | null}`
  (schema-verified at 0.41.0; not exercised live).

The only declared error is `SESSION_NOT_FOUND` (`40401`). The Android app
never calls these.

## Prompts

### Queue snapshot — `GET /api/v1/sessions/{id}/prompts`

**New in 2.5.0** (verified live 2026-11-04). Returns the main agent's prompt
queue: `{"active": <prompt>|null, "queued": [<prompt>…]}`. A prompt is
`{prompt_id, user_message_id, status, content, created_at}` with `status`
`running`/`queued`/`blocked` (live also shows `completed`/`aborted` on the
transcript entities but not in this queue view). Verbatim mid-turn:

```json
{"code":0,"msg":"success","data":{
  "active":{"prompt_id":"msg_…","user_message_id":"msg_…","status":"running",
            "content":[{"type":"text","text":"Run this bash command: sleep 25."}],
            "created_at":"…"},
  "queued":[{"prompt_id":"msg_…","user_message_id":"msg_…","status":"queued",
             "content":[{"type":"text","text":"Reply with exactly: steered-ok"}],
             "created_at":"…"}]},"request_id":"…"}
```

### Send — `POST /api/v1/sessions/{id}/prompts`

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_b6be6715.../prompts \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"content":[{"type":"text","text":"Run exactly this one command, then reply done: echo doc-verify-0290"}],
       "model":"kimi-code/k3","permission_mode":"manual"}'
```

```json
{
  "code": 0, "msg": "success",
  "data": {
    "prompt_id": "msg_01KY6G6B4A1EM8X8XRA02KJ3ZR",
    "user_message_id": "msg_01KY6G6B4A1EM8X8XRA02KJ3ZR",
    "status": "running",
    "content": [{"type": "text", "text": "Run exactly this one command, then reply done: echo doc-verify-0290"}],
    "created_at": "2026-07-23T03:27:03.306Z"
  },
  "request_id": "01KY6G6B2FE6494T12W5FR7Q2E"
}
```

- `prompt_id` equals `user_message_id` (the ULID `msg_...` id of the user
  message).
- `content` is a typed union, not just text. For user prompts the client may
  send (text/image/file items verified live 2026-07-24; the schema lived in
  `packages/protocol/src/message.ts` up to 0.41.0 — **0.42.0 deleted the
  `packages/protocol` package** (commit `9188d03c) and relocated its wire
  types into `packages/agent-core-v2`, which is the ground-truth reference
  from 0.42.0 onward):
  - `{"type":"text","text":"…"}`
  - `{"type":"image","source":{"kind":"file","file_id":"f_…"}}` — for uploaded
    `image/*` files. (`url` and `base64` source kinds also exist in the
    schema.)
  - `{"type":"video","source":{"kind":"file","file_id":"f_…"}}` — uploaded
    `video/*`.
  - `{"type":"file","file_id":"f_…","name":"…","media_type":"…","size":123}` —
    any other uploaded file. The web client picks the kind purely by MIME
    prefix (`image/*` → image, `video/*` → video, else file).
  - **0.39.1 — server-local path attachments** (commit `4e7738b7`; verified
    live 2026-08-28): image/video sources accept a 5th kind
    `{"kind":"path","path":"<absolute server-local path>"}`, and a `file`
    part may replace `file_id`+metadata with just `"path"` (`name`/
    `media_type`/`size` then optional — basename, `application/octet-stream`
    and stat fill the gaps). Exactly one of `file_id`/`path` must be present
    (`40001` otherwise). Paths must be absolute (`40001` "attachment path
    must be absolute: …", an Error2 envelope **with `stack`**), must exist
    (**`40407`** "file not found: …" — a new code on this route, distinct
    from the file store's `40406`), and are realpath-resolved; a
    sensitive-file filter exists but did NOT reject
    `/home/ubuntu/.kimi-code/server.token` live — do not rely on it. Local
    runtime only (the daemon rejects path refs on non-local runtimes).
  - The server **validates the referenced file's sniffed `media_type`** against
    the part type (verified live): a `video` part whose file was uploaded as
    `application/octet-stream` is rejected up front with envelope code `40001`
    (`file f_… is application/octet-stream, not a video`) and the prompt does
    not run. Since the upload sniffer never returns `video/*` on 0.29.0 (see
    Files above), the video path is effectively unreachable in this version.
- The server **rewrites attachment parts** before enqueueing, so history
  (`GET .../messages`) does not contain the original items (verified live
  2026-07-24 except where noted):
  - `file` parts become a **text part**, exact wording verified:
    `Attached file "<name>" (<mime>, <size> bytes): <abs path> — open it with
    the Read tool`. The bytes are materialized under
    `<sessionDir>/attachments/<fileId>-<sanitized name>` (sessionDir is
    `~/.kimi-code/sessions/<workspace_id>/<session_id>`) — the agent reads them
    from disk; the web client parses this notice back into a chip with a regex,
    so its wording is a client contract.
  - **0.37.2 change (verified live):** uploaded images now project to
    `{"type":"image","source":{"kind":"session_media","file_id":"f_…"}}` in
    BOTH the prompt ACK echo and `GET .../messages` — the transient `file`
    upload is promoted to a Session-owned canonical copy at intake, and the
    ACK/history no longer carry base64 or `url:data:…`. Fetch the bytes via
    `GET /api/v1/sessions/{id}/media/{file_id}` (see Files). The pre-0.37.2
    shapes — ACK `{"kind":"base64","media_type","data"}`, history
    `{"kind":"url","url":"data:<mime>;base64,…"}` — no longer occur.
    **0.42.0 (additive, commit `baf17a8f`; verified live 2026-09-09):** the
    projected part carries the original file's **`name`** —
    `{"type":"image","source":{"kind":"session_media","file_id":"f_…"},"name":"one.png"}`
    verbatim on BOTH the ACK echo and history (`video` parts alike).
  - Large images are compressed before enqueueing (verified: a 14.1 MB
    2600×1900 png was downscaled to 1000×731; a 3.2 MB one passed through
    untouched). A compression then prepends a separate **text part** wrapped in
    `<system>…</system>`: `Image compressed to fit model limits: original
    <W>x<H> <mime> (<orig>) -> sent <W>x<H> <mime> (<new>). Fine detail may be
    lost. The uncompressed original is saved at
    "<sessionDir>/media-originals/<hash>.<ext>"; if you need fine detail (e.g.
    small text), call ReadMediaFile on that path …`. Formats the model rejects
    degrade to the "Attached file" notice above (source-derived, not verified).
  - Videos project the same `session_media` source kind as images since 0.37.2
    (`{type:"video", source:{kind:"session_media", file_id}}`); the earlier
    `file`-kind video shape was source-derived and unreachable live (the 0.29.0
    upload sniffer never returned `video/*`).
  - **0.39.1 — attachments recorded on the prompt origin** (verified live):
    every file attachment (uploaded `file_id` or server-local `path`) adds an
    `attachments` array to the user-message origin — history carries it at
    `metadata.origin.attachments`, the `context.spliced` echo at the
    message's `origin.attachments`, and `turn.started` at
    `origin.attachments` — `[{"name","mediaType","size","path"}]`,
    camelCase; `path` is the original path for `path` parts and the
    materialized `<sessionDir>/attachments/…` copy for uploads. A `path`
    image projects as `session_media` (the file is saved into the
    session-media store at intake), a `path` file as the usual
    `Attached file "…"` notice naming the original path in place.
    `turn.started.promptAttachments` is still never emitted live (the schema
    gained a `kind:"file"` variant in 0.39.1; unobserved). The app decodes
    only `origin.kind` — additive, ignored.
- **`swarm_mode` in the prompt body is LIVE since 2.4.0** (commit `78b72fe4`,
  "apply swarm mode from prompt submissions"; verified live 2026-10-28):
  a prompt posted with `"swarm_mode": true` enters swarm mode before the
  prompt is enqueued and `/status` reads `swarm_mode:true`; posting `false`
  exits it (`true`→`false` across two probes on the same session). Up to
  2.3.3 the field parsed but was never consumed, while
  `POST /api/v1/sessions/{id}/profile {"agent_config":{"swarm_mode":true}}`
  already worked through `applySessionAgentConfig` → `swarm.enter('manual')`
  (both routes verified again on 2.4.0). `plan_mode`, `goal_objective` and
  `goal_control` on the prompt body remain parsed-but-unapplied
  (source-derived; `plan_mode` through the profile route is live, see
  Interactions).
- Optional body fields (per-prompt overrides): `permission_mode`
  (`manual`/`auto`/`yolo` — `manual` verified live), `model` (verified: a
  session with empty `agent_config.model` runs when the prompt carries one),
  `thinking`, `plan_mode`, `swarm_mode` (**applied since 2.4.0** — see
  above), `agent_id`, `metadata`,
  `goal_objective`, `goal_control`, `disabled_tools`, `prompt_id` (**0.37.2**:
  a client-chosen prompt record id echoed on the ACK `prompt_id`/
  `user_message_id`, the history message id, and `turn.started.promptId`;
  reusing one already in the agent's history answers `40927`
  `prompt.id_conflict`), and `skills` (**0.37.2**:
  `[{"name":"…","args":"…"}]` bundled skill activations — the app sends none;
  skills stay out of scope).
- **`metadata` — live since 2.0.0** (commit `41eac5d2`; verified live
  2026-09-17): an arbitrary client JSON object in the prompt POST body. It is
  echoed on the HTTP ACK as `data.metadata`, and rides the wire as
  `clientMetadata:[<metadata>]` (array-wrapped) on `prompt.submitted` /
  `prompt.queued`, on the `context.spliced` user message's
  `origin.clientMetadata`, on `turn.started`'s `origin.clientMetadata`, in
  history at `metadata.origin.clientMetadata`, and in the transcript
  prompts[] / user-turn origin payloads. Absent everywhere when not sent.
  Verbatim `prompt.submitted` for a prompt posted with
  `"metadata":{"client":"doc-verify-200","index":1}`:

  ```json
  {"type":"prompt.submitted","seq":22,
   "payload":{"type":"prompt.submitted","time":1789673445307,"agentId":"main",
              "promptId":"msg_01M2RDK5XQN21NFQPNV3D3W14X",
              "userMessageId":"msg_01M2RDK5XQN21NFQPNV3D3W14X","status":"running",
              "content":[{"type":"text","text":"Reply with exactly: meta-ok"}],
              "clientMetadata":[{"client":"doc-verify-200","index":1}],
              "createdAt":"2026-09-17T19:30:45.307Z","sessionId":"session_..."}}
  ```

  The app sends no `metadata` and ignores the field (`ignoreUnknownKeys`).
- `thinking` (verified live 2026-07-24, ticket 032) is a **plain effort
  string**, not an object: `"thinking": "low"`. Values come from the model's
  `support_efforts` (`GET /api/v1/models`; K3 on 0.29.0: `low`/`high`/`max`,
  plus `off` per the error message below). The honored level reads back via
  `GET /api/v1/sessions/{id}/status` (`data.thinking_level`, e.g. `"low"` after
  a `"low"` prompt) and live via the `agent.status.updated` model/usage
  variant's `thinkingEffort`. Error behavior:
  - An object shape (`{"enabled":true,"effort":"low"}` or `{"effort":"low"}`)
    is rejected up front: envelope code `40001`
    (`thinking: Invalid input: expected string, received object`); the prompt
    does not run.
  - An unknown effort (`"banana"`) is rejected up front: envelope code `50001`
    (`Thinking effort "banana" is not supported by model "kimi-code/k3".
    Supported efforts: off, low, high, max.`); the prompt does not run.
  - An effort sent with a model that has no `support_efforts` (e.g.
    `"thinking":"low"` with `kimi-code/kimi-for-coding`) is **accepted**: the
    turn runs and `thinking_level` reads back as `"on"` — the server degrades
    the effort to plain thinking-on instead of erroring. Clients should still
    only send efforts from the model's `support_efforts`.
- The server is asynchronous: the HTTP response only acknowledges the prompt;
  results arrive over the WebSocket.
- While the session is busy, a posted prompt is accepted but queued: the
  response comes back with `"status": "queued"` instead of `"running"`
  (re-verified 2026-07-23). A queued prompt can be steered into the running
  turn — see the next section.
- **2.5.0 — per-prompt actions** (upstream-documented, not probed live).
  `POST .../prompts/{prompt_id}:abort` aborts that running prompt
  (`{aborted:true}`; an already-completed prompt answers `40903` with
  `{aborted:false}`) and `POST .../prompts/{prompt_id}:steer` steers one
  queued prompt (the single-id form of `:steer`). Both dispatch through the
  same `{prompt_id}:{action}` tail route; an unknown action or prompt
  answers `40001`/`40402`.
- **2.5.0 — prompt entities.** The transcript (`prompt.upsert` ops,
  `transcript.reset.snapshot.prompts`, and `GET .../transcript.prompts`)
  carry `{promptId, status, userMessageId, content, createdAt, finishedAt?,
  steeredAt?}` with `status` `running`/`queued`/`completed`/`aborted`/
  `blocked`/`failed`. A steered prompt flips to `completed` with `steeredAt`; its text
  also lands as a user-role transcript frame with `promptIds`. These entities
  replace the old `prompt.submitted`/`queued`/`started`/`completed`/`aborted`
  events.

## Steer

Steering injects a message into the currently running turn. Two shapes are
live on 2.5.3 (verified 2026-11-10):

- **Direct — `POST /api/v1/sessions/{id}/steer`** (new in 2.5.3): one
  request carries the same body as a prompt submission. On a busy session
  it queues and steers the message; on an idle one it behaves exactly like
  the prompt POST (fresh turn).
- **Queue-then-steer — `POST .../prompts` + `POST .../prompts:steer`**: post
  the prompt as usual (`"status":"queued"` while busy), then steer the
  returned `prompt_id` into the active turn.

**2.5.3 execution model (verified live):** a steer interrupts the active
LLM request at the next drain boundary instead of waiting for the step to
finish. The current step is closed as `state:"interrupted"` with the new
`endReason:"steered"`, and the steered text continues in a new step of the
**same turn**. A steer that arrives while a tool call is streaming waits
for that tool to finish first. Steered history is append-only: the message
is its own `role:"user"` entry in `GET .../messages`.

**2.5.0:** the old `prompt.steered`/`turn.steer`/`context.spliced` frames
are gone. The steer is visible as transcript state: the steered prompt's
`prompt.upsert` goes `queued` → `completed` with `steeredAt`, the active
prompt's entity gains `steeredAt`, the steered text appears as a user-role
text frame carrying `promptIds` in the running turn's next step
(`{"kind":"text","frameId":"t2.3.f1","role":"user","text":"…",
"promptIds":["msg_…"],"origin":{"kind":"user"}}`), and an `undo` marker
carries the spliced message with `origin:{"kind":"user","inTurn":true}`.
The turn does not restart; `prompt.completed` no longer exists — the prompt
entity's `status:"completed"` is the completion signal.

### Steer message content — `POST /api/v1/sessions/{id}/steer`

**New in 2.5.3** (commit `e1912c80`; verified live 2026-11-10). Body: the
prompt-submission schema — `content` (the same typed part union as the
prompt POST, attachments included), `agent_id`, `prompt_id`, `skills`,
`model`, `thinking`, `permission_mode`, `swarm_mode`, `metadata`,
`disabled_tools`, `profile`. Success data is the prompt ACK shape.

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_6ea83210.../steer \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"content":[{"type":"text","text":"Stop and reply with exactly: direct-steer-mid-253"}],
       "model":"deepseek/deepseek-v4-flash"}'
```

```json
{"code":0,"msg":"success",
 "data":{"prompt_id":"msg_01M73ETJGQ6E4M9QSRQ8BYMYRK",
         "user_message_id":"msg_01M73ETJGQ6E4M9QSRQ8BYMYRK","status":"running",
         "content":[{"type":"text","text":"Stop and reply with exactly: direct-steer-mid-253"}],
         "created_at":"2026-11-10T19:26:42.205Z"},
 "request_id":"01M73ETJGNJPEJB098A0JW2EDK"}
```

- On an **idle** session the call starts a normal turn (ACK
  `status:"running"`, `prompt.upsert` running → completed), exactly like
  `POST .../prompts`.
- On a **busy** session the ACK also says `status:"running"`, but the
  transcript logs the prompt as `queued` first, then `completed` with
  `steeredAt` once the steer is applied. Trust the transcript entity, not
  the ACK status.
- Errors: empty or missing `content` → `40001` `content: Invalid input`;
  unknown session → `40401` with `stack`; the rest match the prompt POST
  (`40927` prompt-id conflict, `40407` file not found, `40931`/`40932`).
  A steer against an active turn whose prompt is untracked is accepted
  (source-derived; the engine now keys on `activeTurnId`, not a tracked
  prompt).

Mid-stream capture (prompt A streaming its essay, direct steer B posted):
the current step `t2.2` is interrupted mid-thinking, B's user frame opens
`t2.3` in the same turn, and the turn completes normally:

```json
{"op":"prompt.upsert","prompt":{"promptId":"msg_…A","status":"running",
  "steeredAt":"2026-11-10T19:26:42.213Z",…}}
{"op":"prompt.upsert","prompt":{"promptId":"msg_…B","status":"completed",
  "finishedAt":"…","steeredAt":"…","content":[{"type":"text","text":"Stop and reply…"}]}}
{"op":"step.upsert","turnId":"t2","step":{"stepId":"t2.2","state":"interrupted",
  "endedAt":"…","endReason":"steered"}}
{"op":"marker.upsert","item":{"marker":"undo","payload":{"start":14,"deleteCount":0,
  "messages":[{"role":"user","content":[{"type":"text","text":"Stop and reply…"}],
               "origin":{"kind":"user","inTurn":true},"id":"msg_…B"}]}}}
{"op":"step.upsert","turnId":"t2","step":{"stepId":"t2.3","state":"running"}}
{"op":"frame.upsert","turnId":"t2","stepId":"t2.3",
 "frame":{"kind":"text","frameId":"t2.3.f1","role":"user","text":"Stop and reply…",
          "promptIds":["msg_…B"],"origin":{"kind":"user"}}}
```

### Steer queued prompts — `POST /api/v1/sessions/{id}/prompts:steer`

Body: `{"prompt_ids": ["<prompt id>", ...]}` — at least one id.

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_b6be6715.../prompts:steer \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"prompt_ids":["msg_01KY6GCAQF1655KB27NSFBGWZ2"]}'
```

```json
{"code":0,"msg":"success",
 "data":{"steered":true,"prompt_ids":["msg_01KY6GCAQF1655KB27NSFBGWZ2"]},
 "request_id":"01KY6GCAT25KYEJKXA3XMM50H6"}
```

- **2.5.3:** a routed steer shares the direct route's execution model — it
  interrupts the current LLM step (`endReason:"steered"`) and continues in a
  new step of the same turn. Live capture: a queued prompt steered while a
  Bash tool call was running waited for that tool to finish, then opened the
  next step with the user frame; a steer against a streaming text/thinking
  step cuts it at the drain boundary. Idle `40402` is unchanged.
- **Idle session** (nothing to steer into): HTTP 200 with
  `{"code":40402,"msg":"no active prompt to steer into","data":null,...}`
  (re-verified 2026-11-10).
- Empty `prompt_ids`:
  `{"code":40001,"msg":"prompt_ids: Too small: expected array to have >=1 items",...}`
  (re-verified 2026-07-23).
- WS sequence when the steer lands mid-turn (verbatim capture in the WS
  section): `prompt.steered` → **`turn.steer`** (engine-side steer
  application, carrying `{input: [<the steered prompt's content parts>],
  origin: {"kind":"user"}}` — first captured live 2026-09-14 on 0.43.0)
  → the current step runs to `turn.step.completed` → the steered user
  message arrives via `context.spliced` → a new
  `turn.step.started` **in the same turn** (no new `turn.started`) →
  `turn.ended` `"completed"` → a single `prompt.completed` carrying the
  **original** active prompt id. The agent then follows the steered
  instruction. Re-verified 2026-07-27 on 0.29.2. **Event order flipped in
  2.0.0** (commit `2bb6e12c`; verified live 2026-09-17): `prompt.steered`
  now fires **before** `turn.steer` (observed seq 42 then 43) — from 0.43.0
  through 0.43.1 `turn.steer` came first. **2.0.1, additive (commits
  `53e5e3fc`/`60f2a632`/`73ebe9ab`; verified live 2026-09-18):** the steer
  frames gained a message-identity set — `prompt.steered` carries
  **`messageId`**, `turn.steer` carries **`messageId`**, **`promptIds`** and
  **`turnId`** with origin `{"kind":"user","inTurn":true}`, and the spliced
  steered message (plus its history entry) carries that same `messageId` as
  its `id` with the `inTurn:true` origin. **A single steer reuses the queued
  prompt's own id** as `messageId`; **a multi-prompt steer mints a fresh
  ULID** for the merged message (verbatim frames in WebSocket → Core event
  payloads). **2.4.1 re-verification (2026-10-30):** the engine-side
  `turn.steer` was observed *after* the steered-message `context.spliced`
  (spliced seq 18, `turn.steer` seq 19) and immediately before the new
  `turn.step.started`; the payload set is unchanged, so clients must not
  require `turn.steer` ahead of the splice.

## Abort

### Abort current prompt — `POST /api/v1/sessions/{id}:abort`

Body: `{}`. Aborts the currently running prompt/turn of the session.

```bash
curl -s -X POST 'http://127.0.0.1:58627/api/v1/sessions/session_b6be6715...:abort' \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' -d '{}'
# {"code":0,"msg":"success","data":{"aborted":true},"request_id":"01KY6GFG4S5FPPXCFMP60X0FVH"}
```

**2.5.0:** the REST behaviour is unchanged (`{"aborted":true}` mid-turn or
idle), but the old `turn.step.interrupted` → `turn.ended` → `prompt.aborted`
frames are gone. Abort is visible in the transcript stream as: the current
step's `step.upsert` `state:"interrupted"` with `endReason:"aborted"`, the
turn's `turn.upsert` `state:"cancelled"`, a standalone
`marker:"interruption"` item
(`{"turnId":<n>,"reason":"user_cancelled"}`), a `prompt.upsert`
`status:"aborted"`, an `undo` marker carrying the interruption reminder
(`origin:{"kind":"injection","variant":"interruption"}`), and
`event.session.work_changed` with `busy:false`, `last_turn_reason:"cancelled"`.
A killed tool call's `task` frame reports `outcome:"killed"`. (The
`interruption` marker was present before 2.5.3 too; it was missing from this
section's earlier itemization, re-verified live 2026-11-10.)

The historical sequence (0.29.0–2.4.1) was verified mid-run on 0.29.0
(2026-07-23), again on 0.29.2 (2026-07-27), again on 0.31.0 (2026-07-30), and
again on 0.31.1 (2026-07-31): the WS then emitted `turn.step.interrupted`
(`reason: "aborted"`) → `turn.ended` (`reason: "cancelled"`) →
`event.session.work_changed` (idle, `last_turn_reason: "cancelled"`) →
`prompt.aborted`. It is kept below for older journals. **0.31.1 additions to that sequence (verified live):**
`turn.ended` carries `interruptReason: "user_cancelled"`, and a
`context.spliced` echo of the interruption-reminder `<system-reminder>`
injection (origin `{"kind":"injection","variant":"interruption"}` — see
Messages) lands between `turn.ended` and the idle `work_changed`. Verbatim
0.31.1 `turn.ended` payload:

```json
{"type":"turn.ended","turnId":1,"reason":"cancelled","durationMs":5030,
 "interruptReason":"user_cancelled","agentId":"main","sessionId":"session_..."}
```

**0.43.0 (verified live 2026-09-14):** an idle `:abort` (nothing running,
or the prompt already completed) no longer errors — it answers
`{"code":0,"data":{"aborted":true}}`. The `40903`
`prompt.already_completed` rejection was removed (commit `46233953`); the
abort sequence above is unchanged when a turn IS running.

## Compact

Compacting summarizes a session's history to free up context window (the wire
equivalent of the TUI's `/compact`). Documented and verified live on server
`0.30.0` (2026-07-30, ticket 054); **re-verified on 2.5.0 (2026-11-04), where
the result moved from the `compaction.*` event family to transcript markers**
— see WebSocket sequence (2.5.0) below. The web bundle's
`event.session.history_compacted` event (`before_seq` / `reason` /
`summary_message_id`) is still never emitted by this server.

### Compact a session — `POST /api/v1/sessions/{id}:compact`

Body: `{}` or `{"instruction": "..."}` (the body may also be omitted
entirely — same as `{}`, but only when no `Content-Type` header is set;
posting a truly empty body **with** `Content-Type: application/json` fails
with `50001 "Body cannot be empty when content-type is set to
'application/json'"`, a Fastify parser error — verified 2026-08-05 on
0.33.0). The endpoint is **fire-and-forget**: it ACKs in
milliseconds with an empty data object and the compaction runs in the
background (a summarization model call — observed ~8–46 s depending on
history size).

```bash
curl -s -X POST 'http://127.0.0.1:58627/api/v1/sessions/session_647d8a6d-...:compact' \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' -d '{}'
# {"code":0,"msg":"success","data":{},"request_id":"01KYRRVG0A03KKCRD4K27R2E73"}

curl -s -X POST 'http://127.0.0.1:58627/api/v1/sessions/session_abf63a9c-...:compact' \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"instruction":"Keep the echoed marker string verbatim in the summary."}'
# {"code":0,"msg":"success","data":{},"request_id":"01KYRS33HGG01FKRGKM3GXNXG8"}
```

- `instruction` is a free-text hint for the summarizer; it is echoed back in
  the `compaction.started` WS event.
- Posting a second compact while a compaction is already running is a
  **silent no-op** (verified live): same instant `{"code":0,...,"data":{}}`
  ACK, but no second `compaction.started` fires — one compaction per
  session at a time.

### WebSocket sequence (2.5.0)

Compaction no longer has an event family. It is a pair of
`marker:"compaction"` items in the subscribed agent's transcript stream, with
the whole-context rewrite carried by one `undo` marker. Captured live on
2.5.0 (2026-11-04, `{}` body, a session with two completed turns; payloads
trimmed):

```json
{"type":"transcript.ops","session_id":"session_…","timestamp":"…",
 "payload":{"type":"transcript.ops","agent_id":"main","ops":[
   {"op":"marker.upsert","item":{"kind":"marker","markerId":"live-m5",
    "marker":"compaction","payload":{"phase":"started","agentId":"main",
    "trigger":"manual"},"at":"…"}}],"seq":32}}
{"type":"event.session.work_changed","session_id":"session_…","timestamp":"…",
 "payload":{"type":"event.session.work_changed","busy":true,
  "main_turn_active":false,"pending_interaction":"none",
  "last_turn_reason":"completed","agentId":"main","sessionId":"session_…"}}
{"type":"transcript.ops","session_id":"session_…","timestamp":"…",
 "payload":{"type":"transcript.ops","agent_id":"main","ops":[
   {"op":"marker.upsert","item":{"kind":"marker","markerId":"live-m6",
    "marker":"undo","payload":{"agentId":"main","start":0,"deleteCount":6,
     "messages":[<kept user messages>,
                 <summary message, origin.kind:"compaction_summary">,
                 <continuation injection, origin.kind:"injection",
                  variant:"compaction_continuation">],
     "tokens":23493},"at":"…"}}],"seq":33}}
{"type":"transcript.ops","session_id":"session_…","timestamp":"…",
 "payload":{"type":"transcript.ops","agent_id":"main","ops":[
   {"op":"marker.upsert","item":{"kind":"marker","markerId":"live-m7",
    "marker":"compaction","payload":{"phase":"completed","agentId":"main",
     "result":{"summary":"…","compactedCount":6,"tokensBefore":22745,
               "tokensAfter":23493,"keptUserMessageCount":2}},"at":"…"}}],
  "seq":34}}
{"type":"event.session.work_changed", … busy:false, last_turn_reason:"completed" …}
{"type":"usage", … "context_tokens":23493,"max_context_tokens":1000000 …}
```

- `marker:"compaction"` phases: `started` carries `trigger` (`manual`) and
  the request's **`instruction`** when one was sent (verified live
  2026-11-10: `{"phase":"started","agentId":"main","trigger":"manual",
  "instruction":"Keep the marker string verbatim…"}`; absent for `{}`);
  `completed` carries the full `result` block with the same five fields the
  old `compaction.completed` had (`summary`, `compactedCount`, `tokensBefore`,
  `tokensAfter`, `keptUserMessageCount`).
- The wholesale splice is an `undo` marker: `start:0`,
  `deleteCount:<previous context size>`, `messages` = kept user messages
  (verbatim, original ids) + the summary (`origin.kind:"compaction_summary"`)
  + the continuation injection
  (`{"kind":"injection","variant":"compaction_continuation"}`), and a
  `tokens` count.
- `compaction.started` / `compaction.completed` / `compaction.failed` no
  longer exist on the wire. A client that subscribed after the compaction
  sees its result only through the marker history and `GET .../transcript`
  (the marker is journaled); the `usage` frame carries the post-compaction
  fill.
- The REST behaviour is unchanged: instant `{}` ACK, one compaction at a
  time, both `40910`s below.

#### Historical sequence (0.30.0–2.4.1)

Captured 2026-07-30 on 0.30.0 (session with 8 messages / 3 completed turns,
compacted with an empty body); re-verified verbatim on 0.31.0 the same day:

```
seq 34  compaction.started         {"trigger":"manual"} — plus "instruction" when one was sent
seq 35  event.session.work_changed busy=true (main_turn_active stays false)
seq 35  agent.status.updated       usage variant; a second one drops contextTokens to the compacted size
seq 36  context.spliced            start=0, deleteCount=<whole context>, messages = kept user
                                   messages + the summary (see below)
seq 37  compaction.completed       {"result":{summary, compactedCount, tokensBefore, tokensAfter,
                                   keptUserMessageCount}}
seq 38  event.session.work_changed busy=false
```

`agent.status.updated` frames piggyback on the same seqs as usual. The
session reads `busy: true` for the whole compaction (`GET .../status`
verified mid-run), but **no turn events fire** (`turn.started`, `prompt.*`)
— compaction is not a turn.

Verbatim `compaction.started` (the instruction variant, session
`session_abf63a9c-...`):

```json
{"type":"compaction.started","seq":25,"session_id":"session_abf63a9c-...","timestamp":"2026-07-30T05:48:54.194Z",
 "payload":{"type":"compaction.started","trigger":"manual",
            "instruction":"Keep the echoed marker string verbatim in the summary.",
            "agentId":"main","sessionId":"session_abf63a9c-..."},
 "epoch":"ep_01KYRS1R9FV9PCACAS87ZMN54Q"}
```

Verbatim `compaction.completed` (summary trimmed):

```json
{"type":"compaction.completed","seq":37,"session_id":"session_647d8a6d-...","timestamp":"2026-07-30T05:44:56.676Z",
 "payload":{"type":"compaction.completed",
  "result":{"summary":"Nothing is actually pending — this conversation is a completed spike ...",
            "compactedCount":8,"tokensBefore":850,"tokensAfter":386,"keptUserMessageCount":3},
  "agentId":"main","sessionId":"session_647d8a6d-..."},
 "epoch":"ep_01KYRRQHYHCXDT5NY3Z9VERZR4"}
```

- `result.summary` — the generated summary text; also persisted in history
  (see below).
- `result.compactedCount` — how many history messages were folded into the
  summary (all 8 in this run).
- `result.tokensBefore` / `result.tokensAfter` — token estimates of the
  compacted span before/after. `tokensBefore` covers the history span only
  (850 here), not the full context — `GET .../status` `context_tokens` was
  26518 before the same run. `tokensAfter` equalled the post-compaction
  `context_tokens` in both observed runs (386 and 275).
- `result.keptUserMessageCount` — how many real user messages survive
  verbatim in the compacted context.

The `context.spliced` frame rewrites the model context wholesale:
`start: 0`, `deleteCount: <previous context size>` (8 in the capture), and
`messages` is the new context: the kept user messages (verbatim, original
`msg_...` ids, `origin.kind: "user"`) followed by the summary as a user
message with `origin.kind: "compaction_summary"`. **0.31.0 drift:** the
summary message no longer carries `id: null` — the **`id` key is absent
entirely**, while kept user messages still carry their `msg_…` ids (key lists
from the 0.31.0 capture):

```
['content','id','origin','role','toolCalls']        origin user
['content','origin','role','toolCalls']             origin compaction_summary
```

**Observed 2026-09-15 on 0.43.1 (first live itemization; the emitting code
has existed since the agent-core-v2 landing):** the splice may carry a third
message kind after the summary — a whole-message `<system-reminder>`
injection with `origin: {"kind":"injection","variant":"compaction_continuation"}`
and no `id`, reading "Context compaction is complete — continue the work
that was in progress when it began." It is context-only (not persisted in
`GET .../messages`); clients that strip reminder envelopes (the app does)
need no change.

The spliced summary text is prefixed with model-facing instructions that are
**not** persisted — history stores the bare summary (verified, see below).
The prefix (refreshed 2026-10-30 on 2.4.1; the text is identical on 2.4.0,
so it replaces the earlier 0.42.0-era quote):

> The conversation so far has been compacted to free up context. What
> follows is your own working summary of this task — use it to continue
> your train of thought rather than starting over. Do not redo work it
> reports as done or re-run checks it reports as passed; check anything it
> marks as unverified before relying on it. Any user messages earlier in
> this context are preserved verbatim from the compacted conversation;
> where a system-reminder note among them marks an omitted middle section,
> the user messages it replaced are covered by this summary. The summary
> records which earlier requests were already addressed.

The same 0.42.0 commit reworded the summarizer instruction file (handoff
"note" → "summary", the first-person framing dropped) and anchored
compaction resumption on the latest user message (behavioral) — the
`result` block, the WS sequence, and the persisted `compaction_summary`
shape are all unchanged (verified live 2026-09-09, both `{}` and
`{"instruction":…}` bodies).

**0.41.0 content drift (not shape):** the summarizer now works from a new
instruction file (commit `b199e332`), so the generated summary typically
opens with "Handoff note to myself:" — the `result.summary` and the persisted
`compaction_summary` message both carry the new style (verified live
2026-09-04). All shapes above are unchanged.

### Effect on history and status

- `GET .../messages` still returns the **full pre-compaction transcript** —
  nothing is deleted. Compaction **appends one message** at the end:
  `role: "user"`, a single text part with the bare summary (no prefix),
  `metadata.origin.kind: "compaction_summary"`, under a normal sequential
  id (verified):

  ```json
  {"id":"msg_session_647d8a6d-..._000008","session_id":"session_647d8a6d-...","role":"user",
   "content":[{"type":"text","text":"Nothing is actually pending — this conversation is a completed spike ..."}],
   "created_at":"2026-07-30T05:44:56.660Z","metadata":{"origin":{"kind":"compaction_summary"}}}
  ```

  So after compaction, history ≠ model context: history keeps every message
  plus the summary; the context is only the kept user messages + summary
  (per the splice). Pagination and the `role` filter behave as before —
  summaries match `role=user` (a session compacted twice shows two
  `compaction_summary` user messages in the normal order; `before_id`
  paging walks past them unchanged — both verified).
- **2.5.0:** `GET .../status` is gone. `event.session.work_changed` reports
  `busy:true` / `main_turn_active:false` for the whole compaction, and the
  `usage` frame's `context_tokens` / `max_context_tokens` drop to the
  compacted size when it completes (observed 22745 → 23493 in the 2.5.0
  capture — the summary prefix can make `tokensAfter` slightly larger than
  `tokensBefore`). The historical `/status` reads were 26518 → 386 and
  25851 → 275.

### Errors

All verified live 2026-07-30 on 0.30.0, re-verified verbatim on 0.31.0 the
same day and again on 0.32.0 (2026-08-05; HTTP 200 envelopes — since 0.32.0
the Error2 ones carry a server-side `stack` string, shown omitted here):

- Empty/fresh session (also when the body is omitted entirely):
  `{"code":40910,"msg":"No messages to compact in current history.","data":null,...}`
- Turn running (`busy: true`):
  `{"code":40910,"msg":"Cannot compact while a turn is active. Wait for it to finish, then retry.","data":null,...}`
- Approval pending (`permission_mode: "manual"`, tool call awaiting a
  decision): the same `40910` — a pending interaction means the turn is
  still active.
- Unknown session:
  `{"code":40401,"msg":"session session_... does not exist","data":null,...}`.
- Wrong `instruction` type (`{"instruction":123}`):
  `{"code":40001,"msg":"instruction: Invalid input: expected string, received number","data":null,...}`
  with a `details` array.

## Session rating

### Rate a session — `POST /api/v1/sessions/{id}/rating`

**New in 2.3.0** (commit `ce0834a8` — the CLI/web feedback-survey rules
shared with the server; verified live 2026-10-22). Pure REST, no WS events.
The body is a discriminated union on `op`:

- `{"op":"evaluate","turn_id":"<turn>"}` — asks whether a survey should
  appear; answers `{"appearance":{"id","survey":"session"|"long_context"}|null}`.
  Only the latest completed (non-running) main-agent turn can be rated.
- `{"op":"shown","appearance_id":"…"}` — mark the survey shown.
- `{"op":"respond","appearance_id":"…","response":"bad"|"fine"|"good"|"dismissed"}`
- `{"op":"abandon","appearance_id":"…"}`

The three non-evaluate ops answer `data: null` on success; a malformed body
fails `40001`-family (`validation.failed`). Verified live: four `evaluate`
probes on one session (turn ids `t4`, `t5`, `4`, `5`) all answered
`{"code":0,"msg":"success","data":{"appearance":null}}` — no survey was
due, and an unknown or non-latest turn id is **not** an error. The related
config key `disable_feedback_survey` is accepted by the config GET/PATCH
schemas and absent from the GET projection when unset (see Models). The
Android app has no rating surface and no ticket — the surveys are Kimi's
product feedback channel, surfaced by the TUI/web only.

## Interactions

Approvals and questions are the session's two pending-interaction kinds. Since
2.4.0 they share one live WebSocket frame and one REST response route; the
`event.approval.*` / `event.question.*` event families and the
`/approvals` + `/questions` routes are gone. **2.5.0 keeps the frame and the
route unchanged but delivers the frame only to `subscribe_v2` subscribers**
(the old raw `subscribe` no longer exists), adds an `interaction.upsert`
transcript op, and re-sends the full interaction entity list inside
`transcript.reset.snapshot.interactions` plus the pending ones as standalone
frames. Everything below was re-verified live on 2.5.0 (2026-11-04, approval
and question) and again on 2.5.1 (2026-11-05); the captures are the 2.4.0
shapes, which the 2.5.x frames match byte-for-byte except for the delivery
rule.

### The `interaction` frame

Approvals and questions from every agent in the session, sub-agents included,
arrive as the flat `interaction` frame. Unlike every other session event it has
**no `seq`, `timestamp`, or `payload` wrapper**, it is never journaled and never
replayed, and it is not tied to a cursor. Fields:

| Field | Type | Description |
| --- | --- | --- |
| `type` | string | `"interaction"` |
| `session_id` | string | Session id |
| `agent_id` | string | The asking agent: `"main"` or a sub-agent id |
| `event_created_at` | string | When this frame was produced, ISO 8601 |
| `interaction_id` | string | `approval_<uuid>` or `question_<uuid>`; the id the response route takes |
| `status` | string | `pending` / `ended` |
| `outcome` | string | Only on `ended`: `approved`/`rejected`/`cancelled` (approval) or `answered`/`dismissed`/`cancelled` (question) |
| `ended_at` | string | Only on `ended`, ISO 8601 |
| `tool_call_id` | string | Optional; the transcript row's tool-call id (the provider's id, e.g. `call_00_…` or `tool_…`) |
| `kind` | string | `approval` / `question` |
| `request` | object | Approval: `{tool_name, action, tool_input_display?, expires_at}`. Question: `{questions[]}` |
| `response` | object | Only on `ended`: approval `{decision, scope?, feedback?, selected_label?}`; question `{answers?}` (only when `answered`) |

Verbatim pending approval (manual Bash, 2026-10-28):

```json
{"type":"interaction","session_id":"session_0b0a0493-7384-4d51-ba07-5badf36e8c9e",
 "agent_id":"main","event_created_at":"2026-10-28T19:27:19.813Z",
 "interaction_id":"approval_3c06aff8-3e53-4ecf-8e78-a841b579f4ec","status":"pending",
 "tool_call_id":"call_00_KIfpS3Zd2Ch9FwjlUnG20665","kind":"approval",
 "request":{"tool_name":"Bash","action":"Running: rm -rf /home/ubuntu/spike/drift-24/approval-target",
            "tool_input_display":{"kind":"command","command":"rm -rf /home/ubuntu/spike/drift-24/approval-target",
                                  "cwd":"/home/ubuntu/spike","language":"bash"},
            "expires_at":"2026-10-29T19:27:19.811Z"}}
```

The same interaction after `{"kind":"approval","decision":"approved"}`:

```json
{"type":"interaction","session_id":"session_0b0a0493-7384-4d51-ba07-5badf36e8c9e",
 "agent_id":"main","event_created_at":"2026-10-28T19:27:20.145Z",
 "interaction_id":"approval_3c06aff8-3e53-4ecf-8e78-a841b579f4ec","status":"ended",
 "outcome":"approved","ended_at":"2026-10-28T19:27:20.145Z",
 "tool_call_id":"call_00_KIfpS3Zd2Ch9FwjlUnG20665","kind":"approval",
 "request":{"tool_name":"Bash","action":"Running: rm -rf /home/ubuntu/spike/drift-24/approval-target",
            "tool_input_display":{"kind":"command","command":"rm -rf /home/ubuntu/spike/drift-24/approval-target",
                                  "cwd":"/home/ubuntu/spike","language":"bash"},
            "expires_at":"2026-10-29T19:27:19.811Z"},
 "response":{"decision":"approved"}}
```

Verbatim pending question (manual `AskUserQuestion`, 2026-10-28):

```json
{"type":"interaction","session_id":"session_0b0a0493-7384-4d51-ba07-5badf36e8c9e",
 "agent_id":"main","event_created_at":"2026-10-28T19:28:18.527Z",
 "interaction_id":"question_10025aea-b245-4d2e-a290-11461cf10422","status":"pending",
 "tool_call_id":"call_00_IMrajNNAe1wdV1kRD3Zq6099","kind":"question",
 "request":{"questions":[{"id":"q_0","question":"Cake or pie?",
                         "options":[{"id":"opt_0_0","label":"Cake"},{"id":"opt_0_1","label":"Pie"}],
                         "header":"Dessert","allow_other":true}]}}
```

After `{"kind":"question","answers":{"q_0":{"kind":"single","option_id":"opt_0_0"}}}`:

```json
{"type":"interaction","session_id":"session_0b0a0493-7384-4d51-ba07-5badf36e8c9e",
 "agent_id":"main","event_created_at":"2026-10-28T19:28:18.858Z",
 "interaction_id":"question_10025aea-b245-4d2e-a290-11461cf10422","status":"ended",
 "outcome":"answered","ended_at":"2026-10-28T19:28:18.858Z",
 "tool_call_id":"call_00_IMrajNNAe1wdV1kRD3Zq6099","kind":"question",
 "request":{"questions":[{"id":"q_0","question":"Cake or pie?",
                         "options":[{"id":"opt_0_0","label":"Cake"},{"id":"opt_0_1","label":"Pie"}],
                         "header":"Dessert","allow_other":true}]},
 "response":{"answers":{"Cake or pie?":"Cake"}}}
```

Notes:

- **Pending re-send after subscribe (2.5.0; re-verified on 2.5.1).** After an
  accepted `subscribe_v2`, `transcript.reset.snapshot.interactions` carries
  the **full interaction entity list for the session — ended ones included,
  not only pending** — and the still-pending ones are additionally sent as
  standalone `interaction` frames after the ack (verified live 2026-11-05: a
  fresh subscribe while a question waited delivered a reset whose
  `interactions` held one answered and one pending entity, the ack, then one
  pending frame). Clients should rebuild the pending list from the reset by
  filtering `state:"pending"` (or from the frames that follow), not assume
  the snapshot array is pending-only. Dedupe by `interaction_id`. A resolved
  interaction is never re-sent as a standalone frame. `GET .../transcript`
  also returns the current `interactions` and `pending_interactions` for a
  cold read.
- **Ended frames reach every subscriber.** Close the card on the `ended`
  frame, not on the REST response, so every client watching the session
  converges — including the one that answered.
- **Abort semantics.** Aborting a turn with a pending approval ends it with
  `outcome:"cancelled"` and `response:{"decision":"cancelled"}`; aborting with
  a pending question was observed ending it with `outcome:"dismissed"` and no
  `response` (the REST transcript maps true engine cancellations to
  `cancelled`; the live frame's question path reports dismissal).
- An approval's `expires_at` is `event_created_at` + 24 h; `tool_input_display`
  is the same discriminated object tool calls carry — `kind:"command"`, or
  `kind:"plan_review"` with `{plan, path}` for `ExitPlanMode` (see below).
- `permission.approval.requested` / `.resolved` (camelCase, with `id`,
  `toolCallId`, `display`, `toolInput`) still ride the durable stream next to
  these frames, sharing their `seq` with the surrounding events. They carry
  the same `approval_<uuid>` id. Questions have no such duplicate.

### Respond — `POST /api/v1/sessions/{id}/interactions/{interaction_id}`

One route for both kinds; the body's `kind` must match the interaction's kind.
The decision is routed back to the asking agent, so no `agent_id` is needed.

With `kind:"approval"`:

| Parameter | Type | Description |
| --- | --- | --- |
| `decision` | string | **Required.** `approved` / `rejected` / `cancelled` |
| `scope` | string | With `approved`, `session` (the only value) remembers the rule for the session |
| `feedback` | string | Free-form feedback handed to the agent |
| `selected_label` | string | The chosen label when the request offered labeled choices (plan review) |

With `kind:"question"`, send exactly one of `answers` and `dismissed`:

| Parameter | Type | Description |
| --- | --- | --- |
| `answers` | object | Map of question item id (`q_0`, …) to an answer object |
| `dismissed` | `true` | Dismiss without answering |
| `method` | string | `enter` / `space` / `number_key` / `click` |
| `note` | string | Free-form note |

Each answer is discriminated by `kind`: `single` (`option_id`), `multi`
(`option_ids`, at least 1), `other` (`text`), `multi_with_other`
(`option_ids`, `other_text`), `skipped` (no fields; the item is omitted from
the flattened answers).

Success is the standard envelope with `data:{outcome, ended_at}` — for
example:

```json
{"code":0,"msg":"success","data":{"outcome":"approved","ended_at":"2026-10-28T19:27:20.145Z"},"request_id":"01M61ZPCJB0ET579WG3TPXC3DP"}
```

A dismissal answers `data:{"outcome":"dismissed","ended_at":…}` — there is no
special non-zero success code anymore (`40909` is gone).

Errors (all with the standard envelope, no `stack`):

```json
{"code":40902,"msg":"interaction question_0c93eb23-8bf4-4a83-9139-f7566628115b has ended","data":null,"request_id":"…"}
{"code":40404,"msg":"interaction question_00000000-0000-0000-0000-000000000000 not found","data":null,"request_id":"…"}
{"code":40001,"msg":"kind: Invalid input","data":null,"request_id":"…","details":[{"path":"kind","message":"Invalid input"}]}
```

- `40401`: unknown session.
- `40404`: no interaction with that id in the session (including an
  `approval_…` id posted under the wrong session).
- `40902`: the interaction already ended — answered elsewhere, or cancelled
  because its turn ended or its agent closed.
- `40001`: validation failure, including `kind` missing or mismatching the
  interaction (checked before the resolve attempt; a mismatched body against
  an already-ended interaction reports `40902` first).

### Approval flow, end to end

A prompt submitted with `permission_mode:"manual"` that needs a tool (2.5.0):

```
transcript.ops  frame.upsert   tool "Bash", state "running"
event.session.work_changed     pending_interaction "approval" (busy true)
interaction                    status "pending", kind "approval"
transcript.ops  interaction.upsert  state "pending"
-- POST interactions/{id} {"kind":"approval","decision":"approved"} --
interaction                    status "ended", outcome "approved"
transcript.ops  interaction.upsert  state "approved"
transcript.ops  frame.upsert   tool gains approvalId
task                           shell "running" → "ended" (completed)
transcript.ops  frame.upsert   tool state "done", output
event.session.work_changed     pending_interaction "none"
```

`tool_call_id` is the provider's tool-call id and is only the transcript row's
id; every REST action keys on `interaction_id`. Posting a previously used
`approval_<uuid>` answers `40902`; posting an unknown id `40404`. A rejected
decision ends the same way with `outcome:"rejected"`; the tool does not run.

### Question flow, end to end

```
transcript.ops  frame.upsert   tool "AskUserQuestion", state "running"
event.session.work_changed     pending_interaction "question"
interaction                    status "pending", kind "question", questions[]
transcript.ops  interaction.upsert  state "pending"
-- POST interactions/{id} {"kind":"question","answers":{…}} --
event.session.work_changed     pending_interaction "none"
interaction                    status "ended", outcome "answered"
transcript.ops  interaction.upsert  state "answered"
transcript.ops  frame.upsert   tool state "done",
                               output {"answers":{"<question text>":"<flattened>"}}
turn continues
```

The 2.5.0 question capture confirmed the exact `interaction` shapes unchanged
and `request.questions[].options[].description` present (empty string when
the model gave none).

Flattening: `single` → option label; `other` → raw text; `multi` → selected
labels joined with `", "`; `multi_with_other` → labels then free text, same
join; `skipped` → item omitted. The `ended` frame's `response.answers` uses
the **question text** as the key. Option ids are not validated against the
item's options (a bogus id flattens verbatim), so clients must validate their
own selections. A `multi` answer with an empty `option_ids` array fails
`40001` and leaves the interaction pending.

Dismissal posts `{"kind":"question","dismissed":true}` and produces
`outcome:"dismissed"`; the question's `response` is omitted. On the wire the
turn continues and the result carries
`{"answers":{},"note":"User dismissed the question without answering."}`.

**Multi-select rule:** each question item carries an optional
`multi_select` boolean; absence means `false`. The server echoes the model's
input verbatim when it passes the field explicitly, so both `true` and
`false` forms occur; decoders must accept both. The history
(`GET .../messages`) `tool_use` input for `AskUserQuestion` carries
`multi_select` per question but its options are label-only (no ids). The
`skipped` answer kind and the optional `method`/`note` body fields are
schema-verified but never observed live.

**Auto-mode caveat (verified live 2026-10-14 on 2.2.0, unchanged):** a
session running `auto` permission mode gets a model-facing reminder not to
call `AskUserQuestion` at all — under `auto` the tool call never happens and
no question surfaces. The flow above was therefore re-verified with the
session switched to `manual`.

### Pending state on REST (2.5.0)

The snapshot route is gone. Pending interactions now come from two places:

- `GET /api/v1/sessions/{id}/transcript?agent_id=main` returns
  `interactions` — full entities
  `{interactionId, interactionKind, toolCallId?, state, request?, response?}`
  — and `pending_interactions` — the ids of the still-pending ones. The
  `request` shape differs by kind (re-verified live on 2.5.1, 2026-11-05):
  an **approval** uses camelCase
  `{id, sessionId, agentId, turnId, toolCallId, toolName, action, display}`
  (no `expires_at`), while a **question** keeps
  `{question_id, questions[], created_at, turn_id, tool_call_id}`. The WS
  `interaction` frame's own `request` stays snake_case for approvals — only
  the transcript entity is camelCase. `state` observed: `pending`,
  `approved`, `answered`.
- After an accepted `subscribe_v2`,
  `transcript.reset.snapshot.interactions` carries the full interaction
  entity list (ended ones included); the pending ones are also sent as
  standalone `interaction` frames. The transcript `interaction.upsert` op
  carries the same entity on every state change.

There is still no per-session pending list route:
`GET .../approvals` and `GET .../questions` are plain HTTP 404s (a POST to
those words hits the session `:{action}` catch-all and answers `40001
"unsupported action: approvals"`). While an interaction waits, the session
object reads `pending_interaction:"approval"|"question"` and `busy:true` (a
background question can keep `busy:true` with `main_turn_active:false`).

### Plan review approvals

`ExitPlanMode` asks for approval with a plan body. Verbatim
`tool_input_display` (live 2026-10-28, after entering plan mode through
`POST .../profile {"agent_config":{"plan_mode":true}}`):

```json
{"kind":"plan_review",
 "plan":"# Add README.md with \"hello\"\n\n## Context\n\n…",
 "path":"/path/to/session/agents/main/plans/plan.md"}
```

`action` reads "Presenting plan and exiting plan mode". The approval can be
answered `approved` (with optional `selected_label`, e.g. "Approve plan") or
`rejected`. Since 2.4.0 every plan waits for this review: the agent can no
longer enter plan mode itself and `auto` no longer approves plans silently.
Style note: `tool_input_display` can also be absent; clients should fall back
to `action`.

### Background questions (`background: true`)

A background `AskUserQuestion` call does not die with its turn:

- The question surfaces as an `interaction` frame with
  `pending_interaction:"question"`, then the turn can end **without**
  resolving it. While it waits the session reads `busy:true` with
  `main_turn_active:false` and `pending_interaction:"question"`.
- **2.5.0:** the question is a task of the main agent — a `task` frame with
  `kind:"other"` (the old REST wire kind `tool` is gone), `detached:true`,
  and `description` = the question text. Answering ends it with the ordinary
  `task` ended frame; a later transcript turn is opened by the task
  notification (`origin.kind:"task"`), and the answer text is in the tool
  result. There is no `task.notified`/`background.task.*` frame.
- The background tool result text is `task_id:` / `status:` / `next_step:` —
  tool prose, not wire shape.




## Todos

**2.5.0: the todo list is a first-class WS frame** (commit `011b1d41`;
verified live 2026-11-04). Every connection subscribed to an agent receives
`{"type":"todo","session_id":"…","agent_id":"main","event_created_at":"…",
"items":[{"title":"…","status":"in_progress"}]}` when it starts receiving
that agent and on every change, **including undo and cold restore**; the
frame replaces the client's list wholesale. Captured live (a three-step
prompt drove the list):

```json
{"type":"todo","session_id":"session_…","agent_id":"main","event_created_at":"2026-11-04T19:34:17.194Z","items":[{"title":"List files","status":"in_progress"},{"title":"Read a file","status":"pending"},{"title":"Reply done","status":"pending"}]}
```

`items` is always the full list; `[]` clears the widget. The `todo` frame is
the authoritative read — deriving from `TodoList` tool calls is the old
2.4.x rule and misses undo/restore. The `TodoList` tool contract itself is
unchanged and still visible in the transcript as a `tool` frame (the
tool-call history is authoritative for what the model asked at a point in
time).

### The `TodoList` tool (unchanged)

The derivation-era text below is kept as history, and the tool contract is
still what the transcript renders:

- **Tool name:** `TodoList` (upstream `TODO_LIST_TOOL_NAME`). It appears as a
  `tool` frame in the transcript (`"name":"TodoList"`, `input.todos`, and a
  `display:{"kind":"todo_list","items":[…]}` object).
- **Input schema:** `{"todos": [{"title": string, "status": "pending" |
  "in_progress" | "done"}]}`. Every *write* carries the **full** list.
- **Write vs read:** a call *without* the `todos` key is a read-only query.
  The list is unchanged; skip it and keep looking back. An empty `todos`
  array clears the list.
- **Result prose:** a write result starts `Todo list updated.` followed by
  `Current todo list:` and may end with the next-step nudge (verified live
  2026-10-28 on 2.3.3). A read-only query carries `args:{}` and its result
  starts `Current todo list:` (verified 2026-10-22 on 2.3.0; not re-probed on
  2.3.3). The app ignores this prose and derives state from the input args.
- **Derivation rule:** scan the messages backwards for the newest `TodoList`
  call whose input carries a `todos` array — that is the current state.
- **Not on this wire:** upstream also persists the list as
  `tools.update_store` records (key `todo`) and a transcript-level
  `TranscriptTodo` entity (`todo.upsert`); the `todo` frame above is the only
  observable read model.
- **Web-client tolerance (FYI):** `latestTodos.ts` also maps `completed` →
  `done` and falls back to a `content` field for the title, for Claude-style
  `TodoWrite` compatibility. This server's `TodoList` tool emits only the
  schema above; the Android app does not replicate those fallbacks.

The Android app no longer derives the list (ticket 178): the widget is the
`todo` frame's, and `ChatTimeline` only hides `TodoList` tool rows from the
timeline in favour of that summary widget (ticket 042).

## Tools

### Tool catalog — `GET /api/v1/tools`

Lists the agent's tools. Undocumented upstream until now; verified
live on 0.31.0 (2026-07-30) and again on 0.31.1 (2026-07-31), 0.33.0
(2026-08-05), 0.34.0 (2026-08-06), 0.38.0 (2026-08-20) and 0.39.0
(2026-08-27). Response
`data.tools[]` builtin entries carry only
`{name, description, source: "builtin", active: <bool>, input_schema: null}` —
**input schemas are not exposed** (so tool-parameter drift, e.g. the
`TaskOutput` `block`/`timeout` removal, is not observable here). `active`
reflects the resolving session's live tool state (both values observed on
0.38.0). 27 builtin
tools observed on 0.38.0 and again on 0.39.0: Agent, AgentSwarm,
AskUserQuestion, Bash, CreateGoal,
CronCreate/Delete/List, Edit, EnterPlanMode, ExitPlanMode, FetchURL, GetGoal,
Glob, Grep, Read, select_tools, SetGoalBudget, Skill, TaskList,
TaskOutput, TaskStop, TodoList, UpdateGoal, WaitFor, WebSearch, Write.
**The count is
session-state-dependent:** on 0.34.0 fresh never-prompted sessions reported 26
(no `ReadMediaFile`) — the catalog is resolved from the live agent's state.
**2.5.0 (verified live 2026-11-04): 36 entries — 27 builtins plus the same 9
MCP tools** (`ReadMediaFile` is back, `select_tools` present, `NotifyUser`
still gated off).

**0.39.0: the catalog also lists MCP tools** (the unified MCP management
plane, commit `d723cc47`; verified live 2026-08-27): this server reports 36
entries — the 27 builtins plus 9 MCP tools from its two configured servers.
MCP entries carry `source: "mcp"` and an extra **`mcp_server_id`** field
(`{"name":"mcp__kiln__get_batch_status","description":"…","input_schema":null,"source":"mcp","active":true,"mcp_server_id":"kiln"}`).
The total count is now server-config-dependent on top of
session-state-dependent. **0.42.0 (verified live 2026-09-09): 37 entries —
28 builtins plus the same 9 MCP tools.** The 28th builtin is
**`ReadMediaFile`, back in the catalog** on this server (it was withheld
from non-multimodal models on 0.38.0); the new **`NotifyUser` tool is NOT
advertised** — it is gated behind the `notify_user` experimental flag
(`false` here, commit `f6a9c39e`). **2.0.0 (verified live 2026-09-17): 38
entries on an initialized agent — the same 28 builtins plus 10 MCP tools
(tavily gained `tavily_feedback`, now 6; kiln has 4) — and 37 on a fresh
never-prompted session (27 builtins + the same 10 MCP).** **2.1.1 (verified
live 2026-09-24): back to 37 entries on an initialized agent and 36 on a
fresh one — the same 28/27 builtins, 9 MCP tools (tavily lost
`tavily_feedback`, back to 5; kiln 4).** **2.2.0 (verified live
2026-10-14): 36 entries on an initialized agent — 27 builtins + the same 9
MCP tools — `ReadMediaFile` is out of the catalog again** (the catalog is
resolved from the live agent's model/state; treat the builtin count as
server/model-state-dependent, exactly like the 0.38.0 episode). `NotifyUser`
still gated off (`notify_user: false`). Content drift from
this server's MCP config, not wire-shape drift; the builtin set is
unchanged. **2.3.0 (verified live 2026-10-22): 37 entries on an
initialized agent — 28 builtins + the same 9 MCP tools (tavily 5, kiln
4)** — `ReadMediaFile` is back in the catalog again (same
model/state-dependent swing as the 0.38.0/2.0.0 episodes); `NotifyUser`
still gated off. Also re-observed this run: the 0.31.1
liveness caveat below still holds verbatim — right after session create,
before any prompt ran, the catalog answered `{"tools":[]}`; it resolved to
the 37 entries only once a session had run a turn. **2.3.3 (verified live
2026-10-28): back to 36 entries on an initialized agent: 27 builtins plus
the same 9 MCP tools, `ReadMediaFile` absent again.** `NotifyUser` is still
gated off. **2.4.0 (verified live 2026-10-28) and 2.4.1 (verified live
2026-10-30): 35 entries — 26 builtins + the same 9 MCP tools; the builtin
list no longer contains `EnterPlanMode` (plan mode became user-only), and
`ReadMediaFile` stays absent.** **2.5.0 (verified live 2026-11-04) and 2.5.1
(verified live 2026-11-05): 36 entries — 27 builtins + 9 MCP; the builtin
set includes `ReadMediaFile`** (the count stays model/session-state
dependent — keep treating it as live state). The app reads tool names and
`source`, so the swing is invisible to it. Two MCP routes ride along (both verified live
2026-08-27):

- `GET /api/v1/mcp/servers` →
  `{"servers":[{"id":"tavily","name":"tavily","transport":"http","status":"connected","tool_count":5},…]}`.
- `POST /api/v1/mcp/servers/{id}:restart` → `{"restarting":true}`;
  unknown id → `404` family (`mcp_server_not_found`).

**Liveness caveat (verified 2026-07-31, again 2026-08-27 on 0.39.0):** the
route resolves the catalog from
a live session's agent (the `session_id` query param, else the most recent
session). When NO session is live — e.g. right after a server restart, before
any session is opened — it answers `{"code":0,"data":{"tools":[]}}`, not an
error. The 0.31.1 Agent/AgentSwarm schema change (the `model` parameter is
stripped while the secondary-model experiment is off) is therefore both
invisible here and irrelevant to clients of this endpoint.

```bash
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:58627/api/v1/tools
```

### Lookup tool output text (Glob / Grep / WebSearch / FetchURL)

The `tool.result` `output` text shapes the app's rich rows parse (tickets
122/123). Not wire protocol — tool-generated prose from agent-core, like the
Bash `Command failed with exit code: N` trailer; verified 2026-09-01 against
the bundled tool source of the running 0.39.1 server binary
(`packages/agent-core/src/tools/builtin/fs/glob.ts`, `…/web/fetch-url.ts`,
`…/web/web-search.ts`; Grep: `packages/agent-core-v2/src/agent/tools/os/grep/grepTool.ts`
— the live backend is v2 per `/api/v1/meta`; the bundle's v1
`agent-core/src/tools/builtin/file/grep.ts` twin differs only in shorter
notice wordings, called out below), not a live capture.

**Glob** — one path per line (relative to the search root when under the
workspace, absolute otherwise). Optional head notices precede the paths, in
order: `Glob timed out after {N}s; partial results returned.`,
`[stdout truncated at {N} bytes; results may be incomplete \n use a more
specific pattern]` (two lines), a traversal warning (`Glob completed with
warnings; some directories could not be read[: {stderr}]`), and — when matches
exceed the 100 cap — the truncation marker, verbatim:

```
[Truncated at 100 matches 
 use a more specific pattern]
Only the first 100 matches are returned.
```

(the marker's own line break: trailing space after `matches`, leading space
before `use`). Optional foot lines follow the paths: `Filtered {N} sensitive
file(s).` and — only when exactly 100 matches WITHOUT truncation — `Found 100
matches`. No-match outputs: `No matches found`, `No non-sensitive matches found
({N} sensitive file(s) filtered).`. Errors (`is_error`): `{root} is not a
directory`, `{root} does not exist`, `Glob failed[: {stderr}]`, `Glob aborted`.

**Grep** — the tool runs `rg` with `--null` and reformats the NUL-separated
records into plain text lines (`[...headerLines, body, ...messages]`, empty
parts dropped, joined by `\n`). Per `output_mode` (default
`files_with_matches`):

- `content` (with `-n`, the default): match lines `path:NN:text`, context
  lines (`-A`/`-B`/`-C`) `path-NN-text`, `--` separator lines between
  non-contiguous ranges (leading/trailing/doubled separators normalized
  away). With `-n: false` both kinds render `path:text` (no numbers). Paths
  are relativized under the workspace.
- `files_with_matches`: one bare path per line, most-recently-modified first.
- `count_matches`: a head summary line `Found {N} total occurrences across
  {M} files.` (singular forms; `total non-sensitive` when the sensitive
  filter redacted a file), then the body as `path:{count}` lines. In this
  mode the pagination notice sits in the head, right after the summary.

Tail notices (any subset, in this order — except the pagination notice, which
moves to the head in `count_matches`): `Filtered {N} sensitive file(s): {p1,
p2}`, `Results truncated to {N} lines (total: {M}). Use offset={K} to see
more.` (or `…(total: {M} of a partial result set). Use …` after a
truncated/timed-out run — v1 has only the first form), then the two-line
buffer marker (its own line break, like Glob's):

```
[Output truncated at {N} bytes of rg output 
 the result set is incomplete. Narrow the pattern, path, or glob filters and re-run to recover complete results.]
```

then `Grep timed out after {N}s; partial results returned. Narrow the path,
glob, or pattern and retry for complete results.` (v1: just `Grep timed out
after {N}s; partial results returned`). Empty result, any mode: `No
non-sensitive matches found`. Errors (`is_error`): `Failed to grep:
{stderr-summary}` (+ a `ripgrep stderr:` section), `Grep aborted`, `Grep
timed out after {N}s. Try a more specific path or pattern.`, the
ripgrep-unavailable message. Local `rg` cross-check of the raw record shapes
(2026-09-01, bundled rg): content records are `path\0NN:text` / `path\0NN-text`
with bare `--` separator lines; files mode is NUL-separated paths; count
records are `path\0{N}`.

**WebSearch** — per result: `Title: {t}\n`, optional `Site:`/`Date:` lines,
`URL: {u}\n`, `Snippet: {s}\n\n`, results separated by `---\n\n`, closed by the
model-directed line `When you rely on a result in your answer, cite it inline
as a markdown link, e.g. [title](url).`. Empty: `No search results found.`;
errors are classified prose (`Search failed (network): …` etc.).

**FetchURL** — a head preamble, then a blank line, then the content verbatim.
The preamble's first sentence reports the extraction kind —
`The returned content is the main text extracted from the page.` or
`The returned content is the full response body, returned verbatim.`
(passthrough) — followed by the model-directed ` If you use it in your answer,
cite this page as a markdown link, e.g. [title](url).`. Errors: `Failed to
fetch URL. Status: {N}. {msg}`, `Failed to fetch URL due to network error:
{url}. {msg}`, `The response body is empty.`.

### Cron tool output text (CronCreate / CronDelete / CronList)

The cron tools' `tool.result` `output` text (ticket 124). Live captures from a
real session's `wire.jsonl` on the running 0.39.1 server (v2 backend),
cross-checked against the bundled tool source
(`packages/agent-core-v2/src/features/cron/tools/`, extracted with `strings`
from `~/.kimi-code/bin/kimi` — the ticket-122 method).

**CronCreate** — NOT JSON: newline-joined `key: value` lines. Verbatim live
capture (2026-09-01, session `04a03224…`, one-shot create):

```
id: 01KYXPV7A4HPX4GNZAAGTSRSMH
cron: 15 7 1 8 *
humanSchedule: at 07:15 on day 1 of August
recurring: false
nextFireAt: 2026-08-01T07:15:00.000+00:00
```

(`formatOutput()` in `cronCreateTool.ts`; `nextFireAt` is the local ISO
timestamp with numeric offset, or the literal `null`.) This drove the ticket's
parse-or-verbatim decision to **verbatim**: the row shows the cron-expression
preview and renders the result untouched. Errors (`is_error`): `Invalid cron
expression: …`, `Cron expression "…" has no fire within 5 years; refusing to
schedule.`, `Cron job cap reached (max 50 per session).`, `Prompt exceeds 8192
bytes (got N).`, the one-shot-too-far-out message, `Cron scheduling is disabled
(KIMI_DISABLE_CRON=1).`.

**CronDelete** — one line: `Deleted cron job {id}.` (live capture, same
session). Errors: `No cron job with id {id}.`, `Invalid cron job id "…" — must
be a ULID.`.

**CronList** — `cron_jobs: {N}` header, then records joined by `\n---\n`, each
record the `key: value` lines `id`/`cron`/`humanSchedule`/`prompt`
(JSON-encoded, 200-byte preview cap)/`nextFireAt`/`recurring`/`ageDays`
(2-decimal)/`stale`; the empty case is `cron_jobs: 0\nNo cron jobs scheduled.`
(source-verified; no live capture — and deliberately unparsed by the app:
multi-record prose, drift-prone).

## Sub-agents

How the server exposes the sub-agents the main agent spawns with its `Agent`
(or `AgentSwarm`) tool. **2.5.0 rewrote this surface**: the `subagent.*`
lifecycle events, `task.started`/`task.terminated`, `background.task.*`,
`task.notified`, the `context.spliced` notification turn and the snapshot
roster are all gone. A sub-agent is now (a) a `task` frame with
`kind:"subagent"` in the owning agent's task stream and (b) an
automatically-subscribed child agent whose transcript frames arrive on the
same connection. Verified live 2026-11-04 (one foreground and one
`run_in_background:true` explore spawn).

- **Spawn** — the main agent gets a `task` frame (verbatim, trimmed):

  ```json
  {"type":"task","session_id":"session_…","agent_id":"main","event_created_at":"…",
   "task_id":"agent-fe31xobl","kind":"subagent","status":"running","phase":"working",
   "detached":false,"description":"Reply fg-ok","child_agent_id":"agent-0",
   "parent_tool_call_id":"call_00_M5xWP2WYjLLcCuNBij6J8641",
   "started_at":"…","model":"deepseek/deepseek-v4-flash","thinking":"max"}
  ```

  `detached:false` is a foreground spawn (its tool call waits);
  `detached:true` is `run_in_background:true`. `description` is the spawn
  description, not the prompt.
- **Child announcement** — the child agent joins the same connection with
  `transcript.reset` (`agent_id:"agent-0"`, `snapshot.items:[]`, `seq:0`),
  then `agent` (and the other state frames when applicable). No
  `subagent.spawned` carries its type; the spawning tool call's input has
  `subagent_type`, and `GET .../transcript?agent_id=main`'s `agents` array
  lists every child (`{agentId:"agent-0",type:"sub",parentAgentId:"main"}`).
  The child's own turns are still `GET .../transcript?agent_id=agent-0`.
- **Completion** — the last `task` frame is `status:"ended"` with
  `outcome` (`completed`/`failed`/`timed_out`/`killed`/`lost`),
  `ended_at`, and optional `result_summary`/`error`/`state_reason`:

  ```json
  {"type":"task","session_id":"session_…","agent_id":"main","event_created_at":"…",
   "task_id":"agent-fe31xobl","kind":"subagent","status":"ended","outcome":"completed",
   "detached":false,"description":"Reply fg-ok","child_agent_id":"agent-0",
   "parent_tool_call_id":"call_00_…","started_at":"…","ended_at":"…",
   "model":"deepseek/deepseek-v4-flash","thinking":"max"}
  ```
- **Cancel / detach** — `task.stop` replaces REST `:cancel`; the ended frame
  follows with `outcome:"killed"`. `task.background` replaces `:detach` and
  flips `detached:true` while `status` stays `running`. Both are flat control
  frames; see WebSocket → Task controls.
- **Swarm members** — each member is registered as a foreground subagent task
  of the caller with `swarm_index`; the same task lifecycle applies.
- **Two id namespaces** — `task_id` is `agent-…`; `child_agent_id` /
  the frame's `agent_id` is the child's agent id (`agent-0`, …). The child's
  own frames are stamped `agent_id:"agent-0"`, not `"main"`.

The spawning tool call and its result prose are unchanged; the historical
`subagent.*` lifecycle text below is kept as history.

### The spawning tool call in the main transcript

The spawn is an ordinary tool call named `Agent` (verbatim history part):

```json
{"type":"tool_use","tool_call_id":"tool_mhF8UXcf5QwvZbh5223tiIng","tool_name":"Agent",
 "input":{"subagent_type":"explore","description":"doc-verify-subagent",
          "prompt":"Reply with the single word: ok"}}
```

The live `tool.call.started` for the `Agent` tool carries a dedicated
**`display`** (new in 0.31.0):
`"display":{"kind":"agent_call","agent_name":"explore","prompt":"..."}`
(a background spawn adds `"background":true`). The Agent tool also gained a
**`resume`** parameter (see the background result text below). Upstream note:
the `TaskOutput` tool lost its `block`/`timeout` input params and is now
always non-blocking — tool input schemas are not observable on this wire
(`GET /api/v1/tools` returns `input_schema: null`; see the Tools section).

The tool result is plain text. Foreground (waits for completion) —
**0.43.0 (verified live 2026-09-14) adds the `stop_reason:` line and the
trailing `resume_hint:` block** (pre-0.43.0 the foreground result stopped
after the `[summary]` block):

```
agent_id: agent-71
actual_subagent_type: explore
status: completed
stop_reason: completed

[summary]
<the sub-agent's result summary>

resume_hint: Continue with Agent(resume="agent-71", prompt="..."). Use agent_id only; do not set subagent_type. The subagent retains its prior context; redo any unfinished tool call if its result was lost.
```

Background (returns immediately). The head of the contract
(`task_id:`/`status:`/`agent_id:`/`actual_subagent_type:`) is unchanged;
**0.31.0 appends `next_step:`/`resume_hint:` blocks** after the description:

```
task_id: agent-7noib8bb
status: running
agent_id: agent-13
actual_subagent_type: explore
automatic_notification: true

description: doc-verify-bg

next_step: The completion arrives automatically in a later turn — do NOT wait, poll, or call TaskOutput on it; ...

resume_hint: To continue or recover this same subagent later, call Agent(resume="agent-1", prompt="..."). The parameter is agent_id ("agent-1"), NOT task_id ...
```

The killed-task notification body carries the same resume hint.

The `AgentSwarm` call's result is an **XML document** instead —
upstream-schema-only (never captured live; pinned from `renderSwarmResults`,
`packages/agent-core-v2/src/features/swarm/tools/agent-swarm/agentSwarmTool.ts`
at tag 0.43.1, and its tests). One `<subagent>` element per planned member,
attribute order `mode agent_id item state outcome stop_reason` (every one
optional but `outcome`; `agent_id` absent when the member never spawned,
`state="not_started"` marks those; `stop_reason` rides completed runs that
stopped on their own condition). Attribute values are XML-escaped; member
bodies are NOT (a member's raw output can nest the tag family):

```
<agent_swarm_result>
<summary>completed: 1, aborted: 2</summary>
<resume_hint>Call AgentSwarm with resume_agent_ids using the agent_id values in this result to continue unfinished work.</resume_hint>
<subagent agent_id="agent-coder-1" item="src/a.ts" outcome="completed">imports are stable</subagent>
<subagent agent_id="agent-coder-2" item="src/b.ts" state="started" outcome="aborted">The user manually interrupted this subagent batch before this subagent finished.</subagent>
<subagent item="src/c.ts" state="not_started" outcome="aborted">The user manually interrupted this subagent batch before this subagent was started.</subagent>
</agent_swarm_result>
```

The `<summary>` counts show only when > 0; `<resume_hint>` is present when any
member is unfinished or carries a stop reason and any member has an agent id.

`GET .../messages` history carries only the main agent's messages — the
`Agent` tool_use/tool_result pair is the **only** trace of a sub-agent there
(verified 2026-07-28).

### Foreground lifecycle — historical `subagent.*` sequence (removed in 2.5.0)

**2.5.0: these frames no longer exist.** Use the `task` frames above. The
capture is kept for older journals.

```
seq 17  tool.call.started   name "Agent", args {subagent_type, description, prompt} (main)
seq 18  agent.created       payload {"agentId": "agent-12", "sessionId": ...}
seq 19  subagent.spawned    identity metadata (payload.agentId "main")
seq 19  agent.status.updated  0.31.0: republication for the sub-agent, same seq,
                            volatile — see below
seq 20  turn.started        agentId "agent-12"; origin {"kind":"system_trigger","name":"subagent"}
seq 21  context.spliced     the sub-agent's first internal user message
seq 22  subagent.started    {subagentId}
seq 23+ turn.step.*, thinking/assistant deltas, ...   all agentId "agent-12"
seq 25  turn.ended + prompt.completed   one pair per internal turn (the task
                            turn, then a forced result-summary turn)
seq 33  subagent.completed  resultSummary + usage + contextTokens
seq 34  tool.result         the Agent call's result; the main turn resumes
```

`subagent.spawned` (verbatim; carries all the identity metadata — later
`subagent.*` events carry only `subagentId`). A 0.38.0 frame (2026-08-20,
foreground spawn — note `taskId` and `time`):

```json
{"type":"subagent.spawned","seq":41,"payload":{"type":"subagent.spawned","time":1787254524517,"subagentId":"agent-1","subagentName":"explore","parentToolCallId":"tool_ArVyKiUhCDxXT341BmjTba8L","parentAgentId":"main","callerAgentId":"main","description":"Ask what is 2+2","runInBackground":false,"model":"kimi-code/k3","thinkingEffort":"high","taskId":"agent-rhh36xem","agentId":"main","sessionId":"session_59e4fb8e-..."}}
```

**0.31.0:** immediately after
`subagent.spawned` (same seq, volatile) the server **republishes the
sub-agent's `agent.status.updated`** — a duplicate of the one that follows
`agent.created`. **0.34.0 (additive):** the payload gained **`model`** and
**`thinkingEffort`** (camelCase; the spawned child inherits the session's
bound model — `"kimi-code/k3"`/`"high"` observed on both foreground and
background spawns; **0.36.0:** `"low"` observed — the effort follows the
session's thinking config, re-verified 2026-08-13). **0.38.0 (additive,
verified live 2026-08-20 on both foreground and background spawns):**
the payload carries **`taskId`** — the background-task id the run registered
under in the caller's task store (e.g. `"agent-rhh36xem"`, present even for
foreground runs, though foreground runs still emit no `task.*` frames) — and
the event now fires **after** task registration (commit `be8e0175`: clients
can bind cancel/status actions without waiting for `task.started`); the
duplicate launch-time `subagent.spawned` emission was removed (commit
`3b69765c`). Also observed on 0.38.0: `subagent.*` / `task.*` / `tool.call.*`
payloads all carry the top-level **`time`** (epoch ms) field, like
`turn.started` since 0.37.2. The pair, verbatim (0.34.0):

```json
{"type":"subagent.spawned","seq":33,"payload":{"type":"subagent.spawned","subagentId":"agent-2","subagentName":"agent","parentToolCallId":"tool_jaW1ZPJgetZxZ4ZgLQmQM4wP","parentAgentId":"main","callerAgentId":"main","description":"Reply OK","runInBackground":false,"model":"kimi-code/k3","thinkingEffort":"high","agentId":"main","sessionId":"session_1143a947-..."}}
{"type":"agent.status.updated","seq":27,"volatile":true,"payload":{"type":"agent.status.updated","model":"kimi-code/k3","thinkingEffort":"high","maxContextTokens":1048576,"usage":{},"contextTokens":0,"agentId":"agent-0","sessionId":"session_7f33a76c-..."}}
```

(0.29.2 capture for comparison:)

```json
{"type":"subagent.spawned","seq":19,
 "payload":{"type":"subagent.spawned","subagentId":"agent-12","subagentName":"explore",
            "parentToolCallId":"tool_mhF8UXcf5QwvZbh5223tiIng","parentAgentId":"main",
            "callerAgentId":"main","description":"doc-verify-subagent",
            "runInBackground":false,"agentId":"main","sessionId":"session_..."}}
```

Also new in 0.31.0: the sub-agent's first internal user message (the
`context.spliced` after its `turn.started`) embeds a
**`<git-context status="unavailable" reason="not-a-repo"/>`** prefix when the
session cwd is not a git repository. **Gone again in 0.34.0** (verified
2026-08-06 with a non-repo cwd: zero `git-context` occurrences in the full
stream — the first spliced text is the raw prompt verbatim).

`subagent.completed` (verbatim, `resultSummary` elided):

```json
{"type":"subagent.completed","seq":33,
 "payload":{"type":"subagent.completed","subagentId":"agent-12",
            "resultSummary":"<full result summary>",
            "usage":{"inputOther":6462,"output":359,"inputCacheRead":21504,"inputCacheCreation":0},
            "contextTokens":14358,"agentId":"main","sessionId":"session_..."}}
```

Upstream schema only (not captured live): `subagent.spawned` may also carry
`parentToolCallUuid` and `swarmIndex` (the `AgentSwarm` member index);
`subagent.started` re-fires when a suspended sub-agent resumes;
`subagent.suspended` is `{subagentId, reason}` (not terminal);
`subagent.failed` is `{subagentId, error}`.

`subagent.cancelled` — **captured live since 2.0.0** (commit `7d174ac9`;
verified live 2026-09-17): `{type, time, subagentId, agentId:"main",
sessionId}` — terminal, a deliberate stop. It fires on REST `:cancel` of a
running foreground AND background sub-agent; on earlier versions cancels
produced no terminal `subagent.*` event at all (the live stream carried only
the same truth as `task.terminated` with `status:"killed"`). Verbatim frame:

```json
{"type":"subagent.cancelled","seq":25,
 "payload":{"type":"subagent.cancelled","time":1789673862080,
            "subagentId":"agent-0","agentId":"main","sessionId":"session_..."}}
```

Observed sequences (2026-09-17): **foreground cancel** → `prompt.aborted`
(the running prompt's id, `abortedAt`) → `subagent.cancelled` → the spawning
Agent `tool.result` with `isError:true` and output
`agent_id: agent-0\nactual_subagent_type: explore\nstatus: failed\nstop_reason: cancelled\n\nsubagent error: The subagent was stopped before it finished by user.\nnext_step: …`.
**Background cancel** → the sub-agent's internal `prompt.aborted`
(`agentId:"<subagentId>"`) → `subagent.cancelled` → `task.terminated`
(`info.status:"killed"`, `stopReason:"Aborted by the user"`) →
`background.task.terminated` (same `info`) → a task-origin notification turn
(`turn.started` origin `{"kind":"task","taskId":"agent-1wjam2nz","status":"killed","notificationId":"task:agent-1wjam2nz:killed"}`)
+ `task.notified` (`notificationType:"task.killed"`). After a cancel the
snapshot roster entry reads `"status":"cancelled","subagent_phase":"cancelled"`
(see the snapshot roster) and REST `/tasks/{id}` shows `status:"cancelled"`.

### Sub-agent turn frames share the session channel (now transcript ops)

**2.5.0:** a sub-agent's turn content arrives as `transcript.reset`/
`transcript.ops` frames stamped with its own `agent_id`; there are no
`turn.*`/`assistant.delta` frames to route by payload stamp any more.
Historical note follows.

Everything the sub-agent does streams over the **same** session
subscription, tagged with `agentId:"<subagentId>"`: `turn.started` /
`turn.step.*` / `context.spliced` / `thinking.delta` / `assistant.delta` /
`tool.call.*` / `tool.result` / `prompt.completed`. Turn ids are per-agent
(main and sub-agent both number from 0), and `prompt.completed` fires for
the sub-agent's internal turns with internal prompt ids. **A client that
renders only the main transcript must filter on `payload.agentId ==
"main"`** — otherwise sub-agent deltas leak into the main chat. (The Android
app tags these frames' events with the emitting agentId in `SessionSocket`
and routes sub-agent events into a per-sub-agent stream store backing the
sub-agent page — tickets 044/045; sub-agent approvals/questions deliberately
still surface in the main chat — they are actionable.)

### Background lifecycle — historical `task.*` sequence (removed in 2.5.0)

**2.5.0: `task.started`/`task.terminated`/`background.task.*`/`task.notified`
are gone.** Background work is a `task` frame with `detached:true`; its
completion is the ended frame plus the ordinary task-origin turn in the
transcript. Historical capture follows.

Spawn half (0.29.2 capture, unchanged on 0.31.0; **ordering drift since
0.38.0** — registration now precedes the spawn events: the 0.40.1 capture
(2026-09-02) reads `task.started` → `background.task.started` →
`subagent.spawned` → `subagent.started`, matching the "spawned fires after
task registration" note in the foreground section):

```
seq 46  tool.call.started   name "Agent", args {..., "run_in_background":true}
seq 48  subagent.spawned    "runInBackground":true
seq 49+ sub-agent turn frames (agentId "agent-13"), as in the foreground path
seq 51  subagent.started
seq 52  task.started        task-store registration (REST-visible from here)
seq 53  background.task.started     legacy alias — same payload as task.started
seq 55  tool.result         "task_id: ... status: running ..." (immediate)
```

**Completion half — restructured in 0.31.0.** The completion is no longer just
spliced into the running context: it is delivered inside a **NEW main turn**
(0.31.0 capture, verbatim seqs):

```
seq 83  subagent.completed
seq 84  task.terminated     info.status "completed"
seq 85  background.task.terminated  legacy alias
seq 86  event.session.work_changed  busy=false, last_turn_reason "completed"
seq 87  turn.started        MAIN agent — origin {"kind":"task",...}, see below
seq 88  event.session.work_changed  busy=true
seq 89  task.notified       notificationType "task.completed"
seq 90  context.spliced     a `<notification ...>` user message spliced into the
                            MAIN agent — how the main agent learns of it
```

- New `turn.started` origin kind **`task`** (verbatim):
  `{"kind":"task","taskId":"agent-a7fxomh0","status":"completed","notificationId":"task:agent-a7fxomh0:completed"}`.
- The notification splice message's origin is `{"kind":"task",...}` (not
  `user`) and it has **no `id` field** (keep treating spliced-message ids as
  optional). The `<notification>` element gained attributes (`id`, `category`,
  `type`, `source_kind`, `source_id`, `agent_id`) plus an
  `<output-file path=".../agents/main/tasks/<taskId>/output.log" bytes="1532">`
  child telling the agent to Read the output file. The `task.notified`
  payload itself is unchanged.
- The background Agent tool **result text** now ends with `next_step:` /
  `resume_hint:` blocks (see the spawning tool call subsection); the Agent
  tool gained a `resume` parameter.

`task.started` / `task.terminated` payload (verbatim, from the cancel run):

```json
{"type":"task.terminated","seq":109,
 "payload":{"type":"task.terminated",
            "info":{"taskId":"agent-lftjyr4o","description":"doc-verify-cancel",
                    "status":"killed","detached":true,
                    "startedAt":1785210101939,"endedAt":1785210118804,
                    "stopReason":"Aborted by the user","timeoutMs":7200000,
                    "kind":"agent","agentId":"agent-14","subagentType":"agent"},
            "agentId":"main","sessionId":"session_..."}}
```

**0.34.0 (additive):** agent-kind `info` gained **`model`** and
**`thinkingEffort`** (camelCase) on `task.started`/`task.terminated` and
their `background.task.*` legacy aliases alike (verbatim 0.34.0
`task.started`):

```json
{"type":"task.started","seq":103,
 "payload":{"type":"task.started",
            "info":{"taskId":"agent-bpy0kylc","description":"Sleep 240 then reply OK",
                    "status":"running","detached":true,"startedAt":1786044903637,
                    "endedAt":null,"timeoutMs":7200000,
                    "kind":"agent","agentId":"agent-4","subagentType":"agent",
                    "model":"kimi-code/k3","thinkingEffort":"high"},
            "agentId":"main","sessionId":"session_..."}}
```

`info.status` uses the **core** vocabulary (`running` | `completed` |
`failed` | `timed_out` | `killed` | `lost`), not the REST wire one (mapping
below); `kind:"agent"` marks a sub-agent (`process` = background bash).
**0.41.0 (commit `b6b9b374`; schema-level, not triggered live):** a sub-agent
stopped by the repeat breaker now settles `completed` **with** a
`stopReason` on this `info` ("stopped by the repeat breaker after issuing the
same tool call repeatedly; its output is a handoff, not a finished result") —
before, `stopReason` appeared only on failed/killed tasks. The engine also
tracks a per-task `stopCode`, but **no kap-server projection serves it** (the
0.41.0 REST/snapshot/task sources never reference it — engine-internal only),
and the upstream wire manifest gained an optional **`stopReason` on
`turn.ended`** (forced stops; every live `turn.ended` captured on 0.41.0 —
completed and cancelled alike — omits it). The Android app already decodes
the optional `stopReason` on task `info`.
`task.notified` (verbatim shape): `{"notificationType":"task.completed" |
"task.killed","title":"...","body":"...","severity":"info" | "warning",
"sourceKind":"background_task","sourceId":"<taskId>"}`.
**2.1.1 (commit `0dc4d4fb`; verified live 2026-09-24):** the `body` — and the
`<notification>` history XML alike — now opens with a
`Wall time: N.NNN seconds` line (the task tools' output text prepends the
same header). Prose, not shape; the app never parses either.

Two **different** agent ids ride these frames: the payload-level `agentId`
(`"main"` above) is the envelope stamp naming the **owning** agent — the one
whose task store holds the task — while `info.agentId` links a
sub-agent-kind task to the spawned sub-agent's page id. A background task
spawned BY a sub-agent is stamped with the sub-agent's id (verified from the
kap-server source on 0.33.0: every agent's event bus — main and dynamically
created sub-agents alike — feeds the session broadcaster, which stamps
`agentId`/`sessionId` onto each payload). Those sub-agent-owned tasks are
**live-only**: REST `/tasks` resolves only the main agent's task service, so
they never appear in list/get, and `:cancel` answers `40406` for them.

### Task frames and controls (2.5.0)

There is no REST task API any more. Every `/api/v1/sessions/{id}/tasks…`
route is a plain Fastify 404 (GET and POST verified live); `GET .../status`
and `GET .../snapshot` are gone too. Tasks arrive on the session
subscription:

- **`task.list`** — sent when a connection starts receiving an agent:
  `{"type":"task.list","session_id":"…","agent_id":"main","tasks":[<task>,…]}`.
  It replaces the client list and contains only currently running tasks.
- **`task`** — sent on every change, carrying the **full** state:
  `{task_id,kind:"shell"|"subagent"|"other",status:"running"|"ended",
  outcome?,phase?,detached,description?,child_agent_id?,
  parent_tool_call_id?,swarm_index?,command?,started_at?,ended_at?,
  result_summary?,error?,state_reason?,model?,thinking?}`. The ended frame
  (`status:"ended"`, `outcome` one of `completed`/`failed`/`timed_out`/
  `killed`/`lost`) is the last one; ended tasks are not in a later
  `task.list`, and the history carries their outcome.
- **`task.output`** — only to a connection that sent `task.watch`:
  `{type:"task.output",session_id,agent_id,task_id,offset,text}`, where
  `offset` is the UTF-8 byte offset of `text` in the task's output.
- **Flat controls** — fields sit beside `type`, `request_id` replaces `id`,
  and the reply is `{type:"response",request_id,code,msg?,task?}` (not an
  ack). All four verified live:

  | Frame | payload | reply |
  | --- | --- | --- |
  | `task.watch` | `{request_id,session_id,agent_id,task_id,since_offset?,tail_bytes?}` | `code:0`, the task state, then one `task.output` (last `tail_bytes`/32 KiB default, at most 1 MiB) and live chunks |
  | `task.unwatch` | `{request_id,session_id,agent_id,task_id}` | `code:0` |
  | `task.stop` | same | `code:0`, the ended task state; an already-ended task just replies with its state |
  | `task.background` | same | `code:0`, the task state with `detached:true`; a foreground-only task (e.g. a swarm member) fails `40933` with the state |

  Errors: `40001` malformed frame, `40401` unknown session, `40406` the
  agent has no such task, `40933` not movable to the background, `50001`
  internal, `50003` output unreadable. A frame without a readable
  `request_id` closes the socket with code 1008.

Verbatim task frames from the 2.5.0 run (a shell task and a background
sub-agent), trimmed:

```json
{"type":"task.list","session_id":"session_…","agent_id":"main","tasks":[]}
{"type":"task","session_id":"session_…","agent_id":"main","event_created_at":"…",
 "task_id":"bash-s9yny87s","kind":"shell","status":"running","detached":false,
 "description":"Bash: sleep 30 && echo watched-ok",
 "parent_tool_call_id":"call_00_…","command":"sleep 30 && echo watched-ok",
 "started_at":"…"}
{"type":"task","session_id":"session_…","agent_id":"main","event_created_at":"…",
 "task_id":"agent-v93x0tjv","kind":"subagent","status":"running","phase":"working",
 "detached":true,"description":"Sleep then reply bg-ok","child_agent_id":"agent-1",
 "parent_tool_call_id":"call_00_…","started_at":"…",
 "model":"deepseek/deepseek-v4-flash","thinking":"max"}
{"type":"response","request_id":"w1","code":0,
 "task":{"task_id":"bash-s9yny87s","kind":"shell","status":"running","detached":false,
         "description":"Bash: sleep 30 && echo watched-ok",
         "parent_tool_call_id":"call_00_…","command":"sleep 30 && echo watched-ok",
         "started_at":"…"}}
{"type":"task.output","session_id":"session_…","agent_id":"main",
 "task_id":"bash-s9yny87s","offset":0,"text":""}
```

The historical REST shape below is kept for older servers/journals:

All envelope-over-HTTP-200 (verified 2026-07-28, re-verified unchanged
2026-07-30 on 0.31.0):

```bash
curl -s -H "Authorization: Bearer <token>" \
  'http://127.0.0.1:58627/api/v1/sessions/<sid>/tasks?status=completed'
curl -s -H "Authorization: Bearer <token>" \
  'http://127.0.0.1:58627/api/v1/sessions/<sid>/tasks/<task_id>?with_output=true&output_bytes=4096'
curl -s -X POST -H "Authorization: Bearer <token>" \
  'http://127.0.0.1:58627/api/v1/sessions/<sid>/tasks/<task_id>:cancel'
curl -s -X POST -H "Authorization: Bearer <token>" \
  'http://127.0.0.1:58627/api/v1/sessions/<sid>/tasks/<task_id>:detach'
```

- **List** — `GET .../tasks` → `{"items":[...]}`; optional
  `?status=running|completed|failed|cancelled` (other values → `40001`).
  Serves the main agent's background-task store: background sub-agents
  (`kind:"subagent"`), background bash (`kind:"bash"`), question flows
  (`kind:"tool"`). Terminal tasks stay listed. Core→wire mappings: kind
  `agent`→`subagent`, `process`→`bash`, `question`→`tool`; status
  `killed`→`cancelled`, `timed_out`/`lost`→`failed`. **Main-agent only** —
  a sub-agent's own background tasks are never listed, fetched, or
  cancellable here (their only channel is the live `task.*` frames, see the
  owning-agent stamp above). **0.34.0:** a **running foreground sub-agent**
  is listed too (`kind:"subagent"`, `status:"running"`, with
  `model`/`thinking_effort`) and dropped from the list once terminal — see
  the foreground bullet at the top of this section.
- **Get** — `GET .../tasks/{task_id}`; `with_output=true` adds
  `output_preview` (tail of the stored result, default cap 32768 bytes —
  override with `output_bytes`) and `output_bytes` (byte length of the
  returned preview). Unknown task → `40406`; unknown session → `40401`.
  For background **bash** tasks this works **while still `running`** —
  `output_preview` returns the live partial stdout/stderr and grows
  between polls (verified 2026-08-05 on 0.32.0: a 120-tick loop polled
  every 4 s returned 67, 71, 75, … 96 ticks with `completed_at` absent).
  This REST poll is the only way to watch a background command's output:
  **no `tool.progress` frames are emitted for background bash** (zero
  observed across two full runs on 0.32.0, 2026-08-05 — the WS stream
  only carries `task.started`/`task.terminated` lifecycle events).
- **Cancel** — `POST .../tasks/{task_id}:cancel` → `{"cancelled":true}` on a
  running task; a terminal task answers `40904` with
  `data:{"cancelled":false}` + `details.current_status`. Cancelling a
  running background sub-agent (re-verified unchanged on 0.31.0, 2026-07-30)
  emits `turn.step.interrupted` → `turn.ended` → `prompt.aborted` →
  `task.terminated` with
  `status:"killed"` + `stopReason:"Aborted by the user"` — **no**
  `subagent.failed`/`subagent.completed` — then the `background.task.terminated`
  legacy alias, `task.notified`
  (`notificationType:"task.killed"`, severity warning) and the notification
  splice; REST reads
  `"cancelled"` afterwards. **0.34.0:** `:cancel` also works on a **running
  foreground** sub-agent (verified 2026-08-06): the Agent tool returns
  `status: failed` ("subagent error: The subagent was stopped before it
  finished"), the sub-agent's WS shows `tool.result` "Interrupted by user" →
  `turn.step.interrupted` → `turn.ended` `reason:"cancelled"` →
  `prompt.aborted` — still **no `task.*` frames** and no
  `subagent.failed`/`subagent.completed` for it.

Task object (verbatim, 0.39.0 — subagent-kind objects carry `model`/
`thinking_effort` (snake_case, since 0.34.0), `agent_id`, `subagent_type` and
`parent_tool_call_id` (since 0.36.1), and, **new in 0.39.0** (commit
`6595955b`), **`run_in_background`** on every task, list and get, running and
terminal; 2026-08-27 capture of a completed background sub-agent):

```json
{"id":"agent-ptkje5ey","session_id":"session_...","kind":"subagent",
 "description":"Compute 2+2","status":"completed",
 "created_at":"2026-08-27T19:39:16.435Z","started_at":"2026-08-27T19:39:16.435Z",
 "run_in_background":true,
 "completed_at":"2026-08-27T19:39:35.940Z",
 "model":"kimi-code/k3","thinking_effort":"high",
 "agent_id":"agent-1","subagent_type":"coder",
 "parent_tool_call_id":"tool_JBuc3yrESF5aEiDPXUy025NK"}
```

A **running foreground** sub-agent lists with `"run_in_background":false`
(verified live 2026-08-27). Since 0.39.0 (commit `692bb0a4`)
`parent_tool_call_id` is also emitted on `kind:"bash"` (process) tasks when
set — it was subagent-only before. `agent_id` is the sub-agent registry id
the WS `subagent.*` events and the snapshot roster already use, so REST tasks
can be joined to the roster without matching on `description`. With
`with_output=true` the object additionally carries `output_preview` (tail of
the stored result) + `output_bytes`. `created_at` == `started_at` still
holds.

- **Detach** — **new in 0.39.0** (commit `692bb0a4`; verified live
  2026-08-27): `POST .../tasks/{task_id}:detach` (empty body) moves a running
  **foreground** task to the background →
  `{"code":0,...,"data":{"detached":true,"status":"running"}}`. An
  already-background or terminal task answers
  `{"detached":false,"status":<current>}`; unknown task → `40406`. Effects:
  the foreground `Agent` tool call resolves immediately with a stub result
  ("note: The user moved this subagent to the background. …"), the task
  flips to `run_in_background: true` on REST, the WS emits `task.started` +
  `background.task.started` for it (a foreground task has no `task.*` frames
  until detached), and completion then follows the background lifecycle
  (`subagent.completed` + `task.terminated` + the task-origin notification
  turn). The Android app detaches from the sub-agent page's overflow ("To
  background" → `:detach`, ticket 138); the conversion's `task.started`
  flips the member record to background.

Background **bash** tasks live in their own id namespace (`bash-…`, vs
`agent-…` for sub-agents) and carry the shell **`command`** on the wire
(0.32.0, verified 2026-08-05):

```json
{"id":"bash-xbje00tt","session_id":"session_...","kind":"bash",
 "description":"tick counter loop","status":"completed",
 "created_at":"…","started_at":"…","completed_at":"…",
 "command":"for i in …"}
```

plus `output_preview`/`output_bytes` with `with_output=true`.

The background Bash tool **result** is an immediate plain-text stub — the
command's real output lives in the task store (the get-with-output poll
above); history carries the same text in the `tool_result` part, so the
`task_id:` link works for live and re-entered sessions alike (verbatim,
0.32.0, 2026-08-05):

```
task_id: bash-2q5gu61e
pid: 502439
description: tick counter loop (120)
status: running
automatic_notification: true
next_step: …
```

Background **Agent** results also start with `task_id:` but an `agent-…` id
(see the spawning tool call subsection) — the `bash-` prefix is what
distinguishes the two.

Timeout facts (spiked live on 0.32.0, 2026-08-05 — two throwaway background
commands, one completing, one with `timeout: 5`):

- **REST never exposes the timeout** — not even when the call set it
  explicitly (`bash-mm1xr92b`, `timeout: 120`, completed: no timeout field).
  The only wire location is the **tool call input** (`timeout`, seconds;
  `disable_timeout`, boolean), present in live args and history `tool_use`
  parts alike.
- **A timed-out job collapses to `status:"failed"` on REST** —
  indistinguishable from a crash (`bash-w662jmuf`, `timeout: 5`: `failed`,
  `completed_at - started_at` = 5.003 s — the server enforced the budget
  exactly). Only the live WS path keeps the truth: `task.terminated` core
  status `timed_out`, plus a `task.notified` with
  `notificationType:"task.timed_out"` (severity warning).
- **The 600 s background default and `disable_timeout` semantics are the
  harness/tool-schema contract** (unset `timeout` ⇒ 600 s for background
  commands; `disable_timeout: true` ⇒ no kill) — NOT observable on this
  wire. The app hardcodes the default for its budget display; if the tool
  schema changes it, this note is the drift flag.

### Session snapshot roster — **removed in 2.5.0**

**2.5.0: the snapshot route is gone.** The roster's data now comes from
`GET .../transcript?agent_id=main`'s `agents` array
(`{agentId,type:"main"|"sub",parentAgentId?}`) and from `task` frames with
`kind:"subagent"` (`child_agent_id`, `parent_tool_call_id`, `detached`,
`phase`, `model`, `thinking`, `result_summary`). Historical shape follows.

`GET /api/v1/sessions/{sid}/snapshot` (see Sessions → Session snapshot) carries
an optional `subagents` array: the **foreground** roster at the watermark, so a
reconnecting client can rebuild the identity metadata (cursor-resume replays
nothing earlier than the cursor, and `subagent.spawned` is never replayed).
Verified 2026-07-28 right after the foreground run, re-verified 2026-07-30 on
0.31.0 (including the reset-to-`[]` behavior described below); running vs
terminal entries captured on 0.31.1, 2026-08-01.

While running — `status`/`subagent_phase` read `running`/`working`, and
`completed_at`/`output_preview` are absent (**0.34.0, additive:** entries
gained **`model`**/**`thinking_effort`**, snake_case, running and terminal
alike — verbatim 0.34.0 running entry):

```json
{"id":"agent-5","session_id":"session_...","kind":"subagent",
 "description":"Sleep 20 then reply OK","status":"running",
 "subagent_phase":"working","subagent_type":"agent",
 "parent_tool_call_id":"tool_zBY7RxT22gFKxyvchCt15jya","run_in_background":false,
 "model":"kimi-code/k3","thinking_effort":"high",
 "created_at":"2026-08-06T19:38:08.384Z","started_at":"2026-08-06T19:38:08.390Z"}
```

Terminal — both state fields flip and the run's result-summary tail arrives
(0.31.1 capture; on 0.34.0 it additionally carries the same
`model`/`thinking_effort` pair):

```json
{"id":"agent-3","session_id":"session_...","kind":"subagent",
 "description":"Sleep then answer ok","status":"completed",
 "subagent_phase":"completed","subagent_type":"explore",
 "parent_tool_call_id":"tool_GgLCPjSW9Vo2oN0x5SOrB0x9","run_in_background":false,
 "created_at":"2026-08-01T14:50:09.024Z","started_at":"2026-08-01T14:50:09.026Z",
 "completed_at":"2026-08-01T14:52:38.040Z","output_preview":"## Summary\n\n**Task received:** …"}
```

- `subagent_phase`: `queued` | `working` | `suspended` | `completed` |
  `failed` | `cancelled` — **`cancelled` observed live since 2.0.0**
  (verified live 2026-09-17: a REST `:cancel` of a running foreground
  sub-agent leaves the roster entry reading
  `"status":"cancelled","subagent_phase":"cancelled"`; REST `/tasks/{id}`
  shows `status:"cancelled"`; was schema-level only before — the same truth
  as `task.terminated` with `status:"killed"`); `suspended_reason`
  and `swarm_index` optional. The flatter
  `status` mirrors it with the `/tasks` vocabulary (`working` → `running`
  there); `subagent_phase` is the precise one.
- Background sub-agents are excluded (REST serves them). The roster survives
  the turn's end but is dropped when the main agent starts its **next** turn
  (verified: `[]` after a follow-up prompt).

## WebSocket

### URL & handshake

```
ws://127.0.0.1:58627/api/v1/ws?client_id=<unique client id>
```

(`wss://` when the base is https; remotely, the handshake goes through Caddy
and needs the same Bearer token header as REST.)

websocat (installed at `~/tools/websocat-bin/websocat`; note websocat 1.14's
`-H` eats multiple arguments — pass the header as `-H="Authorization: Bearer <token>"`
or put it last):

```bash
~/tools/websocat-bin/websocat -H="Authorization: Bearer <token>" \
  'ws://127.0.0.1:58627/api/v1/ws?client_id=doc_verify_250'
```

First server frame is `server_hello`; 2.5.0 dropped
`max_event_buffer_size` (the old `1000`) but kept the rest (verbatim, 2.5.0):

```json
{"type":"server_hello","timestamp":"2026-11-04T19:24:58.463Z",
 "payload":{"ws_connection_id":"conn_01M6M0B36YD4JZXR37DH7HR7MJ",
            "protocol_version":2,"heartbeat_ms":10000,
            "capabilities":{"event_batching":false,"compression":false}}}
```

There is no `client_hello` requirement: `client_hello` is still accepted and
acknowledged (`{"type":"ack","id":"…","code":0,"msg":"success","payload":{}}`),
but a connection can go straight to `subscribe_v2` (verified live). Global
events arrive even on a socket that sent nothing but `pong`.

### Heartbeat

The heartbeat is unchanged: `heartbeat_ms` is **10000**, the server sends an
app-level `ping` every interval with a fresh ULID `nonce`, and a client answers
`{"type":"pong","payload":{"nonce":<echo>}}`. **Any inbound frame resets the
liveness timer.** A silent connection gets a ping at +10 s and is closed at
+20 s (the server's miss limit is 2; observed live as one ping then no further
pings at +20/+30 — websocat does not surface the close frame). The Android app
must keep answering pings.

### Control frames

Client frames are JSON `{ "type", "id"?, "payload" }`; every non-task request
gets `{"type":"ack","id","code","msg","payload"}` (`code:0` = success). The
only session subscription is `subscribe_v2`; **the old
`{"type":"subscribe","payload":{"session_ids":[…]},"cursors":…}` frame is
silently ignored — no ack, no error, no events.**

| Frame | payload | Description |
| --- | --- | --- |
| `subscribe_v2` | `{ session_id, transcript, transcript_since? }` | The only session subscription: transcript streams + interactions for ONE session |
| `unsubscribe_v2` | `{ session_id, agent_ids? }` | Detach; omitting `agent_ids`, or detaching the last agent, drops the whole session subscription |
| `client_hello` | `{ client_id }` | Optional handshake |
| `task.watch` | flat: `{ request_id, session_id, agent_id, task_id, since_offset?, tail_bytes? }` | Stream one task's output; works without a session subscription |
| `task.unwatch` | flat: same minus offsets | Stop streaming that task's output |
| `task.stop` | flat: same | Stop a task; an ended task just replies with its state |
| `task.background` | flat: same | Detach the waiting tool call; a foreground-only task fails `40933` |

`subscribe_v2`'s `transcript` is a per-agent grade map:
`{"*": "off" | "turn" | "block" | "delta"}` (an exact agent id may override
`"*"`). `transcript_since` is the matching map of op-batch `seq` values to
resume from. The ack (verbatim) is:

```json
{"type":"ack","id":"c1","code":0,"msg":"success",
 "payload":{"accepted":["session_150fe8fe-…"],"not_found":[]}}
```

An unknown session answers `code:0` with the id in `not_found`; a malformed
payload answers `code:1` `"invalid subscribe_v2 payload"` (not `40001`). There
is no multi-session subscribe — send one frame per session. `unsubscribe_v2`
acks with the session id in `accepted` (verbatim:
`{"type":"ack","id":"u1","code":0,"msg":"success","payload":{"accepted":["session_…"],"not_found":[]}}`).

Task frames are **flat** — their fields sit next to `type` instead of under
`payload`, `request_id` replaces `id`, and the reply is
`{ "type": "response", "request_id", "code", "msg"?, "task"? }` instead of an
ack. A frame without a readable `request_id` closes the socket with code 1008.
Requests for the same task are handled in arrival order; different tasks never
wait on each other. Tasks watched are dropped on disconnect.

### Session frames

After an accepted `subscribe_v2` the server sends, in this order (verified
live): per-agent state frames, `transcript.reset`, then the ack. For a fresh
subscription with grade `delta` on a session with an existing turn:

```
task.list              all running tasks of the agent (empty when idle)
agent                  status/model/thinking/permission/modes
usage                  context fill
goal                   goal or null
todo                   full todo list
transcript.reset       baseline snapshot (items: [])
ack
```

All state frames carry `{type, session_id, agent_id, event_created_at, …}` and
replace the client copy wholesale. They are never replayed; a reconnect
re-sends them. If the subscribe's grade is `off`, the agent is not "received"
and only the ack (plus pending interactions, if any) arrives.

**`agent`** — `status` (`idle`/`running`), optional `model`, optional
`thinking`, `permission_mode` (`manual`/`yolo`/`auto`), and the booleans
`plan_mode`/`swarm_mode`/`tower_mode`. Verbatim:

```json
{"type":"agent","session_id":"session_…","agent_id":"main",
 "event_created_at":"2026-11-04T19:25:52.678Z","status":"idle",
 "model":"deepseek/deepseek-v4-flash","thinking":"max","permission_mode":"auto",
 "plan_mode":false,"swarm_mode":false,"tower_mode":false}
```

The frame for a turn's start or end follows the transcript ops of that turn
change. A fresh model-less session omits `model`/`thinking`; `status` is
`running` while a turn or background work keeps the agent busy.

**`usage`** — `context_tokens` (required) and `max_context_tokens`
(optional), coalesced to at most a few frames per second:

```json
{"type":"usage","session_id":"session_…","agent_id":"main",
 "event_created_at":"2026-11-04T19:25:52.679Z","context_tokens":22433,
 "max_context_tokens":1000000}
```

**`goal`** — the main agent's goal, or the key `goal` omitted when none.
Shape: `{objective, status:"active"|"paused"|"blocked"|"complete",
completion_criterion?, turns_used, tokens_used, wall_clock_ms}`. Verbatim
empty frame:

```json
{"type":"goal","session_id":"session_…","agent_id":"main","event_created_at":"2026-11-04T19:25:52.679Z"}
```

**`todo`** — the full list, `items:[{title,status}]` with status
`pending`/`in_progress`/`done`; `[]` clears it. See Todos.

**`task.list` / `task`** — see Sub-agents → Task frames and controls.
Verbatim:

```json
{"type":"task.list","session_id":"session_…","agent_id":"main","tasks":[]}
{"type":"task","session_id":"session_…","agent_id":"main","event_created_at":"…",
 "task_id":"bash-s9yny87s","kind":"shell","status":"running","detached":false,
 "description":"Bash: sleep 30 && echo watched-ok",
 "parent_tool_call_id":"call_00_…","command":"sleep 30 && echo watched-ok",
 "started_at":"…"}
```

**`task.output`** — only to a `task.watch`ing connection:
`{type, session_id, agent_id, task_id, offset, text}`, `offset` a UTF-8 byte
offset. The first chunk is the tail (32 KiB default) after `since_offset`.

**`interaction`** — the flat approval/question frame (unchanged from 2.4.0):
no `seq`/`timestamp`/`payload` wrapper, never journaled, delivered only to
subscribed connections. Pending interactions are re-sent **twice** after the
ack — once inside `transcript.reset.snapshot.interactions`, once as standalone
frames. Verbatim pending approval:

```json
{"type":"interaction","session_id":"session_…","agent_id":"main",
 "event_created_at":"2026-11-04T19:27:26.728Z",
 "interaction_id":"approval_6d1ef26e-b18f-46e3-9d1e-1374d2b9febf",
 "status":"pending","tool_call_id":"call_00_GpDmwYxmNPexNQPjpMHl3844",
 "kind":"approval",
 "request":{"tool_name":"Bash","action":"Running: rm -rf /path/to/project/target",
            "tool_input_display":{"kind":"command",
                                  "command":"rm -rf /path/to/project/target",
                                  "cwd":"/path/to/project","language":"bash"},
            "expires_at":"2026-11-05T19:27:26.727Z"}}
```

After `{"kind":"approval","decision":"approved"}`:

```json
{"type":"interaction","session_id":"session_…","agent_id":"main",
 "event_created_at":"2026-11-04T19:27:27.526Z",
 "interaction_id":"approval_6d1ef26e-b18f-46e3-9d1e-1374d2b9febf",
 "status":"ended","outcome":"approved","ended_at":"2026-11-04T19:27:27.526Z",
 "tool_call_id":"call_00_GpDmwYxmNPexNQPjpMHl3844","kind":"approval",
 "request":{…},"response":{"decision":"approved"}}
```

The transcript also carries an `interaction.upsert` op with the full entity
(see transcript protocol); `GET .../transcript` returns `interactions` +
`pending_interactions`.

### Transcript protocol

`subscribe_v2`'s `transcript` grades select how much detail the agent's
transcript stream carries (verified live on one short turn):

- **`off`** — no transcript and no per-agent state frames; only interactions.
- **`turn`** — prompt/turn/marker changes only (no steps or frames). The op
  `seq` may skip numbers when a lower grade filters a batch.
- **`block`** — adds `step.upsert` and `frame.upsert` with full text values,
  no `append` deltas.
- **`delta`** — adds `append` chunks for streaming text/thinking.

A non-`off` agent receives two frame types:

**`transcript.reset`** — the baseline at subscribe. Verbatim (fresh session
with completed turns and a pending question; `items` is always empty by
design — history pages in over REST):

```json
{"type":"transcript.reset","session_id":"session_…","timestamp":"…",
 "payload":{"type":"transcript.reset","agent_id":"main",
  "snapshot":{"items":[],"interactions":[{"interactionId":"question_…",
              "interactionKind":"question","toolCallId":"call_…",
              "state":"pending","request":{…}}],
              "attachments":[],
              "prompts":[{"promptId":"msg_…","status":"running",
                          "userMessageId":"msg_…","content":[{"type":"text","text":"…"}],
                          "createdAt":"…"}],
              "hasMoreOlder":true},
  "has_more_older":true,"seq":1}}
```

- `snapshot.prompts` are the prompt entities (all statuses, `steeredAt` when
  steered) — the cold-open replacement for the old prompt-queue bookkeeping.
- `snapshot.interactions` are the pending interactions in full entity form
  (see Interactions); `pending_interactions` on the REST transcript is the
  id-only view.
- `seq` is the agent's op-batch watermark; pass it back as
  `transcript_since:{"main":<seq>}` to resume.
- `hasMoreOlder`/`has_more_older` says older turns exist in REST history.

**`transcript.ops`** — one incremental batch: `{type, session_id, timestamp,
payload:{type:"transcript.ops", agent_id, ops:[…], seq}}`. `seq` is strictly
increasing per agent (gaps are possible when a grade filters a batch). The op
union (schema from `packages/transcript`; all but `items.remove`/
`attachment.upsert` observed live):

| Op | Payload |
| --- | --- |
| `prompt.upsert` | `{prompt:{promptId,status,userMessageId,content,createdAt,finishedAt?,steeredAt?}}` |
| `turn.upsert` | `{turn:{kind:"turn",turnId,ordinal,state,origin,prompt?,startedAt?,endedAt?,durationMs?,usage?,triggerPromptId?,error?}}` (no `steps` in the live header; `state` includes `failed` with an `error` message) |
| `step.upsert` | `{turnId, step:{kind:"step",stepId,turnId,ordinal,state,startedAt?,endedAt?,usage?,finishReason?,llmTiming?,retry?,endReason?,endMessage?}}` (frames ride `frame.upsert`; `state:"interrupted"` + `endReason:"error"` is a failed provider call, `endReason:"aborted"` an abort, `endReason:"steered"` a steer cut — 2.5.3) |
| `frame.upsert` | `{turnId, stepId, frame:{kind:"text"|"thinking"|"tool"|"notice",frameId,…}}` |
| `append` | `{target:{type:"frame",turnId,stepId,frameId}, offset, text}` |
| `marker.upsert` | `{item:{kind:"marker",markerId,marker,payload?,at?}, beforeTurn?}` |
| `interaction.upsert` | `{interaction:{interactionId,interactionKind,toolCallId?,state,request?,response?}}` |
| `reset` | `{agentId, snapshot:{items:[…],interactions,attachments,prompts,…}}` — whole-agent transcript replacement, emitted at a turn end when the cold history omits live steps/frames (2.5.3; see below) |
| `attachment.upsert` | `{attachment:{…}}` |
| `items.remove` | `{ids:[…]}` |

Frame kinds (schema in `packages/transcript/src/contract/schema.ts`):
`text` (`role:"assistant"|"user"`, `text`, optional `origin`, `promptIds`,
`attachmentIds`, `taskId`), `thinking` (`text`), `tool` (`toolCallId`, `name`,
`state:"running"|"done"|"error"`, optional `input`, `output`, `inputText`,
`display`, `progress`, `taskId`, `approvalId`, `agentRefs`), and `notice`
(`level:"error"|"warning"|"info"`, `message`, optional `source`/`detail`; not
observed live). Assistant text/tool frames are the transcript's own content;
the tool `display` object is the same discriminated object the old
`tool.call.started` carried (`kind:"command"`, `kind:"agent_call"`,
`kind:"todo_list"`, plan display, …).

Verbatim `transcript.ops` from a one-turn round (grade `delta`; a fresh
session, so the turn id is `t1` because an earlier turn exists in that
session — ids are strings and opaque):

```json
{"type":"transcript.ops","session_id":"session_…","timestamp":"…",
 "payload":{"type":"transcript.ops","agent_id":"main","ops":[
   {"op":"prompt.upsert","prompt":{"promptId":"msg_…","status":"running",
    "userMessageId":"msg_…","content":[{"type":"text","text":"Reply with exactly: delta-probe"}],
    "createdAt":"…"}}],"seq":19}}
{"type":"transcript.ops","session_id":"session_…","timestamp":"…",
 "payload":{"type":"transcript.ops","agent_id":"main","ops":[
   {"op":"turn.upsert","turn":{"kind":"turn","turnId":"t1",
    "triggerPromptId":"msg_…","ordinal":1,"state":"running",
    "origin":{"kind":"user","payload":{"kind":"user"}},
    "prompt":"Reply with exactly: delta-probe","startedAt":"…"}}],"seq":20}}
{"type":"transcript.ops","session_id":"session_…","timestamp":"…",
 "payload":{"type":"transcript.ops","agent_id":"main","ops":[
   {"op":"marker.upsert","item":{"kind":"marker","markerId":"live-m4",
    "marker":"undo","payload":{"agentId":"main","start":4,"deleteCount":0,
     "messages":[{"role":"user","content":[{"type":"text","text":"Reply with exactly: delta-probe"}],
                  "id":"msg_…","origin":{"kind":"user"}}]},"at":"…"}}],"seq":21}}
{"type":"transcript.ops",…,"ops":[
   {"op":"step.upsert","turnId":"t1","step":{"kind":"step","stepId":"t1.1",
    "turnId":"t1","ordinal":1,"state":"running","startedAt":"…"}}],"seq":23}
{"type":"transcript.ops",…,"ops":[
   {"op":"frame.upsert","turnId":"t1","stepId":"t1.1",
    "frame":{"kind":"text","frameId":"t1.1.f2","role":"assistant","text":""}},
   {"op":"append","target":{"type":"frame","turnId":"t1","stepId":"t1.1",
    "frameId":"t1.1.f2"},"offset":0,"text":"delta"}],"seq":25}
{"type":"transcript.ops",…,"ops":[
   {"op":"append","target":{"type":"frame","turnId":"t1","stepId":"t1.1",
    "frameId":"t1.1.f2"},"offset":5,"text":"-pro"}],"seq":26}
{"type":"transcript.ops",…,"ops":[
   {"op":"frame.upsert","turnId":"t1","stepId":"t1.1",
    "frame":{"kind":"text","frameId":"t1.1.f2","role":"assistant","text":"delta-probe"}},
   {"op":"step.upsert","turnId":"t1","step":{"kind":"step","stepId":"t1.1",
    "turnId":"t1","ordinal":1,"state":"completed","startedAt":"…","endedAt":"…",
    "usage":{"inputOther":172,"output":4,"inputCacheRead":22272,"inputCacheCreation":0},
    "finishReason":"end_turn","llmTiming":{"llmFirstTokenLatencyMs":527,"llmStreamDurationMs":951}}}],
  "seq":28}
{"type":"transcript.ops",…,"ops":[
   {"op":"turn.upsert","turn":{"kind":"turn","turnId":"t1","ordinal":1,
    "state":"completed","triggerPromptId":"msg_…",
    "origin":{"kind":"user","payload":{"kind":"user"}},"prompt":"…",
    "startedAt":"…","endedAt":"…","durationMs":1545,
    "usage":{"inputTokens":172,"cachedTokens":22272,"outputTokens":4}}}],"seq":29}
{"type":"transcript.ops",…,"ops":[
   {"op":"prompt.upsert","prompt":{"promptId":"msg_…","status":"completed",
    "userMessageId":"msg_…","content":[…],"createdAt":"…","finishedAt":"…"}}],
  "seq":30}
```

- A `text` frame can be emitted twice by the engine (two `text` frames with
  the same text in one step were observed in the REST transcript); treat
  frame ids as the identity.
- `append` offsets are character offsets in the accumulated text. A duplicate
  (below local length) is a duplicate frame; a gap needs a REST refresh.
- `step.upsert` carries `usage`, `finishReason` and `llmTiming` at
  completion; `turn.upsert` carries `usage`/`durationMs`.
- Tool calls are `tool` frames: `state:"running"` with `inputText` growing
  token-by-token during the call, then `state:"done"` (or `"error"`) with
  `input`, `display` and `output`. There is no `tool.progress` frame; the
  running frame's `progress` object is the schema-level substitute.

**2.5.3 `reset` op.** `{"op":"reset","agentId":"main","snapshot":{…}}`
replaces the agent's whole transcript (the same snapshot shape as
`transcript.reset`, but with `items` populated: full turns, each with its
steps and frames). The server emits it at a turn end when the live
transcript's steps or frames are not a superset of the cold-folded history —
in this run every completed turn (plain, steered, question, approval, and
each child agent's single turn) produced one just before the usual heal
ops. Verbatim (trimmed; the same batch's
later ops are ordinary `turn.upsert`/`frame.upsert` heals):

```json
{"op":"reset","agentId":"main","snapshot":{"items":[
  {"kind":"turn","turnId":"t0","ordinal":0,"state":"completed","prompt":"…",
   "startedAt":"…","endedAt":"…","steps":[
     {"kind":"step","stepId":"t0.1","ordinal":1,"state":"completed",
      "frames":[{"kind":"text","frameId":"t0.1.f1","role":"assistant",
                 "text":"I'll run the command as specified."},…]},…]},…],
  "interactions":[],"attachments":[],"prompts":[…],"hasMoreOlder":false}}
```

A client that keeps its own live transcript should apply it wholesale, then
fold the batch's remaining ops; a client that refetches history at turn end
can ignore it (the Android app does, safely: its turn state is already
terminal, so the later `frame.upsert`s are dropped too).

**Resume.** `subscribe_v2` with
`transcript_since:{"*":30}` replays batches with `seq > 30` before the ack
(verified live: seq 31–36 in order, ack last). A `transcript_since` above the
journal's latest (e.g. 9999) produces no reset and no ops, only the ack — a
future cursor is not an error. When the journal cannot cover the gap, the
REST counterpart `GET .../transcript/ops?agent_id=…&since_seq=…` returns
`complete:false`; the client then does a full `GET .../transcript` refresh.
Omitted `transcript_since` always yields a fresh `transcript.reset`.

**Reconnect.** Subscriptions do not survive a disconnect. Re-subscribe each
session; the server re-sends `task.list`, `agent`, `usage`, `goal`, `todo`,
a fresh `transcript.reset` (or the replay ops), and the pending interactions.
Global events missed while disconnected are not replayed; refresh the lists
you keep over REST. Task watches are dropped — send `task.watch` again with
`since_offset` set to the end of the output you already have.

**The legacy event family is not coming back.** There are no `turn.*`,
`assistant.delta`, `thinking.delta`, `tool.*`, `context.spliced`,
`prompt.*`, `subagent.*`, `task.started`/`terminated`, `compaction.*`,
`event.approval.*`, `event.question.*`, `permission.approval.*`,
`agent.status.updated`, `agent.created` or `agent.disposed` frames on 2.5.0.

### Global events

Global events are sent to every established connection, with or without any
subscription or `client_hello` (verified live: a silent socket received
`session.meta.updated` and `event.session.work_changed` while a session it
never subscribed to ran a turn). The envelope is

```json
{"type":"<event type>","session_id":"<id or __global__>","timestamp":"…","payload":{…}}
```

— **no `seq`, no `epoch`, no `volatile`**. `session_id` is `__global__` for
most events, the created session's id for `event.session.created`, and the
session's own id for `session.meta.updated`/`event.session.work_changed`.

The delivered types are: `session.meta.updated`,
`event.session.work_changed`, `event.session.created`, `event.session.archived`,
`event.session.deleted`, `event.workspace.created`/`updated`/`deleted`,
`event.config.changed`, `event.config.warning`,
`event.model_catalog.changed`, `event.di.unit_changed`,
`event.plugin.changed` and `event.capability.changed` (the last four are
schema-level; not triggered this run). **`session.meta.updated` and
`event.session.work_changed` are no longer per-subscription** — they are the
app-level pushes the session list consumes.

Verbatim global frames from 2.5.0:

```json
{"type":"session.meta.updated","session_id":"session_…","timestamp":"…",
 "payload":{"type":"session.meta.updated","title":"Reply with exactly: probe",
  "patch":{"title":"Reply with exactly: probe","isCustomTitle":false,
           "lastPrompt":"Reply with exactly: probe"},
  "agentId":"main","sessionId":"session_…"}}
{"type":"event.session.work_changed","session_id":"session_…","timestamp":"…",
 "payload":{"type":"event.session.work_changed","busy":true,
  "main_turn_active":true,"pending_interaction":"none",
  "agentId":"main","sessionId":"session_…"}}
{"type":"event.session.created","session_id":"session_created_…","timestamp":"…",
 "payload":{"type":"event.session.created",
  "session":{"id":"session_created_…","workspace_id":"wd_…","title":"…",
             "created_at":"…","updated_at":"…","busy":false,
             "main_turn_active":false,"pending_interaction":"none",
             "archived":false,"metadata":{"cwd":"/path/to/project"},
             "agent_config":{"model":""},"usage":{…},
             "permission_rules":[],"message_count":0},
  "agentId":"main","sessionId":"session_created_…"}}
{"type":"event.session.archived","session_id":"__global__","timestamp":"…",
 "payload":{"type":"event.session.archived","workspace_id":"wd_…",
  "agentId":"main","sessionId":"session_…"}}
{"type":"event.session.deleted","session_id":"__global__","timestamp":"…",
 "payload":{"type":"event.session.deleted","workspace_id":"wd_…",
  "agentId":"main","sessionId":"session_…"}}
{"type":"event.workspace.updated","session_id":"__global__","timestamp":"…",
 "payload":{"type":"event.workspace.updated",
  "workspace":{"id":"wd_…","root":"/path/to/project","name":"project",
               "kind":"implicit","additional_dirs":[],
               "created_at":"…","last_opened_at":"…","session_count":93},
  "agentId":"main","sessionId":"__global__"}}
```

- **Create** (`POST /api/v1/sessions`) broadcasts `event.session.created` on
  the new session's own envelope id, carrying the full snake_case session
  object. **2.5.0: `:fork` now broadcasts it too** — a fork made by another
  client appears live in the session list (the old server dropped the fork's
  frame because the session was not loaded).
- **Archive** broadcasts `event.session.archived`; **restore still fires
  nothing**.
- **Delete** broadcasts `event.session.deleted`; the row leaves the list.
- **Session creation also fires `event.workspace.updated`** for the target
  workspace (observed live on 2.5.0; new to this run's captures).
- `event.config.changed` / `event.model_catalog.changed` keep their old
  payloads: `{type:"event.config.changed", changedFields:[…], config:{…}}`
  (`changedFields` camelCase domain names, credentials redacted to
  `has_api_key`), and the model event may carry `retired_default`. Only the
  envelope lost `seq`/`epoch`. `event.config.warning` keeps its
  `{type, warnings:[{domain?,message}]}` payload; an empty array clears the
  warning set.
- `event.workspace.created` / `updated` carry the full workspace object
  (`{id,root,name,kind,additional_dirs,created_at,last_opened_at,
  session_count}`); `event.workspace.deleted` carries
  `{workspace_id,root}`. `updated` also fires when a session creation
  touches the workspace (observed live on 2.5.0).
- `event.di.unit_changed`, `event.plugin.changed` and
  `event.capability.changed` are in the server's schema but were not
  triggered this run.

### Captured round-trips (2.5.0)

A plain prompt on a grade-`delta` subscription (session with one earlier
turn) produced, in order: `session.meta.updated` (global) → `prompt.upsert`
(running) → `turn.upsert` (running) → `marker.upsert` (undo splice of the
user message) → `prompt.upsert` (running again) → `agent` (running) →
`event.session.work_changed` (busy) → `step.upsert` (running) →
`frame.upsert`+`append` (thinking, then assistant text) → `step.upsert`
(completed, with usage/finishReason/llmTiming) → `turn.upsert` (completed) →
`agent` (idle) → `prompt.upsert` (completed) → `event.session.work_changed`
(idle, `last_turn_reason:"completed"`) → `usage`. The exact op batches are in
Transcript protocol above; `prompt.completed`/`turn.ended` do not exist.

**Steer** (prompt A running, prompt B queued, then `:steer` B): B's
`prompt.upsert` is `queued` (twice) and then flips to `completed` with
`steeredAt`; A's entity gains `steeredAt`; the steered text is a user-role
text frame with `promptIds` in A's running turn; A's turn later completes
normally. The queued prompt snapshot is also available at any time from
`GET .../prompts` (`{active, queued}`).

**Abort** (mid-turn `:abort`): the running step's `step.upsert` is
`state:"interrupted"`, the turn's `turn.upsert` is `state:"cancelled"`, the
prompt's `prompt.upsert` is `status:"aborted"`, an `undo` marker carries the
interruption reminder (`origin:{"kind":"injection","variant":"interruption"}`),
a killed shell task sends `task` `status:"ended"`/`outcome:"killed"`, the
`agent` frame goes idle, and `event.session.work_changed` reports
`busy:false`, `last_turn_reason:"cancelled"`. Idle `:abort` still answers
`{"aborted":true}`.

**Approval** (manual Bash): the tool `frame.upsert` reaches `state:"running"`;
`interaction` pending + `interaction.upsert`; after the POST, `interaction`
ended (`outcome:"approved"`) + `interaction.upsert` (`state:"approved"`); the
tool frame gains `approvalId`, then `task` shell running/ended, then the tool
frame `state:"done"` with `output`. No `permission.approval.*` frames.

**Question** (manual `AskUserQuestion`): tool frame running; `interaction`
pending + `interaction.upsert`; after the POST, `interaction` ended
(`outcome:"answered"`, `response.answers` keyed by question text) +
`interaction.upsert` (`state:"answered"`); the tool frame reaches
`state:"done"` with `output` `{"answers":{"Cake or pie?":"Pie"}}`; the turn
continues.

**Compaction**: see Compact → WebSocket sequence (2.5.0).

**Sub-agents**: a foreground and a `run_in_background:true` spawn produced
two `task` frames (`kind:"subagent"`, `detached:false`/`true`,
`child_agent_id:"agent-0"`/`"agent-1"`), each child announced itself with
`transcript.reset` (`agent_id:"agent-0"`, `seq:0`) and `agent`; completion was
the ended `task` frame with `outcome:"completed"`. No `subagent.*` frames.

### The v3 protocol (added 0.43.0, **removed in 2.0.2** — never used by the app)

**Removed in 2.0.2** (commit `2502d215`, a revert of the 0.43.0 addition;
verified live 2026-09-19: `WS /api/v3/ws` refuses the handshake and
`GET /api/v1/sessions/{id}/history` answers a plain Fastify 404 — the v2
endpoint this document describes is byte-untouched). The notes below are
kept for history only.

0.43.0 (commit `64505e36`) adds a **second, opt-in wire protocol** next to
the v2 one this document describes. The client picks the protocol by
picking the URL — there is no version negotiation, and **the v2 endpoint
is byte-for-byte untouched** (`transport/ws/v1/*` identical between the
tags; `server_hello` still numeric `protocol_version: 2`, subscribe/ack/
cursor-resume re-verified live 2026-09-14). The Android app uses none of
this; it is documented here once as a potential future migration target
(its flat entities match the app's consumption style better than v2's
nested events). Verified live 2026-09-14.

**WS endpoint — `WS /api/v3/ws`** (same bearer-token subprotocol auth as
v2). On connect the server immediately sends a `hello` frame (note the
frame type and the **string** protocol version):

```json
{"type":"hello","protocol_version":"3","server_id":"01M2GP2YK8BQKH1QAEPCRWG0KJ","capabilities":["step_replay_v1"]}
```

Client → server frames are only `subscribe` / `unsubscribe`:

```json
{"type":"subscribe","id":1,"session_id":"...","agent_ids":["..."],"omit":["thinking.delta"]}
{"type":"unsubscribe","id":2,"session_id":"..."}
```

**No `since_seq` / cursor** — catch-up is done over REST (`after_step`,
below), not over the socket. Server control frames:
`{"type":"ack","id":1,"code":0,"msg?":"..."}` and
`{"type":"error","code":N,"msg":"..."}`; new error code
`WS_SLOW_CONSUMER: 42903` (closes with 1008 on outbound overflow).
Heartbeat is WS-level ping/pong (10 s interval, terminated after 2
misses). On subscribe the server sends `ack`, then **recovery messages**
(a replay of the session's current projection state), then live traffic.

Server → client traffic is **flat entity messages** — no envelope; each
carries `session_id` / `agent_id` / `timestamp` (epoch ms) plus its own
fields. Families: `turn`, `step`, `user`, `assistant` (+ `assistant.delta`),
`thinking` (+ `thinking.delta`), `tool_call` (+ `tool_call.delta` +
`tool.progress`), `system` (compaction/undo/clear/plan/swarm/hook/
interruption markers), `interaction` (approvals and questions in one
shape, with `status` lifecycle), `task`, `todo`, `agent.state`,
`session.state`, and session-less globals (`session`/`workspace`/`config`/
`config.warning`/`model_catalog`/`plugin`/`capability`).

**History route — `GET /api/v1/sessions/{id}/history`** (mounted under
the existing v1 prefix, standard envelope) serves the same flat entities
cold-rebuilt from persisted wire records. Query params: `before_turn`
(page older) / `after_step` (catch up newer; mutually exclusive),
`page_size` (default 200, max 500), `agent_id` (defaults to the main
agent). Response data:

```json
{"messages":[<entity messages>],"has_more":false,"in_flight":{"turn_id":"...","step_id":"..."}}
```

(`in_flight` present only while a turn is running; observed live 2026-09-14
on the throwaway session: `has_more: true`, 37 entity messages on the
first page, entities `turn`/`user`/`step`/… with `turn_id: "t7"`,
`step_id: "t7.1"` string ids.) Errors: schema-validation `40001`s, unknown
session `40401`. The loopback-only debug xstate stream
`WS /api/v1/debug/ws` (`6299cfda`) is **not registered on this
deployment** (verified live — handshake refused).

## Verification log

- 2026-11-10 re-verification on `2.5.3` (server 2.5.2 → 2.5.3; throwaway
  sessions created with `metadata.cwd=/home/ubuntu/spike` and deleted after
  probing; the nightly job's `pending-changelog.md` covered 2.5.2→2.5.3
  (5 commits), reviewed via the GitHub API — `e1912c80` adds the direct
  steer route and the interrupt execution model, `1b5359d0` adds the
  transcript `reset` op, `c50a55bf` is Windows-only, `e3c3a120` the web-dist
  sync and `5af9942e` the release; live probes over REST + websocat against
  `127.0.0.1:58627`, server started `2026-11-10T19:23:55.224Z`). Drift
  found: the new `POST /api/v1/sessions/{id}/steer` route (idle = prompt
  POST, busy = queue+steer in one request, ACK `status:"running"` even when
  queued), steer now interrupting the active LLM step with
  `endReason:"steered"` and continuing in the same turn, append-only steer
  history in `GET .../messages`, the `reset` op in `transcript.ops` at turn
  ends with divergent live steps, and the `steerIfActive` relaxation to any
  active turn. Re-verified unchanged: auth 401s (missing + wrong token),
  `/meta` (5 flags, 17 features, same capabilities), config
  (`deepseek/deepseek-v4-flash`, effort `max`, permission `auto`, 3
  providers), models (12), tools (36 = 27 builtins + 9 MCP), session
  create/get/list (`page_size` + `has_more`), the v2 table (`view=
  by_workspace` groups + total), profile, fork with `turn_index`,
  reload/takeover/archive/restore, goal/warnings, messages (newest-first;
  the image prompt rewrite to `session_media` + `name` in the ACK and
  history), the full DeepSeek prompt round-trip (`prompt.upsert` →
  `turn.upsert` → undo markers → `step.upsert` → `frame.upsert`/`append` →
  completed step/turn/prompt; synchronous Bash `task` running/ended), the
  queued `prompts:steer` flow (steered while a Bash call ran: applied at the
  tool boundary, user frame in a new step of the same turn), direct
  `/steer` on an idle session, direct `/steer` mid-essay-stream (step
  `interrupted` `endReason:"steered"`, new step in the same turn), abort
  (interrupted step `endReason:"aborted"`, cancelled turn, `interruption`
  marker + undo reminder, aborted prompt, killed shell task; idle
  `{"aborted":true}`), both compaction bodies (`{}` and an instruction
  echoed in the `started` marker; `tokensAfter` matched the following
  `usage` frame — 24223 and 24308; both `40910`s with `stack`), questions
  (pending/ended `interaction` frames, `interaction.upsert`, answer via
  `answers.{"q_0":{"kind":"single","option_id":…}}` → `answered`, re-answer
  `40902`, dismiss → `dismissed`, unknown `40404`, `GET .../questions`
  404), approvals (manual `rm -rf`, pending/ended `interaction` frames,
  `approved`, re-answer `40902`), foreground and background sub-agents
  (`agent-0`/`agent-1` `transcript.reset` + `agent` frames, `task` frames
  with `kind:"subagent"`, `child_agent_id`, `detached` true/false; REST
  `/tasks` 404), the `TodoList` write contract and the read-only `input:{}`
  query (`display:{"kind":"todo_list"}`, `Current todo list:` output),
  files (upload with sniffed `media_type:"image/png"`, download 200, `Range`
  206, delete → `{"deleted":true}` then GET 404/`40407`, session media
  survives), WS hello (`client_hello` ack)/`subscribe_v2`/ack, unknown
  session `not_found`, malformed `code:1`, `transcript_since` resume (the
  reset op and heal ops replayed from seq 37), `unsubscribe_v2`,
  `task.watch`/`task.output`/`task.stop`/`task.unwatch`, `/status` and
  `/snapshot` and `/tasks` 404, `/transcript` (same key set),
  `/transcript/ops`, `/transcript/user-messages`, `/transcript/plan`, search
  (`50001`, disabled) and rating (`{"appearance":null}` on `turn_id:"t0"`).
  The managed `kimi-code/k3` alias still fails ~1.7 s after the POST with
  `provider.auth_error` (step `endReason:"error"` + `endMessage`, turn
  `failed` with the subscription message, a `marker:"notice"` error item);
  the checks otherwise used `deepseek/deepseek-v4-flash`. **App impact: none
  breaks; one improvement ticket (185 — direct steer route).** Unit tests:
  `:app:testDebugUnitTest` — 1083 tests, 0 failures, 1 skipped. The first
  full run flaked once on `SessionListViewModelTest.show more pages five
  sessions at a time` (`TimeoutCancellationException`, unrelated to the wire
  shapes); that class passed in isolation and the full suite passed on
  rerun.

- 2026-11-06 re-verification on `2.5.2` (server 2.5.1 → 2.5.2; throwaway
  sessions created with `metadata.cwd=/home/ubuntu/spike` and deleted after
  probing; the nightly job's `pending-changelog.md` covered 2.5.1→2.5.2
  (3 commits), reviewed via the GitHub API — the only code commit `7b906ea3`
  touches `packages/agent-core-v2` (MCP service, tool registry, tool-select,
  tool contract), `ef10107d` is the web-dist sync and `1c142a00` the release
  commit; no `packages/kap-server` or protocol-definition change; live probes
  over REST + websocat against `127.0.0.1:58627`, server started
  `2026-11-06T19:23:39.783Z`). Drift found: **none on the wire** — every
  endpoint and frame shape below is byte-identical to 2.5.1. The release's
  one engine behavior change (same-name tool updates pinned for a running
  session) adds only the source-derived `tool_update_pending` injection
  variant, which was not triggered here. Re-verified unchanged: auth 401s
  (missing + wrong token), `/meta` (5 flags, 17 features, same capabilities),
  config (`deepseek/deepseek-v4-flash`, effort `max`, permission `auto`,
  3 providers), models (12), tools (36 = 27 builtins + 9 MCP), session
  create/get/list/profile, the v2 session table (flat, grouped,
  `fields=id,archived`), fork with `turn_index` (broadcasts
  `event.session.created`), archive/restore, goal/warnings, messages
  (newest-first; the image prompt rewrite to `session_media` + `name` in the
  ACK and history), a full DeepSeek prompt round-trip (prompt.upsert →
  turn.upsert → undo marker → step.upsert → frame.upsert/append → completed
  step/turn/prompt; the synchronous Bash call emitted `task` running/ended
  frames), steer (queued → completed with `steeredAt`, user text frame with
  `promptIds` in the same turn), abort (interrupted step with
  `endReason:"aborted"`, cancelled turn, interruption marker, aborted prompt;
  idle `:abort` → `{"aborted":true}`), both compaction bodies (`{}` and an
  instruction echo; both `40910`s with `stack`; `tokensAfter` matched the
  following `usage` frame and the compaction summary message), questions
  (pending/ended `interaction` frames, `interaction.upsert`, answer →
  `outcome:"answered"`, re-answer `40902`, dismiss → `outcome:"dismissed"`,
  unknown id `40404`), approvals (manual Bash, pending/ended `interaction`
  frames; `40902` on a re-answer), foreground and background sub-agents
  (`agent-0`/`agent-1` `transcript.reset` + `agent` frames, `task` frames
  with `kind:"subagent"`, `child_agent_id`, `detached` true/false; no
  `subagent.*` frames; REST `/tasks` 404), the `TodoList` write contract and
  a read-only `input:{}` query (both with `display:{"kind":"todo_list"}`),
  the `task.watch`/`task.stop` control frames (`task.output` byte offsets and
  a `response` carrying the task), files (upload with sniffed
  `media_type:"image/png"`, download 200, `Range` 206, delete → 
  `{"deleted":true}` then GET 404, session media 200 and surviving the
  upload's delete), WS hello/`subscribe_v2`/ack, unknown-session
  `not_found`, malformed `code:1`, `transcript_since` resume (from seq 197
  the server sent batches 198–200 then the ack, no `transcript.reset`; a
  future cursor fell back to `transcript.reset`), `unsubscribe_v2`,
  `/transcript` (same 2.5.0 key set), `/transcript/ops`,
  `/transcript/user-messages`, `/transcript/plan`, session rating
  (`{"appearance":null}`) and search (`50001`, still disabled). The managed
  `kimi-code/k3` alias still fails with `provider.auth_error` (turn
  `failed`, step `endReason:"error"`), while `Kimi Platform/kimi-k3`
  completed a turn. **App impact: none — no breakage ticket, no improvement
  ticket.** Unit tests: `:app:testDebugUnitTest --rerun-tasks` — 998 tests,
  0 failures, 4 skipped.

- 2026-11-05 re-verification on `2.5.1` (server 2.5.0 → 2.5.1; throwaway
  sessions created with `metadata.cwd=/home/ubuntu/spike` and deleted after
  probing; the nightly job's `pending-changelog.md` covered
  2.5.0→2.5.1 (5 commits), reviewed via the GitHub API — the protocol-bearing
  commits are `6dad2c8d` (structured session media in tool results) and
  `b4e4a84f` (hide sessions of deleted workspaces); `2af56d29` is
  LLM-request serialization and the rest is the web bundle/release; live
  probes over REST + websocat against `127.0.0.1:58627`, server started
  `2026-11-05T19:23:54.678Z`). Drift found:
  (a) a `ReadMediaFile` tool result now carries
      `output:[{type:"text"}, {type:"image",source:{kind:"session_media",
      file_id}}, {type:"text"}]` in the live ops, the warm transcript and
      `/messages` `tool_result.output` (cold-fold parity is source-derived);
      session media download → 200; base64/https/audio stay text;
  (b) `GET /api/v1/sessions` and `GET /api/v2/sessions` (flat, grouped,
      `fields=id,archived`) hide sessions whose workspace was deleted;
      `GET .../{id}` still serves them with `metadata.cwd`;
  (c) two doc corrections from the same probes: the transcript interaction
      entity's approval `request` is camelCase
      (`id/sessionId/agentId/turnId/toolCallId/toolName/action/display`, no
      `expires_at`), and `transcript.reset.snapshot.interactions` is the full
      entity list (ended interactions included), with pending ones re-sent as
      standalone frames after the ack.
  Re-verified unchanged: auth, `/meta` (5 flags, 17 features), config
  (`deepseek/deepseek-v4-flash`, effort `max`, permission `auto`), models
  (12), tools (36 = 27 builtins + 9 MCP), session create/get/list/profile,
  the v2 session table, messages (newest-first; the prompt image rewrite to
  `session_media` + `name`), a full DeepSeek prompt round-trip
  (prompt.upsert → turn.upsert → undo marker → step.upsert →
  frame.upsert/append → completed step/turn/prompt; the synchronous Bash call
  emitted `task` running/ended frames), steer (queued → completed with
  `steeredAt`, user text frame with `promptIds` in the same turn), abort
  (interrupted step, cancelled turn, interruption marker, aborted prompt;
  idle `:abort` → `{"aborted":true}`), both compaction bodies (`{}` and an
  instruction echo; both `40910`s with `stack`; `tokensAfter` matched the
  following `usage` frame and the compaction_summary message), questions
  (pending/ended `interaction` frames, `interaction.upsert`, answer →
  `outcome:"answered"`, re-answer `40902`, dismiss → `outcome:"dismissed"`,
  subscribe-while-pending reset), approvals (manual Bash, pending/ended
  `interaction` frames; no `permission.approval.*` frames), foreground and
  background sub-agents (`agent-0`/`agent-1` `transcript.reset` + `agent`
  frames, `task` frames with `kind:"subagent"`, `child_agent_id`, `detached`
  true/false; no `subagent.*` frames), the `TodoList` write contract and a
  read-only `input:{}` query, files (upload/download/`Range` 206/delete →
  `40407`, session media 200), WS hello/`subscribe_v2`/ack, unknown-session
  `not_found`, malformed `code:1`, and `transcript_since` resume (from seq 55
  the server sent batches 56–60 then the ack, no `transcript.reset`). The
  managed `kimi-code/k3` alias still fails with `provider.auth_error` (turn
  `failed`, step `endReason:"error"`), while `Kimi Platform/kimi-k3` completes.
  **App impact: none — no breakage ticket. One improvement ticket: 184
  (render tool-result `session_media` images).**
  Unit tests: `:app:testDebugUnitTest --rerun-tasks` — 998 tests, 0 failures,
  4 skipped.

- 2026-11-04 re-verification on `2.5.0` (server 2.4.1 → 2.5.0; throwaway
  sessions created with `metadata.cwd=/path/to/project` and deleted after;
  the nightly job's `pending-changelog.md` covered 2.4.1→2.5.0 (69 commits),
  reviewed via the GitHub API with the protocol-bearing commits
  `8535bd79`, `ebc2a9f9`, `011b1d41`, `e24e1e19` and `be89803b`; live probes
  over REST + websocat against `127.0.0.1:58627`, server started
  `2026-11-04T19:23:46.695Z`). **Breaking drift:** the WebSocket layer was
  rewritten — the raw `subscribe`/`unsubscribe` path (silently ignored
  now), the seq/epoch journal, cursor replay, `resync_required`, agent
  filters and every legacy turn/tool/prompt/subagent/task/compaction event
  are gone; `subscribe_v2` is the only session subscription and carries
  `transcript.reset`/`transcript.ops` plus the new flat `task.list`, `task`,
  `agent`, `usage`, `goal`, `todo` and `interaction` frames. `GET /status`,
  `GET /snapshot` and the whole REST task API are plain 404s; their data
  moved to `GET .../transcript` (`seq`, `interactions`,
  `pending_interactions`, `prompts`, `agents`), the flat frames and the
  `task.*` control frames. Compaction is a `marker:"compaction"` pair.
  Global search is disabled by default (`50001`). `:fork` now broadcasts
  `event.session.created`; `session.meta.updated` and
  `event.session.work_changed` are global pushes without a subscription.
  Drift found:
  (a) `subscribe` → no ack, no events; `subscribe_v2` → per-agent state
      frames, `transcript.reset`, ack (ack last); one session per frame;
      unknown session → `code:0` + `not_found`, malformed → `code:1`;
  (b) no `seq`/`epoch` on any session or global frame; replay only via
      `transcript_since` (from seq 30 replayed 31–36 before the ack; a
      future 9999 produced no reset and only the ack);
  (c) `agent`/`usage`/`goal`/`todo` verbatim frames captured, including a
      child agent's `transcript.reset` (`seq:0`) as it announced itself;
  (d) `task.list`/`task` verbatim (shell and subagent, `detached`
      true/false, `child_agent_id`, `parent_tool_call_id`,
      `model`/`thinking`), `task.watch` → `response` + `task.output` (empty
      first chunk), `task.background` flips `detached`, `task.stop` →
      `outcome:"killed"`;
  (e) the full prompt round-trip (prompt.upsert → turn.upsert → undo marker
      → step.upsert → frame.upsert/append → completed step/turn/prompt),
      steer (queued→completed with `steeredAt`, user text frame with
      `promptIds`), abort (interrupted step, cancelled turn, aborted
      prompt, interruption marker, killed task), both compaction markers,
      approval and question interactions (pending/ended + `interaction.upsert`
      + task frames);
  (f) `GET .../transcript` on a running turn returns the live `running` turn
      and the `agents` roster (`main`, `agent-0`, `agent-1`);
      `GET .../transcript/ops` since 30 returns 31+; `/user-messages` and
      `/plan` answer their documented empty/normal shapes;
      `GET .../prompts` returns `{active, queued}`;
  (g) files upload/download/Range 206/delete → 40407/session media 200 and
      the `session_media` prompt rewrite are unchanged; the TodoList input
      and `todo_list` display are unchanged, and the `todo` frame fired
      three times for a three-step task;
  (h) global events (`event.session.created` on fork too,
      `event.session.archived`, `event.session.deleted`,
      `event.workspace.updated`, `session.meta.updated`,
      `event.session.work_changed`) arrive without a subscription and carry
      no `seq`/`epoch`;
  (i) search answers `50001` "global search is disabled …";
  (j) a `kimi-code/k3` prompt is accepted then fails with
      `provider.auth_error` in the transcript representation (step
      `interrupted` + `endReason:"error"`, turn `failed` + `error`, prompt
      `failed`), with no standalone `error` frame.
  Re-verified unchanged: auth, `/meta` (5 flags, 17 features), config,
  models (12), tools (36 = 27 builtins + 9 MCP), session
  create/get/list/archive/restore/delete/fork/profile, the v2 session
  table, workspaces, messages (newest-first), prompts POST/steer/abort,
  both `40910`s with `stack`, the interaction response route and its
  `40404`/`40902`/`40001` errors, files, and the v2 `prompt_id` ==
  `user_message_id` identity.
  **App impact: breaking — the chat has no live stream, no cold start
  (`/snapshot`, `/status` 404), the jobs screen has no `/tasks`, approvals
  and questions are still on the unreachable interaction surface, and
  search is disabled. Tickets 175–183 cover the migration; 171/172/173 are
  updated for the removed snapshot.**
  Unit tests: `:app:testDebugUnitTest --rerun-tasks` — 998 tests, 0 failures,
  4 skipped. The suite does not exercise the removed routes or the socket
  frame dispatch end to end, so it does not catch this drift; the tickets
  above carry the migration tests.

- 2026-10-30 re-verification on `2.4.1` (server 2.4.0 → 2.4.1; throwaway
  sessions `session_0d923d75-…`, `session_ae1dd2d4-…`,
  `session_c221914f-…`, `session_926d017c-…`, `session_0d4d620b-…`,
  `session_3282d15a-…`, `session_2a04406f-…`, `session_50a8c086-…`,
  `session_f47cf281-…`, `session_74a7d234-…`, `session_8dfe1187-…`,
  `session_3bc8967d-…`, `session_acf99dac-…`, `session_6896cadc-…`, all
  created with `metadata.cwd=/home/ubuntu/spike`; the nightly job's
  `pending-changelog.md` covered 2.4.0→2.4.1 (4 commits), reviewed via the
  GitHub API — 140 changed files, none under `packages/kap-server/src` or the
  wire definitions; live probes over REST + Node's built-in WebSocket
  against `127.0.0.1:58627`). **No wire drift found**: no route, frame type,
  field or enum changed. Content-only changes: (a) a single Read now caps at
  ~25k tokens/100k chars (a 210,000-char file returned 99,613 chars); the
  notice is the model-facing `note`, absent from `tool.result`; (b)
  Edit/Write gained the same model-facing `note`, absent from the wire; (c)
  the compaction summary prefix reads "Do not redo work it reports as done
  or re-run checks it reports as passed; …" on both 2.4.0 and 2.4.1 (quote
  refreshed); (d) `turn.steer` was observed after the steered-message
  `context.spliced`, before the new `turn.step.started`; (e) a
  `kimi-code/k3` prompt is accepted and then fails ~4 s later with
  `turn.ended reason:"failed"` + `provider.auth_error` + a standalone `error`
  frame (the POST itself is `code:0`) — same as 2.4.0. Re-verified unchanged:
  meta, config, models (12), tools (35 = 26 builtins + 9 MCP), sessions
  create/get/list/status/snapshot/messages, the v2 session table (flat +
  `by_workspace`), workspaces, search, the prompt round-trip
  (TodoList/Write/Edit/Read: 6 tool calls, 294 frames; `agent.created` on the
  cold sub-agent session, no `streaming` phase), steer (`prompt.queued`
  `queueLength:1`, `prompt.steered`, `turn.steer`, `prompt.completed` with the
  original id), abort (mid-approval → `outcome:"cancelled"`, idle
  `{"aborted":true}`), compaction `{}` and instruction (splice keys,
  `result` fields, empty/busy `40910` with `stack`), approvals (pending →
  POST → ended, legacy `permission.approval.*` alongside), questions
  (pending → answer → `response.answers` keyed by question text; options may
  carry an optional `description`), foreground + background sub-agents
  (`subagent.*`/`task.*`, REST `/tasks` list/get/`:cancel` →
  `status:"killed"`/`"cancelled"`, the snapshot roster), files (upload
  sniffed `text/plain`/`image/png`, download 200, `Range` 206, session media
  200, delete → `40407`, prompt notice + `session_media` rewrite,
  `origin.attachments`), WS hello/subscribe/ack, cursor replay (from seq 40
  re-delivered 43–44), future-cursor `resync_required`, and the
  `client_hello` ack (`{accepted_subscriptions:[], resync_required:[],
  cursors:{}}`).
- 2026-10-28 re-verification on `2.4.0` (server 2.3.3 → 2.4.0; throwaway
  sessions `session_0b0a0493-…`, `session_e27cced3-…`, `session_9415ea78-…`,
  `session_b3b293e0-…`, `session_ddb95bd4-…`, `session_088406ec-…`, and a
  deleted lifecycle session, all created with
  `metadata.cwd=/home/ubuntu/spike`; the nightly job's
  `pending-changelog.md` covered 2.3.1→2.4.0 (49 commits), reviewed via the
  GitHub API; the post-2.3.3 slice is 10 commits with two protocol files
  touched — `461c6e92` (`packages/kap-server`: interaction frame/route) and
  `78b72fe4` (`routes/prompts.ts`: `swarm_mode`); live probes over REST +
  Node's built-in WebSocket against `127.0.0.1:58627`).
  Drift found (one breaking interaction change, one additive prompt field):
  (a) **breaking** — `event.approval.*` / `event.question.*` removed and
      replaced by the flat `interaction` frame; `POST .../approvals/{id}`,
      `POST .../questions/{id}`, `POST .../questions/{id}:dismiss`,
      `GET .../approvals`, `GET .../questions` removed; one
      `POST .../interactions/{id}` remains (`{kind:"approval",decision,…}` /
      `{kind:"question",answers|dismissed}` → `{outcome, ended_at}`).
      Verbatim pending/ended captures for both kinds in Interactions; the
      error paths `40404` unknown id, `40902` ended, `40001` kind mismatch,
      and the old routes' plain HTTP 404s were exercised live. Pending
      interactions are re-sent after every accepted subscribe ack.
      `permission.approval.requested`/`.resolved` still ride the durable
      stream unchanged; the v2 frames were observed next to the new ones.
      Abort with a pending approval → `outcome:"cancelled"` /
      `response:{decision:"cancelled"}`; abort with a pending question →
      `outcome:"dismissed"`, no `response`;
  (b) `swarm_mode` on the prompt POST is applied now (true→`/status`
      `swarm_mode:true`, false→false); up to 2.3.3 it was ignored;
  (c) engine behavior: plan mode is user-only and every plan waits for
      review — an `ExitPlanMode` approval carries
      `tool_input_display:{kind:"plan_review",plan,path}` (verbatim in
      Interactions), and `POST .../profile {"agent_config":{"plan_mode":true}}`
      still enters plan mode;
  (d) idle sessions unload after 30 min
      (`KIMI_CODE_SESSION_IDLE_RECLAIM_MS`) and resume transparently;
  (e) content drift: 35 tools on an initialized agent (26 builtins + 9 MCP;
      `ReadMediaFile` absent), 12 models, `default_model`
      `deepseek/deepseek-v4-flash`, `/meta` 5 flags and 17 features; a
      `kimi-code/k3` prompt still failed with `provider.auth_error` / HTTP
      403 subscription text, so the turn probes ran on DeepSeek.
  Re-verified unchanged: meta, config, sessions
  create/get/list/status/snapshot, `40401`, `:reload` (idle, session DTO),
  `:takeover` (idle `{taken_over:true}`), `:archive`/`:restore`, `:delete`
  plus the `__global__` `event.session.deleted`, a full prompt round-trip
  (`agent.created` → `agent.status.updated` → `prompt.submitted` →
  `session.meta.updated` → `turn.started` → `context.spliced` →
  `prompt.started` → steps/deltas → `turn.ended` → `work_changed` →
  `prompt.completed`; no `streaming` phase), queueing + steer
  (`prompt.queued` `queueLength:1`, `prompt.steered` reusing the queued id,
  `turn.steer` with `inTurn:true` and the same `messageId`), running and idle
  `:abort`, both compaction bodies (`tokensAfter` 29740 == post-compact
  `context_tokens`, instruction echo, wholesale splice) and both `40910`s
  with `stack`, questions (answer → `answered` + `response.answers` keyed by
  question text, dismiss → `dismissed`, re-dismiss `40902`, unknown id
  `40404`), approvals (approve and reject, both `interaction` pairs, the
  snapshot `pending_approvals` item and `pending_interaction:"approval"`),
  files (upload sniffed `media_type`, download 200, `Range` 206, prompt
  `session_media` rewrite with `name`, delete → legacy route 404 while the
  session media route still serves), the TodoList write
  (`{"todos":[{"title","status"}]}` with `done`), foreground + background
  sub-agents (`subagent.spawned` with `taskId`/`model`/`thinkingEffort`,
  `subagent.completed` with `resultSummary`/`usage`/`contextTokens`,
  `task.started` → `background.task.started` → `task.terminated` →
  `background.task.terminated` → `task.notified`), REST `/tasks`, the v2
  session table (`{items, has_more, next_page_token, total}`), workspaces,
  global search (`index_state` ready, `stale:true`), and WS
  hello/subscribe/ack/cursor-resume (replay from seq 65 re-delivered 66–68
  before the ack; future seq → standalone `resync_required`
  `epoch_changed` then the ack listing it).
- 2026-10-28 re-verification on `2.3.3` (server 2.3.1 → 2.3.3; throwaway
  sessions `session_dac7161d-…`, `session_805f6cb8-…`, and
  `session_1e35eb75-…` created with `metadata.cwd=/home/ubuntu/spike`, all
  `:delete`d after; the nightly job's
  `pending-changelog.md` fetch WORKED, 13 commits between
  `@moonshot-ai/kimi-code@2.3.1` and `…@2.3.3`, reviewed via the GitHub API;
  only `packages/kap-server/src/services/clientConfigs.ts` changed among the
  client-facing protocol files: `f895f305` and `0051ef10`; live probes over
  REST + websocat against `127.0.0.1:58627`).
  Drift found (all additive; nothing breaks the Android app):
  (a) `model_switch_confirm` rules now require `session_type`
      (`old`/`new`/`all`), accept `*` wildcards in `from`/`to`, and may carry
      `warning`; the live config is populated and includes `new`-session and
      `old`-session rules for `k3.1-pro-highspeed`, plus an `old`-session rule
      for the K3.1 series (verbatim capture in Client configs);
  (b) new registered client config `effort_switch_confirm` returns enabled
      descriptions for `low`/`high`/`max` and one wildcard `old`-session
      confirmation rule (verbatim capture in Client configs);
  (c) engine behavior: AgentSwarm requires user-enabled swarm mode, rejected
      swarm calls with no spawned members are hidden, and the model is told
      to launch `Agent` only on explicit request. No wire type or field
      changed;
  (d) content drift: 12 models (K3.1 highspeed display now includes
      "2x Usage Cost", both K3.1 defaults `high`), `default_model`
      `deepseek/deepseek-v4-flash`, and 36 tools on an initialized agent
      (27 builtins + 9 MCP, `ReadMediaFile` absent);
  (e) provider state: a `kimi-code/k3` prompt failed instantly with
      `provider.auth_error` / HTTP 403 subscription text; the round-trip then
      ran on the configured `deepseek/deepseek-v4-flash`. Envelope and turn
      failure shapes were unchanged.
  Re-verified unchanged: meta (5 flags, 17 features), config
  (`thinking.effort:"max"`, `default_permission_mode:"auto"`, no feedback
  survey or title keys), sessions create/get/list/status/snapshot
  (`last_seq:1`, `as_of_seq:2`), `40401` without `stack`, `:reload` on an
  idle session (session DTO, `last_seq:0`) and on an unknown session
  (`40401` with `stack`), a full prompt
  round-trip (`prompt.submitted` → `session.meta.updated` → `turn.started` →
  `context.spliced` → `prompt.started` → steps → `turn.ended` with `traceId`
  → `work_changed` → `prompt.completed`), the TodoList write with
  `{"todos":[{status,title}]}` and its `Todo list updated.` result prose,
  queueing plus `prompt.steered` (the queued prompt seeded the next turn
  after abort, so no `turn.steer` fired), running and idle `:abort`, compact
  with `{}` (`tokensAfter` 30413) and `{"instruction":…}` (`tokensAfter`
  30298), both `40910`s with `stack`, questions (answer, re-answer `40902`,
  dismiss `40909`, re-dismiss `40902`, snapshot items), the manual approval
  round-trip (`tool_call_id` → `40404`, approval → both resolved frames),
  files (upload, download byte match, `Range` 206, delete → `40407`, session
  media survives upload deletion), foreground and background sub-agents
  (`subagent.*`, `turn.started` on the agent id, task cancel and terminal
  `40904`), `/tasks`, session rating `evaluate` (all `{"appearance":null}`),
  idle `:takeover` `{"taken_over":true}`, the lifecycle events
  (`event.session.created`, global `event.session.deleted` with camelCase
  `sessionId`), and WS hello/subscribe/ack (`protocol_version:2`,
  `heartbeat_ms:10000`; cursor 100 replayed 101–217 before the ack; a future
  cursor produced standalone `resync_required` `epoch_changed` then the ack).
  Unit tests: `:app:testDebugUnitTest --rerun-tasks` ran 959 tests, 2 failed,
  4 skipped. The failures are `ChatViewModelTest.a swarm's members all link
  to the one call from the cold-start roster` (deterministic, no element in
  the expected UI list) and `SubagentViewModelTest.the store entry flows into
  the ui state` (passed when rerun alone). Neither test touches a DTO or
  parser changed by this protocol audit, and no app source was changed in
  this run.
- 2026-10-23 re-verification on `2.3.1` (server 2.3.0 → 2.3.1; throwaway
  sessions created with `metadata.cwd=/home/ubuntu/spike`, all eleven
  `:delete`d after; the nightly job's `pending-changelog.md` fetch WORKED —
  4 commits between the annotated tags `@moonshot-ai/kimi-code@2.3.0` →
  `…@2.3.1`, diffs reviewed in full via the GitHub API: `c569bab5`
  (`:takeover` route, zombie-occupancy fix, trust decoupling,
  `KIMI_CODE_SESSION_LOCK`), `0e3980e7` (`clientDisplayName` on the 40931
  holder + message text), `c445cae0` web-dist sync, `684bae9e` release CI;
  live probes over REST + websocat against `127.0.0.1:58627`, plus
  `kimi -S <id> -m kimi-code/k3 -p` CLI turns to exercise the occupancy
  lock).
  Drift found (all additive or behavioral; nothing breaks the Android app):
  (a) new route `POST :takeover` — idle `{taken_over:true}`, unknown `40401`
      with `stack`, live-CLI-held → lock file evicted and the retried prompt
      ran; takeover by the holding process itself is a no-op (all verified
      live);
  (b) 40931 `details.holder` gained optional `clientDisplayName` and the
      `msg` names it — verbatim envelope in the drift section; the embedded
      server's own holds read "Kimi Code Web"; a dead-pid lock was reclaimed
      live mid-probe;
  (c) workspaces trust round-trip re-verified (the resolver no longer
      materializes the runtime — behavioral, same wire);
  (d) content drift: models catalog 12 entries (`kimi-code/k3.1-pro` +
      `k3.1-pro-highspeed` new, no `upgrade` on any); `model_switch_confirm`
      client config now POPULATED (`enable:true`, k3*/kimi-for-coding* →
      k3.1-pro* rules with en/zh hints — was `config:null` on 2.3.0); tools
      catalog 36 on a fresh session (27 builtins + 9 MCP);
  (e) first-time itemization: the create route briefly holds a server-side
      lock (`clientDisplayName:"Kimi Code Web"`), released when the session
      goes idle.
  Re-verified unchanged, most byte-for-byte: meta (5 flags, 17 features —
  only `server_version` differs), the config projection, sessions
  create/get/list (`last_seq` `1` fresh detail / `0` list), `40401` without
  `stack` on the get path, `/status`, the snapshot (top-level keys, real
  `session.usage`, busy `in_flight_turn` capture), messages history
  (newest-first), prompts POST + ACK, one full prompt round-trip (the
  documented skeleton verbatim — `prompt.submitted` → `session.meta.updated`
  → `turn.started` with `promptId` → `context.spliced` → `prompt.started` →
  steps/deltas → `turn.step.completed` → `turn.ended` with `traceId` →
  idle `work_changed` → `prompt.completed`), prompt queueing + steer
  (`queueLength`, `{"steered":true}` ACK, single-steer id reuse,
  `prompt.steered` seq 15 before `turn.steer` seq 20 with the `inTurn:true`
  origin), mid-run `:abort` (interrupted sequence verbatim) plus idle
  `{"aborted":true}`, compact `{"instruction":…}` (instant ACK, instruction
  echo, splice, `compaction.completed` `result` with `tokensAfter` 30415)
  plus both `40910`s with `stack`, the questions flow (pending poll with
  `agent_id`, answer → `resolved:true`, dismiss → `40909` +
  `dismissed:true`, re-dismiss AND answer-after-dismiss → `40902`,
  `event.question.requested`/`answered` frames), the manual-approval
  round-trip (`approval_<uuid>` split intact, `tool_call_id` POST → `40404`,
  `approved` → both resolved frames), files (upload sniffed `image/png`,
  `session_media` projection with `name` in the ACK AND history, download
  200, `Range` 206, delete → `{"deleted":true}`, `40407` at HTTP 404), the
  TodoList contract (verbatim `{"todos":[{status,title}]}` in history
  `tool_use` parts), foreground + background sub-agent lifecycles
  (`subagent.spawned` with `taskId`/`model`/`thinkingEffort:"max"`,
  `task.started` → `background.task.started`), REST `/tasks` list, the
  workspaces list, global search (`index_state`), the v2 grouped session
  table, the lifecycle events (`event.session.created`,
  `event.session.deleted` on `__global__` with camelCase `sessionId`), and
  WS hello/subscribe/ack/cursor-resume (`protocol_version: 2`,
  `heartbeat_ms` 10000; replay from seq 2 re-delivered 3–15 in order before
  the ack; future-seq cursor → standalone `resync_required` `epoch_changed`
  then the ack listing it; heartbeat-timeout silence re-observed — frames
  stop ~30 s in without pongs; a 5 s pong feeder keeps the socket alive).
  Tooling notes: websocat 1.14 needs `-H="Authorization: …"` (equals form —
  the space-separated form eats the header as the URL and fails with "No URL
  specified"); a CLI resumed against a session created for a different cwd
  refuses with "created under a different directory" — `cd` into the
  session's cwd first; a CLI `-p` turn without `-m` may die with
  `model.not_configured` after taking the lock (the dead-pid reclaim then
  makes the probe silently pass — check the holder pid against the CLI pid).
- 2026-10-22 re-verification on `2.3.0` (server 2.2.0 → 2.3.0; throwaway
  sessions `session_074efb5c-…` / `session_76b4f3bc-…` created with
  `metadata.cwd=/home/ubuntu/spike`, both `:delete`d after; the
  nightly job's `pending-changelog.md` fetch FAILED again (missing tag or
  network), so the 33 upstream commits between the tags were enumerated
  from a `--filter=blob:none` clone of the public repo (annotated tags
  `@moonshot-ai/kimi-code@2.2.0` → `472192c3…`, `…@2.3.0` → `b5e28fd6…`)
  and the 20 touching `packages/kap-server`/`packages/agent-core-v2`
  reviewed in full; live probes over REST + websocat against
  `127.0.0.1:58627`, plus a second `kimi -p` CLI process to exercise the
  occupancy lock; the wire-relevant commits are `df0ff3c5` (occupancy lock,
  40931/40932, envelope `details`), `18c6d642` (`:reload`), `ce0834a8`
  (`/rating` + `disable_feedback_survey`), `3f80896a` (`/client_configs`),
  `0327e8e9` (model `upgrade` / `retired_default`), `5710c549` (WS
  event-state eviction) — see the drift section above).
  Drift found (all additive or behavioral; nothing breaks the Android app):
  (a) new route `POST :reload` — idle answers the session DTO, busy answers
      40901 with `stack` (both verified live);
  (b) new route `POST /api/v1/sessions/{id}/rating` — `evaluate` with four
      turn-id spellings all answered `{"appearance":null}`, unknown turn ids
      are not errors;
  (c) new route `POST /api/v1/client_configs` — `model_switch_confirm` AND
      an unregistered name both answer `config:null` on this server;
  (d) 40931 verified live end-to-end: a `kimi -p` CLI turn held the session
      lock (`~/.kimi-code/locks/sessions/<id>.lock`, holder JSON verbatim in
      the drift section), the server prompt POST answered 40931 with
      `details.holder.clientIdentity:"kimi_code_cli"`; after the CLI exited
      the same POST succeeded. 40932 NOT returned by prompts — a prompt
      after the CLI appended a turn self-healed (`code:0`), confirming the
      `reloadOnStale` wrap; only `:undo` surfaces it (not probed);
  (e) schema-derived, absent live: model-item `upgrade`,
      `event.model_catalog.changed` `retired_default`, config
      `disable_feedback_survey` (no key when unset — verified live);
  (f) behavioral: archived session subscribe → `not_found`; cursor-resume
      replay still works across archive/restore AND across the stale-reload
      (the replay included the reload's `agent.disposed` frame);
  (g) content drift: tools catalog 37 entries on an initialized agent (28
      builtins — `ReadMediaFile` back — + 9 MCP: tavily 5, kiln 4); v2
      table `activity.model` read `null` (not `""`) on one never-opened
      session.
  Re-verified unchanged, most byte-for-byte: `/api/v1/meta` (5 flags —
  `notify_user`/`wait_for`/`subagent_fork`/`tower`/`tool-select`; 17
  features — only `server_version` differs), the config projection
  (`default_model` `kimi-code/k3-256k`, `thinking.effort` `"max"`,
  `default_permission_mode` `"auto"`, providers redacted, the `models` map),
  the models catalog (10 entries, same item shape, no `upgrade`), sessions
  create/get/list (`last_seq` `1` on a fresh detail, `0` on the list),
  `40401` without `stack`, `/status` (`permission` flips `auto`→`manual`
  via profile, pushing the 2.2.0 `agent.status.updated` `permission`
  variant), the snapshot (top-level keys, real `session.usage`
  `context_tokens` 28950/`context_limit` 1048576, busy `in_flight_turn`
  with `current_prompt_id`/`running_tools`, the completed roster entry with
  `output_preview`), messages history (newest-first; the steered message
  persisted with the queued prompt's own id; `date_change` +
  `permission_mode` injections; no text-part `meta` — hook-less server),
  prompts POST + ACK (`prompt_id` == `user_message_id`, `status`
  `running`/`queued`, `content` echo), one full prompt round-trip (the
  documented skeleton — `prompt.submitted` → `session.meta.updated` →
  `turn.started` with `promptId` → `context.spliced` → `prompt.started` →
  steps/deltas → `turn.step.completed` with the full `llm*` timing block +
  `usage` → `turn.ended` with `traceId` → idle `work_changed` →
  `prompt.completed`; no `streaming` phase), prompt queueing + steer
  (`prompt.queued` `queueLength`, `{"steered":true}` ACK, single-steer id
  reuse, `prompt.steered` before `turn.steer` with the `inTurn:true`
  origin), mid-run `:abort` (`turn.step.interrupted` `aborted` →
  `turn.ended` `cancelled`/`user_cancelled` → interruption-reminder splice
  with `ownerPromptId` → `prompt.aborted`) plus idle `:abort`
  `{"aborted":true}`, compact with `{}` AND `{"instruction":"Keep the probe
  findings."}` (instant empty ACK, instruction echo, wholesale
  `context.spliced` — id-less `compaction_summary` +
  `compaction_continuation` —, `compaction.completed` `result`,
  `tokensAfter` 30205 == post-compact `context_tokens`, the busy AND empty
  `40910`s with `stack`), the questions flow end-to-end (pending poll with
  `agent_id`, `question_<uuid>` ≠ `tool_call_id`, answer →
  `{"resolved":true}`, re-answer `40902`, dismiss `40909` +
  `dismissed:true`, re-dismiss `40902`, all three `event.question.*`
  frames), the manual-approval round-trip (`approval_<uuid>` split intact,
  `tool_call_id` POST → `40404`, `approved` → both resolved frames), files
  (upload with sniffed `media_type`, the `session_media` projection with
  `name` in the ACK echo AND history, download 200, `Range` 206, delete →
  `40407` at HTTP 404, the media copy surviving the upload's delete), the
  TodoList contract (verbatim `{"todos":[{status,title}]}`), foreground +
  background sub-agent lifecycles (`subagent.spawned` with
  `taskId`/`model`/`thinkingEffort:"max"`, `subagent.started`,
  `subagent.completed` with `resultSummary`/`usage`/`contextTokens`,
  `task.started` → `background.task.started` → `task.terminated` →
  `background.task.terminated`), REST `/tasks` list (all the
  0.39.0/0.36.1 fields incl. `run_in_background`/`agent_id`/
  `subagent_type`/`parent_tool_call_id`), the per-agent transcript (steps
  carry `usage` + `llmTiming`; top-level `agents`/`attachments`/
  `interactions`/`prompts`/`tasks`/`todos` intact), the turn file-history
  routes (`{changes[], recorded}`, no `enabled`), the workspaces list,
  global search (`index_state` intact), the v2 grouped session table
  (`groups[]`, `activity.model`), the lifecycle events
  (`event.session.created`, `:delete` → `{"deleted":true}` +
  `event.session.deleted` with camelCase `sessionId` on the `__global__`
  envelope), and WS hello/subscribe/ack/cursor-resume
  (`protocol_version: 2`, `heartbeat_ms` 10000; replay before the ack;
  future-seq cursor → standalone `resync_required` `epoch_changed` then the
  ack listing it; the heartbeat-timeout silence re-observed — frames stop
  ~30 s in when pongs go unanswered). **App impact: none — three
  improvement tickets (166/167/168, filed by this drift run's earlier
  session); no breakage tickets.**
- 2026-10-14 re-verification on `2.2.0` (server 2.1.1 → 2.2.0; throwaway
  sessions `session_ec4c69c0-…` / `session_2796ca8f-…` created with
  `metadata.cwd=/home/ubuntu/spike`, both `:delete`d after; the nightly
  job's `pending-changelog.md` fetch FAILED (missing tag or network), so
  the 36 upstream commits between the tags were enumerated and reviewed
  directly via the GitHub compare/commits API; live probes over REST +
  websocat against `127.0.0.1:58627`; the wire-relevant commits are
  `a940f2ff` (`agent.status.updated` `permission`), `06ebfc82` (text-part
  `meta` + hook folding), `e3bf50c0` (interruption-reminder
  `ownerPromptId`), `20a2cea7` (WaitFor 90 s cap) — the rest is
  wire-invisible here (NotifyUser/tower flags off, remote-control and
  fs-list routes out of scope, TUI/CLI churn) — see the drift section
  above).
  Drift found (all additive or behavioral; nothing breaks the Android
  app):
  (a) `agent.status.updated` fires on permission-mode changes carrying
      `"permission":"<mode>"` — verified live twice: `POST .../profile`
      `{"agent_config":{"permission_mode":"manual"}}` pushed the frame at
      seq 90 (verbatim in the drift section), the `"auto"` write pushed
      its twin; the app never modeled the field (ignoreUnknownKeys) —
      ticket 165 consumes it;
  (b) the interruption `<system-reminder>` history entry's
      `metadata.origin` carries `ownerPromptId` — verified live in
      `GET .../messages` after an `:abort`
      (`{"kind":"injection","variant":"interruption","ownerPromptId":"msg_01M4XYSMNQ3MCK6DFGQ2776DPG"}`);
  (c) history text parts may carry `meta` (skill-activation /
      UserPromptSubmit-hook markers) — source-derived only: this server
      runs no such hooks and no skill prompts were sent, so nothing new
      appeared on the wire;
  (d) WaitFor capped at 90 s and ended early by new input — engine
      behavior (wire shape unchanged; not probed live — the app never
      parses WaitFor results);
  (e) behavioral: `default_permission_mode` now reads `"auto"` (was
      `"yolo"`), and under auto mode the model is reminded not to call
      `AskUserQuestion` — verified live: a cake-or-pie prompt on an
      `auto` session completed with zero question events; the same prompt
      after `POST .../profile {"agent_config":{"permission_mode":"manual"}}`
      surfaced the full questions flow (pending poll with `agent_id`,
      answer → `{"resolved":true}`, re-answer `40902`, dismiss `40909` +
      `dismissed:true`, re-dismiss `40902`, all three `event.question.*`
      frames);
  (f) tools-catalog content drift: 36 entries on an initialized agent —
      27 builtins + 9 MCP tools (tavily 5, kiln 4); `ReadMediaFile` out of
      the catalog again (2.1.1 had it, 28 builtins); `NotifyUser` still
      gated off.
  Re-verified unchanged, most byte-for-byte: `/api/v1/meta` (5 flags —
  `notify_user`/`wait_for`/`subagent_fork`/`tower`/`tool-select`; 17
  features — only `server_version` differs), the config projection
  (`default_model` `kimi-code/k3-256k`, `thinking.effort` `"max"`,
  providers redacted), the models catalog (10 entries), sessions
  create/get/list (`last_seq` `1` on a fresh detail, `0` on the list),
  `40401` without `stack`, `/status` (`thinking_level` `""` fresh,
  `max_context_tokens` 262144, `permission` `"auto"`), the snapshot
  (top-level keys `as_of_seq`/`epoch`/`in_flight_turn`/`messages`/
  `pending_approvals`/`pending_questions`/`session`/`subagents`, real
  `session.usage`, busy `in_flight_turn` with `running_tools`,
  `messages:{items, has_more}`, empty roster once terminal), messages
  history (newest-first, `has_more`), prompts POST + ACK (`prompt_id` ==
  `user_message_id`, `content` echo, `created_at`), one full prompt
  round-trip (the documented skeleton — `session.meta.updated` →
  `prompt.submitted` → `turn.started` with `promptId` → `context.spliced`
  (user message + `date_change` + `permission_mode` injections) →
  `prompt.started` → steps → `tool.call.started`/`tool.result` →
  `turn.step.completed` → `turn.ended` with `traceId` → idle
  `work_changed` → `prompt.completed`; no `streaming` phase), prompt
  queueing + steer (`prompt.queued` `queueLength`, `prompt.steered` with
  the 2.0.1 `messageId` id-reuse, `context.spliced` then `turn.steer`
  with `inTurn:true`/`promptIds`/`turnId`, same-turn continuation, one
  `prompt.completed` with the original id), mid-run `:abort`
  (`turn.step.interrupted` → `turn.ended` `cancelled`/`user_cancelled` →
  interruption-reminder splice → `prompt.aborted`) plus idle `:abort`
  `{"aborted":true}`, compact with `{}` AND `{"instruction":"keep the
  todo plan"}` (instant empty ACK, `compaction.started` with the
  instruction echo, wholesale `context.spliced` — id-less
  `compaction_summary` + `compaction_continuation` injection —,
  `compaction.completed` with the full `result` block, `tokensAfter`
  29708 == post-compact `context_tokens`, both `40910`s with `stack`),
  the manual-approval round-trip (pending poll item with `agent_id`,
  `approval_<uuid>` ≠ `tool_call_id`, `tool_call_id` POST → `40404`,
  `approved` → `event.approval.resolved` +
  `permission.approval.resolved`), files (upload sniffed `media_type`,
  download 200, `Range` 206, delete `{"deleted":true}`, `40407` at HTTP
  404 after delete, the image `session_media` projection with `name` in
  the ACK AND history, the media copy surviving the upload's delete —
  session-media route 200 with the same 69 bytes), the TodoList contract
  (verbatim `{"todos":[{status,title}]}` with the `todo_list` display),
  foreground + background sub-agents (full lifecycles:
  `subagent.spawned` with `taskId`/`model`/`thinkingEffort:"max"`,
  `subagent.completed` with `resultSummary`/`usage`/`contextTokens`,
  `task.started` → `background.task.started` → `task.terminated` →
  `background.task.terminated` → `task.notified` with the "Wall time:"
  prefix; the cancel sequence verbatim — `subagent.cancelled` →
  `task.terminated` `"killed"` → `background.task.terminated` →
  `task.notified` `"task.killed"`), REST `/tasks` list/get
  `with_output`/`:cancel` (`{"cancelled":true}`; `40904` on an
  already-finished task; REST `status:"cancelled"` after), the v2
  grouped session table (`groups[]` + `total`, `activity.model`), the
  workspaces list, the per-agent transcript (completed steps carry
  `usage` + `llmTiming`), the tools catalog (36 entries), the session
  lifecycle events (`event.session.created` at seq 1 on the new
  session's own stream, `:delete` → `{"deleted":true}` + global
  `event.session.deleted` with camelCase `sessionId`), and WS
  hello/heartbeat/subscribe/ack/cursor-resume (`protocol_version: 2`,
  `heartbeat_ms` 10000; replay from seq 290 re-delivered 291+ in order
  before the ack; future-seq cursor → standalone `resync_required`
  `epoch_changed` then the ack listing it; the heartbeat-timeout close
  re-observed — pings at +10 s/+20 s after the last inbound frame, close
  before +30 s; verification nit: websocat keeps its process alive on a
  held-open stdin FIFO after the server closes, so liveness must be
  judged by the close message / missing acks, not the process).
- 2026-09-24 re-verification on `2.1.1` (server 2.0.2 → 2.1.1; throwaway
  sessions `session_97ade091-…` / `session_36a02184-…` /
  `session_e8f1813c-…` / `session_18e4c056-…` / `session_d80d9dd8-…` /
  `session_a292eb77-…` created with `metadata.cwd=/home/ubuntu/spike`, all
  `:delete`d after; guided by the nightly job's `pending-changelog.md` — 28
  upstream commits between the tags, reviewed via the GitHub API; live
  probes over REST + websocat against `127.0.0.1:58627`; the wire-relevant
  commits are `b428bfd0` (transcript step `timing`→`llmTiming` + cold-fold
  usage/timing), `895e9d9b` (durable `subagent.*` events + swarm restore +
  transcript `lost` marking), `0dc4d4fb` ("Wall time:" headers),
  `6a52dd78` (`auto_session_title` config), `994287ac` (fork `titleKind`)
  and `6a214b85` (wireCache stack overflow); the rest is wire-invisible —
  see the drift section above).
  Drift found (all additive or prose; nothing breaks the Android app):
  (a) transcript steps carry `usage` + renamed `llmTiming` — verified live
      on the warm path (verbatim step in the drift section); the cold-fold
      inclusion is source-derived (`foldFacts.ts`/`groupTurns.ts`); the
      app parses neither field;
  (b) `subagent.spawned`/`started`/`cancelled` are durable and now ride the
      cursor-resume replay — verified live: a resume from seq 50 replayed
      them at seqs 68/69/84; shapes unchanged;
  (c) `task.notified` `body` and the `<notification>` history XML open with
      `Wall time: N.NNN seconds` (verbatim `"Wall time: 14.559 seconds\n…"`
      on a killed-agent notification; also on completed bash/agent); the
      task tools' output text prepends the same header; the model-facing
      tool-result header stays engine→LLM-only — WS `tool.result.output`
      and history `tool_result.output` verified byte-clean of it, and the
      background stubs still head with `task_id:` (live AND history);
  (d) `auto_session_title` accepted by the config REST schema but absent
      from this server's GET projection (titles on by default);
  (e) tools-catalog content drift: 37 entries initialized / 36 fresh (was
      38/37 on 2.0.2) — same 28/27 builtins, 9 MCP tools (tavily back to 5,
      `tavily_feedback` gone; kiln 4) — host MCP config, not the release.
  Re-verified unchanged, most byte-for-byte: `/api/v1/meta` (5 flags, 17
  features — only `server_version` differs), config projection, sessions
  create/get/list (`last_seq` `1` on a fresh detail, `0` on the list),
  `40401` without `stack`, `/status`, the snapshot (top-level keys, real
  `session.usage`, busy `in_flight_turn` with thinking text,
  `pending_approvals` item with `agent_id`, empty roster once terminal),
  messages history (newest-first; media parts with `name`), prompts POST +
  ACK, one full prompt round-trip (the documented skeleton —
  `session.meta.updated` → `prompt.submitted` → `turn.started` →
  `context.spliced` → `prompt.started` → steps/deltas → `tool.*` →
  `turn.ended` → `prompt.completed`), prompt queueing + steer
  (`prompt.queued` `queueLength`, `prompt.steered` with `messageId` == the
  queued id, `turn.steer` with the 2.0.1 identity set, `prompt.completed`
  with the original id), mid-run `:abort` (`turn.step.interrupted` →
  `turn.ended` `cancelled`/`user_cancelled` → interruption-reminder splice
  → `prompt.aborted`) plus idle `:abort` `{"aborted":true}`, compact with
  `{}` and with `{"instruction":…}` (instant empty ACK, instruction echo in
  `compaction.started`, wholesale splice with the id-less
  `compaction_summary` + `compaction_continuation`, `tokensAfter` 29431 ==
  post-compaction `context_tokens`, both `40910`s with `stack`), the
  questions flow end-to-end (pending poll with `agent_id`, `single` answer
  → `{"resolved":true}`, dismiss `40909`, re-answer/re-dismiss `40902`,
  all three `event.question.*` frames), the manual-approval round-trip
  (`approval_<uuid>` ≠ `tool_call_id`, `tool_call_id` POST → `40404`,
  `approved` → both resolved frames, the snapshot item), files (upload with
  sniffed `media_type`, download 200, `Range` 206, delete, `40407` at HTTP
  404 after delete, the image `session_media` projection with `name` in the
  ACK echo AND history, the media copy surviving the upload's delete), the
  TodoList contract (verbatim `{"todos":[{status,title}]}` + the
  `todo_list` display), foreground + background sub-agent lifecycles
  (`subagent.spawned` with `taskId`/`model`/`thinkingEffort:"max"`,
  `subagent.completed` with `resultSummary`/`usage`/`contextTokens`, the
  cancel sequence `prompt.aborted` → `subagent.cancelled` →
  `task.terminated` `killed` → task-origin notification turn verbatim),
  REST `/tasks` list, the v2 grouped session table, the models catalog (10
  entries), the lifecycle events (`event.session.created` at seq 1,
  `:delete` → `{"deleted":true}` + global `event.session.deleted` with
  camelCase `sessionId`), and WS hello/subscribe/ack/cursor-resume
  (`protocol_version: 2`, `heartbeat_ms` 10000; replay from seq 50
  re-delivered 51+ in order before the ack; future-seq cursor → standalone
  `resync_required` `epoch_changed` then the ack listing it; the
  heartbeat-timeout close re-observed on a silent subscriber).
  **App impact: none — every 2.1.1 change is additive on payloads the app
  either ignores wholesale or parses defensively (`ignoreUnknownKeys`).
  No tickets filed.**
  Unit tests: `:app:testDebugUnitTest` green — 957 tests, 0 failures
  (forced full re-run, `--rerun-tasks`; counts from the JUnit XML of that
  execution).

- 2026-09-19 re-verification on `2.0.2` (server 2.0.1 → 2.0.2; throwaway
  session `session_29a30643-…` created with `metadata.cwd=/home/ubuntu/spike`,
  `:delete`d after; guided by the nightly job's `pending-changelog.md` — 12
  upstream commits between the tags, reviewed via the GitHub API; live probes
  over REST + websocat against `127.0.0.1:58627`; the wire-relevant commits
  are `2cedfaf1` (`agent_id` on approval/question wire items + interaction
  events bypassing agent filters), `2502d215` (v3 WS + `/history` revert),
  `97212596` (no `turn.steer` for an unconsumed steer), `9df7a9cc` (turn-id
  floor + cold-transcript fold) and `88a7d932` (compaction pre-shrink).
  Verified live: the v3 handshake refused and `/history` 404; the v2
  hello/subscribe/ack unchanged (`protocol_version: 2`, `heartbeat_ms`
  10000); session create/get/list/`/status`/messages unchanged; the full
  questions flow (pending poll, answer → `resolved:true`,
  `event.question.answered` keyed by question text, dismiss → `40909`,
  `event.question.requested` verbatim with the new `agent_id`); the approval
  round-trip (`/approvals?status=pending` item with `agent_id`, decision
  `approved` → `resolved:true`, `event.approval.requested` verbatim with
  `agent_id`, `event.approval.resolved` + the v2 duplicate; a bogus decision
  `40001`s listing `"approved"|"rejected"|"cancelled"`); the snapshot
  (top-level keys unchanged; `pending_questions`/`pending_approvals` items
  carry `agent_id`); a single-prompt steer (`prompt.steered` with
  `messageId` == the queued id → `turn.steer` with the 2.0.1 identity set,
  unchanged); compact with `{}` and with an instruction
  (`compaction.started` `"trigger":"manual"` → `context.spliced` →
  `compaction.completed` with the summary, unchanged); `:abort`; and the
  heartbeat-timeout close (a subscriber that never answers pings is dropped
  after ~20 s, as documented since 0.35.0).

- 2026-09-18 re-verification on `2.0.1` (server 2.0.0 → 2.0.1; throwaway
  sessions `session_81a2130c-…` / `session_516ea33e-…` /
  `session_cd550cb1-…` created with `metadata.cwd=/home/ubuntu/spike`, all
  `:delete`d after; guided by the nightly job's `pending-changelog.md` — 21
  upstream commits between the tags, reviewed via the GitHub API; live probes
  over REST + websocat against `127.0.0.1:58627`; the wire-relevant commits
  are `3cc6b2a3` (turn `traceId`), `53e5e3fc`/`60f2a632`/`73ebe9ab` (steer
  message-identity pairing), `c5ad17f0` (provider `api_key_env`), `92c3c59b`
  (catalog provider `base_url`), `86e08208` (yolo approves unanalyzable
  bash), `e3f48a22` (session-delete hang fix) and `5108cad9` (search sync
  cap); the rest are wire-invisible — see the drift section above).
  Drift found (all additive; nothing breaks the Android app):
  (a) `turn.ended` gained optional `traceId` — present on every
      `turn.ended` of this run, main and sub-agent turns alike;
      `turn.started` does not carry one;
  (b) steer message-identity pairing — `prompt.steered` gained `messageId`;
      `turn.steer` gained `messageId`/`promptIds`/`turnId` and an
      `{"kind":"user","inTurn":true}` origin; the `context.spliced` steered
      message and history carry that same id and origin. A **single** steer
      reuses the queued prompt's own id (verbatim seq 15/18/19 on
      `session_516ea33e-…`, id `msg_01M2V0F8B40WBPS5J5QC2RPZJ9`); a
      **multi-prompt** steer mints a fresh ULID for the merged message
      (verbatim seq 106/116/117 on `session_cd550cb1-…`,
      `msg_01M2V1PDZGA1RG0KQKQ6659RSF` merging
      `msg_01M2V1PDS79PZE7QERNKYNY208` + `msg_01M2V1PDV6A77QW94DX088AMW9`).
      Sequence unchanged since 2.0.0: `prompt.steered` → step completes →
      `context.spliced` → `turn.steer` → new step in the same turn →
      `turn.ended` → `prompt.completed` with the original prompt id;
  (c) provider config/catalog fields (source-reviewed, routes out of scope):
      optional `api_key_env` on provider config values; required-nullable
      `base_url` on models.dev catalog provider items;
  (d) behavioral, wire shape unchanged — yolo auto-approves unanalyzable bash
      (`86e08208`; dangerous verdicts still ask), `:delete` no longer hangs
      (`e3f48a22`), search sync caps at 500 sessions/cycle (`5108cad9`);
  (e) undo behavior (TUI-only, source-derived from `sessions.test.ts`, not
      probed live): an `:undo` count of 2/3 reaching into a steered turn now
      answers `40911` instead of partially undoing.
  Re-verified unchanged, most byte-for-byte: auth 401s (missing + wrong
  token), `/api/v1/meta` (5 flags, 17 features — only `server_version`
  differs), the models catalog (10 entries), config (`default_model`
  `kimi-code/k3-256k`, `thinking.effort` `"max"`, `default_permission_mode`
  `"yolo"`), sessions create/get/list (`last_seq` `1` on a fresh detail, `0`
  on the list), `40401` without `stack`, `/status`, the snapshot (top-level
  keys, real `session.usage`, the sub-agent roster), the full prompt
  round-trip skeleton (no `streaming` phase), the questions flow end-to-end
  (pending poll, answer → `resolved:true`, re-answer/re-dismiss `40902`,
  dismiss `40909` + `dismissed:true`, all three `event.question.*` frames),
  the manual-approval round-trip (`approval_<uuid>` split, `tool_call_id`
  POST → `40404`, both resolved frames, the snapshot item), files (upload,
  download 200, `Range` 206, delete, `40407` at HTTP 404 after delete, the
  `session_media` projection with `name` in the ACK and history, the media
  copy surviving the upload's delete), the TodoList contract (verbatim
  `{"todos":[{status,title}]}`), foreground + background sub-agent lifecycles
  (verbatim `subagent.cancelled`, unchanged shape), REST `/tasks` list/get
  `with_output`, the v2 grouped session table, the v3 spot-check (WS hello
  `protocol_version:"3"` + `/history` `{messages, has_more}`), the lifecycle
  events (`event.session.created` at seq 1, `event.session.deleted` with
  camelCase `sessionId`), WS hello/subscribe/ack/cursor-resume
  (`protocol_version: 2`, `heartbeat_ms` 10000; replay of seqs 131–136 before
  the ack; future-seq cursor → standalone `resync_required` `epoch_changed`
  then the ack listing it), compact (instruction echo, wholesale splice with
  the id-less `compaction_summary` + `compaction_continuation`,
  `tokensAfter` 31496 == post-compact `context_tokens`, both `40910`s with
  `stack`), and the transcript's steered frames keeping `promptIds`. **App
  impact: none — every 2.0.1 change is additive on payloads the app either
  ignores wholesale (it never reads `prompt.steered`/`turn.steer`, and
  `TurnEndedPayload` reads only `reason`) or parses with
  `ignoreUnknownKeys`. No tickets filed.**
  Unit tests: `:app:testDebugUnitTest` green — 886 tests, 0 failures
  (task UP-TO-DATE against the current tree; counts from the JUnit XML of
  that execution).

- 2026-09-17 re-verification on `2.0.0` (server 0.43.1 → 2.0.0; throwaway
  sessions `session_2c247ce5-…` / `session_894f3d11-…` /
  `session_bfd7170d-…` created with `metadata.cwd=/home/ubuntu/spike`, all
  `:delete`d after; guided by the nightly job's `pending-changelog.md` — 29
  upstream commits between the tags, reviewed via the GitHub API; live probes
  over REST + websocat against `127.0.0.1:58627`; the wire-relevant commits
  are `7d174ac9` (subagent scope LRU + `subagent.cancelled`), `41eac5d2`
  (prompt `metadata` / clientMetadata), `2bb6e12c` (steer event order swap),
  `5653c739` (image upload as file refs — engine↔LLM internal,
  wire-invisible), `540133f0` (kosong optional media names — wire-invisible),
  and `1c7e996a`/`cafd9b5a`/`0cf413f7`/`bd061789` (transcript-projection
  fixes — history rendering internals); the rest are wire-invisible
  refactors).
  Drift found (all additive; nothing breaks the Android app — it ignores
  unknown event types/fields):
  (a) new WS event `subagent.cancelled` `{type, time, subagentId,
      agentId:"main", sessionId}` — fires on REST `:cancel` of a running
      foreground AND background sub-agent; previously cancels produced no
      terminal `subagent.*` event at all. Foreground sequence:
      `prompt.aborted` → `subagent.cancelled` → Agent `tool.result`
      (`status: failed`, `stop_reason: cancelled`, `isError:true`);
      background sequence: `subagent.cancelled` → `task.terminated`
      (`status:"killed"`) → `background.task.terminated` → task-origin
      notification turn + `task.notified` (`task.killed`). Post-cancel the
      snapshot roster entry reads `"status":"cancelled","subagent_phase":"cancelled"`
      (new live-observed phase) and REST `/tasks/{id}` shows
      `status:"cancelled"` (verbatim captures in Sub-agents);
  (b) prompt POST `metadata` field — arbitrary client JSON object echoed on
      the ACK as `data.metadata` and riding `prompt.submitted`/
      `prompt.queued` as `clientMetadata:[<metadata>]`, the
      `context.spliced` origin, history `metadata.origin.clientMetadata` and
      the transcript origin payloads; absent everywhere when not sent
      (verbatim frame in Prompts);
  (c) steer event order swap — `prompt.steered` now precedes `turn.steer`
      (observed seq 42 then 43; was the reverse since 0.43.0); everything
      else about steer unchanged;
  (d) `turn.step.completed` now routinely carries `llmClientBlockedMs`
      (schema-optional since 0.43.1; first live observation this run);
  (e) doc correction, not 2.0.0 drift — the legacy `GET /api/v1/files/{id}`
      answers HTTP 404 + `{"code":40407,"msg":"file not found"}` after
      delete (the media route the same); the route source is identical at
      both tags, the doc's old HTTP-200-envelope claim was wrong;
  (f) tools catalog content drift (server-config only, not wire shape) — 38
      tools on an initialized agent (28 builtins incl. `ReadMediaFile` + 10
      MCP: tavily gained `tavily_feedback`, kiln has 4), 37 on a fresh
      never-prompted session (27 builtins + 10 MCP).
  Re-verified unchanged, most byte-for-byte: auth 401s (missing + wrong
  token), `/api/v1/meta` (5 flags, 17 features — only `server_version`
  differs), the models catalog (10 entries), config (`default_model`
  `kimi-code/k3-256k`, `thinking.effort` `"max"`, `default_permission_mode`
  `"yolo"`), sessions create/get/list (`last_seq` `1` on a fresh detail, `0`
  on the list), `40401` without `stack`, `/status` (fresh session
  `thinking_level` `""`, `max_context_tokens` 262144), the snapshot
  (top-level keys, real `session.usage`), the full prompt round-trip
  skeleton (`session.meta.updated` → `prompt.submitted` → `turn.started`
  with `promptId` → `context.spliced` → `prompt.started` → steps → `tool.*`
  → `turn.ended` → `prompt.completed`; no `streaming` phase; `date_change`
  injection), mid-run and idle `:abort`, compact (instant ACK, instruction
  echo, wholesale splice with `compaction_summary` +
  `compaction_continuation`, `tokensAfter` == `context_tokens`, both
  `40910`s), questions end-to-end (pending poll, answer, dismiss `40909`,
  re-answer/re-dismiss `40902`, the `multi_select` omission rule), approvals
  (`approval_<uuid>` split, `tool_call_id` POST → `40404`, both resolved
  frames), files (sniffed `media_type` upload, download 200, `Range` 206,
  delete, `session_media` with `name`, the media copy surviving the upload's
  delete), the TodoList contract, foreground + background sub-agent
  lifecycles (`subagent.spawned` with `taskId`/`model`/
  `thinkingEffort:"max"`, `subagent.completed` with `resultSummary`/`usage`/
  `contextTokens`), REST `/tasks` list/get `with_output`, the v2 grouped
  session table, the v3 spot-check (WS hello `protocol_version:"3"` +
  `/history` `{messages, has_more}`), the lifecycle events
  (`event.session.created` at seq 1, `:delete` → `event.session.deleted`
  with camelCase `sessionId`), and WS hello/subscribe/ack/cursor-resume
  (`protocol_version: 2`, `heartbeat_ms` 10000; replay before the ack;
  future-seq cursor → standalone `resync_required` `epoch_changed` then the
  ack). **App impact: none — the data layer already parses
  `subagent.cancelled` defensively and maps roster phase `"cancelled"`;
  prompt `metadata`, the steer order swap and `llmClientBlockedMs` are all
  ignored safely (`ignoreUnknownKeys`). No tickets filed.**
  Unit tests: `:app:testDebugUnitTest` green — 886 tests, 0 failures,
  4 skipped (task UP-TO-DATE against the current tree; counts from the
  JUnit XML of that execution).

- 2026-09-15 re-verification on `0.43.1` (server 0.43.0 → 0.43.1; throwaway
  sessions `session_f067fe3d-…` and `session_dc97867b-…` in
  `/home/ubuntu/spike`, both `:delete`d after; guided by the upstream
  0.43.0→0.43.1 diff — 14 commits; the only core-loop-wire one is `1336be38`
  (indexed search results verified against session sources), the rest is
  wire-invisible here: `f2486241` (session event bus sharding — the v1
  broadcaster file and the bus publish path are byte-identical between the
  tags), `82ec469e` (swarm-cancel CLI crash), `0725ef1c` (tower roster
  identity, flag off here), `513e71ed` (`secondary_model` effort
  validation), `bb0c66a1` (managed-usage quota payload — touches the
  `/oauth` route, out of scope), `486dcd26` (pi-tui re-baseline),
  `26a28419` (web-dist sync)).
  Drift found: **none in shape**. The search fix verified live: a unique
  token planted in two sessions — after one was `:delete`d, the global
  index returned only the live session's hits (user + assistant) across two
  refresh cycles, and container search on the deleted session answered
  `code:0` with zero items. First-time itemizations (additive, predate
  0.43.1 in the engine source): the compact splice's trailing
  `compaction_continuation` `<system-reminder>` injection (no `id`, not
  persisted in history); sub-agent `prompt.completed` frames with internal
  prompt ids and `agentId:"agent-89"/"agent-90"` on the session channel
  (the behavior documented in Sub-agents — re-observed verbatim, no
  matching `prompt.submitted`); a single-select question item echoing the
  model's explicit `"multi_select":false`; and a cross-turn re-resolve of a
  resolved question answering `40405` where a same-turn re-resolve answers
  `40902` (the resolved-interaction registry evicts older entries — both
  codes are long documented, the app maps both generically). Re-verified
  unchanged, most byte-for-byte: auth 401s (missing + wrong token), meta (5
  flags, 17 features — byte-similar apart from `server_version`), models
  catalog (10 entries), config (`default_model` `kimi-code/k3-256k`,
  `thinking.effort` `"max"`, `default_permission_mode` `"yolo"`), sessions
  create/get/list (`last_seq` real on the detail path — `1` fresh; list
  still `0`), `40401` without `stack`, both compact `40910`s with `stack`
  (empty + busy), `/status`, the full snapshot (top-level keys, real
  `session.usage` omitting `total_cost_usd`/`turn_count`, busy
  `in_flight_turn` with `running_tools`, empty pending/roster lists),
  messages history (newest-first; `origin.attachments` on the attachment
  message; the `date_change` injection with today's `localDate` re-spliced
  on the session's first turn), prompts POST + ACK (`prompt_id` == `user_message_id`), one full
  prompt round-trip (the documented skeleton — `session.meta.updated` →
  `prompt.submitted` → `turn.started` with always-on `promptId` →
  `context.spliced` → `prompt.started` → steps/deltas →
  `tool.call.started`/`tool.progress`/`tool.result` → `turn.ended` →
  `prompt.completed`; no `streaming` phase), prompt queueing + steer
  (`prompt.queued` `queueLength:1`, `turn.steer` `{input, origin}`,
  `prompt.steered` with `activePromptId`/`promptIds`/`steeredAt`,
  `prompt.completed` with the original id), mid-run `:abort`
  (`turn.step.interrupted` → `turn.ended` `cancelled`/`user_cancelled` →
  interruption-reminder splice → `prompt.aborted`) plus idle `:abort`
  `{"aborted":true}`, compact with `{"instruction":…}` (instant empty ACK,
  instruction echo in `compaction.started`, wholesale `context.spliced` —
  summary without `id`, the prefix sentence intact — `compaction.completed`
  with the full `result` block, `tokensAfter` 30144 == post-compaction
  `context_tokens`, the persisted `compaction_summary` history message),
  the questions flow end-to-end (`question_<uuid>` ≠ `tool_call_id`,
  pending poll, `single` answer → `{"resolved":true}` with the label
  flattened, dismiss `40909`, re-dismiss/re-answer `40902`, all three
  `event.question.*`), the manual-approval round-trip (`approval_<uuid>` ≠
  `tool_call_id` on both frames and the snapshot `pending_approvals` item,
  `tool_call_id` POST → `40404`, `approved` → both resolved frames), files
  upload (sniffed `media_type`)/download/`Range` 206/delete/`40407` after
  delete + the image `session_media` projection with `name` in the ACK echo
  AND history + the upload-id reuse + the media copy surviving the upload's
  delete, foreground + background sub-agents (full lifecycles:
  `subagent.spawned` with `taskId`/`model`/`thinkingEffort:"max"`/
  `runInBackground`, `subagent.started`/`completed`, `task.started` →
  `background.task.started` → `task.terminated` →
  `background.task.terminated`, mid-turn notification splice +
  `task.notified`, REST `/tasks` list with `run_in_background`/`agent_id`/
  `subagent_type`/`parent_tool_call_id`), the TodoList contract
  (`{"todos":[{status,title}…]}` verbatim), the tools catalog (37 on the
  initialized agent — 28 builtins incl. `ReadMediaFile` + 9 MCP; 36 on a
  fresh never-prompted session, the documented liveness caveat), the v2
  grouped session table, the v3 spot-check (`/api/v3/ws` hello
  `protocol_version:"3"` + `GET .../history` `{messages, has_more}`), the
  session lifecycle events (`event.session.created` on the new session's
  own stream at `seq:1`, `:delete` → `{"deleted":true}` + global
  `event.session.deleted` with camelCase `sessionId`), and WS
  hello/heartbeat/subscribe/ack/cursor-resume (10 s `ping`s; replay from
  seq 236 re-delivered 237+ in order before the ack; future-seq cursor →
  standalone `resync_required` `epoch_changed` then the ack listing it).
  **App impact: no breakage, no new tickets** — the only wire change is on
  a route the app never calls, and every first-time itemization lands in
  territory the app already tolerates (unknown `prompt.*` frames ignored,
  reminder envelopes stripped, `multi_select` decoded optional).
  `:app:testDebugUnitTest` — 805 tests, 0 failures (4 skipped). Tooling:
  websocat with a fifo pong feeder whose writer fd stays open for the
  feeder's lifetime — the open-per-write variant races websocat into an
  stdin-EOF disconnect mid-capture. Watch item re-confirmed: a prompt-level
  `permission_mode:"manual"` stays sticky for the session's later turns
  (reset via `POST .../profile`).

- 2026-09-14 re-verification on `0.43.0` (server 0.42.0 → 0.43.0;
  throwaway sessions `session_c0ea7090-…` and `session_85f66878-…` in
  `/home/ubuntu/spike`, both `:delete`d after; guided by the upstream
  0.42.0→0.43.0 diff — 38 commits; the wire-relevant ones are `64505e36`
  (v3 WS + history API), `56ed9549` (`streaming` phase kind removed),
  `46233953` (prompt-queue fold: `40903` + prompt-POST auth prechecks
  removed, `turn.steer` emitted), `2d4d799a`/`cd0d4a1d` (hidden `think`
  summary parts omitted from v1 history), `6126472c` (`auto_session_title`
  graduated) and `9296e687` (`dynamically_loaded_tools` capability).
  Drift found and documented inline in the 0.42.0→0.43.0 section above:
  (a) additive opt-in v3 protocol (`WS /api/v3/ws` hello +
      `GET .../history`; v2 byte-untouched);
  (b) `agent.status.updated` `streaming` phase kind gone — zero observed
      across this run's round-trips;
  (c) `40903` gone — idle `:abort` answers `{"aborted":true}`;
  (d) new `turn.steer` frame before `prompt.steered` (`{input, origin}`);
  (e) `40401` envelopes without `stack` (`40910` still has it);
  (f) new `date_change` injection origin variant with `disclosure`;
  (g) foreground Agent tool-result prose gained `stop_reason:` +
      `resume_hint:` lines.
  Re-verified unchanged, most byte-for-byte: meta (5 flags now), models
  catalog shape (10 entries, content churn only), config
  (`default_model` `kimi-code/k3-256k`), sessions create/get/list/
  `:delete`/`event.session.deleted`, `/status`, the full snapshot
  (top-level keys, `session.usage`, busy `in_flight_turn`, empty
  pending/roster lists), messages history (newest-first, media `name`,
  hidden-`think` omission not observable with kimi-code/k3), one full
  prompt round-trip (verbatim WS capture — no `streaming` phase), prompt
  queueing + steer end-to-end, mid-run `:abort` (full interrupted
  sequence), compact with `{"instruction":…}` (instant ACK, instruction
  echo, wholesale splice with the 0.42.0 prefix sentence, summary message
  without `id`, `tokensAfter` == `context_tokens`, both `40910`s with
  `stack`), the questions flow end-to-end (`question_<uuid>` ≠
  `tool_call_id`, pending poll, answer/dismiss, `40909`/`40902`, all
  three WS events), manual approvals (`approval_<uuid>` ≠ `tool_call_id`,
  tool_call_id POST → `40404`, both resolved frames, snapshot
  `pending_approvals`), files upload/download(`Range` 206)/delete
  (`40407`) plus the image-attachment `session_media` route (ACK and
  history `name` fields intact), sub-agents foreground + background
  (full lifecycles, REST `/tasks` all fields, running foreground listed,
  background notification both mid-turn splice and idle-path task-origin
  turn), the TodoList write contract verbatim, the tools catalog (37:
  28 builtin + 9 MCP; `NotifyUser` still gated off), the v2 grouped
  session table, workspaces + trust, WS hello/heartbeat/subscribe/ack/
  cursor-resume (replayed 213–217 in order before the ack; refused cursor
  → standalone `resync_required` then ack). **App impact: no breakage, no
  new tickets** — the app never decoded `streaming` phases, never
  referenced `40903`, ignores unknown frames (`turn.steer`), strips
  `<system-reminder>` envelopes by tag (`date_change`), treats thinking
  parts as optional, and maps error codes without reading `stack`.
  `:app:testDebugUnitTest` — 789 tests, 0 failures (4 skipped). Tooling:
  `websocat` with a fifo pong feeder (`-H` headers after the URL).
- 2026-09-14 spot verification on `0.42.0` (ticket 127; throwaway sessions
  `session_d6c12410-…` + its forks in `/home/ubuntu/spike`, all deleted
  after): `POST /api/v1/sessions/{id}:fork` verified end-to-end — `{}` body,
  the standard session envelope, the `Fork: ` default title, workspace/cwd
  inheritance, the full history copy (7/7 messages on the fork), the
  inherited-`updated_at` quirk (predating `created_at`), `40401` on an
  unknown id and the `40901` busy error (verbatim captures in Sessions →
  Fork).

- 2026-09-09 re-verification on `0.42.0` (server 0.41.0 → 0.42.0; throwaway
  sessions `session_1082c900-…` / `session_0e2d9419-…` /
  `session_fee8aac1-…` in `/home/ubuntu/spike`, guided by the upstream
  0.41.0→0.42.0 diff — 55 commits; the wire-relevant ones are `fa87d040`
  (session deletion + `event.session.deleted`), `baf17a8f` (media
  attachment names), `80480c01` (HEIC/HEIF/BMP engine acceptance),
  `f0434f2d`/`e831fd1e` (experimental-flag graduation), `55685c58`
  (context-budget removal), `f12d59e0` (compaction resumption anchor +
  prefix sentence), `f6a9c39e` (`NotifyUser` tool behind the `notify_user`
  flag) and `9188d03c` (`packages/protocol` deleted, types moved into
  `agent-core-v2`; the huge `485594f6` human-layer/loop-facade refactor is
  wire-invisible). Drift found and documented inline:
  (a) **`POST /api/v1/sessions/{id}:delete` is live** —
      `{"deleted":true}`, unknown id `40401` (with `stack`), bare `DELETE`
      method still plain 404, deleted session drops off the list and get →
      `40401`;
  (b) **new global `event.session.deleted`** — verbatim capture (payload
      `workspace_id` + camelCase `sessionId` on the `__global__`
      envelope); **the app's session-list socket ignores it — ticket
      126**;
  (c) **image/video content parts carry optional `name`** in the prompt ACK
      echo AND history (`"name":"one.png"` verbatim, both surfaces);
  (d) **`/meta`:** `experimental_flags` down to 6 keys
      (`remote-control`/`persistence_minidb_readmodel`/`search_worker`/
      `secondary-model` gone, `notify_user` new), `features` down to 17
      (`contextBudget` and `interaction` gone, `notify` new);
  (e) **compaction splice prefix gained** "The summary records which
      earlier requests were already addressed." (verbatim capture);
      summarizer instruction reworded — shapes/sequence unchanged;
  (f) **tools catalog: 37 entries** (28 builtins — `ReadMediaFile` back —
      plus 9 MCP); `NotifyUser` NOT advertised (flag off); the 0.31.1
      empty-catalog liveness caveat re-observed right after session
      create;
  (g) BMP uploads still sniff as `application/octet-stream`, so the new
      BMP/HEIC acceptance is unreachable via the upload flow (image part →
      `40001` "not an image", same as video).
  Config drift (not shape): `default_model` is `kimi-code/kimi-for-coding`
  again (fresh-session `max_context_tokens` 262144), `thinking.effort`
  still `"max"`, models catalog still 10 entries, new `database` config
  section.
  Re-verified unchanged, most byte-for-byte: auth 401s and `40401`+
  `stack` envelopes, v1 sessions create/get/list (`last_seq` real on the
  detail path — `147` after this run's turns; list still `0`),
  `message_count` still stuck at 0, the full snapshot (top-level keys,
  real `session.usage` omitting `total_cost_usd`/`turn_count`, busy
  `in_flight_turn` with `running_tools`, empty pending/roster lists at the
  watermark), messages history (newest-first; media parts with `name`),
  prompts POST + ACK (`prompt_id` == `user_message_id`), prompt queueing +
  steer (`"status":"queued"` ACK, `{"steered":true}`, one `prompt.steered`
  frame), `:abort` (`{"aborted":true}`, `prompt.aborted`, the
  interruption-reminder path visible in the replay), compact `{}` and
  `{"instruction":…}` (instant empty ACKs, instruction echo in
  `compaction.started`, `compaction.completed` `result` block, the
  empty-session `40910` with `stack`), the questions flow end-to-end
  (`question_<uuid>` + distinct `tool_call_id`, `q_0`/`opt_0_N` ids,
  pending poll, `single` answer → `{"resolved":true}`), the
  manual-approval round-trip (`approval_<uuid>` ≠ `tool_call_id` on the
  legacy frame, `tool_call_id` POST → `40404`, `approved` → both resolved
  frames), files upload (sniffed `media_type`)/download/`Range` 206/
  delete/`40407` after delete, foreground + background sub-agents (full
  lifecycles: `subagent.spawned`/`started`/`completed`, `task.started` →
  `background.task.started` → `task.terminated` → `background.task.terminated`,
  `task.notified`, the task-origin notification turn; `model`/
  `thinking_effort` `"max"` throughout), REST `/tasks` list (terminal
  background sub-agent with all 0.39.0/0.36.1 fields; the completed
  foreground one dropped), the TodoList contract (verbatim
  `{"todos":[{status,title}…]}`, 14 frames), the v2 grouped session table,
  and WS hello/heartbeat/subscribe/ack/cursor-resume (replay from seq 57
  re-delivered 58–132 in order before the ack; future-seq cursor →
  standalone `resync_required` then the ack listing it).
  **App impact: one gap — ticket 126** (`event.session.deleted` handling);
  everything else is additive into `ignoreUnknownKeys` territory
  (`name`, flags/features) or uncalled routes (`:delete`). Unit tests
  (`:app:testDebugUnitTest --rerun`): **753 tests, 0 failures**.
  Tooling note: websocat kept alive by a 6 s `pong` feeder, as since the
  0.40.1 run.

- 2026-09-04 re-verification on `0.41.0` (server 0.40.1 → 0.41.0; throwaway
  sessions `session_40856c6d-…` / `session_00747c46-…` in
  `/home/ubuntu/spike`, guided by the upstream 0.40.1→0.41.0 diff — 30
  commits; the wire-relevant ones are `eba23edb` (file-history experimental
  gate dropped), `523d35b5` (background questions survive turn end + inline
  answers), `b6b9b374` (handoff step after forced stops + sub-agent stop
  reasons), `b184b314` (dangerous-command guard dropped in auto mode),
  `a0209469` (staleGuard feature removed) and `b199e332` (contextBudget
  feature + new compaction instruction). `packages/protocol` untouched; the
  rest is telemetry/CLI/TUI/web-bundle churn).
  Drift found and documented inline:
  (a) **`/meta`:** `experimental_flags` dropped `file_history`; `features`
      reads 19 entries — `staleGuard` gone, `contextBudget` new — verified
      live;
  (b) **file-history routes live now** — `GET …/file-history/changes`
      answers `{"changes":[],"recorded":false}` on an unrecorded turn; the
      shipped schema has no `enabled` field — verified live;
  (c) **background questions survive turn end** — pending question outlived
      `main_turn_active:false` (session `busy:true` +
      `pending_interaction:"question"`), the answer came back as a
      task-origin notification turn (`task.terminated` core kind `question`,
      `task.notified` "Background question answered", `turn.started` origin
      `{"kind":"task","taskId":"question-…"}`), REST `/tasks` lists it as
      kind `tool` — all verified live end-to-end;
  (d) **transcript `triggerPromptId` gone from sub-agent internal turns**
      (main user turns keep it) — verified live on three sub-agent
      transcripts;
  (e) `turn.ended` omits the new optional `stopReason` on completed AND
      cancelled turns (forced-stop-only field); repeat-breaker
      `stopReason` on completed sub-agent tasks and the engine `stopCode`
      are source-derived only — verified live that neither appears on
      normal completions;
  (f) behavioral: dangerous-command guard skipped in auto mode
      (source-verified); compaction summaries now open with "Handoff note
      to myself:" (new instruction file — content drift; the splice prefix
      is byte-identical).
  Config drift (not shape): the models catalog reads 10 entries (two new
  `Kimi Platform/*` providers).
  Re-verified unchanged, most byte-for-byte: auth 401s (missing + wrong
  token), public healthz, config shape (`default_model` `kimi-code/k3-256k`,
  `thinking.effort` `"max"`), workspaces list, v1 sessions create/get/list
  (`last_seq` real on the detail path — fresh reads `1`, after turns `20`;
  list still `0`), `/status` (fresh `thinking_level:""`, all mode flags),
  the full snapshot (top-level keys, real `session.usage` omitting
  `total_cost_usd`/`turn_count`, oldest-first message page,
  `in_flight_turn` busy capture with `running_tools`, running foreground
  roster entry with `model`/`thinking_effort`), messages history, prompts
  POST ACK, one full prompt round-trip (`prompt.submitted` → `turn.started`
  with always-on `promptId` → `context.spliced` → `prompt.started` →
  steps/deltas → `tool.call.started`/`tool.progress`/`tool.result` →
  `turn.ended` → `prompt.completed`), prompt queueing + steer
  (`prompt.queued` `queueLength`, `prompt.steered` provenance, transcript
  `prompts[]` `steeredAt` + steered-content rewrite + `promptIds` text
  frame), `:abort` (`turn.step.interrupted` → `turn.ended`
  `cancelled`/`user_cancelled` → interruption-reminder splice →
  `prompt.aborted`), compact `{}` and `{"instruction":…}` (instant empty
  ACK, instruction echo in `compaction.started`, wholesale
  `context.spliced` — summary message still without an `id` key —,
  `compaction.completed` with the full `result` block,
  `tokensAfter`==post-compaction `context_tokens`; both `40910`s with
  `stack`), the foreground questions flow end-to-end (pending poll with
  `question_<uuid>` + distinct `tool_call_id`, `single` answer, dismiss
  `40909`, re-answer/re-dismiss `40902`), the manual-approval round-trip
  (`approval_<uuid>` ≠ `tool_call_id` on all three surfaces, `tool_call_id`
  POST → `40404`, `approved` → both resolved frames), files
  upload/download/`Range` 206/delete + `40407` on both not-found routes + a
  `path` file attachment (notice wording and `origin.attachments`
  byte-identical), foreground + background sub-agents (full lifecycles,
  spawn order `task.started` → `background.task.started` →
  `subagent.spawned` → `subagent.started`, `model`/`thinkingEffort`
  everywhere), REST `/tasks` list/get + `40406`, the TodoList
  `tool.call.started` contract, the v2 grouped session table, the tools
  catalog (27 builtins + 9 MCP), and WS hello/heartbeat/subscribe/ack/
  cursor-resume (replay from seq 150 re-delivered 151+ in order before the
  ack; future-seq cursor → standalone `resync_required` then the ack
  listing it).
  **App impact: no breakage, no new tickets** — every drifted shape is
  either additive into payloads the app never decodes (file-history routes,
  `/meta` flags/features, `turn.ended.stopReason`) or ignored by the app's
  `ignoreUnknownKeys` parsing; the app already decodes the optional task
  `stopReason`; the background-question states and events reuse flows the
  app already handles (pending-question surfacing, task-origin notification
  turns). Tooling note: websocat kept alive by a 6 s `pong` feeder, as
  introduced in the 0.40.1 run. Watch item: a prompt-level
  `permission_mode:"manual"` bound the throwaway session to manual for its
  remaining turns (later plain `echo` calls awaited approval) — the
  per-prompt override is sticky, as documented in Prompts.

- 2026-09-02 re-verification on `0.40.1` (server 0.39.1 → 0.40.1; throwaway
  sessions `session_068cac46-…` / `session_95b95798-…` in
  `/home/ubuntu/spike`, guided by the upstream 0.39.1→0.40.1 diff — 56
  commits; the wire-relevant ones are `0f39b2cf` (getSession `last_seq`),
  `a23ff563` (`/workspaces/{id}/add-dir`), `cbe0a77f`/`58b74cfe`
  (transcript prompt identity + steer provenance), `742e1197`/`9e881528`
  (durable step-interrupt / prompt-resolution records), `971a8b24`
  (`secondary-model` graduated to default-on), `650d12f6` (experimental
  file-history routes + flag), `c3bf6f9d` (`tower_base` schema line), and
  `4b9888b7` (dangerous-bash approval in all modes, behavioral).
  `packages/protocol` otherwise untouched; the CLI permission-mode rename
  (`08a2fb00`) is labels-only — the wire enum stays `manual`/`yolo`/`auto`).
  Drift found and documented inline:
  (a) **get-one `last_seq` is the real watermark now** (fresh session `4`,
      after two turns `20`; list/create still `0`) — verified live;
  (b) new route `POST /api/v1/workspaces/{id}/add-dir` — success
      (`project_root`/`config_path`/`additional_dirs`/`persisted`),
      relative-path and `~` resolution, `persist:false`, `40001` empty
      path, `40409` missing path, `40410` unknown workspace — all verified
      live;
  (c) transcript: `triggerPromptId` on every turn (main AND sub-agent),
      `origin` on user text frames (seen on the steered frame), `steeredAt`
      on `prompts[]` entries, the steered-into prompt's `content` showing
      the steered text — all verified live;
  (d) WS `turn.started` carries `promptId` even without a client-sent
      `prompt_id` — verified live on the round-trip;
  (e) file-not-found moved `40406` → `40407` on BOTH the legacy files route
      (after-delete download) and the session-media route (unknown id) —
      verified live; `40406` still serves unknown tasks;
  (f) `/meta`: `experimental_flags` +`file_history:false`,
      `secondary-model` now `true`; `features` +`fileHistory` (18).
  Config drift (not shape): `default_model` is `kimi-code/k3-256k`
  (fresh-session `/status` `max_context_tokens` back to 262144),
  `thinking.effort` is `"max"` (so `thinking_level`/`thinkingEffort`/
  sub-agent `thinking_effort` all read `"max"` this run).
  Re-verified unchanged, most byte-for-byte: auth 401s (missing + wrong
  token), meta otherwise, models catalog (27 builtins incl. `select_tools`
  + 4 MCP entries), workspaces list, v1 sessions create/get (object shape,
  `agent_config.model` real after a prompt), `/status` (fresh
  `thinking_level:""`, all mode flags present), the full snapshot
  (top-level keys, real `session.usage` omitting `total_cost_usd`/
  `turn_count`, oldest-first message page, `in_flight_turn` busy capture,
  `pending_approvals` legacy-shape item, `pending_questions` wire-question
  item, running foreground roster entry with `model`/`thinking_effort`),
  messages history (envelope, `metadata.origin`, newest-first, sequential
  ids), prompts POST ACK (`prompt_id` == `user_message_id`), one full
  prompt round-trip (`session.meta.updated` → `prompt.submitted` →
  `turn.started` → `context.spliced` → `prompt.started` →
  `turn.step.started` → deltas → `tool.call.started`/`tool.progress`/
  `tool.result` → `turn.step.completed` → `turn.ended` →
  `prompt.completed`), prompt queueing + steer (`"status":"queued"` ACK,
  `prompt.queued` with `queueLength`, `prompt.steered` with
  `activePromptId`/`promptIds`/`content`/`steeredAt`, `{"steered":true}`
  steer ACK), `:abort` (`{"aborted":true}` ACK, `interrupted` phase,
  `turn.ended` `reason:"cancelled"` + `interruptReason:"user_cancelled"`,
  `prompt.aborted`), compact `{}` and `{"instruction":…}` (instant empty
  ACK, `compaction.started` `trigger:"manual"` + instruction echo →
  `context.spliced` wholesale rewrite → `compaction.completed` with
  `result.summary`/`compactedCount`/`tokensBefore`/`tokensAfter`/
  `keptUserMessageCount`; both `40910`s with `stack` — empty session "No
  messages to compact…", busy/approval-pending session "Cannot compact
  while a turn is active…"), the questions flow end-to-end (pending poll
  with `question_<uuid>` + distinct `tool_call_id` + stable `q_0`/`opt_0_N`
  ids and `multi_select` omitted when false, `single` answer →
  `event.question.answered` with the label flattened, dismiss `40909`,
  re-dismiss `40902`, all three `event.question.*`), the manual-approval
  round-trip (`permission.approval.requested` + `event.approval.requested`
  with the dedicated `approval_<uuid>` ≠ `tool_call_id`, the
  `awaiting_approval` phase carrying both ids, `tool_call_id` decision POST
  → `40404`, `approved` decision → both resolved frames), files
  upload/download/`Range` 206/delete, a `path` file attachment (rewrites to
  the `Attached file "…"` notice naming the original path), foreground +
  background sub-agents (`agent.created`, `subagent.spawned` with
  `taskId`/`runInBackground`/`model`/`thinkingEffort`, `subagent.started`,
  `task.started`/`background.task.started`, `subagent.completed`,
  `task.terminated`/`background.task.terminated`, `task.notified`), REST
  `/tasks` list/get (running foreground + terminal background, all the
  0.39.0 fields) + `:cancel` (`{"cancelled":true}` → status `cancelled`;
  terminal repeat → `40904` with `details.current_status`), the TodoList
  `tool.call.started` contract (`{"todos":[{status,title}…]}`), the v2
  grouped session table (`groups[]` + `activity.model`), WS
  hello/heartbeat/subscribe/ack/cursor-resume (replay from seq 165
  re-delivered 166–170 in order before the ack; future-seq cursor →
  standalone `resync_required` then the ack listing it), and
  `event.config.changed` on a real config write (a no-op write emits no
  frame; config restored byte-identical).
  **App impact: no breakage, no new tickets** — every drifted shape is
  either additive into payloads the app never decodes (`last_seq`,
  transcript additions, `turn.started.promptId`, `tower_base`, `/meta`
  flags/features; the data layer runs `ignoreUnknownKeys` everywhere) or a
  code path the app never inspects (the file-not-found envelope code — the
  app maps the binary 404 without reading it; `add-dir` is uncalled). The
  permission-mode rename never reached the wire. The dangerous-bash guard
  only means *more* approval requests in yolo/auto — a flow the app already
  handles. Unit tests (`:app:testDebugUnitTest`) pass unmodified.
  Tooling note: websocat kept alive by a 6 s `pong` feeder (the 0.35.0
  heartbeat kills silent listeners after ~20 s — a subscribe-only pipe
  truncated one capture mid-turn this run before the feeder was added).

- 2026-08-28 re-verification on `0.39.1` (server 0.39.0 → 0.39.1; throwaway
  sessions `session_62714470-…` / `session_9565d62c-…` in
  `/home/ubuntu/spike`, guided by the upstream 0.39.0→0.39.1 diff — 11
  commits; the wire-relevant ones are `4e7738b7` (server-local path
  attachments + prompt-origin attachment records) and `2bf7ed22` (`/auth`
  `models_ready`, session-list `model`, config-event delivery);
  `dc6028dc` (cold-transcript steer folding) and `23921e9f` (resumed-task
  reminder) are behavioral/engine-internal). Drift found and documented
  inline:
  (a) **`GET /api/v1/auth` dropped `ready`/`default_model` for
      `models_ready`** (verified live) — breaking upstream, but the app
      never calls `/auth`;
  (b) server-local path attachments: `path` source kind for image/video,
      `file` part `path` variant (metadata optional), absolute-path `40001`
      (Error2, with `stack`), missing-path `40407`, both-keys `40001`;
      path files rewrite to the usual `Attached file "…"` notice naming the
      original path; path images project as `session_media`; the sensitive-
      file filter did NOT reject `server.token`;
  (c) prompt attachments recorded on the origin — `metadata.origin
      .attachments` (history), `origin.attachments` (splice echo and
      `turn.started`), typed file entries (no `source`) in transcript
      `attachments[]`; `turn.started.promptAttachments` still never emitted;
  (d) v1 session list/get `agent_config.model` serves the live bound model
      (was hardcoded `""`); v2 `activity.model` added (`""` when unset);
  (e) `event.config.changed` now delivered on config writes (global frame,
      camelCase `changedFields`, full redacted config projection — verified
      live, config restored byte-identical); `event.model_catalog.changed`
      forwarded too (not triggered); `event.config.warning` schema-
      registered (delivered since 0.32.0).
  Config drift (not shape): `default_model` is `kimi-code/k3` again (was
  `deepseek/deepseek-v4-pro` since 0.37.2) — fresh-session `/status`
  `max_context_tokens` reads 1048576.
  Re-verified unchanged, most byte-for-byte: meta (flags + 17 features
  unchanged), auth 401s, v1 sessions list/create/get, `/status` (fresh
  `thinking_level:""`, `max_context_tokens` present, `tower_mode:false`),
  the full snapshot (top-level keys, real `session.usage` omitting
  `total_cost_usd`/`turn_count`, oldest-first message page), messages
  history, one full prompt round-trip with a manual-permission tool call
  (`prompt.submitted` → `turn.started` → `context.spliced` →
  `permission.approval.requested`/`event.approval.requested` — the 0.39.0
  `approval_<uuid>` split intact, REST `approved` decision, posting the
  `tool_call_id` still `40404` — → `tool.result` → `turn.ended` →
  `prompt.completed`), prompt queueing + steer (`prompt.queued`
  `queueLength`, `{"steered":true}` ACK), `:abort` (`turn.ended`
  `reason:"cancelled"` + `interruptReason:"user_cancelled"` → interruption-
  reminder splice → `prompt.aborted`), compact `{}` and
  `{"instruction":…}` (`compaction.started` instruction echo →
  `context.spliced` → `compaction.completed` with `result.summary`; both
  `40910`s with `stack`), the questions flow end-to-end (pending poll with
  `question_<uuid>` + distinct `tool_call_id` + stable `q_0`/`opt_0_N` ids,
  `{"answers":{"q_0":{"kind":"single","option_id":…}}}` answer, dismiss
  `40909`, re-dismiss `40902`, all three `event.question.*`), files
  upload/download/`Range` 206/delete, foreground + background sub-agents
  (`subagent.spawned` with `taskId`/`model`/`thinkingEffort`,
  `task.started`/`background.task.started`/`…terminated`, `task.notified`,
  REST `/tasks` list/get with `run_in_background`/`agent_id`/
  `subagent_type`/`parent_tool_call_id`), the TodoList `tool.call.started`
  contract, and WS hello/heartbeat/subscribe/ack/cursor-resume (replay from
  seq 190 re-delivered 191+ in order). The 0.39.0 background-subagent WS
  stall did NOT recur (frames kept flowing through the background
  lifecycle). **App impact: no breakage, no new tickets** — `/auth` is
  unused, `origin.attachments`/transcript attachments/config events are
  additive and ignored (`ignoreUnknownKeys` + unknown-frame ignore), and
  real `agent_config.model` values only improve the app's model fallback.
  Tooling note: websocat kept alive by a 5 s `pong` feeder (any inbound
  frame resets the heartbeat timer).

- 2026-08-27 re-verification on `0.39.0` (server 0.38.0 → 0.39.0; throwaway
  session `session_36cdf3cf-…` in `/home/ubuntu/spike`, guided by the upstream
  0.38.0→0.39.0 diff — 66 commits; the wire-relevant ones are `6595955b`
  (`run_in_background` on the task wire), `692bb0a4` (task `:detach` +
  `parent_tool_call_id` on process tasks), `b3f08b68` (wire question shape in
  transcript interaction entities), `5634cb55` (steered messages as live user
  frames + `promptIds`), `2f124693` (transcript `prompts` entities + task
  `model`/`thinkingEffort`), `f143130c` (orchestrator `prompt` on sub-agent
  turns), `c4885860` (undone turns dropped from the live projection),
  `d723cc47` (MCP tools in the catalog + `/mcp/servers` routes),
  `0f44537c`/`d3b27cc7` (`tower_mode` + flag), `f6736d7c` (`Agent` `fork`,
  schema-only here) and `f0a60948` (`remote-control` flag)). Drift found and
  documented inline — all additive:
  (a) **approval-id split** — `approval_<uuid>` everywhere (v2 `id`, legacy
      `approval_id`, `awaiting_approval` phase), distinct from
      `tool_call_id`; the decision POST accepts only the `approval_<uuid>`
      form (`tool_call_id` → `40404`, verified live). The app keys off
      `approval_id` throughout — transparent, no ticket;
  (b) `/status` gained `tower_mode: false`;
  (c) REST `/tasks` gained `run_in_background` on every object (verified on a
      running foreground and a completed background sub-agent) and the
      `:detach` action (verified: `{"detached":true,"status":"running"}` on a
      running foreground task, `{"detached":false,…}` on the repeat; detach
      resolves the Agent tool call with a stub, emits
      `task.started`/`background.task.started`, then the full background
      lifecycle including the notification turn);
  (d) transcript: top-level `prompts` entities, steered mid-turn user frames
      with `promptIds`, wire-shaped pending-question `interactions[].request`
      (verified while pending), task `model`/`thinkingEffort`, sub-agent
      turns carrying the orchestrator prompt;
  (e) new WS `prompt.started`; `prompt.submitted`/`prompt.queued` confirmed
      live (they exist since at least 0.38.0 — this document's "does not
      exist" claim was stale);
  (f) tools catalog lists 9 MCP tools (`source:"mcp"`, `mcp_server_id`) next
      to the 27 builtins; `GET /api/v1/mcp/servers` verified
      (`tavily`+`kiln`, both `connected`);
  (g) `/meta` flags +`subagent_fork`/`remote-control`/`tower`, features
      +`skill`/`cron`/`interaction`/`reminder` (17).
  Re-verified unchanged, most byte-for-byte: v1 sessions list/create/get
  (list/get `usage` still the all-zero placeholder even after turns), the
  session object, `/status` otherwise, the full snapshot (top-level keys,
  `session.usage` real fill omitting `total_cost_usd`/`turn_count`,
  `agent_config`, `in_flight_turn`, roster), messages history (envelope,
  `metadata.origin`, newest-first), prompts POST ACK (`prompt_id` ==
  `user_message_id`), one full prompt round-trip (`agent.status.updated` →
  `session.meta.updated` → `prompt.submitted` → `turn.started` →
  `context.spliced` → deltas → `turn.step.*` → `turn.ended` →
  `prompt.completed`; `turn.started` still emits NO `promptAttachments`
  live), prompt queueing + steer (`prompt.queued` with `queueLength`,
  `prompt.steered`, same-turn continuation, `prompt.completed` with the
  original id), `:abort` (`turn.step.interrupted` → `turn.ended`
  `interruptReason:"user_cancelled"` → interruption-reminder splice → idle
  `work_changed` → `prompt.aborted`), compact `{}` and
  `{"instruction":…}` (ACK, `compaction.started` `trigger:"manual"` →
  `context.spliced` → `compaction.completed` with `result.summary`; both
  `40910`s — empty session "No messages to compact…", busy session "Cannot
  compact while a turn is active…"), the questions flow end-to-end
  (surface + `pending_interaction:"question"` + pending poll + `single`
  answer + dismiss `40909` + all three `event.question.*` — the answer
  `kind`-union predates 0.39.0; the legacy `{"selected":[…]}` body is
  rejected `40001`), the manual-approval round-trip (id split above),
  files upload/download/session-media/delete (69-byte png byte-identical on
  all routes; ACK + history project `session_media`), foreground +
  background sub-agents (`subagent.spawned` with `taskId`,
  `runInBackground`, `model`/`thinkingEffort`), REST `/tasks`
  list/get/`:cancel` shapes, the TodoList `tool.call.started` contract,
  WS hello/heartbeat/subscribe/ack/cursor-resume (replay from seq 14
  re-delivered `event.question.requested`), and `event.session.archived`
  (`__global__` envelope, camelCase `sessionId`).
  **App impact: no breakage, no new tickets** — every change is additive and
  the app decodes leniently (`ignoreUnknownKeys`); the approval split is
  transparent (the app keys off `approval_id`), the transcript additions land
  in payloads the app does not decode, and the steered-user frames only
  appear in the main agent's transcript, which no app screen renders.
  Tooling note: verification used a stdlib-python WS client answering the
  app-level heartbeat pings — websocat's static stdin gets the connection
  killed after ~2 ping cycles, which silently truncated earlier captures.
- 2026-08-20 re-verification on `0.38.0` (server 0.37.2 → 0.38.0; throwaway
  sessions in `/home/ubuntu/spike`, guided by the upstream 0.37.2→0.38.0 diff
  — 38 commits; the wire-relevant ones are `cb8a7e5f` (/meta `features`),
  `15da8460` (v2 grouped view + lifecycle events), `4ff06f17` (real snapshot
  usage + optional usage fields + `/status` `context_usage` optional),
  `38a5a934` (`turn.started.promptAttachments`, schema-only live), and
  `be8e0175`/`3b69765c` (`subagent.spawned` `taskId` + ordering)). Drift found
  and documented inline:
  (a) `/meta` gained `features` and the `wait_for` flag;
  (b) `/api/v2/sessions` gained `meta.has_prompt` / `view=by_workspace` /
      `group.page_size` (grouped page verified live; the v2 item `meta` now
      carries `archived_at:null` when never archived);
  (c) new global `event.session.archived` (verified live — envelope
      `session_id:"__global__"`, payload `workspace_id` + camelCase
      `sessionId`; observed only on connections that had sent at least one
      inbound frame);
  (d) snapshot `session.usage` is REAL now and omits `total_cost_usd` /
      `turn_count`; `session.agent_config` added (verified live);
  (e) **`question_id` split from `tool_call_id`** — pending poll and
      `event.question.requested` now carry a dedicated `question_<uuid>` id
      plus a distinct `tool_call_id` (verified live end-to-end: surface,
      record-body answer, dismiss `40909`, both events); answer/dismiss routes
      keyed on `question_id` work unchanged — ticket 106 covers the app's
      row-correlation reliance on the old equality, ticket 105 covers the
      unhandled `event.session.archived`;
  (f) `subagent.spawned` carries `taskId` on foreground AND background spawns
      and fires after task registration (verified live); every event payload
      family now emits a top-level epoch-ms `time`.
  Re-verified unchanged, most byte-for-byte: auth 401s, v1 sessions
  list/create/get (`usage` still the all-zero eight-field placeholder on the
  list path), `/status` (k3 session: `context_usage` present,
  `max_context_tokens:1048576`), snapshot top-level keys + roster shape,
  messages history (envelope, newest-first, `session_media` image projection),
  prompts POST ACK, prompt round-trip event order, image upload → ACK/history
  `session_media` projection → both byte routes → DELETE (and the
  session-media copy surviving the upload's delete), steer (`prompt_ids`
  array body, fresh-ULID splice), `:abort` (`interruptReason:"user_cancelled"`),
  compact `{}` / `{"instruction":…}` / both `40910`s (empty + busy) /
  post-compaction history + status, questions flow (above), TodoList
  `tool.call.started` contract, REST `/tasks` list/get + terminal `:cancel`
  `40904` with `details.current_status`, tools catalog (27 entries — `WaitFor`
  in, `ReadMediaFile` out), WS hello/heartbeat/subscribe/ack/cursor-resume,
  and the `40401` error envelope with `stack`.
  **Anomaly (unresolved, likely server-side):** in 3/3 background-subagent
  runs the subscribed WS connection silently stalled right after the Agent
  call's `tool.result` — no close frame, no further events (not even pings),
  while foreground runs and plain turns streamed fully; `task.terminated` /
  `task.notified` could not be captured this run. REST `/tasks` showed the
  runs completing normally. The Android app should treat a stalled socket as
  a drop and resync via REST; no app ticket filed (no client-side fix).
- 2026-08-18 re-verification on `0.37.2` (server 0.36.1 → 0.37.2; throwaway
  sessions `session_956685b4-...` / `session_378ba373-...` in
  `/home/ubuntu/spike`, guided by the upstream 0.36.1→0.37.2 diff — 43
  commits; the wire-relevant ones are the media resolver (`d833a1a8`),
  `turn.ended` time (`3ded0808`), `/meta` `web_title` (`09976b09`), the prompt
  `skills`/`prompt_id` fields (`d6021fa0`/`d833a1a8`), and the v2-sessions
  page/projection/batch-archive work (`eaa3969d`)). Re-verified unchanged, most
  byte-for-byte: meta (capabilities + `experimental_flags` + `backend:"v2"`;
  `web_title` absent), v1 session object/list/create/get/status (fresh
  `thinking_level:""`, `pending_interaction:"none"`, no `archived_at`/no
  `last_turn_reason` on fresh never-archived sessions), WS
  `server_hello`/`ack`/`ping` (heartbeat 10 s), and one full prompt round-trip
  on a fresh session emitting the complete event stream for two turns
  (`agent.created`, `turn.started`, `context.spliced`, deltas,
  `turn.step.*`, `turn.ended`, `prompt.completed`). Drift found (all detailed
  inline): (a) image/video `source` gained `session_media` — history AND the
  prompt ACK now project uploaded images as
  `{kind:"session_media",file_id}` instead of base64/`url:data:…` (live-
  verified with a 1×1 png; new `GET /api/v1/sessions/{id}/media/{file_id}`
  endpoint serves the copy) — **this breaks the app's image rendering**, ticket
  104; (b) `turn.ended` gained `time` (epoch ms; `turn.started`/
  `prompt.completed` emit it live too, schema-undocumented); (c) `turn.started`
  gained `promptId`; (d) prompt POST gained `skills`/`prompt_id` (the latter
  echoed on ACK/history/`turn.started.promptId`); (e) `/meta` gained
  `web_title` (absent here); (f) error code `40927` `prompt.id_conflict`;
  (g) `/api/v2/sessions` gained `page`, `fields=id,archived`,
  `meta.updated_before`, `total`, and `POST :archive`/`:restore` batch
  endpoints (app doesn't use v2). Config drift this run: default model is now
  `deepseek/deepseek-v4-pro`, `thinking.effort` `"high"`, four `deepseek/*`
  models added, and a fresh session's `/status` `max_context_tokens` reads
  1000000. Not re-triggered this run: questions, approvals, steer, compact,
  files upload/delete, sub-agents, TodoList, search, workspaces — no upstream
  commit touched their shapes. App impact: one breakage (image
  `session_media`); ticket 104 filed.

- 2026-08-14 re-verification on `0.36.1` (server 0.36.0 → 0.36.1; throwaway
  session `session_e03e5ddc-...` in `/home/ubuntu/spike`, guided by the
  upstream 0.36.0→0.36.1 diff — 25 commits; four kap-server commits, two
  wire-relevant and both on REST `/tasks`: `325913a5` (`agent_id`) and
  `cb30a779` (`subagent_type`/`parent_tool_call_id`); the plugin-marketplace
  routes (`741708f9`) and the session-export limit removal (`eb72aebe`) are
  additive routes outside the core loop). Spot-verified unchanged, most
  byte-for-byte: meta (`backend:"v2"`; `experimental_flags` gained
  `auto_session_title:false`), session create/get (no `archived_at` on a
  never-archived session), `/status` on a k3 session
  (`max_context_tokens:1048576`, `thinking_level:"low"`), prompts POST,
  `:abort` ACK, WS `server_hello` (`protocol_version:2`,
  `heartbeat_ms:10000`). Drift found (detailed inline): subagent-kind
  `/tasks` objects (list, get, `with_output`) gained optional `agent_id`,
  `subagent_type` and `parent_tool_call_id` — verified live on a background
  `explore` sub-agent (`agent_id:"agent-0"` joins to the WS/snapshot roster
  id). Not re-triggered this run: questions, approvals, steer, compact,
  files, snapshot, foreground sub-agents — no upstream commit touched their
  shapes. App impact: no breakage — all three fields are additive optional
  and every app parser runs `ignoreUnknownKeys = true`; the app simply does
  not consume them yet. No `todo/` tickets filed.

- 2026-08-13 re-verification on `0.36.0` (server 0.35.0 → 0.36.0; throwaway
  session `session_3d09cba8-...` in `/home/ubuntu/spike`, guided by the
  upstream 0.35.0→0.36.0 diff — 19 commits; five kap-server commits, two
  wire-relevant: `43c68f58` (session `archived_at`) and `c9bfe8b2` (config
  shape / error mapping); the debug-RPC restriction, the agent-RPC
  aggregation removal and the swarm scope reorganization are import-move or
  internal diffs). Re-verified unchanged, most byte-for-byte: meta
  (`backend:"v2"`; `experimental_flags` values drifted —
  `persistence_minidb_readmodel:true`, new `search_worker:true` flag),
  models catalog, sessions create/get/list/status (fresh
  `thinking_level:""`, `max_context_tokens:262144` present, no
  `archived_at` key on a never-archived session), snapshot (top-level keys,
  roster `model`/`thinking_effort`, all-zero `session.usage` placeholder),
  error envelopes (`40401` + `stack`, unknown list cursor →
  `{"items":[],"has_more":false}`, `40922` bad v2 page token, empty-session
  compact `40910` + `stack`, terminal-task `:cancel` `40904` with
  `details.current_status`), WS hello (`heartbeat_ms:10000`) / ack /
  server `ping` frames, one full prompt round-trip with a foreground AND a
  background sub-agent spawn (`agent.created` ×2, `subagent.spawned` with
  `model`/`thinkingEffort`, `subagent.started`/`completed`, sub-agent-tagged
  turn frames, `task.started`/`task.terminated` + the `background.task.*`
  legacy aliases, `task.notified`, the `{"kind":"task"}` completion-turn
  origin, REST `/tasks` list/get `with_output`), a mid-turn `:abort`
  (`turn.step.interrupted` → `turn.ended` `interruptReason:"user_cancelled"`
  → reminder splice → idle `work_changed` → `prompt.aborted`), and a
  compact with `instruction` (`compaction.started` echo,
  `compaction.completed` result keys, the `compaction_summary` history
  message, `tokensAfter` semantics). Drift found (all detailed inline):
  (a) session object gained optional `archived_at` (source-derived; absent
  on never-archived sessions — verified live); (b) `GET /api/v1/config`
  dropped the `secondary_model` key and gained `subagent`
  (`{"timeoutMs":7200000}` here); (c) `Error2(config.invalid)` now maps to
  envelope `40001` instead of `50001` (not triggered live). Config-derived
  values this run: session thinking effort is `low`, so every
  `thinkingEffort`/`thinking_effort` capture reads `"low"` (was `"high"` on
  0.34.0). Not re-triggered this run: questions, approvals, steer, files,
  search, workspaces/trust, transcript paging — no upstream commit touched
  their shapes. App impact: no breakage — `archived_at` is additive,
  `subagent` is additive, and the app consumes neither `secondary_model`
  nor the config route's new field. No `todo/` tickets filed.

- 2026-08-12 re-verification on `0.35.0` (server 0.34.0 → 0.35.0; throwaway
  session `session_4b7556dd-...` in `/home/ubuntu/spike`, guided by the
  upstream 0.34.0→0.35.0 diff — 24 commits; `packages/protocol` untouched;
  three kap-server commits, one wire-relevant: the WS heartbeat). Re-checked
  unchanged: meta (capabilities + `experimental_flags`; `backend:"v2"`),
  sessions list/create (`{"metadata":{"cwd":...}}`), session status (idle,
  `max_context_tokens:262144`, `thinking_level:""`), messages (empty
  `{"items":[],"has_more":false}`), WS handshake/subscribe/ack (cursor
  `{seq,epoch}` unchanged). Drift found (detailed inline): (a) `server_hello`
  gained `heartbeat_ms:10000` (always present); (b) new server→client `ping`
  frames every 10 s with a ULID `nonce`, and a server-initiated close
  (code `1001`, reason `"heartbeat timeout"`) after ~2 cycles (~20 s) of
  total client silence — observed live on a silent subscribed connection
  (pings at +10 s/+20 s, closed before +30 s); (c) the `last_turn_reason`
  persisted fallback (schema new in 0.34.0) is now observable on cold
  pre-restart sessions. Not re-triggered this run: prompts/steer/abort,
  compact, questions, approvals, sub-agents, TodoList, files, search — no
  upstream commits touched their shapes. App impact: the app parses none of
  the new frames (`ping` falls into the unknown-type ignore — harmless), but
  it never sends WS traffic while idle, so the heartbeat timeout now
  force-closes its sockets every ~20 s of silence — reconnect churn on both
  the chat and session-list sockets. Ticket 103 filed.

- 2026-08-06 re-verification on `0.34.0` (server 0.33.0 → 0.34.0; throwaway
  sessions in `/home/ubuntu/spike`, guided by the upstream 0.33.0→0.34.0
  diff — 24 commits; protocol-relevant: the sub-agent model/effort
  surfacing, `/status` `max_context_tokens` optionality, persisted
  `last_turn_reason` for cold listings, the new `/api/v2/sessions` table,
  MCP-server tombstoning, skill-activation attachments). Re-verified
  unchanged, most byte-for-byte: meta (incl. `experimental_flags`), models,
  sessions create/get/list (multi-page `before_id`/`after_id` walks; unknown
  cursor → `{"items":[],"has_more":false}`), status idle/busy, snapshot
  (top-level keys, busy `in_flight_turn`, roster), workspaces + trust cycle,
  messages (incl. the interruption injection), transcript, prompts
  running/queued, steer (fresh-id splice, `40402`/`40001`), abort
  (`interruptReason:"user_cancelled"` + reminder splice), a full
  manual-approval round-trip (v2 + legacy frames, REST approve,
  `expires_at` = +24 h), questions (surface, poll, single/multi answers,
  dismiss, `40909`/`40902`, 404 on `/questions/{qid}`), TodoList write
  contract verbatim, compact `{}` and with `instruction` (full WS sequence,
  summary without `id`, `tokensAfter` == post-compaction `context_tokens`,
  both `40910`s), files upload/download/`Range` 206/delete + the
  `Attached file "…"` rewrite (em dash intact), search (v2 keyset tokens,
  same-generation replay, both `40001` token errors with `stack`,
  `index_state.stale:true` observed mid-rebuild), foreground + background
  sub-agent lifecycles (`subagent.*`/`task.*` + legacy aliases, REST `/tasks`
  list/get/cancel→killed/`40904`, snapshot roster), WS hello/subscribe/ack,
  cursor-resume replay (seq 44 → 45–47 in order), refused cursor (standalone
  `resync_required` + ack), zero heartbeat frames. Drift found (all detailed
  inline): (a) `subagent.spawned` gained `model`/`thinkingEffort`
  (camelCase); agent-kind `task.*` `info` gained the same pair; REST
  `/tasks` subagent objects and the snapshot roster gained
  `model`/`thinking_effort` (snake_case) — `"kimi-code/k3"`/`"high"`
  observed throughout; (b) foreground sub-agents now register in the task
  store while running — REST-listed, fetchable once terminal, `:cancel`-able
  while running, still no `task.*` frames, dropped from the list when
  terminal; (c) `/status` `max_context_tokens` is omittable per the upstream
  schema (never observed omitted — fresh sessions report the 262144
  default); fresh sessions now report `thinking_level: ""`; (d)
  `last_turn_reason` has a persisted fallback for cold sessions (upstream
  only — all 100 pre-restart sessions still omit it); (e) question items
  carry `multi_select` only when true now; (f) `GET /api/v1/tools` lists 27
  tools again — `ReadMediaFile` is back (26 on fresh never-prompted
  sessions); (g) the sub-agent first-message `<git-context
  status="unavailable"/>` prefix is gone; (h) new endpoint `GET
  /api/v2/sessions` (v1 envelope, v1-fingerprint keyset `next_page_token`,
  new error code `40922`, GET-only); (i) additive WS observations: new
  `agent.status.updated` phase kind `interrupted` during abort, new
  injection origin variant `permission_mode`. Not re-triggered this run:
  TodoList read-query, `event.config.warning`, the image url-source `id`
  field, MCP `removed` status. App impact: no breakage — every change is
  additive or already tolerated (`ignoreUnknownKeys`, unknown-frame ignore);
  the `/status` zero-limit handling and the foreground-subagent task-store
  registration degrade existing behavior — tickets 100 and 101 filed.

- 2026-08-05 re-verification on `0.33.0` (server 0.32.0 → 0.33.0; throwaway
  sessions `session_5921be1f-...` / `session_c9b9588a-...` in
  `/home/ubuntu/spike`, guided by an upstream tree-diff of
  `packages/protocol` + `packages/kap-server` between the tags — only
  `events.ts` (an optional MCP `expiresAt`, out of scope), the search route,
  the sessions-list pagination internals, and the skills listing changed).
  Re-verified unchanged, most byte-for-byte: auth/401s, meta (incl.
  `experimental_flags`), models, sessions create/get/list (multi-page
  `before_id`/`after_id` walks after the keyset rewrite; unknown cursor →
  `{"items":[],"has_more":false}`; the `message_count`/`last_seq` stuck-at-0
  and all-zero `usage` quirks persist), status idle/busy, snapshot (top-level
  keys, busy `in_flight_turn`, roster), workspaces + trust cycle, messages
  (incl. the interruption injection), transcript, prompts running/queued,
  steer (fresh-id splice, `40402`/`40001`), a full manual-approval
  round-trip (v2 + legacy frames, REST approve), questions (surface, poll,
  answer, dismiss, `40909`/`40902`/404), TodoList write contract verbatim,
  foreground + background sub-agent lifecycles (`subagent.*`/`task.*` +
  legacy aliases, REST `/tasks` list/get/`40406`/cancel→killed/`40904`,
  snapshot roster), abort (`interruptReason:"user_cancelled"` + reminder
  splice), compact `{}` and with `instruction` (full WS sequence, summary
  message without `id`, `40910` ×2, `40401`, `40001`), files
  upload/download/`Range` 206/delete + the `Attached file "…"` rewrite (em
  dash intact), WS hello/subscribe/ack, cursor-resume replay (seq 218 →
  219–231 in order), refused cursor (standalone `resync_required` + ack),
  zero heartbeat frames. Drift found (all detailed inline): (a) search page
  tokens are now v2 keyset cursors pinned to an index generation (replay
  within a generation works; tampered → `40001 "pageToken is malformed"`;
  legacy v1 tokens still hit the v1 decoder — fingerprint-mismatch `40001`
  observed, end-to-end v1 acceptance unverifiable client-side);
  `index_state.stale:true` observed live mid-rebuild; `degraded` and the
  extended `incomplete` enum rest on the upstream diff; the search `40001`s
  carry a `stack`; (b) `GET /api/v1/tools` lists 26 tools —
  `ReadMediaFile` removed; (c) `last_prompt`/`patch.lastPrompt` redact
  session-dir paths to `/home/ubuntu/.[redacted].txt` (display-only);
  (d) `:compact` with `Content-Type: application/json` and a truly empty
  body now fails `50001` (body omitted entirely = no content-type, still
  fine). Not re-triggered this run: question `multi_select` variants,
  TodoList read-query, `event.config.warning` (none in force). No app
  breakage: the app never calls `/api/v1/search` or `/api/v1/tools`, and
  every other change is additive or already tolerated. No `todo/` tickets
  filed.

- 2026-08-05 re-verification on `0.32.0` (server 0.31.1 → 0.32.0; throwaway
  sessions `session_61335917-...` / `session_e70e0240-...` /
  `session_701f76d7-...` in `/home/ubuntu/spike`, verified against the
  upstream 0.31.1→0.32.0 changelog — 20 commits; the protocol-relevant ones
  are the `/meta` experimental-flags exposure, the v1 message-history/snapshot
  move into kap-server, the colon-question-id fix, the Error2 refactor, the
  config-warning event, and the tokenCounting service). Re-verified unchanged:
  auth, sessions create/get/list/status (the `message_count`/`last_seq`
  stuck-at-0 and idle-zeroed `usage` quirks persist exactly as documented),
  workspaces list, config, models, global search, tools catalog (27 tools),
  messages history order, snapshot (top-level keys, idle `in_flight_turn:
  null`, busy `in_flight_turn` verbatim, message page OLDEST-first),
  transcript (`agents` roster, real turn `state`/`endedAt`/`durationMs`),
  a full prompt round-trip (TodoList write contract + `AskUserQuestion`
  surface + selected-option answer + dismiss + `40902` re-dismiss, every
  event payload byte-identical), prompts queued-while-busy + `prompts:steer`,
  mid-turn `:abort` (`interruptReason:"user_cancelled"` + interruption
  reminder splice), compact `{}` and with `instruction` (full WS sequence,
  context 26993→657), the empty-session and busy-session `40910`s, files
  upload/download/`Range` 206/delete + the `Attached file "…"` prompt rewrite
  (em dash intact), foreground sub-agent lifecycle (`agent.created` main +
  sub, `subagent.spawned`/`started`/`completed`, sub-agent-tagged frames),
  background sub-agent (`task.started`/`task.terminated` + the
  `background.task.*` legacy aliases, REST `/tasks` list/get, `:cancel` →
  `killed`, terminal `:cancel` → `40904`, snapshot roster `[]` for the
  background run), WS hello/subscribe/ack, cursor-resume replay (seq 2 →
  3–81 in order) and refused cursor (standalone `resync_required` frame +
  ack). Drift found (all detailed inline): (a) `/meta` gained
  `experimental_flags`; (b) new global WS event `event.config.warning`
  (`session_id:"__global__"`, both the warning-set and the clearing
  `warnings:[]` frames captured verbatim — triggered by temporarily adding
  the deprecated `[loop_control] max_retries_per_step` key to `config.toml`,
  restored byte-identical afterwards); (c) the server-side `stack` string now
  rides on every Error2 domain-error envelope (`40401`/`40402`/`40910`/
  `50001` observed; `40001`/`40406` still clean); (d) the error-code enum
  grew (agent/skill/task/mcp/storage/wire/cron/web codes); (e) upstream
  internals with no observed shape change: the tokenCounting service
  (`maxContextTokens` present on every capture; the omittable-when-unknown
  variant never seen), the message-history/snapshot move, the colon
  question-id resolve fix. Approvals could NOT be re-verified live (this
  server's `default_permission_mode` is `yolo` — no approval gate surfaces;
  last live capture 0.31.0). No app breakage: every change is additive or
  already tolerated (`ignoreUnknownKeys`, unknown-frame ignore, foreign-
  session drop). No `todo/` tickets filed.

- 2026-08-05 `session.usage` placeholder re-verified on `0.32.0` (real
  sessions, after Louis's "ring shows 0/0 on cold open" bug report):
  `/snapshot`'s `session.usage` is the all-zero placeholder on EVERY read path
  — busy sessions included (`session_4ee7722f-...` busy with 68005 real
  context tokens per `/status`; idle `session_30836352-...` 48034/262144,
  `session_7c334bae-...` 121884/262144, both all-zero in the bundle; the
  sessions LIST's `usage` is likewise all-zero, `turn_count` included). On
  0.31.1 only idle sessions zeroed the context pair. kimi-web handles this
  explicitly (`isPlaceholderSessionUsage` + "re-read `/status` so the ring
  converges on the live value"); the Android app now does the same — the
  mapper drops a zero-limit usage (unknown, not a real 0/0) and the snapshot
  apply re-reads `/status`, seed-guarded against live
  `agent.status.updated` frames.

- 2026-08-02 snapshot `messages` order re-verified on `0.31.1` (real session
  `session_428cd0bf-...`, 375+ messages, after Louis's inverted-timeline bug
  report): `/messages` newest page returns items newest-first (`..._000375` →
  `..._000326`, descending ids/`created_at`), while `/snapshot`'s `messages`
  returns the SAME newest page OLDEST-first (`..._000276` → `..._000375`,
  ascending ids/`created_at`, page size ~100 vs ~50 there). The 085 mapping had
  assumed `/messages` order and reversed the snapshot page — root cause of the
  cold-open inverted timeline (bottom pin mid-session, tool results orphaned
  before their Running calls, newest-id paging cursor). Fixed app-side
  (ChatMessageMapper keeps the snapshot wire order) and inline above.

- 2026-08-01 snapshot deep-dive on `0.31.1` (throwaway sessions in
  `/home/ubuntu/spike`; the Android app adopted `/snapshot` as its
  cold-start/resync entry point — ticket 085). Captured inline (Sessions →
  Session snapshot): the full top-level key list; `session.usage` carries the
  context fill as `context_tokens`/`context_limit` (NOT the `/status`
  `max_context_tokens` naming); `messages` is the newest page with
  `has_more` (~100 items) but OLDEST-first item order — unlike `/messages`
  (newest-first); misreading it as newest-first inverted the Android cold-start
  timeline (ticket 085 follow-up, fixed 2026-08-02); `in_flight_turn` verbatim while busy (`turn_id`,
  `assistant_text`, `thinking_text`, `running_tools[{tool_call_id,name,args,
  description,display}]`, `current_prompt_id`), `null` when idle;
  `pending_approvals` items are the legacy `event.approval.requested` payload
  verbatim (`approval_id` == `tool_call_id`); `pending_questions` items are
  byte-identical to the `/questions?status=pending` poll's `items[]` (verified
  with a live AskUserQuestion); the `subagents` roster captured both while
  running (`status:"running"`/`subagent_phase:"working"`, no
  `completed_at`/`output_preview`) and terminal (both flip, `completed_at` +
  `output_preview` result-summary tail).

- 2026-07-31 re-verification on `0.31.1` (server 0.31.0 → 0.31.1; throwaway
  sessions `session_1cf6658d-...` / `session_ec77caa8-...` in
  `/home/ubuntu/spike`, verified against the upstream 0.31.0→0.31.1 changelog
  — 19 commits, mostly an agent-core-v2 internal refactor). Spot-checked
  unchanged: meta, sessions create/get/list/status, workspaces list, config
  (`secondary_model` still `{}`), messages history, transcript `agent_id`
  paging, questions pending list, snapshot roster, global search, WS
  hello/subscribe/ack. Drift found (all detailed inline): (a) `turn.ended`
  gained optional `interruptReason` — a REST `:abort` reports
  `"user_cancelled"`; (b) interruption reminder: a user-cancelled turn appends
  a whole-message `<system-reminder>` user injection
  (`metadata.origin: {"kind":"injection","variant":"interruption"}`), visible
  in `GET .../messages` and pushed live as a `context.spliced` echo between
  `turn.ended` and the idle `work_changed` — the app's envelope stripper
  already hides it; (c) transcript: new `interruption` markers (warm AND cold
  path) and real terminal turn `state`/`endedAt`/`durationMs`/`error` on the
  cold rebuild (persisted `turn.ended` records; pre-0.31.1 journals keep the
  `completed` default); (d) `GET /api/v1/tools` answers `{"tools":[]}` when no
  session is live (verified empty right after the restart, 27 tools again once
  a session ran); (e) new endpoints, all out of the app's scope:
  `GET /api/v1/oauth/userinfo` (managed-account profile, verified 200),
  `POST /api/v1/workspace/fs:search` (session-less; verified, `40410` on an
  unknown workspace), and the workspace-trust routes
  `GET|POST .../workspaces/{id}/trust` + `POST .../untrust`
  (`{"trusted":bool}`; the missing-root error path leaks a `stack` field on
  `50001`); (f) the Agent/AgentSwarm `model` schema-parameter strip is
  invisible through `/api/v1/tools` (`input_schema: null`). No app breakage:
  every change is additive or already tolerated by the app's parsers
  (`ignoreUnknownKeys`, marker tolerance, envelope stripping, blank-part
  drop). No `todo/` tickets filed.

- 2026-07-30 full re-verification on `0.31.0` (server 0.29.2 → 0.31.0;
  throwaway sessions in `/home/ubuntu/spike` — `kimi-web.service` now runs
  with `PrivateTmp=yes`, so the old `/tmp` throwaway cwd is invisible to the
  server). All core-loop REST shapes re-verified unchanged: meta, models,
  sessions CRUD + status + pagination, workspaces, messages, transcript,
  prompts, steer REST, abort, compact REST + WS + errors, questions
  answer/dismiss + WS events, approvals, TodoList write contract, files
  (incl. the attachment rewrite), tasks REST, transcript `agent_id` paging,
  snapshot `subagents` roster, WS hello/ack/cursor-resume. Drift found (all
  detailed inline): (a) the 50 MiB file-upload cap was removed — uploads
  stream to disk, a 55 MiB upload returns HTTP 200 with the exact `size`,
  413/41301 no longer occurs on `/files` (the code survives for session
  export); `expires_in_sec` now adds `expires_at` to the FileMeta response;
  (b) `GET`/`POST /api/v1/config` gained `secondary_model` (`{}` here);
  `default_model` reads `kimi-code/k3-256k` (config value, not protocol);
  (c) new endpoints `POST /api/v1/search` (global message search) and
  `GET /api/v1/tools` (tool catalog, `input_schema: null`, 27 builtin tools);
  (d) WS: `agent.created` now fires for the main agent at the head of the
  first prompt; new event type `turn.step.retrying` (LLM retry, e.g. provider
  429); `agent.status.updated` gained a nested-usage variant (`byModel` /
  `total` / `currentTurn`) and the streaming phase now also appears with
  `stream:"assistant"` (`turn.step.completed.usage` and
  `subagent.completed.usage` remain flat); right after `subagent.spawned` the
  server republishes the sub-agent's `agent.status.updated`; the Agent
  tool's `tool.call.started` carries `display.kind:"agent_call"`; sub-agent
  first internal message embeds `<git-context status="unavailable"
  reason="not-a-repo"/>` when the cwd is not a git repo; background-task
  completion is now delivered as a NEW main turn (`turn.started` origin
  `{"kind":"task",...}`) and the notification splice message has no `id` and
  origin kind `task` (the `<notification>` element gained attributes and an
  `<output-file>` child); the background Agent tool result text gained
  `next_step:`/`resume_hint:` blocks (Agent gained a `resume` parameter;
  `TaskOutput` lost `block`/`timeout`); the compact `context.spliced`
  summary message no longer carries `id: null` — the `id` key is absent;
  (e) environment: the old `/tmp` throwaway cwd replaced by
  `/home/ubuntu/spike` in the examples. Two caveats: the steer WS mid-turn sequence could NOT be
  re-verified (the steered turn hung model-side and was aborted), and the
  TodoList read-query (no `todos` key) was not triggered live this run.

- 2026-07-30 compact round-trips on `0.30.0` (ticket 054; throwaway
  sessions `session_647d8a6d-...` / `session_abf63a9c-...` /
  `session_e658daa5-...` in `/home/ubuntu/spike-compact-054`): `POST
  :compact` with `{}`, with `{"instruction":"..."}`, and with no body —
  instant `{"code":0,"data":{}}` ACK, compaction runs in the background; WS
  sequence `compaction.started` (`trigger:"manual"`, instruction echoed) →
  busy `event.session.work_changed` → `context.spliced` (`deleteCount` =
  whole context; kept user messages + summary with
  `origin.kind:"compaction_summary"` and `id:null`, wrapper prefix present
  only here) → `compaction.completed` (`result.summary` / `compactedCount`
  / `tokensBefore` / `tokensAfter` / `keptUserMessageCount`) → idle
  `work_changed`; `GET .../status` busy mid-compaction, `context_tokens`
  26518→386 and 25851→275 after; `GET .../messages` keeps the full
  transcript and appends the bare summary as a `role:"user"`
  `compaction_summary` message (pagination + `role=user` filter unchanged,
  two summaries after two compactions); double-compact silently no-ops (one
  `compaction.started` for two POSTs); errors: empty session `40910` "No
  messages to compact in current history.", busy session `40910` "Cannot
  compact while a turn is active...", approval pending same `40910`,
  unknown session `40401`, numeric `instruction` `40001` with `details`.
  **`event.session.history_compacted` never fired** — the web-bundle recon
  (`before_seq` / `reason` / `summary_message_id`) does not match this
  server. All captured with `websocat` 1.14.0 against
  `ws://127.0.0.1:58627/api/v1/ws` and `curl` against
  `http://127.0.0.1:58627/api/v1/...`.
- 2026-07-28 transcript round-trips on `0.29.2` (ticket 046): finished
  sub-agent (`agent-15`, cold rebuild), running then finished sub-agent
  (`agent-23`, throwaway session `session_59566ec0-...` — first prompt needed
  `"model":"kimi-code/k3"`, a fresh session without one fails silently with an
  empty completed turn), `page_size=1` two-page walk with `before_turn` (past
  the oldest → `items:[]` + `has_more:false`), `after_turn` forward page,
  `agent_id` omitted → `40001`, `page_size=0`/`101`/`200` → `40001`, default
  page (20 turns + 13 interleaved markers = 33 items), `swarm.enter`/`exit`
  and `undo` markers on the main transcript, `prompt` field on user-origin
  turns. All `curl` against `http://100.64.0.1:80/api/v1/...`.
- 2026-07-28 sub-agent round-trips on `0.29.2` (throwaway session
  `session_1525d049-...`; first prompt needed
  `"model":"kimi-code/k3"` — a fresh session has no model and fails with
  `model.not_configured`): foreground `Agent` spawn (full lifecycle —
  `agent.created`, `subagent.spawned`/`started`/`completed`, two internal
  turns of sub-agent-tagged frames, the Agent tool-result text), background
  spawn with `run_in_background:true` (`task.started`/`task.terminated` plus
  the `background.task.*` legacy aliases, `task.notified` +
  `<notification>` splice), REST `/tasks` list + `?status=` filter (invalid
  value → `40001`), get with `with_output`/`output_bytes`, unknown task
  (`40406`), unknown session (`40401`), empty list for the foreground run,
  `:cancel` on a running background sub-agent (`{"cancelled":true}`,
  `task.terminated` `status:"killed"` + `stopReason:"Aborted by the user"`,
  REST reads `"cancelled"`) and on a terminal one (`40904`), snapshot
  `subagents` roster (present after the foreground run, `[]` once the main
  agent started a new turn), history showing only the main transcript. All
  captured with `websocat` 1.14.0 against `ws://127.0.0.1:58627/api/v1/ws`.
- REST probes: `curl` against `http://127.0.0.1:58627/api/v1/...` (commands
  inline above), server `0.31.0` (`backend: v2`).
- Auth (re-verified 2026-07-27): `/api/v1/meta` → `401` without a token or
  with a wrong one, `200` with `Authorization: Bearer <token>`;
  `/api/v1/healthz` is public (always `200`). Auth is server-native (bearer
  token), enforced on all authenticated REST and WebSocket routes;
  `"dangerous_bypass_auth": false` in `/meta`.
- WS captures: websocat 1.14.0 (`~/tools/websocat-bin/websocat`, header passed
  as `-H="Authorization: Bearer <token>"`) against
  `ws://127.0.0.1:58627/api/v1/ws?client_id=...`, subscribing to throwaway
  sessions created for the captures. 2026-07-23 round-trips recorded on
  0.29.0: tool call with manual approval (approve path included), plain queued
  prompt, mid-turn steer of a queued prompt (`prompts:steer`), idle-session
  steer error (`code:40402`), empty `prompt_ids` error (`code:40001`), mid-run
  abort, cursor-resume replay (seq 70 → 71–78, seq 78 → later question
  events), unsubscribe ack, question surface + answer (selected option and
  free text), question dismiss (HTTP 200 with `code:40909`), `GET
  questions?status=pending` polling, missing `status` param error
  (`code:40001`), `GET questions/{qid}` confirmed 404, re-answer/re-dismiss of
  resolved questions (`code:40902` "already resolved"). Questions triggered by
  prompting the session to call its `AskUserQuestion` tool. No heartbeat
  frames observed.
- 0.29.1 → 0.29.2 drift found: **one shape change in the core loop** — the
  steered user message spliced via `context.spliced` carries an `id` again (a
  fresh ULID, not the queued prompt's id; 0.29.0/0.29.1 dropped it). Backward
  compatible with the Android app (`MessageDto.id` defaults to `""`). Also
  observed: `kimi-code/k3-256k`'s `display_name` is now `"K3-256k"`, and
  `GET /api/v1/config` `default_model` reads `kimi-code/k3` (config value, not
  protocol). Everything else — sessions/workspaces/messages/prompts/
  prompts:steer/approvals/questions/`:abort`/files REST shapes, every WS event
  payload, cursor-resume replay, and all error envelopes — is byte-for-byte
  identical to 0.29.0/0.29.1. Upstream also added provider write endpoints
  (`POST`/`PUT`/`DELETE /api/v1/providers`, `GET /api/v1/catalog/providers`),
  an `api_key` reveal on the single-provider GET, a `desktop` flag on session
  export, and a WS frame-flush fix — all outside the core loop; no Android DTO
  or parser needs updating.
- 2026-07-27 0.29.2 round-trips (throwaway session `session_43b9ba9b-...`):
  manual tool-call approval (approve path, both
  `permission.approval.*` and legacy `event.approval.*` shapes with
  `expires_at` +24 h), queued prompt while busy, `prompts:steer` (fresh-id
  splice capture), idle-session steer error (`code:40402`), empty
  `prompt_ids` error (`code:40001`), mid-run `:abort`, `AskUserQuestion`
  surface + selected-option answer + dismiss (HTTP 200 with `code:40909`) +
  re-answer/re-dismiss (`code:40902`), `GET questions?status=pending` polling,
  missing `status` param error (`code:40001`), `GET questions/{qid}` confirmed
  404, live `TodoList` capture (three-step prompt), file
  upload/download(`Range` 206)/delete, prompt with a `file` attachment
  (history confirmed the `Attached file "…"` notice wording, including the em
  dash, and `<sessionDir>/attachments/` materialization), WS cursor-resume
  (seq 135 → replayed 136–142 in order), `DELETE /api/v1/sessions/{id}`
  confirmed 404. All captured with `websocat` 1.14.0 against
  `ws://127.0.0.1:58627/api/v1/ws`; no heartbeat frames observed.
- 0.29.0 → 0.29.1 drift found: **none in the core loop**. REST shapes
  (`sessions`, `workspaces`, `messages`, `prompts`, `prompts:steer`,
  `approvals`, `questions`, `:abort`, `files`) and every WebSocket event
  payload the Android app consumes are byte-for-byte identical to 0.29.0.
  Changes are confined to server-side defaults and the models catalog:
  `GET /api/v1/config` now returns `default_model: "kimi-code/kimi-for-coding"`
  (was `kimi-code/k3`), and `GET /api/v1/models` adds `kimi-code/k3-256k` and
  refreshes `capabilities` to `["thinking", "always_thinking", "image_in",
  "video_in", "tool_use"]` (k3-256k omits `"video_in"`). No Android DTO or
  parser needs updating.
- 2026-07-25 0.29.1 round-trips (throwaway session `session_c42940aa-...`):
  manual tool-call approval (approve path), `AskUserQuestion`
  surface + answer via `POST .../questions/{qid}`, queued prompt while busy,
  `prompts:steer`, mid-run `:abort`, file upload/download/delete, and prompt
  with a `file` attachment (history confirmed the `Attached file "…"` notice
  wording, including the em dash, and `<sessionDir>/attachments/`
  materialization). All captured with `websocat` 1.14.0 against
  `ws://127.0.0.1:58627/api/v1/ws`; no heartbeat frames observed.
- Housekeeping notes: the `kimi-web.service` unit has `PrivateTmp=yes`, so a
  host-side `/tmp` directory is invisible to the server — throwaway sessions
  are created with cwd `/home/ubuntu/spike` instead (see Sessions → Create). There is no session-delete endpoint (`DELETE` → 404, re-verified
  2026-07-27); throwaway sessions created for the captures were left in place.
- 2026-07-24 attachment round-trips on 0.29.0 (session `attach-spike`, cwd the
  repo root): upload/download (incl. `Range` 206 and `content-disposition`
  inline-vs-attachment)/delete of text and png files; prompt with
  `image`+`file`+`text` content items — ACK echo and `GET .../messages`
  history confirmed the `Attached file "…"` notice wording byte-for-byte, the
  `<sessionDir>/attachments/<fileId>-<name>` materialization (bytes readable on
  disk), and the image base64-vs-data-url shape difference between ACK and
  history. A 14.1 MB png was compressed with a `<system>` caption text part
  (original kept under `<sessionDir>/media-originals/`); a 3.2 MB png was not.
  Upload `media_type` is content-sniffed: valid mp4/WebM uploads return
  `application/octet-stream`, and a `video` prompt part referencing such a file
  is rejected with envelope `40001`, so the video path is unreachable on
  0.29.0. The uploaded test files were deleted afterwards; the spike session
  was left in place (no session-delete endpoint).
- 2026-07-22 round-trips on 0.28.1, 2026-07-19/20 round-trips on 0.27.0 and
  2026-07-17 round-trips on 0.23.3 (superseded shapes): see git history.
