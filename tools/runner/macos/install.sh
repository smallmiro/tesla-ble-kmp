#!/usr/bin/env bash
# tools/runner/macos/install.sh — 이 Mac에 ios job용 runner를 설치하고 launchd 서비스로 등록한다 (ADR-0012).
# Docker가 아니라 호스트에서 사용자 권한으로 돈다. Xcode 26과 xcodegen이 미리 설치돼 있어야 한다.
set -euo pipefail

REPO="${TESLA_RUNNER_REPO:-smallmiro/tesla-ble-kmp}"
VERSION="2.337.0"
SHA256="5a2cd92908a93d7276a194e1de6008099f3e7946f3f8e14aa7a1a7b4a31fdec2"
DIR="${HOME}/actions-runner-tesla"
TARBALL="actions-runner-osx-arm64-${VERSION}.tar.gz"

[ "$(uname -m)" = arm64 ] || { echo "this script supports Apple Silicon only" >&2; exit 1; }
for cmd in gh xcodebuild xcodegen; do
  command -v "$cmd" >/dev/null || { echo "$cmd not found" >&2; exit 1; }
done
[ -e "$DIR/.runner" ] && { echo "already configured in $DIR (run uninstall.sh first)" >&2; exit 1; }

mkdir -p "$DIR"
cd "$DIR"
curl -fsSL -o "$TARBALL" "https://github.com/actions/runner/releases/download/v${VERSION}/${TARBALL}"
echo "${SHA256}  ${TARBALL}" | shasum -a 256 -c -
tar xzf "$TARBALL"
rm -f "$TARBALL"

token="$(gh api -X POST "repos/${REPO}/actions/runners/registration-token" --jq .token)"
./config.sh --unattended --replace \
  --url "https://github.com/${REPO}" \
  --token "$token" \
  --name "tesla-macos-$(hostname -s | tr '[:upper:]' '[:lower:]')" \
  --labels tesla-macos \
  --work _work

./svc.sh install
./svc.sh start
echo "installed macOS runner in ${DIR}"
