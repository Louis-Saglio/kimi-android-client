# Kimi Android Client

## What this project is

A native Android clone of the Kimi Code web client. It lets Louis drive the Kimi
Code agent from his phone with a native UX instead of the browser. The app talks
to the same backend as the web client — the HTTP + WebSocket server embedded in
the `kimi` binary — reached via Caddy at `http://100.64.0.1:80` over
Tailscale (or `https://agent.example.com:8443` over the public internet),
authenticated with the server's bearer token (`Authorization: Bearer <token>`,
stored in the app settings).

## Tech stack

- Kotlin + Jetpack Compose (Material 3), single `app` module.
- Hilt + KSP for DI, coroutines/Flow, `kotlinx.serialization`, OkHttp, DataStore.
- Chat markdown: `com.mikepenz:multiplatform-markdown-renderer-m3` (pinned to
  0.26.0, the newest release built against Compose 1.6.8 / Kotlin 2.0.x — do
  not upgrade past it without also bumping the Compose BOM / Kotlin).
- MVVM: one immutable `UiState` per screen as `StateFlow`, one-shot effects via
  `Channel`. ALL one-shots (snackbars, open-file intents) go through the effect
  channel — composables never call `snackbarHostState` or do I/O directly.
- minSdk 31, targetSdk 35, AGP 8.6.x.

## Layout & conventions

- `data/network/` — `ApiClient` is the shared HTTP scaffold (URL building,
  401/HTTP/IO → `DataError`, envelope unwrap). Every REST call goes through it;
  never hand-roll `Request.Builder` in a repository.
- `data/chat/` — `ChatRepository` is a thin facade over `ChatApi` (REST verbs),
  `SessionSocket` (WebSocket, reconnect, resume cursor), `ModelsRepository`,
  `SessionPrefsStore` (per-session model/effort prefs), and `ChatMessageMapper`
  (DTO→domain). New chat endpoints belong in a collaborator, not the facade.
- `data/attachments/` — `AttachmentStore` owns all platform file I/O (content
  resolver reads capped at 50 MiB, the server's own limit; cache writes;
  FileProvider URIs). Composables never touch `ContentResolver`/`File`.
- Errors: repositories return `Result` + sealed `DataError`; ViewModels map them
  with `toUserMessage()` (kept next to `DataError`). Dropped WebSocket frames
  get a `Log.w` — never swallow silently.
- `ui/<screen>/` — screen composable + ViewModel. Chat building blocks live in
  `ui/chat/components/` (same package, `internal` — androidTests import them).
  `ui/common/` holds shared `StateViews` (loading/error/empty) and
  `Formatters` — reuse them instead of adding one-off copies.
- Sub-agent streams (045): `SessionSocket` tags transcript events with the
  emitting `agentId`; sub-agent-scoped events route to `SubagentStreamStore`
  (ui/chat, session-scoped singleton keyed by session, one non-history-backed
  `ChatTimeline` per sub-agent, same delta coalescing as ChatViewModel). The
  chat's socket collection is the store's ONLY feeder: a socket gate in
  ChatViewModel keeps it alive while a sub-agent page of the session is open
  (SubagentViewModel registers via pageOpened/pageClosed) — never open a
  second, page-owned socket (two cursors would double-feed on replay). The
  read-only `SubagentScreen` reuses `MessageList`/`ToolRows`; linked `Agent`
  rows carry `subagentId` — the row keeps its expand/collapse tap and a
  trailing open button navigates to the page. Cold-open (spawn before
  subscribe): linked rows seed their store entry from the call args/result
  (identity, status, `prompt` user bubble + `[summary]` assistant bubble)
  via `seedFromAgentRow`.
- Streaming: `ChatViewModel` conflates assistant/thinking deltas (leading edge
  immediately, then one emission per ~100 ms window, flushed before any
  non-delta event) because `MarkdownBody` re-parses on every emission. The
  window is injectable (`streamCoalesceWindowMillis`; 0 in unit tests).

## Distribution: put the release APK on the file server

After every build Louis is meant to try, the **release** APK **must** be copied
to the file server so he can download and install it on his phone:

```bash
./gradlew :app:assembleRelease
sudo cp app/build/outputs/apk/release/app-release.apk /home/fileserver/files/
sudo chown fileserver:fileserver /home/fileserver/files/app-release.apk
```

It then appears in the listing at `https://agent.example.com` (basic auth,
user `louis` — only the file server still uses basic auth, not the Kimi API).

The release build is the distribution default (decided 2026-07-22): R8 +
resource shrinking makes it ~2.9 MB vs ~28 MB debug, and it is signed with the
debug key so it installs over a debug build. Do not publish the debug APK
unless Louis explicitly asks for it. App-level keep rules live in
`app/proguard-rules.pro` (kotlinx.serialization needs none while only generated
serializers are used).

## Server drift automation

`scripts/` holds the `kimi-nightly-update` machinery (systemd timer on the
VPS, fires at 19:23 UTC = 03:23 Beijing time): it installs any new Kimi server
version (auto-update is disabled in `~/.kimi-code/tui.toml`), restarts the
server, fetches the upstream commits between the two releases from the public
`MoonshotAI/kimi-code` GitHub repo (tags `@moonshot-ai/kimi-code@<version>`)
into `~/.local/state/kimi-nightly-update/pending-changelog.md`, then a
headless agent uses that changelog to re-verify the wire protocol, rewrites
`docs/server-api.md`, files `todo/` tickets for breakage, and commits +
pushes the result. `docs/server-api.md` is the protocol reference — keep it
accurate when touching the data layer, and keep
`scripts/protocol-update-prompt.md` aligned with the doc's structure.

## Web client source reference

The Kimi Code web client source is available at
<https://github.com/MoonshotAI/kimi-code> in the `apps/kimi-web` folder. It can
be useful for understanding the HTTP + WebSocket API or for checking what
changed between two server versions.
