# Kimi Code server wire protocol (core loop)

Wire protocol of the HTTP+WebSocket server embedded in the `kimi` binary, as used
by Kimi Code Web. Documented and verified against the live server on 2026-09-24
(server version `2.1.1`, `"backend": "v2"`, WS `protocol_version: 2`).

Scope: core loop + user interaction — auth check, sessions, messages, prompts,
steer, questions, abort, permissions, WebSocket events, file attachments,
sub-agents (foreground lifecycle + background task store), global message
search, the tools catalog, plus
the workspace list used by the mobile create-session flow. Explicitly skipped:
`fs:*`, terminals, cron, goals, providers, skills.

Every endpoint below was executed against the live server; the exact commands
used are shown with each section. Secrets are redacted (`<token>`).

**Protocol history:** this document previously described server `2.0.2`
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
  socket handles `event.session.archived` (ticket 105) but not the deleted
  sibling — a row deleted from another client lingers until refresh —
  ticket 126.**
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
  of the flag and is always on (commit `6126472c`).
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
  picker will consume it (ticket 142).
- **`auto_session_title`** (new in 2.1.1, commit `6a52dd78`; verified live
  2026-09-24): a boolean gating automatic AI session-title generation ("Whether
  clients may automatically generate session titles" per the upstream doc).
  **Absent from this server's GET projection when unset** — no key in `data`,
  and titles generate by default (`session.meta.updated` on the first prompt
  re-verified this run). The REST schema accepts it on both GET and POST.
  The app reads no such field — no ticket.

## Sessions

### List — `GET /api/v1/sessions`

Query params (all optional): `page_size` (default 20), `before_id`, `after_id`
(pagination cursors — `before_id` paging verified 2026-07-22, list shape
re-verified 2026-07-30 on 0.31.0), `status`, `include_archive`, `archived_only`,
`exclude_empty`, `workspace_id`.

```bash
curl -s -H "Authorization: Bearer <token>" 'http://127.0.0.1:58627/api/v1/sessions?page_size=2'
```

Session object shape (0.31.0, fields truncated for brevity):

```json
{
  "id": "session_b6be6715-e7f3-450c-a103-a53463686d45",
  "workspace_id": "wd_spike_3e0ac6a88795",
  "title": "Run exactly this one command, then reply done: echo doc-verify-0290",
  "created_at": "2026-07-23T03:26:27.612Z",
  "updated_at": "2026-07-23T03:28:34.668Z",
  "busy": false,
  "main_turn_active": false,
  "pending_interaction": "none",
  "last_turn_reason": "completed",
  "archived": false,
  "last_prompt": "Run exactly this one command, then reply done: echo doc-verify-0290",
  "metadata": {"cwd": "/home/ubuntu/spike"},
  "agent_config": {"model": ""},
  "usage": {"input_tokens": 0, "output_tokens": 0, "cache_read_tokens": 0,
            "cache_creation_tokens": 0, "total_cost_usd": 0, "context_tokens": 0,
            "context_limit": 0, "turn_count": 0},
  "permission_rules": [],
  "message_count": 0,
  "last_seq": 0
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
  **`last_seq` on the list path is likewise still `0`** (re-verified
  2026-09-02 on 0.40.1) — but see Get one: since 0.40.1 the **detail**
  endpoint serves the real WS event watermark there.
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

Returns the same session object as the list, with one fix: **0.40.1 — the
detail path now reports the real event-stream watermark in `last_seq`**
(upstream commit `0f39b2cf`, "report the real event watermark as last_seq in
getSession"; verified live 2026-09-02 — `"last_seq": 4` on a fresh session,
`20` after two turns, matching the snapshot's `as_of_seq`; the create
response, the list, and fork/child responses still emit `0`). Re-verified
2026-09-04 on 0.41.0: a fresh session's first get reads `"last_seq": 1` (the
exact fresh value tracks the session's init records — treat any non-zero
value as "real"), `20` after several turns, still matching `as_of_seq`. A
client could
seed a WS resume cursor from it together with the snapshot's `epoch` family;
the Android app keeps using the snapshot's `as_of_seq`.

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

- The write goes through to `/status`: a following GET on the same session
  reported `"permission":"manual"` (was `"yolo"` before the write — fresh
  sessions start from the server's `default_permission_mode`, see
  `GET /api/v1/config` in Models).
- Quirk: the response's `agent_config` does **not** echo the written
  `permission_mode` (stays `{"model":""}` in the capture) — `/status`'s
  `permission` is the read-back, not the session object.
- The accepted enum is the wire enum `manual`/`yolo`/`auto` (label mapping
  recorded once at the 0.40.1 drift notes, commit `08a2fb00`; the per-prompt
  `permission_mode` override in Prompts takes the same values).

### Live status — `GET /api/v1/sessions/{id}/status`

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

### Session snapshot — `GET /api/v1/sessions/{id}/snapshot`

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
- `pending_approvals`: the pending permission requests. Each item is the
  legacy `event.approval.requested` payload minus the camelCase `agentId`
  stamp (up to 0.38.0 `approval_id` == `tool_call_id`; **0.39.0:**
  `approval_id` is a dedicated `approval_<uuid>` id distinct from
  `tool_call_id` — see Permissions). **2.0.2:** the items now carry
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

- `pending_questions`: the pending `AskUserQuestion` requests; each item is
  byte-identical to the `/questions?status=pending` poll's `items[]` entries
  (verified live with a live question, 2026-08-01): `question_id`,
  `session_id`, `questions[]` with
  `id`/`question`/`options[{id,label}]`/`header`/`allow_other`/`multi_select`,
  `created_at`, `turn_id`. **0.38.0:** `question_id` is a dedicated
  `question_<uuid>` interaction id and the item also carries a distinct
  `tool_call_id` (was: `question_id` == `tool_call_id` — see Questions).
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

- `{"type": "text", "text": "..."}` — user or assistant text.
- `{"type": "thinking", "thinking": "..."}` — assistant reasoning (may be empty).
- `{"type": "tool_use", "tool_call_id": "tool_...", "tool_name": "Bash", "input": {...}}` — assistant tool call.
- `{"type": "tool_result", "tool_call_id": "tool_...", "output": "..."}` — in a
  `role: "tool"` message; may also carry `"is_error": true`.

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
cancelled turn and persists in history. Sibling variant observed on **0.34.0**
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
history only ever carries the main agent). Verified live on 0.29.2
(2026-07-28), including a running sub-agent and the cold-session rebuild path;
re-verified on 0.31.0 (2026-07-30, `agent_id` paging included) and on 0.31.1
(2026-07-31 — `interruption` markers and real terminal turn states, see
below).

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

`data` fields beyond `agent_id`/`items`/`has_more` — `tasks`, `interactions`,
`attachments`, `todos`, `prompts`, `meta`, `agents`, `pending_interactions`,
`seq` — are
the session's **unpaginated global state**, shipped with every page (the
`agents` roster: `{agentId, type: "main"|"sub", parentAgentId?, label?}`).
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
  `"assistant"` observed — `text`), and `tool` (`frameId`, `toolCallId`,
  `name`, `state` `"running"`/`"done"`, `input` (args object), `output`).
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

Out of scope for the app (unused siblings under the same route prefix):
`/transcript/ops`, `/transcript/user-messages`, `/transcript/plan`.

## Search

### Global message search — `POST /api/v1/search`

New in 0.31.0 (verified live 2026-07-30; re-verified 2026-08-05 on 0.33.0 —
two-page token walk, same-generation replay, tampered/foreign-fingerprint
token rejections). Global full-text search over user
messages, assistant replies and session titles, across all sessions.

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
- **`swarm_mode` in the prompt body is DEAD on 0.43.1** (verified live
  2026-09-16, ticket 140 — throwaway session, deleted after): a prompt posted
  with `"swarm_mode": true` ran to completion with `/status` `swarm_mode`
  never flipping and no `swarm.enter` transcript marker. Upstream at tag
  0.43.1 the submitPrompt handler applies only
  `profile`/`model`/`thinking`/`permission_mode`/`disabled_tools`
  (`packages/kap-server/src/routes/prompts.ts`) — the field parses
  (`rest-prompt.ts`) but is never consumed; `plan_mode`, `goal_objective`
  and `goal_control` are likewise parsed-but-unapplied there (source-derived,
  not live-verified). Swarm mode DOES engage through the session profile
  route: `POST /api/v1/sessions/{id}/profile` with
  `{"agent_config":{"swarm_mode":true}}` flips `/status` `swarm_mode` to
  `true` (verified live on the same probe session; upstream
  `applySessionAgentConfig` → `swarm.enter('manual')`, Session profile).
- Optional body fields (per-prompt overrides): `permission_mode`
  (`manual`/`auto`/`yolo` — `manual` verified live), `model` (verified: a
  session with empty `agent_config.model` runs when the prompt carries one),
  `thinking`, `plan_mode`, `swarm_mode` (**dead on 0.43.1** — see above),
  `agent_id`, `metadata`,
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

## Steer

Steering injects a queued prompt into the currently running turn. The client
flow (re-verified 2026-07-23 on 0.29.0): while the session is busy, POST the
new prompt as usual (response `"status": "queued"`), then steer the returned
`prompt_id` into the active turn.

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

- **Idle session** (nothing to steer into): HTTP 200 with
  `{"code":40402,"msg":"no active prompt to steer into","data":null,...}`
  (re-verified 2026-07-23).
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
  payloads).

## Abort

### Abort current prompt — `POST /api/v1/sessions/{id}:abort`

Body: `{}`. Aborts the currently running prompt/turn of the session.

```bash
curl -s -X POST 'http://127.0.0.1:58627/api/v1/sessions/session_b6be6715...:abort' \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' -d '{}'
# {"code":0,"msg":"success","data":{"aborted":true},"request_id":"01KY6GFG4S5FPPXCFMP60X0FVH"}
```

Verified mid-run on 0.29.0 (2026-07-23), again on 0.29.2 (2026-07-27), again
on 0.31.0 (2026-07-30), and again on 0.31.1 (2026-07-31): the
WS then emits `turn.step.interrupted` (`reason: "aborted"`) → `turn.ended`
(`reason: "cancelled"`) → `event.session.work_changed` (idle,
`last_turn_reason: "cancelled"`) →
`prompt.aborted`. **0.31.1 additions to that sequence (verified live):**
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
`0.30.0` (2026-07-30, ticket 054); re-verified the same day on `0.31.0` with
one field-level drift (the summary message's `id` key — see the splice
paragraph below). **Beware, web-bundle recon is misleading
here: the `event.session.history_compacted` event the web client maps
(`before_seq` / `reason` / `summary_message_id`) was never emitted by this
server — a manual compact produces `compaction.started` /
`compaction.completed` instead (captures below).**

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

### WebSocket sequence

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
The prefix (verbatim, re-verified byte-identical 2026-09-04 on 0.41.0;
**0.42.0 amendment (commit `f12d59e0`, verified live 2026-09-09): a final
sentence was appended**, shown in bold):

> The conversation so far has been compacted to free up context. What
> follows is your own working summary of this task — use it to continue
> your train of thought rather than starting over. Treat it as notes, not
> proof: where it says a step was done, tests passed, or a fix worked,
> verify that yourself before relying on it. Any user messages earlier in
> this context are preserved verbatim from the compacted conversation;
> where a system-reminder note among them marks an omitted middle section,
> the user messages it replaced are covered by this summary. **The summary
> records which earlier requests were already addressed.**

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
- `GET .../status`: `busy` is true while the compaction runs;
  `context_tokens` / `context_usage` drop to the compacted size when it
  completes (26518 → 386 and 25851 → 275 in the two captures).

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

## Permissions

A permission request surfaces as **WebSocket events**; there is no polling
endpoint. The decision is posted back via REST.

1. A prompt sent with `permission_mode: "manual"` that needs a tool emits two
   WS events (see the WS section for exact payloads):
   `permission.approval.requested` (new v2 shape, camelCase) followed by the
   legacy `event.approval.requested` (snake_case, with `approval_id` and
   `expires_at` — 24 h validity observed).
   Both carry the human-readable action and a command preview.
   **0.39.0: the approval id split from the tool-call id** (verified live
   2026-08-27): the v2 frame's `id` and the legacy frame's `approval_id` are
   now a dedicated `approval_<uuid>` interaction id, **distinct** from the
   `toolCallId`/`tool_call_id` (`tool_…`) both frames also carry (they were
   equal up to 0.38.0 — the same split questions got in 0.38.0). The
   `awaiting_approval` phase of `agent.status.updated` carries both ids too.
   **2.0.2: the legacy frame and the snapshot `pending_approvals` items also
   carry `agent_id`** (snake_case, required — `"main"` or the asking
   sub-agent's id; verified live 2026-09-19), alongside the camelCase
   `agentId` stamp the WS frame already had.
   Pending approvals can also be polled —
   `GET /api/v1/sessions/{id}/approvals?status=pending` returns
   `{"code":0,...,"data":{"items":[<same item shape as the snapshot's
   pending_approvals>]}}` (verified live 2026-09-19 on 2.0.2).
2. Post the decision. **Only the `approval_<uuid>` id is accepted** —
   posting the `tool_call_id` answers
   `{"code":40404,"msg":"approval tool_… not found","data":null,...}`
   (verified live 2026-08-27; up to 0.38.0 either spelling worked because
   they were the same string):

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_b6be6715.../approvals/approval_92bb2ce7-0956-4791-ba68-ac8f8762093f \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"decision":"approved"}'
# {"code":0,"msg":"success","data":{"resolved":true,"resolved_at":"2026-08-27T19:54:53.787Z"},"request_id":"01M12CM9EJDYPKP9BT211FGFDP"}
```

Body fields: `decision` (`"approved"` | `"rejected"`; the 40001 error for an
invalid value lists a third accepted enum, `"cancelled"` — observed
2026-09-19 on 2.0.2, never exercised), optional `scope`,
`feedback`, `selected_label`. After the decision the WS emits
`event.approval.resolved` and `permission.approval.resolved`, then the tool
runs.

## Questions

The agent's user-question tool (`AskUserQuestion`) surfaces as **WebSocket
events**; the answer or dismissal is posted back via REST. Re-verified
2026-07-30 on 0.31.0 (same flow as
2026-07-27 on 0.29.2: surface + selected-option answer + dismiss + polling,
same session flow as 2026-07-23 on 0.29.0) by prompting a session to call the
tool — the question surfaced directly, with no approval gate, under the
session's default permission mode. Flow re-verified end-to-end on 0.32.0
(2026-08-05: surface + selected-option answer + dismiss + re-dismiss `40902` +
pending polling, all byte-identical), and the answer/dismiss routes plus the
new id convention on 0.38.0 (2026-08-20). Since 0.32.0 the resolve routes also
accept question ids containing **colons** (upstream #2585 — some
OpenAI-compatible providers emit tool-call ids like `AskUserQuestion:0`).
On 0.38.0 question ids are server-minted `question_<uuid>` strings (see step
1), so provider tool-call id shapes no longer reach the route at all.

1. Surface sequence: `tool.call.started` (`"name": "AskUserQuestion"`) →
   `event.session.work_changed` with `pending_interaction: "question"` →
   `event.question.requested` (verbatim payload in the WS section). **0.38.0
   drift: the question id is no longer the `toolCallId`** — `question_id` is
   now a dedicated interaction id (`question_<uuid>`), and the payload carries
   BOTH `question_id` and a distinct `tool_call_id` (verified live 2026-08-20:
   `question_a38aaf28-…` vs `tool_EwFXscDF0HraGfTelCTRdRe9`). Key
   resolve/dismiss routes and resolution bookkeeping off `question_id`; the
   `tool_call_id` is only the transcript row's id. Unlike
   approvals there is **no v2 camelCase duplicate** — only the
   `event.question.*` family exists. **2.0.2: the payload (WS frame, pending
   poll, snapshot item alike) also carries `agent_id`** (snake_case,
   optional in the schema, present on every 2.0.2 live capture — `"main"`
   or the asking sub-agent's id; verified live 2026-09-19).
2. While a question is pending, the session object shows
   `"pending_interaction": "question"`.
3. Pending questions can also be polled —
   `GET /api/v1/sessions/{id}/questions?status=pending` returns
   `{"code":0,...,"data":{"items":[<same payload as event.question.requested>]}}`.
   The `status` query param is mandatory and only accepts `"pending"`; without
   it: `{"code":40001,"msg":"status: Invalid input: expected \"pending\"",...}`.
   **`GET /api/v1/sessions/{id}/questions/{qid}` does not exist** (plain HTTP
   404, `{"statusCode":404,"error":"Not Found",...}` — re-verified 2026-07-23)
   — the single-question shape only exists inside the list item / WS event
   payload.

**Multi-select questions** (verified live 2026-08-02 on 0.31.1, against
`packages/protocol/src/question.ts` in the upstream repo): every question item
carries a **`multi_select` boolean** — **0.34.0: present only when `true`**
(single-select items omit the key entirely in the `event.question.requested`
payload, the `/questions?status=pending` poll, and the history `tool_use`
input; re-verified live 2026-08-06 — on 0.31.1 it appeared in both `true` and
`false` form). Treat absence as `false`; the protocol schema marks it
optional, and the session snapshot behaves the same. **0.43.1 observation
(2026-09-15):** the omission rule is input-dependent — when the model
passes `multi_select` explicitly in its `AskUserQuestion` input, the wire
item echoes it verbatim, so a single-select item can arrive with
`"multi_select":false` present (observed on both the
`event.question.requested` frame and the pending poll). Decoders must
accept both forms. The history
(`GET .../messages`) `tool_use` input for
`AskUserQuestion` also carries `multi_select` per question (when true), but its options
are **label-only** (no ids). The `skipped` answer kind and the optional
`method`/`note` body fields exist in the protocol schema (`skipped` omits the
item from the flattened `answers`; all-skipped degrades to the dismiss shape)
— source-verified only, never observed live.

4. Answer — `POST /api/v1/sessions/{id}/questions/{qid}`. `answers` keys are
   the per-question item ids from the request payload (`q_0`); values are a
   discriminated union on `kind` (single/other re-verified 2026-07-23, multi
   kinds verified live 2026-08-02 on 0.31.1):

```bash
# selected option (re-verified 2026-07-23)
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_b6be6715.../questions/tool_Isj4WDPaDkLQejGM4gDzWgQ3 \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"answers":{"q_0":{"kind":"single","option_id":"opt_0_1"}}}'
# free text (the question had "allow_other": true; re-verified 2026-07-23)
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_b6be6715.../questions/tool_J7bZIaNmzApVJZvKP0aQh9I5 \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"answers":{"q_0":{"kind":"other","text":"ramen"}}}'
# multi-select, two options (verified live 2026-08-02 on 0.31.1)
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_28ba6c7b.../questions/tool_t4BQ8GpCUJGg32O6PTAPEpdy \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"answers":{"q_0":{"kind":"multi","option_ids":["opt_0_0","opt_0_2"]}}}'
# multi-select + free text combined (verified live 2026-08-02 on 0.31.1)
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_28ba6c7b.../questions/tool_qRyyocUhF4wX6gCV6DqagJgx \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"answers":{"q_0":{"kind":"multi_with_other","option_ids":["opt_0_0"],"other_text":"Fish"}}}'
```

All four kinds return HTTP 200
`{"code":0,"msg":"success","data":{"resolved":true,"resolved_at":"..."},...}`.
On the wire: `event.session.work_changed` (`pending_interaction` back to
`"none"`) → `event.question.answered` → `tool.result` whose output is
`{"answers":{"<question text>":"<flattened answer>"}}` → the turn
continues. The flattened value is the option label for `single`, the raw text
for `other`, the selected labels **joined with `", "`** for `multi`
(`"Red, Blue"`, verified live 2026-08-02) and the labels followed by the free
text, same join, for `multi_with_other` (`"Dog, Fish"`, verified live
2026-08-02). Answering an already-resolved question returns
`{"code":40902,"msg":"question <qid> already resolved","data":{"resolved":false},...}`
(re-verified 2026-07-23; 0.27.0 returned `40405` "not found").

Two multi-answer caveats (both verified live 2026-08-02 on 0.31.1):

- `{"kind":"multi","option_ids":[]}` is refused with envelope code `40001`
  (`answers.q_0.option_ids: Too small: expected array to have >=1 items`) and
  the question **stays pending** — a multi answer needs at least one option.
- Option ids are **not validated** against the question's options: a bogus id
  resolves the question and flattens verbatim into the answer value
  (`{"answers":{"Which colors do you like?":"opt_0_9"}}`). Clients must
  validate selections themselves.

5. Dismiss — `POST /api/v1/sessions/{id}/questions/{qid}:dismiss`, body `{}`:

```json
{"code":40909,"msg":"question tool_nZX3S4ZZ6klG3ppFyIUKvUNa dismissed",
 "data":{"dismissed":true,"dismissed_at":"2026-07-23T03:37:06.177Z"},
 "request_id":"01KY6GRQW0TPCSD8GNAQ47VN7T"}
```

**Beware: a successful dismiss comes back over HTTP 200 with the non-zero
envelope code `40909`** — do not gate success on `code == 0` here; check
`data.dismissed`. Re-dismissing a resolved question also returns
`code:40902` ("already resolved"; re-verified 2026-07-23). On the wire:
`event.session.work_changed` (`"none"`) → `event.question.dismissed` →
`tool.result` with output
`{"answers":{},"note":"User dismissed the question without answering."}` → the
turn continues.

**Background questions (`background: true`) — 0.41.0 fix (commit `523d35b5`,
verified live 2026-09-04):** a background `AskUserQuestion` call no longer
dies with its turn (up to 0.40.1 it was cancelled at turn end). The
lifecycle matches a background task's:

- The question surfaces with the usual `event.question.requested` +
  `pending_interaction: "question"`, then the turn can end **without**
  resolving it. While it waits the session reads `busy: true` with
  `main_turn_active: false` and `pending_interaction: "question"` — a state
  combination earlier versions never produced (previously `busy` tracked
  `main_turn_active` outside compactions).
- The question registers in the main agent's task store: REST `/tasks`
  lists it with wire kind **`tool`** (`{"id":"question-agfe3d68",…,
  "kind":"tool","description":"<the question text>","run_in_background":true}`),
  and `task.started`/`background.task.started` ride the WS when it opens.
- Answering (the ordinary REST route, same `question_<uuid>` id) emits
  `event.question.answered` → `task.terminated` (core kind `question`) →
  `background.task.terminated` → a **task-origin notification turn** on the
  main agent: `turn.started` origin
  `{"kind":"task","taskId":"question-…","status":"completed","notificationId":"task:question-…:completed"}`,
  `task.notified` with `notificationType:"task.completed"` and title
  "Background question answered", and a `context.spliced` `<notification>`
  message that inlines the answer text (up to 0.40.1 the answer was saved
  to an output file for the agent to read).
- The background tool result text shrank to `task_id:` / `status:` /
  `next_step:` lines (the `description:`/`automatic_notification:` lines are
  gone) — tool prose, not wire shape.

## Todos

The agent's todo list has **no dedicated endpoint or WS event** on this
protocol. Clients derive it from the agent's `TodoList` tool calls, exactly as
the web client did (`apps/kimi-web/src/composables/latestTodos.ts` — path
valid up to 0.32.0; the folder was deleted in 0.33.0). Verified
against upstream source on `main` 2026-07-27
(`packages/agent-core-v2/src/agent/tools/todo-list/todoListTool.ts`,
`packages/agent-core-v2/src/session/todo/todoItem.ts`) **and captured live on
0.29.2** (2026-07-27: a three-step prompt produced `tool.call.started` with
`"name": "TodoList"` and args `{"todos": [{"status": "in_progress", "title":
"Run echo step-one"}, {"status": "pending", "title": "Run echo step-two"},
{"status": "pending", "title": "Run echo step-three"}]}`; history carried the
same `toolUse` part). The write contract was re-verified on 0.31.0
(2026-07-30); the read-only query variant (call without the `todos` key) was
not triggered live that run.

- **Tool name:** `TodoList` (upstream `TODO_LIST_TOOL_NAME`). It appears
  wherever tool calls do: `toolUse` content parts in message history, and live
  `tool.call.delta` / `tool.call.started` WS events.
- **Input schema:** `{"todos": [{"title": string, "status": "pending" |
  "in_progress" | "done"}]}`. Every *write* carries the **full** list.
- **Write vs read:** a call *without* the `todos` key is a read-only query —
  the list is unchanged; skip it and keep looking back. An empty `todos`
  array clears the list.
- **Derivation rule:** scan the messages backwards for the newest `TodoList`
  call whose input carries a `todos` array — that is the current state.
- **Not on this wire:** upstream also persists the list as
  `tools.update_store` records (key `todo`) and a transcript-level
  `TranscriptTodo` entity (`todo.upsert`); neither has been observed on the
  session WS protocol documented here.
- **Web-client tolerance (FYI):** `latestTodos.ts` also maps `completed` →
  `done` and falls back to a `content` field for the title, for Claude-style
  `TodoWrite` compatibility. This server's `TodoList` tool emits only the
  schema above; the Android app does not replicate those fallbacks.

The Android app implements this derivation in `TodoParser` + `ChatTimeline`
(ticket 042) and hides `TodoList` tool rows from the timeline in favour of a
summary widget.

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
`tavily_feedback`, back to 5; kiln 4).** Content drift from
this server's MCP config, not wire-shape drift; the builtin set is
unchanged. Also re-observed this run: the 0.31.1
liveness caveat below still holds verbatim — right after session create,
before any prompt ran, the catalog answered `{"tools":[]}`; it resolved to
the 37 entries only once a session had run a turn. Two MCP routes ride along (both verified live
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
(or `AgentSwarm`) tool. All shapes below verified live on `0.29.2`
(2026-07-28, throwaway session — pre-PrivateTmp cwd — prompts with
`permission_mode:"yolo"`) and re-verified on `0.31.0` (2026-07-30, throwaway
session in `/home/ubuntu/spike`) and on `0.34.0` (2026-08-06) — drift
since 0.29.2 is called out inline (spawn-time status republication, the
`agent_call` display, the git-context prefix, the background completion
delivery, the Agent tool-result trailer, the 0.34.0 model/effort fields and
the foreground task-store registration); fields not captured live are marked
as upstream-schema-only (`packages/kap-server/src/protocol/events-zod.ts`).
**2.1.1 (commit `895e9d9b`; verified live 2026-09-24):** the `subagent.*`
lifecycle events are now **durable journal records** — a cursor-resume
replay delivers them (a resume from seq 50 replayed `subagent.spawned`/
`started`/`cancelled` at seqs 68/69/84); up to 2.0.2 they were live-only,
so a disconnected client could only heal misses from REST `/tasks` and the
snapshot roster. Same payload shapes.

There are **two exposure paths**, chosen by the `Agent` call's
`run_in_background` argument:

- **Foreground** (default): the sub-agent exists on the WS event stream
  and in the session-snapshot roster; its messages never enter history.
  **0.34.0:** it also **registers in the task store while running** — listed
  by REST `/tasks` (`status:"running"`, `run_in_background:false` since
  0.39.0), fetchable by id (even once
  terminal), and cancellable via `:cancel` — but still with **no `task.*`
  WS frames**, and the list drops it again once terminal
  (`{"items":[]}` after a completed run still holds, even with
  `?status=completed`). On 0.33.0 and earlier it was never listed at all.
  **0.39.0:** a running foreground task can be converted to background with
  the new `:detach` action — from then on it gets `task.*` frames and stays
  listed (see REST `/tasks`).
- **Background** (`run_in_background: true`): registered in the main agent's
  background-task store, so it gets the full REST `/tasks` lifecycle plus
  `task.*` WS events, and its completion is pushed back into the main agent
  as a spliced `<notification>` user message.

Two id namespaces, both present in the frames:

- payload `agentId` — the **emitting** agent: `"main"` for the main agent,
  the sub-agent's server-assigned id (`agent-12`, `agent-13`, …) for the
  sub-agent's own turn frames.
- `subagentId` — a field inside the `subagent.*` lifecycle payloads (which
  are themselves emitted with `agentId:"main"`).

For background sub-agents the REST task id differs again (e.g.
`agent-7noib8bb` vs `agentId:"agent-13"`); `task.started`'s `info.agentId`
links the two.

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

### Foreground lifecycle — WS sequence

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

### Sub-agent turn frames share the session channel

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

### Background lifecycle — WS sequence

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

### REST `/tasks` — background tasks only

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

### Session snapshot roster

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
  'ws://127.0.0.1:58627/api/v1/ws?client_id=doc_verify_0290'
```

First server frame is `server_hello`; since 0.35.0 it always carries
`heartbeat_ms` (captured 2026-08-12 on 0.35.0):

```json
{"type":"server_hello","timestamp":"2026-08-12T19:27:55.907Z",
 "payload":{"ws_connection_id":"conn_01KZVQ44G3D5YNXTB65V0TRNZK","protocol_version":2,
            "heartbeat_ms":10000,
            "max_event_buffer_size":1000,
            "capabilities":{"event_batching":false,"compression":false}}}
```

### Heartbeat — back in 0.35.0

History: 0.23.3 sent app-level `ping` frames every ~30 s; the v2 protocol
(0.29.0 through 0.34.0) sent none. **0.35.0 reintroduces the heartbeat**
(upstream commit `619564dc`, "add WebSocket heartbeat to survive proxy idle
timeouts"; verified live 2026-08-12):

- The server sends a `ping` frame every `heartbeat_ms` (default **10000**),
  each with a fresh ULID `nonce`:

  ```json
  {"type":"ping","timestamp":"2026-08-12T19:28:05.908Z",
   "payload":{"nonce":"01KZVQ4E8M7Y73ME5EZQMFQ971"}}
  ```

- The client should answer with `{"type":"pong","payload":{"nonce":<echo>}}`.
  **Any inbound frame resets the server's liveness timer** — an unanswered
  `ping` is harmless as long as the client sends ordinary control traffic
  (subscribe, unsubscribe, …). The upstream docs keep `heartbeat_ms`
  "advisory, not required".
- After ~2 cycles of total client silence (~20 s at the default) the server
  closes the socket with code **`1001`**, reason **`"heartbeat timeout"`**.
  Observed live: a connection that subscribed once and then went silent got
  `ping`s at +10 s and +20 s, then the connection closed before the +30 s
  ping. Previously (0.29.0–0.34.0) the server never initiated a disconnect,
  so idle app sockets stayed open indefinitely — clients that only listen
  now reconnect every ~20 s unless they answer the pings.

### Subscribe to a session

Events are per-session; subscribe to receive them. One text frame = one JSON
message. Client frames carry a client-chosen `id` that the server mirrors in
its `ack`.

```json
++ client: {"type":"subscribe","id":"c_1","payload":{"session_ids":["session_b6be6715..."]}}
-- server: {"type":"ack","id":"c_1","code":0,"msg":"success",
            "payload":{"accepted":["session_b6be6715..."],
                       "not_found":[],"resync_required":[],
                       "cursors":{"session_b6be6715...":{"seq":2,"epoch":"ep_01KY6G589H3J9XHG6RF1RYAE59"}}}}
```

**Resume after disconnect** (re-verified on 0.32.0, 2026-08-05 — durable
replay from `seq 2` replayed 3–81 in order; on 0.31.0, 2026-07-30; on 0.29.2, 2026-07-27: subscribing
from `seq 135` replayed seqs 136–142 in order; on 0.29.0: from `seq 70`
replayed 71–78, from `seq 78` replayed the later question events):

```json
{"type":"subscribe","id":"r_1","payload":{"session_ids":["session_..."],
 "cursors":{"session_...":{"seq":70,"epoch":"ep_01KY6G589H3J9XHG6RF1RYAE59"}}}}
```

**Refused cursor** (re-verified on 0.32.0, 2026-08-05 — a future seq produced
the standalone frame then the `resync_required` ack, exactly as below; first
verified on 0.31.0, 2026-07-30): when the cursor cannot be
served (e.g. a future seq with a matching epoch), the server **first sends a
standalone frame**, then answers the subscribe with an `ack` that lists the
session in `resync_required` (the client must then drop its state and re-sync
via REST). The standalone frame, verbatim:

```json
{"type":"resync_required","timestamp":"...","payload":{"session_id":"session_7f33a76c-...","reason":"epoch_changed","current_seq":1,"epoch":"ep_01KYT7YRH68CT2NDATYYXZZT9A"}}
```

Note the reason string is `"epoch_changed"` even when only the seq was in the
future.

**Caveat — unsolicited frames (observed repeatedly on 0.31.0):** an
authenticated WS connection receives events for sessions it **never subscribed
to** (frames for other live sessions arrived with zero subscribe frames sent).
Clients MUST filter incoming events on `session_id`.

`unsubscribe` works the same way and gets its own `ack` (re-verified
2026-07-23: `{"type":"ack","id":"u_1","code":0,...,"payload":{"accepted":[],...}}`).

### Event envelope

Every event frame:

```json
{
  "type": "<event type>",        // mirrored inside payload.type
  "seq": 21,                     // per-session monotonically increasing
  "epoch": "ep_01KY6G589H3J9XHG6RF1RYAE59",
  "session_id": "session_...",
  "timestamp": "2026-07-23T03:28:34.668Z",
  "volatile": true,              // only on high-frequency deltas; absent otherwise
  "offset": 0,                   // byte offset of the delta in the accumulated text
  "payload": { "type": "<event type>", "...": "..." }
}
```

### Global events — `event.config.warning` (new in 0.32.0)

A **global** frame pushed to every established connection (no subscribe
needed) whenever the server's config-warning set (deprecated keys/env vars in
use, invalid sections) changes. It carries `session_id: "__global__"` and a
global `epoch` unrelated to any session's, and the payload repeats
`agentId: "main"` / `sessionId: "__global__"`. Delivery is live-only: late
joiners are not replayed, and an empty initial warning set publishes nothing —
silence means no warnings. An empty `warnings` array means the last warning
cleared. Verbatim captures (2026-08-05 on 0.32.0; triggered by adding the
deprecated `[loop_control] max_retries_per_step` key to `config.toml` and
removing it again):

```json
{"type":"event.config.warning","seq":17,"session_id":"__global__","timestamp":"2026-08-05T02:15:16.806Z",
 "payload":{"type":"event.config.warning",
  "warnings":[{"domain":"loopControl","message":"[loop_control] 'max_retries_per_step' is deprecated and no longer used; rename it to 'max_attempts_per_step'. Run /update-config to fix it."}],
  "agentId":"main","sessionId":"__global__"},
 "epoch":"ep_01KX01QDWWRZFHBCEEQ8A5SZ8T"}
```

```json
{"type":"event.config.warning","seq":18,"session_id":"__global__","timestamp":"2026-08-05T02:15:28.563Z",
 "payload":{"type":"event.config.warning","warnings":[],"agentId":"main","sessionId":"__global__"},
 "epoch":"ep_01KX01QDWWRZFHBCEEQ8A5SZ8T"}
```

Each item is `{domain?: string, message: string}`. Clients that ignore unknown
frame types (the Android app does) need no change; note the frame would also
fail any per-session `session_id` filter, which is the correct outcome.

### Global events — `event.config.changed` / `event.model_catalog.changed` (delivered since 0.39.1)

Both event types existed in the schema earlier but were **never forwarded to
WS clients**; 0.39.1 (commit `2bf7ed22`) wires the broadcaster up. Every
config write (any path — REST `POST /api/v1/config`, OAuth refresh, …)
pushes a global `event.config.changed` frame to every established
connection, debounced with a trailing edge. `changedFields` is **camelCase
domain names** (e.g. `["thinking"]` — up to 0.39.0 the never-delivered event
carried the raw snake_case request keys; upstream flags the rename as
breaking), and `config` is the full config projection with credentials
redacted to `has_api_key` (providers/models/services). Verified live
2026-08-28 (`POST /api/v1/config {"thinking":{"effort":"low"}}` → frame,
restored afterwards):

```json
{"type":"event.config.changed","seq":181,"session_id":"__global__","timestamp":"2026-08-28T19:46:13.649Z",
 "payload":{"type":"event.config.changed","changedFields":["thinking"],
  "config":{"providers":{"managed:kimi-code":{"type":"kimi","base_url":"https://api.kimi.com/coding/v1","has_api_key":true},…},
   "default_model":"kimi-code/k3","models":{…},…}},
 "epoch":"ep_01M14XPS0FE7GX6J2H5NA8MP52"}
```

`event.model_catalog.changed` rides the same forwarding path (provider model
refreshes; not triggered live). The Android app handles both since ticket 110:
the session-list socket maps the frame type alone (the payload is never
decoded) to a `SessionListEvent` ahead of the foreign-session filter, and the
session-list ViewModel drops the `ModelsRepository` models/config caches for
the current server URL, so the next read refetches. `event.config.warning`
below stays ignored.

### Global events — `event.session.archived` (new in 0.38.0)

Fires when a session is archived — on both the live and the cold archive path
(`POST /api/v2/sessions:archive` with either a loaded or a never-loaded
session). Global like `event.config.warning`: pushed to every established
connection with no subscribe needed, `session_id: "__global__"`, and the real
session id rides in the payload as **camelCase `sessionId`** (alongside
snake_case `workspace_id`). Delivery is live-only; there is no
session-deleted event. Verbatim capture (2026-08-20 on 0.38.0):

```json
{"type":"event.session.archived","seq":28,"session_id":"__global__","timestamp":"2026-08-20T19:31:40.052Z",
 "payload":{"type":"event.session.archived","workspace_id":"wd_spike_6baf4c130e63",
  "agentId":"main","sessionId":"session_a57d4784-4fe8-4268-bbe1-b5f3e23b37ba"},
 "epoch":"ep_01KX01QDWWRZFHBCEEQ8A5SZ8T"}
```

(Verification nit: on this server the frame arrived on every connection that
had sent at least one inbound frame (`client_hello` or a `subscribe`), while a
fully silent socket received nothing in three attempts. The Android app's
sockets always subscribe, so they qualify; a listen-only client should send a
`client_hello` after connect to be safe.)

**Restore fires no event at all** (verified 2026-09-14 on 0.42.0 — ticket
131): archiving a session pushes this frame, but `POST .../sessions/{id}:restore`
pushes nothing — no `event.session.restored`, no `event.session.updated`.
Other clients learn about restores by refetching (see Sessions → Archive /
Restore).

### Global events — `event.session.deleted` (new in 0.42.0)

Fires when a session is **deleted** (`POST /api/v1/sessions/{id}:delete` —
see Sessions → Delete). Global exactly like `event.session.archived`: no
subscribe needed, `session_id: "__global__"`, the real session id camelCase
in the payload alongside snake_case `workspace_id`. Verbatim capture
(2026-09-09 on 0.42.0):

```json
{"type":"event.session.deleted","seq":276,"session_id":"__global__","timestamp":"2026-09-09T19:33:22.675Z",
 "payload":{"type":"event.session.deleted","workspace_id":"wd_spike_6baf4c130e63",
  "agentId":"main","sessionId":"session_0e2d9419-353f-4e08-bb59-dbfdacac1467"},
 "epoch":"ep_01KX01QDWWRZFHBCEEQ8A5SZ8T"}
```

The Android app does not handle it — a deleted session's row lingers in
the list until the next refresh (ticket 126); the app has no delete UI.

The sibling workspace lifecycle events `event.workspace.created` / `updated`
(full wire workspace object) and `event.workspace.deleted`
(`{workspace_id, root}`) are global too — all out of the app's scope.

### Global events — `event.session.created`

Fires when a session is **created** from any client (`POST /api/v1/sessions`
— see Sessions → Create). Pushed to every established connection with no
subscribe needed, like the other `event.session.*` lifecycle frames — but
unlike them the envelope is NOT `__global__`: the frame is dispatched on the
new session's own event stream, so the envelope `session_id` IS the created
session's id and `seq`/`epoch` are that session's own (`seq` 1 below — fresh
journal). The payload carries the **full session object in the snake_case
REST shape** (identical to `GET /api/v1/sessions` items) under `session`,
plus the usual camelCase `agentId`/`sessionId`. Verbatim capture (2026-09-14
on 0.42.0):

```json
{"type":"event.session.created","seq":1,"session_id":"session_0fb965a3-733b-41a3-b863-05dc44881de5",
 "timestamp":"2026-09-14T10:18:49.155Z",
 "payload":{"type":"event.session.created","session":{"id":"session_0fb965a3-733b-41a3-b863-05dc44881de5",
  "workspace_id":"wd_spike_6baf4c130e63","title":"ticket-129-capture-source-2",
  "created_at":"2026-09-14T10:18:49.118Z","updated_at":"2026-09-14T10:18:49.118Z",
  "busy":false,"main_turn_active":false,"pending_interaction":"none","archived":false,
  "metadata":{"cwd":"/home/ubuntu/spike"},"agent_config":{"model":""},
  "usage":{"input_tokens":0,"output_tokens":0,"cache_read_tokens":0,"cache_creation_tokens":0,
   "total_cost_usd":0,"context_tokens":0,"context_limit":0,"turn_count":0},
  "permission_rules":[],"message_count":0,"last_seq":0},
  "agentId":"main","sessionId":"session_0fb965a3-733b-41a3-b863-05dc44881de5"},
 "epoch":"ep_01M2FPTCY3629JDWB3V096PW34"}
```

**`:fork` puts nothing on the wire** (verified live 2026-09-14 on 0.42.0:
three `:fork` calls with a subscribed listener produced zero frames, while a
plain create in the same window produced the capture above). The fork route
publishes the same core event (`packages/kap-server/src/routes/sessions.ts`),
but the v1 broadcaster dispatches `event.session.created` through the
per-session path (`sessionEventBroadcaster.ts`: `dispatchSessionEvent` →
`getLiveSessionById`), which requires a loaded session — and `fork()` never
materializes its result, so the frame is dropped server-side before reaching
any connection. `POST /sessions/{id}/children` (sub-agent children) is
dropped the same way, so child sessions never enter the session list through
this event. A session forked from ANOTHER client therefore still appears only
on the next refresh; the app's own forks insert the row locally (ticket 128).

The Android app handles the frame since ticket 129: parsed ahead of the
foreign-session filter (the new session's id is by definition never in the
subscribed set), the carried session upserted into the list at its
`updated_at` position.

### Captured round-trips

Full prompt with a manual-permission tool call, recorded 2026-07-23 on 0.29.0
and **re-verified byte-for-byte on 0.31.0 (2026-07-30)** (command:
`POST .../prompts` with `permission_mode: "manual"`, approval via REST as
above). Same skeleton as 0.28.1 (one nit: on the 0.29.2 capture `turn.started`
(seq 4) arrived *before* the busy `event.session.work_changed` (seq 5); treat
their relative order as unspecified). **0.31.0 addition:** an `agent.created`
frame now fires for the **main** agent — payload
`{"agentId":"main","sessionId":"..."}` — at the head of the session's first
prompt turn (this event was previously documented only in the sub-agent
flow):

```
seq 2   agent.status.updated       model/usage variant (now with thinkingEffort)
seq 3   session.meta.updated       title patch
seq 4   event.session.work_changed busy=true, main_turn_active=true
seq 5   turn.started               {"turnId": 0, "origin": {"kind": "user"}, "prompt": "<text>",
                                    "promptId": "msg_…" ← always present since 0.40.1}
seq 6   context.spliced            the new user message, inline
seq 7   turn.step.started          step 1; thinking.delta, tool.call.delta xN
seq 8   permission.approval.requested  v2 shape (approval id = toolCallId up to 0.38.0;
                                        a dedicated approval_<uuid> id since 0.39.0)
seq 9   event.session.work_changed pending_interaction "approval"
seq 10  event.approval.requested   legacy shape (approval_id, expires_at +24 h)
seq 11  event.session.work_changed pending_interaction back to "none" (decision posted)
seq 12  event.approval.resolved    after the REST decision
seq 13  permission.approval.resolved v2 duplicate
seq 14  tool.call.started + tool.progress (volatile stdout streaming)
seq 15  tool.result
seq 16  turn.step.completed        usage + finishReason "tool_use" + latency stats
seq 17  turn.step.started          step 2; thinking.delta, assistant.delta ("done")
seq 18  turn.step.completed
seq 19  turn.ended                 {"reason": "completed", "durationMs": ...}
seq 20  event.session.work_changed busy=false, last_turn_reason "completed"
seq 21  prompt.completed           {"promptId": "msg_...", "reason": "completed"}
```

Plain text prompt: same skeleton without the approval/tool events.
**`prompt.submitted` DOES exist** (this section's older claim was stale — it
is in the 0.38.0 broadcaster source and fires live on 0.39.0, verified
2026-08-27): right after `session.meta.updated`, echoing the submission
(`promptId`, `userMessageId`, `status:"running"`, `content`, `createdAt`).
A busy-session POST additionally fires **`prompt.queued`** (with
`queueLength`) and **0.39.0 adds `prompt.started`** when the prompt actually
begins executing (`{"promptId","agentId","sessionId"}`). The user message
itself still arrives inline in
`context.spliced`. `agent.status.updated` (volatile; model, context, usage —
plus a `phase` object, see payloads) and `session.meta.updated`
(title/lastPrompt patch) fire interleaved throughout, sharing the same `seq`
as the event they piggyback on.

Abort sequence (`POST :abort` mid-run, 2026-07-23):

```
seq 41  turn.step.interrupted      {"reason": "aborted"}
seq 42  turn.ended                 {"reason": "cancelled", "durationMs": 8085}
seq 43  event.session.work_changed busy=false, last_turn_reason "cancelled"
seq 44  prompt.aborted             {"promptId": "msg_...", "abortedAt": "..."}
```

Steer round-trip (2026-07-27 on 0.29.2, same skeleton as 2026-07-23 on
0.29.0): prompt A ("list 60 facts about the Moon") running, prompt B posted
while busy (`"status": "queued"`), then `POST prompts:steer` with B's id:

```
seq 28  prompt.steered             activePromptId=A, promptIds=[B], B's content inline
seq 29  turn.step.completed        the current step finishes normally
seq 30  context.spliced            the steered user message — carries a FRESH id on 0.29.2 (see below)
seq 31  turn.step.started          next step — SAME turn, no new turn.started
seq 33  turn.ended                 reason "completed"
seq 35  prompt.completed           promptId = A (the original active prompt)
```

Question round-trip (2026-07-23; the prompt asked the agent to use its
`AskUserQuestion` tool):

```
seq 50  tool.call.started          name "AskUserQuestion"
seq 51  event.session.work_changed pending_interaction "question"
seq 52  event.question.requested   question_id (== toolCallId before 0.38.0), questions inline
-- answer posted via REST --
seq 53  event.session.work_changed pending_interaction "none"
seq 54  event.question.answered    answers {"<question text>": "<label or free text>"}
seq 55  tool.result                output {"answers":{...}}; the turn continues
```

Dismiss path instead of the answer (2026-07-23): `event.session.work_changed`
(`"none"`) → `event.question.dismissed` → `tool.result` with output
`{"answers":{},"note":"User dismissed the question without answering."}`.

### Core event payloads (verbatim captures, re-verified 0.32.0)

**New user message** (`context.spliced`; `messages` carries full message
objects — `start`/`deleteCount` are splice coordinates into the context).
Unchanged:

```json
{"type":"context.spliced","seq":6,"epoch":"ep_...","session_id":"session_...","timestamp":"...",
 "payload":{"type":"context.spliced","start":0,"deleteCount":0,
  "messages":[{"role":"user","content":[{"type":"text","text":"Run exactly this one command, then reply done: echo doc-verify-0290"}],
               "toolCalls":[],"origin":{"kind":"user"},"id":"msg_01KY6G6B4A1EM8X8XRA02KJ3ZR"}],
  "agentId":"main","sessionId":"session_..."}}
```

**Steered user message** (`context.spliced` after `prompt.steered`) —
**changed again in 0.29.2: the spliced steered message carries an `id`
again** (0.29.0/0.29.1 dropped it). On 0.29.2 the id was a **fresh ULID**,
not the queued prompt's `msg_...` id (`msg_01KYJGTJCZPX81AMH663V8881Z` below
vs prompt id `msg_01KYJGT9HN3BCDQZ8MXG4AYH69`). **2.0.1 (verified live
2026-09-18):** the origin now reads `{"kind":"user","inTurn":true}` and the
id pairing changed — a **single** steer splices the message under the
**queued prompt's own id**, while a **multi-prompt** steer mints a fresh ULID
for the merged message (verbatim 2.0.1 captures after the 0.29.2 one). Treat
spliced-message ids as opaque (verbatim 0.29.2 capture, 2026-07-27):

```json
{"type":"context.spliced","seq":30,"epoch":"ep_...","session_id":"session_...","timestamp":"...",
 "payload":{"type":"context.spliced","start":6,"deleteCount":0,
  "messages":[{"role":"user","content":[{"type":"text","text":"Stop. Reply with exactly: steered-ok-0292"}],
               "toolCalls":[],"origin":{"kind":"user"},"id":"msg_01KYJGTJCZPX81AMH663V8881Z"}],
  "agentId":"main","sessionId":"session_..."}}
```

Verbatim 2.0.1 captures (2026-09-18) — single steer, where the spliced
message id **is** the queued prompt's id (`msg_01M2V0F8B40WBPS5J5QC2RPZJ9` is
the id the prompt POST returned):

```json
{"type":"context.spliced","seq":18,
 "payload":{"type":"context.spliced","start":4,"deleteCount":0,
  "messages":[{"role":"user","content":[{"type":"text","text":"STEERED-MARKER-201"}],
               "toolCalls":[],"origin":{"kind":"user","inTurn":true},
               "id":"msg_01M2V0F8B40WBPS5J5QC2RPZJ9"}],
  "agentId":"main","sessionId":"session_..."}}
```

multi-prompt steer, where the two queued prompts merge into one spliced
message under a fresh ULID id:

```json
{"type":"context.spliced","seq":116,
 "payload":{"type":"context.spliced","start":17,"deleteCount":0,
  "messages":[{"role":"user","content":[{"type":"text","text":"MULTI-STEER-A"},
                                        {"type":"text","text":"MULTI-STEER-B"}],
               "toolCalls":[],"origin":{"kind":"user","inTurn":true},
               "id":"msg_01M2V1PDZGA1RG0KQKQ6659RSF"}],
  "agentId":"main","sessionId":"session_..."}}
```

**Work state** (`event.session.work_changed`; `pending_interaction` observed:
`"none"`, `"approval"`, `"question"`; `last_turn_reason` present only when idle).
Unchanged:

```json
{"type":"event.session.work_changed","seq":9,
 "payload":{"type":"event.session.work_changed","busy":true,"main_turn_active":true,
            "pending_interaction":"approval","agentId":"main","sessionId":"session_..."}}
```

**Streaming text delta** (`assistant.delta`; `thinking.delta` identical except
`"type": "thinking.delta"`). Unchanged:

```json
{"type":"assistant.delta","seq":17,"epoch":"ep_...","volatile":true,"offset":0,
 "session_id":"session_...","timestamp":"2026-07-23T03:28:34.657Z",
 "payload":{"type":"assistant.delta","turnId":0,"delta":"done","agentId":"main",
            "sessionId":"session_..."}}
```

**Tool call argument streaming** (`tool.call.delta`, volatile; `argumentsPart`
fragments concatenate into the tool's JSON arguments). Unchanged.

**Tool call start** (`tool.call.started`; `description` is the human-readable
action, same text as the approval's `action`). Unchanged:

```json
{"type":"tool.call.started","seq":14,
 "payload":{"type":"tool.call.started","turnId":0,"toolCallId":"tool_RMgBIy1asIorg1eV8P3ZhHiL",
            "name":"Bash","args":{"command":"echo doc-verify-0290"},
            "description":"Running: echo doc-verify-0290",
            "display":{"kind":"command","command":"echo doc-verify-0290","cwd":"/home/ubuntu/spike","language":"bash"},
            "agentId":"main","sessionId":"session_..."}}
```

**Tool result** (`tool.result`): unchanged (`toolCallId`, `output`).

**Tool progress** (`tool.progress`, volatile — live tool output). Unchanged.
**Foreground tool calls only** — a Bash call with `run_in_background=true`
emits zero `tool.progress` frames (verified 2026-08-05 on 0.32.0); poll
REST `/tasks/{id}?with_output=true` instead (see REST `/tasks`).

```json
{"type":"tool.progress","seq":14,"volatile":true,
 "payload":{"type":"tool.progress","turnId":0,"toolCallId":"tool_RMgBIy1asIorg1eV8P3ZhHiL",
            "update":{"kind":"stdout","text":"doc-verify-0290\n"},"agentId":"main","sessionId":"session_..."}}
```

**Step completed** — unchanged (latency breakdown `llmRequestBuildMs`,
`llmServerFirstTokenMs`, `llmServerDecodeMs`, `llmClientConsumeMs` and
`rawFinishReason` all still present). **2.0.0 (first live observation
2026-09-17):** the optional **`llmClientBlockedMs`** (schema-declared since
0.43.1) is now routinely present — every `turn.step.completed` of this run's
round-trips carried it (e.g. `"llmClientBlockedMs":47`); earlier captures
never showed it:

```json
{"type":"turn.step.completed","seq":16,
 "payload":{"type":"turn.step.completed","turnId":0,"step":1,"stepId":"7adb2b03-...",
            "usage":{"inputOther":13677,"output":59,"inputCacheRead":11264,"inputCacheCreation":0},
            "finishReason":"tool_use","llmFirstTokenLatencyMs":35290,"llmStreamDurationMs":13,
            "llmRequestBuildMs":11,"llmServerFirstTokenMs":35279,"llmServerDecodeMs":9,
            "llmClientConsumeMs":4,"providerFinishReason":"tool_calls","rawFinishReason":"tool_calls",
            "agentId":"main","sessionId":"session_..."}}
```

**Step retrying** (`turn.step.retrying`) — **new in 0.31.0**: fires when an
LLM call is retried (e.g. provider 429); afterwards a fresh
`turn.step.started` with a **new `stepId`** re-fires for the same step number.
Verbatim:

```json
{"type":"turn.step.retrying","seq":21,"payload":{"type":"turn.step.retrying","turnId":1,"step":1,"stepId":"d09585d5-...","failedAttempt":1,"nextAttempt":2,"maxAttempts":10,"delayMs":592.08,"errorName":"APIProviderRateLimitError","errorMessage":"429 The engine is currently overloaded, please try again later","statusCode":429,"agentId":"main","sessionId":"session_..."}}
```

**turn.started / turn.ended** — `turn.started` carries `prompt`,
the prompt text. **0.31.1, additive:** `turn.ended` gained an optional
`interruptReason` (`"user_cancelled"` / `"aborted"` / `"max_steps"` /
`"error"` / `"filtered"` / `"blocked"`), present when a turn stops early —
a REST `:abort` reports `"user_cancelled"` (verbatim capture in Abort).
**0.41.0, additive (upstream wire manifest, commit `b6b9b374`; never
observed live):** `turn.ended` gained an optional **`stopReason`** string for
forced stops (e.g. the repeat breaker) — every 0.41.0 live capture,
completed and cancelled alike, omits it. **2.0.1, additive (commit
`3cc6b2a3`; verified live 2026-09-18):** `turn.ended` gained an optional
**`traceId`** (the turn trace id, carried for rating-survey telemetry) —
every `turn.ended` of this run's round-trips carried it, main and sub-agent
turns alike (e.g. `"traceId":"365206127174fa1a7979ea31f2e51554"`);
`turn.started` does not carry one.
**0.37.2, additive:** `turn.ended` gained optional **`time`** (epoch ms,
schema-declared — commit `3ded0808`); live, `turn.started` and
`prompt.completed` also emit a top-level `time` (epoch ms) without a matching
schema field. `turn.started` also gained optional **`promptId`** (the
submitter's `prompt_id`). **0.40.1:** `promptId` is now **always
populated** — the prompt-identity persistence work (commits
`cbe0a77f`/`58b74cfe`); verified live 2026-09-02 on a plain prompt with no
client-sent `prompt_id` (`"promptId":"msg_01M1HT2N9FF08KBJYYYQCM6N4V"` —
the server-minted prompt/user-message id — riding next to
`origin`/`prompt`). **0.38.0, additive (schema-only):** `turn.started`
gained optional **`promptAttachments`** — `[{kind:"image"|"video"|"audio",
fileId}]`, the session-media references carried by the prompt (commit
`38a5a934`, transcript attachment projection). **Not observed live on
0.38.0** (verified 2026-08-20: a prompt whose image part resolved to a
`session_media` upload emitted `turn.started` with neither `promptAttachments`
nor `promptId`) — treat it as a server-side projection detail the wire
withholds for now. Also observed live on 0.38.0: `assistant.delta` /
`thinking.delta` payloads now carry a top-level **`time`** (epoch ms), like
`turn.started`/`turn.ended`/`prompt.completed` since 0.37.2. The app ignores
all of them. Verbatim 0.37.2 frames
(one turn, seqs trimmed):

```json
{"type":"turn.started","seq":4,
 "payload":{"type":"turn.started","time":1787081841775,"turnId":0,"origin":{"kind":"user"},
            "prompt":"Reply with exactly: one-ok-0372",
            "agentId":"main","sessionId":"session_..."}}
{"type":"turn.ended","seq":9,
 "payload":{"type":"turn.ended","time":1787081845958,"turnId":0,"reason":"completed","durationMs":4182,
            "agentId":"main","sessionId":"session_..."}}
{"type":"prompt.completed","seq":11,
 "payload":{"type":"prompt.completed","time":1787081845959,"promptId":"msg_01M0B61Q2PTMSXVXRX0DSHJ2C1",
            "finishedAt":"2026-08-18T19:37:25.959Z","reason":"completed",
            "agentId":"main","sessionId":"session_..."}}
```

(`turn.ended.reason` observed: `"completed"`, `"cancelled"` after abort.)

**Agent status** (`agent.status.updated`, volatile) — two payload variants,
both still present. **0.29.0 changes, all additive:** the model/usage variant
can now carry `thinkingEffort`; the `phase` objects now carry `since` (epoch
ms) on active phases; the `awaiting_approval` inline approval now carries both
`approvalId` and `toolCallId` (**0.39.0:** `approvalId` is the dedicated
`approval_<uuid>`, no longer equal to `toolCallId` — the capture below is
from the pre-split era); and there is a **new phase kind `tool_call`**
(emitted while a tool executes). Observed kinds: `running`, `streaming`
(with `"stream": "thinking" | "tool_call"` — **0.31.0 adds
`"stream": "assistant"`** to the streaming-phase variant), `awaiting_approval`,
`tool_call`,
`ended` (with `at` instead of `since`), and — observed on **0.34.0**
(2026-08-06, during a REST `:abort`; possibly older) — **`interrupted`**:
`{"kind":"interrupted","turnId":2,"step":1,"reason":"aborted","at":...}` (with
`at`, like `ended`). **0.43.0, REMOVED:** the **`streaming` phase kind is
gone** (commit `56ed9549` — the phase is now projected at the kap-server
edge from domain events, `LegacyActivityTracker`; verified live
2026-09-14: zero `streaming` phases across this run's full round-trips,
deltas streaming throughout — the phase stays `running` while tokens
stream). The remaining kinds are `idle`/`running`/`tool_call`/`retrying`/
`awaiting_approval`/`interrupted`/`ended`; the `streaming` capture below
is historical. **0.31.0, additive:** the model/usage
variant's `usage` can be a **nested** object — per-model buckets plus
`total`/`currentTurn` aggregates:

```json
"usage":{"byModel":{"kimi-code/k3":{"inputOther":14102,"output":61,"inputCacheRead":11520,"inputCacheCreation":0}},"total":{...},"currentTurn":{...}}
```

The top-level `contextTokens` / `maxContextTokens` are still present alongside
it (verified). Note `turn.step.completed.usage` and
`subagent.completed.usage` remain the **flat** shape
(`{"inputOther":...,"output":...,"inputCacheRead":...,"inputCacheCreation":...}`)
— only `agent.status.updated` got the nested form:

```json
{"type":"agent.status.updated","seq":2,"volatile":true,
 "payload":{"type":"agent.status.updated","model":"kimi-code/k3","thinkingEffort":"high",
            "maxContextTokens":1048576,"usage":{},"contextTokens":0,
            "agentId":"main","sessionId":"session_..."}}
{"type":"agent.status.updated","seq":5,"volatile":true,
 "payload":{"type":"agent.status.updated",
            "phase":{"kind":"running","turnId":0,"step":0,"stepId":"","since":1784777223312},
            "agentId":"main","sessionId":"session_..."}}
{"type":"agent.status.updated","seq":7,"volatile":true,
 "payload":{"type":"agent.status.updated",
            "phase":{"kind":"streaming","turnId":0,"step":1,"stepId":"","stream":"thinking","since":1784777223312},
            "agentId":"main","sessionId":"session_..."}}
{"type":"agent.status.updated","seq":8,"volatile":true,
 "payload":{"type":"agent.status.updated",
            "phase":{"kind":"awaiting_approval","turnId":0,"step":1,
                     "approval":{"approvalId":"tool_RMgBIy1asIorg1eV8P3ZhHiL",
                                 "toolCallId":"tool_RMgBIy1asIorg1eV8P3ZhHiL"},
                     "since":1784777258760},
            "agentId":"main","sessionId":"session_..."}}
{"type":"agent.status.updated","seq":14,"volatile":true,
 "payload":{"type":"agent.status.updated",
            "phase":{"kind":"tool_call","turnId":0,"step":1,
                     "toolCallId":"tool_RMgBIy1asIorg1eV8P3ZhHiL","name":"Bash","since":1784777308442},
            "agentId":"main","sessionId":"session_..."}}
{"type":"agent.status.updated","seq":20,"volatile":true,
 "payload":{"type":"agent.status.updated",
            "phase":{"kind":"ended","turnId":0,"reason":"completed","durationMs":91353,"at":1784777314665},
            "agentId":"main","sessionId":"session_..."}}
```

**Session meta** (`session.meta.updated`; `patch` carries `title`,
`isCustomTitle`, `lastPrompt` — since 0.33.0 with session-dir paths in
`lastPrompt` redacted to `/home/ubuntu/.[redacted].txt`, see Sessions).
Otherwise unchanged:

```json
{"type":"session.meta.updated","seq":3,
 "payload":{"type":"session.meta.updated","title":"Run exactly this one command, then reply done: echo doc-verify-0290",
            "patch":{"title":"Run exactly this one command, then reply done: echo doc-verify-0290",
                     "isCustomTitle":false,"lastPrompt":"Run exactly this one command, then reply done: echo doc-verify-0290"},
            "agentId":"main","sessionId":"session_..."}}
```

**Permission requested — v2 shape** (`permission.approval.requested`,
camelCase; **no `expires_at`**). **0.39.0:** gained a dedicated
**`id: "approval_<uuid>"`** — up to 0.38.0 there was no id field and
`toolCallId` doubled as the approval id (fresh 0.39.0 capture, verified
2026-08-27):

```json
{"type":"permission.approval.requested","seq":276,
 "payload":{"type":"permission.approval.requested","time":1787860456869,
            "id":"approval_92bb2ce7-0956-4791-ba68-ac8f8762093f",
            "sessionId":"session_...","agentId":"main",
            "turnId":11,"toolCallId":"tool_m7jk00BDj5fLljgs8LRp9YCP",
            "toolName":"Bash","action":"Running: echo approval-probe",
            "display":{"kind":"command","command":"echo approval-probe","cwd":"/home/ubuntu/spike","language":"bash"},
            "toolInput":{"command":"echo approval-probe"}}}
```

**Permission requested — legacy shape** (`event.approval.requested`, still
emitted right after the v2 one; `expires_at` is `created_at` + 24 h).
**0.39.0:** `approval_id` is the same dedicated `approval_<uuid>` as the v2
frame's `id`, no longer equal to `tool_call_id` (fresh 0.39.0 capture,
verified 2026-08-27). **2.0.2:** the payload gained snake_case `agent_id`
(see Permissions; verbatim 2.0.2 capture in the header drift block):

```json
{"type":"event.approval.requested","seq":278,
 "payload":{"type":"event.approval.requested","sessionId":"session_...","agentId":"main",
            "approval_id":"approval_92bb2ce7-0956-4791-ba68-ac8f8762093f","session_id":"session_...",
            "turn_id":11,"tool_call_id":"tool_m7jk00BDj5fLljgs8LRp9YCP",
            "tool_name":"Bash","action":"Running: echo approval-probe",
            "tool_input_display":{"kind":"command","command":"echo approval-probe",
                                  "cwd":"/home/ubuntu/spike","language":"bash"},
            "created_at":"2026-08-27T19:54:16.870Z","expires_at":"2026-08-28T19:54:16.870Z"}}
```

Both are followed (after the REST decision) by `event.approval.resolved` →
`{"approval_id": "...", "decision": "approved", "resolved_at": "..."}` and its
`permission.approval.resolved` v2 duplicate (adds `"decision"` to the request
payload).

**Prompt finished** (0.37.2: the payload also carries `time`, epoch ms — see the
`turn.started`/`turn.ended` note above; otherwise unchanged):

```json
{"type":"prompt.completed","seq":21,
 "payload":{"type":"prompt.completed","promptId":"msg_01KY6G6B4A1EM8X8XRA02KJ3ZR",
            "finishedAt":"2026-07-23T03:28:34.667Z","reason":"completed",
            "agentId":"main","sessionId":"session_..."}}
```

**Prompt steered** (`prompt.steered`; `activePromptId` is the running prompt,
`promptIds` the steered-in queued ones, their content inline). **2.0.1,
additive (verified live 2026-09-18):** gained **`messageId`** — the id the
spliced steered message will carry. For a **single** steered prompt it equals
that prompt's queued id; for a **multi-prompt** steer it is a fresh ULID
minted for the merged message. Verbatim 2.0.1 captures — single steer:

```json
{"type":"prompt.steered","seq":15,
 "payload":{"type":"prompt.steered","time":1789760356644,
            "activePromptId":"msg_01M2V0F4B5H2Z9CRZE8ZE4SFW2",
            "promptIds":["msg_01M2V0F8B40WBPS5J5QC2RPZJ9"],
            "messageId":"msg_01M2V0F8B40WBPS5J5QC2RPZJ9",
            "content":[{"type":"text","text":"STEERED-MARKER-201"}],
            "steeredAt":"2026-09-18T19:39:16.644Z","agentId":"main","sessionId":"session_..."}}
```

multi-prompt steer:

```json
{"type":"prompt.steered","seq":106,
 "payload":{"type":"prompt.steered","time":1789761632240,
            "activePromptId":"msg_01M2V1P9SMCNP2W645DYSSMNN2",
            "promptIds":["msg_01M2V1PDS79PZE7QERNKYNY208","msg_01M2V1PDV6A77QW94DX088AMW9"],
            "messageId":"msg_01M2V1PDZGA1RG0KQKQ6659RSF",
            "content":[{"type":"text","text":"MULTI-STEER-A"},{"type":"text","text":"MULTI-STEER-B"}],
            "steeredAt":"2026-09-18T20:00:32.240Z","agentId":"main","sessionId":"session_..."}}
```

The matching `turn.steer` frames carry the same `messageId` plus `promptIds`,
`turnId` and an `inTurn:true` origin — verbatim (single steer, seq 19):

```json
{"type":"turn.steer","seq":19,
 "payload":{"type":"turn.steer","time":1789760393742,"turnId":0,
            "input":[{"type":"text","text":"STEERED-MARKER-201"}],
            "origin":{"kind":"user","inTurn":true},
            "messageId":"msg_01M2V0F8B40WBPS5J5QC2RPZJ9",
            "promptIds":["msg_01M2V0F8B40WBPS5J5QC2RPZJ9"],
            "agentId":"main","sessionId":"session_..."}}
```

Pre-2.0.1 capture (no `messageId`; 0.29.x era):

```json
{"type":"prompt.steered","seq":28,
 "payload":{"type":"prompt.steered","activePromptId":"msg_01KY6GC4TTDXY3WYGGAWQ50ZQ5",
            "promptIds":["msg_01KY6GCAQF1655KB27NSFBGWZ2"],
            "content":[{"type":"text","text":"Stop. Reply with exactly: steered-ok-0290"}],
            "steeredAt":"2026-07-23T03:30:19.589Z","agentId":"main","sessionId":"session_..."}}
```

**Question requested** (`event.question.requested`; snake_case;
no v2 duplicate). **0.38.0 drift (verified live 2026-08-20): `question_id` is
now a dedicated `question_<uuid>` interaction id, distinct from
`tool_call_id`** — both fields are present (before 0.38.0 they were equal).
**2.0.2:** the payload gained snake_case `agent_id` (see Questions;
verbatim 2.0.2 item in the header drift block). `options[].description`
is omitted when the option has none; `header` is optional and was absent
entirely from the 2026-07-23 captures (key order is not stable either — never
rely on it). `multi_select` is present per item in both `true` and `false`
form (verified live 2026-08-02 on 0.31.1; **0.34.0: present only when
`true`**). Fresh 0.38.0 capture (2026-08-20):

```json
{"type":"event.question.requested","seq":10,
 "payload":{"type":"event.question.requested","question_id":"question_a38aaf28-7dfe-489d-bca3-ea43c93b86fa",
            "session_id":"session_...","turn_id":0,
            "tool_call_id":"tool_EwFXscDF0HraGfTelCTRdRe9",
            "questions":[{"id":"q_0","question":"Pick a color?",
                          "options":[{"id":"opt_0_0","label":"Red"},
                                     {"id":"opt_0_1","label":"Blue"}],
                          "allow_other":true}],
            "created_at":"2026-08-20T19:33:45.150Z","agentId":"main","sessionId":"session_..."}}
```

0.29.0 capture for comparison:

```json
{"type":"event.question.requested","seq":52,
 "payload":{"type":"event.question.requested","question_id":"tool_Isj4WDPaDkLQejGM4gDzWgQ3",
            "session_id":"session_...","turn_id":3,
            "tool_call_id":"tool_Isj4WDPaDkLQejGM4gDzWgQ3",
            "questions":[{"id":"q_0","question":"Red or blue?",
                          "options":[{"id":"opt_0_0","label":"red","description":"warm color"},
                                     {"id":"opt_0_1","label":"blue","description":"cool color"}],
                          "allow_other":true}],
            "created_at":"2026-07-23T03:33:08.490Z","agentId":"main","sessionId":"session_..."}}
```

**Question answered** (`event.question.answered`; `answers` is keyed by
**question text**, values are option labels or free text — multi-select
answers flatten to labels joined with `", "`, free text appended the same way
for `multi_with_other`; verified live 2026-08-02). Single/other unchanged —
selected option and free text both re-verified 2026-07-23:

```json
{"type":"event.question.answered","seq":54,
 "payload":{"type":"event.question.answered","question_id":"tool_Isj4WDPaDkLQejGM4gDzWgQ3",
            "answers":{"Red or blue?":"blue"},"resolved_at":"2026-07-23T03:33:36.757Z",
            "agentId":"main","sessionId":"session_..."}}
{"type":"event.question.answered","seq":88,
 "payload":{"type":"event.question.answered","question_id":"tool_J7bZIaNmzApVJZvKP0aQh9I5",
            "answers":{"What is your favorite food?":"ramen"},"resolved_at":"2026-07-23T03:38:18.621Z",
            "agentId":"main","sessionId":"session_..."}}
```

**Question dismissed:**

```json
{"type":"event.question.dismissed","seq":71,
 "payload":{"type":"event.question.dismissed","question_id":"tool_nZX3S4ZZ6klG3ppFyIUKvUNa",
            "dismissed_at":"2026-07-23T03:37:06.177Z","agentId":"main","sessionId":"session_..."}}
```

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
