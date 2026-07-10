# Kimi Android Client

A native Android application for chatting with the Kimi Code server running on your VPS.

## What it does

- Connects over your Tailscale VPN to the Kimi Code server.
- Lists your sessions and starts new ones.
- Streams assistant replies word-by-word in real time.
- Supports file attachments.

## Quick start

1. Install the APK on your Android phone (side-load).
2. Make sure your phone is connected to Tailscale.
3. Open the app and enter the same username/password you use for the Kimi web UI.
4. The default server URL is `http://100.64.0.1`.

## Documentation

- [User Guide](docs/USER_GUIDE.md)
- [Developer Guide](docs/DEVELOPER_GUIDE.md)
- [Changelog](CHANGELOG.md)

## Build

```bash
cd /home/ubuntu/kimi-android-client
export JAVA_HOME=/home/ubuntu/tools/jdk-21.0.7+6
export ANDROID_HOME=/home/ubuntu/tools/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleRelease
```

The release APK is written to `app/build/outputs/apk/release/app-release.apk`.
