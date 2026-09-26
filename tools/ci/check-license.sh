#!/usr/bin/env bash
# tools/ci/check-license.sh — NOTICE/LICENSE 존재와 포팅 파일 헤더 확인 (NFR-008)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
test -f LICENSE || { echo "LICENSE missing"; exit 1; }
test -f NOTICE || { echo "NOTICE missing"; exit 1; }
grep -q "vehicle-command" NOTICE || { echo "NOTICE must credit vehicle-command"; exit 1; }
status=0
# Go 원본을 포팅한 파일은 헤더로 표시한다. 'Go 원본:' KDoc 참조가 있는데 헤더가 없으면 실패.
while IFS= read -r file; do
  if ! head -3 "$file" | grep -q "Ported from vehicle-command@"; then
    echo "missing ported-from header: $file"; status=1
  fi
done < <(grep -rl --include='*.kt' -E 'Go 원본: |Ported from' --exclude-dir=build --exclude-dir=reference . | grep -v '/build/' | xargs grep -L "Ported from vehicle-command@" || true)
exit $status
