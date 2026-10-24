"""tools/analysis/pbwire.py — 스키마 없는 protobuf wire 디코더 (로그 분석용, 표준 라이브러리만)

모니터 로그의 raw 헥사를 필드 트리로 펼친다. LEN 판정 순서는 documents/md/18-lib-troubleshooting.md 의 관찰 로그 상세 형식과
같다(단 그 형식의 필드 64개·256바이트 상한은 두지 않는다. 분석용이라 끝까지 푼다): LEN 값은 하위 메시지로 끝까지 풀리면 목록, 아니면 출력 가능한 ASCII면 "text:…", 아니면 "hex:…".
필드 이름은 만들지 않는다. 뜻은 분석 보고서에서 근거와 함께 다룬다.

사용: python3 -I tools/analysis/pbwire.py <hex>   → JSON
"""
import json
import sys

WIRE_NAMES = {0: "varint", 1: "fixed64", 2: "len", 5: "fixed32"}


class _Invalid(Exception):
    pass


def _varint(data, i):
    shift = 0
    value = 0
    while True:
        if i >= len(data) or shift > 63:
            raise _Invalid()
        b = data[i]
        i += 1
        value |= (b & 0x7F) << shift
        if not b & 0x80:
            return value, i
        shift += 7


def _fields(data, depth, max_depth, strict):
    """data 전체를 필드 목록으로 읽는다. strict 이면 잘못된 곳에서 _Invalid, 아니면 그 필드를 "invalid" 로 남기고 멈춘다."""
    out = []
    i = 0
    while i < len(data):
        try:
            key, i = _varint(data, i)
        except _Invalid:
            if strict:
                raise
            out.append({"field": 0, "wire": "varint", "value": "invalid"})
            break
        field, wire = key >> 3, key & 7
        if field == 0 or wire not in WIRE_NAMES:
            if strict:
                raise _Invalid()
            out.append({"field": field, "wire": f"wire{wire}", "value": "invalid"})
            break
        name = WIRE_NAMES[wire]
        try:
            if wire == 0:
                value, i = _varint(data, i)
            elif wire in (1, 5):
                size = 8 if wire == 1 else 4
                if i + size > len(data):
                    raise _Invalid()
                value = int.from_bytes(data[i:i + size], "little")
                i += size
            else:
                length, i = _varint(data, i)
                if i + length > len(data):
                    raise _Invalid()
                value = _len_value(data[i:i + length], depth, max_depth)
                i += length
        except _Invalid:
            if strict:
                raise
            out.append({"field": field, "wire": name, "value": "invalid"})
            break
        out.append({"field": field, "wire": name, "value": value})
    return out


def _len_value(chunk, depth, max_depth):
    if depth < max_depth:
        try:
            return _fields(chunk, depth + 1, max_depth, strict=True)
        except _Invalid:
            pass
    if chunk and all(0x20 <= b < 0x7F for b in chunk):
        return "text:" + chunk.decode("ascii")
    return "hex:" + chunk.hex()


def decode(data, depth=0, max_depth=3):
    """protobuf wire 바이트를 [{"field", "wire", "value"}] 로 푼다. 잘못된 입력에서도 예외를 던지지 않는다."""
    return _fields(bytes(data), depth, max_depth, strict=False)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: pbwire.py <hex>", file=sys.stderr)
        sys.exit(2)
    print(json.dumps(decode(bytes.fromhex(sys.argv[1])), indent=2))
