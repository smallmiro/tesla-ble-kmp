# shellcheck shell=bash
# tools/release/release-lib.sh — prepare.sh 와 publish.sh 가 함께 쓰는 값과 함수 (ADR-0017). 실행하지 않고 source 한다.
#   부르는 쪽이 먼저 정한다: SCRIPT_NAME (메시지 머리말), WORK_TMP (임시 폴더, 끝나면 지운다). 현재 폴더는 저장소 루트.
#   check-consumer-ios.sh 도 source 하지만 REPO_URL 과 check_version 만 쓴다 (SCRIPT_NAME 만 정한다).
#   bash 3.2 (macOS 기본) 에서 돈다: 연관 배열, mapfile, ${var,,} 를 쓰지 않고, 빈 배열을 펼치지 않는다.

# shellcheck disable=SC2034 # 아래 값 중 일부는 이 파일을 source 하는 스크립트만 쓴다
REPO_URL="https://github.com/smallmiro/tesla-ble-kmp"
GH_REPO="smallmiro/tesla-ble-kmp"
PACKAGE_FILE="Package.swift"
ZIP_NAME="Teslable.xcframework.zip"
RELEASE_DIR="build/release"
# build-xcframework.sh 의 산출물. 작업용이다: 다음 빌드(check-consumer-ios.sh 인자 없이 실행 포함)가 덮어쓴다.
BUILD_ZIP="$RELEASE_DIR/$ZIP_NAME"
BUILD_SHA="$BUILD_ZIP.sha256"
# X.Y.Z, 숫자는 0 이거나 0으로 시작하지 않는다 (SemVer). 앞에 v 를 붙이지 않는다.
VERSION_RE='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

die() { # $1 = 한 줄 요약, $2 = (선택) 자세한 줄들
  echo "$SCRIPT_NAME: FAIL: $1" >&2
  [ -z "${2:-}" ] || printf '%s\n' "$2" | sed 's/^/  /' >&2
  exit 1
}
note() { echo "$SCRIPT_NAME: $1"; }
short() { git rev-parse --short "$1"; }

# prepare.sh 가 버전마다 보관하는 릴리스 산출물 폴더. 빌드 스크립트는 이 폴더를 건드리지 않는다.
#   Teslable.xcframework.zip(.sha256)  Package.swift 의 checksum 이 가리키는 zip, 올릴 파일은 이것뿐이다
#   source-tree                         그 zip 을 빌드한 커밋의 git tree (publish.sh 가 태그할 소스와 맞는지 본다)
#   verified                            publish.sh: tools/ci/verify.sh 가 통과한 "<커밋> <체크섬>"
archive_dir() { printf '%s/v%s' "$RELEASE_DIR" "$1"; }

release_url() { printf '%s/releases/download/v%s/%s' "$REPO_URL" "$1" "$ZIP_NAME"; }

check_version() {
  [[ "$1" =~ $VERSION_RE ]] || die "version must be X.Y.Z (SemVer numbers, no leading v or zeros): got '$1'"
}

is_checksum() { # 소문자 16진수 64자 (SwiftPM 체크섬)
  case "$1" in '' | *[!0-9a-f]*) return 1 ;; esac
  [ "${#1}" -eq 64 ]
}

count_lines() { if [ -z "$1" ]; then echo 0; else printf '%s\n' "$1" | wc -l | tr -d ' '; fi; }

# origin 이 이 저장소여야 한다 (태그를 다른 저장소에 올리거나 다른 main 에서 준비하지 않게). url 과 pushurl 을 설정 그대로 본다.
check_origin() {
  git rev-parse --is-inside-work-tree > /dev/null 2>&1 || die "$(pwd) is not a git work tree"
  local url urls
  urls="$(git config --get-all remote.origin.url || true)"
  [ -n "$urls" ] || die "no remote named origin"
  urls="$urls"$'\n'"$(git config --get-all remote.origin.pushurl || true)"
  while IFS= read -r url; do
    case "$url" in
      '' | "https://github.com/$GH_REPO" | "https://github.com/$GH_REPO.git" | "git@github.com:$GH_REPO" | \
        "git@github.com:$GH_REPO.git" | "ssh://git@github.com/$GH_REPO" | "ssh://git@github.com/$GH_REPO.git") ;;
      *) die "origin is $url, not $REPO_URL: run this in a clone of the repository" ;;
    esac
  done <<< "$urls"
}

check_clean_tree() {
  local dirty
  dirty="$(git status --porcelain)" || die "git status failed"
  [ -z "$dirty" ] || die "working tree not clean: commit or remove these first" "$dirty"
}

fetch_origin() {
  git fetch --quiet origin || die "git fetch origin failed"
  git rev-parse -q --verify 'refs/remotes/origin/main^{commit}' > /dev/null || die "origin/main not found after git fetch origin"
}

# $1 = (선택) 원격 태그가 가리키는 커밋. HEAD 가 그 커밋이고 origin/main 에 들어 있으면 받아들인다 (publish.sh 재시도: 태그를
# 올린 뒤 main 이 앞으로 갔을 때 그 커밋으로 돌아가 마무리한다).
check_head_is_origin_main() {
  local head main
  head="$(git rev-parse -q --verify 'HEAD^{commit}')" || die "HEAD has no commit"
  main="$(git rev-parse -q --verify 'refs/remotes/origin/main^{commit}')" || die "origin/main not found"
  [ "$head" != "$main" ] || return 0
  if [ -n "${1:-}" ] && [ "$head" = "$1" ] && git merge-base --is-ancestor "$head" "$main"; then
    note "HEAD $(short "$head") is the commit the tag on origin marks; origin/main has moved on since: continuing that release"
    return 0
  fi
  die "HEAD is not origin/main (HEAD $(short "$head"), origin/main $(short "$main")): git switch main && git pull --ff-only"
}

# 원격 태그 $1 이 가리키는 커밋 (없으면 빈 줄). 주석 태그는 ^{} 줄이 커밋이다. 실패하면 메시지를 내고 1 (호출: x="$(...)" || exit 1).
remote_tag_commit() {
  local out sha ref peeled="" plain=""
  out="$(git ls-remote --tags origin "refs/tags/$1" "refs/tags/$1^{}")" || die "git ls-remote origin failed"
  while IFS=$'\t' read -r sha ref; do
    case "$ref" in
      "refs/tags/$1^{}") peeled="$sha" ;;
      "refs/tags/$1") plain="$sha" ;;
    esac
  done <<< "$out"
  printf '%s\n' "${peeled:-$plain}"
}

# SwiftPM 체크섬. 소비자의 SwiftPM 이 내려받은 zip 에 대해 확인하는 값과 같다. (호출: x="$(...)" || exit 1)
compute_checksum() {
  local sum
  sum="$(swift package --scratch-path "$WORK_TMP/swiftpm-checksum" compute-checksum "$1")" ||
    die "swift package compute-checksum $1 failed"
  is_checksum "$sum" || die "unexpected checksum from swift package compute-checksum $1: '$sum'"
  printf '%s\n' "$sum"
}

# Package.swift 의 "<필드>: \"값\"" 줄에서 값을 모두 꺼낸다. $1 = url | checksum, $2 = 파일
package_values() { sed -n 's/^[[:space:]]*'"$1"': "\([^"]*\)".*$/\1/p' "$2"; }

# 저장소 루트 Package.swift 의 전체 내용. prepare.sh 가 쓰고 publish.sh 가 같은지 확인한다 (형식: 계획 Global Constraints).
render_package_swift() { # $1 = 버전, $2 = 체크섬
  local url
  url="$(release_url "$1")"
  cat << SWIFT
// swift-tools-version:5.9
// Written by tools/release/prepare.sh (ADR-0017). Do not edit by hand: tools/release/publish.sh publishes only
// the Package.swift that prepare.sh writes, with the checksum of the zip that prepare.sh built and kept.
import PackageDescription

let package = Package(
    name: "Teslable",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "Teslable", targets: ["Teslable"]),
    ],
    targets: [
        .binaryTarget(
            name: "Teslable",
            url: "$url",
            checksum: "$2"
        ),
    ]
)
SWIFT
}

# swift package dump-package 출력(JSON)에서 기대와 다른 점을 한 줄씩 출력한다. $1 = JSON 파일, $2 = url, $3 = checksum
manifest_problems() {
  python3 -I - "$1" "$2" "$3" << 'PY'
import json
import sys

path, url, checksum = sys.argv[1:4]
with open(path) as f:
    d = json.load(f)
problems = []


def want(label, got, expected):
    if got != expected:
        problems.append("%s: %r, expected %r" % (label, got, expected))


want("toolsVersion", (d.get("toolsVersion") or {}).get("_version"), "5.9.0")
want("name", d.get("name"), "Teslable")
want("platforms", [(p.get("platformName"), p.get("version")) for p in d.get("platforms") or []], [("ios", "16.0")])
want("dependencies", d.get("dependencies"), [])
want("products", [(p.get("name"), p.get("targets"), sorted((p.get("type") or {}).keys())) for p in d.get("products") or []],
     [("Teslable", ["Teslable"], ["library"])])
want("targets", [(t.get("name"), t.get("type"), t.get("url"), t.get("checksum")) for t in d.get("targets") or []],
     [("Teslable", "binary", url, checksum)])
print("\n".join(problems))
PY
}

# Package.swift 가 있는 폴더 $1 을 SwiftPM 으로 읽어(네트워크 없음) 이름, iOS 16, product·binary target Teslable, url, checksum 을 확인한다.
check_package_manifest() { # $1 = 폴더, $2 = 버전, $3 = 체크섬
  local json="$WORK_TMP/dump-package.json" problems
  swift package --package-path "$1" --scratch-path "$WORK_TMP/swiftpm-dump" dump-package > "$json" 2> "$WORK_TMP/dump-package.err" ||
    die "swift package dump-package cannot read the Package.swift it should commit" "$(cat "$WORK_TMP/dump-package.err")"
  problems="$(manifest_problems "$json" "$(release_url "$2")" "$3")" ||
    die "python3 could not read the swift package dump-package output"
  [ -z "$problems" ] || die "Package.swift does not describe the Teslable binary target as expected" "$problems"
}
