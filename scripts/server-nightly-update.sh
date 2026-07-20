#!/usr/bin/env bash
# Nightly Kimi server update job. Checks whether a new kimi-code version is
# available (auto-update is disabled in tui.toml), installs it via the vendor
# install script, and restarts kimi-web. Whenever the running server version
# differs from the last verified one — whether the change came from this job
# or not — launches a headless kimi agent (scripts/protocol-update-prompt.md)
# that re-verifies the wire protocol, rewrites docs/server-api.md, files
# todo/ tickets for app breakage, and commits + pushes the result.
# State advances only after a successful agent run, so failures retry the
# next night. Installed as kimi-nightly-update.service/.timer —
# see /home/ubuntu/administration.md.
set -euo pipefail

REPO=/home/ubuntu/kimi-android-client
STATE_DIR=/home/ubuntu/.local/state/kimi-nightly-update
STATE_FILE=$STATE_DIR/server_version
KIMI=/home/ubuntu/.kimi-code/bin/kimi
META_URL=http://127.0.0.1:58627/api/v1/meta
LATEST_URL=https://code.kimi.com/kimi-code/latest

mkdir -p "$STATE_DIR"
exec 9>"$STATE_DIR/lock"
flock -n 9 || exit 0

server_version() {
    curl -sf --max-time 10 "$META_URL" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["server_version"])'
}

installed=$("$KIMI" -V)
if ! latest=$(curl -fsSL --max-time 30 "$LATEST_URL" | tr -d '[:space:]'); then
    echo "kimi-nightly-update: cannot resolve latest version (network?); skipping"
    exit 0
fi

if [ "$installed" != "$latest" ]; then
    echo "kimi-nightly-update: new version available: $installed -> $latest; installing"
    curl -fsSL https://code.kimi.com/kimi-code/install.sh | KIMI_NO_MODIFY_PATH=1 bash
    echo "kimi-nightly-update: installed $("$KIMI" -V); restarting kimi-web"
    sudo -n systemctl restart kimi-web.service
    for _ in $(seq 1 30); do
        server_version >/dev/null 2>&1 && break
        sleep 2
    done
else
    echo "kimi-nightly-update: already on latest version ($installed)"
fi

if ! version=$(server_version); then
    echo "kimi-nightly-update: server unreachable or bad /meta payload; aborting"
    exit 1
fi
last=$(cat "$STATE_FILE" 2>/dev/null || true)

if [ "$version" = "$last" ]; then
    echo "kimi-nightly-update: server $version already verified; nothing to do"
    exit 0
fi

echo "kimi-nightly-update: server version changed: ${last:-none} -> $version; re-verifying protocol"
if (cd "$REPO" && timeout 2700 "$KIMI" -p "$(cat "$REPO/scripts/protocol-update-prompt.md")"); then
    echo "$version" > "$STATE_FILE"
    echo "kimi-nightly-update: re-verification for $version completed"
else
    echo "kimi-nightly-update: re-verification for $version FAILED (will retry tomorrow)"
    exit 1
fi
