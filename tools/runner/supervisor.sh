#!/usr/bin/env bash
# tools/runner/supervisor.sh — 호스트에서 ephemeral runner 컨테이너 슬롯을 유지한다 (ADR-0012).
# INTERVAL초마다 슬롯을 확인하고, 비어 있으면 gh로 1시간짜리 등록 토큰을 받아 새 컨테이너를 띄운다.
# 컨테이너는 job 1개를 마치면 종료된다. supervisor가 종료 코드를 확인한 뒤 삭제하고 새 컨테이너를 띄운다.
# gh 토큰은 호스트 밖으로 나가지 않는다.
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

# 종료된 슬롯 컨테이너를 지우고 종료 코드를 출력한다. 컨테이너가 없으면 아무것도 출력하지 않는다.
reap_slot() {
  local name code
  name="$(container_name "$1")"
  code="$(docker inspect --format '{{.State.ExitCode}}' "$name" 2>/dev/null)" || return 0
  docker rm -f "$name" >/dev/null 2>&1 || true
  printf '%s' "$code"
}

# GitHub에 남은 이 호스트의 tesla-docker runner를 지운다.
# $1=offline: 오프라인만 지운다(기동 시, 강제 종료된 컨테이너의 흔적).
# $1=idle: job을 실행 중이 아닌 것만 지운다(종료 시). 컨테이너를 방금 멈췄어도 GitHub 상태 반영이 늦어 아직
# online으로 보이기 때문에 상태로 거르지 않는다. job 도중에 멈춘 runner는 지우지 않고 다음 기동 때 offline으로 정리한다.
remove_runners() {
  local filter ids
  filter="(.name | startswith(\"${NAME_PREFIX}-${HOST_TAG}-\"))"
  case "$1" in
    offline) filter="${filter} and .status == \"offline\"" ;;
    idle) filter="${filter} and (.busy | not)" ;;
  esac
  ids="$(gh api --paginate "repos/${REPO}/actions/runners" --jq ".runners[] | select(${filter}) | .id")" || return 1
  for id in $ids; do
    gh api -X DELETE "repos/${REPO}/actions/runners/${id}" >/dev/null && log "removed runner id=${id}"
  done
}

start_slot() {
  local slot="$1" token
  token="$(gh api -X POST "repos/${REPO}/actions/runners/registration-token" --jq .token)" || return 1
  # 토큰을 명령줄 인자로 넘기지 않는다(ps에 보임). 환경변수 이름만 넘겨 docker가 값을 읽게 한다.
  RUNNER_TOKEN="$token" \
  GITHUB_REPO="$REPO" \
  RUNNER_NAME="${NAME_PREFIX}-${HOST_TAG}-${slot}-$(date +%s)" \
    docker run -d \
      --name "$(container_name "$slot")" \
      --platform linux/amd64 \
      --cpus "$CPUS" --memory "$MEMORY" \
      -e RUNNER_TOKEN -e GITHUB_REPO -e RUNNER_NAME \
      "$IMAGE" >/dev/null || return 1
  log "started slot ${slot}"
}

shutdown() {
  log "stopping"
  # 슬롯을 병렬로 멈춘다. launchd ExitTimeOut(install.sh) 안에 끝나야 runner 정리까지 실행된다.
  for slot in $(seq 1 "$SLOTS"); do
    docker stop -t 30 "$(container_name "$slot")" >/dev/null 2>&1 &
  done
  wait || true
  for slot in $(seq 1 "$SLOTS"); do reap_slot "$slot" >/dev/null; done
  remove_runners idle || true
  exit 0
}
trap shutdown TERM INT

log "supervisor up: repo=${REPO} image=${IMAGE} slots=${SLOTS}"
remove_runners offline || log "could not clean offline runners (gh or network)"

while true; do
  delay="$INTERVAL"
  for slot in $(seq 1 "$SLOTS"); do
    if ! docker info >/dev/null 2>&1; then
      log "docker is not reachable (is colima running?)"; delay="$BACKOFF"; break
    fi
    if ! slot_running "$slot"; then
      code="$(reap_slot "$slot")"
      # 0이 아닌 종료 코드는 등록이나 runner 실패다. 바로 다시 띄우면 토큰 발급을 계속 반복하므로 쉬었다가 띄운다.
      if [ -n "$code" ] && [ "$code" != 0 ]; then
        log "slot ${slot} exited with code ${code}; retrying after ${BACKOFF}s"; delay="$BACKOFF"; continue
      fi
      start_slot "$slot" || { log "failed to start slot ${slot}"; delay="$BACKOFF"; }
    fi
  done
  # 백그라운드 sleep + wait: TERM을 받으면 바로 trap이 실행된다.
  sleep "$delay" & wait $! || true
done
