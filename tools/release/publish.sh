#!/usr/bin/env bash
# tools/release/publish.sh — 릴리스 2단계: 병합된 main 에서 태그 v<version> 과 GitHub Release 를 만들고 공개된 zip 을 확인한다 (ADR-0017).
#   사용: tools/release/publish.sh <version>   (prepare.sh 를 실행한 이 Mac 에서, prepare.sh 의 PR 이 병합된 main 에서)
#   zip 을 만들지 않는다. prepare.sh 가 build/release/v<version>/ 에 보관한 zip(체크섬이 Package.swift 에 있는 것)을 그대로 올린다.
#   사전 검사 (하나라도 어기면 exit 1, 부수 효과 없음):
#     버전 형식, origin 이 이 저장소, 깨끗한 작업 트리, HEAD = origin/main (원격 태그가 이미 HEAD 를 가리키고 그 커밋이 main 에
#     들어 있으면 그 커밋에서 이어서 진행), 원격·로컬 태그 v<version> 이 있으면 HEAD 를 가리킴, Package.swift 가 prepare.sh 가
#     쓴 그대로이고 url 이 v<version>, 보관한 zip 이 있고 .sha256 = Package.swift checksum = zip 체크섬, 지금 소스가 zip 을
#     빌드한 소스와 같음(Package.swift 만 다름), gh 로그인
#   단계 (다시 실행하면 끝난 단계는 건너뛴다):
#     1) tools/ci/verify.sh   이 커밋과 이 zip 으로 아직 통과한 적이 없을 때만 (build/release/v<version>/verified)
#     2) 태그 v<version>      원격에 없으면 git tag -a 와 git push origin refs/tags/v<version> (태그만, main 은 푸시하지 않는다)
#     3) GitHub Release       없으면 draft 로 만들고(gh release create --draft --verify-tag), zip 이 없으면 올리고, 올라간 zip 을
#                             내려받아 체크섬을 확인한 뒤에야 정식 릴리스로 게시한다(draft·prerelease 해제). 체크섬이 다른 자산은
#                             바꾸지 않고 멈춘다. publish.sh 가 쓰지 않은(노트가 없는) Release 도 멈춘다.
#     4) Package.swift 의 url(공개 URL)에서 zip 을 내려받아 체크섬 확인
#     5) tools/release/check-consumer-ios.sh --remote <version>
#   끝에 RELEASED v<version> 을 출력한다. gh 인증만 쓰고 토큰을 출력하지 않는다.
set -euo pipefail

SCRIPT_NAME="publish"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
# shellcheck source=tools/release/release-lib.sh
. "$ROOT/tools/release/release-lib.sh"

if [ $# -ne 1 ]; then
  echo "usage: tools/release/publish.sh <version>   (X.Y.Z, for example 1.0.0)" >&2
  exit 2
fi
version="$1"
check_version "$version"
tag="v$version"
url="$(release_url "$version")"
archive="$(archive_dir "$version")"
kept_zip="$archive/$ZIP_NAME"
kept_sha="$kept_zip.sha256"
kept_tree="$archive/source-tree"
verified="$archive/verified"

WORK_TMP="$(mktemp -d)"
trap 'rm -rf "$WORK_TMP"' EXIT
export GH_PROMPT_DISABLED=1 GH_NO_UPDATE_NOTIFIER=1

# ---- GitHub Release 상태 ----
# gh release view 의 JSON → "<draft> <prerelease> <immutable> <노트 있음> <자산 상태|none>" (앞의 넷은 0/1)
release_fields() {
  python3 -I -c '
import json
import sys

d = json.load(sys.stdin)
body = d.get("body") or ""
notes_ok = "## Installation" in body and "## Real-vehicle check pending: #118" in body
states = [a.get("state") or "unknown" for a in d.get("assets") or [] if a.get("name") == sys.argv[1]]
print(int(bool(d.get("isDraft"))), int(bool(d.get("isPrerelease"))), int(bool(d.get("isImmutable"))), int(notes_ok),
      states[0] if states else "none")
' "$ZIP_NAME"
}

REL_EXISTS=0 REL_DRAFT=0 REL_PRERELEASE=0 REL_IMMUTABLE=0 REL_NOTES_OK=0 ASSET_STATE=none
release_state() {
  local out fields
  REL_EXISTS=0 REL_DRAFT=0 REL_PRERELEASE=0 REL_IMMUTABLE=0 REL_NOTES_OK=0 ASSET_STATE=none
  if ! out="$(gh release view "$tag" --repo "$GH_REPO" --json isDraft,isPrerelease,isImmutable,body,assets 2> "$WORK_TMP/gh-view.err")"; then
    case "$(cat "$WORK_TMP/gh-view.err")" in *"release not found"*) return 0 ;; esac
    die "gh release view $tag failed" "$(cat "$WORK_TMP/gh-view.err")"
  fi
  fields="$(printf '%s' "$out" | release_fields)" || die "cannot read the output of gh release view $tag" "$out"
  read -r REL_DRAFT REL_PRERELEASE REL_IMMUTABLE REL_NOTES_OK ASSET_STATE <<< "$fields"
  REL_EXISTS=1
}

# 자산이 틀렸을 때: 게시된 immutable Release 는 자산을 지우거나 바꿀 수 없고 태그도 다시 쓸 수 없다
broken_asset() { # $1 = 무엇이 틀렸나
  if [ "$REL_DRAFT" -eq 0 ] && [ "$REL_IMMUTABLE" -eq 1 ]; then
    die "$1; release $tag is immutable, so $version cannot be fixed: prepare and publish a new version"
  fi
  die "$1; delete that asset (gh release delete-asset $tag $ZIP_NAME --repo $GH_REPO --yes) and run publish.sh again"
}

check_tag_still_at_head() {
  local now
  now="$(remote_tag_commit "$tag")" || exit 1
  [ "$now" = "$head" ] || die "tag $tag on origin is now at '$now', not HEAD $(short "$head"): stopping"
}

write_release_notes() { # $1 = 파일
  sed -e "s|@VERSION@|$version|g" -e "s|@TAG@|$tag|g" -e "s|@REPO_URL@|$REPO_URL|g" -e "s|@CHECKSUM@|$checksum|g" > "$1" << 'NOTES'
Teslable @VERSION@ for iOS: a Swift package (static XCFramework) that talks to Tesla vehicles over Bluetooth LE, with no server.

## Installation

In Xcode choose **File > Add Package Dependencies...**, enter `@REPO_URL@`, pick **Up to Next Major Version** from `@VERSION@`, and add the `Teslable` product to your app target. In a `Package.swift`:

```swift
.package(url: "@REPO_URL@", from: "@VERSION@")
```

- iOS 16 or later. The simulator slice is arm64 only (Apple Silicon Mac).
- CocoaPods is not supported. Android: build from source for now (Maven Central comes later).
- Installation guide: @REPO_URL@/blob/@TAG@/documents/md/11-lib-overview.md#installation

## Real-vehicle check pending: #118

Some features pass the fake-vehicle tests but are not confirmed on a real car yet, notably the Infotainment commands and `getState`. The checklist is @REPO_URL@/issues/118. Android has not been checked on a real device yet.

## Checksum

SwiftPM checksum of `Teslable.xcframework.zip`: `@CHECKSUM@`
NOTES
}

# 게시 직후에는 공개 URL 이 잠시 404 일 수 있다. curl --retry 는 404 를 다시 받지 않으므로 정한 횟수만큼 새 파일로 다시 받는다.
download_public_zip() { # $1 = 저장할 파일
  local attempt=1 tries=10 pause=6 part
  while :; do
    part="$WORK_TMP/public-attempt-$attempt"
    if curl --fail --silent --show-error --location --output "$part" "$url" 2> "$WORK_TMP/curl.err"; then
      mv "$part" "$1"
      return 0
    fi
    [ "$attempt" -lt "$tries" ] || die "cannot download $url after $tries attempts" "$(cat "$WORK_TMP/curl.err")"
    note "download attempt $attempt of $tries failed: $(head -n 1 "$WORK_TMP/curl.err"); trying again in ${pause}s"
    attempt=$((attempt + 1))
    sleep "$pause"
  done
}

# ---- 사전 검사 ----
check_origin
check_clean_tree
fetch_origin
remote_commit="$(remote_tag_commit "$tag")" || exit 1
check_head_is_origin_main "$remote_commit"
head="$(git rev-parse HEAD)"
if [ -n "$remote_commit" ] && [ "$remote_commit" != "$head" ]; then
  die "tag $tag already exists on origin at $(short "$remote_commit"), not at HEAD $(short "$head"): a published tag must not move; to finish that release, run publish.sh from its commit (git fetch origin tag $tag && git switch --detach $tag)"
fi
local_commit="$(git rev-parse -q --verify "refs/tags/$tag^{commit}" || true)"
if [ -n "$local_commit" ] && [ "$local_commit" != "$head" ]; then
  die "local tag $tag points at $(short "$local_commit"), not HEAD $(short "$head"): delete it (git tag -d $tag) and run again"
fi

[ -f "$PACKAGE_FILE" ] || die "Package.swift missing: merge the release/$tag PR from tools/release/prepare.sh $version first"
urls="$(package_values url "$PACKAGE_FILE")"
[ "$urls" = "$url" ] ||
  die "Package.swift version mismatch: url is '$(printf '%s' "$urls" | tr '\n' ' ')', expected $url (merge the release/$tag PR first)"
checksum="$(package_values checksum "$PACKAGE_FILE")"
is_checksum "$checksum" ||
  die "Package.swift has no valid checksum (64 lowercase hex characters): '$(printf '%s' "$checksum" | tr '\n' ' ')'"
render_package_swift "$version" "$checksum" > "$WORK_TMP/expected-$PACKAGE_FILE"
cmp -s "$WORK_TMP/expected-$PACKAGE_FILE" "$PACKAGE_FILE" ||
  die "Package.swift is not the file prepare.sh writes for $version: run tools/release/prepare.sh again instead of editing it" \
    "$(diff -u "$WORK_TMP/expected-$PACKAGE_FILE" "$PACKAGE_FILE" || true)"

[ -f "$kept_zip" ] ||
  die "zip missing: run prepare.sh on this machine ($kept_zip must be the zip whose checksum is in Package.swift; publish.sh never builds one)"
[ -f "$kept_sha" ] && [ -f "$kept_tree" ] ||
  die "prepared files incomplete: run prepare.sh on this machine ($archive/ needs $ZIP_NAME.sha256 and source-tree next to the zip)"
kept_sum="$(cat "$kept_sha")"
[ "$kept_sum" = "$checksum" ] ||
  die "checksum mismatch: do not rebuild the zip ($kept_sha says $kept_sum, Package.swift says $checksum)" \
    "If the prepared zip is gone, run tools/release/prepare.sh $version again and merge its new Package.swift."
zip_sum="$(compute_checksum "$kept_zip")" || exit 1
[ "$zip_sum" = "$checksum" ] ||
  die "checksum mismatch: do not rebuild the zip ($kept_zip hashes to $zip_sum, Package.swift says $checksum)"

# 태그는 zip 을 빌드한 소스를 가리켜야 한다. rebase merge 는 커밋을 새로 만들므로 SHA 가 아니라 tree 를 비교한다 (Package.swift 만 빼고).
source_tree="$(cat "$kept_tree")"
git cat-file -e "$source_tree^{tree}" 2> /dev/null || die "$kept_tree names tree '$source_tree', which this clone does not have"
changed="$(git diff --name-only "$source_tree" 'HEAD^{tree}' -- ":(top,exclude)$PACKAGE_FILE")" || die "git diff against the prepared source failed"
[ -z "$changed" ] ||
  die "the source changed since prepare.sh built the zip, so tag $tag would not mark the source of the binary: delete $archive/, run tools/release/prepare.sh $version again and merge its Package.swift" "$changed"

command -v gh > /dev/null 2>&1 || die "gh not found (brew install gh, then gh auth login)"
command -v curl > /dev/null 2>&1 || die "curl not found"
gh auth status --hostname github.com > /dev/null 2>&1 || die "gh is not logged in to github.com: run gh auth login"

# ---- 1) 로컬 게이트 ----
if [ -f "$verified" ] && [ "$(cat "$verified")" = "$head $checksum" ]; then
  note "skipping tools/ci/verify.sh: it already passed for HEAD $(short "$head") and this zip"
else
  note "tools/ci/verify.sh: the local gate before the tag (several minutes)"
  "$ROOT/tools/ci/verify.sh" || die "tools/ci/verify.sh failed (see above); nothing was published"
  printf '%s %s\n' "$head" "$checksum" > "$verified"
  # 게이트가 도는 동안 원격이 바뀌었을 수 있다
  fetch_origin
  git merge-base --is-ancestor "$head" refs/remotes/origin/main ||
    die "origin/main no longer contains HEAD $(short "$head") (it changed during tools/ci/verify.sh); nothing was published"
  remote_commit="$(remote_tag_commit "$tag")" || exit 1
  [ -z "$remote_commit" ] || [ "$remote_commit" = "$head" ] ||
    die "tag $tag appeared on origin at $(short "$remote_commit") during tools/ci/verify.sh; nothing was published by this run"
fi

# ---- 2) 태그 ----
if [ "$remote_commit" = "$head" ]; then
  note "tag $tag is already on origin at HEAD $(short "$head")"
else
  if [ -z "$local_commit" ]; then
    git tag -a "$tag" -m "Teslable $version" || die "git tag -a $tag failed"
  fi
  note "git push origin refs/tags/$tag (the tag only; main is never pushed)"
  git push --quiet origin "refs/tags/$tag" || die "git push origin refs/tags/$tag failed; run publish.sh again"
  check_tag_still_at_head
fi

# ---- 3) GitHub Release ----
# 보관한 zip 을 임시 폴더로 복사하고 그 복사본의 체크섬을 확인한다: 확인한 바이트를 그대로 올린다.
upload="$WORK_TMP/upload/$ZIP_NAME"
mkdir -p "$WORK_TMP/upload"
cp "$kept_zip" "$upload"
up_sum="$(compute_checksum "$upload")" || exit 1
[ "$up_sum" = "$checksum" ] ||
  die "checksum mismatch: do not rebuild the zip ($kept_zip changed during this run and hashes to $up_sum)"

release_state
if [ "$REL_EXISTS" -eq 0 ]; then
  write_release_notes "$WORK_TMP/notes.md"
  note "gh release create $tag --draft (it is published only after the uploaded zip is checked)"
  gh release create "$tag" --repo "$GH_REPO" --draft --verify-tag --title "$version" --notes-file "$WORK_TMP/notes.md" ||
    die "gh release create $tag failed (see above); run publish.sh again, finished steps are skipped"
  release_state
  [ "$REL_EXISTS" -eq 1 ] || die "gh release create $tag returned, but gh release view does not find the release"
elif [ "$REL_NOTES_OK" -eq 0 ]; then
  die "release $tag exists, but its notes lack the '## Installation' and '## Real-vehicle check pending: #118' sections, so publish.sh did not make it: fix its notes or delete it, then run again"
fi

if [ "$ASSET_STATE" = none ]; then
  [ "$REL_DRAFT" -eq 1 ] || [ "$REL_IMMUTABLE" -eq 0 ] || broken_asset "release $tag has no $ZIP_NAME"
  note "gh release upload $tag $ZIP_NAME"
  gh release upload "$tag" "$upload" --repo "$GH_REPO" || die "gh release upload $tag failed (see above); run publish.sh again"
elif [ "$ASSET_STATE" != uploaded ]; then
  broken_asset "release $tag has $ZIP_NAME in state '$ASSET_STATE' (an upload broke off)"
fi

# 올라간 자산을 내려받아(draft 도 된다) 체크섬을 확인한다. 게시하기 전에 한다: 게시한 뒤에는 바꿀 수 없을 수 있다(immutable).
mkdir -p "$WORK_TMP/asset"
gh release download "$tag" --repo "$GH_REPO" --pattern "$ZIP_NAME" --dir "$WORK_TMP/asset" ||
  die "gh release download $tag failed (see above); run publish.sh again"
asset_sum="$(compute_checksum "$WORK_TMP/asset/$ZIP_NAME")" || exit 1
[ "$asset_sum" = "$checksum" ] ||
  broken_asset "asset checksum differs: release $tag has $ZIP_NAME with $asset_sum, Package.swift says $checksum"

if [ "$REL_DRAFT" -eq 1 ] || [ "$REL_PRERELEASE" -eq 1 ]; then
  check_tag_still_at_head
  note "gh release edit $tag --draft=false --prerelease=false (publishing it as a full release)"
  gh release edit "$tag" --repo "$GH_REPO" --draft=false --prerelease=false ||
    die "gh release edit $tag failed (see above); run publish.sh again"
fi
release_state
[ "$REL_EXISTS" -eq 1 ] && [ "$REL_DRAFT" -eq 0 ] && [ "$REL_PRERELEASE" -eq 0 ] && [ "$ASSET_STATE" = uploaded ] ||
  die "release $tag is not a full release with $ZIP_NAME (draft=$REL_DRAFT prerelease=$REL_PRERELEASE asset=$ASSET_STATE); run publish.sh again"

# ---- 4) 공개 URL ----
note "downloading $url (the url in Package.swift)"
download_public_zip "$WORK_TMP/public-$ZIP_NAME"
public_sum="$(compute_checksum "$WORK_TMP/public-$ZIP_NAME")" || exit 1
[ "$public_sum" = "$checksum" ] ||
  broken_asset "the zip at $url hashes to $public_sum, Package.swift says $checksum"
note "the public zip matches Package.swift ($checksum)"

# ---- 5) 게시된 패키지로 소비자 앱 ----
note "tools/release/check-consumer-ios.sh --remote $version"
"$ROOT/tools/release/check-consumer-ios.sh" --remote "$version" ||
  die "tools/release/check-consumer-ios.sh --remote $version failed (see above); the release is public: find the cause, then run publish.sh again to repeat the checks"
check_tag_still_at_head
echo "RELEASED $tag"
