# Kimi Code server wire protocol (core loop)

Wire protocol of the HTTP+WebSocket server embedded in the `kimi` binary, as used
by Kimi Code Web. Documented and verified against the live server on 2026-08-18
(server version `0.37.2`, `"backend": "v2"`, WS `protocol_version: 2`).

Scope: core loop + user interaction — auth check, sessions, messages, prompts,
steer, questions, abort, permissions, WebSocket events, file attachments,
sub-agents (foreground lifecycle + background task store), global message
search, the tools catalog, plus
the workspace list used by the mobile create-session flow. Explicitly skipped:
`fs:*`, terminals, cron, goals, providers, skills.

Every endpoint below was executed against the live server; the exact commands
used are shown with each section. Secrets are redacted (`<token>`).

**Protocol history:** this document previously described server `0.36.1`
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
  with `details`) and `40406` envelopes still omit it. Ignore it either way.

## Meta

`GET /api/v1/meta` — server identity and capabilities. Requires the token.

```bash
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:58627/api/v1/meta
```

```json
{
  "code": 0, "msg": "success",
  "data": {
    "server_version": "0.37.2",
    "capabilities": {"websocket": true, "file_upload": true, "fs_query": true, "mcp": true, "tasks": true, "terminal": true},
    "server_id": "01M0B58VCJKD97XHSQN45VWE90",
    "started_at": "2026-08-18T19:23:46.962Z",
    "open_in_apps": [],
    "dangerous_bypass_auth": false,
    "backend": "v2",
    "experimental_flags": {"secondary-model": false, "auto_session_title": false, "tool-select": false, "persistence_minidb_readmodel": true, "search_worker": true}
  },
  "request_id": "01M0B59KZ0TMNAB51M7CJT62PC"
}
```

- **`experimental_flags` is new in 0.32.0** (additive): the effective
  server-side experiment switches. Flag *values* are config, not protocol —
  on 0.37.2 (2026-08-18) `persistence_minidb_readmodel` and `search_worker`
  read `true`, and `auto_session_title` (`false` here) gates server-side
  session-title generation. Treat every flag as optional.
- **`web_title` is new in 0.37.2** (additive): the instance's `--web-title`
  browser-tab label, present only when the server was started with that flag;
  omitted here (verified live 2026-08-18). Treat it as an optional instance
  label.

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
  the server may change it independently of protocol drift. The catalog above
  is the 0.31.0 capture; **0.37.2 adds four `deepseek/*` entries**
  (`deepseek/deepseek-v4-flash`, `-pro`, `-chat`, `-reasoner`; all
  `max_context_size: 1000000`, no `support_efforts`) — additive.
- `GET`/`POST /api/v1/config` also carries a **`secondary_model`** field (new
  in 0.31.0, additive): `{}` on this server (2026-07-30). It is writable via
  `POST /api/v1/config`; per upstream, the POST body is snake_case while the
  GET echoes camelCase for some fields. The Android app consumes neither.
  **0.36.0:** the key is **absent** from this server's GET response (the
  experiment is off and nothing is configured), and the response gained a
  **`subagent`** object (`{"timeoutMs": 7200000}` here — the subagent
  idle-timeout section, camelCase; upstream commit `c9bfe8b2`).

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
- `message_count` and `last_seq` are still present but observed stuck at `0`
  (re-verified 2026-07-23 after several turns) — do not rely on them.
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
placeholder. There is no delete endpoint: `DELETE /api/v1/sessions/{id}`
answers plain HTTP 404 (re-verified 2026-07-23).

### Get one — `GET /api/v1/sessions/{id}`

Returns the same session object as the list.

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
    "context_tokens": 25050,
    "max_context_tokens": 1048576,
    "context_usage": 0.023889541625976562
  },
  "request_id": "01KY6GB8PGD0PV9FGE5XPX55Z3"
}
```

`busy: true` ⇔ the session is mid-turn. `model` is omitted entirely when the
session has no model configured. `thinking_level` and `permission` reflect the
session's current config (`"off"`/`"yolo"` observed on a fresh session;
**0.34.0:** a fresh session now reports `thinking_level: ""`). **0.34.0:**
`max_context_tokens` is now **omittable when the context limit is unknown**
(upstream schema change — `0` is the engine's "unknown" marker, never a real
limit); never observed omitted live — a fresh model-less session reports the
default model's context (262144 on 0.34.0–0.36.1; **1000000 since 0.37.2**,
the `deepseek/deepseek-v4-pro` default) and a k3 session 1048576 (re-verified
2026-08-18). Clients should
still treat an absent/zero limit as "unknown", never render a 0/0 ring.

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
  `/status` naming (`context_tokens`/`max_context_tokens`). **0.32.0 drift:**
  `session.usage` is the server's all-zero placeholder on every read path
  (every field zeroed — verified live 2026-08-05 on busy AND idle sessions
  with 48k–121k real context tokens per `/status`; on 0.31.1 only idle
  sessions zeroed both). kimi-web documents the same
  (`isPlaceholderSessionUsage`: "both engines return placeholders for the
  heavy session fields on the list/snapshot read paths"). The context fill's
  only live sources are `GET .../status` and the WS `agent.status.updated`
  stream — clients must treat the placeholder as "unknown" (never render a
  0/0 ring) and re-read `/status` to converge, like both official clients.
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
- `pending_approvals`: the pending permission requests — the ONLY REST read
  that exists for approvals. Each item is the legacy
  `event.approval.requested` payload verbatim (`approval_id` ==
  `tool_call_id`; 2026-08-01 capture):

  ```json
  {"approval_id":"tool_EYGD…","session_id":"session_…","turn_id":0,
   "tool_call_id":"tool_EYGD…","tool_name":"Bash","action":"Running: sleep 90",
   "tool_input_display":{"kind":"command","command":"sleep 90","cwd":"/tmp","language":"bash"},
   "created_at":"2026-08-01T…","expires_at":"2026-08-01T…"}
  ```

- `pending_questions`: the pending `AskUserQuestion` requests; each item is
  byte-identical to the `/questions?status=pending` poll's `items[]` entries
  (verified live with a live question, 2026-08-01): `question_id` ==
  `tool_call_id`, `session_id`, `questions[]` with
  `id`/`question`/`options[{id,label}]`/`header`/`allow_other`/`multi_select`,
  `created_at`, `turn_id`.

### Session table (v2) — `GET /api/v2/sessions`

**New in 0.34.0** (upstream #2640/#2644, built for the kimi-inspect tool's
spreadsheet-like session table). GET-only (`POST` → plain 404), wrapped in
the **v1 response envelope**. A leaner, cold-storage-friendly session
listing: epoch-**ms** timestamps, `null` (not absent) unset fields, no usage
block. The Android app does not use it; documented for completeness
(verified live 2026-08-06).

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

- Query params (0.37.2 additions marked): `page_size` (1–100, default 50;
  `0`/over-100 → `40001`; **with `fields=id,archived` the ceiling relaxes to
  10000**), `sort` (`meta.updated_at_desc` default | `meta.updated_at_asc` |
  `meta.created_at_desc`; anything else → `40001` listing the options),
  `page_token` (opaque base64 **v1-fingerprint keyset cursor** — decodes to
  `{"v":1,"f":"<fingerprint>","k":[<updated_at_ms>,"<session_id>"]}`;
  multi-page continuation verified, no overlap), **`page` (0.37.2: stateless
  1-based page number; mutually exclusive with `page_token` — combining them →
  `40001`; `next_page_token` is always `null` in this mode)**,
  **`meta.updated_before` (0.37.2: epoch ms upper bound on `meta.updated_at`)**,
  and **`fields` (0.37.2: only `id,archived`, trimming each item to
  `{id, archived}`; any other field/combination → `40001`; not combinable with
  `include=git`)**. Items are ordered by
  `meta.updated_at` desc by default. Unknown params (`limit`, `cursor`,
  `workspace_id`, `archived`, `q`, …) are **silently ignored**.
- Bad page tokens are a **new error code `40922`** (HTTP 200, envelope;
  distinct from search's `40001`): undecodable → `"page_token is corrupted;
  discard it and restart from the first page"`; valid-base64-but-foreign →
  `"page_token does not match the query conditions; …"`.
- `activity.status` observed only as `"idle"` on this server; `title` /
  `last_prompt` are `null` when unset.
- **0.37.2:** every page carries **`total`** (the size of the filtered set);
  with `fields=id,archived` each item is just `{id, archived}` (verified live).

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
`attachments`, `todos`, `meta`, `agents`, `pending_interactions`, `seq` — are
the session's **unpaginated global state**, shipped with every page (the
`agents` roster: `{agentId, type: "main"|"sub", parentAgentId?, label?}`). The
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
  0.31.1, see below), `origin`, `steps`. `origin` is `{"kind":"user"}`
  for user-prompted turns — those additionally carry a top-level **`prompt`**
  field with the prompt text (seen on main-agent turns) — or
  `{"kind":"other","payload":{"kind":"system_trigger","name":"subagent"}}` for
  a sub-agent's internal turns (the task turn and the forced result-summary
  turn; **no** `prompt` field — the task text is not in the transcript turns)
  — or a **`task` origin** on a background-task completion-notification turn
  (see the verification below).
- Step: `stepId` (`"t0.1"`), `turnId`, `ordinal`, `state`, `frames`.
- Frames: `thinking` (`frameId`, `text`), `text` (`frameId`, `role` —
  `"assistant"` observed — `text`), and `tool` (`frameId`, `toolCallId`,
  `name`, `state` `"running"`/`"done"`, `input` (args object), `output`).
  A step can hold several frames of the same kind (two `text` frames seen).
- **Warm vs cold rebuild:** answers computed from the live session add
  `startedAt`/`endedAt` on turns and steps, `finishReason`/`usage`/`timing` on
  steps, and `inputText`/`display`/`progress` on tool frames; the cold-session
  rebuild from persisted wire records ships only `turnId`/`ordinal`/`state`/
  `origin`/`steps` on turns and the core frame fields (`input`+`output` on
  finished tool frames). Treat every field outside the core set as optional.
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
the returned `file_id` in a prompt's `content` items (see Prompts below). The
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
`40406` "file not found". For media uploaded through `POST /api/v1/files` the
`file_id` is the same `f_…` id, so the legacy `GET /api/v1/files/{id}` still
serves it (verified live: both returned the same 70 bytes for a 1×1 png); the
session-media route is the canonical path and the only one that works for media
that never passed through the daemon upload store.

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
  send (text/image/file items verified live 2026-07-24; the schema is
  `packages/protocol/src/message.ts`):
  - `{"type":"text","text":"…"}`
  - `{"type":"image","source":{"kind":"file","file_id":"f_…"}}` — for uploaded
    `image/*` files. (`url` and `base64` source kinds also exist in the
    schema.)
  - `{"type":"video","source":{"kind":"file","file_id":"f_…"}}` — uploaded
    `video/*`.
  - `{"type":"file","file_id":"f_…","name":"…","media_type":"…","size":123}` —
    any other uploaded file. The web client picks the kind purely by MIME
    prefix (`image/*` → image, `video/*` → video, else file).
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
- Optional body fields (per-prompt overrides): `permission_mode`
  (`manual`/`auto`/`yolo` — `manual` verified live), `model` (verified: a
  session with empty `agent_config.model` runs when the prompt carries one),
  `thinking`, `plan_mode`, `swarm_mode`, `agent_id`, `metadata`,
  `goal_objective`, `goal_control`, `disabled_tools`, `prompt_id` (**0.37.2**:
  a client-chosen prompt record id echoed on the ACK `prompt_id`/
  `user_message_id`, the history message id, and `turn.started.promptId`;
  reusing one already in the agent's history answers `40927`
  `prompt.id_conflict`), and `skills` (**0.37.2**:
  `[{"name":"…","args":"…"}]` bundled skill activations — the app sends none;
  skills stay out of scope).
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
  section): `prompt.steered` → the current step runs to `turn.step.completed`
  → the steered user message arrives via `context.spliced` → a new
  `turn.step.started` **in the same turn** (no new `turn.started`) →
  `turn.ended` `"completed"` → a single `prompt.completed` carrying the
  **original** active prompt id. The agent then follows the steered
  instruction. Re-verified 2026-07-27 on 0.29.2.

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

The spliced summary text is prefixed with model-facing instructions that are
**not** persisted — history stores the bare summary (verified, see below).
The prefix (verbatim):

> The conversation so far has been compacted to free up context. What
> follows is your own working summary of this task — use it to continue
> your train of thought rather than starting over. Treat it as notes, not
> proof: where it says a step was done, tests passed, or a fix worked,
> verify that yourself before relying on it. Any user messages earlier in
> this context are preserved verbatim from the compacted conversation;
> where a system-reminder note among them marks an omitted middle section,
> the user messages it replaced are covered by this summary.

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
   `permission.approval.requested` (new v2 shape, camelCase; **the approval id
   is the `toolCallId`**) followed by the legacy `event.approval.requested`
   (snake_case, with `approval_id` and `expires_at` — 24 h validity observed).
   Both carry the human-readable action and a command preview.
2. Post the decision (either id form works — they are the same id):

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_b6be6715.../approvals/tool_RMgBIy1asIorg1eV8P3ZhHiL \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"decision":"approved"}'
# {"code":0,"msg":"success","data":{"resolved":true,"resolved_at":"2026-07-23T03:28:28.438Z"},"request_id":"01KY6G8Y8F6EJC7XH8KYZ4F2E5"}
```

Body fields: `decision` (`"approved"` | `"rejected"`), optional `scope`,
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
pending polling, all byte-identical). Since 0.32.0 the resolve routes also
accept question ids containing **colons** (upstream #2585 — some
OpenAI-compatible providers emit tool-call ids like `AskUserQuestion:0`, and
question id == tool-call id); with this provider's ULID ids nothing changes.

1. Surface sequence: `tool.call.started` (`"name": "AskUserQuestion"`) →
   `event.session.work_changed` with `pending_interaction: "question"` →
   `event.question.requested` (verbatim payload in the WS section). **The
   question id is the `toolCallId`** (same convention as approvals). Unlike
   approvals there is **no v2 camelCase duplicate** — only the
   `event.question.*` family exists.
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
optional, and the session snapshot behaves the same. The history
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

Lists the agent's builtin tools. Undocumented upstream until now; verified
live on 0.31.0 (2026-07-30) and again on 0.31.1 (2026-07-31), 0.33.0
(2026-08-05) and 0.34.0 (2026-08-06). Response
`data.tools[]` entries carry only
`{name, description, source: "builtin", active: true, input_schema: null}` —
**input schemas are not exposed** (so tool-parameter drift, e.g. the
`TaskOutput` `block`/`timeout` removal, is not observable here). 27 builtin
tools observed on 0.34.0 (**`ReadMediaFile` is back** — removed in 0.33.0,
restored in 0.34.0): Agent, AgentSwarm, AskUserQuestion, Bash,
CreateGoal,
CronCreate/Delete/List, Edit, EnterPlanMode, ExitPlanMode, FetchURL, GetGoal,
Glob, Grep, Read, ReadMediaFile, select_tools, SetGoalBudget, Skill, TaskList,
TaskOutput, TaskStop, TodoList, UpdateGoal, WebSearch, Write. **The count is
session-state-dependent:** fresh never-prompted sessions report 26 (no
`ReadMediaFile`) — the catalog is resolved from the live agent's state.

**Liveness caveat (verified 2026-07-31):** the route resolves the catalog from
a live session's agent (the `session_id` query param, else the most recent
session). When NO session is live — e.g. right after a server restart, before
any session is opened — it answers `{"code":0,"data":{"tools":[]}}`, not an
error. The 0.31.1 Agent/AgentSwarm schema change (the `model` parameter is
stripped while the secondary-model experiment is off) is therefore both
invisible here and irrelevant to clients of this endpoint.

```bash
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:58627/api/v1/tools
```

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

There are **two exposure paths**, chosen by the `Agent` call's
`run_in_background` argument:

- **Foreground** (default): the sub-agent exists on the WS event stream
  and in the session-snapshot roster; its messages never enter history.
  **0.34.0:** it also **registers in the task store while running** — listed
  by REST `/tasks` (`status:"running"`), fetchable by id (even once
  terminal), and cancellable via `:cancel` — but still with **no `task.*`
  WS frames**, and the list drops it again once terminal
  (`{"items":[]}` after a completed run still holds, even with
  `?status=completed`). On 0.33.0 and earlier it was never listed at all.
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

The tool result is plain text. Foreground (waits for completion):

```
agent_id: agent-12
actual_subagent_type: explore
status: completed

[summary]
<the sub-agent's result summary>
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
`subagent.*` events carry only `subagentId`). **0.31.0:** immediately after
`subagent.spawned` (same seq, volatile) the server **republishes the
sub-agent's `agent.status.updated`** — a duplicate of the one that follows
`agent.created`. **0.34.0 (additive):** the payload gained **`model`** and
**`thinkingEffort`** (camelCase; the spawned child inherits the session's
bound model — `"kimi-code/k3"`/`"high"` observed on both foreground and
background spawns; **0.36.0:** `"low"` observed — the effort follows the
session's thinking config, re-verified 2026-08-13). The pair, verbatim (0.34.0):

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

Spawn half (0.29.2 capture, unchanged on 0.31.0):

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
`task.notified` (verbatim shape): `{"notificationType":"task.completed" |
"task.killed","title":"...","body":"...","severity":"info" | "warning",
"sourceKind":"background_task","sourceId":"<taskId>"}`.

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

Task object (verbatim, 0.36.1 — subagent-kind objects carry `model`/
`thinking_effort` (snake_case, since 0.34.0) and, **new in 0.36.1**,
`agent_id`, `subagent_type` and `parent_tool_call_id`, in list and get,
running and terminal):

```json
{"id":"agent-l38j2zce","session_id":"session_...","kind":"subagent",
 "description":"doc-verify","status":"completed",
 "created_at":"2026-08-14T19:25:58.172Z","started_at":"2026-08-14T19:25:58.172Z",
 "completed_at":"2026-08-14T19:26:07.709Z",
 "model":"kimi-code/k3","thinking_effort":"low",
 "agent_id":"agent-0","subagent_type":"explore",
 "parent_tool_call_id":"tool_FXrHPtQ0ieUGu1EpztqzhreZ",
 "output_preview":"OK — but to be candid: …","output_bytes":1513}
```

(`output_preview`/`output_bytes` only with `with_output=true`.) The three new
fields are emitted only on `kind:"subagent"` tasks — bash/tool tasks omit
them. `agent_id` is the sub-agent registry id the WS `subagent.*` events and
the snapshot roster already use, so REST tasks can now be joined to the
roster without matching on `description`. `created_at` == `started_at` still
holds.

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
  `failed`; `suspended_reason` and `swarm_index` optional. The flatter
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
seq 5   turn.started               {"turnId": 0, "origin": {"kind": "user"}, "prompt": "<text>"}
seq 6   context.spliced            the new user message, inline
seq 7   turn.step.started          step 1; thinking.delta, tool.call.delta xN
seq 8   permission.approval.requested  v2 shape (approval id = toolCallId)
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
**`prompt.submitted` does not exist** — the user message arrives inline in
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
seq 52  event.question.requested   question_id = toolCallId, questions inline
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
again** (0.29.0/0.29.1 dropped it). Note the id is a **fresh ULID**, not the
queued prompt's `msg_...` id (`msg_01KYJGTJCZPX81AMH663V8881Z` below vs prompt
id `msg_01KYJGT9HN3BCDQZ8MXG4AYH69`). Keep treating spliced-message ids as
optional — both forms are in the wild (verbatim 0.29.2 capture, 2026-07-27):

```json
{"type":"context.spliced","seq":30,"epoch":"ep_...","session_id":"session_...","timestamp":"...",
 "payload":{"type":"context.spliced","start":6,"deleteCount":0,
  "messages":[{"role":"user","content":[{"type":"text","text":"Stop. Reply with exactly: steered-ok-0292"}],
               "toolCalls":[],"origin":{"kind":"user"},"id":"msg_01KYJGTJCZPX81AMH663V8881Z"}],
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
`rawFinishReason` all still present):

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
**0.37.2, additive:** `turn.ended` gained optional **`time`** (epoch ms,
schema-declared — commit `3ded0808`); live, `turn.started` and
`prompt.completed` also emit a top-level `time` (epoch ms) without a matching
schema field. `turn.started` also gained optional **`promptId`** (the
submitter's `prompt_id`). The app ignores all of them. Verbatim 0.37.2 frames
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
`approvalId` and `toolCallId`; and there is a **new phase kind `tool_call`**
(emitted while a tool executes). Observed kinds: `running`, `streaming`
(with `"stream": "thinking" | "tool_call"` — **0.31.0 adds
`"stream": "assistant"`** to the streaming-phase variant), `awaiting_approval`,
`tool_call`,
`ended` (with `at` instead of `since`), and — observed on **0.34.0**
(2026-08-06, during a REST `:abort`; possibly older) — **`interrupted`**:
`{"kind":"interrupted","turnId":2,"step":1,"reason":"aborted","at":...}` (with
`at`, like `ended`). **0.31.0, additive:** the model/usage
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
camelCase; **no `approval_id`, no `expires_at` — use `toolCallId` as the
approval id**). Unchanged:

```json
{"type":"permission.approval.requested","seq":8,
 "payload":{"type":"permission.approval.requested","sessionId":"session_...","agentId":"main",
            "turnId":0,"toolCallId":"tool_RMgBIy1asIorg1eV8P3ZhHiL",
            "toolName":"Bash","action":"Running: echo doc-verify-0290",
            "display":{"kind":"command","command":"echo doc-verify-0290","cwd":"/home/ubuntu/spike","language":"bash"},
            "toolInput":{"command":"echo doc-verify-0290"}}}
```

**Permission requested — legacy shape** (`event.approval.requested`, still
emitted right after the v2 one; `expires_at` is `created_at` + 24 h).
Unchanged:

```json
{"type":"event.approval.requested","seq":10,
 "payload":{"type":"event.approval.requested","sessionId":"session_...","agentId":"main",
            "approval_id":"tool_RMgBIy1asIorg1eV8P3ZhHiL","session_id":"session_...",
            "turn_id":0,"tool_call_id":"tool_RMgBIy1asIorg1eV8P3ZhHiL",
            "tool_name":"Bash","action":"Running: echo doc-verify-0290",
            "tool_input_display":{"kind":"command","command":"echo doc-verify-0290",
                                  "cwd":"/home/ubuntu/spike","language":"bash"},
            "created_at":"2026-07-23T03:27:38.761Z","expires_at":"2026-07-24T03:27:38.761Z"}}
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
`promptIds` the steered-in queued ones, their content inline). Unchanged:

```json
{"type":"prompt.steered","seq":28,
 "payload":{"type":"prompt.steered","activePromptId":"msg_01KY6GC4TTDXY3WYGGAWQ50ZQ5",
            "promptIds":["msg_01KY6GCAQF1655KB27NSFBGWZ2"],
            "content":[{"type":"text","text":"Stop. Reply with exactly: steered-ok-0290"}],
            "steeredAt":"2026-07-23T03:30:19.589Z","agentId":"main","sessionId":"session_..."}}
```

**Question requested** (`event.question.requested`; snake_case;
**`question_id` == `tool_call_id`** — no v2 duplicate). `options[].description`
is omitted when the option has none; `header` is optional and was absent
entirely from the 2026-07-23 captures (key order is not stable either — never
rely on it). `multi_select` is present per item in both `true` and `false`
form (verified live 2026-08-02 on 0.31.1):

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

## Verification log

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
