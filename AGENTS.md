# Kimi Android Client

## What this project is

A native Android clone of the Kimi Code web client. It lets Louis drive the Kimi
Code agent from his phone with a native UX instead of the browser. The app talks
to the same backend as the web client — the HTTP + WebSocket server embedded in
the `kimi` binary — reached via Caddy at `http://100.64.0.1:80` over
Tailscale (or `https://agent.example.com:8443` over the public internet),
authenticated with the server's bearer token (`Authorization: Bearer <token>`,
stored in the app settings).

The app only ever runs against the LATEST server version: never keep
compatibility shims for older server APIs (legacy event shapes, removed error
codes, superseded fields). When the server drops something, drop it here too —
`docs/server-api.md` always describes the current version, and the nightly
drift check keeps the two in sync.

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
- Crash reporting: Firebase Crashlytics in the default VPS project
  (`kimi-code-android-client`, Android app `fr.louissaglio.kimi.android`,
  `app/google-services.json` committed). The SDK auto-initializes — no app
  code. Reports land at
  https://console.firebase.google.com/project/kimi-code-android-client/crashlytics
  and are deobfuscated because the Crashlytics Gradle plugin uploads the R8
  mapping on every release build.

## Querying Crashlytics from the VPS

```bash
export GOOGLE_APPLICATION_CREDENTIALS=/home/ubuntu/service-account.json
TOKEN=$(gcloud auth application-default print-access-token)
APP=projects/kimi-code-android-client/apps/1:000000000000:android:0000000000000000
BASE=https://firebasecrashlytics.googleapis.com/v1alpha   # v1alpha only, v1beta1 404s
```

List issues:

```bash
curl -s -G -H "Authorization: Bearer $TOKEN" "$BASE/$APP/reports/topIssues" \
  --data-urlencode "filter.interval.startTime=2026-07-30T00:00:00Z" \
  --data-urlencode "filter.interval.endTime=2026-07-30T23:59:59Z"
```

Full stack traces (event ids from `sampleEvent` above; ~160 KB/event, parse
with `python3`; frames in `exceptions[].frames`, deobfuscated via the R8
mapping uploaded at build time):

```bash
curl -s -G -H "Authorization: Bearer $TOKEN" "$BASE/$APP/events:batchGet" \
  --data-urlencode "names=projects/-/apps/<appId>/events/<eventId>"
```

Close an issue once its fix has shipped (`projects.apps.issues.patch`; the
enum value is `CLOSED` — `RESOLVED` is rejected with INVALID_ARGUMENT):

```bash
curl -s -X PATCH -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  "$BASE/$APP/issues/<issueId>?updateMask=state" \
  -d '{"state":"CLOSED"}'
```

Crashlytics reopens a closed issue automatically if the crash reappears on a
newer app version, so a premature close resurfaces on its own — closing is
also the regression signal for "verified fixed" tickets.

## Layout & conventions

- `data/network/` — `ApiClient` is the shared HTTP scaffold (URL building,
  401/HTTP/IO → `DataError`, envelope unwrap). Every REST call goes through it;
  never hand-roll `Request.Builder` in a repository.
- `data/chat/` — `ChatRepository` is a thin facade over `ChatApi` (REST verbs),
  `SessionSocket` (WebSocket, reconnect, per-session resume cursor held at
  instance level so it survives the ON_STOP/ON_START collection restarts —
  ticket 048; a refused cursor acks as `resync_required` → REST re-sync),
  `ModelsRepository`,
  `SessionPrefsStore` (per-session model/effort prefs), and `ChatMessageMapper`
  (DTO→domain). New chat endpoints belong in a collaborator, not the facade.
- `data/attachments/` — `AttachmentStore` owns all platform file I/O (content
  resolver reads capped at 256 MiB — a client-side guard; the server removed
  its own upload cap in 0.31.0, see ticket 059; cache writes;
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
  (ui/chat, session-scoped singleton keyed by session, one `ChatTimeline` per
  sub-agent, same delta coalescing as ChatViewModel). The store is fed by
  exactly ONE session-socket collection at a time (064): the chat's while its
  socket gate holds the session (ChatViewModel signals
  chatCollectionStarted/Stopped), otherwise the store's own keep-alive
  collection while a sub-agent page is open (SubagentViewModel registers via
  pageOpened/pageClosed) — the keep-alive lives in the store because after
  process death with a page on top the chat ViewModel is never recreated. Two
  concurrent collections would double-feed on replay, so the keep-alive never
  runs while a chat collection does; never open a page-owned socket. The
  read-only `SubagentScreen` reuses `MessageList`/`ToolRows`; linked `Agent`
  rows carry `subagentId` — linked rows never expand (the page has all the
  details); tapping the row or its trailing open button navigates to the page. Cold-open (spawn before
  subscribe): linked rows seed their store entry from the call args/result
  (identity, status, `prompt` user bubble + `[summary]` assistant bubble)
  via `seedFromAgentRow`. Transcript backing (046): the page's history comes
  from `GET .../transcript?agent_id=…` (`ChatApi.transcript` →
  turn/step/frame domain model in `data/chat/Transcript.kt`, NOT
  `ChatMessage`); `SubagentViewModel` fetches the newest page on cold open
  (keepLive merge under the live tail), refetches canonically when the
  sub-agent reaches a terminal state, and pages older turns with
  `before_turn` on scroll-to-top (prepend, dedup by turn id). The seeded
  task-prompt bubble lives outside the timeline (`Stream.seededPrompt`) —
  the transcript never carries the task prompt.
- Streaming: `ChatViewModel` conflates assistant/thinking deltas (leading edge
  immediately, then one emission per ~100 ms window, flushed before any
  non-delta event) because `MarkdownBody` re-parses on every emission. The
  window is injectable (`streamCoalesceWindowMillis`; 0 in unit tests).

## Distribution: put the release APK on the file server

After every build Louis is meant to try, the **release** APK **must** be copied
to the file server so he can download and install it on his phone. The file
name on the file server must be `kimi-android-client-<branch-name>.apk`, where
`<branch-name>` is the current git branch with `/` replaced by `-` (e.g.
`kimi-android-client-main.apk`, `kimi-android-client-agent-fix-auth.apk`).
This avoids name conflicts with other apps and prevents parallel sessions on
different branches from overwriting each other's APK:

```bash
./gradlew :app:assembleRelease
BRANCH=$(git branch --show-current | tr '/' '-')
sudo cp app/build/outputs/apk/release/app-release.apk "/home/fileserver/files/kimi-android-client-${BRANCH}.apk"
sudo chown fileserver:fileserver "/home/fileserver/files/kimi-android-client-${BRANCH}.apk"
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
