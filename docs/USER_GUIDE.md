# User Guide — Kimi Android Client

## Overview

This app is a native Android front-end for the Kimi Code server running on your VPS. It gives you the same chat experience as the web UI, but as a standalone app.

## Requirements

- Android 12 or later.
- Tailscale connected on your phone.
- Your Kimi server username and password.

## Installation

1. Copy `app-release.apk` to your phone.
2. Tap the APK and allow installation from this source if prompted.
3. Open the app.

## First launch

1. Enter the server URL. The default is `http://100.64.0.1`.
2. Enter your username and password (the same Caddy Basic Auth credentials used for the web UI).
3. Tap **Connect**.

The app stores your credentials securely using Android's encrypted preferences.

## Using the app

- **Sessions**: tap a session to resume it, or tap the + button to start a new one.
- **Chat**: type a message and tap send. Replies stream in as they are generated.
- **Attachments**: tap the paperclip to attach a file from your phone.
- **Settings**: open the menu to update credentials or log out.

## Troubleshooting

| Problem | Solution |
|---------|----------|
| "Cannot connect to server" | Check that Tailscale is active and the server URL is correct. |
| "Authentication failed" | Verify your username and password. |
| Replies do not stream | Check that the WebSocket path `/api/v1/ws` is reachable. |
