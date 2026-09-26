#!/usr/bin/env bash
# tools/ci/scan-secrets.sh — VIN 패턴과 PEM 개인키 탐지 (workflow §6, NFR-006). 허용 목록: tools/ci/secret-allowlist.txt
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
ALLOW="tools/ci/secret-allowlist.txt"
status=0
# 17자 VIN 후보 (단어 단위 -w: GNU/BSD grep 공통. \b는 POSIX ERE가 아니다). -I: 바이너리 파일은 건너뛴다.
# 문서(documents/, HANDOFF.md, docs/)와 원본 클론은 제외
candidates=$(git ls-files | grep -v -E '^(documents/|docs/|HANDOFF\.md|reference/|vehicle-command/|tools/ci/secret-allowlist\.txt)' \
  | xargs grep -I -n -o -w -E '[A-HJ-NPR-Z0-9]{17}' 2>/dev/null || true)
while IFS= read -r line; do
  [ -z "$line" ] && continue
  vin="${line##*:}"
  if ! grep -q -x "$vin" "$ALLOW"; then echo "possible real VIN: $line"; status=1; fi
done <<< "$candidates"
# PEM 개인키
if git ls-files | grep -v -E '^(documents/|docs/|HANDOFF\.md|reference/|vehicle-command/)' | xargs grep -I -l -E 'BEGIN (EC |RSA )?PRIVATE KEY' 2>/dev/null; then
  echo "PEM private key found"; status=1
fi
exit $status
