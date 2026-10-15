# tools/fixtures — 골든 TX/RX 수집

Go `tesla-control`의 `-debug` 출력에서 TX/RX hex 줄을 모아 실차 골든 픽스처의 원재료를 만듭니다(SDD §9.2, NFR-002, ADR-0011).

## 왜 사용자 기기에서 실행하나
이 저장소 작업 환경에는 Go 도구체인이 없습니다. 사용자가 Go로 `tesla-control`을 빌드해 PATH에 두고, 실차 옆(블루투스 범위)에서 직접 실행합니다.

## 사용법
```bash
# 인증 없는 명령 (M3): list-keys, body-controller-state
VIN=<실제 VIN> tools/fixtures/collect-golden.sh

# 인증 명령 (M4/M5): session-info(vcsec), lock, unlock, climate-on, state charge
VIN=<실제 VIN> KEY_FILE=<개인키 PEM 경로> tools/fixtures/collect-golden.sh --with-key
```
결과는 `tools/fixtures/captures/<명령>.txt` 에 저장됩니다(줄: `[debug] TX: ...` / `[debug] RX: ...`).

## VIN 마스킹
`collect-golden.sh`는 저장 전에 `mask-vin.sh <VIN>`을 거칩니다. ASCII 원문과 그 hex를 테스트 VIN `5YJ30123456789ABC`로 바꿉니다(길이가 같아 TLV 길이가 유지됨). 검증: `bash tools/fixtures/mask-vin.sh --self-test`.

## 커밋 금지 규칙
- `tools/fixtures/captures/` 는 git-ignored 입니다. 캡처 파일 자체를 커밋하지 않습니다.
- 개인키 파일, 실제 VIN, 실제 공개키가 담긴 RX(`list-keys`)는 어떤 형태로도 커밋하지 않습니다.
- 필요한 줄만 골라 마스킹을 확인한 뒤 `{{FIXTURES_DIR}}` 의 Kotlin 상수로 옮깁니다(ADR-0011).
