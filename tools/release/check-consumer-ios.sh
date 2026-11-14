#!/usr/bin/env bash
# tools/release/check-consumer-ios.sh — 릴리스 XCFramework를 SPM으로 링크하는 격리된 iOS 소비자 앱을 빌드·시험한다 (ADR-0017).
#   사용:
#     tools/release/check-consumer-ios.sh                    build-xcframework.sh(항상 전체 릴리스 재빌드) → 로컬 소비자 검사
#     tools/release/check-consumer-ios.sh --no-build         build-xcframework.sh를 건너뛰고 build/release/ 의 기존 산출물로 검사 (빠른 재실행용)
#     tools/release/check-consumer-ios.sh --remote <version> 게시된 릴리스 검사: build-xcframework.sh를 부르지 않고 build/release/ 를 건드리지 않는다.
#                                                            소비자를 임시 폴더로 복사해 패키지 의존을 https://github.com/smallmiro/tesla-ble-kmp
#                                                            exact: <version> 으로 바꿔 빌드한다. 그 버전이 없으면 바로 실패한다.
#   검사 (하나라도 실패하면 exit 1):
#     1) xcodegen generate
#     2) xcodebuild build -destination 'generic/platform=iOS'                      기기용 링크 (CryptoKit·Swift 런타임 심볼 누락 확인)
#     3) xcodebuild archive -configuration Release -destination 'generic/platform=iOS'
#                                                                                  앱 dSYM 에 Kotlin 컴파일 단위가 있고 앱과 UUID 가 같음
#        (dsymutil 은 Release 에서만 돈다. Kotlin 오브젝트의 디버그 맵이 빠지면 dsymutil 의 "could not open" 경고로도 실패한다)
#     4) xcodebuild test  -destination 'platform=iOS Simulator,name=iPhone 17'     ConsumerSmokeTests (앱 번들 안에서 실행, ad-hoc 서명: Keychain 사용)
#   빌드 산출물(DerivedData)은 임시 폴더에 두고 끝나면 지운다. 생성한 .xcodeproj 는 로컬 모드에서만 consumer-ios/ 에 남고 git이 무시한다.
set -euo pipefail

if [ "$(uname -s)" != "Darwin" ]; then
  echo "check-consumer-ios: FAIL: macOS only (needs xcodebuild, xcodegen)" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CONSUMER_SRC="$ROOT/tools/release/consumer-ios"
REPO_URL="https://github.com/smallmiro/tesla-ble-kmp"
SIM_DESTINATION="platform=iOS Simulator,name=iPhone 17"
LOCAL_XCF="$ROOT/build/release/Teslable.xcframework"

fail() {
  echo "check-consumer-ios: FAIL: $1" >&2
  exit 1
}

usage() {
  echo "usage: tools/release/check-consumer-ios.sh [--no-build | --remote <version>]" >&2
  exit 2
}

no_build=0
remote_version=""
while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) no_build=1; shift ;;
    --remote)
      [ $# -ge 2 ] || { echo "check-consumer-ios: --remote needs a version" >&2; usage; }
      remote_version="$2"; shift 2 ;;
    *) echo "check-consumer-ios: unknown argument: $1" >&2; usage ;;
  esac
done
if [ -n "$remote_version" ] && [ "$no_build" -eq 1 ]; then
  echo "check-consumer-ios: --no-build does not apply to --remote (remote never builds)" >&2
  usage
fi
if [ -n "$remote_version" ] && ! printf '%s' "$remote_version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.+-]+)?$'; then
  fail "--remote version must look like 1.0.0 (SemVer, no leading v): got '$remote_version'"
fi

command -v xcodegen >/dev/null 2>&1 || fail "xcodegen not found (brew install xcodegen)"
[ -f "$CONSUMER_SRC/project.yml" ] || fail "missing $CONSUMER_SRC/project.yml"

work_tmp="$(mktemp -d)"
trap 'rm -rf "$work_tmp"' EXIT

if [ -n "$remote_version" ]; then
  # 게시된 릴리스 모드: 태그가 없으면 xcodebuild 가 한참 뒤에 알기 어려운 오류를 내므로 먼저 확인한다.
  tag="v$remote_version"
  echo "check-consumer-ios: checking that $REPO_URL has tag $tag"
  tags_out="$(git ls-remote --tags "$REPO_URL" "refs/tags/$tag" 2>&1)" ||
    fail "cannot reach $REPO_URL to look for tag $tag: $tags_out"
  [ -n "$tags_out" ] || fail "tag $tag does not exist in $REPO_URL (publish the release first)"

  project_dir="$work_tmp/consumer-ios"
  mkdir -p "$project_dir"
  # LocalTeslable 은 build/release/ 상대 경로를 쓰므로 복사하지 않는다. 생성물과 숨김 폴더도 복사하지 않는다.
  ( cd "$CONSUMER_SRC" && tar -cf - --exclude LocalTeslable --exclude '*.xcodeproj' --exclude '.build' --exclude '.swiftpm' --exclude DerivedData . ) |
    ( cd "$project_dir" && tar -xf - )
  cat > "$project_dir/packages.yml" <<YML
packages:
  Teslable:
    url: $REPO_URL
    exactVersion: $remote_version
YML
else
  if [ "$no_build" -eq 1 ]; then
    echo "check-consumer-ios: --no-build: using the existing build/release/ output"
  else
    "$ROOT/tools/release/build-xcframework.sh"
  fi
  [ -d "$LOCAL_XCF" ] || fail "missing $LOCAL_XCF (run tools/release/build-xcframework.sh first, or run this script without --no-build)"
  project_dir="$CONSUMER_SRC"
fi

derived="$work_tmp/DerivedData"

echo "check-consumer-ios: xcodegen generate ($project_dir)"
( cd "$project_dir" && xcodegen generate --quiet ) || fail "xcodegen generate failed"

# 건너뛰는 경고는 이 한 가지뿐이다 (Xcode 도구 잡음, 우리 코드나 XCFramework와 무관):
#   "appintentsmetadataprocessor[<pid>:<tid>] warning: Metadata extraction skipped, no AppIntents.framework dependency found"
#   소비자 앱은 App Intents를 쓰지 않으므로 Xcode의 이 안내는 항상 나온다.
# 다른 경고는 모두 실패다. 새 잡음이 나오면 그 줄만 정확히 이름 붙여 추가하고 이유를 적는다.
NOISE_WARNING_PATTERNS='appintentsmetadataprocessor\[[0-9:]+\] warning: Metadata extraction skipped, no AppIntents\.framework dependency found'

xcb() { # xcodebuild 를 조용히 돌리고, 실패하면 로그 끝부분을 보여 준다
  local name="$1"; shift
  local log="$work_tmp/$name.log"
  if ! ( cd "$project_dir" && xcodebuild -project ConsumerApp.xcodeproj -scheme ConsumerApp -derivedDataPath "$derived" "$@" ) >"$log" 2>&1; then
    echo "--- last 60 lines of xcodebuild $name ---" >&2
    tail -n 60 "$log" >&2
    fail "xcodebuild $name failed"
  fi
  tail -n 3 "$log"
  # 경고도 발견 사항으로 본다: 초록 빌드라도 컴파일러·링커·Swift 6 경고가 있으면 실패한다. 경고는 소비자 코드에서 고친다.
  # 건너뛰는 패턴 (Xcode/시스템 잡음만): NOISE_WARNING_PATTERNS 아래 주석 참조.
  local warnings
  warnings="$(grep -a 'warning:' "$log" | grep -a -v -E "$NOISE_WARNING_PATTERNS" | sort -u || true)"
  if [ -n "$warnings" ]; then
    echo "--- warnings in xcodebuild $name ---" >&2
    printf '%s\n' "$warnings" >&2
    fail "warnings in $name"
  fi
}

echo "check-consumer-ios: device build (generic/platform=iOS)"
xcb device-build build -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO

echo "check-consumer-ios: device archive, Release (the app dSYM must carry the Kotlin debug info)"
# Debug 빌드는 dSYM 을 만들지 않아(DEBUG_INFORMATION_FORMAT=dwarf) 이 검사를 대신하지 못한다. 앱 dSYM 에 Kotlin DWARF 가 없으면
# 크래시 리포트의 Kotlin 프레임에 파일:줄과 인라인 프레임이 없다 (sdk/build.gradle.kts releaseDebugPrefixMap). Kotlin 오브젝트의
# 디버그 맵이 빠지면 dsymutil 이 "could not open '…/Teslable(Teslable.framework.o)'" 경고를 내고, xcb 가 그 경고로 실패한다.
archive="$work_tmp/ConsumerApp.xcarchive"
xcb release-archive archive -configuration Release -destination 'generic/platform=iOS' -archivePath "$archive" CODE_SIGNING_ALLOWED=NO
dsym="$archive/dSYMs/ConsumerApp.app.dSYM"
app_bin="$archive/Products/Applications/ConsumerApp.app/ConsumerApp"
[ -d "$dsym" ] && [ -f "$app_bin" ] || fail "release archive has no ConsumerApp.app.dSYM or no app binary"
dsym_cus="$(xcrun dwarfdump --debug-info --recurse-depth=0 "$dsym")" || fail "dwarfdump cannot read $dsym"
grep -q 'DW_AT_producer.*"kotlin-compiler' <<< "$dsym_cus" ||
  fail "release archive dSYM has no Kotlin compile unit (Kotlin frames in crash reports would lose file:line)"
app_uuid="$(xcrun dwarfdump --uuid "$app_bin" | cut -d' ' -f2)" || fail "dwarfdump --uuid cannot read $app_bin"
dsym_uuid="$(xcrun dwarfdump --uuid "$dsym" | cut -d' ' -f2)" || fail "dwarfdump --uuid cannot read $dsym"
[ -n "$app_uuid" ] && [ "$app_uuid" = "$dsym_uuid" ] || fail "release archive app UUID '$app_uuid' differs from its dSYM '$dsym_uuid'"
echo "check-consumer-ios: release archive dSYM has a Kotlin compile unit (UUID $app_uuid)"

echo "check-consumer-ios: simulator tests ($SIM_DESTINATION)"
# 시뮬레이터 시험만 ad-hoc 서명("-", 팀·인증서 없음)으로 돌린다. 서명이 없으면 앱에 keychain entitlement가 없어
# 키 저장소가 -34018(errSecMissingEntitlement)로 실패한다. 기기용 빌드는 서명하지 않는다.
xcb simulator-test test -destination "$SIM_DESTINATION" CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=-
grep -E "Executed [0-9]+ tests?, with 0 failures" "$work_tmp/simulator-test.log" >/dev/null ||
  fail "simulator tests did not report 'with 0 failures'"
executed="$(grep -E "Executed [0-9]+ tests?," "$work_tmp/simulator-test.log" | tail -n 1)"
# 시험이 조용히 빠지지 않게 개수를 고정한다 (ConsumerSmokeTests: async, Flow, sealed switch, 키 생성)
printf '%s' "$executed" | grep -Eq "Executed 4 tests," || fail "expected 4 tests, got: $executed"
echo "check-consumer-ios: PASS: $executed"
