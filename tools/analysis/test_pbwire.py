"""tools/analysis/pbwire.py 테스트. 실행: python3 -I -m unittest discover -s tools/analysis -p 'test_*.py'"""
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import pbwire  # noqa: E402


class DecodeTest(unittest.TestCase):
    def test_varint(self):
        self.assertEqual(pbwire.decode(bytes.fromhex("0801")), [{"field": 1, "wire": "varint", "value": 1}])

    def test_nested_message(self):
        self.assertEqual(pbwire.decode(bytes.fromhex("12020801"))[0]["value"],
                         [{"field": 1, "wire": "varint", "value": 1}])

    def test_printable_text(self):
        self.assertEqual(pbwire.decode(b"\x1a\x03abc")[0]["value"], "text:abc")

    def test_binary_falls_back_to_hex(self):
        self.assertEqual(pbwire.decode(b"\x1a\x02\xff\xfe")[0]["value"], "hex:fffe")

    def test_fixed32_and_fixed64(self):
        self.assertEqual(pbwire.decode(bytes.fromhex("0d01000000"))[0], {"field": 1, "wire": "fixed32", "value": 1})
        self.assertEqual(pbwire.decode(bytes.fromhex("110200000000000000"))[0],
                         {"field": 2, "wire": "fixed64", "value": 2})

    def test_truncated_length_is_invalid_not_an_exception(self):
        self.assertEqual(pbwire.decode(bytes.fromhex("0a05ff")), [{"field": 1, "wire": "len", "value": "invalid"}])

    def test_depth_limit_keeps_hex(self):
        nested = bytes.fromhex("0a020801")  # field 1 = message {1: 1}
        self.assertEqual(pbwire.decode(nested, max_depth=0)[0]["value"], "hex:0801")

    def test_zero_length_len_is_an_empty_message(self):
        self.assertEqual(pbwire.decode(bytes.fromhex("0a00")), [{"field": 1, "wire": "len", "value": []}])

    def test_repeated_fields_keep_arrival_order(self):
        fields = [e["field"] for e in pbwire.decode(bytes.fromhex("1001080110 02"))]
        self.assertEqual(fields, [2, 1, 2])


if __name__ == "__main__":
    unittest.main()
