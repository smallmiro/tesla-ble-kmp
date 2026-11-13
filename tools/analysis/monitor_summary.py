"""모니터 로그(JSON Lines) 요약. 실차 로그를 가져온 직후 무엇이 들어 있는지 한눈에 본다.

사용: python3 -I tools/analysis/monitor_summary.py <monitor-*.jsonl>...

출력: 파일마다 앱 빌드, 시간 범위, 줄 종류별 개수, 호출별 결과 개수, 성공이 아닌 조회, mark, 연결/끊김,
다른 클라이언트(`other`) 프레임 수. getState 요약(값과 좌표가 들어갈 수 있음)은 출력하지 않는다
(14-lib-reading-state A8.3/A8.4, AGENTS.md 절대 규칙 8). 표준 라이브러리만 쓴다.
"""
import collections
import json
import pathlib
import sys


def _hms(ts):
    return ts[11:19] if isinstance(ts, str) and len(ts) >= 19 else "?"


def _safe_reason(call, summary):
    # 성공이 아닌 getState 요약은 durationMs/error/reason 뿐이다. 필드 목록이 섞여 있으면 버린다.
    if call.startswith("getState") and "present=" in summary:
        return "(getState summary withheld)"
    return summary


def summarize(path):
    s = {"app": None, "first": None, "last": None, "kinds": collections.Counter(), "bad_lines": 0,
         "calls": collections.defaultdict(collections.Counter), "results": collections.Counter(),
         "non_success": [], "marks": [], "links": [], "other_frames": 0}
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            try:
                o = json.loads(raw)
            except ValueError:
                s["bad_lines"] += 1
                continue
            if not isinstance(o, dict):
                s["bad_lines"] += 1
                continue
            ts, kind = o.get("ts"), o.get("kind", "?")
            s["first"] = s["first"] or _hms(ts)
            s["last"] = _hms(ts)
            s["kinds"][kind] += 1
            if kind == "session":
                s["app"] = o.get("app")
            elif kind == "query":
                call, result = o.get("call", "?"), o.get("result", "?")
                s["calls"][call][result] += 1
                s["results"][result] += 1
                if result != "Success":
                    s["non_success"].append((_hms(ts), call, result, _safe_reason(call, str(o.get("summary", "")))))
            elif kind == "mark":
                s["marks"].append((_hms(ts), str(o.get("text") or o.get("label") or o.get("note") or "")))
            elif kind == "link" and o.get("state") in ("connected", "disconnected"):
                detail = o.get("reason") or (f"rssi={o['rssi']}" if "rssi" in o else "")
                s["links"].append((_hms(ts), o["state"], detail))
            elif kind == "observe" and "other" in json.dumps(o):
                s["other_frames"] += 1
    s["calls"] = {k: dict(v) for k, v in s["calls"].items()}
    s["results"] = dict(s["results"])
    s["kinds"] = dict(s["kinds"])
    return s


def render(s, name):
    out = [f"== {name}  app={s['app']}  {s['first']}-{s['last']}  kinds={s['kinds']}"
           + (f"  bad_lines={s['bad_lines']}" if s["bad_lines"] else "")]
    if s["results"]:
        out.append(f"  results {s['results']}")
        for call, counts in sorted(s["calls"].items()):
            out.append(f"    {call}: {counts}")
    for t, call, result, reason in s["non_success"]:
        out.append(f"  ! {t} {call} {result}: {reason[:100]}")
    for t, text in s["marks"]:
        out.append(f"  mark {t} {text}")
    for t, state, detail in s["links"]:
        out.append(f"  link {t} {state} {detail}")
    out.append(f"  other-client frames: {s['other_frames']}")
    return "\n".join(out)


def main(argv):
    if not argv:
        print("usage: monitor_summary.py <monitor-*.jsonl>...", file=sys.stderr)
        return 64
    for p in argv:
        print(render(summarize(pathlib.Path(p)), pathlib.Path(p).name))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
