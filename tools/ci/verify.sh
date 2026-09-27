#!/usr/bin/env bash
# tools/ci/verify.sh — 로컬 검증 게이트 (ADR-0012). 이 Mac 한 대에서 예전 GitHub Actions
# .github/workflows/ci.yml의 6개 잡을 순서대로 전부 재현한다. main에 푸시하거나 PR을 병합하기
# 전에 이 스크립트가 통과해야 한다(workflow.md §5.3/§6, AGENTS.md §3 규칙 3).
#
# 단계 → 대체하는 CI 잡
#   (a) Gradle 한 번 호출          → lint, jvm-test, android, ios 잡의 Gradle 부분
#   (b) 병합 매니페스트 INTERNET 검사 → android 잡의 "Merged manifest must not request INTERNET (NFR-014)" 스텝
#   (c) MinimumOSVersion 16.0 검사  → ios 잡의 "Teslable.framework must declare MinimumOSVersion 16.0 (NFR-009)" 스텝
#   (d) xcodegen + xcodebuild      → ios 잡의 샘플 앱 빌드 스텝
#   (e) tools/ci/check-license.sh  → license 잡
#   (f) tools/ci/scan-secrets.sh   → secrets 잡
set -euo pipefail

if [ "$(uname -s)" != "Darwin" ]; then
  echo "tools/ci/verify.sh는 macOS에서만 실행할 수 있습니다 (iOS 시뮬레이터 테스트, xcodebuild, plutil 필요)." >&2
  exit 1
fi

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"

TOTAL_STEPS=6
STEP=0
START_TIME=$(date +%s)

step() {
  STEP=$((STEP + 1))
  echo "==> [$STEP/$TOTAL_STEPS] $1"
}

fail() {
  echo "local gate FAILED at step $STEP/$TOTAL_STEPS: $1" >&2
  exit 1
}

# (a) ci.yml: lint, jvm-test, android, ios 잡의 Gradle 스텝을 한 번의 호출로.
step "gradle: check lintKotlin detekt forbiddenTokens jvmTest testAndroidHostTest :samples:android:assembleDebug iosSimulatorArm64Test apiCheck :sdk:linkDebugFrameworkIosSimulatorArm64"
./gradlew check lintKotlin detekt forbiddenTokens jvmTest testAndroidHostTest \
  :samples:android:assembleDebug iosSimulatorArm64Test apiCheck \
  :sdk:linkDebugFrameworkIosSimulatorArm64 --console=plain \
  || fail "gradle"

# (b) ci.yml android 잡 — 병합 매니페스트에 INTERNET 권한이 없어야 한다 (NFR-014).
step "merged manifest must not request INTERNET (NFR-014)"
manifest_dir=samples/android/build/intermediates
merged=$(find "$manifest_dir" -name AndroidManifest.xml -path '*merged_manifest*')
if [ -z "$merged" ]; then
  echo "No merged AndroidManifest.xml under $manifest_dir; the check would pass vacuously" >&2
  fail "merged manifest INTERNET check"
fi
if grep -rl --include=AndroidManifest.xml 'android.permission.INTERNET' "$manifest_dir"; then
  echo "android.permission.INTERNET is in the manifest(s) listed above (NFR-014: no network)" >&2
  fail "merged manifest INTERNET check"
fi
echo "No INTERNET permission in the merged manifests"

# (c) ci.yml ios 잡 — Teslable.framework는 MinimumOSVersion 16.0을 선언해야 한다 (NFR-009).
step "Teslable.framework must declare MinimumOSVersion 16.0 (NFR-009)"
plist=sdk/build/bin/iosSimulatorArm64/debugFramework/Teslable.framework/Info.plist
min="$(plutil -extract MinimumOSVersion raw -o - "$plist")"
echo "MinimumOSVersion=$min"
if [ "$min" != "16.0" ]; then
  echo "Expected MinimumOSVersion 16.0 in $plist, got '$min'" >&2
  fail "MinimumOSVersion check"
fi

# (d) ci.yml ios 잡 — 샘플 iOS 앱이 빌드되는지 확인 (CODE_SIGNING_ALLOWED=NO).
step "xcodegen generate && xcodebuild samples/ios (CODE_SIGNING_ALLOWED=NO)"
(
  cd samples/ios
  xcodegen generate
  xcodebuild -project TeslableSample.xcodeproj -scheme TeslableSample \
    -destination 'generic/platform=iOS Simulator' -configuration Debug \
    CODE_SIGNING_ALLOWED=NO build -quiet
) || fail "xcodebuild samples/ios"

# (e) ci.yml license 잡.
step "tools/ci/check-license.sh"
tools/ci/check-license.sh || fail "check-license.sh"

# (f) ci.yml secrets 잡.
step "tools/ci/scan-secrets.sh"
tools/ci/scan-secrets.sh || fail "scan-secrets.sh"

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))
echo "local gate: all $TOTAL_STEPS steps passed in ${ELAPSED}s"
