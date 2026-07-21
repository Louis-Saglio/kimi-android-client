# Kimi Code server wire protocol (core loop)

Wire protocol of the HTTP+WebSocket server embedded in the `kimi` binary, as used
by Kimi Code Web. Documented and verified against the live server on 2026-07-19
(server version `0.27.0`, `"backend": "v2"`, WS `protocol_version: 2`); the Steer
and Questions sections were verified on 2026-07-20 against the same version.

Scope: core loop + user interaction — auth check, sessions, messages, prompts,
steer, questions, abort, permissions, WebSocket events. Explicitly skipped:
`fs:*`, terminals, cron, goals, workspaces, providers, skills.

Every endpoint below was executed against the live server; the exact commands
used are shown with each section. Secrets are redacted (`<token>`).

**Protocol history:** this document previously described server `0.23.3`
(verified 2026-07-17). The 0.27.0 server ships a reworked backend (`v2`) that
changed several shapes; see git history for the 0.23.3 version of this file.
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

Verified (server 0.28.1, 2026-07-21):

```bash
# No token -> rejected on authenticated routes
curl -s -o /dev/null -w '%{http_code}' http://100.64.0.1/api/v1/meta
# 401

# Wrong token -> rejected
curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer wrong" http://100.64.0.1/api/v1/meta
# 401

# Valid token -> allowed
curl -s -H "Authorization: Bearer <token>" http://100.64.0.1/api/v1/healthz
# {"code":0,"msg":"success","data":{"ok":true},"request_id":"01KY261D..."}
```

### Response envelope

Every REST response is JSON wrapped in an envelope:

```json
{"code": 0, "msg": "success", "data": { ... }, "request_id": "01KXX2YH4KDAQ3WDNQFXRG9P04"}
```

- `code: 0` = success; the payload is in `data`.
- **Errors come back as HTTP 200 with a non-zero `code`** — check `code`, not
  the HTTP status. Example: a non-existent `cwd` on session create returns
  `{"code":40409,"msg":"workspace root /tmp/spike does not exist","data":null,...}`
  over HTTP 200.

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
    "server_id": "01KY261C834HWWWT83D0QTM0FC",
    "started_at": "2026-07-21T11:12:37.123Z",
    "open_in_apps": [],
    "dangerous_bypass_auth": false,
    "backend": "v2"
  },
  "request_id": "01KY261DPAS6GMVV4X3ZFW3RAF"
}
```

## Sessions

### List — `GET /api/v1/sessions`

Query params (all optional): `page_size` (default 20), `before_id`, `after_id`
(pagination cursors — `before_id` paging verified 2026-07-19), `status`,
`include_archive`, `archived_only`, `exclude_empty`, `workspace_id`.

```bash
curl -s 'http://127.0.0.1:58627/api/v1/sessions?page_size=2'
```

Session object shape (0.27.0):

```json
{
  "id": "session_24a24af8-14a5-4b3a-a3e3-a4dd450fbc0f",
  "workspace_id": "wd_spike_3e0ac6a88795",
  "title": "Run exactly this one command, then reply done: echo doc-verify",
  "created_at": "2026-07-19T15:07:10.541Z",
  "updated_at": "2026-07-19T15:07:59.269Z",
  "busy": false,
  "main_turn_active": false,
  "pending_interaction": "none",
  "last_turn_reason": "cancelled",
  "archived": false,
  "last_prompt": "Count from 1 to 100000, one number per line.",
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

- Runtime state fields replacing 0.23.3's `status` string: `busy` (bool),
  `main_turn_active` (bool), `pending_interaction` (`"none"` | `"approval"` |
  `"question"`), `last_turn_reason` (`"completed"` | `"cancelled"`).
- `message_count` and `last_seq` are still present but observed stuck at `0` —
  do not rely on them.
- The list envelope carries `has_more` for pagination (verified with
  `page_size=2&before_id=<last id of page>`).

### Create — `POST /api/v1/sessions`

Body: `{"metadata": {"cwd": "<working directory>"}}`, optional top-level
`"title"`. A `cwd` is expected; the server auto-creates a workspace for new
cwds. A non-existent `cwd` is refused with envelope code `40409` over HTTP 200.

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"metadata":{"cwd":"/tmp/spike"}}'
```

Returns the session object above. Fresh sessions created without a `title`
come back with `"title": ""` (verified 2026-07-19; 0.23.3 defaulted to
`"New Session"`) — clients should fall back to their own placeholder.

### Get one — `GET /api/v1/sessions/{id}`

Returns the same session object as the list.

### Live status — `GET /api/v1/sessions/{id}/status`

Lightweight runtime state:

```bash
curl -s http://127.0.0.1:58627/api/v1/sessions/session_24a24af8-14a5-4b3a-a3e3-a4dd450fbc0f/status
```

```json
{
  "code": 0, "msg": "success",
  "data": {
    "busy": false,
    "model": "kimi-code/k3",
    "thinking_level": "max",
    "permission": "manual",
    "plan_mode": false,
    "swarm_mode": false,
    "context_tokens": 24797,
    "max_context_tokens": 1048576,
    "context_usage": 0.02364826202392578
  },
  "request_id": "01KXXEVFRGVVCH5M8DWHHR3PG0"
}
```

`busy: true` ⇔ the session is mid-turn (0.23.3's `status: "running"`).
`model` is omitted entirely when the session has no model configured.

## Messages

### History — `GET /api/v1/sessions/{id}/messages`

Query params (all optional): `page_size`, `before_id`, `after_id`, `role`.
**Items are returned newest-first**; page backwards with `before_id`.

```bash
curl -s 'http://127.0.0.1:58627/api/v1/sessions/session_24a24af8.../messages?page_size=2'
```

Message shape (unchanged from 0.23.3):

```json
{
  "id": "msg_01KXXENEWDC5PZT72320NHRSH6",
  "session_id": "session_24a24af8-14a5-4b3a-a3e3-a4dd450fbc0f",
  "role": "user",
  "content": [{"type": "text", "text": "Run exactly this one command, then reply done: echo doc-verify"}],
  "created_at": "2026-07-19T15:07:11.000Z",
  "metadata": {"origin": {"kind": "user"}}
}
```

Roles observed: `user`, `assistant`, `tool`. Content is a list of parts; part
types observed:

- `{"type": "text", "text": "..."}` — user or assistant text.
- `{"type": "thinking", "thinking": "..."}` — assistant reasoning.
- `{"type": "tool_use", "tool_call_id": "tool_er2XKV7KXHB4A3U2AXGxZfrY", "tool_name": "Bash", "input": {"command": "echo doc-verify"}}` — assistant tool call.
- `{"type": "tool_result", "tool_call_id": "tool_er2XKV7KXHB4A3U2AXGxZfrY", "output": "doc-verify\n"}` — in a `role: "tool"` message; may also carry `"is_error": true`.

An aborted turn commits an assistant message with **empty `content`** when the
abort landed before any assistant text was produced.

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
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_24a24af8.../prompts \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"content":[{"type":"text","text":"Reply with exactly: ok"}]}'
```

```json
{
  "code": 0, "msg": "success",
  "data": {
    "prompt_id": "msg_01KXX2Z3TAM7SJ4JR16VJR0WGK",
    "user_message_id": "msg_01KXX2Z3TAM7SJ4JR16VJR0WGK",
    "status": "running",
    "content": [{"type": "text", "text": "Reply with exactly: ok"}],
    "created_at": "2026-07-19T11:42:45.066Z"
  },
  "request_id": "01KXX2Z3R8T021FXE9HF4BCMZ9"
}
```

- **`prompt_id` now equals `user_message_id`** (a `msg_...` id; 0.23.3 issued
  distinct `prompt_...` ids).
- Optional body fields (per-prompt overrides): `permission_mode`
  (`manual`/`auto`/`yolo` — `manual` verified live), `model` (verified: a
  session with empty `agent_config.model` runs when the prompt carries one),
  `thinking`, `plan_mode`, `swarm_mode`, `agent_id`, `metadata`,
  `goal_objective`, `goal_control`.
- The server is asynchronous: the HTTP response only acknowledges the prompt;
  results arrive over the WebSocket.
- While the session is busy, a posted prompt is accepted but queued: the
  response comes back with `"status": "queued"` instead of `"running"`
  (verified 2026-07-20). A queued prompt can be steered into the running turn —
  see the next section.

## Steer

Steering injects a queued prompt into the currently running turn. The client
flow (verified 2026-07-20 on 0.27.0): while the session is busy, POST the new
prompt as usual (response `"status": "queued"`), then steer the returned
`prompt_id` into the active turn.

### Steer queued prompts — `POST /api/v1/sessions/{id}/prompts:steer`

Body: `{"prompt_ids": ["<prompt id>", ...]}` — at least one id.

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_6cef6010.../prompts:steer \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"prompt_ids":["msg_01KXZ1YD3W8BYB3AWETAARR97K"]}'
```

```json
{"code":0,"msg":"success",
 "data":{"steered":true,"prompt_ids":["msg_01KXZ1YD3W8BYB3AWETAARR97K"]},
 "request_id":"01KXZ1YF3HEF7PED02J69SR67P"}
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
curl -s -X POST 'http://127.0.0.1:58627/api/v1/sessions/session_24a24af8...:abort' \
  -H 'Content-Type: application/json; charset=utf-8' -d '{}'
# {"code":0,"msg":"success","data":{"aborted":true},"request_id":"01KXX2Z70AB2QR25Y0ER47DK4D"}
```

Verified mid-run on 0.27.0: the WS then emits `turn.step.interrupted`
(`reason: "aborted"`) → `turn.ended` (`reason: "cancelled"`) →
`event.session.work_changed` (idle) → `prompt.aborted`.

## Permissions

A permission request surfaces as **WebSocket events**; there is no polling
endpoint. The decision is posted back via REST.

1. A prompt sent with `permission_mode: "manual"` that needs a tool emits two
   WS events (see the WS section for exact payloads):
   `permission.approval.requested` (new v2 shape, camelCase; **the approval id
   is the `toolCallId`**) followed by the legacy `event.approval.requested`
   (snake_case, with `approval_id` and `expires_at` — 24 h validity observed,
   up from 60 s on 0.23.3). Both carry the human-readable action and a command
   preview.
2. Post the decision (either id form works — they are the same id):

```bash
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_24a24af8.../approvals/tool_er2XKV7KXHB4A3U2AXGxZfrY \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"decision":"approved"}'
# {"code":0,"msg":"success","data":{"resolved":true,"resolved_at":"2026-07-19T15:07:32.044Z"},"request_id":"01KXXEP2TB3QNC6W4HEPR1Q4S5"}
```

Body fields: `decision` (`"approved"` | `"rejected"`), optional `scope`,
`feedback`, `selected_label`. After the decision the WS emits
`event.approval.resolved` and `permission.approval.resolved`, then the tool
runs.

## Questions

The agent's user-question tool (`AskUserQuestion`) surfaces as **WebSocket
events**; the answer or dismissal is posted back via REST. Verified 2026-07-20
on 0.27.0 by prompting a session to call the tool — the question surfaced
directly, with no approval gate, under the session's default `manual`
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
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_68500739.../questions/tool_mU6ITagcpKjwSyiUKLHPZYWw \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"answers":{"q_0":{"kind":"single","option_id":"opt_0_1"}}}'
# free text (the question had "allow_other": true)
curl -s -X POST http://127.0.0.1:58627/api/v1/sessions/session_68500739.../questions/tool_MpHIZQr76briFmJyfKLXR4Ib \
  -H 'Content-Type: application/json; charset=utf-8' \
  -d '{"answers":{"q_0":{"kind":"other","text":"ramen"}}}'
```

Both returned HTTP 200
`{"code":0,"msg":"success","data":{"resolved":true,"resolved_at":"..."},...}`.
On the wire: `event.session.work_changed` (`pending_interaction` back to
`"none"`) → `event.question.answered` → `tool.result` whose output is
`{"answers":{"<question text>":"<option label or free text>"}}` → the turn
continues. Answering an already-resolved question:
`{"code":40405,"msg":"question <qid> not found","data":null,...}`.

5. Dismiss — `POST /api/v1/sessions/{id}/questions/{qid}:dismiss`, body `{}`:

```json
{"code":40909,"msg":"question tool_n7OfQDnRJ46s9vVgpof0zuhT dismissed",
 "data":{"dismissed":true,"dismissed_at":"2026-07-20T06:17:24.711Z"},
 "request_id":"01KXZ2R3Z5RGVZYBBRQDFG6RY0"}
```

**Beware: a successful dismiss comes back over HTTP 200 with the non-zero
envelope code `40909`** — do not gate success on `code == 0` here; check
`data.dismissed`. Re-dismissing a resolved question returns `code:40405`
("not found"). On the wire: `event.session.work_changed` (`"none"`) →
`event.question.dismissed` → `tool.result` with output
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

websocat (installed at `~/tools/websocat-bin/websocat`):

```bash
~/tools/websocat-bin/websocat 'ws://127.0.0.1:58627/api/v1/ws?client_id=doc_verify'
```

First server frame is `server_hello`:

```json
{"type":"server_hello","timestamp":"2026-07-19T15:07:10.599Z",
 "payload":{"ws_connection_id":"conn_01KXXENDW7HH6VY1FSBTE3FK5N","protocol_version":2,
            "max_event_buffer_size":1000,
            "capabilities":{"event_batching":false,"compression":false}}}
```

### Heartbeat — gone in v2

0.23.3 sent app-level `ping` frames every ~30 s and closed connections that did
not answer with a matching `pong`. **0.27.0 sends no pings** (zero observed in
a 95 s capture; the connection stayed open). Answering pings remains harmless
if an older server sends them.

### Subscribe to a session

Events are per-session; subscribe to receive them. One text frame = one JSON
message. Client frames carry a client-chosen `id` that the server mirrors in
its `ack`.

```json
++ client: {"type":"subscribe","id":"c_1","payload":{"session_ids":["session_24a24af8..."]}}
-- server: {"type":"ack","id":"c_1","code":0,"msg":"success",
            "payload":{"accepted":["session_24a24af8..."],
                       "not_found":[],"resync_required":[],
                       "cursors":{"session_24a24af8...":{"seq":1,"epoch":"ep_01KXX31QW77FH0KSY8BNAQMG4V"}}}}
```

**Resume after disconnect** (verified on 0.27.0: subscribing from `seq 35`
replayed seqs 36–38 in order):

```json
{"type":"subscribe","id":"c_1","payload":{"session_ids":["session_..."],
 "cursors":{"session_...":{"seq":35,"epoch":"ep_01KXX31QW77FH0KSY8BNAQMG4V"}}}}
```

`unsubscribe` works the same way and gets its own `ack` (verified).

### Event envelope

Every event frame:

```json
{
  "type": "<event type>",        // mirrored inside payload.type
  "seq": 21,                     // per-session monotonically increasing
  "epoch": "ep_01KXX31QW77FH0KSY8BNAQMG4V",
  "session_id": "session_...",
  "timestamp": "2026-07-19T15:07:43.588Z",
  "volatile": true,              // only on high-frequency deltas; absent otherwise
  "offset": 0,                   // byte offset of the delta in the accumulated text
  "payload": { "type": "<event type>", "...": "..." }
}
```

### Captured round-trips (0.27.0)

Full prompt with a manual-permission tool call, recorded 2026-07-19 (command:
`POST .../prompts` with `permission_mode: "manual"`, approval via REST as
above):

```
seq 3   event.session.work_changed   busy=true, main_turn_active=true
seq 4   turn.started                 {"turnId": 0, "origin": {"kind": "user"}}
seq 5   context.spliced              the new user message, inline
seq 6   turn.step.started            step 1; thinking.delta x4, tool.call.delta x7
seq 7   permission.approval.requested  v2 shape (approval id = toolCallId)
seq 9   event.approval.requested     legacy shape (approval_id, expires_at +24 h)
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
**`prompt.submitted` no longer exists** — the user message arrives inline in
`context.spliced`. `agent.status.updated` (volatile; model, context, usage) and
`session.meta.updated` (title/lastPrompt patch) fire interleaved throughout.

Abort sequence (`POST :abort` mid-run):

```
seq 35  turn.step.interrupted        {"reason": "aborted"}
seq 36  turn.ended                   {"reason": "cancelled", "durationMs": 4033}
seq 37  event.session.work_changed   busy=false, last_turn_reason "cancelled"
seq 38  prompt.aborted               {"promptId": "msg_...", "abortedAt": "..."}
```

Steer round-trip (2026-07-20): prompt A ("count to 100000") running, prompt B
posted while busy (`"status": "queued"`), then `POST prompts:steer` with B's id:

```
seq 8   prompt.steered               activePromptId=A, promptIds=[B], B's content inline
seq 11  turn.step.completed          the current step finishes normally
seq 12  context.spliced              the steered user message (no `id` field on it)
seq 13  turn.step.started            step 2 — SAME turn, no new turn.started
seq 15  turn.ended                   reason "completed"
seq 17  prompt.completed             promptId = A (the original active prompt)
```

Question round-trip (2026-07-20; the prompt asked the agent to use its
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

### Core event payloads (verbatim captures, 0.27.0)

**New user message** (`context.spliced`; `messages` carries full message
objects — `start`/`deleteCount` are splice coordinates into the context):

```json
{"type":"context.spliced","seq":5,"epoch":"ep_...","session_id":"session_...","timestamp":"...",
 "payload":{"type":"context.spliced","start":0,"deleteCount":0,
  "messages":[{"role":"user","content":[{"type":"text","text":"Run exactly this one command, then reply done: echo doc-verify"}],
               "toolCalls":[],"origin":{"kind":"user"},"id":"msg_01KXXENEWDC5PZT72320NHRSH6"}],
  "agentId":"main","sessionId":"session_..."}}
```

**Work state** (`event.session.work_changed`; `pending_interaction` observed:
`"none"`, `"approval"`, `"question"`; `last_turn_reason` present only when idle):

```json
{"type":"event.session.work_changed","seq":8,
 "payload":{"type":"event.session.work_changed","busy":true,"main_turn_active":true,
            "pending_interaction":"approval","agentId":"main","sessionId":"session_..."}}
```

**Streaming text delta** (`assistant.delta`; `thinking.delta` identical except
`"type": "thinking.delta"`):

```json
{"type":"assistant.delta","seq":16,"epoch":"ep_...","volatile":true,"offset":0,
 "session_id":"session_...","timestamp":"2026-07-19T15:07:43.588Z",
 "payload":{"type":"assistant.delta","turnId":0,"delta":"done","agentId":"main",
            "sessionId":"session_..."}}
```

**Tool call argument streaming** (`tool.call.delta`, volatile; `argumentsPart`
fragments concatenate into the tool's JSON arguments), **tool call start** and
**tool result**: unchanged from 0.23.3 (`toolCallId`, `name`, `args`, `display`
on start; `toolCallId`, `output` on result).

**Tool progress** (`tool.progress`, volatile, new in v2 — live tool output):

```json
{"type":"tool.progress","seq":13,"volatile":true,
 "payload":{"type":"tool.progress","turnId":0,"toolCallId":"tool_er2XKV7KXHB4A3U2AXGxZfrY",
            "update":{"kind":"stdout","text":"doc-verify\n"},"agentId":"main","sessionId":"session_..."}}
```

**Step completed** (usage fields renamed vs 0.23.3):

```json
{"type":"turn.step.completed","seq":15,
 "payload":{"type":"turn.step.completed","turnId":0,"step":1,"stepId":"6cff69da-...",
            "usage":{"inputOther":5405,"output":77,"inputCacheRead":19200,"inputCacheCreation":0},
            "finishReason":"tool_use","llmFirstTokenLatencyMs":19531,"llmStreamDurationMs":421,
            "providerFinishReason":"tool_calls","agentId":"main","sessionId":"session_..."}}
```

**turn.started / turn.ended:**

```json
{"type":"turn.started","seq":4,
 "payload":{"type":"turn.started","turnId":0,"origin":{"kind":"user"},
            "agentId":"main","sessionId":"session_..."}}
{"type":"turn.ended","seq":18,
 "payload":{"type":"turn.ended","turnId":0,"reason":"completed","durationMs":...,
            "agentId":"main","sessionId":"session_..."}}
```

(`turn.ended.reason` observed: `"completed"`, `"cancelled"` after abort.)

**Permission requested — v2 shape** (`permission.approval.requested`,
camelCase; **no `approval_id`, no `expires_at` — use `toolCallId` as the
approval id**):

```json
{"type":"permission.approval.requested","seq":7,
 "payload":{"type":"permission.approval.requested","sessionId":"session_...","agentId":"main",
            "turnId":0,"toolCallId":"tool_er2XKV7KXHB4A3U2AXGxZfrY",
            "toolName":"Bash","action":"Running: echo doc-verify",
            "display":{"kind":"command","command":"echo doc-verify","cwd":"/tmp/spike","language":"bash"},
            "toolInput":{"command":"echo doc-verify"}}}
```

**Permission requested — legacy shape** (`event.approval.requested`, still
emitted right after the v2 one; `expires_at` is `created_at` + 24 h):

```json
{"type":"event.approval.requested","seq":9,
 "payload":{"type":"event.approval.requested","sessionId":"session_...","agentId":"main",
            "approval_id":"tool_er2XKV7KXHB4A3U2AXGxZfrY","session_id":"session_...",
            "turn_id":0,"tool_call_id":"tool_er2XKV7KXHB4A3U2AXGxZfrY",
            "tool_name":"Bash","action":"Running: echo doc-verify",
            "tool_input_display":{"kind":"command","command":"echo doc-verify",
                                  "cwd":"/tmp/spike","language":"bash"},
            "created_at":"2026-07-19T14:42:11.579Z","expires_at":"2026-07-20T14:42:11.579Z"}}
```

Both are followed (after the REST decision) by `event.approval.resolved` →
`{"approval_id": "...", "decision": "approved", "resolved_at": "..."}` and its
`permission.approval.resolved` v2 duplicate (adds `"decision"` to the request
payload).

**Prompt finished:**

```json
{"type":"prompt.completed","seq":20,
 "payload":{"type":"prompt.completed","promptId":"msg_01KXXENEWDC5PZT72320NHRSH6",
            "finishedAt":"2026-07-19T15:07:43.596Z","reason":"completed",
            "agentId":"main","sessionId":"session_..."}}
```

**Prompt steered** (`prompt.steered`, 2026-07-20; `activePromptId` is the
running prompt, `promptIds` the steered-in queued ones, their content inline):

```json
{"type":"prompt.steered","seq":8,
 "payload":{"type":"prompt.steered","activePromptId":"msg_01KXZ1Y3AA2QCVYDCWBPTYVAA1",
            "promptIds":["msg_01KXZ1YD3W8BYB3AWETAARR97K"],
            "content":[{"type":"text","text":"Stop counting. Reply with exactly: steered-ok"}],
            "steeredAt":"2026-07-20T06:03:24.146Z","agentId":"main","sessionId":"session_..."}}
```

**Question requested** (`event.question.requested`, 2026-07-20; snake_case;
**`question_id` == `tool_call_id`** — no v2 duplicate). `options[].description`
is omitted when the option has none; `multi_select` did not appear on this
single-select question:

```json
{"type":"event.question.requested","seq":9,
 "payload":{"type":"event.question.requested","question_id":"tool_mU6ITagcpKjwSyiUKLHPZYWw",
            "session_id":"session_...","turn_id":0,
            "tool_call_id":"tool_mU6ITagcpKjwSyiUKLHPZYWw",
            "questions":[{"id":"q_0","question":"Red or blue?","header":"Color",
                          "options":[{"id":"opt_0_0","label":"red","description":"warm color"},
                                     {"id":"opt_0_1","label":"blue","description":"cool color"}],
                          "allow_other":true}],
            "created_at":"2026-07-20T06:06:49.808Z","agentId":"main","sessionId":"session_..."}}
```

**Question answered** (`event.question.answered`; `answers` is keyed by
**question text**, values are option labels or free text):

```json
{"type":"event.question.answered","seq":11,
 "payload":{"type":"event.question.answered","question_id":"tool_mU6ITagcpKjwSyiUKLHPZYWw",
            "answers":{"Red or blue?":"blue"},"resolved_at":"2026-07-20T06:08:12.548Z",
            "agentId":"main","sessionId":"session_..."}}
```

**Question dismissed:**

```json
{"type":"event.question.dismissed","seq":28,
 "payload":{"type":"event.question.dismissed","question_id":"tool_TThzzORvQB8tHTKyLhIL5bQt",
            "dismissed_at":"2026-07-20T06:12:37.844Z","agentId":"main","sessionId":"session_..."}}
```

## Verification log

- REST probes: `curl` against `http://127.0.0.1:58627/api/v1/...` (commands
  inline above), server `0.27.0` (`backend: v2`).
- Remote auth (re-verified 2026-07-21 on 0.28.1): `/api/v1/meta` and
  `/api/v1/sessions` → `401` without a token or with a wrong one, `200` with
  `Authorization: Bearer <token>`; `/api/v1/healthz` is public (always `200`).
  The unauthenticated WebSocket handshake is rejected with `401`. Auth is
  server-native (bearer token), enforced on all authenticated REST and
  WebSocket routes; `"dangerous_bypass_auth": false` in `/meta`.
- WS captures: websocat 1.14.0 (`~/tools/websocat-bin/websocat`) against
  `ws://127.0.0.1:58627/api/v1/ws?client_id=...`, subscribing to throwaway
  sessions created for the captures. 2026-07-19 round-trips recorded on
  0.27.0: tool call with manual approval (approve path included), plain text
  reply, mid-run abort, cursor-resume replay (seq 35 → 36–38), and an
  unsubscribe ack. No heartbeat frames observed in 95 s.
- 2026-07-20 round-trips on 0.27.0 (same server process): mid-turn steer of a
  queued prompt (`prompts:steer`), idle-session steer error (`code:40402`),
  question surface + answer (selected option and free text), question dismiss
  (HTTP 200 with `code:40909`), `GET questions?status=pending` polling,
  `GET questions/{qid}` confirmed 404, re-answer/re-dismiss of resolved
  questions (`code:40405`). Questions triggered by prompting the session to
  call its `AskUserQuestion` tool.
- 2026-07-17 round-trips on 0.23.3 (superseded shapes): see git history.
