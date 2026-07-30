You are repairing the Kimi Code web server on this VPS. An automatic nightly
update just installed a new kimi-code version and the `kimi-web.service`
systemd service no longer passes its health check. Your job: make the NEW
version work. Do not downgrade anything.

Context:

- Binary: `/home/ubuntu/.kimi-code/bin/kimi` (check its version with `-V`).
- Service: `kimi-web.service`, unit file `/etc/systemd/system/kimi-web.service`,
  runs as user `ubuntu`. You have passwordless sudo (`sudo -n`).
- Expected behavior: an HTTP server on `127.0.0.1:58627`, reverse-proxied by
  Caddy (Caddy handles TLS — do NOT touch Caddy).
- The service must keep serving on port 58627, keep bearer-token auth
  enabled (do NOT add `--dangerous-bypass-auth`), and keep allowing hosts
  `100.64.0.1` and `agent.example.com` (Caddy vhosts; the server
  enforces a Host allowlist).
- Health check (both must hold):
  - `systemctl is-active --quiet kimi-web.service`
  - `curl -sf -H "Authorization: Bearer $(cat /home/ubuntu/.kimi-code/server.token)" http://127.0.0.1:58627/api/v1/meta`
    returns JSON containing `data.server_version`

Process hygiene (critical): you run headless as `kimi -p "<this prompt>"`, so your runner's command line contains this entire prompt text and its process name is `kimi`. Restart services via `systemctl` only. Never kill processes by name or command-line pattern (`pkill`, `pkill -f`, `killall`) — `pkill -x kimi`, `pkill -f kimi`, or any substring match can kill your own runner. If a stray process must die, identify its PID from `ps` output and `kill` that PID only.

Procedure:

1. Diagnose first. Look at `sudo journalctl -u kimi-web -n 150 --no-pager`.
   The most common causes are CLI changes (renamed/removed subcommands or
   flags in the unit's `ExecStart`) and config format changes
   (`/home/ubuntu/.kimi-code/config.toml`, `tui.toml`). Check the new CLI
   surface with `--help` on the binary and its subcommands; consult
   https://code.kimi.com or the changelog if needed.
2. Apply the minimal fix: update `ExecStart` in the unit file (then
   `sudo systemctl daemon-reload`) or migrate the config. Keep all other
   unit settings (`User`, `RestrictNamespaces`, `Restart`, ...) intact.
3. `sudo systemctl restart kimi-web.service`, then verify the health check
   passes, waits 15 seconds, and still passes — a crash-looping service can
   answer briefly between restarts. Repeat diagnosis if it still fails.
4. Only modify the kimi-web unit file and files under `/home/ubuntu/.kimi-code`.
   Never replace or downgrade the binary, never touch Caddy or other services.
   If the new version has a genuine bug or is fundamentally incompatible with
   this system, do not hack around it: report it and exit 1 so the caller can
   roll back.

Exit 0 only if the health check passes at the end. Otherwise exit 1 and print
a short summary of the root cause (if found) and everything you tried.
