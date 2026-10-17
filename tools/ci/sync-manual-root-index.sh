#!/usr/bin/env bash
# tools/ci/sync-manual-root-index.sh — documents/html/index.html 을 en/index.html 에서 다시 만든다
#   루트 진입점은 영어 홈이다. en/index.html 을 고친 뒤 이 스크립트를 돌리고 함께 커밋한다.
#   check-manual-i18n.sh 가 두 파일이 어긋나면 실패시킨다.
set -euo pipefail
ROOT="${1:-$(cd "$(dirname "$0")/../.." && pwd)}"
python3 -I - "$ROOT" <<'PY'
import pathlib, re, sys

html_dir = pathlib.Path(sys.argv[1]) / "documents/html"

# check-manual-i18n.sh 의 to_root 와 같아야 한다.
def to_root(h):
    if h.startswith("../"):
        return h[3:]
    if "://" in h or h.startswith(("#", "mailto:")):
        return h
    return "en/" + h

en = (html_dir / "en/index.html").read_text(encoding="utf-8")
root = re.sub(r'\bhref="([^"]+)"', lambda m: f'href="{to_root(m.group(1))}"', en)
(html_dir / "index.html").write_text(root, encoding="utf-8")
print("documents/html/index.html regenerated from en/index.html")
PY
