#!/usr/bin/env bash
# tools/samples/pull-monitor-logs.sh — 모니터 앱 로그를 USB로 연결한 폰에서 가져온다 (SFR-017)
#   사용: tools/samples/pull-monitor-logs.sh android|ios
#   android: adb pull /sdcard/Android/data/dev.mirolab.teslable.sample/files/logs/ → samples/logs/android/<yyyy-MM-dd>/
#   ios:     아직 없음 (계획 Task 10)
#   samples/logs/ 는 git-ignored 다. 가져온 로그에 VIN이 가려져 있는지 마지막에 검사한다.
set -euo pipefail

platform="${1:-}"
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
date_dir="$(date +%Y-%m-%d)"

case "$platform" in
  android)
    command -v adb >/dev/null 2>&1 || { echo "adb not found (install Android platform-tools)" >&2; exit 1; }
    dest="$repo_root/samples/logs/android/$date_dir"
    mkdir -p "$dest"
    adb pull /sdcard/Android/data/dev.mirolab.teslable.sample/files/logs/ "$dest/"
    echo "pulled to $dest"
    # 가려지지 않은 VIN 모양(17자)이 있으면 알린다. (가려진 값은 * 가 들어 있어 걸리지 않는다)
    if grep -rEq '[A-HJ-NPR-Z0-9]{17}' "$dest"; then
      echo "WARNING: a 17-character VIN-like string is in the pulled logs; do not share them before checking" >&2
    fi
    ;;
  ios)
    echo "not implemented yet (Task 10)"
    exit 2
    ;;
  *)
    echo "usage: $0 android|ios" >&2
    exit 64
    ;;
esac
