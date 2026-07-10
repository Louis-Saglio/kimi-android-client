# Developer Guide — Kimi Android Client

## Architecture

The app is a single-module Android project written in Kotlin with Jetpack Compose.

- `data/` — network layer (REST, WebSocket, repositories).
- `ui/` — Compose screens and components.
- `viewmodel/` — screen-level state holders.

## Kimi Server Protocol

The app talks directly to the Kimi Code server REST API and WebSocket endpoint.

### REST endpoints

Base URL: `http://100.64.0.1` ( proxied by Caddy, Basic Auth required).

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/meta` | Server info and capabilities. |
| GET | `/api/v1/sessions` | List sessions. |
| POST | `/api/v1/sessions` | Create a session. Body: `{ metadata: { cwd: ... } }`. |
| GET | `/api/v1/sessions/{id}` | Get session details. |
| GET | `/api/v1/sessions/{id}/messages` | Fetch messages. |
| POST | `/api/v1/sessions/{id}/prompts` | Send a prompt. Body: `{ content: [{ type: "text", text: "..." }] }`. |
| POST | `/api/v1/sessions/{id}:archive` | Archive a session. |
| POST | `/api/v1/files` | Upload a file (multipart). |
| GET | `/api/v1/files/{id}` | Download a file. |

All endpoints return a wrapper: `{ code: 0, msg: "success", data: ... }`.

### WebSocket

Endpoint: `ws://100.64.0.1/api/v1/ws`

After connecting, the client sends:

```json
{
  "type": "client_hello",
  "id": "...",
  "payload": {
    "client_id": "...",
    "subscriptions": ["session_id"],
    "cursors": { "session_id": 0 }
  }
}
```

The server then streams events such as:

- `event.message.created`
- `event.message.updated`
- `event.assistant.delta`
- `event.session.status_changed`
- `event.tool.output`
- `event.approval.requested`
- `event.question.requested`

### Message content blocks

Messages contain an array of content blocks:

- `{ type: "text", text: "..." }`
- `{ type: "thinking", thinking: "..." }`
- `{ type: "tool_use", tool_call_id: "...", tool_name: "...", input: ... }`
- `{ type: "tool_result", tool_call_id: "...", output: ..., is_error: false }`
- `{ type: "file", file_id: "...", name: "...", media_type: "...", size: 123 }`

## Build and run

```bash
cd /home/ubuntu/kimi-android-client
export JAVA_HOME=/home/ubuntu/tools/jdk-21.0.7+6
export ANDROID_HOME=/home/ubuntu/tools/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleDebug
```

## Dependencies

- Jetpack Compose for UI.
- OkHttp for HTTP and WebSocket.
- Kotlinx Serialization for JSON.
- AndroidX Security for encrypted credential storage.
