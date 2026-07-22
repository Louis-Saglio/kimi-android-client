# Kimi Code server wire protocol (core loop)

Wire protocol of the HTTP+WebSocket server embedded in the `kimi` binary, as used
by Kimi Code Web. Documented and verified against the live server on 2026-07-22
(server version `0.28.1`, `"backend": "v2"`, WS `protocol_version: 2`).

Scope: core loop + user interaction — auth check, sessions, messages, prompts,
steer, questions, abort, permissions, WebSocket events. Explicitly skipped:
`fs:*`, terminals, cron, goals, workspaces, providers, skills.

Every endpoint below was executed against the live server; the exact commands
used are shown with each section. Secrets are redacted (`<token>`).

**Protocol history:** this document previously described server `0.27.0`
(verified 2026-07-19/20; see git history) and `0.23.3` before that. The drift
from 0.27.0 to 0.28.1 is small and **additive**: new fields on a few WS events,
a new id format for server-generated messages, and a new envelope code for
already-resolved questions — no removed or renamed shapes. Details inline.
Server updates are installed by the `kimi-nightly-update` systemd timer (see
`scripts/`), which re-verifies this protocol after every update.

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

Verified (server 0.28.1, 2026-07-22):

```bash
# No token -> rejected on authenticated routes
curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:58627/api/v1/meta
# 401

# Wrong token -> rejected
curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer wrong" http://127.0.0.1:58627/api/v1/meta
# 401

# Valid token -> allowed
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:58627/api/v1/healthz
# {"code":0,"msg":"success","data":{"ok":true},"request_id":"01KY3XQ1..."}
```

### Response envelope

Every REST response is JSON wrapped in an envelope:

```json
{"code": 0, "msg": "success", "data": { ... }, "request_id": "01KY3XQ1BSC5S5047TD0B2SQCP"}
```

- `code: 0` = success; the payload is in `data`.
- **Errors come back as HTTP 200 with a non-zero `code`** — check `code`, not
  the HTTP status. Example: a non-existent `cwd` on session create returns
  `{"code":40409,"msg":"workspace root /tmp/spike does not exist","data":null,...}`
  over HTTP 200. Error envelopes may also carry `details` (validation errors)
  and a server-side `stack` string (0.28.1; ignore it).

## Meta

`GET /api/v1/meta` — server identity and capabilities. Requires the token.

```bash
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:58627/api/v1/meta
```

```json
{
  "code": 0, "msg": "success",
  "data": {
    "server_version": "0.28.1",
    "capabilities": {"websocket": true, "file_upload": true, "fs_query": true, "mcp": true, "tasks": true, "terminal": true},
    "server_id": "01KY288682HCN4K16PDK1GJDD2",
    "started_at": "2026-07-21T11:51:17.506Z",
    "open_in_apps": [],
    "dangerous_bypass_auth": false,
    "backend": "v2"
  },
  "request_id": "01KY3XQ1BSC5S5047TD0B2SQCP"
}
```

## Sessions

### List — `GET /api/v1/sessions`

Query params (all optional): `page_size` (default 20), `before_id`, `after_id`
(pagination cursors — `before_id` paging re-verified 2026-07-22), `status`,
`include_archive`, `archived_only`, `exclude_empty`, `workspace_id`.

```bash
curl -s -H "Authorization: Bearer <token>" 'http://127.0.0.1:58627/api/v1/sessions?page_size=2'
```

Session object shape (0.28.1, fields truncated for brevity):

```json
{
  "id": "session_81fd0b05-3e9c-4b75-a677-d2892719538b",
  "workspace_id": "wd_spike_3e0ac6a88795",
  "title": "Run exactly this one command, then reply done: echo doc-verify-0281",
  "created_at": "2026-07-22T03:27:18.314Z",
  "updated_at": "2026-07-22T03:30:42.776Z",
  "busy": false,
  "main_turn_active": false,
  "pending_interaction": "none",
  "last_turn_reason": "completed",
  "archived": false,
  "last_prompt": "Run exactly this one command, then reply done: echo doc-verify-0281",
  "metadata": {"cwd": "/tmp/spike"},
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
  absent entirely on sessions that have not run a turn yet** (0.28.1).
- `message_count` and `last_seq` are still present but observed stuck at `0` —
  do not rely on them.
- The list envelope carries `has_more` for pagination (verified with
  `page_size=2&before_id=<last id of page>`).

### Create — `POST /api/v1/sessions`

Body: `{"metadata": {"cwd": "<working directory>"}}`, optional top-level
`"title"`. A `cwd` is expected; the server auto-creates a workspace for new
cwds. A non-existent `cwd` is refused with envelope code `40409` over HTTP 200.
**Note for this VPS:** the `kimi-web.service` unit runs with `PrivateTmp=yes`,
so the server's `/tmp` is a private namespace — a host-side `/tmp/spike` is
invisible to it; create it inside the service namespace
(`sudo nsenter -t $(systemctl show kimi-web -p MainPID --value) -m mkdir -p /tmp/spike`)
or use a path outside `/tmp`.

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"metadata":{"cwd":"/tmp/spike"}}'
```

Returns the session object above. Fresh sessions created without a `title`
come back with `"title": ""` — clients should fall back to their own
placeholder. There is no delete endpoint: `DELETE /api/v1/sessions/{id}`
answers plain HTTP 404 (verified 2026-07-22).

### Get one — `GET /api/v1/sessions/{id}`

Returns the same session object as the list.

### Live status — `GET /api/v1/sessions/{id}/status`

Lightweight runtime state:

```bash
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:58627/api/v1/sessions/session_81fd0b05.../status
```

```json
{
  "code": 0, "msg": "success",
  "data": {
    "busy": false,
    "model": "kimi-code/k3",
    "thinking_level": "low",
    "permission": "manual",
    "plan_mode": false,
    "swarm_mode": false,
    "context_tokens": 25060,
    "max_context_tokens": 1048576,
    "context_usage": 0.023899078369140625
  },
  "request_id": "01KY3YKPEMA1H1ZBNBGVBER64Q"
}
```

`busy: true` ⇔ the session is mid-turn. `model` is omitted entirely when the
session has no model configured. `thinking_level` and `permission` reflect the
session's current config (`"off"`/`"yolo"` observed on a fresh session).

## Messages

### History — `GET /api/v1/sessions/{id}/messages`

Query params (all optional): `page_size`, `before_id`, `after_id`, `role`.
**Items are returned newest-first**; page backwards with `before_id`.

```bash
curl -s -H "Authorization: Bearer <token>" 'http://127.0.0.1:58627/api/v1/sessions/session_81fd0b05.../messages?page_size=10'
```

Message shapes (0.28.1, verbatim from one turn — user prompt, assistant tool
call, tool result, assistant text):

```json
{"id":"msg_01KY3Y0AEWYPN791ESGMZE5658","session_id":"session_81fd0b05-...","role":"user",
 "content":[{"type":"text","text":"Run exactly this one command, then reply done: echo doc-verify-0281"}],
 "created_at":"2026-07-22T03:30:42.782Z","metadata":{"origin":{"kind":"user"}}}

{"id":"msg_session_81fd0b05-3e9c-4b75-a677-d2892719538b_000001","session_id":"session_81fd0b05-...","role":"assistant",
 "content":[{"type":"thinking","thinking":""},
            {"type":"tool_use","tool_call_id":"tool_FsSfXhnM8ZJK1WfTxntUlEQM","tool_name":"Bash",
             "input":{"command":"echo doc-verify-0281"}}],
 "created_at":"2026-07-22T03:30:42.784Z"}

{"id":"msg_session_81fd0b05-3e9c-4b75-a677-d2892719538b_000002","session_id":"session_81fd0b05-...","role":"tool",
 "content":[{"type":"tool_result","tool_call_id":"tool_FsSfXhnM8ZJK1WfTxntUlEQM","output":"doc-verify-0281\n"}],
 "created_at":"2026-07-22T03:31:23.681Z"}

{"id":"msg_session_81fd0b05-3e9c-4b75-a677-d2892719538b_000003","session_id":"session_81fd0b05-...","role":"assistant",
 "content":[{"type":"thinking","thinking":""},{"type":"text","text":"done"}],
 "created_at":"2026-07-22T03:31:23.683Z"}
```

Roles observed: `user`, `assistant`, `tool`. Content is a list of parts; part
types observed:

- `{"type": "text", "text": "..."}` — user or assistant text.
- `{"type": "thinking", "thinking": "..."}` — assistant reasoning.
- `{"type": "tool_use", "tool_call_id": "tool_...", "tool_name": "Bash", "input": {...}}` — assistant tool call.
- `{"type": "tool_result", "tool_call_id": "tool_...", "output": "..."}` — in a
  `role: "tool"` message; may also carry `"is_error": true`.

**Message id format changed in 0.28.1:** user messages keep the ULID form
(`msg_01KY...`), but server-generated messages (`assistant`, `tool`) now get
sequential per-session ids `msg_<session_id>_<NNNNNN>` (zero-padded counter).
Treat ids as opaque strings — do not parse them. `metadata` is only present on
user messages (carries `origin`).

An aborted turn commits an assistant message with **empty `content`** when the
abort landed before any assistant text was produced (re-verified 2026-07-22).

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

## Prompts

### Send — `POST /api/v1/sessions/{id}/prompts`

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_81fd0b05.../prompts \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"content":[{"type":"text","text":"Run exactly this one command, then reply done: echo doc-verify-0281"}],
       "model":"kimi-code/k3","permission_mode":"manual"}'
```

```json
{
  "code": 0, "msg": "success",
  "data": {
    "prompt_id": "msg_01KY3Y0AEWYPN791ESGMZE5658",
    "user_message_id": "msg_01KY3Y0AEWYPN791ESGMZE5658",
    "status": "running",
    "content": [{"type": "text", "text": "Run exactly this one command, then reply done: echo doc-verify-0281"}],
    "created_at": "2026-07-22T03:30:42.780Z"
  },
  "request_id": "01KY3Y0ADZ9WC62F2YW2539HAW"
}
```

- `prompt_id` equals `user_message_id` (the ULID `msg_...` id of the user
  message).
- Optional body fields (per-prompt overrides): `permission_mode`
  (`manual`/`auto`/`yolo` — `manual` verified live), `model` (verified: a
  session with empty `agent_config.model` runs when the prompt carries one),
  `thinking`, `plan_mode`, `swarm_mode`, `agent_id`, `metadata`,
  `goal_objective`, `goal_control`.
- The server is asynchronous: the HTTP response only acknowledges the prompt;
  results arrive over the WebSocket.
- While the session is busy, a posted prompt is accepted but queued: the
  response comes back with `"status": "queued"` instead of `"running"`
  (re-verified 2026-07-22). A queued prompt can be steered into the running
  turn — see the next section.

## Steer

Steering injects a queued prompt into the currently running turn. The client
flow (re-verified 2026-07-22 on 0.28.1): while the session is busy, POST the
new prompt as usual (response `"status": "queued"`), then steer the returned
`prompt_id` into the active turn.

### Steer queued prompts — `POST /api/v1/sessions/{id}/prompts:steer`

Body: `{"prompt_ids": ["<prompt id>", ...]}` — at least one id.

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_160acae4.../prompts:steer \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"prompt_ids":["msg_01KY3Y53G46J8F237D6K05XFN8"]}'
```

```json
{"code":0,"msg":"success",
 "data":{"steered":true,"prompt_ids":["msg_01KY3Y53G46J8F237D6K05XFN8"]},
 "request_id":"01KY3Y5RKKZ5JR1ZG7FCN9SQS9"}
```

- **Idle session** (nothing to steer into): HTTP 200 with
  `{"code":40402,"msg":"no active prompt to steer into","data":null,...}`.
- Empty `prompt_ids`:
  `{"code":40001,"msg":"prompt_ids: Too small: expected array to have >=1 items",...}`.
- WS sequence when the steer lands mid-turn (verbatim capture in the WS
  section): `prompt.steered` → the current step runs to `turn.step.completed`
  → the steered user message arrives via `context.spliced` → a new
  `turn.step.started` **in the same turn** (no new `turn.started`) →
  `turn.ended` `"completed"` → a single `prompt.completed` carrying the
  **original** active prompt id. The agent then follows the steered
  instruction.

## Abort

### Abort current prompt — `POST /api/v1/sessions/{id}:abort`

Body: `{}`. Aborts the currently running prompt/turn of the session.

```bash
curl -s -X POST 'http://127.0.0.1:58627/api/v1/sessions/session_39795f5f...:abort' \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' -d '{}'
# {"code":0,"msg":"success","data":{"aborted":true},"request_id":"01KY3Y7Q1T2XJS5PJ4PMWWXN29"}
```

Verified mid-run on 0.28.1: the WS then emits `turn.step.interrupted`
(`reason: "aborted"`) → `turn.ended` (`reason: "cancelled"`) →
`event.session.work_changed` (idle) → `prompt.aborted`.

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
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_81fd0b05.../approvals/tool_FsSfXhnM8ZJK1WfTxntUlEQM \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"decision":"approved"}'
# {"code":0,"msg":"success","data":{"resolved":true,"resolved_at":"2026-07-22T03:31:23.662Z"},"request_id":"01KY3Y1JCCA17ZENC7QWXBF3PQ"}
```

Body fields: `decision` (`"approved"` | `"rejected"`), optional `scope`,
`feedback`, `selected_label`. After the decision the WS emits
`event.approval.resolved` and `permission.approval.resolved`, then the tool
runs.

## Questions

The agent's user-question tool (`AskUserQuestion`) surfaces as **WebSocket
events**; the answer or dismissal is posted back via REST. Re-verified
2026-07-22 on 0.28.1 by prompting a session to call the tool — the question
surfaced directly, with no approval gate, under the session's default
permission mode.

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
   404, `{"statusCode":404,"error":"Not Found",...}`) — the single-question
   shape only exists inside the list item / WS event payload.
4. Answer — `POST /api/v1/sessions/{id}/questions/{qid}`. `answers` keys are
   the per-question item ids from the request payload (`q_0`); values carry
   `kind` plus `option_id` (selected option) or `text` (free text):

```bash
# selected option
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_54f9fec7.../questions/tool_C07XSGo6P3vzrsWh9QYgTSsP \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"answers":{"q_0":{"kind":"single","option_id":"opt_0_1"}}}'
# free text (the question had "allow_other": true)
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_54f9fec7.../questions/tool_ZYSesxoz91XhJh69FKcJiGGz \
  -H "Authorization: Bearer <token>" \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"answers":{"q_0":{"kind":"other","text":"ramen"}}}'
```

Both returned HTTP 200
`{"code":0,"msg":"success","data":{"resolved":true,"resolved_at":"..."},...}`.
On the wire: `event.session.work_changed` (`pending_interaction` back to
`"none"`) → `event.question.answered` → `tool.result` whose output is
`{"answers":{"<question text>":"<option label or free text>"}}` → the turn
continues. **Changed in 0.28.1:** answering an already-resolved question now
returns `{"code":40902,"msg":"question <qid> already resolved","data":null,...}`
(0.27.0 returned `40405` "not found").

5. Dismiss — `POST /api/v1/sessions/{id}/questions/{qid}:dismiss`, body `{}`:

```json
{"code":40909,"msg":"question tool_Cem7SmwFnnFNjuhLaX1yJ2sw dismissed",
 "data":{"dismissed":true,"dismissed_at":"2026-07-22T03:36:48.173Z"},
 "request_id":"01KY3YBF9C11E18YPWMG29PX7M"}
```

**Beware: a successful dismiss comes back over HTTP 200 with the non-zero
envelope code `40909`** — do not gate success on `code == 0` here; check
`data.dismissed`. Re-dismissing a resolved question now also returns
`code:40902` ("already resolved"; 0.27.0: `40405`). On the wire:
`event.session.work_changed` (`"none"`) → `event.question.dismissed` →
`tool.result` with output
`{"answers":{},"note":"User dismissed the question without answering."}` → the
turn continues.

The web bundle hints at further answer kinds (`multi`, `multi_with_other`,
`skipped`) and optional `method`/`note` body fields — **not verified live**;
treat as leads only.

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
  'ws://127.0.0.1:58627/api/v1/ws?client_id=doc_verify'
```

First server frame is `server_hello` (unchanged):

```json
{"type":"server_hello","timestamp":"2026-07-22T03:30:26.525Z",
 "payload":{"ws_connection_id":"conn_01KY3XZTJXAV7NXM3TJ1VN09HT","protocol_version":2,
            "max_event_buffer_size":1000,
            "capabilities":{"event_batching":false,"compression":false}}}
```

### Heartbeat — gone in v2

0.23.3 sent app-level `ping` frames every ~30 s and closed connections that did
not answer with a matching `pong`. **v2 sends no pings** (zero observed across
all 2026-07-22 captures; the connections stayed open). Answering pings remains
harmless if an older server sends them.

### Subscribe to a session

Events are per-session; subscribe to receive them. One text frame = one JSON
message. Client frames carry a client-chosen `id` that the server mirrors in
its `ack`.

```json
++ client: {"type":"subscribe","id":"c_1","payload":{"session_ids":["session_81fd0b05..."]}}
-- server: {"type":"ack","id":"c_1","code":0,"msg":"success",
            "payload":{"accepted":["session_81fd0b05..."],
                       "not_found":[],"resync_required":[],
                       "cursors":{"session_81fd0b05...":{"seq":1,"epoch":"ep_01KY3XT2SX1EFJVP1P3GVY4Q6D"}}}}
```

**Resume after disconnect** (re-verified on 0.28.1: subscribing from `seq 18`
replayed seqs 19–20 in order):

```json
{"type":"subscribe","id":"c_1","payload":{"session_ids":["session_..."],
 "cursors":{"session_...":{"seq":18,"epoch":"ep_01KY3XT2SX1EFJVP1P3GVY4Q6D"}}}}
```

`unsubscribe` works the same way and gets its own `ack` (re-verified).

### Event envelope

Every event frame:

```json
{
  "type": "<event type>",        // mirrored inside payload.type
  "seq": 21,                     // per-session monotonically increasing
  "epoch": "ep_01KY3XT2SX1EFJVP1P3GVY4Q6D",
  "session_id": "session_...",
  "timestamp": "2026-07-22T03:31:28.957Z",
  "volatile": true,              // only on high-frequency deltas; absent otherwise
  "offset": 0,                   // byte offset of the delta in the accumulated text
  "payload": { "type": "<event type>", "...": "..." }
}
```

### Captured round-trips (0.28.1)

Full prompt with a manual-permission tool call, recorded 2026-07-22 (command:
`POST .../prompts` with `permission_mode: "manual"`, approval via REST as
above). Identical skeleton to 0.27.0:

```
seq 3   event.session.work_changed   busy=true, main_turn_active=true
seq 4   turn.started                 {"turnId": 0, "origin": {"kind": "user"}, "prompt": "<text>"}
seq 5   context.spliced              the new user message, inline
seq 6   turn.step.started            step 1; thinking.delta, tool.call.delta x9
seq 7   permission.approval.requested  v2 shape (approval id = toolCallId)
seq 8   event.session.work_changed   pending_interaction "approval"
seq 9   event.approval.requested     legacy shape (approval_id, expires_at +24 h)
seq 10  event.session.work_changed   pending_interaction back to "none" (decision posted)
seq 11  event.approval.resolved      after the REST decision
seq 12  permission.approval.resolved v2 duplicate
seq 13  tool.call.started + tool.progress (volatile stdout streaming)
seq 14  tool.result
seq 15  turn.step.completed          usage + finishReason "tool_use" + latency stats
seq 16  turn.step.started            step 2; thinking.delta, assistant.delta ("done")
seq 17  turn.step.completed
seq 18  turn.ended                   {"reason": "completed", "durationMs": ...}
seq 19  event.session.work_changed   busy=false, last_turn_reason "completed"
seq 20  prompt.completed             {"promptId": "msg_...", "reason": "completed"}
```

Plain text prompt: same skeleton without the approval/tool events.
**`prompt.submitted` does not exist** — the user message arrives inline in
`context.spliced`. `agent.status.updated` (volatile; model, context, usage,
plus a `phase` object — new in 0.28.1, see payloads) and `session.meta.updated`
(title/lastPrompt patch) fire interleaved throughout, sharing the same `seq` as
the event they piggyback on.

Abort sequence (`POST :abort` mid-run, 2026-07-22):

```
seq 7   turn.step.interrupted        {"reason": "aborted"}
seq 8   turn.ended                   {"reason": "cancelled", "durationMs": 3034}
seq 9   event.session.work_changed   busy=false, last_turn_reason "cancelled"
seq 10  prompt.aborted               {"promptId": "msg_...", "abortedAt": "..."}
```

Steer round-trip (2026-07-22): prompt A ("count to 100000") running, prompt B
posted while busy (`"status": "queued"`), then `POST prompts:steer` with B's id:

```
seq 16  prompt.steered               activePromptId=A, promptIds=[B], B's content inline
seq 19  turn.step.completed          the current step finishes normally
seq 20  context.spliced              the steered user message (carries an id)
seq 21  turn.step.started            next step — SAME turn, no new turn.started
seq 23  turn.ended                   reason "completed"
seq 25  prompt.completed             promptId = A (the original active prompt)
```

Question round-trip (2026-07-22; the prompt asked the agent to use its
`AskUserQuestion` tool):

```
seq 7   tool.call.started            name "AskUserQuestion"
seq 8   event.session.work_changed   pending_interaction "question"
seq 9   event.question.requested     question_id = toolCallId, questions inline
-- answer posted via REST --
seq 10  event.session.work_changed   pending_interaction "none"
seq 11  event.question.answered      answers {"<question text>": "<label or free text>"}
seq 12  tool.result                  output {"answers":{...}}; the turn continues
```

Dismiss path instead of the answer: `event.session.work_changed` (`"none"`) →
`event.question.dismissed` → `tool.result` with output
`{"answers":{},"note":"User dismissed the question without answering."}`.

### Core event payloads (verbatim captures, 0.28.1)

**New user message** (`context.spliced`; `messages` carries full message
objects — `start`/`deleteCount` are splice coordinates into the context).
Unchanged from 0.27.0:

```json
{"type":"context.spliced","seq":5,"epoch":"ep_...","session_id":"session_...","timestamp":"...",
 "payload":{"type":"context.spliced","start":0,"deleteCount":0,
  "messages":[{"role":"user","content":[{"type":"text","text":"Run exactly this one command, then reply done: echo doc-verify-0281"}],
               "toolCalls":[],"origin":{"kind":"user"},"id":"msg_01KY3Y0AEWYPN791ESGMZE5658"}],
  "agentId":"main","sessionId":"session_..."}}
```

**Work state** (`event.session.work_changed`; `pending_interaction` observed:
`"none"`, `"approval"`, `"question"`; `last_turn_reason` present only when idle).
Unchanged:

```json
{"type":"event.session.work_changed","seq":8,
 "payload":{"type":"event.session.work_changed","busy":true,"main_turn_active":true,
            "pending_interaction":"approval","agentId":"main","sessionId":"session_..."}}
```

**Streaming text delta** (`assistant.delta`; `thinking.delta` identical except
`"type": "thinking.delta"`). Unchanged:

```json
{"type":"assistant.delta","seq":16,"epoch":"ep_...","volatile":true,"offset":0,
 "session_id":"session_...","timestamp":"2026-07-22T03:31:28.957Z",
 "payload":{"type":"assistant.delta","turnId":0,"delta":"done","agentId":"main",
            "sessionId":"session_..."}}
```

**Tool call argument streaming** (`tool.call.delta`, volatile; `argumentsPart`
fragments concatenate into the tool's JSON arguments). Unchanged.

**Tool call start** (`tool.call.started`) — **0.28.1 adds a `description`
field** (the human-readable action, same text as the approval's `action`);
`toolCallId`, `name`, `args`, `display` unchanged:

```json
{"type":"tool.call.started","seq":13,
 "payload":{"type":"tool.call.started","turnId":0,"toolCallId":"tool_FsSfXhnM8ZJK1WfTxntUlEQM",
            "name":"Bash","args":{"command":"echo doc-verify-0281"},
            "description":"Running: echo doc-verify-0281",
            "display":{"kind":"command","command":"echo doc-verify-0281","cwd":"/tmp/spike","language":"bash"},
            "agentId":"main","sessionId":"session_..."}}
```

**Tool result** (`tool.result`): unchanged (`toolCallId`, `output`).

**Tool progress** (`tool.progress`, volatile — live tool output). Unchanged:

```json
{"type":"tool.progress","seq":13,"volatile":true,
 "payload":{"type":"tool.progress","turnId":0,"toolCallId":"tool_FsSfXhnM8ZJK1WfTxntUlEQM",
            "update":{"kind":"stdout","text":"doc-verify-0281\n"},"agentId":"main","sessionId":"session_..."}}
```

**Step completed** — **0.28.1 adds `llmRequestBuildMs`,
`llmServerFirstTokenMs`, `llmServerDecodeMs`, `llmClientConsumeMs` and
`rawFinishReason`** (a duplicate of `providerFinishReason` here):

```json
{"type":"turn.step.completed","seq":15,
 "payload":{"type":"turn.step.completed","turnId":0,"step":1,"stepId":"688f2e71-...",
            "usage":{"inputOther":13689,"output":57,"inputCacheRead":11264,"inputCacheCreation":0},
            "finishReason":"tool_use","llmFirstTokenLatencyMs":12111,"llmStreamDurationMs":11,
            "llmRequestBuildMs":3,"llmServerFirstTokenMs":12108,"llmServerDecodeMs":10,
            "llmClientConsumeMs":1,"providerFinishReason":"tool_calls","rawFinishReason":"tool_calls",
            "agentId":"main","sessionId":"session_..."}}
```

**turn.started / turn.ended** — **0.28.1 adds `prompt`** (the prompt text) to
`turn.started`:

```json
{"type":"turn.started","seq":4,
 "payload":{"type":"turn.started","turnId":0,"origin":{"kind":"user"},
            "prompt":"Run exactly this one command, then reply done: echo doc-verify-0281",
            "agentId":"main","sessionId":"session_..."}}
{"type":"turn.ended","seq":18,
 "payload":{"type":"turn.ended","turnId":0,"reason":"completed","durationMs":46184,
            "agentId":"main","sessionId":"session_..."}}
```

(`turn.ended.reason` observed: `"completed"`, `"cancelled"` after abort.)

**Agent status** (`agent.status.updated`, volatile) — **0.28.1 adds a `phase`
object** describing what the agent is doing; observed kinds: `running`,
`streaming` (with `"stream": "thinking" | "tool_call"`), `awaiting_approval`
(with the approval inline), `ended`. The older fields (model, contextTokens,
usage — now nested under `usage.byModel`) still appear on other updates of the
same event type:

```json
{"type":"agent.status.updated","seq":19,"volatile":true,
 "payload":{"type":"agent.status.updated",
            "phase":{"kind":"ended","turnId":0,"reason":"completed","durationMs":46185,"at":1784691088966},
            "agentId":"main","sessionId":"session_..."}}
{"type":"agent.status.updated","seq":6,"volatile":true,
 "payload":{"type":"agent.status.updated",
            "phase":{"kind":"awaiting_approval","turnId":0,"step":1,
                     "approval":{"approvalId":"tool_FsSfXhnM8ZJK1WfTxntUlEQM",...}},
            "agentId":"main","sessionId":"session_..."}}
```

**Session meta** (`session.meta.updated`; `patch` carries `title`,
`isCustomTitle`, `lastPrompt`). Unchanged:

```json
{"type":"session.meta.updated","seq":2,
 "payload":{"type":"session.meta.updated","title":"Run exactly this one command, then reply done: echo doc-verify-0281",
            "patch":{"title":"Run exactly this one command, then reply done: echo doc-verify-0281",
                     "isCustomTitle":false,"lastPrompt":"Run exactly this one command, then reply done: echo doc-verify-0281"},
            "agentId":"main","sessionId":"session_..."}}
```

**Permission requested — v2 shape** (`permission.approval.requested`,
camelCase; **no `approval_id`, no `expires_at` — use `toolCallId` as the
approval id**). Unchanged:

```json
{"type":"permission.approval.requested","seq":7,
 "payload":{"type":"permission.approval.requested","sessionId":"session_...","agentId":"main",
            "turnId":0,"toolCallId":"tool_FsSfXhnM8ZJK1WfTxntUlEQM",
            "toolName":"Bash","action":"Running: echo doc-verify-0281",
            "display":{"kind":"command","command":"echo doc-verify-0281","cwd":"/tmp/spike","language":"bash"},
            "toolInput":{"command":"echo doc-verify-0281"}}}
```

**Permission requested — legacy shape** (`event.approval.requested`, still
emitted right after the v2 one; `expires_at` is `created_at` + 24 h).
Unchanged:

```json
{"type":"event.approval.requested","seq":9,
 "payload":{"type":"event.approval.requested","sessionId":"session_...","agentId":"main",
            "approval_id":"tool_FsSfXhnM8ZJK1WfTxntUlEQM","session_id":"session_...",
            "turn_id":0,"tool_call_id":"tool_FsSfXhnM8ZJK1WfTxntUlEQM",
            "tool_name":"Bash","action":"Running: echo doc-verify-0281",
            "tool_input_display":{"kind":"command","command":"echo doc-verify-0281",
                                  "cwd":"/tmp/spike","language":"bash"},
            "created_at":"2026-07-22T03:30:54.928Z","expires_at":"2026-07-23T03:30:54.928Z"}}
```

Both are followed (after the REST decision) by `event.approval.resolved` →
`{"approval_id": "...", "decision": "approved", "resolved_at": "..."}` and its
`permission.approval.resolved` v2 duplicate (adds `"decision"` to the request
payload).

**Prompt finished:**

```json
{"type":"prompt.completed","seq":20,
 "payload":{"type":"prompt.completed","promptId":"msg_01KY3Y0AEWYPN791ESGMZE5658",
            "finishedAt":"2026-07-22T03:31:28.966Z","reason":"completed",
            "agentId":"main","sessionId":"session_..."}}
```

**Prompt steered** (`prompt.steered`; `activePromptId` is the running prompt,
`promptIds` the steered-in queued ones, their content inline). Unchanged:

```json
{"type":"prompt.steered","seq":16,
 "payload":{"type":"prompt.steered","activePromptId":"msg_01KY3Y4ZHPHRPE10BH6B4KBAND",
            "promptIds":["msg_01KY3Y53G46J8F237D6K05XFN8"],
            "content":[{"type":"text","text":"Stop counting. Reply with exactly: steered-ok-0281"}],
            "steeredAt":"2026-07-22T03:33:41.109Z","agentId":"main","sessionId":"session_..."}}
```

**Question requested** (`event.question.requested`; snake_case;
**`question_id` == `tool_call_id`** — no v2 duplicate). `options[].description`
is omitted when the option has none; `header` moves around inside the question
object (key order is not stable — never rely on it):

```json
{"type":"event.question.requested","seq":9,
 "payload":{"type":"event.question.requested","question_id":"tool_C07XSGo6P3vzrsWh9QYgTSsP",
            "session_id":"session_...","turn_id":0,
            "tool_call_id":"tool_C07XSGo6P3vzrsWh9QYgTSsP",
            "questions":[{"id":"q_0","question":"Red or blue?","header":"Color",
                          "options":[{"id":"opt_0_0","label":"red","description":"warm color"},
                                     {"id":"opt_0_1","label":"blue","description":"cool color"}],
                          "allow_other":true}],
            "created_at":"2026-07-22T03:35:15.724Z","agentId":"main","sessionId":"session_..."}}
```

**Question answered** (`event.question.answered`; `answers` is keyed by
**question text**, values are option labels or free text). Unchanged:

```json
{"type":"event.question.answered","seq":11,
 "payload":{"type":"event.question.answered","question_id":"tool_C07XSGo6P3vzrsWh9QYgTSsP",
            "answers":{"Red or blue?":"blue"},"resolved_at":"2026-07-22T03:35:46.625Z",
            "agentId":"main","sessionId":"session_..."}}
```

**Question dismissed:**

```json
{"type":"event.question.dismissed","seq":28,
 "payload":{"type":"event.question.dismissed","question_id":"tool_Cem7SmwFnnFNjuhLaX1yJ2sw",
            "dismissed_at":"2026-07-22T03:36:48.173Z","agentId":"main","sessionId":"session_..."}}
```

## Verification log

- REST probes: `curl` against `http://127.0.0.1:58627/api/v1/...` (commands
  inline above), server `0.28.1` (`backend: v2`).
- Auth (re-verified 2026-07-22): `/api/v1/meta` → `401` without a token or
  with a wrong one, `200` with `Authorization: Bearer <token>`;
  `/api/v1/healthz` is public (always `200`). Auth is server-native (bearer
  token), enforced on all authenticated REST and WebSocket routes;
  `"dangerous_bypass_auth": false` in `/meta`.
- WS captures: websocat 1.14.0 (`~/tools/websocat-bin/websocat`, header passed
  as `-H="Authorization: Bearer <token>"`) against
  `ws://127.0.0.1:58627/api/v1/ws?client_id=...`, subscribing to throwaway
  sessions created for the captures. 2026-07-22 round-trips recorded on
  0.28.1: tool call with manual approval (approve path included), plain queued
  prompt, mid-turn steer of a queued prompt (`prompts:steer`), idle-session
  steer error (`code:40402`), empty `prompt_ids` error (`code:40001`), mid-run
  abort, cursor-resume replay (seq 18 → 19–20), unsubscribe ack, question
  surface + answer (selected option and free text), question dismiss (HTTP 200
  with `code:40909`), `GET questions?status=pending` polling, missing `status`
  param error (`code:40001`), `GET questions/{qid}` confirmed 404,
  re-answer/re-dismiss of resolved questions (**now `code:40902` "already
  resolved"** — 0.27.0 returned `40405`). Questions triggered by prompting the
  session to call its `AskUserQuestion` tool. No heartbeat frames observed.
- Housekeeping notes: the `kimi-web.service` unit has `PrivateTmp=yes`, so
  `/tmp/spike` must exist in the service's mount namespace (see Sessions →
  Create). There is no session-delete endpoint (`DELETE` → 404); throwaway
  sessions created for the captures were left in place.
- 2026-07-19/20 round-trips on 0.27.0 and 2026-07-17 round-trips on 0.23.3
  (superseded shapes): see git history.
