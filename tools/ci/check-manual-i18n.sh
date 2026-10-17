#!/usr/bin/env bash
# tools/ci/check-manual-i18n.sh — 개발 매뉴얼 언어 규칙 검사 (AGENTS.md §3 규칙 12)
#   - documents/md/ 에 한글이 없다 (에이전트용 MD는 영어로만)
#   - documents/html/{ko,en,ja}/ 의 파일 목록이 같다
#   - 페이지마다 <html lang> 이 폴더 언어와 같고, id 집합과 href 순서가 ko/ 와 같다
#     (언어 전환 링크의 class="active" 위치만 다를 수 있다)
#   - en/, ja/ 에는 언어 전환 링크의 "한국어" 라벨 말고는 한글이 없다
# 인자로 저장소 루트를 넘기면 그 경로를 검사한다(검사 스크립트 자체를 시험할 때).
set -euo pipefail
ROOT="${1:-$(cd "$(dirname "$0")/../.." && pwd)}"
python3 -I - "$ROOT" <<'PY'
import pathlib, re, sys

root = pathlib.Path(sys.argv[1])
md_dir = root / "documents/md"
html_dir = root / "documents/html"
hangul = re.compile("[ᄀ-ᇿ㄰-㆏가-힣]")
langs = ["ko", "en", "ja"]
errors = []

for p in sorted(md_dir.glob("*.md")):
    for n, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
        if hangul.search(line):
            errors.append(f"{p.relative_to(root)}:{n}: Hangul in agent manual (must be English)")

pages = {lang: sorted(x.name for x in (html_dir / lang).glob("*.html")) for lang in langs}
for lang in langs[1:]:
    if pages[lang] != pages["ko"]:
        missing = sorted(set(pages["ko"]) - set(pages[lang]))
        extra = sorted(set(pages[lang]) - set(pages["ko"]))
        errors.append(f"documents/html/{lang}/: page list differs from ko/ (missing {missing}, extra {extra})")

def shape(text):
    ids = sorted(re.findall(r'\bid="([^"]+)"', text))
    hrefs = re.findall(r'\bhref="([^"]+)"', text)
    return ids, hrefs

for name in pages["ko"]:
    base_ids, base_hrefs = shape((html_dir / "ko" / name).read_text(encoding="utf-8"))
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
        ids, hrefs = shape(text)
        if ids != base_ids:
            errors.append(f"{rel}: id set differs from ko/{name}")
        if hrefs != base_hrefs:
            errors.append(f"{rel}: href list differs from ko/{name}")
        for n, line in enumerate(text.splitlines(), 1):
            if hangul.search(line.replace("한국어", "")):
                errors.append(f"{rel}:{n}: Hangul outside the language switcher")

for e in errors:
    print(e)
if errors:
    print(f"manual i18n check FAILED: {len(errors)} problem(s) (AGENTS.md §3 rule 12)")
    sys.exit(1)
print(f"manual i18n OK: md English-only, {len(pages['ko'])} HTML pages in ko/en/ja match")
PY
