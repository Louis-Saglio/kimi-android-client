# Kimi Android Client

## What this project is

A native Android clone of the Kimi Code web client. It lets Louis drive the Kimi
Code agent from his phone with a native UX instead of the browser. The app talks
to the same backend as the web client — the HTTP + WebSocket server embedded in
the `kimi` binary — reached via Caddy at `http://100.64.0.1:80` over
Tailscale with HTTP basic auth.

## Tech stack

- Kotlin + Jetpack Compose (Material 3), single `app` module.
- Hilt + KSP for DI, coroutines/Flow, `kotlinx.serialization`, OkHttp, DataStore.
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
same `louis` credentials as the web UI).
