# Kimi Android Client

## What this project is

A native Android clone of the Kimi Code web client. It lets Louis drive the Kimi
Code agent from his phone with a native UX instead of the browser. The app talks
to the same backend as the web client — the HTTP + WebSocket server embedded in
the `kimi` binary.

The app only ever runs against the LATEST server version: never keep
compatibility shims for older server APIs (legacy event shapes, removed error
codes, superseded fields). When the server drops something, drop it here too —
`docs/server-api.md` always describes the current version, and the nightly
drift check keeps the two in sync.

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

## Web client source reference

The Kimi Code repo is at <https://github.com/MoonshotAI/kimi-code>. Up to
server 0.32.0 the web client source lived in `apps/kimi-web`; **from 0.33.0
that folder is gone** — the web client ships as a prebuilt bundle
(`apps/kimi-code/dist-web/`, minified). For the HTTP + WebSocket API, diff
`packages/kap-server` and `packages/protocol` between release tags instead;
the wire-protocol definitions in `packages/protocol` are the ground truth.

## References

- `docs/server-api.md` — the wire protocol reference for the server's HTTP +
  WebSocket API; keep it accurate when touching the data layer.
- `docs/conventions.md` — code layout and architectural conventions
  (data layer, chat feature collaborators, streaming, sync).
- `docs/crashlytics.md` — querying Crashlytics issues and stack traces from
  the VPS, and closing verified-fixed issues.
- `docs/server-drift.md` — the nightly server update + protocol drift check
  automation in `scripts/`.
