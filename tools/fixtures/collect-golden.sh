#!/usr/bin/env bash
# tools/fixtures/collect-golden.sh — 사용자가 실차 옆에서 Go tesla-control로 TX/RX hex를 모은다(SDD §9.2, NFR-002).
# 필요: Go로 빌드한 tesla-control(PATH), 블루투스. 출력: tools/fixtures/captures/<명령>.txt (git-ignored, 커밋 금지)
# 사용: VIN=... tools/fixtures/collect-golden.sh            # 인증 없는 명령(M3): list-keys, body-controller-state
#       VIN=... KEY_FILE=... tools/fixtures/collect-golden.sh --with-key   # 인증 명령(M4/M5): session-info, lock, unlock, climate-on, "state charge"
set -euo pipefail
: "${VIN:?set VIN}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/tools/fixtures/captures"
mkdir -p "$OUT"
run() {
  local name="$1"; shift
  echo "==> $name"
  set +e
  tesla-control -ble -vin "$VIN" -debug "$@" 2>&1 | grep -E '\[debug\] (TX|RX): ' | "$ROOT/tools/fixtures/mask-vin.sh" "$VIN" > "$OUT/$name.txt"
  local st=("${PIPESTATUS[@]}")
  set -e
  # grep 종료 코드 1(일치 없음)만 허용한다. 그 외 실패는 경고(마스킹 실패 시 캡처 파일을 지운다).
  [ "${st[0]}" -eq 0 ] || echo "    WARNING: tesla-control exited ${st[0]} for $name" >&2
  { [ "${st[1]}" -eq 0 ] || [ "${st[1]}" -eq 1 ]; } || echo "    WARNING: grep failed (${st[1]}) for $name" >&2
  if [ "${st[2]}" -ne 0 ]; then
    echo "    WARNING: mask-vin.sh failed (${st[2]}) for $name; removing capture" >&2
    rm -f "$OUT/$name.txt"; return 0
  fi
  [ -f "$OUT/$name.txt" ] && wc -l < "$OUT/$name.txt" | xargs echo "    lines:"
}
run list-keys list-keys
run body-controller-state body-controller-state
if [ "${1:-}" = "--with-key" ]; then
  : "${KEY_FILE:?set KEY_FILE for authenticated commands}"
  run session-info-vcsec session-info "$KEY_FILE" vcsec   # commands.go:861-868: PUBLIC_KEY는 키 파일 경로, 인증 불필요
  run lock -key-file "$KEY_FILE" lock
  run unlock -key-file "$KEY_FILE" unlock
  run climate-on -key-file "$KEY_FILE" climate-on
  run state-charge -key-file "$KEY_FILE" state charge
fi
echo "captures in $OUT — WARNING: list-keys와 session-info RX에는 실제 공개키가 들어 있다. 원본 캡처는 절대 커밋하지 않는다. 필요한 줄만 골라 FIXTURES_DIR의 Kotlin 상수로 옮긴다(ADR-0011)."
