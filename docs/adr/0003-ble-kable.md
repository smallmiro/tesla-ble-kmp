# ADR-0003: BLE: Kable을 Transport 포트 뒤에 두고 indication 문제는 스파이크로 확인

| 항목 | 내용 |
|---|---|
| 상태 | 제안 (2026-09-26) — SDD 승인 시 채택 |
| 결정 번호 | D23 (`{{SDD_FILE}}` §0) |
| 관련 | `{{PRD_FILE}}`, `{{SDD_FILE}}` |

## 맥락
Go는 go-ble로 0213 특성을 **indication**으로 구독한다. Kable 0.45.0은 특성이 notify와 indicate를 모두 지원하면 notify를 고르고 강제 옵션이 없다. MTU는 Android `requestMtu`, iOS `maximumWriteValueLengthForType`으로 얻는다. Tesla 0213 특성의 속성 조합은 실차에서만 확인할 수 있다.

## 결정
Kable 0.45.0을 `:adapter-ble`에서 `Transport`/`TransportFactory` 포트 구현으로 사용한다. M3의 첫 작업은 실차 스파이크: (1) 0213의 properties 로그, (2) Kable 기본 구독으로 응답이 오는지, (3) 안 오면 `onSubscription`에서 CCCD(0x2902)에 `0x0200`(indication)을 직접 쓰는 우회, (4) 그래도 안 되면 같은 포트를 CoreBluetooth/BluetoothGatt로 직접 구현. 프레이밍·재조립·이름 계산은 `:domain`에 두어 스택을 바꿔도 재사용한다.

## 결과
- 스택 교체 비용은 `:adapter-ble` 한 모듈로 한정된다.
- Kable은 Kotlin 2.4.10 빌드이며 minSdk 21이지만 우리는 31.
- iOS는 MTU 협상 API가 없으므로 `maximumWriteValueLength(for: .withResponse)`로 블록 길이를 정한다.

## 대안
- 처음부터 자체 GATT 구현: 스캔·연결·구독·재시도를 두 플랫폼에서 새로 짜야 한다. Kable이 실패할 때만.
