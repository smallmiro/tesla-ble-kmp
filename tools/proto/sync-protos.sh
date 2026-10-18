#!/usr/bin/env bash
# tools/proto/sync-protos.sh — Teslemetry/tesla-protocol 의 command proto 9개를 그대로 복사하고 출처를 기록한다
# (ADR-0002 개정 2026-10-18). 네트워크를 쓰지 않는다: 고정 커밋으로 체크아웃한 로컬 사본을 인자나
# TESLA_PROTOCOL_DIR 로 받는다.
#
#   git clone https://github.com/Teslemetry/tesla-protocol /path/to/tesla-protocol
#   git -C /path/to/tesla-protocol checkout <PROTO_SCHEMA_COMMIT>
#   tools/proto/sync-protos.sh /path/to/tesla-protocol
#   ./gradlew :domain:generateCommonMainProtos
#
# 고정 커밋을 바꿀 때는 아래 EXPECTED_COMMIT 과 docs/PATHS.md 의 PROTO_SCHEMA_COMMIT 을 같은 PR에서 고친다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC_REPO="${1:-${TESLA_PROTOCOL_DIR:-}}"
DST="$ROOT/domain/src/commonMain/proto"
EXPECTED_COMMIT="e6f7afb1b633d7215e34d0cdac66dab983c6ae78" # docs/PATHS.md PROTO_SCHEMA_COMMIT
FILES=(car_server common errors keys managed_charging signatures universal_message vcsec vehicle)

if [ -z "$SRC_REPO" ]; then
  echo "usage: $0 <tesla-protocol checkout>  (or set TESLA_PROTOCOL_DIR)" >&2
  exit 2
fi
if [ ! -d "$SRC_REPO/proto/command" ]; then
  echo "$SRC_REPO/proto/command not found (expected a Teslemetry/tesla-protocol checkout)" >&2
  exit 1
fi
ACTUAL_COMMIT="$(git -C "$SRC_REPO" rev-parse HEAD)"
if [ "$ACTUAL_COMMIT" != "$EXPECTED_COMMIT" ]; then
  echo "tesla-protocol HEAD $ACTUAL_COMMIT != expected $EXPECTED_COMMIT (docs/PATHS.md PROTO_SCHEMA_COMMIT)" >&2
  exit 1
fi
if [ -n "$(git -C "$SRC_REPO" status --porcelain -- proto/command)" ]; then
  echo "tesla-protocol proto/command has local changes; use a clean checkout" >&2
  exit 1
fi

mkdir -p "$DST"
for f in "${FILES[@]}"; do
  cp "$SRC_REPO/proto/command/$f.proto" "$DST/$f.proto"
done
{
  echo "# PROTO_SOURCE"
  echo
  echo "Copied without modification from Teslemetry/tesla-protocol@${ACTUAL_COMMIT} proto/command/ (Apache-2.0)."
  echo "That snapshot is teslamotors/vehicle-command pkg/protocol/protobuf/ plus Teslemetry extensions marked TESLEMETRY-EXT"
  echo "(see NOTICE and docs/adr/0002-protobuf-wire.md, revision 2026-10-18). Only these 9 files are used."
  echo "Regenerate with tools/proto/sync-protos.sh <tesla-protocol checkout>. Do not edit these files."
  echo
  (cd "$DST" && for f in "${FILES[@]}"; do shasum -a 256 "./$f.proto"; done)
} > "$DST/PROTO_SOURCE.md"
echo "synced ${#FILES[@]} proto files from tesla-protocol@${ACTUAL_COMMIT}"
