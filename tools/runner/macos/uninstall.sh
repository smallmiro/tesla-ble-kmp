#!/usr/bin/env bash
# tools/runner/macos/uninstall.sh — macOS runner 서비스를 내리고 GitHub 등록을 해제한 뒤 설치 폴더를 지운다 (ADR-0012).
set -euo pipefail

REPO="${TESLA_RUNNER_REPO:-smallmiro/tesla-ble-kmp}"
DIR="${HOME}/actions-runner-tesla"

[ -d "$DIR" ] || { echo "nothing installed in $DIR"; exit 0; }
cd "$DIR"
if [ -f ./svc.sh ]; then
  ./svc.sh stop || true
  ./svc.sh uninstall || true
fi
if [ -e .runner ]; then
  token="$(gh api -X POST "repos/${REPO}/actions/runners/remove-token" --jq .token)"
  ./config.sh remove --token "$token"
fi
cd "$HOME"
rm -rf "$DIR"
echo "uninstalled macOS runner"
