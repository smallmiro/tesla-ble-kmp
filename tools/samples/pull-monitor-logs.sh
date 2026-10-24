#!/usr/bin/env bash
# tools/samples/pull-monitor-logs.sh — 모니터 앱 로그를 USB로 연결한 폰에서 가져온다 (SFR-017)
#   사용: tools/samples/pull-monitor-logs.sh android
#         tools/samples/pull-monitor-logs.sh ios [--device <id|name|udid>]
#   android: adb pull /sdcard/Android/data/dev.mirolab.teslable.sample/files/logs/ → samples/logs/android/<yyyy-MM-dd>/
#   ios:     xcrun devicectl device copy from (앱 데이터 컨테이너의 Documents/logs) → samples/logs/ios/<yyyy-MM-dd>/
#            --device 를 주지 않으면 연결된 실기기 iPhone 이 하나일 때 그것을 쓴다. 번들 ID 는 samples/ios/project.yml 의
#            TeslableSampleDevice 타깃 PRODUCT_BUNDLE_IDENTIFIER 에서 읽는다.
#   samples/logs/ 는 git-ignored 다. 가져온 로그에 VIN이 가려져 있는지 마지막에 검사한다.
set -euo pipefail

platform="${1:-}"
[ "$#" -gt 0 ] && shift
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
date_dir="$(date +%Y-%m-%d)"

warn_if_vin() {
  # 가려지지 않은 VIN 모양(17자)이 있으면 알린다. (가려진 값은 * 가 들어 있어 걸리지 않는다)
  if grep -rEq '[A-HJ-NPR-Z0-9]{17}' "$1"; then
    echo "WARNING: a 17-character VIN-like string is in the pulled logs; do not share them before checking" >&2
  fi
}

# 실기기 iPhone 의 식별자를 고른다. 하나가 아니면 목록을 보이고 실패한다.
pick_ios_device() {
  local json list count
  json="$(mktemp)"
  xcrun devicectl list devices --quiet --json-output "$json" >/dev/null
  list="$(python3 -I - "$json" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    devices = json.load(f)["result"]["devices"]
for d in devices:
    hw = d.get("hardwareProperties", {})
    if hw.get("reality") == "physical" and hw.get("deviceType") == "iPhone":
        print(d["identifier"] + "\t" + d.get("deviceProperties", {}).get("name", "?"))
PY
)"
  rm -f "$json"
  count="$(printf '%s' "$list" | grep -c . || true)"
  if [ "$count" -eq 1 ]; then
    printf '%s' "$list" | cut -f1
    return 0
  fi
  if [ "$count" -eq 0 ]; then
    echo "no physical iPhone found (connect the phone with USB, unlock it, trust this Mac); or pass --device <id>" >&2
  else
    echo "more than one iPhone found; pass --device <id>:" >&2
    printf '%s\n' "$list" >&2
  fi
  return 1
}

case "$platform" in
  android)
    command -v adb >/dev/null 2>&1 || { echo "adb not found (install Android platform-tools)" >&2; exit 1; }
    dest="$repo_root/samples/logs/android/$date_dir"
    mkdir -p "$dest"
    adb pull /sdcard/Android/data/dev.mirolab.teslable.sample/files/logs/ "$dest/"
    echo "pulled to $dest"
    warn_if_vin "$dest"
    ;;
  ios)
    device=""
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --device) device="${2:?--device needs a value}"; shift 2 ;;
        *) echo "usage: $0 ios [--device <id>]" >&2; exit 64 ;;
      esac
    done
    xcrun --find devicectl >/dev/null 2>&1 || { echo "xcrun devicectl not found (install Xcode 15 or later)" >&2; exit 1; }
    bundle_id="$(awk '/^  TeslableSampleDevice:/{t=1} t && /PRODUCT_BUNDLE_IDENTIFIER:/{print $2; exit}' "$repo_root/samples/ios/project.yml")"
    [ -n "$bundle_id" ] || { echo "cannot read the bundle id from samples/ios/project.yml" >&2; exit 1; }
    [ -n "$device" ] || device="$(pick_ios_device)"
    dest="$repo_root/samples/logs/ios/$date_dir"
    mkdir -p "$dest"
    xcrun devicectl device copy from --device "$device" \
      --domain-type appDataContainer --domain-identifier "$bundle_id" \
      --source Documents/logs --destination "$dest/"
    echo "pulled to $dest"
    warn_if_vin "$dest"
    ;;
  *)
    echo "usage: $0 android | ios [--device <id>]" >&2
    exit 64
    ;;
esac
