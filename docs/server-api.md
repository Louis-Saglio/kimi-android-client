# Kimi Code server wire protocol (core loop)

Wire protocol of the HTTP+WebSocket server embedded in the `kimi` binary, as used
by Kimi Code Web. Documented and verified against the live server on 2026-07-17
(server version `0.23.3`, WS `protocol_version: 2`).

Scope: core loop only — auth check, sessions, messages, prompts, abort,
permissions, WebSocket events. Explicitly skipped: `fs:*`, terminals, cron,
goals, workspaces, providers, skills.

Every endpoint below was executed against the live server; the exact commands
used are shown with each section. Secrets are redacted (`<password>`).

## Server 0.27.0 deltas (verified 2026-07-19)

The server was upgraded 0.23.3 → 0.27.0 (`"backend": "v2"` in `/meta`) after the
sections below were written. Everything not listed here still matches, but these
shapes changed:

- `GET /sessions/{id}/status` returns `{"busy": bool, "model", "thinking_level",
  "permission", "plan_mode", "swarm_mode", "context_tokens", ...}` instead of
  `{"status": "idle|running|aborted", ...}`.
- The session object likewise replaces `status` with `busy: bool`,
  `main_turn_active: bool`, `pending_interaction`, and `last_turn_reason`
  (`"cancelled"` after an abort).
- WS `event.session.status_changed` is renamed `event.session.work_changed`;
  the payload carries `busy`, `main_turn_active`, `pending_interaction` (plus
  `last_turn_reason` when going idle).
- WS `prompt.submitted` is no longer emitted; the new user message arrives
  inline in a `context.spliced` frame.
- The abort chain is unchanged: `POST :abort` acks `{"aborted": true}`, then
  `turn.step.interrupted` (`"aborted"`) → `turn.ended` (`"cancelled"`) →
  `event.session.work_changed` (idle) → `prompt.aborted`.
- `/meta` capabilities rename `background_tasks` → `tasks`.

## Base URL & auth

| Path | Base URL | Auth |
|---|---|---|
| Local (this VPS) | `http://127.0.0.1:58627` | none — server runs with `--dangerous-bypass-auth` |
| Remote (Tailscale) | `http://100.64.0.1` | HTTP Basic, enforced by Caddy (`user: louis`) |

- REST API root is `<base>/api/v1` (confirmed from the web bundle
  `/assets/index-e04Pgjfa.js`: `` `${origin}/api/v1${path}` ``).
- Remote auth is plain HTTP Basic: `Authorization: Basic base64(louis:<password>)`,
  i.e. `curl -u louis:<password> http://100.64.0.1/api/v1/...`. The same
  header must be present on the WebSocket handshake (Caddy terminates auth
  before proxying).
- **Credential check endpoint: `GET /api/v1/healthz`** — the lightest endpoint
  (87-byte response). `200` ⇒ credentials valid, `401` ⇒ invalid.

Verified:

```bash
# Remote, no credentials -> rejected by Caddy
curl -i http://100.64.0.1/api/v1/healthz
# HTTP/1.1 401 Unauthorized
# Server: Caddy
# Www-Authenticate: Basic realm="restricted"

# Local, no credentials -> allowed (dangerous_bypass_auth)
curl -s http://127.0.0.1:58627/api/v1/healthz
# {"code":0,"msg":"success","data":{"ok":true},"request_id":"01KXQ3V78MCKADR796KDJ40KES"}
```

The remote 200-with-credentials path was not re-verified here (the basic-auth
password is not stored on this VPS, only a bcrypt hash in the Caddyfile); it is
the same request with `-u louis:<password>` added.

The web bundle also supports a server-level bearer token when the server does
*not* run with `--dangerous-bypass-auth`: REST `Authorization: Bearer <token>`,
and WS auth via a subprotocol header `kimi-code.bearer.<token>` (bundle-derived,
not live-verified — the local server bypasses auth). Not needed in our
deployment: Caddy Basic auth is the only gate.

### Client headers the web app sends (optional)

`X-Request-Id: <uuid>` plus identity headers `X-Kimi-Client-Id`,
`X-Kimi-Client-Name`, `X-Kimi-Client-Version`, `X-Kimi-Client-Ui-Mode`. The
server answered all probes without them; treat as optional telemetry.

### Response envelope

Every REST response is JSON wrapped in an envelope:

```json
{"code": 0, "msg": "success", "data": { ... }, "request_id": "01KXQ3V78MCKADR796KDJ40KES"}
```

- `code: 0` = success; the payload is in `data`.
- **Errors come back as HTTP 200 with a non-zero `code`** — check `code`, not
  the HTTP status:

```bash
curl -i http://127.0.0.1:58627/api/v1/sessions/session_doesnotexist
# HTTP/1.1 200 OK
# {"code":40401,"msg":"session session_doesnotexist does not exist","data":null,"request_id":"01KXQ4EADYYW5GNSA8T4RDN3AR"}
```

## Meta

`GET /api/v1/meta` — server identity and capabilities. Also usable as an auth
check (slightly larger than `/healthz`).

```bash
curl -s http://127.0.0.1:58627/api/v1/meta
```

```json
{
  "code": 0, "msg": "success",
  "data": {
    "server_version": "0.23.3",
    "capabilities": {"websocket": true, "file_upload": true, "fs_query": true, "mcp": true, "background_tasks": true, "terminal": true},
    "server_id": "01KX57TWMMAVR0N4MQX1ZB3XP3",
    "started_at": "2026-07-10T05:26:03.158Z",
    "open_in_apps": [],
    "dangerous_bypass_auth": true
  },
  "request_id": "01KXQ3V799DKZK7XX5Q3J4KVBZ"
}
```

## Sessions

### List — `GET /api/v1/sessions`

Query params (all optional): `page_size` (default 20), `before_id`, `after_id`
(pagination cursors), `status`, `include_archive`, `archived_only`,
`exclude_empty`, `workspace_id`.

```bash
curl -s 'http://127.0.0.1:58627/api/v1/sessions?page_size=2'
```

```json
{
  "code": 0, "msg": "success",
  "data": {
    "items": [
      {
        "id": "session_6a8c6718-faaa-4848-b3a2-2012ebe31d72",
        "workspace_id": "wd_kimi-android-client_479bb0bcfde4",
        "title": "I want to build an android native clone of the kimi code web client...",
        "created_at": "2026-07-17T03:34:49.193Z",
        "updated_at": "2026-07-17T03:48:31.606Z",
        "status": "idle",
        "archived": false,
        "last_prompt": "Good, commit and push",
        "metadata": {"cwd": "/home/ubuntu/kimi-android-client"},
        "agent_config": {"model": ""},
        "usage": {"input_tokens": 0, "output_tokens": 0, "cache_read_tokens": 0,
                  "cache_creation_tokens": 0, "total_cost_usd": 0, "context_tokens": 0,
                  "context_limit": 0, "turn_count": 0},
        "permission_rules": [],
        "message_count": 0,
        "last_seq": 0
      }
    ],
    "has_more": true
  },
  "request_id": "..."
}
```

Observed `status` values: `idle`, `running`, `aborted`.

### Create — `POST /api/v1/sessions`

Body: `{"metadata": {"cwd": "<working directory>"}}`. A `cwd` is expected (the
web client always sends one); the server auto-creates a workspace for new cwds.
Without a `workspace_id`, one is derived from the cwd.

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"metadata":{"cwd":"/tmp/spike"}}'
```

```json
{
  "code": 0, "msg": "success",
  "data": {
    "id": "session_86df049a-e22b-480e-842e-488b77c5469b",
    "workspace_id": "wd_spike_3e0ac6a88795",
    "title": "New Session",
    "created_at": "2026-07-17T04:05:16.506Z",
    "updated_at": "2026-07-17T04:05:16.506Z",
    "status": "idle",
    "archived": false,
    "metadata": {"cwd": "/tmp/spike"},
    "agent_config": {"model": ""},
    "usage": {"input_tokens": 0, "...": "..."},
    "permission_rules": [],
    "message_count": 0,
    "last_seq": 0
  },
  "request_id": "01KXQ400JESP1B66BE37YA4AN1"
}
```

### Get one — `GET /api/v1/sessions/{id}`

Returns the same session object as the list (see above).

### Live status — `GET /api/v1/sessions/{id}/status`

Lightweight runtime state (cheaper than the full session object):

```bash
curl -s http://127.0.0.1:58627/api/v1/sessions/session_86df049a-e22b-480e-842e-488b77c5469b/status
```

```json
{
  "code": 0, "msg": "success",
  "data": {
    "status": "idle",
    "model": "kimi-code/k3",
    "thinking_level": "max",
    "permission": "manual",
    "plan_mode": false,
    "swarm_mode": false,
    "context_tokens": 24155,
    "max_context_tokens": 262144,
    "context_usage": 0.09214401245117188
  },
  "request_id": "01KXQ461HHZ11HZ1WPW7DB8Q32"
}
```

## Messages

### History — `GET /api/v1/sessions/{id}/messages`

Query params (all optional): `page_size`, `before_id`, `after_id`, `role`.
**Items are returned newest-first**; page backwards with `before_id`.

```bash
curl -s 'http://127.0.0.1:58627/api/v1/sessions/session_86df049a-e22b-480e-842e-488b77c5469b/messages?page_size=2'
```

Message shape:

```json
{
  "id": "msg_session_86df049a-e22b-480e-842e-488b77c5469b_000006",
  "session_id": "session_86df049a-e22b-480e-842e-488b77c5469b",
  "role": "user",
  "content": [{"type": "text", "text": "Write a 300-word essay about the Linux kernel."}],
  "created_at": "2026-07-17T04:12:10.666Z",
  "metadata": {"origin": {"kind": "user"}}
}
```

Roles observed in one conversation: `user`, `assistant`, `tool`. Content is a
list of parts; part types observed:

- `{"type": "text", "text": "..."}` — user or assistant text.
- `{"type": "thinking", "thinking": "..."}` — assistant reasoning.
- `{"type": "tool_use", "tool_call_id": "tool_3oZjtQUGzDwUwQwfkXioAtne", "tool_name": "Bash", "input": {"command": "echo hello-from-spike"}}` — assistant tool call.
- `{"type": "tool_result", "tool_call_id": "tool_3oZjtQUGzDwUwQwfkXioAtne", "output": "hello-from-spike\n"}` — in a `role: "tool"` message; may also carry `"is_error": true`.

(The bundle additionally maps `image`/`video`/`file` parts for uploads; not
exercised here.)

## Prompts

### Send — `POST /api/v1/sessions/{id}/prompts`

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_86df049a-e22b-480e-842e-488b77c5469b/prompts \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"content":[{"type":"text","text":"Reply with exactly: ok"}]}'
```

```json
{
  "code": 0, "msg": "success",
  "data": {
    "prompt_id": "prompt_01KXQ4ACK46S036HVA3B4M62MK",
    "user_message_id": "msg_session_86df049a-e22b-480e-842e-488b77c5469b_pending_prompt_01KXQ4ACK46S036HVA3B4M62MK",
    "status": "running",
    "content": [{"type": "text", "text": "Reply with exactly: ok"}],
    "created_at": "2026-07-17T04:10:56.484Z"
  },
  "request_id": "01KXQ4ACHHGN1SHETDAV2CZXHP"
}
```

Optional body fields (from the bundle, all per-prompt overrides):
`permission_mode` (`manual`/`auto`/`yolo`), `model`, `thinking`, `plan_mode`,
`swarm_mode`, `agent_id`, `metadata`, `goal_objective`, `goal_control`.
`permission_mode: "manual"` was verified live (used to trigger the permission
flow below); the response and events are identical in shape otherwise. The
server is asynchronous: the HTTP response only acknowledges the prompt;
results arrive over the WebSocket.

## Abort

### Abort current prompt — `POST /api/v1/sessions/{id}:abort`

Body: `{}`. Aborts the currently running prompt/turn of the session.

```bash
curl -s -X POST 'http://127.0.0.1:58627/api/v1/sessions/session_86df049a-e22b-480e-842e-488b77c5469b:abort' \
  -H 'Content-Type: application/json; charset=utf-8' -d '{}'
# {"code":0,"msg":"success","data":{"aborted":true},"request_id":"01KXQ4CTYTV0HG44ZJ5SVB39K6"}
```

Verified mid-stream: the WS then emits `prompt.aborted` →
`turn.step.interrupted` (`reason: "aborted"`) → `turn.ended`
(`reason: "cancelled"`) → `event.session.status_changed` (`status: "aborted"`).

(The bundle also exposes per-prompt abort
`POST /sessions/{id}/prompts/{promptId}:abort` and a WS `abort` frame; neither
was needed for the core loop and neither was live-verified.)

## Permissions

A permission request surfaces as a **WebSocket event**
(`event.approval.requested`); there is no polling endpoint. The decision is
posted back via REST.

1. Prompt sent with `permission_mode: "manual"` that needs a tool → WS event
   (full payload in the WS section below): `approval_id`, `tool_call_id`,
   `tool_name`, human-readable `action`, `tool_input_display`, and
   `expires_at` (60 s validity observed).
2. Post the decision:

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_86df049a-e22b-480e-842e-488b77c5469b/approvals/01KXQ421K6TNT49DSWKS59KXC2 \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"decision":"approved"}'
# {"code":0,"msg":"success","data":{"resolved":true,"resolved_at":"2026-07-17T04:06:58.803Z"},"request_id":"01KXQ434FFC1T461Q91Q79R8JW"}
```

Body fields: `decision` (`"approved"` | `"rejected"`), optional `scope` (e.g.
`"session"` to remember the rule), `feedback`, `selected_label`. After an
approval the WS emits `event.approval.resolved` and the tool runs.

## WebSocket

### URL & handshake

```
ws://127.0.0.1:58627/api/v1/ws?client_id=<unique client id>
```

(`wss://` when the base is https; `client_id` is a client-chosen identifier,
the web app uses `web_<uuid>`.) Remotely, the handshake goes through Caddy and
needs the same Basic auth header as REST.

websocat (installed at `~/tools/websocat-bin/websocat` for this spike):

```bash
~/tools/websocat-bin/websocat 'ws://127.0.0.1:58627/api/v1/ws?client_id=spike_doc2'
```

First server frame is `server_hello`:

```json
{"type":"server_hello","timestamp":"2026-07-17T04:10:15.863Z",
 "payload":{"ws_connection_id":"conn_01KXQ494XPGSCMKZP306YC49DP","protocol_version":2,
            "heartbeat_ms":30000,"max_event_buffer_size":1000,
            "capabilities":{"event_batching":false,"compression":false}}}
```

### Heartbeat — reply to app-level pings

The server sends application-level `ping` frames (every ~30 s per
`heartbeat_ms`) and **expects a `pong` with the same nonce — otherwise it
closes the connection** (observed live: first capture died ~1 min in because
websocat did not answer):

```json
-- server: {"type":"ping","timestamp":"...","payload":{"nonce":"01KXQ41XKAV08YSZ76XED1ZV9W"}}
++ client: {"type":"pong","payload":{"nonce":"01KXQ41XKAV08YSZ76XED1ZV9W"}}
```

### Subscribe to a session

Events are per-session; subscribe to receive them. One text frame = one JSON
message. Client frames carry a client-chosen `id` that the server mirrors in
its `ack`.

```json
++ client: {"type":"subscribe","id":"c_1","payload":{"session_ids":["session_86df049a-e22b-480e-842e-488b77c5469b"]}}
-- server: {"type":"ack","id":"c_1","code":0,"msg":"success",
            "payload":{"accepted":["session_86df049a-e22b-480e-842e-488b77c5469b"],
                       "not_found":[],"resync_required":[],
                       "cursors":{"session_86df049a-e22b-480e-842e-488b77c5469b":{"seq":16,"epoch":"ep_01KXQ400MPFT5EZ22MDEQB6W3V"}}}}
```

**Resume after disconnect:** re-subscribe with the last seen cursor; the server
replays buffered newer events (buffer: 1000 events). Verified — replaying from
`seq 7` recovered 18 missed events in order:

```json
{"type":"subscribe","id":"c_1","payload":{"session_ids":["session_..."],
 "cursors":{"session_...":{"seq":7,"epoch":"ep_01KXQ400MPFT5EZ22MDEQB6W3V"}}}}
```

`unsubscribe` works the same way and gets its own `ack` (verified):
`{"type":"unsubscribe","id":"c_2","payload":{"session_ids":["session_..."]}}` →
`{"type":"ack","id":"c_2","code":0,"msg":"success","payload":{"accepted":["session_..."],"not_found":[],"resync_required":[]}}`.

### Event envelope

Every event frame:

```json
{
  "type": "<event type>",        // mirrored inside payload.type
  "seq": 21,                     // per-session monotonically increasing
  "epoch": "ep_01KXQ400MPFT5EZ22MDEQB6W3V",
  "session_id": "session_...",
  "timestamp": "2026-07-17T04:11:00.603Z",
  "volatile": true,              // only on high-frequency deltas; absent otherwise
  "offset": 0,                   // byte offset of the delta in the accumulated text
  "payload": { "type": "<event type>", "...": "..." }
}
```

### Captured round-trip (prompt → turn.ended)

Real sequence recorded 2026-07-17 by sending `POST /sessions/{id}/prompts`
with `{"content":[{"type":"text","text":"Reply with exactly: ok"}]}` while
subscribed (command: `POST .../prompts` as above; capture via websocat with a
pong responder):

```
seq 17  prompt.submitted                 prompt acknowledged (promptId, userMessageId, status "running")
seq 18  event.session.status_changed     idle -> running (current_prompt_id set)
seq 19  session.meta.updated             patch: {"lastPrompt": "..."}
seq 20  turn.started                     {"turnId": 1, "origin": {"kind": "user"}}
seq 21  turn.step.started                step 1 of the turn
seq 21  thinking.delta  (x13)            reasoning stream, volatile
seq 21  assistant.delta                  visible reply stream, volatile ("delta": "ok", offset 0)
seq 22  turn.step.completed              usage + finishReason "end_turn" + latency stats
seq 22  agent.status.updated             volatile; model, contextTokens/contextUsage, permission, usage totals
seq 23  turn.ended                       {"turnId": 1, "reason": "completed", "durationMs": 4124}
seq 24  prompt.completed                 {"promptId": "...", "reason": "completed"}
seq 25  event.session.status_changed     running -> idle
```

Exact payloads for the core-loop events (verbatim captures, envelope omitted
for brevity where noted):

**Streaming text delta** (`assistant.delta`; `thinking.delta` identical except
`"type": "thinking.delta"`):

```json
{"type":"assistant.delta","seq":21,"epoch":"ep_...","volatile":true,"offset":0,
 "session_id":"session_...","timestamp":"2026-07-17T04:11:00.603Z",
 "payload":{"type":"assistant.delta","turnId":1,"delta":"ok","agentId":"main",
            "sessionId":"session_..."}}
```

**Tool call argument streaming** (`tool.call.delta`, volatile; `argumentsPart`
fragments concatenate into the tool's JSON arguments):

```json
{"type":"tool.call.delta","seq":6,"volatile":true,"offset":0,
 "payload":{"type":"tool.call.delta","turnId":0,
            "toolCallId":"tool_3oZjtQUGzDwUwQwfkXioAtne","name":"Bash",
            "argumentsPart":"{\"command\":\"","agentId":"main","sessionId":"session_..."}}
```

**Tool call start** (after approval, if any):

```json
{"type":"tool.call.started","seq":9,
 "payload":{"type":"tool.call.started","turnId":0,
            "toolCallId":"tool_3oZjtQUGzDwUwQwfkXioAtne","name":"Bash",
            "args":{"command":"echo hello-from-spike"},
            "description":"Running: echo hello-from-spike",
            "display":{"kind":"command","command":"echo hello-from-spike",
                       "cwd":"/tmp/spike","language":"bash"},
            "agentId":"main","sessionId":"session_..."}}
```

**Tool result:**

```json
{"type":"tool.result","seq":10,
 "payload":{"type":"tool.result","turnId":0,
            "toolCallId":"tool_3oZjtQUGzDwUwQwfkXioAtne",
            "output":"hello-from-spike\n","agentId":"main","sessionId":"session_..."}}
```

**turn.started / turn.ended:**

```json
{"type":"turn.started","seq":20,
 "payload":{"type":"turn.started","turnId":1,"origin":{"kind":"user"},
            "agentId":"main","sessionId":"session_..."}}
{"type":"turn.ended","seq":23,
 "payload":{"type":"turn.ended","turnId":1,"reason":"completed","durationMs":4124,
            "agentId":"main","sessionId":"session_..."}}
```

(`turn.ended.reason` observed: `"completed"`, `"cancelled"` after abort.)

**Permission requested** (see Permissions section for the flow):

```json
{"type":"event.approval.requested","seq":7,
 "payload":{"type":"event.approval.requested","sessionId":"session_...","agentId":"main",
            "approval_id":"01KXQ421K6TNT49DSWKS59KXC2","session_id":"session_...",
            "turn_id":0,"tool_call_id":"tool_3oZjtQUGzDwUwQwfkXioAtne",
            "tool_name":"Bash","action":"Running: echo hello-from-spike",
            "tool_input_display":{"kind":"command","command":"echo hello-from-spike",
                                  "cwd":"/tmp/spike","language":"bash"},
            "created_at":"2026-07-17T04:06:23.079Z","expires_at":"2026-07-17T04:07:23.079Z"}}
```

Followed (after the REST decision) by
`event.approval.resolved` → `{"approval_id": "...", "decision": "approved", "resolved_at": "..."}`.

**Session status changed:**

```json
{"type":"event.session.status_changed","seq":18,
 "payload":{"type":"event.session.status_changed","agentId":"main",
            "sessionId":"session_...","status":"running","previous_status":"idle",
            "current_prompt_id":"prompt_01KXQ4ACK46S036HVA3B4M62MK"}}
```

**Abort sequence** (after `POST :abort`, captured live):

```
seq 31  prompt.aborted              {"promptId": "...", "abortedAt": "..."}
seq 32  turn.step.interrupted       {"reason": "aborted"}
seq 33  turn.ended                  {"reason": "cancelled", "durationMs": 6091}
seq 34  event.session.status_changed  running -> aborted
```

## Verification log

- REST probes: `curl` against `http://127.0.0.1:58627/api/v1/...` (commands
  inline above), server `0.23.3`.
- Remote auth: `curl -i http://100.64.0.1/api/v1/healthz` → `401` from
  Caddy (no credentials); local bypass confirmed by
  `"dangerous_bypass_auth": true` in `/meta`.
- WS captures: websocat 1.14.0 (`~/tools/websocat-bin/websocat`) against
  `ws://127.0.0.1:58627/api/v1/ws?client_id=spike_doc2`, subscribing to a
  throwaway session created for this spike; app-level pings answered with
  `{"type":"pong","payload":{"nonce":...}}` to keep the connection alive.
  Three round-trips recorded: tool call with manual approval, plain text
  reply, and mid-stream abort; plus one cursor-resume replay.
- Bundle cross-check: endpoint paths, query param names, optional prompt
  fields, bearer/subprotocol auth scheme, and client headers were extracted
  from `/assets/index-e04Pgjfa.js` (2026-07-17) and match the live behavior.
