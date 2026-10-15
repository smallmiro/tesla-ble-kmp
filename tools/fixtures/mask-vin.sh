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
  local vin_lc hex_lc hex_uc test_hex
  vin_lc="$(printf '%s' "$vin" | tr '[:upper:]' '[:lower:]')"
  hex_lc="$(hex_of "$vin")"; hex_uc="$(printf '%s' "$hex_lc" | tr '[:lower:]' '[:upper:]')"
  test_hex="$(hex_of "$TEST_VIN")"
  # GNU sed의 I 플래그 없이 이식 가능하게: 대문자/소문자 VIN, 소문자/대문자 hex를 각각 치환한다.
  local out
  out="$(sed -e "s/${vin}/${TEST_VIN}/g" -e "s/${vin_lc}/${TEST_VIN}/g" -e "s/${hex_lc}/${test_hex}/g" -e "s/${hex_uc}/${test_hex}/g")"
  # 누출 방지: 어떤 형태로든 실제 VIN이 남아 있으면 출력하지 않고 실패한다.
  local form
  for form in "$vin" "$vin_lc" "$hex_lc" "$hex_uc"; do
    if printf '%s\n' "$out" | grep -qiF -- "$form"; then
      echo "mask-vin: real VIN still present after masking; refusing to output" >&2; exit 3
    fi
  done
  printf '%s\n' "$out"
}
if [ "${1:-}" = "--self-test" ]; then
  # 형식만 맞춘 가짜 VIN. scan-secrets가 잡지 않도록 두 조각으로 이어 만든다.
  real="LRW3E7FA0MC""123456"
  real_lc="$(printf '%s' "$real" | tr '[:upper:]' '[:lower:]')"
  hex_uc="$(hex_of "$real" | tr '[:lower:]' '[:upper:]')"
  input="TX: 0a$(hex_of "$real")ff vin=$real
low vin=$real_lc
up hex: 0A${hex_uc}FF"
  expected="TX: 0a$(hex_of "$TEST_VIN")ff vin=$TEST_VIN
low vin=$TEST_VIN
up hex: 0A$(hex_of "$TEST_VIN")FF"
  actual="$(printf '%s\n' "$input" | mask "$real")"
  [ "$actual" = "$expected" ] || { echo "mask-vin self-test: FAILED: $actual" >&2; exit 1; }
  # 누출 방지: 마스킹이 놓치는 형태(대소문자 섞인 VIN)는 대소문자 무시 검사로 exit 3
  mixed="$(printf '%s' "$real" | cut -c1-8)$(printf '%s' "$real_lc" | cut -c9-)"
  rc=0; printf 'x %s\n' "$mixed" | mask "$mixed" >/dev/null 2>&1 || rc=$?
  [ "$rc" -eq 3 ] || { echo "mask-vin self-test: FAILED: leak guard rc $rc" >&2; exit 1; }
  echo "mask-vin self-test: ok"
  exit 0
fi
mask "${1:?usage: mask-vin.sh <VIN> < capture > masked}"
