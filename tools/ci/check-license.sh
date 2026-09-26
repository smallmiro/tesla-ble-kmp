#!/usr/bin/env bash
# tools/ci/check-license.sh — NOTICE/LICENSE 존재와 포팅 파일 헤더 확인 (NFR-008)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT"
test -f LICENSE || { echo "LICENSE missing"; exit 1; }
test -f NOTICE || { echo "NOTICE missing"; exit 1; }
grep -q "vehicle-command" NOTICE || { echo "NOTICE must credit vehicle-command"; exit 1; }

HEADER="Ported from vehicle-command@"
status=0

# (a) 명시적 목록 (권위 있는 소스): tools/ci/ported-files.txt 에 적힌 파일은 반드시 첫 3줄에
# "Ported from vehicle-command@<commit> <path> (Apache-2.0)" 헤더가 있어야 한다. '#' 주석과 빈 줄은 무시.
LIST="tools/ci/ported-files.txt"
test -f "$LIST" || { echo "$LIST missing"; exit 1; }
listed="$(sed -e 's/#.*//' -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' "$LIST" | grep -v '^$' || true)"

while IFS= read -r file; do
  [ -z "$file" ] && continue
  if [ ! -f "$file" ]; then
    echo "ported-files.txt lists a missing file: $file"; status=1
    continue
  fi
  if ! head -3 "$file" | grep -q "$HEADER"; then
    echo "missing ported-from header: $file"; status=1
  fi
done <<< "$listed"

# (b) 보조 휴리스틱: tools/ci/ported-files.txt 에 아직 없는 추적 파일 중 'Go 원본: ' 또는
# 'Ported from' 을 언급하는데 헤더가 없으면 실패한다(목록 드리프트 탐지). reference/ 는 학습용
# PoC 코드라 제외한다. git이 추적하는 *.kt 파일만 본다.
while IFS= read -r file; do
  case "$file" in
    reference/*) continue ;;
  esac
  if printf '%s\n' "$listed" | grep -qxF "$file"; then
    continue
  fi
  if grep -q -E 'Go 원본: |Ported from' "$file" 2>/dev/null; then
    if ! head -3 "$file" | grep -q "$HEADER"; then
      echo "missing ported-from header (not yet in ported-files.txt): $file"; status=1
    fi
  fi
done < <(git ls-files '*.kt')

exit $status
