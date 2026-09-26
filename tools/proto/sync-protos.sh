#!/usr/bin/env bash
# tools/proto/sync-protos.sh — vehicle-command 의 .proto 를 무수정으로 복사하고 출처를 기록한다 (ADR-0002)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/vehicle-command/pkg/protocol/protobuf"
DST="$ROOT/domain/src/commonMain/proto"
EXPECTED_COMMIT="a4b43c1eff0e09d77deb9f2dce97031141fe8c8a"
head="$(cat "$ROOT/vehicle-command/.git/HEAD")"
if [[ "$head" == ref:\ * ]]; then
  ref="${head#ref: }"
  if [ -f "$ROOT/vehicle-command/.git/$ref" ]; then
    ACTUAL_COMMIT="$(cat "$ROOT/vehicle-command/.git/$ref")"
  else
    ACTUAL_COMMIT="$(grep " $ref\$" "$ROOT/vehicle-command/.git/packed-refs" | cut -d' ' -f1)"
  fi
else
  ACTUAL_COMMIT="$head"
fi
if [ "$ACTUAL_COMMIT" != "$EXPECTED_COMMIT" ]; then
  echo "vehicle-command HEAD $ACTUAL_COMMIT != expected $EXPECTED_COMMIT (docs/PATHS.md REF_REPO_COMMIT)" >&2
  exit 1
fi
mkdir -p "$DST"
cp "$SRC"/*.proto "$DST"/
{
  echo "# PROTO_SOURCE"
  echo
  echo "Copied without modification from teslamotors/vehicle-command@${ACTUAL_COMMIT} pkg/protocol/protobuf/ (Apache-2.0)."
  echo "Regenerate with tools/proto/sync-protos.sh. Do not edit these files."
  echo
  (cd "$DST" && shasum -a 256 ./*.proto)
} > "$DST/PROTO_SOURCE.md"
echo "synced $(ls "$DST"/*.proto | wc -l | tr -d ' ') proto files"
