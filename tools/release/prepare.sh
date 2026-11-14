#!/usr/bin/env bash
# tools/release/prepare.sh — 릴리스 1단계: release/v<version> 브랜치에 Package.swift 커밋 하나를 만든다 (ADR-0017).
#   사용: tools/release/prepare.sh <version>      예: tools/release/prepare.sh 1.0.0   (X.Y.Z, 앞에 v 없음)
#   순서 (체크섬을 읽은 뒤에는 아무것도 zip 을 다시 만들지 않는다):
#     1) 사전 검사 (부수 효과 없음): 버전 형식, origin 이 이 저장소, 깨끗한 작업 트리, HEAD = 최신 origin/main,
#        원격에 태그 v<version> 없음, release/v<version> 브랜치가 없거나 HEAD 에 있음(지난 실행이 중간에 멈춤),
#        build/release/v<version>/ 없음(다른 준비본을 덮어쓰지 않음), git 사용자 설정, swift·python3
#     2) release/v<version> 브랜치를 만들거나 그 브랜치로 바꾼다
#     3) tools/release/check-consumer-ios.sh (인자 없음): build-xcframework.sh 로 zip 과 .sha256 을 만들고, 소비자 앱을
#        기기용으로 빌드하고, Release 아카이브의 앱 dSYM 에 Kotlin 디버그 정보가 있는지 보고, 시뮬레이터에서 시험한다
#     4) zip 과 .sha256 을 build/release/v<version>/ 에 보관하고(빌드한 git tree 도 적는다), 그 체크섬으로 Package.swift 를 쓴 뒤
#        swift package dump-package 로 확인한다 (네트워크 없음)
#     5) 커밋 "chore: release <version>" (Package.swift 만)
#   푸시와 PR 생성은 하지 않는다. build/release/v<version>/ 는 publish.sh 가 올릴 때까지 지우지 않는다.
#   실패하면 고친 뒤 같은 명령을 다시 실행한다 (커밋이 없는 release/v<version> 브랜치는 그대로 이어 쓴다).
set -euo pipefail

SCRIPT_NAME="prepare"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
# shellcheck source=tools/release/release-lib.sh
. "$ROOT/tools/release/release-lib.sh"

if [ $# -ne 1 ]; then
  echo "usage: tools/release/prepare.sh <version>   (X.Y.Z, for example 1.0.0)" >&2
  exit 2
fi
version="$1"
check_version "$version"
tag="v$version"
branch="release/$tag"
archive="$(archive_dir "$version")"

WORK_TMP="$(mktemp -d)"
archive_made=0   # 이 실행이 build/release/v<version>/ 을 만들었다
package_written=0
committed=0
on_exit() {
  # 커밋 전에 멈추면 이 실행이 만든 것을 되돌린다: 다음 실행이 깨끗한 작업 트리에서 다시 시작할 수 있게.
  if [ "$committed" -eq 0 ]; then
    if [ "$package_written" -eq 1 ]; then
      git reset -q -- "$PACKAGE_FILE" 2> /dev/null || true
      if git cat-file -e "HEAD:$PACKAGE_FILE" 2> /dev/null; then
        git checkout -q HEAD -- "$PACKAGE_FILE" 2> /dev/null || true
      else
        rm -f "$PACKAGE_FILE"
      fi
    fi
    [ "$archive_made" -eq 0 ] || rm -rf "$archive"
  fi
  rm -rf "$WORK_TMP"
}
trap on_exit EXIT

# ---- 1) 사전 검사 ----
check_origin
check_clean_tree
fetch_origin
check_head_is_origin_main "" # 준비는 언제나 최신 origin/main 에서 시작한다 (publish.sh 와 달리 예외 없음)
head="$(git rev-parse HEAD)"

published="$(remote_tag_commit "$tag")" || exit 1
[ -z "$published" ] || die "tag $tag already exists on origin: $version is released, pick a new version"

branch_commit="$(git rev-parse -q --verify "refs/heads/$branch^{commit}" || true)"
if [ -n "$branch_commit" ] && [ "$branch_commit" != "$head" ]; then
  die "branch $branch already exists at $(short "$branch_commit"), not at origin/main $(short "$head"): if prepare.sh finished, push that branch and open a PR; otherwise delete it (git branch -D $branch) and run again"
fi

if [ -e "$archive" ]; then
  die "$archive/ already holds a prepared release: if its PR is merged, publish it (tools/release/publish.sh $version); if it was abandoned, delete the folder and run again"
fi

git var GIT_COMMITTER_IDENT > /dev/null 2>&1 || die "git user.name and user.email are not set (the release commit needs them)"
command -v swift > /dev/null 2>&1 || die "swift not found (install Xcode)"
command -v python3 > /dev/null 2>&1 || die "python3 not found"

# ---- 2) 브랜치 ----
if [ -z "$branch_commit" ]; then
  note "creating branch $branch at origin/main $(short "$head")"
  git switch -q -c "$branch"
elif [ "$(git symbolic-ref -q --short HEAD || true)" != "$branch" ]; then
  note "branch $branch exists at origin/main $(short "$head") (an earlier run stopped): switching to it"
  git switch -q "$branch"
else
  note "on $branch at origin/main $(short "$head") (an earlier run stopped): continuing"
fi

# ---- 3) 릴리스 빌드 + 소비자 검사 ----
# 지난 빌드의 zip 이 이번 결과로 보이지 않게 먼저 지운다 (build-xcframework.sh 도 처음에 지운다). 준비본은 build/release/v*/ 에 따로 있다.
rm -f "$BUILD_ZIP" "$BUILD_SHA"
note "tools/release/check-consumer-ios.sh: release XCFramework build, then the consumer app (several minutes)"
"$ROOT/tools/release/check-consumer-ios.sh" || die "tools/release/check-consumer-ios.sh failed (see above); fix it and run prepare.sh again"

[ -f "$BUILD_ZIP" ] && [ -f "$BUILD_SHA" ] ||
  die "check-consumer-ios.sh left no $BUILD_ZIP and $BUILD_SHA (it must build them with build-xcframework.sh)"
checksum="$(cat "$BUILD_SHA")"
is_checksum "$checksum" || die "$BUILD_SHA does not hold one SwiftPM checksum: '$checksum'"
dirty="$(git status --porcelain)" || die "git status failed"
[ -z "$dirty" ] || die "the release build changed the working tree: restore these files and run prepare.sh again" "$dirty"
[ "$(git rev-parse HEAD)" = "$head" ] || die "HEAD moved during the build"

# ---- 4) 보관 + Package.swift ----
# 이 zip 이 Package.swift 의 체크섬이 가리키는 유일한 파일이다. 빌드 스크립트가 덮어쓰지 못하는 곳에 둔다.
mkdir -p "$RELEASE_DIR"
mkdir "$archive"
archive_made=1
cp -p "$BUILD_ZIP" "$archive/$ZIP_NAME"
printf '%s\n' "$checksum" > "$archive/$ZIP_NAME.sha256"
git rev-parse 'HEAD^{tree}' > "$archive/source-tree"
kept="$(compute_checksum "$archive/$ZIP_NAME")" || exit 1
[ "$kept" = "$checksum" ] || die "$BUILD_SHA says $checksum but the zip hashes to $kept"

mkdir "$WORK_TMP/package"
render_package_swift "$version" "$checksum" > "$WORK_TMP/package/$PACKAGE_FILE"
note "swift package dump-package: checking the new Package.swift (offline)"
check_package_manifest "$WORK_TMP/package" "$version" "$checksum"

# ---- 5) 커밋 ----
package_written=1
cp "$WORK_TMP/package/$PACKAGE_FILE" "$PACKAGE_FILE"
git add -- "$PACKAGE_FILE"
cat > "$WORK_TMP/commit-msg" << MSG
chore: release $version

Package.swift points at $ZIP_NAME of GitHub Release $tag
(SwiftPM checksum $checksum), built from origin/main $(short "$head")
(tree $(cat "$archive/source-tree")). tools/release/check-consumer-ios.sh passed:
device build, Release archive with the Kotlin debug info in the app dSYM, and
simulator tests. After this merges, publish from main on the same Mac:
tools/release/publish.sh $version

Refs: ADR-0017
MSG
git commit -q -F "$WORK_TMP/commit-msg" || die "git commit failed"
committed=1

dirty="$(git status --porcelain)" || die "git status failed"
[ -z "$dirty" ] || die "the working tree is not clean after the release commit" "$dirty"

note "DONE: $branch is one commit on origin/main: $(git log -1 --format='%h %s')"
note "checksum $checksum, kept in $archive/"
echo "Next: push the branch and open a PR; keep build/release/ until publish.sh"
echo "  git push -u origin $branch"
echo "  after the PR is merged: git switch main && git pull --ff-only && tools/release/publish.sh $version"
