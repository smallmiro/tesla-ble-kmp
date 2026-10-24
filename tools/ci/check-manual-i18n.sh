#!/usr/bin/env bash
# tools/ci/check-manual-i18n.sh — 개발 매뉴얼 언어 규칙 검사 (AGENTS.md §3 규칙 12)
#   문서 세트(DOC_SETS)마다 아래를 검사한다: 개발 매뉴얼 documents/, 샘플 앱 스펙 samples/documents/
#   - <set>/md/ 에 한글이 없다 (에이전트용 MD는 영어로만)
#   - <set>/html/{ko,en,ja}/ 의 파일 목록이 같다
#   - 페이지마다 <html lang> 이 폴더 언어와 같고, id 집합과 href 순서가 ko/ 와 같다
#     (언어 전환 링크의 class="active" 위치만 다를 수 있다)
#   - 페이지마다 태그 이름별 개수가 ko/ 와 같다 (문단·목록·표 행 누락 탐지. 어순 때문에 순서는 비교하지 않는다)
#   - en/, ja/ 에는 언어 전환 링크의 "한국어" 라벨 말고는 한글이 없다
#   - <set>/html/index.html 은 en/index.html 을 한 단계 위 경로로 옮긴 영어 홈이다
#     (en/index.html 의 href·src를 루트 기준으로 바꾼 결과와 바이트 단위로 같다)
#   - style.css 의 라이트 테마 토큰 두 벌(시스템 설정용, 직접 선택용)이 같다
#   - 샘플 세트의 assets/ 파일은 documents/html/assets/ 의 같은 이름 파일과 바이트 단위로 같다
#   - 코드 블록 문법 색이 최신이다 (highlight-manual-code.sh --check)
# 인자로 저장소 루트를 넘기면 그 경로를 검사한다(검사 스크립트 자체를 시험할 때).
set -euo pipefail
ROOT="${1:-$(cd "$(dirname "$0")/../.." && pwd)}"
python3 -I - "$ROOT" <<'PY'
import collections, pathlib, re, sys

root = pathlib.Path(sys.argv[1])
# sync-manual-root-index.sh, highlight-manual-code.sh 의 DOC_SETS 와 같아야 한다.
DOC_SETS = [("documents/md", "documents/html"), ("samples/documents/md", "samples/documents/html")]
MANUAL_ASSETS = root / "documents/html/assets"
hangul = re.compile("[ᄀ-ᇿ㄰-㆏가-힣]")  # 한글 자모, 호환 자모, 음절
langs = ["ko", "en", "ja"]
errors = []
summary = []

def shape(text):
    ids = sorted(re.findall(r'\bid="([^"]+)"', text))
    hrefs = re.findall(r'\bhref="([^"]+)"', text)
    tags = collections.Counter(re.findall(r"<([a-zA-Z][a-zA-Z0-9]*)", text))
    return ids, hrefs, tags

# sync-manual-root-index.sh 의 to_root 와 같아야 한다.
def to_root(h):
    if h.startswith("../"):
        return h[3:]
    if "://" in h or h.startswith(("#", "mailto:")):
        return h
    return "en/" + h

def check_set(md_rel, html_rel):
    md_dir = root / md_rel
    html_dir = root / html_rel

    # 폴더가 옮겨졌거나 경로가 틀리면 아무것도 검사하지 않고 통과하는 일을 막는다.
    for d in [md_dir] + [html_dir / lang for lang in langs]:
        if not d.is_dir():
            errors.append(f"{d.relative_to(root)}: directory missing")
            return
    if not any(md_dir.glob("*.md")) or not any((html_dir / "ko").glob("*.html")):
        errors.append(f"{md_rel}, {html_rel}/ko: no manual pages found")
        return

    for p in sorted(md_dir.glob("*.md")):
        for n, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
            if hangul.search(line):
                errors.append(f"{p.relative_to(root)}:{n}: Hangul in agent manual (must be English)")

    pages = {lang: sorted(x.name for x in (html_dir / lang).glob("*.html")) for lang in langs}
    for lang in langs[1:]:
        if pages[lang] != pages["ko"]:
            missing = sorted(set(pages["ko"]) - set(pages[lang]))
            extra = sorted(set(pages[lang]) - set(pages["ko"]))
            errors.append(f"{html_rel}/{lang}/: page list differs from ko/ (missing {missing}, extra {extra})")

    for name in pages["ko"]:
        base_ids, base_hrefs, base_tags = shape((html_dir / "ko" / name).read_text(encoding="utf-8"))
        for lang in langs:
            p = html_dir / lang / name
            if not p.exists():
                continue
            text = p.read_text(encoding="utf-8")
            rel = p.relative_to(root)
            if f'<html lang="{lang}">' not in text:
                errors.append(f"{rel}: <html lang> must be \"{lang}\"")
            if lang == "ko":
                continue
            ids, hrefs, tags = shape(text)
            if ids != base_ids:
                errors.append(f"{rel}: id set differs from ko/{name}")
            if hrefs != base_hrefs:
                errors.append(f"{rel}: href list differs from ko/{name}")
            if tags != base_tags:
                diff = {t: (base_tags[t], tags[t]) for t in set(base_tags) | set(tags) if base_tags[t] != tags[t]}
                errors.append(f"{rel}: tag counts differ from ko/{name} (ko, {lang}): {diff}")
            for n, line in enumerate(text.splitlines(), 1):
                if hangul.search(line.replace("한국어", "")):
                    errors.append(f"{rel}:{n}: Hangul outside the language switcher")

    # 라이트 토큰: @media (prefers-color-scheme: light) 안의 블록과 :root[data-theme="light"] 블록
    css_path = html_dir / "assets/style.css"
    if not css_path.exists():
        errors.append(f"{html_rel}/assets/style.css: missing")
    else:
        css = css_path.read_text(encoding="utf-8")
        m_sys = re.search(r'@media \(prefers-color-scheme: light\) \{\n  :root:not\(\[data-theme="dark"\]\) \{\n(.*?)\n  \}\n\}', css, re.S)
        m_pick = re.search(r':root\[data-theme="light"\] \{\n(.*?)\n\}', css, re.S)
        if not (m_sys and m_pick):
            errors.append(f"{html_rel}/assets/style.css: light theme token blocks not found")
        elif [l.strip() for l in m_sys.group(1).splitlines()] != [l.strip() for l in m_pick.group(1).splitlines()]:
            errors.append(f"{html_rel}/assets/style.css: the two light theme token blocks differ")

    # 매뉴얼이 아닌 세트는 매뉴얼의 assets/ 를 그대로 쓴다(테마를 한쪽만 고치는 일을 막는다).
    assets = html_dir / "assets"
    if assets != MANUAL_ASSETS:
        theirs = sorted(x.name for x in MANUAL_ASSETS.iterdir() if x.is_file())
        ours = sorted(x.name for x in assets.iterdir() if x.is_file()) if assets.is_dir() else []
        if ours != theirs or any((assets / n).read_bytes() != (MANUAL_ASSETS / n).read_bytes() for n in ours):
            errors.append(f"{html_rel}/assets: differs from documents/html/assets (copy the manual's assets)")

    root_index = html_dir / "index.html"
    if not root_index.exists():
        errors.append(f"{html_rel}/index.html: missing (English home)")
    else:
        en = (html_dir / "en/index.html").read_text(encoding="utf-8")
        expected = re.sub(r'\b(href|src)="([^"]+)"', lambda m: f'{m.group(1)}="{to_root(m.group(2))}"', en)
        if root_index.read_text(encoding="utf-8") != expected:
            errors.append(f"{html_rel}/index.html: out of sync with en/index.html (run tools/ci/sync-manual-root-index.sh)")

    summary.append(f"{html_rel}: {len(pages['ko'])} pages")

for md_rel, html_rel in DOC_SETS:
    check_set(md_rel, html_rel)

for e in errors:
    print(e)
if errors:
    print(f"manual i18n check FAILED: {len(errors)} problem(s) (AGENTS.md §3 rule 12)")
    sys.exit(1)
print(f"manual i18n OK: md English-only, HTML ko/en/ja match, root index mirrors en/ ({'; '.join(summary)})")
PY
"$(dirname "$0")/highlight-manual-code.sh" --check "$ROOT"
