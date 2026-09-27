#!/usr/bin/env bash
# tools/runner/entrypoint.sh — 컨테이너 안에서 runner를 ephemeral로 등록하고 job 1개를 실행한다 (ADR-0012).
# 호스트 supervisor가 1시간짜리 등록 토큰만 넘긴다. gh 토큰은 이 컨테이너에 들어오지 않는다.
set -euo pipefail

: "${GITHUB_REPO:?GITHUB_REPO is required (owner/name)}"
: "${RUNNER_NAME:?RUNNER_NAME is required}"
: "${RUNNER_TOKEN:?RUNNER_TOKEN is required}"
RUNNER_LABELS="${RUNNER_LABELS:-tesla-docker}"

cd /home/runner
./config.sh --unattended --ephemeral --replace --disableupdate \
  --url "https://github.com/${GITHUB_REPO}" \
  --token "$RUNNER_TOKEN" \
  --name "$RUNNER_NAME" \
  --labels "$RUNNER_LABELS" \
  --work _work

# 등록이 끝나면 토큰은 필요 없다. job 단계가 환경변수로 읽지 못하게 지운다.
unset RUNNER_TOKEN
exec ./run.sh
