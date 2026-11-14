#!/usr/bin/env bash
# tools/release/test_release_scripts.sh — prepare.sh, publish.sh, check-consumer-ios.sh 인자 검사 시험 (오프라인, ADR-0017).
#   사용: tools/release/test_release_scripts.sh   (인자 없음. 시험이 하나라도 실패하면 exit 1)
#   임시 git 저장소(repo/)와 가짜 원격(remote.git, bare)을 한 번 만들어 시험마다 복사한다. 이 폴더의 prepare.sh·publish.sh·
#   release-lib.sh 를 그 저장소의 tools/release/ 에 두고 실행한다. 네트워크, GitHub, Gradle, Xcode는 쓰지 않는다:
#     - build-xcframework.sh, check-consumer-ios.sh, tools/ci/verify.sh 는 임시 저장소 안의 대역이다 (부른 기록을 남긴다).
#       check-consumer-ios.sh 인자 검사 시험만 진짜 스크립트를 복사해 두고, xcodegen 을 부르기 전에 멈추는지 본다
#     - PATH 맨 앞의 gh, curl 대역이 GitHub Release 하나를 흉내 내고, xcodebuild·xcodegen·gradle 대역은 불리면 실패한다
#     - git 은 임시 설정만 읽고(GIT_CONFIG_GLOBAL, GIT_CONFIG_NOSYSTEM), github.com URL 은 가짜 원격이나 없는 경로로 바뀐다(insteadOf)
#   swift package dump-package, compute-checksum 과 python3 은 진짜를 쓴다 (모두 네트워크를 쓰지 않는다).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC_ROOT="$(cd "$HERE/../.." && pwd)"
REPO_URL="https://github.com/smallmiro/tesla-ble-kmp"
VERSION="1.0.0"
BUILD_ZIP="build/release/Teslable.xcframework.zip"                   # build-xcframework.sh 산출물 (작업용)
KEPT="build/release/v$VERSION/Teslable.xcframework.zip"              # prepare.sh 가 보관한 릴리스 zip
ASSET_URL="$REPO_URL/releases/download/v$VERSION/Teslable.xcframework.zip"
NEXT_STEP="push the branch and open a PR; keep build/release/ until publish.sh"

# 바깥 git 환경(훅 안에서 실행 등)이 임시 저장소로 새지 않게 한다
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY GIT_COMMON_DIR GIT_PREFIX

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

export GIT_CONFIG_NOSYSTEM=1
export GIT_CONFIG_GLOBAL="$SANDBOX/gitconfig"
: > "$GIT_CONFIG_GLOBAL"
git config --global user.name "Release Test"
git config --global user.email "release-test@example.invalid"
git config --global init.defaultBranch main
git config --global commit.gpgSign false
git config --global tag.gpgSign false
git config --global advice.detachedHead false
# github.com 으로 나가는 git 호출은 없는 경로로 보낸다. 저장소마다 둔 insteadOf(REPO_URL → 가짜 원격)가 더 길어서 먼저 맞는다.
git config --global url."$SANDBOX/no-network/".insteadOf "https://github.com/"
git config --global --add url."$SANDBOX/no-network/".insteadOf "git@github.com:"

BIN="$SANDBOX/bin"
STUBS="$SANDBOX/stubs"
mkdir -p "$BIN" "$STUBS"

for tool in xcodebuild xcodegen gradle; do
  cat > "$BIN/$tool" << 'SH'
#!/usr/bin/env bash
echo "forbidden $(basename "$0") $*" >> "${RELEASE_TEST_CALLS:-/dev/null}"
echo "test_release_scripts: $(basename "$0") must not run in these tests" >&2
exit 97
SH
done

# gh 대역: $RELEASE_TEST_GH 폴더가 GitHub Release 하나다.
#   파일: release(있음) draft prerelease immutable asset(자산 바이트) asset-state notes(본문) title create-flags
cat > "$BIN/gh" << 'SH'
#!/usr/bin/env bash
set -euo pipefail
S="$RELEASE_TEST_GH"
echo "gh $*" >> "$RELEASE_TEST_CALLS"
cmd="${1:-} ${2:-}"
shift 2 || true
tag="" file="" repo="" notes="" title="" pattern="" dir="" flags=""
while [ $# -gt 0 ]; do
  case "$1" in
    --repo | -R) repo="$2"; shift 2 ;;
    --notes-file) notes="$2"; shift 2 ;;
    --title) title="$2"; shift 2 ;;
    --json | --hostname) shift 2 ;;
    --pattern | -p) pattern="$2"; shift 2 ;;
    --dir | -D) dir="$2"; shift 2 ;;
    -*) flags="$flags $1"; shift ;;
    *) if [ -z "$tag" ]; then tag="$1"; else file="$1"; fi; shift ;;
  esac
done
[ "$cmd" != "auth status" ] || exit 0
[ "$repo" = "smallmiro/tesla-ble-kmp" ] || { echo "gh stand-in: expected --repo smallmiro/tesla-ble-kmp, got '$repo'" >&2; exit 98; }
case "$cmd" in
  "release view")
    [ -f "$S/release" ] || { echo "release not found" >&2; exit 1; }
    python3 -I - "$S" << 'PY'
import json
import os
import sys

s = sys.argv[1]


def has(name):
    return os.path.exists(os.path.join(s, name))


def read(name, default=""):
    return open(os.path.join(s, name)).read() if has(name) else default


assets = []
if has("asset"):
    assets.append({"name": "Teslable.xcframework.zip", "size": os.path.getsize(os.path.join(s, "asset")),
                   "state": read("asset-state", "uploaded").strip()})
print(json.dumps({"isDraft": has("draft"), "isPrerelease": has("prerelease"),
                  "isImmutable": has("immutable") and not has("draft"), "body": read("notes"), "assets": assets}))
PY
    ;;
  "release create")
    [ ! -f "$S/release" ] || { echo "a release with the same tag name already exists: $tag" >&2; exit 1; }
    case "$flags" in *--verify-tag*) ;; *) echo "gh stand-in: release create without --verify-tag" >&2; exit 98 ;; esac
    [ -n "$(git ls-remote --tags origin "refs/tags/$tag")" ] || { echo "tag $tag doesn't exist in the remote repository" >&2; exit 1; }
    touch "$S/release"
    case "$flags" in *--draft*) touch "$S/draft" ;; esac
    [ -z "$file" ] || cp "$file" "$S/asset"
    cp "$notes" "$S/notes"
    printf '%s' "$title" > "$S/title"
    printf '%s' "$flags" > "$S/create-flags"
    echo "https://github.com/smallmiro/tesla-ble-kmp/releases/tag/untagged-0000"
    ;;
  "release upload")
    [ -f "$S/release" ] || { echo "release not found" >&2; exit 1; }
    [ ! -f "$S/immutable" ] || [ -f "$S/draft" ] || { echo "Cannot upload assets to an immutable release" >&2; exit 1; }
    [ ! -f "$S/asset" ] || { echo "asset under the same name already exists" >&2; exit 1; }
    [ "$(basename "$file")" = Teslable.xcframework.zip ] || { echo "gh stand-in: unexpected asset '$file'" >&2; exit 98; }
    cp "$file" "$S/asset"
    ;;
  "release download")
    [ -f "$S/asset" ] || { echo "no assets to download" >&2; exit 1; }
    [ ! -e "$dir/$pattern" ] || { echo "$dir/$pattern already exists" >&2; exit 1; }
    cp "$S/asset" "$dir/$pattern"
    ;;
  "release edit")
    case "$flags" in *--draft=false*) rm -f "$S/draft" ;; esac
    case "$flags" in *--prerelease=false*) rm -f "$S/prerelease" ;; esac
    ;;
  *) echo "gh stand-in: unexpected call: gh $cmd" >&2; exit 99 ;;
esac
SH

# curl 대역: 공개 다운로드 URL 하나만 안다. 게시된(draft 아님) Release 의 자산만 내려준다.
cat > "$BIN/curl" << 'SH'
#!/usr/bin/env bash
set -euo pipefail
S="$RELEASE_TEST_GH"
echo "curl $*" >> "$RELEASE_TEST_CALLS"
out="" url=""
while [ $# -gt 0 ]; do
  case "$1" in
    --output | -o) out="$2"; shift 2 ;;
    --retry | --retry-delay | --max-time | --connect-timeout | --proto) shift 2 ;;
    -*) shift ;;
    *) url="$1"; shift ;;
  esac
done
if [ "$url" = "$RELEASE_TEST_ASSET_URL" ] && [ -f "$S/release" ] && [ ! -f "$S/draft" ] && [ -f "$S/asset" ]; then
  cp "$S/asset" "$out"
  exit 0
fi
echo "curl: (22) The requested URL returned error: 404" >&2
exit 22
SH

# 임시 저장소 안의 대역들
cat > "$STUBS/build-xcframework.sh" << 'SH'
#!/usr/bin/env bash
# build-xcframework.sh 대역: 진짜처럼 실행할 때마다 다른 zip 과 그 체크섬을 build/release/ 에 쓴다
set -euo pipefail
echo "build-xcframework${*:+ $*}" >> "$RELEASE_TEST_CALLS"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$ROOT/build/release/Teslable.xcframework"
printf 'stand-in zip built by process %s at %s\n' "$$" "$(date +%s)" > "$ROOT/build/release/Teslable.xcframework.zip"
shasum -a 256 "$ROOT/build/release/Teslable.xcframework.zip" | cut -d ' ' -f 1 > "$ROOT/build/release/Teslable.xcframework.zip.sha256"
SH
cat > "$STUBS/check-consumer-ios.sh" << 'SH'
#!/usr/bin/env bash
# check-consumer-ios.sh 대역: 인자가 없으면 진짜처럼 build-xcframework.sh 를 먼저 부른다
set -euo pipefail
echo "check-consumer-ios${*:+ $*}" >> "$RELEASE_TEST_CALLS"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
[ $# -gt 0 ] || "$ROOT/tools/release/build-xcframework.sh"
exit "${RELEASE_TEST_CONSUMER_EXIT:-0}"
SH
cat > "$STUBS/verify.sh" << 'SH'
#!/usr/bin/env bash
echo "verify${*:+ $*}" >> "$RELEASE_TEST_CALLS"
exit 0
SH
chmod +x "$BIN"/* "$STUBS"/*
export PATH="$BIN:$PATH"
export RELEASE_TEST_ASSET_URL="$ASSET_URL"

# ---- 시험 도우미 (시험 하나는 따로 된 subshell 에서 set -e 로 돈다) ----

T="" OUT="" STATUS=0 REFS_BEFORE=""

# origin(REPO_URL)을 폴더 $1 의 가짜 원격으로 보낸다. 환경 변수 설정이라 저장소에 경로가 남지 않아 저장소를 통째로 복사할 수 있다.
use_remote() { export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0="url.$1/remote.git.insteadOf" GIT_CONFIG_VALUE_0="$REPO_URL"; }

# 시험 저장소의 원본: main 커밋 하나를 가짜 원격에 올린 상태 (HEAD = origin/main, 깨끗한 작업 트리). 한 번 만들고 시험마다 복사한다.
TEMPLATE="$SANDBOX/template"
(
  use_remote "$TEMPLATE"
  git init -q --bare "$TEMPLATE/remote.git"
  git init -q "$TEMPLATE/repo"
  cd "$TEMPLATE/repo"
  mkdir -p tools/release tools/ci
  cp "$SRC_ROOT/.gitignore" .gitignore
  for f in prepare.sh publish.sh release-lib.sh; do
    [ ! -f "$HERE/$f" ] || cp "$HERE/$f" tools/release/
  done
  cp "$STUBS/build-xcframework.sh" "$STUBS/check-consumer-ios.sh" tools/release/
  cp "$STUBS/verify.sh" tools/ci/
  echo "release script test fixture" > README.md
  git add -A
  git commit -q -m "chore: fixture"
  git remote add origin "$REPO_URL"
  git push -q origin main
)

new_repo() {
  T="$SANDBOX/case-$1"
  cp -R "$TEMPLATE" "$T"
  mkdir -p "$T/gh"
  : > "$T/calls"
  export RELEASE_TEST_CALLS="$T/calls" RELEASE_TEST_GH="$T/gh"
  use_remote "$T"
  cd "$T/repo"
}

# prepare.sh 가 끝나고 그 PR 이 병합된 main: Package.swift(버전 $1), build/release/v<버전>/ 에 zip, .sha256, source-tree
prepared_main() {
  local ver="${1:-$VERSION}" sum kept="build/release/v${1:-$VERSION}"
  mkdir -p "$kept"
  printf 'fixture zip\n' > "$kept/Teslable.xcframework.zip"
  sum="$(checksum_of "$kept/Teslable.xcframework.zip")"
  printf '%s\n' "$sum" > "$kept/Teslable.xcframework.zip.sha256"
  git rev-parse 'HEAD^{tree}' > "$kept/source-tree"
  (
    # shellcheck source=/dev/null # 저장소 복사본의 release-lib.sh (render_package_swift)
    . tools/release/release-lib.sh
    render_package_swift "$ver" "$sum"
  ) > Package.swift
  git add Package.swift
  git commit -q -m "chore: release $ver"
  git push -q origin main
}

# 전 실행이 해 둔 일
gate_passed() { printf '%s %s\n' "$(git rev-parse HEAD)" "$(cat "$KEPT.sha256")" > "build/release/v$VERSION/verified"; }
push_tag_at() { # $1 = 커밋
  git tag -a "v$VERSION" -m "test tag" "$1"
  git push -q origin "refs/tags/v$VERSION"
}
release_with_our_notes() { # $@ = gh 상태 파일 (draft, immutable ...)
  touch "$T/gh/release"
  printf '## Installation\n\n...\n\n## Real-vehicle check pending: #118\n\n...\n' > "$T/gh/notes"
  local f
  for f in "$@"; do touch "$T/gh/$f"; done
}

checksum_of() { shasum -a 256 "$1" | cut -d ' ' -f 1; }
snapshot() { REFS_BEFORE="$(git ls-remote "$T/remote.git")"; }

run() { # $1 = prepare.sh | publish.sh, 나머지 = 인자. OUT, STATUS 를 채운다
  set +e
  OUT="$("./tools/release/$1" "${@:2}" 2>&1)"
  STATUS=$?
  set -e
}

fail_case() {
  echo "ASSERTION FAILED: $1" >&2
  [ -z "${2:-}" ] || printf '%s\n' "$2" | sed 's/^/  | /' >&2
  exit 1
}

expect_exit() { [ "$STATUS" -eq "$1" ] || fail_case "exit $STATUS, expected $1" "$OUT"; }
expect_out() { grep -qF -- "$1" <<< "$OUT" || fail_case "output lacks: $1" "$OUT"; }
expect_no_out() { ! grep -qF -- "$1" <<< "$OUT" || fail_case "output should not have: $1" "$OUT"; }
expect_last_line() { [ "$(printf '%s\n' "$OUT" | tail -n 1)" = "$1" ] || fail_case "last line is not: $1" "$OUT"; }

# 부른 대역 목록을 앞 단어들로 줄인다 (gh 는 하위 명령까지)
calls() { sed -E 's/^(gh [a-z]+ [a-z]+|check-consumer-ios --remote [0-9.]+|[a-z-]+).*/\1/' "$T/calls"; }
expect_calls() { # $1 = 줄마다 하나씩, 순서대로
  [ "$(calls)" = "$1" ] || fail_case "calls differ" "$(printf 'got:\n%s\nexpected:\n%s' "$(cat "$T/calls")" "$1")"
}

# 사전 검사에서 멈췄다: 대역을 하나도 부르지 않았고, 원격·로컬 태그·브랜치가 그대로다
expect_no_side_effects() {
  [ ! -s "$T/calls" ] || fail_case "a precheck failure still called" "$(cat "$T/calls")"
  [ "$(git ls-remote "$T/remote.git")" = "$REFS_BEFORE" ] || fail_case "remote refs changed" "$(git ls-remote "$T/remote.git")"
  [ -z "$(git tag -l)" ] || [ -n "${KEEP_TAGS:-}" ] || fail_case "a local tag was created" "$(git tag -l)"
  [ -z "$(git for-each-ref refs/heads/release/)" ] || fail_case "a release branch was created" "$(git for-each-ref refs/heads/release/)"
  [ "$(git symbolic-ref -q --short HEAD || echo detached)" = main ] || fail_case "HEAD left main"
}

origin_tag_commit() { (cd "$T/remote.git" && git rev-parse -q --verify "refs/tags/v$VERSION^{commit}") || true; }

# 성공한 publish.sh 뒤의 상태: 태그는 HEAD, Release 는 정식, 자산은 보관한 zip, main 은 그대로
expect_released() { # $1 = 태그가 가리킬 커밋, $2 = origin main 이 있어야 할 커밋
  expect_exit 0
  expect_last_line "RELEASED v$VERSION"
  [ "$(origin_tag_commit)" = "$1" ] || fail_case "origin tag v$VERSION is not at $1"
  [ "$(cd "$T/remote.git" && git rev-parse refs/heads/main)" = "$2" ] || fail_case "origin main moved"
  [ ! -e "$T/gh/draft" ] || fail_case "the release is still a draft"
  [ ! -e "$T/gh/prerelease" ] || fail_case "the release is still a prerelease"
  cmp -s "$T/gh/asset" "$KEPT" || fail_case "the release asset is not $KEPT"
}

# ---- 사전 검사 (계획 Task 3 Step 1의 여섯 가지) ----

case_prepare_rejects_bad_version() {
  snapshot
  run prepare.sh 1.0
  expect_exit 1
  expect_out "version must be X.Y.Z"
  expect_no_side_effects
}

case_prepare_rejects_dirty_tree() {
  echo "local edit" >> README.md
  snapshot
  run prepare.sh "$VERSION"
  expect_exit 1
  expect_out "working tree not clean"
  expect_no_side_effects
}

case_publish_rejects_head_not_origin_main() {
  prepared_main
  echo "unpushed" >> README.md
  git commit -q -am "local commit"
  snapshot
  run publish.sh "$VERSION"
  expect_exit 1
  expect_out "HEAD is not origin/main"
  expect_no_side_effects
}

case_publish_rejects_package_version_mismatch() {
  prepared_main 1.0.1
  snapshot
  run publish.sh "$VERSION"
  expect_exit 1
  expect_out "Package.swift version mismatch"
  expect_no_side_effects
}

case_publish_rejects_sha256_mismatch() {
  prepared_main
  printf 'rebuilt zip\n' | shasum -a 256 | cut -d ' ' -f 1 > "$KEPT.sha256"
  snapshot
  run publish.sh "$VERSION"
  expect_exit 1
  expect_out "checksum mismatch: do not rebuild the zip"
  expect_no_side_effects
}

case_publish_rejects_missing_zip() {
  prepared_main
  rm "$KEPT"
  snapshot
  run publish.sh "$VERSION"
  expect_exit 1
  expect_out "zip missing: run prepare.sh on this machine"
  expect_no_side_effects
}

# ---- prepare.sh ----

expect_prepared() { # $1 = 브랜치를 만든 커밋 (origin/main)
  local base="$1" sum
  expect_exit 0
  expect_out "$NEXT_STEP"
  [ "$(git symbolic-ref --short HEAD)" = "release/v$VERSION" ] || fail_case "not on release/v$VERSION"
  [ "$(git rev-parse HEAD^)" = "$base" ] || fail_case "the release commit is not on origin/main"
  [ "$(git log -1 --format=%s)" = "chore: release $VERSION" ] || fail_case "commit subject: $(git log -1 --format=%s)"
  [ "$(git diff --name-only HEAD^ HEAD)" = "Package.swift" ] || fail_case "the commit changes more than Package.swift" "$(git diff --name-only HEAD^ HEAD)"
  [ -z "$(git status --porcelain)" ] || fail_case "working tree not clean after prepare" "$(git status --porcelain)"
  # 보관한 zip = 마지막 빌드의 zip, .sha256 = 그 체크섬 = Package.swift 의 checksum, source-tree = 빌드한 커밋의 tree
  cmp -s "$KEPT" "$BUILD_ZIP" || fail_case "$KEPT is not the zip of the build"
  sum="$(cat "$KEPT.sha256")"
  [ "$sum" = "$(checksum_of "$KEPT")" ] || fail_case "$KEPT.sha256 does not belong to the zip"
  [ "$(cat "build/release/v$VERSION/source-tree")" = "$(git rev-parse "$base^{tree}")" ] || fail_case "source-tree is not the tree of $base"
  (
    # shellcheck source=/dev/null # 저장소 복사본의 release-lib.sh (render_package_swift)
    . tools/release/release-lib.sh
    render_package_swift "$VERSION" "$sum"
  ) > "$T/expected-Package.swift"
  cmp -s "$T/expected-Package.swift" Package.swift ||
    fail_case "Package.swift is not the template with the zip checksum" "$(diff -u "$T/expected-Package.swift" Package.swift || true)"
  grep -qF "url: \"$ASSET_URL\"" Package.swift || fail_case "Package.swift url" "$(cat Package.swift)"
  [ "$(git ls-remote "$T/remote.git")" = "$REFS_BEFORE" ] || fail_case "prepare.sh pushed something"
  [ -z "$(git tag -l)" ] || fail_case "prepare.sh created a tag"
}

case_prepare_happy_path() {
  local base
  base="$(git rev-parse HEAD)"
  snapshot
  run prepare.sh "$VERSION"
  expect_prepared "$base"
  # 체크섬을 읽기 전에 소비자 검사(그 안에서 빌드)가 딱 한 번 돌았다. 그 뒤로는 아무것도 zip 을 다시 만들지 않았다.
  expect_calls "check-consumer-ios
build-xcframework"
}

case_prepare_resumes_after_a_failed_consumer_check() {
  local base
  base="$(git rev-parse HEAD)"
  snapshot
  RELEASE_TEST_CONSUMER_EXIT=1 run prepare.sh "$VERSION"
  expect_exit 1
  expect_out "check-consumer-ios.sh failed"
  [ ! -e Package.swift ] || fail_case "Package.swift was written although the consumer check failed"
  [ ! -e "build/release/v$VERSION" ] || fail_case "the zip was kept although the consumer check failed"
  [ "$(git rev-parse HEAD)" = "$base" ] || fail_case "a commit was made although the consumer check failed"
  [ "$(git symbolic-ref --short HEAD)" = "release/v$VERSION" ] || fail_case "expected to stay on release/v$VERSION for the next run"
  [ -z "$(git status --porcelain)" ] || fail_case "working tree not clean" "$(git status --porcelain)"
  : > "$T/calls"
  run prepare.sh "$VERSION"
  expect_out "continuing"
  expect_prepared "$base"
}

case_prepare_rejects_head_not_origin_main() {
  echo "unpushed" >> README.md
  git commit -q -am "local commit"
  snapshot
  run prepare.sh "$VERSION"
  expect_exit 1
  expect_out "HEAD is not origin/main"
  expect_no_side_effects
}

case_prepare_rejects_released_version() {
  push_tag_at HEAD
  snapshot
  run prepare.sh "$VERSION"
  expect_exit 1
  expect_out "tag v$VERSION already exists on origin"
  KEEP_TAGS=1 expect_no_side_effects
}

case_prepare_keeps_an_earlier_prepared_zip() {
  mkdir -p "build/release/v$VERSION"
  printf 'zip of an earlier prepare.sh run\n' > "$KEPT"
  snapshot
  run prepare.sh "$VERSION"
  expect_exit 1
  expect_out "already holds a prepared release"
  expect_no_side_effects
  [ "$(cat "$KEPT")" = "zip of an earlier prepare.sh run" ] || fail_case "the earlier zip was touched"
}

case_prepare_rejects_a_release_branch_with_commits() {
  git switch -q -c "release/v$VERSION"
  echo "old attempt" >> README.md
  git commit -q -am "old attempt"
  git switch -q main
  snapshot
  run prepare.sh "$VERSION"
  expect_exit 1
  expect_out "branch release/v$VERSION already exists"
  [ ! -s "$T/calls" ] || fail_case "a precheck failure still called" "$(cat "$T/calls")"
}

# ---- publish.sh ----

case_publish_happy_path() {
  local head
  prepared_main
  head="$(git rev-parse HEAD)"
  snapshot
  run publish.sh "$VERSION"
  expect_released "$head" "$head"
  # 게이트 → 태그 → draft → 업로드 → 내려받아 확인 → 게시 → 공개 URL 확인 → 원격 소비자
  expect_calls "gh auth status
verify
gh release view
gh release create
gh release view
gh release upload
gh release download
gh release edit
gh release view
curl
check-consumer-ios --remote $VERSION"
  [ "$(cd "$T/remote.git" && git cat-file -t "refs/tags/v$VERSION")" = tag ] || fail_case "tag v$VERSION is not annotated"
  [ "$(cd "$T/remote.git" && git for-each-ref --format='%(refname)')" = "refs/heads/main
refs/tags/v$VERSION" ] || fail_case "publish.sh pushed more than the tag" "$(cd "$T/remote.git" && git for-each-ref)"
  [ "$(cat "$T/gh/title")" = "$VERSION" ] || fail_case "release title: $(cat "$T/gh/title")"
  case "$(cat "$T/gh/create-flags")" in
    *--prerelease*) fail_case "release created as a prerelease: $(cat "$T/gh/create-flags")" ;;
  esac
  local want
  for want in "## Real-vehicle check pending: #118" "## Installation" "$REPO_URL" "from: \"$VERSION\"" \
    "11-lib-overview.md#installation" "Android" "$(checksum_of "$KEPT")"; do
    grep -qF -- "$want" "$T/gh/notes" || fail_case "release notes lack: $want" "$(cat "$T/gh/notes")"
  done
  [ "$(cat "build/release/v$VERSION/verified")" = "$head $(checksum_of "$KEPT")" ] || fail_case "the gate record is missing"
}

case_publish_resumes_a_draft_without_its_zip() {
  local head
  prepared_main
  head="$(git rev-parse HEAD)"
  gate_passed
  push_tag_at HEAD
  release_with_our_notes draft # 전 실행이 draft 를 만들고 업로드에서 멈췄다
  run publish.sh "$VERSION"
  expect_released "$head" "$head"
  expect_out "skipping tools/ci/verify.sh"
  expect_calls "gh auth status
gh release view
gh release upload
gh release download
gh release edit
gh release view
curl
check-consumer-ios --remote $VERSION"
}

case_publish_finishes_a_draft_release() {
  local head
  prepared_main
  head="$(git rev-parse HEAD)"
  gate_passed
  push_tag_at HEAD
  release_with_our_notes draft
  cp "$KEPT" "$T/gh/asset"
  run publish.sh "$VERSION"
  expect_released "$head" "$head"
  expect_calls "gh auth status
gh release view
gh release download
gh release edit
gh release view
curl
check-consumer-ios --remote $VERSION"
}

case_publish_promotes_a_prerelease() {
  local head
  prepared_main
  head="$(git rev-parse HEAD)"
  gate_passed
  push_tag_at HEAD
  release_with_our_notes prerelease # 정식 릴리스여야 한다 (계획 Global Constraints)
  cp "$KEPT" "$T/gh/asset"
  run publish.sh "$VERSION"
  expect_released "$head" "$head"
  expect_calls "gh auth status
gh release view
gh release download
gh release edit
gh release view
curl
check-consumer-ios --remote $VERSION"
}

case_publish_runs_the_gate_for_a_tag_it_did_not_push() {
  local head before
  prepared_main
  head="$(git rev-parse HEAD)"
  push_tag_at HEAD # 손으로 올린 태그: 게이트가 통과했다는 기록이 없다
  before="$(cd "$T/remote.git" && git rev-parse "refs/tags/v$VERSION")"
  run publish.sh "$VERSION"
  expect_released "$head" "$head"
  expect_calls "gh auth status
verify
gh release view
gh release create
gh release view
gh release upload
gh release download
gh release edit
gh release view
curl
check-consumer-ios --remote $VERSION"
  [ "$(cd "$T/remote.git" && git rev-parse "refs/tags/v$VERSION")" = "$before" ] || fail_case "the existing tag was replaced"
}

case_publish_finishes_after_main_moved_on() {
  local release_commit main_now
  prepared_main
  release_commit="$(git rev-parse HEAD)"
  gate_passed
  push_tag_at HEAD
  echo "later work" >> README.md
  git commit -q -am "later work on main"
  git push -q origin main
  main_now="$(git rev-parse HEAD)"
  git switch -q --detach "v$VERSION"
  run publish.sh "$VERSION"
  expect_out "origin/main has moved on"
  expect_released "$release_commit" "$main_now"
  expect_calls "gh auth status
gh release view
gh release create
gh release view
gh release upload
gh release download
gh release edit
gh release view
curl
check-consumer-ios --remote $VERSION"
}

case_publish_refuses_an_asset_with_another_checksum() {
  prepared_main
  gate_passed
  push_tag_at HEAD
  release_with_our_notes
  printf 'some other zip\n' > "$T/gh/asset"
  run publish.sh "$VERSION"
  expect_exit 1
  expect_out "asset checksum differs"
  expect_out "gh release delete-asset v$VERSION Teslable.xcframework.zip"
  expect_calls "gh auth status
gh release view
gh release download"
  [ "$(cat "$T/gh/asset")" = "some other zip" ] || fail_case "the existing asset was replaced"
}

case_publish_refuses_to_patch_an_immutable_release() {
  prepared_main
  gate_passed
  push_tag_at HEAD
  release_with_our_notes immutable
  printf 'some other zip\n' > "$T/gh/asset"
  run publish.sh "$VERSION"
  expect_exit 1
  expect_out "immutable"
  expect_no_out "delete-asset"
  expect_calls "gh auth status
gh release view
gh release download"
}

case_publish_refuses_a_release_it_did_not_make() {
  prepared_main
  gate_passed
  push_tag_at HEAD
  touch "$T/gh/release" # 손으로 만든 Release: 설치 안내와 #118 절이 없다
  cp "$KEPT" "$T/gh/asset"
  run publish.sh "$VERSION"
  expect_exit 1
  expect_out "notes lack"
  expect_calls "gh auth status
gh release view"
}

case_publish_refuses_a_tag_on_another_commit() {
  prepared_main
  KEEP_TAGS=1
  push_tag_at HEAD^
  snapshot
  run publish.sh "$VERSION"
  expect_exit 1
  expect_out "tag v$VERSION already exists on origin at"
  expect_no_side_effects
}

case_publish_rejects_a_zip_changed_after_prepare() {
  prepared_main
  printf 'rebuilt\n' >> "$KEPT" # .sha256 은 Package.swift 와 같지만 zip 바이트가 바뀌었다
  snapshot
  run publish.sh "$VERSION"
  expect_exit 1
  expect_out "checksum mismatch: do not rebuild the zip"
  expect_no_side_effects
}

case_publish_rejects_source_changed_after_prepare() {
  prepared_main
  echo "merged after prepare.sh" >> README.md # rebase merge 가 다른 PR 위에 올라갔다
  git commit -q -am "another PR"
  git push -q origin main
  snapshot
  run publish.sh "$VERSION"
  expect_exit 1
  expect_out "the source changed since prepare.sh built the zip"
  expect_out "README.md"
  expect_no_side_effects
}

case_publish_rejects_a_hand_edited_package_swift() {
  prepared_main
  echo "// hand edit" >> Package.swift
  git commit -q -am "edit Package.swift"
  git push -q origin main
  snapshot
  run publish.sh "$VERSION"
  expect_exit 1
  expect_out "Package.swift is not the file prepare.sh writes"
  expect_no_side_effects
}

# ---- check-consumer-ios.sh 의 인자 검사 (대역 대신 진짜 스크립트, xcodegen 을 부르기 전에 멈춘다) ----

real_consumer_check() { cp "$HERE/check-consumer-ios.sh" tools/release/check-consumer-ios.sh; }

case_consumer_check_takes_only_versions_publish_takes() {
  # publish.sh 가 거절하는 버전(prerelease, 빌드 메타데이터, 앞자리 0, v 접두어)은 --remote 로도 검사하지 않는다
  real_consumer_check
  local v
  for v in 1.0.0-rc.1 1.0.0+build.5 01.0.0 v1.0.0 1.0; do
    run check-consumer-ios.sh --remote "$v"
    expect_exit 1
    expect_out "check-consumer-ios: FAIL: version must be X.Y.Z"
  done
  [ ! -s "$T/calls" ] || fail_case "the consumer check ran a tool before rejecting the version" "$(cat "$T/calls")"
}

case_consumer_check_looks_for_the_tag_in_the_release_repository() {
  # --remote 는 release-lib.sh 의 REPO_URL 에서 태그를 찾는다 (여기서는 insteadOf 로 가짜 원격, 태그 없음)
  real_consumer_check
  mkdir -p tools/release/consumer-ios
  : > tools/release/consumer-ios/project.yml
  run check-consumer-ios.sh --remote "$VERSION"
  expect_exit 1
  expect_out "check-consumer-ios: FAIL: tag v$VERSION does not exist in $REPO_URL"
  [ ! -s "$T/calls" ] || fail_case "the consumer check ran a tool although the tag is missing" "$(cat "$T/calls")"
}

# ---- 저장소 설정 ----

case_gitignore_covers_release_and_swiftpm_output() {
  # Package.swift 가 저장소 루트에 생기면 SwiftPM/Xcode 가 .build/ 와 .swiftpm/ 을 만든다
  local p
  for p in .build/x .swiftpm/x "$BUILD_ZIP" "$KEPT" "build/release/v$VERSION/source-tree"; do
    git check-ignore -q "$p" || fail_case "the root .gitignore does not ignore $p"
  done
}

# ---- 실행 ----

PASSED=0
FAILED=0
run_case() { # $1 = 시험 이름 (함수 case_<이름>)
  local status log="$SANDBOX/$1.log"
  set +e
  (
    set -euo pipefail
    new_repo "$1"
    "case_$1"
  ) > "$log" 2>&1
  status=$?
  set -e
  if [ "$status" -eq 0 ]; then
    PASSED=$((PASSED + 1))
    echo "PASS  $1"
  else
    FAILED=$((FAILED + 1))
    echo "FAIL  $1"
    tail -n 40 "$log" | sed 's/^/      /'
  fi
}

START="$(date +%s)"
for name in \
  prepare_rejects_bad_version \
  prepare_rejects_dirty_tree \
  publish_rejects_head_not_origin_main \
  publish_rejects_package_version_mismatch \
  publish_rejects_sha256_mismatch \
  publish_rejects_missing_zip \
  prepare_happy_path \
  prepare_resumes_after_a_failed_consumer_check \
  prepare_rejects_head_not_origin_main \
  prepare_rejects_released_version \
  prepare_keeps_an_earlier_prepared_zip \
  prepare_rejects_a_release_branch_with_commits \
  publish_happy_path \
  publish_resumes_a_draft_without_its_zip \
  publish_finishes_a_draft_release \
  publish_promotes_a_prerelease \
  publish_runs_the_gate_for_a_tag_it_did_not_push \
  publish_finishes_after_main_moved_on \
  publish_refuses_an_asset_with_another_checksum \
  publish_refuses_to_patch_an_immutable_release \
  publish_refuses_a_release_it_did_not_make \
  publish_refuses_a_tag_on_another_commit \
  publish_rejects_a_zip_changed_after_prepare \
  publish_rejects_source_changed_after_prepare \
  publish_rejects_a_hand_edited_package_swift \
  consumer_check_takes_only_versions_publish_takes \
  consumer_check_looks_for_the_tag_in_the_release_repository \
  gitignore_covers_release_and_swiftpm_output; do
  run_case "$name"
done
echo "test_release_scripts: $PASSED passed, $FAILED failed ($(($(date +%s) - START))s)"
[ "$FAILED" -eq 0 ]
