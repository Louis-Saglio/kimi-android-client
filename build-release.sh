#!/bin/bash
set -e

export JAVA_HOME=/home/ubuntu/tools/jdk-21.0.7+6
export ANDROID_HOME=/home/ubuntu/tools/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew :app:assembleRelease

APK_SOURCE="app/build/outputs/apk/release/app-release.apk"
APK_DEST="/home/fileserver/files/kimi-android-client.apk"

sudo cp "$APK_SOURCE" "$APK_DEST"
sudo chmod 644 "$APK_DEST"
sudo chown fileserver:fileserver "$APK_DEST"

echo "APK built at: $APK_SOURCE"
echo "APK published to: $APK_DEST"
