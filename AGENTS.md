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
  `Channel`.
- minSdk 31, targetSdk 35.

## Distribution: put the APK on the file server

After every build Louis is meant to try, the APK **must** be copied to the file
server so he can download and install it on his phone:

```bash
sudo cp app/build/outputs/apk/debug/app-debug.apk /home/fileserver/files/
sudo chown fileserver:fileserver /home/fileserver/files/app-debug.apk
```

It then appears in the listing at `https://agent.example.com` (basic auth,
user `louis` — only the file server still uses basic auth, not the Kimi API).

For slow networks there is also a minified release build (R8 + resource
shrinking, ~4.5 MB vs ~28 MB debug, signed with the debug key so it installs
over the debug build): `./gradlew :app:assembleRelease`, then copy
`app/build/outputs/apk/release/app-release.apk` the same way.

## Server drift automation

`scripts/` holds the `kimi-nightly-update` machinery (systemd timer on the
VPS, fires at 03:23): it installs any new Kimi server version (auto-update is
disabled in `~/.kimi-code/tui.toml`), restarts the server, then a headless
agent re-verifies the wire protocol, rewrites `docs/server-api.md`, files
`todo/` tickets for breakage, and commits + pushes the result.
`docs/server-api.md` is the protocol reference — keep it accurate when
touching the data layer, and keep `scripts/protocol-update-prompt.md` aligned
with the doc's structure.

## Web client source reference

The Kimi Code web client source is available at
<https://github.com/MoonshotAI/kimi-code> in the `apps/kimi-web` folder. It can
be useful for understanding the HTTP + WebSocket API or for checking what
changed between two server versions.
