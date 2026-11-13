#!/usr/bin/env bash
# tools/samples/pull-monitor-logs.sh — 모니터 앱 로그를 USB로 연결한 폰에서 가져온다 (SFR-017)
#   사용: tools/samples/pull-monitor-logs.sh android
#         tools/samples/pull-monitor-logs.sh ios [--device <id|name|udid>] [--new]
#   android: adb pull /sdcard/Android/data/dev.mirolab.teslable.sample/files/logs/ → samples/logs/android/<yyyy-MM-dd>/
#   ios:     xcrun devicectl device copy from (앱 데이터 컨테이너의 Documents/logs) → samples/logs/ios/<yyyy-MM-dd>/
#            --device 를 주지 않으면 연결된 실기기 iPhone 이 하나일 때 그것을 쓴다. 번들 ID 는 samples/ios/project.yml 의
#            TeslableSampleDevice 타깃 PRODUCT_BUNDLE_IDENTIFIER 에서 읽는다.
#            --new: samples/logs/ios/ 아래 어디에도 같은 이름·크기로 없는 monitor-*.jsonl 만 하나씩 가져온다.
#            새 파일은 오늘 날짜 폴더에, 폰에서 더 커진 파일은 원래 있던 자리에 덮어쓴다. 받은 파일 경로를 한 줄씩 출력한다.
#            Wi-Fi 연결(Xcode에서 "Connect via network")된 폰도 devicectl 이 보이면 같은 방법으로 된다.
#   samples/logs/ 는 git-ignored 다. 가져온 로그에 VIN이 가려져 있는지 마지막에 검사한다.
set -euo pipefail

platform="${1:-}"
[ "$#" -gt 0 ] && shift
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
date_dir="$(date +%Y-%m-%d)"

warn_if_vin() {
  # 가려지지 않은 VIN 모양(17자, 영문 대문자 1개 이상)이 있으면 알린다. (가려진 값은 * 가 들어 있어 걸리지 않는다.
  # 원시 바이트 hex 는 소문자라서 숫자만 17자 이어진 조각은 VIN 으로 보지 않는다.)
  if grep -rhoE '[A-HJ-NPR-Z0-9]{17}' "$1" | grep -q '[A-HJ-NPR-Z]'; then
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
    only_new=0
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --device) device="${2:?--device needs a value}"; shift 2 ;;
        --new) only_new=1; shift ;;
        *) echo "usage: $0 ios [--device <id>] [--new]" >&2; exit 64 ;;
      esac
    done
    xcrun --find devicectl >/dev/null 2>&1 || { echo "xcrun devicectl not found (install Xcode 15 or later)" >&2; exit 1; }
    bundle_id="$(awk '/^  TeslableSampleDevice:/{t=1} t && /PRODUCT_BUNDLE_IDENTIFIER:/{print $2; exit}' "$repo_root/samples/ios/project.yml")"
    [ -n "$bundle_id" ] || { echo "cannot read the bundle id from samples/ios/project.yml" >&2; exit 1; }
    [ -n "$device" ] || device="$(pick_ios_device)"
    dest="$repo_root/samples/logs/ios/$date_dir"
    if [ "$only_new" -eq 0 ]; then
      mkdir -p "$dest"
      xcrun devicectl device copy from --device "$device" \
        --domain-type appDataContainer --domain-identifier "$bundle_id" \
        --source Documents/logs --destination "$dest/"
      echo "pulled to $dest"
      warn_if_vin "$dest"
      exit 0
    fi
    listing="$(mktemp)"
    xcrun devicectl device info files --device "$device" \
      --domain-type appDataContainer --domain-identifier "$bundle_id" \
      --subdirectory Documents/logs --quiet --json-output "$listing" >/dev/null
    # 폰의 monitor-*.jsonl 중 로컬(samples/logs/ios/**)에 같은 이름·크기가 없는 것만 고른다.
    # 출력 한 줄 = "<폰 상대 경로>\t<받을 로컬 경로>". 이미 있던 파일이 폰에서 커졌으면 그 자리에 덮어쓴다.
    wanted="$(python3 -I - "$listing" "$repo_root/samples/logs/ios" "$dest" <<'PY'
import json, pathlib, sys
files = json.load(open(sys.argv[1]))["result"]["files"]
root, dest = pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
local = {}
if root.is_dir():
    for p in root.rglob("monitor-*.jsonl"):
        if p.name not in local or p.stat().st_size > local[p.name].stat().st_size:
            local[p.name] = p
for f in files:
    name = f.get("name", "")
    if f.get("resources", {}).get("isDirectory") or not (name.startswith("monitor-") and name.endswith(".jsonl")):
        continue
    have = local.get(name)
    if have is None or have.stat().st_size < f.get("metadata", {}).get("size", 0):
        print(f.get("relativePath", name) + "\t" + str(have or dest / name))
PY
)"
    rm -f "$listing"
    if [ -z "$wanted" ]; then
      echo "no new logs on the phone" >&2
      exit 0
    fi
    mkdir -p "$dest"
    while IFS=$'\t' read -r rel target; do
      xcrun devicectl device copy from --device "$device" \
        --domain-type appDataContainer --domain-identifier "$bundle_id" \
        --source "Documents/logs/$rel" --destination "$target" >/dev/null
      warn_if_vin "$target"
    done <<< "$wanted"
    printf '%s\n' "$wanted" | cut -f2
    ;;
  *)
    echo "usage: $0 android | ios [--device <id>] [--new]" >&2
    exit 64
    ;;
esac
