#!/usr/bin/env bash
# Watches the running Kimi server version. On change, launches a headless kimi
# agent (scripts/protocol-update-prompt.md) that re-verifies the wire protocol,
# rewrites docs/server-api.md, and files todo/ tickets for app breakage.
# State advances only after a successful agent run, so failures retry next tick.
# Installed as kimi-protocol-watch.service/.timer — see /home/ubuntu/administration.md.
set -euo pipefail

REPO=/home/ubuntu/kimi-android-client
STATE_DIR=/home/ubuntu/.local/state/kimi-protocol-watch
STATE_FILE=$STATE_DIR/server_version
KIMI=/home/ubuntu/.kimi-code/bin/kimi
META_URL=http://127.0.0.1:58627/api/v1/meta

mkdir -p "$STATE_DIR"
exec 9>"$STATE_DIR/lock"
flock -n 9 || exit 0

if ! version=$(curl -sf --max-time 10 "$META_URL" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["server_version"])'); then
    echo "kimi-protocol-watch: server unreachable or bad /meta payload; skipping"
    exit 0
fi
last=$(cat "$STATE_FILE" 2>/dev/null || true)

if [ "$version" = "$last" ]; then
    exit 0
fi

echo "kimi-protocol-watch: server version changed: ${last:-none} -> $version; re-verifying protocol"
if timeout 2700 "$KIMI" -p "$(cat "$REPO/scripts/protocol-update-prompt.md")"; then
    echo "$version" > "$STATE_FILE"
    echo "kimi-protocol-watch: re-verification for $version completed"
else
    echo "kimi-protocol-watch: re-verification for $version FAILED (will retry next tick)"
    exit 1
fi
