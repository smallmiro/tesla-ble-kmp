#!/usr/bin/env bash
# tools/ci/sync-manual-root-index.sh — 문서 세트마다 <set>/html/index.html 을 en/index.html 에서 다시 만든다
#   세트: documents/html (개발 매뉴얼), samples/documents/html (샘플 앱 스펙)
#   루트 진입점은 영어 홈이다(href·src 를 한 단계 위 경로로 바꾼다). en/index.html 을 고친 뒤 이 스크립트를 돌리고 함께 커밋한다.
#   check-manual-i18n.sh 가 두 파일이 어긋나면 실패시킨다.
set -euo pipefail
ROOT="${1:-$(cd "$(dirname "$0")/../.." && pwd)}"
python3 -I - "$ROOT" <<'PY'
import pathlib, re, sys

root = pathlib.Path(sys.argv[1])
# check-manual-i18n.sh, highlight-manual-code.sh 의 DOC_SETS 와 같아야 한다.
DOC_SETS = [("documents/md", "documents/html"), ("samples/documents/md", "samples/documents/html")]

# check-manual-i18n.sh 의 to_root 와 같아야 한다.
def to_root(h):
    if h.startswith("../"):
        return h[3:]
    if "://" in h or h.startswith(("#", "mailto:")):
        return h
    return "en/" + h

for _, html_rel in DOC_SETS:
    html_dir = root / html_rel
    en = (html_dir / "en/index.html").read_text(encoding="utf-8")
    home = re.sub(r'\b(href|src)="([^"]+)"', lambda m: f'{m.group(1)}="{to_root(m.group(2))}"', en)
    (html_dir / "index.html").write_text(home, encoding="utf-8")
    print(f"{html_rel}/index.html regenerated from en/index.html")
PY
