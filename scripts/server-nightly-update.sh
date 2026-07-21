#!/usr/bin/env bash
# Nightly Kimi server update job. Checks whether a new kimi-code version is
# available (auto-update is disabled in tui.toml), installs it via the vendor
# install script, and restarts kimi-web. After the restart it health-checks
# the web server (HTTP /meta + service not crash-looping). If the new version
# broke the server, it restarts once, then launches a headless kimi agent
# (scripts/server-repair-prompt.md) that investigates the failure — usually a
# CLI or config format change — and tries to make the new version work by
# fixing the unit file or config. Only if the repair fails (genuine bug or
# incompatibility) does it roll back to the previous binary
# (~/.kimi-code/bin/kimi.bak, kept by the installer) and marks the broken
# version so it is not retried every night. Whenever the running server
# version differs from the last verified one — whether the change came from
# this job or not — launches a headless kimi agent
# (scripts/protocol-update-prompt.md) that re-verifies the wire protocol,
# rewrites docs/server-api.md, files todo/ tickets for app breakage, and
# commits + pushes the result. State advances only after a successful agent
# run, so failures retry the next night. Installed as
# kimi-nightly-update.service/.timer — see /home/ubuntu/administration.md.
set -euo pipefail

REPO=/home/ubuntu/kimi-android-client
STATE_DIR=/home/ubuntu/.local/state/kimi-nightly-update
STATE_FILE=$STATE_DIR/server_version
BROKEN_FILE=$STATE_DIR/broken_version
KIMI=/home/ubuntu/.kimi-code/bin/kimi
META_URL=http://127.0.0.1:58627/api/v1/meta
LATEST_URL=https://code.kimi.com/kimi-code/latest

mkdir -p "$STATE_DIR"
exec 9>"$STATE_DIR/lock"
flock -n 9 || exit 0

server_version() {
    curl -sf --max-time 10 "$META_URL" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["server_version"])'
}

health_ok() {
    systemctl is-active --quiet kimi-web.service && server_version >/dev/null 2>&1
}

# Poll for up to $1 x 3s; the second health_ok after a 10s pause catches
# crash-looping services that only answer briefly between restarts.
wait_healthy() {
    for _ in $(seq 1 "$1"); do
        if health_ok && sleep 10 && health_ok; then
            return 0
        fi
        sleep 3
    done
    return 1
}

rollback() {
    if [ ! -f "$KIMI.bak" ]; then
        echo "kimi-nightly-update: no previous binary at $KIMI.bak; cannot roll back"
        return 1
    fi
    cp -f "$KIMI" "$STATE_DIR/kimi.failed-$latest"
    cp -f "$KIMI.bak" "$KIMI"
    echo "kimi-nightly-update: rolled back to $("$KIMI" -V) (broken binary kept at $STATE_DIR/kimi.failed-$latest)"
    sudo -n systemctl restart kimi-web.service
}

installed=$("$KIMI" -V)
if ! latest=$(curl -fsSL --max-time 30 "$LATEST_URL" | tr -d '[:space:]'); then
    echo "kimi-nightly-update: cannot resolve latest version (network?); skipping"
    exit 0
fi

if [ "$installed" != "$latest" ]; then
    if [ "$(cat "$BROKEN_FILE" 2>/dev/null)" = "$latest" ]; then
        echo "kimi-nightly-update: $latest broke kimi-web last time; skipping (remove $BROKEN_FILE to retry)"
        exit 0
    fi
    echo "kimi-nightly-update: new version available: $installed -> $latest; installing"
    curl -fsSL https://code.kimi.com/kimi-code/install.sh | KIMI_NO_MODIFY_PATH=1 bash
    echo "kimi-nightly-update: installed $("$KIMI" -V); restarting kimi-web"
    sudo -n systemctl restart kimi-web.service
    if wait_healthy 40; then
        rm -f "$BROKEN_FILE"
    else
        echo "kimi-nightly-update: kimi-web unhealthy after update; restarting once more"
        sudo -n systemctl restart kimi-web.service
        if wait_healthy 30; then
            rm -f "$BROKEN_FILE"
        else
            echo "kimi-nightly-update: kimi-web still unhealthy; launching repair agent to make $latest work"
            if (cd "$REPO" && timeout 900 "$KIMI" -p "$(cat "$REPO/scripts/server-repair-prompt.md")") && wait_healthy 20; then
                echo "kimi-nightly-update: repair agent fixed kimi-web on $latest"
                rm -f "$BROKEN_FILE"
            else
                echo "kimi-nightly-update: repair failed (bug or incompatibility?); rolling back to previous version"
                rollback || true
                if wait_healthy 40; then
                    echo "$latest" > "$BROKEN_FILE"
                    echo "kimi-nightly-update: rollback OK, $latest marked broken; web UI is up on $("$KIMI" -V)"
                else
                    echo "kimi-nightly-update: rollback did NOT restore kimi-web; manual intervention needed"
                fi
                exit 1
            fi
        fi
    fi
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
