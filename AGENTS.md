# Kimi Android Client

## What this project is

A native Android clone of the Kimi Code web client: it drives the Kimi Code
agent from a phone with a native UX instead of the browser. The app talks to
the same backend as the web client — the HTTP + WebSocket server embedded in
the `kimi` binary.

The app only ever runs against the LATEST server version: never keep
compatibility shims for older server APIs (legacy event shapes, removed error
codes, superseded fields). When the server drops something, drop it here too —
`docs/server-api.md` always describes the current version, and the nightly
drift check keeps the two in sync.

## Machine-specific setup: `AGENTS.local.md`

This repository is public. Everything tied to one machine — where the APKs to
try are published, the e2e runner host, the Firebase project and its
credentials, the server's hostnames — lives in `AGENTS.local.md`, which is
gitignored. Read it at the start of every session when it exists; when it does
not, the steps it describes do not apply.

## Public repo hygiene

Never commit anything machine- or user-specific:

- `app/google-services.json`, `local.properties`, `AGENTS.local.md` and
  `scripts/*.local.*` are gitignored — keep them that way. Without
  `google-services.json` the build simply leaves Firebase Crashlytics out.
- Commits, docs and tickets carry no personal hostnames, IP addresses,
  credentials or home paths: use placeholders such as `agent.example.com` and
  `100.64.0.1`.

## E2E tests: run on the emulator runner

The development VPS cannot host a usable Android emulator, and the hosted
device farm has limited quota. Run the instrumented suite in
`app/src/androidTest` on a separate machine that hosts an emulator, through its
`kimi-android-uitest` runner — never try to boot an emulator on the VPS.
`scripts/run-e2e.sh` reaches the runner over SSH; it reads `KIMI_E2E_HOST` (ssh
destination) and `KIMI_E2E_RUNNER` (runner path on that host) from the
environment or from `scripts/e2e.local.env`.

```bash
scripts/run-e2e.sh                      # current working tree, uncommitted changes included
scripts/run-e2e.sh --class <FQCN>       # one class (comma-separated for several)
scripts/run-e2e.sh --ref <branch|sha>   # clean checkout of a ref
```

The last stdout line is `RESULT=pass|fail|infra-error REPORT_DIR=<path>`; exit
codes are 0 pass, 1 test/build failure, 2 infra error, 3 busy or low resources.
The full suite takes about 5 minutes. The runner serializes runs and waits for
load ≤ 8 and 10 GB free RAM, so it may sit idle while its host is busy.
Reports (JUnit XML, logcat, Gradle and emulator logs) live in `REPORT_DIR` on
the runner host; pull one with
`ssh "$KIMI_E2E_HOST" 'tar -C <REPORT_DIR> -cz .' | tar xz`.
Live-server tests need `--env-file <file>` (mode 600) with `LIVE_SERVER_URL`
and `LIVE_SERVER_TOKEN`; without it they are skipped.

## Web client source reference

The Kimi Code repo is at <https://github.com/MoonshotAI/kimi-code>. Up to
server 0.32.0 the web client source lived in `apps/kimi-web`; **from 0.33.0
that folder is gone** — the web client ships as a prebuilt bundle
(`apps/kimi-code/dist-web/`, minified). For the HTTP + WebSocket API, diff
`packages/kap-server` and `packages/agent-core-v2` between release tags —
the wire-protocol definitions in `packages/agent-core-v2` are the ground
truth (up to 0.41.0 they lived in `packages/protocol`, which 0.42.0
deleted).

## References

- `docs/server-api.md` — the wire protocol reference for the server's HTTP +
  WebSocket API; keep it accurate when touching the data layer.
- `docs/conventions.md` — code layout and architectural conventions
  (data layer, chat feature collaborators, streaming, sync).
- `docs/crashlytics.md` — querying Crashlytics issues and stack traces from
  the command line, and closing verified-fixed issues.
- `docs/server-drift.md` — the nightly server update + protocol drift check
  automation in `scripts/`.
