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

See `docs/conventions.md`.

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
