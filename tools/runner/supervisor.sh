#!/usr/bin/env bash
# tools/runner/supervisor.sh — 호스트에서 ephemeral runner 컨테이너 슬롯을 유지한다 (ADR-0012).
# INTERVAL초마다 슬롯을 확인하고, 비어 있으면 gh로 1시간짜리 등록 토큰을 받아 새 컨테이너를 띄운다.
# 컨테이너는 job 1개를 마치면 종료되고 --rm으로 삭제된다. gh 토큰은 호스트 밖으로 나가지 않는다.
set -euo pipefail

REPO="${TESLA_RUNNER_REPO:-smallmiro/tesla-ble-kmp}"
IMAGE="${TESLA_RUNNER_IMAGE:-tesla-runner:latest}"
SLOTS="${TESLA_RUNNER_SLOTS:-2}"
CPUS="${TESLA_RUNNER_CPUS:-3}"
MEMORY="${TESLA_RUNNER_MEMORY:-5.5g}"
INTERVAL="${TESLA_RUNNER_INTERVAL:-10}"
BACKOFF="${TESLA_RUNNER_BACKOFF:-60}"
NAME_PREFIX="tesla-docker"
HOST_TAG="$(hostname -s | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9\n' '-')"

log() { printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*"; }

container_name() { printf 'tesla-runner-%s' "$1"; }

slot_running() {
  [ -n "$(docker ps -q --filter "name=^$(container_name "$1")\$")" ]
}

# GitHub에 남은 오프라인 tesla-docker runner(강제 종료된 컨테이너의 흔적)를 지운다.
remove_offline_runners() {
  local ids
  ids="$(gh api --paginate "repos/${REPO}/actions/runners" \
    --jq ".runners[] | select(.status == \"offline\" and (.name | startswith(\"${NAME_PREFIX}-\"))) | .id")" || return 1
  for id in $ids; do
    gh api -X DELETE "repos/${REPO}/actions/runners/${id}" >/dev/null && log "removed offline runner id=${id}"
  done
}

start_slot() {
  local slot="$1" token
  token="$(gh api -X POST "repos/${REPO}/actions/runners/registration-token" --jq .token)" || return 1
  # 토큰을 명령줄 인자로 넘기지 않는다(ps에 보임). 환경변수 이름만 넘겨 docker가 값을 읽게 한다.
  RUNNER_TOKEN="$token" \
  GITHUB_REPO="$REPO" \
  RUNNER_NAME="${NAME_PREFIX}-${HOST_TAG}-${slot}-$(date +%s)" \
    docker run -d --rm \
      --name "$(container_name "$slot")" \
      --platform linux/amd64 \
      --cpus "$CPUS" --memory "$MEMORY" \
      -e RUNNER_TOKEN -e GITHUB_REPO -e RUNNER_NAME \
      "$IMAGE" >/dev/null || return 1
  log "started slot ${slot}"
}

shutdown() {
  log "stopping"
  for slot in $(seq 1 "$SLOTS"); do
    docker stop -t 30 "$(container_name "$slot")" >/dev/null 2>&1 || true
  done
  remove_offline_runners || true
  exit 0
}
trap shutdown TERM INT

log "supervisor up: repo=${REPO} image=${IMAGE} slots=${SLOTS}"
remove_offline_runners || log "could not clean offline runners (gh or network)"

while true; do
  delay="$INTERVAL"
  for slot in $(seq 1 "$SLOTS"); do
    if ! docker info >/dev/null 2>&1; then
      log "docker is not reachable (is colima running?)"; delay="$BACKOFF"; break
    fi
    if ! slot_running "$slot"; then
      start_slot "$slot" || { log "failed to start slot ${slot}"; delay="$BACKOFF"; }
    fi
  done
  # 백그라운드 sleep + wait: TERM을 받으면 바로 trap이 실행된다.
  sleep "$delay" & wait $! || true
done
