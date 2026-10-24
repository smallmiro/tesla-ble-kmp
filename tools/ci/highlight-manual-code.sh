#!/usr/bin/env bash
# tools/ci/highlight-manual-code.sh — HTML 매뉴얼 코드 블록에 문법 색을 입힌다
#   매뉴얼은 외부 리소스를 쓰지 않고 스크립트도 테마 전환(assets/theme.js)뿐이므로, 색은 미리 <span class="..."> 로 넣어 둔다.
#   대상: 문서 세트(documents/html, samples/documents/html)마다 {ko,en,ja}/*.html 와 index.html 의 <pre><code class="language-X"> (bash, go, json, kotlin, python, swift, xml, yaml)
#   다시 돌려도 결과가 같다(기존 span을 지우고 다시 칠한다). 코드 블록을 고친 뒤 돌리고 함께 커밋한다.
#   --check: 파일을 바꾸지 않고, 칠하지 않은 블록이 있으면 실패한다(check-manual-i18n.sh 가 부른다).
#   클래스: c 주석, k 키워드, s 문자열, n 숫자·상수, f 함수·명령, t 타입, v 변수, p 키·플래그, o 연산자
set -euo pipefail
MODE=write
if [ "${1:-}" = "--check" ]; then MODE=check; shift; fi
ROOT="${1:-$(cd "$(dirname "$0")/../.." && pwd)}"
python3 -I - "$ROOT" "$MODE" <<'PY'
import html, pathlib, re, sys

root = pathlib.Path(sys.argv[1])
mode = sys.argv[2]
# check-manual-i18n.sh, sync-manual-root-index.sh 의 DOC_SETS 와 같아야 한다.
DOC_SETS = [("documents/md", "documents/html"), ("samples/documents/md", "samples/documents/html")]

def words(*ws):
    return r"\b(?:" + "|".join(ws) + r")\b"

STR_DQ = r'"(?:\\.|[^"\\\n])*"'
STR_SQ = r"'(?:\\.|[^'\\\n])*'"

GO = re.compile("|".join([
    r"(?P<c>//[^\n]*|/\*.*?\*/)",
    rf"(?P<s>{STR_DQ}|`[^`]*`|'(?:\\.|[^'\\\n])+')",
    "(?P<k>" + words("break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for",
                     "func", "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select",
                     "struct", "switch", "type", "var") + ")",
    "(?P<t>" + words("any", "bool", "byte", "complex64", "complex128", "error", "float32", "float64", "int", "int8",
                     "int16", "int32", "int64", "rune", "string", "uint", "uint8", "uint16", "uint32", "uint64",
                     "uintptr") + ")",
    r"(?P<n>" + words("true", "false", "nil", "iota") + r"|\b0[xX][0-9a-fA-F_]+\b|\b\d[\d_]*(?:\.\d+)?\b)",
    r"(?P<f>\b[A-Za-z_]\w*(?=\())",
    r"(?P<o>:=|<-|&&|\|\||==|!=)",
]), re.S)

BASH_KW = {"if", "then", "else", "elif", "fi", "for", "do", "done", "while", "case", "esac", "in", "export",
           "function", "return", "sudo"}
BASH = re.compile("|".join([
    r"(?P<c>(?<![^\s])#[^\n]*)",
    rf"(?P<s>'[^'\n]*'|{STR_DQ})",
    r"(?P<v>\$\{[^}\n]*\}|\$[A-Za-z_]\w*|\$[0-9#?@*$]|(?<=export )[A-Za-z_]\w*(?==)|^[ \t]*[A-Za-z_]\w*(?==))",
    r"(?P<f>^[ \t]*[a-z][\w.+-]*|(?<=\| )[a-z][\w.+-]*|(?<=&& )[a-z][\w.+-]*|(?<=; )[a-z][\w.+-]*|(?<=\$\()[a-z][\w.+-]*|(?<=sudo )[a-z][\w.+-]*)",
    r"(?P<p>(?<![\w-])--?[A-Za-z][\w-]*)",
    r"(?P<o>&&|\|\||[|<>;])",
]), re.M)

JSON = re.compile("|".join([
    rf"(?P<p>{STR_DQ}(?=\s*:))",
    rf"(?P<s>{STR_DQ})",
    r"(?P<n>" + words("true", "false", "null") + r"|-?\b\d+(?:\.\d+)?(?:[eE][-+]?\d+)?\b)",
]))

PYTHON = re.compile("|".join([
    r"(?P<c>#[^\n]*)",
    rf"(?P<s>\b[bBrRfFuU]{{0,2}}(?:\"\"\".*?\"\"\"|'''.*?'''|{STR_DQ}|{STR_SQ}))",
    "(?P<k>" + words("and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
                     "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda",
                     "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield") + ")",
    r"(?P<n>" + words("True", "False", "None") + r"|\b0[xX][0-9a-fA-F]+\b|\b\d+(?:\.\d+)?\b)",
    r"(?P<f>\b[A-Za-z_]\w*(?=\())",
]), re.S)

YAML = re.compile("|".join([
    r"(?P<c>(?<![^\s])#[^\n]*)",
    r"(?P<p>^[ \t]*[\w.-]+(?=:(?:[ \t]|$)))",
    rf"(?P<s>{STR_DQ}|{STR_SQ})",
    r"(?P<n>" + words("true", "false", "null") + r")",
]), re.M)

KOTLIN = re.compile("|".join([
    r"(?P<c>//[^\n]*|/\*.*?\*/)",
    rf"(?P<s>\"\"\".*?\"\"\"|{STR_DQ}|'(?:\\.|[^'\\\n])+')",
    "(?P<k>" + words("as", "break", "class", "companion", "continue", "data", "do", "else", "enum", "finally", "for",
                     "fun", "if", "import", "in", "interface", "is", "object", "override", "package", "private",
                     "public", "return", "sealed", "suspend", "throw", "try", "val", "var", "when", "while") + ")",
    r"(?P<n>" + words("true", "false", "null", "this") + r"|\b0[xX][0-9a-fA-F_]+\b|\b\d[\d_]*(?:\.\d+)?[uUL]?\b)",
    r"(?P<f>\b[a-z_]\w*(?=\s*[({]))",
    r"(?P<t>\b[A-Z]\w*)",
    r"(?P<o>\?:|\?\.|!!|->|==|!=|&&|\|\|)",
]), re.S)

SWIFT = re.compile("|".join([
    r"(?P<c>//[^\n]*|/\*.*?\*/)",
    rf"(?P<s>{STR_DQ})",
    # function calls before keywords: TeslaBleConfig.companion.default(...) is a call
    r"(?P<f>\b[a-z_]\w*(?=\())",
    "(?P<k>" + words("as", "async", "await", "break", "case", "catch", "class", "continue", "default", "do", "else",
                     "enum", "for", "func", "guard", "if", "import", "in", "let", "private", "return", "self",
                     "struct", "switch", "throw", "throws", "try", "var", "where", "while") + ")",
    r"(?P<n>" + words("true", "false", "nil") + r"|\b0[xX][0-9a-fA-F_]+\b|\b\d[\d_]*(?:\.\d+)?\b)",
    r"(?P<t>\b[A-Z]\w*)",
    r"(?P<o>\?\?|->|==|!=|&&|\|\|)",
]), re.S)

XML = re.compile("|".join([
    r"(?P<c><!--.*?-->)",
    rf"(?P<s>{STR_DQ})",
    r"(?P<p>(?<=\s)[\w:.-]+(?==))",
    r"(?P<k></?[\w:.-]+|/?>)",
]), re.S)

LEXERS = {"bash": BASH, "go": GO, "json": JSON, "kotlin": KOTLIN, "python": PYTHON, "swift": SWIFT, "xml": XML,
          "yaml": YAML}

def paint(lang, code):
    lexer = LEXERS[lang]
    out, pos = [], 0
    for m in lexer.finditer(code):
        kind = m.lastgroup
        text = m.group()
        if lang == "bash" and kind == "f" and text.strip() in BASH_KW:
            kind = "k"
        lead = len(text) - len(text.lstrip(" \t")) if kind in ("f", "k", "v", "p") else 0
        out.append(html.escape(code[pos:m.start() + lead], quote=False))
        out.append(f'<span class="{kind}">{html.escape(text[lead:], quote=False)}</span>')
        pos = m.end()
    out.append(html.escape(code[pos:], quote=False))
    return "".join(out)

BLOCK = re.compile(r'(<pre><code class="language-(\w+)">)(.*?)(</code></pre>)', re.S)

def highlight(text):
    def one(m):
        lang = m.group(2)
        if lang not in LEXERS:
            return m.group(0)
        code = html.unescape(re.sub(r"</?span[^>]*>", "", m.group(3)))
        return m.group(1) + paint(lang, code) + m.group(4)
    return BLOCK.sub(one, text)

files = []
for _, html_rel in DOC_SETS:
    html_dir = root / html_rel
    if not html_dir.is_dir():
        print(f"{html_rel}: directory missing")
        sys.exit(1)
    files += sorted(html_dir.glob("*/*.html")) + sorted(html_dir.glob("index.html"))
stale = []
for p in files:
    text = p.read_text(encoding="utf-8")
    new = highlight(text)
    if new != text:
        stale.append(p.relative_to(root))
        if mode == "write":
            p.write_text(new, encoding="utf-8")
if mode == "check":
    for s in stale:
        print(f"{s}: code blocks not highlighted (run tools/ci/highlight-manual-code.sh)")
    if stale:
        sys.exit(1)
    print(f"manual code highlighting OK: {len(files)} pages")
else:
    print(f"highlighted {len(stale)} of {len(files)} pages")
PY
