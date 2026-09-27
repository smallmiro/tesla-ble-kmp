#!/usr/bin/env bash
# tools/runner/uninstall.sh — supervisor LaunchAgent를 내리고 runner 컨테이너와 등록을 정리한다 (ADR-0012).
set -euo pipefail

LABEL="io.github.smallmiro.tesla-runner"
PLIST="${HOME}/Library/LaunchAgents/${LABEL}.plist"

# bootout이 supervisor에 TERM을 보내면 supervisor가 컨테이너를 멈추고 오프라인 runner를 지운다.
launchctl bootout "gui/$(id -u)/${LABEL}" 2>/dev/null || true
rm -f "$PLIST"
rm -rf "${HOME}/.tesla-runner"
echo "uninstalled ${LABEL}"
