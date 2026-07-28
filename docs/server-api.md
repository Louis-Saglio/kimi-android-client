# Kimi Code server wire protocol (core loop)

Wire protocol of the HTTP+WebSocket server embedded in the `kimi` binary, as used
by Kimi Code Web. Documented and verified against the live server on 2026-07-27
(server version `0.29.2`, `"backend": "v2"`, WS `protocol_version: 2`; the
Sub-agents section was added and verified on 2026-07-28, same server).

Scope: core loop + user interaction — auth check, sessions, messages, prompts,
steer, questions, abort, permissions, WebSocket events, file attachments,
sub-agents (foreground lifecycle + background task store), plus
the workspace list used by the mobile create-session flow. Explicitly skipped:
`fs:*`, terminals, cron, goals, providers, skills.

Every endpoint below was executed against the live server; the exact commands
used are shown with each section. Secrets are redacted (`<token>`).

**Protocol history:** this document previously described server `0.29.1`
(verified 2026-07-25; see git history), `0.29.0` before that, `0.28.1` before
that, `0.27.0` before that, and `0.23.3` before that. The drift from 0.29.1 to
0.29.2 is **minor** for the core loop: every REST and WebSocket shape the
Android app parses is unchanged, with one exception — a steered user message
spliced via `context.spliced` **carries an `id` again** (0.29.0 had dropped
it; on 0.29.2 it is a fresh ULID, not the queued prompt's id — details in the
WS section). This is backward compatible for clients that followed the 0.29.0
advice to treat spliced-message ids as optional. Outside the core loop the
release adds provider write endpoints (`POST`/`PUT`/`DELETE /api/v1/providers`,
`GET /api/v1/catalog/providers[/{catalog_id}]`; the single-provider GET now
also reveals the stored `api_key`), an optional `desktop` flag on session
export, and a WS frame-flush fix (timing only, no shape change). The models
catalog is unchanged apart from the `kimi-code/k3-256k` display name
(`"k3-256k"` → `"K3-256k"`); `GET /api/v1/config` `default_model` reads
`kimi-code/k3` on this server again (it is a config value, not protocol — see
Models). Details inline. Server updates are installed by the
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

Verified (server 0.29.2, 2026-07-27):

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
  and a server-side `stack` string (still present on 0.29.0; ignore it).

## Meta

`GET /api/v1/meta` — server identity and capabilities. Requires the token.

```bash
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:58627/api/v1/meta
```

```json
{
  "code": 0, "msg": "success",
  "data": {
    "server_version": "0.29.2",
    "capabilities": {"websocket": true, "file_upload": true, "fs_query": true, "mcp": true, "tasks": true, "terminal": true},
    "server_id": "01KYJGGW0WVW0SR8X6388R34PP",
    "started_at": "2026-07-27T19:23:41.468Z",
    "open_in_apps": [],
    "dangerous_bypass_auth": false,
    "backend": "v2"
  },
  "request_id": "01KY6G2SVTVTF380XEWC88EC67"
}
```

## Models

### List — `GET /api/v1/models`

The models the server can run, for model pickers. Requires the token.

```bash
curl -s -H "Authorization: Bearer <token>" http://127.0.0.1:58627/api/v1/models
```

Verified live on 0.29.2 (2026-07-27, `capabilities` arrays trimmed):

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
  protocol: it read `kimi-code/kimi-for-coding` on 0.29.1 (2026-07-25) and
  `kimi-code/k3` on 0.29.2 (2026-07-27) — the server may change it
  independently of protocol drift.

## Sessions

### List — `GET /api/v1/sessions`

Query params (all optional): `page_size` (default 20), `before_id`, `after_id`
(pagination cursors — `before_id` paging verified 2026-07-22, list shape
re-verified 2026-07-27 on 0.29.2), `status`, `include_archive`, `archived_only`,
`exclude_empty`, `workspace_id`.

```bash
curl -s -H "Authorization: Bearer <token>" 'http://127.0.0.1:58627/api/v1/sessions?page_size=2'
```

Session object shape (0.29.2, fields truncated for brevity):

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
  absent entirely on sessions that have not run a turn yet** (re-verified
  2026-07-23).
- `message_count` and `last_seq` are still present but observed stuck at `0`
  (re-verified 2026-07-23 after several turns) — do not rely on them.
- The list envelope carries `has_more` for pagination (verified with
  `page_size=2&before_id=<last id of page>`).

### Create — `POST /api/v1/sessions`

Body: `{"metadata": {"cwd": "<working directory>"}}`, optional top-level
`"title"`. A `cwd` is expected; the server auto-creates a workspace for new
cwds. A non-existent `cwd` is refused with envelope code `40409` over HTTP 200.
**Note for this VPS:** the `kimi-web.service` unit runs with `PrivateTmp=yes`,
so the server's `/tmp` is a private namespace — a host-side `/tmp/spike` is
invisible to it; create it inside the service namespace, owned by the service user
(`sudo nsenter -t $(systemctl show kimi-web -p MainPID --value) -m mkdir -p /tmp/spike &&
sudo nsenter -t $(systemctl show kimi-web -p MainPID --value) -m chown ubuntu:ubuntu /tmp/spike`)
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
session's current config (`"off"`/`"yolo"` observed on a fresh session).

## Workspaces

### List — `GET /api/v1/workspaces`

Returns every workspace the server knows about (verified live 2026-07-27,
server `0.29.2`). Requires the token.

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

## Files (attachments)

File upload backs message attachments: upload the bytes first, then reference
the returned `file_id` in a prompt's `content` items (see Prompts below). The
endpoints and the prompt-side rewriting were verified live on 0.29.0
(2026-07-24, session `attach-spike`) and re-verified on 0.29.2 (2026-07-27:
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

- File ids are `f_` + ULID/UUID. Any MIME type is accepted; the per-file limit
  is **50 MiB** (server-enforced, truncated uploads are rejected 413/41301).
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
  - Uploaded images are inlined as base64, but the shape **differs between the
    prompt ACK and history**: the `POST .../prompts` response echoes
    `{"type":"image","source":{"kind":"base64","media_type":"…","data":"…"}}`,
    while `GET .../messages` returns
    `{"type":"image","source":{"kind":"url","url":"data:<mime>;base64,…"}}`.
    Clients must accept both.
  - Large images are compressed before enqueueing (verified: a 14.1 MB
    2600×1900 png was downscaled to 1000×731; a 3.2 MB one passed through
    untouched). A compression then prepends a separate **text part** wrapped in
    `<system>…</system>`: `Image compressed to fit model limits: original
    <W>x<H> <mime> (<orig>) -> sent <W>x<H> <mime> (<new>). Fine detail may be
    lost. The uncompressed original is saved at
    "<sessionDir>/media-originals/<hash>.<ext>"; if you need fine detail (e.g.
    small text), call ReadMediaFile on that path …`. Formats the model rejects
    degrade to the "Attached file" notice above (source-derived, not verified).
  - Videos appear as `{type:"video", source:{kind:"file", file_id}}` over REST
    (source-derived; unreachable live on 0.29.0, see validation note above).
- Optional body fields (per-prompt overrides): `permission_mode`
  (`manual`/`auto`/`yolo` — `manual` verified live), `model` (verified: a
  session with empty `agent_config.model` runs when the prompt carries one),
  `thinking`, `plan_mode`, `swarm_mode`, `agent_id`, `metadata`,
  `goal_objective`, `goal_control`.
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

Verified mid-run on 0.29.0 (2026-07-23) and again on 0.29.2 (2026-07-27): the
WS then emits `turn.step.interrupted` (`reason: "aborted"`) → `turn.ended`
(`reason: "cancelled"`) → `event.session.work_changed` (idle) →
`prompt.aborted`.

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
2026-07-27 on 0.29.2 (surface + selected-option answer + dismiss + polling,
same session flow as 2026-07-23 on 0.29.0) by prompting a session to call the
tool — the question surfaced directly, with no approval gate, under the
session's default permission mode.

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
4. Answer — `POST /api/v1/sessions/{id}/questions/{qid}`. `answers` keys are
   the per-question item ids from the request payload (`q_0`); values carry
   `kind` plus `option_id` (selected option) or `text` (free text):

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
```

Both returned HTTP 200
`{"code":0,"msg":"success","data":{"resolved":true,"resolved_at":"..."},...}`.
On the wire: `event.session.work_changed` (`pending_interaction` back to
`"none"`) → `event.question.answered` → `tool.result` whose output is
`{"answers":{"<question text>":"<option label or free text>"}}` → the turn
continues. Answering an already-resolved question returns
`{"code":40902,"msg":"question <qid> already resolved","data":{"resolved":false},...}`
(re-verified 2026-07-23; 0.27.0 returned `40405` "not found").

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

The web bundle hints at further answer kinds (`multi`, `multi_with_other`,
`skipped`) and optional `method`/`note` body fields — **not verified live**;
treat as leads only.

## Todos

The agent's todo list has **no dedicated endpoint or WS event** on this
protocol. Clients derive it from the agent's `TodoList` tool calls, exactly as
the web client does (`apps/kimi-web/src/composables/latestTodos.ts`). Verified
against upstream source on `main` 2026-07-27
(`packages/agent-core-v2/src/agent/tools/todo-list/todoListTool.ts`,
`packages/agent-core-v2/src/session/todo/todoItem.ts`) **and captured live on
0.29.2** (2026-07-27: a three-step prompt produced `tool.call.started` with
`"name": "TodoList"` and args `{"todos": [{"status": "in_progress", "title":
"Run echo step-one"}, {"status": "pending", "title": "Run echo step-two"},
{"status": "pending", "title": "Run echo step-three"}]}`; history carried the
same `toolUse` part).

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

## Sub-agents

How the server exposes the sub-agents the main agent spawns with its `Agent`
(or `AgentSwarm`) tool. All shapes below verified live on `0.29.2`
(2026-07-28, throwaway session in `/tmp/spike`, prompts with
`permission_mode:"yolo"`); fields not captured live are marked as
upstream-schema-only (`packages/kap-server/src/protocol/events-zod.ts`).

There are **two exposure paths**, chosen by the `Agent` call's
`run_in_background` argument:

- **Foreground** (default): the sub-agent exists only on the WS event stream
  and in the session-snapshot roster. It is **never** listed by REST `/tasks`
  (verified: `{"items":[]}` right after a completed foreground run) and its
  messages never enter history.
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

The tool result is plain text. Foreground (waits for completion):

```
agent_id: agent-12
actual_subagent_type: explore
status: completed

[summary]
<the sub-agent's result summary>
```

Background (returns immediately):

```
task_id: agent-7noib8bb
status: running
agent_id: agent-13
actual_subagent_type: explore
automatic_notification: true

description: doc-verify-bg
```

`GET .../messages` history carries only the main agent's messages — the
`Agent` tool_use/tool_result pair is the **only** trace of a sub-agent there
(verified 2026-07-28).

### Foreground lifecycle — WS sequence

```
seq 17  tool.call.started   name "Agent", args {subagent_type, description, prompt} (main)
seq 18  agent.created       payload {"agentId": "agent-12", "sessionId": ...}
seq 19  subagent.spawned    identity metadata (payload.agentId "main")
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
`subagent.*` events carry only `subagentId`):

```json
{"type":"subagent.spawned","seq":19,
 "payload":{"type":"subagent.spawned","subagentId":"agent-12","subagentName":"explore",
            "parentToolCallId":"tool_mhF8UXcf5QwvZbh5223tiIng","parentAgentId":"main",
            "callerAgentId":"main","description":"doc-verify-subagent",
            "runInBackground":false,"agentId":"main","sessionId":"session_..."}}
```

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
app filters these frame types in `SessionSocket` since ticket 044; sub-agent
approvals/questions deliberately still surface there — they are actionable.)

### Background lifecycle — WS sequence

```
seq 46  tool.call.started   name "Agent", args {..., "run_in_background":true}
seq 48  subagent.spawned    "runInBackground":true
seq 49+ sub-agent turn frames (agentId "agent-13"), as in the foreground path
seq 51  subagent.started
seq 52  task.started        task-store registration (REST-visible from here)
seq 53  background.task.started     legacy alias — same payload as task.started
seq 55  tool.result         "task_id: ... status: running ..." (immediate)
seq 71  subagent.completed
seq 72  task.terminated     info.status "completed", endedAt set
seq 73  background.task.terminated  legacy alias
seq 77  task.notified       completion notification
seq 78  context.spliced     a `<notification ...>` user message spliced into the
                            MAIN agent — how the main agent learns of it
```

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

`info.status` uses the **core** vocabulary (`running` | `completed` |
`failed` | `timed_out` | `killed` | `lost`), not the REST wire one (mapping
below); `kind:"agent"` marks a sub-agent (`process` = background bash).
`task.notified` (verbatim shape): `{"notificationType":"task.completed" |
"task.killed","title":"...","body":"...","severity":"info" | "warning",
"sourceKind":"background_task","sourceId":"<taskId>"}`.

### REST `/tasks` — background tasks only

All envelope-over-HTTP-200 (verified 2026-07-28):

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
  `killed`→`cancelled`, `timed_out`/`lost`→`failed`.
- **Get** — `GET .../tasks/{task_id}`; `with_output=true` adds
  `output_preview` (tail of the stored result, default cap 32768 bytes —
  override with `output_bytes`) and `output_bytes` (byte length of the
  returned preview). Unknown task → `40406`; unknown session → `40401`.
- **Cancel** — `POST .../tasks/{task_id}:cancel` → `{"cancelled":true}` on a
  running task; a terminal task answers `40904` with
  `data:{"cancelled":false}` + `details.current_status`. Cancelling a
  running background sub-agent emits `task.terminated` with
  `status:"killed"` + `stopReason:"Aborted by the user"` — **no**
  `subagent.failed`/`subagent.completed` — then `task.notified`
  (`notificationType:"task.killed"`) and the notification splice; REST reads
  `"cancelled"` afterwards.

Task object (verbatim):

```json
{"id":"agent-7noib8bb","session_id":"session_...","kind":"subagent",
 "description":"doc-verify-bg","status":"completed",
 "created_at":"2026-07-28T03:40:08.039Z","started_at":"2026-07-28T03:40:08.039Z",
 "completed_at":"2026-07-28T03:40:22.372Z"}
```

The REST shape is thinner than the WS/snapshot one: no `subagent_type`, no
`parent_tool_call_id`, and `created_at` == `started_at`.

### Session snapshot roster

`GET /api/v1/sessions/{sid}/snapshot` carries an optional `subagents` array:
the **foreground** roster at the watermark, so a reconnecting client can
rebuild the identity metadata (cursor-resume replays nothing earlier than
the cursor, and `subagent.spawned` is never replayed). Verified 2026-07-28
right after the foreground run:

```json
{"id":"agent-12","session_id":"session_...","kind":"subagent",
 "description":"doc-verify-subagent","status":"completed",
 "subagent_phase":"completed","subagent_type":"explore",
 "parent_tool_call_id":"tool_mhF8UXcf5QwvZbh5223tiIng","run_in_background":false,
 "created_at":"2026-07-28T03:37:35.492Z","started_at":"2026-07-28T03:37:35.496Z",
 "completed_at":"2026-07-28T03:37:51.951Z","output_preview":"<resultSummary>"}
```

- `subagent_phase`: `queued` | `working` | `suspended` | `completed` |
  `failed`; `suspended_reason` and `swarm_index` optional.
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

First server frame is `server_hello` (unchanged):

```json
{"type":"server_hello","timestamp":"2026-07-23T03:26:48.774Z",
 "payload":{"ws_connection_id":"conn_01KY6G5WY5EKAC0EQGZRN8XKD9","protocol_version":2,
            "max_event_buffer_size":1000,
            "capabilities":{"event_batching":false,"compression":false}}}
```

### Heartbeat — gone in v2

0.23.3 sent app-level `ping` frames every ~30 s and closed connections that did
not answer with a matching `pong`. **v2 sends no pings** (zero observed across
all 2026-07-22 and 2026-07-23 captures; the connections stayed open).
Answering pings remains harmless if an older server sends them.

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

**Resume after disconnect** (re-verified on 0.29.2, 2026-07-27: subscribing
from `seq 135` replayed seqs 136–142 in order; on 0.29.0: from `seq 70`
replayed 71–78, from `seq 78` replayed the later question events):

```json
{"type":"subscribe","id":"r_1","payload":{"session_ids":["session_..."],
 "cursors":{"session_...":{"seq":70,"epoch":"ep_01KY6G589H3J9XHG6RF1RYAE59"}}}}
```

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

### Captured round-trips

Full prompt with a manual-permission tool call, recorded 2026-07-23 on 0.29.0
and **re-verified event-for-event on 0.29.2 (2026-07-27)** (command:
`POST .../prompts` with `permission_mode: "manual"`, approval via REST as
above). Same skeleton as 0.28.1 (one nit: on the 0.29.2 capture `turn.started`
(seq 4) arrived *before* the busy `event.session.work_changed` (seq 5); treat
their relative order as unspecified):

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

### Core event payloads (verbatim captures, re-verified 0.29.2)

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
            "display":{"kind":"command","command":"echo doc-verify-0290","cwd":"/tmp/spike","language":"bash"},
            "agentId":"main","sessionId":"session_..."}}
```

**Tool result** (`tool.result`): unchanged (`toolCallId`, `output`).

**Tool progress** (`tool.progress`, volatile — live tool output). Unchanged:

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

**turn.started / turn.ended** — unchanged (`turn.started` carries `prompt`,
the prompt text):

```json
{"type":"turn.started","seq":5,
 "payload":{"type":"turn.started","turnId":0,"origin":{"kind":"user"},
            "prompt":"Run exactly this one command, then reply done: echo doc-verify-0290",
            "agentId":"main","sessionId":"session_..."}}
{"type":"turn.ended","seq":19,
 "payload":{"type":"turn.ended","turnId":0,"reason":"completed","durationMs":91351,
            "agentId":"main","sessionId":"session_..."}}
```

(`turn.ended.reason` observed: `"completed"`, `"cancelled"` after abort.)

**Agent status** (`agent.status.updated`, volatile) — two payload variants,
both still present. **0.29.0 changes, all additive:** the model/usage variant
can now carry `thinkingEffort`; the `phase` objects now carry `since` (epoch
ms) on active phases; the `awaiting_approval` inline approval now carries both
`approvalId` and `toolCallId`; and there is a **new phase kind `tool_call`**
(emitted while a tool executes). Observed kinds: `running`, `streaming`
(with `"stream": "thinking" | "tool_call"`), `awaiting_approval`, `tool_call`,
`ended` (with `at` instead of `since`):

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
`isCustomTitle`, `lastPrompt`). Unchanged:

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
            "display":{"kind":"command","command":"echo doc-verify-0290","cwd":"/tmp/spike","language":"bash"},
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
                                  "cwd":"/tmp/spike","language":"bash"},
            "created_at":"2026-07-23T03:27:38.761Z","expires_at":"2026-07-24T03:27:38.761Z"}}
```

Both are followed (after the REST decision) by `event.approval.resolved` →
`{"approval_id": "...", "decision": "approved", "resolved_at": "..."}` and its
`permission.approval.resolved` v2 duplicate (adds `"decision"` to the request
payload).

**Prompt finished:** unchanged:

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
rely on it):

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
**question text**, values are option labels or free text). Unchanged —
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

- 2026-07-28 sub-agent round-trips on `0.29.2` (throwaway session
  `session_1525d049-...` in `/tmp/spike`; first prompt needed
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
  inline above), server `0.29.2` (`backend: v2`).
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
- 2026-07-27 0.29.2 round-trips (throwaway session `session_43b9ba9b-...` in
  `/tmp/spike`): manual tool-call approval (approve path, both
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
- 2026-07-25 0.29.1 round-trips (throwaway session `session_c42940aa-...` in
  `/tmp/spike`): manual tool-call approval (approve path), `AskUserQuestion`
  surface + answer via `POST .../questions/{qid}`, queued prompt while busy,
  `prompts:steer`, mid-run `:abort`, file upload/download/delete, and prompt
  with a `file` attachment (history confirmed the `Attached file "…"` notice
  wording, including the em dash, and `<sessionDir>/attachments/`
  materialization). All captured with `websocat` 1.14.0 against
  `ws://127.0.0.1:58627/api/v1/ws`; no heartbeat frames observed.
- Housekeeping notes: the `kimi-web.service` unit has `PrivateTmp=yes`, so
  `/tmp/spike` must exist in the service's mount namespace (see Sessions →
  Create). There is no session-delete endpoint (`DELETE` → 404, re-verified
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
