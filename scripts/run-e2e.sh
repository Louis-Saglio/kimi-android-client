#!/usr/bin/env bash
# Run the instrumented Android suite (app/src/androidTest) on e2e-runner.
#
# The VPS cannot host a usable emulator, so emulator tests never run here.
# See AGENTS.md, "E2E tests: run on e2e-runner".
#
#   scripts/run-e2e.sh                      # current working tree, uncommitted changes included
#   scripts/run-e2e.sh --class <FQCN>       # one class (comma-separated for several)
#   scripts/run-e2e.sh --ref <branch|sha>   # clean checkout of a ref, fetched from GitHub
#
# Any remaining argument is forwarded to kimi-android-uitest on e2e-runner.
# Last stdout line: RESULT=pass|fail|infra-error REPORT_DIR=<path>
# Exit codes: 0 pass, 1 test/build failure, 2 infra error, 3 busy or low resources.

set -euo pipefail

host=${KIMI_E2E_HOST:-louis@e2e-runner}
runner=${KIMI_E2E_RUNNER:-/home/louis/.local/bin/kimi-android-uitest}
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)

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
