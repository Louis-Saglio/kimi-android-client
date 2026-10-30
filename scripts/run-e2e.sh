#!/usr/bin/env bash
# Run the instrumented Android suite (app/src/androidTest) on the e2e runner host.
#
# The development VPS cannot host a usable emulator, so emulator tests never run
# there. See AGENTS.md, "E2E tests: run on the emulator runner".
#
#   scripts/run-e2e.sh                      # current working tree, uncommitted changes included
#   scripts/run-e2e.sh --class <FQCN>       # one class (comma-separated for several)
#   scripts/run-e2e.sh --ref <branch|sha>   # clean checkout of a ref, fetched from GitHub
#
# KIMI_E2E_HOST (ssh destination) and KIMI_E2E_RUNNER (runner path on that host)
# come from the environment or from scripts/e2e.local.env, which is gitignored
# because they are machine-specific.
#
# Any remaining argument is forwarded to kimi-android-uitest on the runner host.
# Last stdout line: RESULT=pass|fail|infra-error REPORT_DIR=<path>
# Exit codes: 0 pass, 1 test/build failure, 2 infra error, 3 busy or low resources.

set -euo pipefail

repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -f $repo/scripts/e2e.local.env ]]; then
  # shellcheck source=/dev/null
  source "$repo/scripts/e2e.local.env"
fi
host=${KIMI_E2E_HOST:?set KIMI_E2E_HOST (ssh destination of the e2e runner), e.g. in scripts/e2e.local.env}
runner=${KIMI_E2E_RUNNER:?set KIMI_E2E_RUNNER (path of kimi-android-uitest on the runner host)}

ref=
args=()
while (($#)); do
  case $1 in
    --ref)
      ref=${2:?--ref needs a branch, tag or commit}
      shift 2
      ;;
    *)
      args+=("$1")
      shift
      ;;
  esac
done

if [[ -n $ref ]]; then
  printf -v remote '%q ' "$runner" --ref "$ref" "${args[@]}"
  exec ssh -o BatchMode=yes "$host" "$remote"
fi

printf -v remote '%q ' "$runner" --src-tar - "${args[@]}"
tar -C "$repo" \
  --exclude=./.git \
  --exclude=./.gradle \
  --exclude=./.kotlin \
  --exclude=./.idea \
  --exclude=./build \
  --exclude=./app/build \
  --exclude=./local.properties \
  -czf - . \
  | ssh -o BatchMode=yes "$host" "$remote"
