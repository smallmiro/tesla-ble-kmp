"""tools/analysis/monitor_summary.py 테스트. 실행: python3 -I -m unittest discover -s tools/analysis -p 'test_*.py'"""
import json
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import monitor_summary  # noqa: E402


def write_log(lines):
    f = tempfile.NamedTemporaryFile("w", suffix=".jsonl", delete=False)
    for line in lines:
        f.write(line if isinstance(line, str) else json.dumps(line))
        f.write("\n")
    f.close()
    return pathlib.Path(f.name)


LOG = [
    {"ts": "2026-11-14T08:02:21.000+09:00", "kind": "session", "platform": "ios", "app": "0.5.0(27)"},
    {"ts": "2026-11-14T08:02:22.000+09:00", "kind": "link", "state": "connected", "rssi": -49},
    {"ts": "2026-11-14T08:02:23.000+09:00", "kind": "query", "call": "vehicleStatus", "result": "Success",
     "summary": "lock=LOCKED"},
    {"ts": "2026-11-14T08:02:24.000+09:00", "kind": "query", "call": "getState(LOCATION)", "result": "Success",
     "summary": "durationMs=300 present=[latitude, longitude]"},
    {"ts": "2026-11-14T08:02:25.000+09:00", "kind": "query", "call": "listKeys", "result": "Uncertain",
     "summary": "timed out waiting for vehicle response"},
    {"ts": "2026-11-14T08:02:26.000+09:00", "kind": "mark", "text": "cold"},
    {"ts": "2026-11-14T08:02:27.000+09:00", "kind": "observe", "line": "rx to=address(16B, other) 40B"},
    {"ts": "2026-11-14T08:02:28.000+09:00", "kind": "link", "state": "disconnected",
     "reason": "bluetooth connection lost"},
    "not json",
]


class SummarizeTest(unittest.TestCase):
    def setUp(self):
        self.path = write_log(LOG)
        self.s = monitor_summary.summarize(self.path)

    def tearDown(self):
        self.path.unlink()

    def test_build_span_and_kinds(self):
        self.assertEqual(self.s["app"], "0.5.0(27)")
        self.assertEqual(self.s["first"], "08:02:21")
        self.assertEqual(self.s["last"], "08:02:28")
        self.assertEqual(self.s["kinds"]["query"], 3)
        self.assertEqual(self.s["bad_lines"], 1)

    def test_outcomes_per_call(self):
        self.assertEqual(self.s["calls"]["getState(LOCATION)"], {"Success": 1})
        self.assertEqual(self.s["results"], {"Success": 2, "Uncertain": 1})

    def test_non_success_keeps_reason(self):
        self.assertEqual(self.s["non_success"],
                         [("08:02:25", "listKeys", "Uncertain", "timed out waiting for vehicle response")])

    def test_marks_links_and_other_frames(self):
        self.assertEqual(self.s["marks"], [("08:02:26", "cold")])
        self.assertEqual(self.s["links"], [("08:02:22", "connected", "rssi=-49"),
                                           ("08:02:28", "disconnected", "bluetooth connection lost")])
        self.assertEqual(self.s["other_frames"], 1)

    def test_never_prints_getstate_summaries(self):
        # 위치 값이 들어갈 수 있는 getState 요약은 출력하지 않는다 (14-lib-reading-state A8.3/A8.4).
        text = monitor_summary.render(self.s, "x.jsonl")
        self.assertNotIn("present=", text)
        self.assertNotIn("latitude", text)


if __name__ == "__main__":
    unittest.main()
