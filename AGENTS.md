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
- Chat markdown: `com.mikepenz:multiplatform-markdown-renderer-m3` 0.38.1
  (ticket 073: newest release on the Kotlin 2.2.x line; the 0.43.0 rung is
  unblocked now that Kotlin is 2.4 — ticket 077). Stack: Kotlin 2.4.10,
  Compose BOM 2026.06.01, Hilt 2.60.1 (2.51.1's KSP processor crashes under
  KSP2; 2.57.x's Gradle plugin predates AGP 9 — don't downgrade either). `MarkdownBody` parses the INITIAL content
  synchronously (`rememberMarkdownState(immediate = true)`) so MessageList's
  bottom pin measures real first-frame row heights; content changes (streaming)
  still re-parse asynchronously, with `retainState` keeping the previous
  snapshot on screen.
- MVVM: one immutable `UiState` per screen as `StateFlow`, one-shot effects via
  `Channel`. ALL one-shots (snackbars, open-file intents) go through the effect
  channel — composables never call `snackbarHostState` or do I/O directly.
- minSdk 36 (Android 16+ only), targetSdk 36, compileSdk 37, Java 21
  (ticket 079), AGP 9.3.1, Gradle 9.6.1, KSP 2.3.10 (ticket 075). AGP 9's
  built-in Kotlin compiles the Kotlin sources — `org.jetbrains.kotlin.android`
  is NOT applied (applying it errors out); KGP 2.4.10/KSP 2.3.10 are pinned
  via a `buildscript { classpath(...) }` block in the root `build.gradle.kts`
  (the documented AGP 9 override; its built-in baseline is KGP 2.2.10).
  Escape hatches until AGP 10: `android.builtInKotlin=false`,
  `android.newDsl=false`. The VPS cmdline-tools are too stale to see new SDK
  packages — let AGP auto-install platforms at build time (android-37.0 got
  installed that way). On the VPS, build with `--max-workers=1`: the first
  parallel build on this toolchain exceeded the 5 GB kimi-web cgroup and was
  OOM-killed.
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
- Chat feature collaborators (the 065 `DeltaCoalescer` pattern, generalized by
  072): a cohesive chat feature gets an `internal` class in `ui/chat`
  (`DeltaCoalescer`, `SeedGuard`, `ChatAttachments`, `QuestionWizard`,
  `HistoryPager`) owned by the ViewModel. Collaborators are logic holders, NOT
  second state sources —
  the screen's `UiState`/`Effect` stay in the ViewModel; intents arrive as
  calls (one-line delegates keep the screen-facing API) and updates report back
  through constructor callbacks (a state-transform sink, an effect sink, a
  scope provider). New chat features grow a collaborator from day one instead
  of landing in `ChatViewModel`. Feature tests sit next to the collaborator on
  the shared `ChatViewModelTestBase` harness.
- Sub-agent streams (045): `SessionSocket` tags transcript events with the
  emitting `agentId`; sub-agent-scoped events route to `SubagentStreamStore`
  (ui/chat, session-scoped singleton keyed by session, one `ChatTimeline` per
  sub-agent, delta coalescing via the `DeltaCoalescer` shared with ChatViewModel,
  ticket 065). The store is fed by
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
  `before_turn` on scroll-to-top (prepend, dedup by turn id). That paging
  machinery is NOT a second implementation: both this page and the main chat
  drive the shared `HistoryPager` (ui/chat, internal — ticket 074, the root
  fix for the 061/062 drift), which owns the fetch jobs and their guards
  (newest-page latest-wins, older-page in-flight/newest-page guards) while
  each screen parameterizes the fetch (messages vs transcript), the merge
  (`ChatTimeline` directly vs the store's `applyTranscriptPage`/
  `prependTranscriptPage`, each flushing its own `DeltaCoalescer` before a
  replace), and the cursor/hasMore ownership. The seeded
  task-prompt bubble lives outside the timeline (`Stream.seededPrompt`) —
  the transcript never carries the task prompt.
- Streaming: `ChatViewModel` and `SubagentStreamStore` conflate assistant/thinking
  deltas through the shared `DeltaCoalescer` (ui/chat, internal — ticket 065):
  leading edge immediately, then one emission per ~100 ms window, flushed before any
  non-delta event (`onEvent` carries the invariant) and before REST refetches
  (`flush()`), because each new text snapshot still costs one full re-parse in
  `MarkdownBody` (renderer 0.38.1 memoizes unchanged content; incremental
  `StreamingMarkdownState` needs renderer 0.42 + Kotlin 2.4). The
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
