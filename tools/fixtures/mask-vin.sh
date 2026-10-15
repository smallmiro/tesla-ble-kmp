#!/usr/bin/env bash
# tools/fixtures/mask-vin.sh — 캡처의 실제 VIN(ASCII 원문과 그 hex)을 테스트 VIN 5YJ30123456789ABC로 바꾼다(SDD §9.2).
# 길이가 같아 TLV 길이가 유지된다. 사용: mask-vin.sh <VIN> < capture.txt > masked.txt
#        mask-vin.sh --self-test
set -euo pipefail
TEST_VIN="5YJ30123456789ABC"
hex_of() { printf '%s' "$1" | od -An -tx1 | tr -d ' \n'; }
mask() {
  local vin; vin="$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')"
  if [ "${#vin}" -ne 17 ]; then echo "VIN must be 17 characters" >&2; exit 2; fi
  local vin_hex test_hex; vin_hex="$(hex_of "$vin")"; test_hex="$(hex_of "$TEST_VIN")"
  sed -e "s/${vin}/${TEST_VIN}/g" -e "s/${vin_hex}/${test_hex}/g"
}
if [ "${1:-}" = "--self-test" ]; then
  # 형식만 맞춘 가짜 VIN. scan-secrets가 잡지 않도록 두 조각으로 이어 만든다.
  real="LRW3E7FA0MC""123456"
  input="TX: 0a$(hex_of "$real")ff vin=$real"
  expected="TX: 0a$(hex_of "$TEST_VIN")ff vin=$TEST_VIN"
  actual="$(printf '%s\n' "$input" | mask "$real")"
  [ "$actual" = "$expected" ] && echo "mask-vin self-test: ok" || { echo "mask-vin self-test: FAILED: $actual" >&2; exit 1; }
  exit 0
fi
mask "${1:?usage: mask-vin.sh <VIN> < capture > masked}"
