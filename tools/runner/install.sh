#!/usr/bin/env bash
# tools/runner/install.sh — runner 이미지를 빌드하고 supervisor를 사용자 LaunchAgent로 등록한다 (ADR-0012).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LABEL="io.github.smallmiro.tesla-runner"
APP_DIR="${HOME}/.tesla-runner"
PLIST="${HOME}/Library/LaunchAgents/${LABEL}.plist"
LOG="${HOME}/Library/Logs/tesla-runner.log"
DOMAIN="gui/$(id -u)"

for cmd in docker gh; do
  command -v "$cmd" >/dev/null || { echo "$cmd not found" >&2; exit 1; }
done
gh auth status >/dev/null 2>&1 || { echo "gh is not logged in (run: gh auth login)" >&2; exit 1; }

docker build --platform linux/amd64 -t tesla-runner:latest "$HERE"

# 저장소 경로(워크트리 등)가 바뀌어도 서비스가 깨지지 않게 스크립트를 복사해 둔다.
mkdir -p "$APP_DIR" "$(dirname "$PLIST")" "$(dirname "$LOG")"
install -m 755 "$HERE/supervisor.sh" "$APP_DIR/supervisor.sh"

cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key>
  <array><string>/bin/bash</string><string>${APP_DIR}/supervisor.sh</string></array>
  <key>EnvironmentVariables</key>
  <dict><key>PATH</key><string>$(dirname "$(command -v docker)"):$(dirname "$(command -v gh)"):/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string></dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ExitTimeOut</key><integer>90</integer>
  <key>StandardOutPath</key><string>${LOG}</string>
  <key>StandardErrorPath</key><string>${LOG}</string>
</dict>
</plist>
PLIST

# 재설치: bootout은 비동기라, 이전 supervisor가 정리를 마치고 내려갈 때까지(최대 ExitTimeOut) 기다린 뒤 등록한다.
# 기다리지 않으면 bootstrap이 "Input/output error"로 실패한다.
launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
for _ in $(seq 1 100); do
  launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1 || break
  sleep 1
done
launchctl bootstrap "$DOMAIN" "$PLIST"
echo "installed ${LABEL}; log: ${LOG}"
