#!/usr/bin/env bash
# tools/release/build-xcframework.sh — 배포용 정적 Teslable.xcframework를 빌드하고 검사한 뒤 zip과 SPM 체크섬을 만든다 (ADR-0017).
#   사용: tools/release/build-xcframework.sh   (인자 없음. 항상 Gradle 빌드부터 다시 한다. 언제 부를지는 호출하는 쪽이 정한다)
#   빌드: ./gradlew :sdk:assembleTeslableReleaseXCFramework --no-build-cache --rerun-tasks
#         → sdk/build/XCFrameworks/release/Teslable.xcframework
#   검사: 하나라도 어기면 위반 목록을 출력하고 exit 1. 이때 아래 산출물은 하나도 남기지 않는다.
#     - Info.plist AvailableLibraries 가 정확히 ios-arm64, ios-arm64-simulator 두 개이고 아키텍처는 arm64 만
#       (시뮬레이터 슬라이스에 x86_64 없음: Apple Silicon Mac 전용)
#     - 각 슬라이스 Teslable.framework/Info.plist MinimumOSVersion = 16.0 (NFR-009)
#     - 각 슬라이스 Modules/Teslable.swiftmodule/*.swiftinterface 존재 (Swift library evolution)
#     - 각 슬라이스 바이너리가 정적 라이브러리(ar 아카이브)이고 clang 모듈 breadcrumb(*.pcm)가 0개 (SKIE produceDistributableFramework())
#     - zip 안의 항목이 모두 Teslable.xcframework/ 아래이고 ._* AppleDouble·__MACOSX 항목이 없음
#     - zip 안 어느 파일에도 빌드한 Mac의 경로(/Users/, $HOME, 저장소 루트)가 없음 (사용자 이름·폴더 구조 노출 방지)
#   산출물 (tools/release 의 다른 스크립트가 쓴다):
#     build/release/Teslable.xcframework/             압축 해제본 (로컬 SPM binaryTarget path, *.swiftsourceinfo 제외)
#     build/release/Teslable.xcframework.zip          ditto -c -k --keepParent (확장 속성 제외), 압축 루트에 Teslable.xcframework/
#     build/release/Teslable.xcframework.zip.sha256   swift package compute-checksum 값 한 줄
#   다시 돌리면 zip과 체크섬이 바뀐다. 체크섬을 낸 zip을 그대로 올려야 한다.
set -euo pipefail

if [ "$(uname -s)" != "Darwin" ]; then
  echo "build-xcframework: FAIL: macOS only (needs xcodebuild, plutil, ditto, swift)" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

GRADLE_TASK=":sdk:assembleTeslableReleaseXCFramework"
SRC_XCF="sdk/build/XCFrameworks/release/Teslable.xcframework"
OUT_DIR="build/release"
OUT_XCF="$OUT_DIR/Teslable.xcframework"
OUT_ZIP="$OUT_DIR/Teslable.xcframework.zip"
OUT_SHA="$OUT_ZIP.sha256"
SLICES="ios-arm64 ios-arm64-simulator" # 정렬한 값. 이 둘만 허용한다
MIN_OS="16.0"

work_tmp="" # 임시 폴더: zip을 풀어 검사하는 곳, swift package 가 현재 폴더에 만드는 .build/ 대신 쓰는 곳

remove_outputs() { rm -rf "$OUT_XCF" "$OUT_ZIP" "$OUT_SHA" "$OUT_SHA.tmp"; }
# 실패하면 반쯤 만든 산출물을 지운다. 지난 실행의 zip이나 체크섬을 이번 결과로 착각하지 않게 한다.
on_exit() {
  local status=$?
  [ -z "$work_tmp" ] || rm -rf "$work_tmp"
  [ "$status" -eq 0 ] || remove_outputs
}
trap on_exit EXIT

fail() {
  echo "build-xcframework: FAIL: $1" >&2
  exit 1
}

violations=()
violate() { violations+=("$1"); }
exit_if_violations() {
  [ "${#violations[@]}" -eq 0 ] && return 0
  echo "build-xcframework: FAIL: ${#violations[@]} violation(s) in $1" >&2
  local v
  for v in "${violations[@]}"; do echo "  - $v" >&2; done
  exit 1
}

has_match() { [ -e "$1" ]; } # 글롭이 하나라도 맞으면 참

remove_outputs
work_tmp="$(mktemp -d)"

# --no-build-cache --rerun-tasks: 빌드 캐시나 다른 폴더(다른 worktree)에서 컴파일한 klib에는 그 폴더의 절대 경로가 들어 있어
# 디버그 경로 매핑(-Xdebug-prefix-map=<저장소 루트>=.)이 닿지 않는다. 배포 빌드는 이 저장소에서 처음부터 다시 컴파일한다.
echo "==> ./gradlew $GRADLE_TASK --no-build-cache --rerun-tasks"
./gradlew "$GRADLE_TASK" --no-build-cache --rerun-tasks --console=plain ||
  fail "./gradlew $GRADLE_TASK (see the Gradle output above)"

echo "==> check $SRC_XCF"
plist="$SRC_XCF/Info.plist"
if [ ! -f "$plist" ]; then
  violate "$plist is missing"
else
  count="$(plutil -extract AvailableLibraries raw -o - "$plist" 2>/dev/null)" || count=0
  case "$count" in '' | *[!0-9]*) count=0 ;; esac
  ids=""
  i=0
  while [ "$i" -lt "$count" ]; do
    key="AvailableLibraries.$i"
    id="$(plutil -extract "$key.LibraryIdentifier" raw -o - "$plist" 2>/dev/null)" || id="(no LibraryIdentifier)"
    ids="$ids$id"$'\n'
    narch="$(plutil -extract "$key.SupportedArchitectures" raw -o - "$plist" 2>/dev/null)" || narch=0
    arch0="$(plutil -extract "$key.SupportedArchitectures.0" raw -o - "$plist" 2>/dev/null)" || arch0=""
    if [ "$narch" != 1 ] || [ "$arch0" != arm64 ]; then
      archs="$(plutil -extract "$key.SupportedArchitectures" json -o - "$plist" 2>/dev/null)" || archs="(none)"
      violate "AvailableLibraries: $id supports $archs, expected arm64 only"
    fi
    i=$((i + 1))
  done
  found="$(printf '%s' "$ids" | LC_ALL=C sort | tr '\n' ' ' | sed 's/ *$//')"
  if [ "$found" != "$SLICES" ]; then
    violate "AvailableLibraries: [$found], expected exactly [$SLICES]"
  fi
fi

for id in $SLICES; do
  fw="$SRC_XCF/$id/Teslable.framework"
  if [ ! -d "$fw" ]; then
    violate "$id: Teslable.framework is missing"
    continue
  fi
  min="$(plutil -extract MinimumOSVersion raw -o - "$fw/Info.plist" 2>/dev/null)" || min="(missing)"
  if [ "$min" != "$MIN_OS" ]; then
    violate "$id: Teslable.framework/Info.plist MinimumOSVersion is $min, expected $MIN_OS"
  fi
  if ! has_match "$fw"/Modules/Teslable.swiftmodule/*.swiftinterface; then
    violate "$id: no Modules/Teslable.swiftmodule/*.swiftinterface (Swift library evolution is off)"
  fi
  # ar 아카이브 매직 "!<arch>" = 정적 라이브러리. 동적 프레임워크는 소비자 앱이 임베드·서명해야 해서 배포 형태가 바뀐다.
  magic="$(head -c 7 "$fw/Teslable" 2>/dev/null)" || magic=""
  if [ "$magic" != '!<arch>' ]; then
    violate "$id: Teslable.framework/Teslable is not a static library (ar archive)"
  fi
  # *.pcm = swiftc 가 정적 프레임워크 디버그 정보에 남기는 clang 모듈 캐시 breadcrumb. SKIE produceDistributableFramework()
  # (noClangModuleBreadcrumbsInStaticFrameworks)가 켜져 있으면 0개다. library evolution 은 XCFramework에서 SKIE가 스스로 켜므로
  # .swiftinterface 검사만으로는 이 설정이 빠진 것을 잡지 못한다.
  if [ -f "$fw/Teslable" ]; then
    pcm="$(LC_ALL=C tr -c '[:print:]' '\n' < "$fw/Teslable" | grep -F '.pcm' || true)"
    if [ -n "$pcm" ]; then
      npcm="$(grep -c . <<< "$pcm")"
      violate "$id: Teslable.framework/Teslable has $npcm clang module breadcrumb(s) (*.pcm): SKIE produceDistributableFramework() is off"
    fi
  fi
done
exit_if_violations "$SRC_XCF"

echo "==> package $OUT_DIR"
mkdir -p "$OUT_DIR"
ditto "$SRC_XCF" "$OUT_XCF"
# .swiftsourceinfo 는 SKIE Swift 소스의 절대 경로를 담은 진단용 파일이다. 소비자 빌드에 필요 없으므로 배포하지 않는다.
find "$OUT_XCF" -name '*.swiftsourceinfo' -type f -delete
# --norsrc --noextattr --noacl --noqtn: 확장 속성(com.apple.provenance 등)이 ._* AppleDouble 항목으로 zip에 들어가지 않게 한다.
ditto -c -k --norsrc --noextattr --noacl --noqtn --keepParent "$OUT_XCF" "$OUT_ZIP"

entries="$(zipinfo -1 "$OUT_ZIP")" || fail "zipinfo $OUT_ZIP"
outside="$(grep -v -E '^Teslable\.xcframework/' <<< "$entries" || true)"
junk="$(grep -E '(^|/)(\._|__MACOSX)' <<< "$entries" || true)"
[ -z "$outside" ] || violate "zip entries outside Teslable.xcframework/: $(head -3 <<< "$outside" | tr '\n' ' ')"
[ -z "$junk" ] || violate "zip has AppleDouble or __MACOSX entries: $(head -3 <<< "$junk" | tr '\n' ' ')"
grep -q -x -F 'Teslable.xcframework/Info.plist' <<< "$entries" || violate "zip has no Teslable.xcframework/Info.plist"

# 빌드한 Mac의 절대 경로(홈 폴더와 그 안의 사용자 이름, 저장소 위치)가 배포 파일 어디에도 없어야 한다.
# zip을 풀어 모든 파일의 바이트를 본다(바이너리의 디버그 정보 포함).
ditto -x -k "$OUT_ZIP" "$work_tmp/unzipped"
leak_args=(-e "/Users/" -e "$ROOT")
case "$HOME" in "" | "/") ;; *) leak_args+=(-e "$HOME") ;; esac
tilde="~"
leaky_files="$(grep -r -l -a -F "${leak_args[@]}" "$work_tmp/unzipped" || true)"
while IFS= read -r f; do
  [ -n "$f" ] || continue
  # strings 는 ar 아카이브를 멤버 단위로 읽어 멤버 밖 바이트를 건너뛴다. 출력 가능한 바이트 묶음을 직접 잘라 grep 과 같은 범위를 본다.
  hits="$(LC_ALL=C tr -c '[:print:]' '\n' < "$f" | grep -F "${leak_args[@]}" || true)"
  n="$(grep -c . <<< "$hits" || true)"
  sample="$(head -2 <<< "$hits" | tr '\n' ' ')"
  case "$HOME" in "" | "/") ;; *) sample="${sample//"$HOME"/$tilde}" ;; esac
  violate "${f#"$work_tmp/unzipped"/}: $n build-machine path string(s), e.g. $sample"
done <<< "$leaky_files"
exit_if_violations "$OUT_ZIP"

checksum="$(swift package --scratch-path "$work_tmp/swiftpm" compute-checksum "$OUT_ZIP")" ||
  fail "swift package compute-checksum $OUT_ZIP"
case "$checksum" in
  *[!0-9a-f]* | '') fail "unexpected checksum from swift package compute-checksum: '$checksum'" ;;
esac
[ "${#checksum}" -eq 64 ] || fail "unexpected checksum length ${#checksum}: '$checksum'"
printf '%s\n' "$checksum" > "$OUT_SHA.tmp"
mv "$OUT_SHA.tmp" "$OUT_SHA"

echo "build-xcframework: PASS"
echo "  $OUT_XCF/"
echo "  $OUT_ZIP ($(du -h "$OUT_ZIP" | cut -f1 | tr -d ' '))"
echo "  $OUT_SHA $checksum"
