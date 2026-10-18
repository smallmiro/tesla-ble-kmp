# ADR-0002: protobuf 코드 생성: Wire

| 항목 | 내용 |
|---|---|
| 상태 | **채택** (2026-09-26, SDD v1.0 승인) |
| 결정 번호 | D22 (`{{SDD_FILE}}` §0) |
| 관련 | `{{PRD_FILE}}`, `{{SDD_FILE}}` |

## 맥락
`pkg/protocol/protobuf/*.proto` 9개(proto3, oneof 다수, `google.protobuf.Timestamp`, `allow_alias`)를 수정 없이 commonMain에서 쓰려면 KMP 지원 protobuf 구현이 필요하다. 이 머신에는 `protoc`가 없다.

## 결정
Square Wire 7.0.4. Gradle 플러그인이 `:domain` commonMain에 Kotlin을 생성한다(`protoc` 불필요). `wire-runtime`은 iOS 아티팩트를 제공한다. 생성 패키지는 각 파일의 `option java_package`(`com.tesla.generated.*`)를 따르고, 옵션이 없는 `managed_charging.proto`만 `ManagedCharging` 패키지가 된다. `Timestamp`는 `com.squareup.wire.Instant`. oneof는 기본(flat) 모드.

## 결과
- `.proto`는 복사본이며 무수정. 파일 헤더 주석 대신 `{{REF_REPO_DIR}}` 경로와 커밋을 `NOTICE`와 README에 명시.
- `GetState`가 반환하는 `CarServer.VehicleData` 등 생성 타입이 공개 API에 노출된다(SDD §2.3). `apiDump`에 포함.
- `wire-runtime`이 `:domain`의 유일한 외부 의존(coroutines 제외).

## 대안
- protobuf-kotlin(Google): JVM 전용. 기각.
- pbandk: 유지보수 정체. 기각.
- 수기 파서(PoC `readFields`): 메시지 900여 필드를 손으로 쓸 수 없다. 기각.

## 개정 (2026-10-18): 스키마 출처를 Teslemetry 스냅샷으로

| 항목 | 내용 |
|---|---|
| 상태 | **채택** (2026-10-18, 사용자 요청 "Teslemetry/tesla-protocol 에 정의된 것들부터 정의해 달라", 검토 기록 `.superpowers/sdd/2026-10-18-m4-pairing-vcsec/codex-proto-ext.md` 선택지 A) |
| 관련 | `{{SDD_FILE}}` D22·§12, `{{PATHS_FILE}}` `PROTO_SCHEMA_REPO`·`PROTO_SCHEMA_COMMIT`, `NOTICE`, NFR-008, NFR-017 |

### 맥락
실차 관찰(M4 Task 1·1B)에서 `VehicleStatus` 6·7, `FromVCSECMessage` 3처럼 공식 proto에 없는 필드가 보였다. `{{PROTO_SCHEMA_REPO}}`(Apache-2.0)은 `teslamotors/vehicle-command`의 `pkg/protocol/protobuf/*.proto`를 그대로 두고 `TESLEMETRY-EXT` 표시와 함께 필드·enum·메시지를 더한 상위 집합이다. proto3는 다른 파일에서 기존 메시지에 필드를 덧붙일 수 없으므로(선택지 B 기각), 9개 파일 자체를 바꾼다.

### 결정
1. **스키마 출처** = `{{PROTO_SCHEMA_REPO}}`@`{{PROTO_SCHEMA_COMMIT}}`의 `proto/command/` 중 우리가 쓰던 9개(`car_server`, `common`, `errors`, `keys`, `managed_charging`, `signatures`, `universal_message`, `vcsec`, `vehicle`). 바이트 그대로 복사하며 `TESLEMETRY-EXT`·`UNCONFIRMED`·`name unverified` 주석을 지우지 않는다. `session.proto`와 다른 계열(telemetry, energy, dashcam)은 가져오지 않는다.
2. **정답 기준은 그대로**: 명령의 의미, 암호, 라우팅, 오류 분류의 기준은 계속 `{{REF_REPO_DIR}}`@`{{REF_REPO_COMMIT}}`(공식 Go)다. 두 출처가 충돌하면 공식 Go를 따르고 차이를 `{{SDD_FILE}}` §12에 적어 사용자에게 보고한다. 이 스냅샷의 기반(`upstream.json`의 vehicle-command `4c2143a5`)의 9개 파일은 SHA-256이 우리 `a4b43c1` 복사본과 같다.
3. **미검증 항목은 스키마로만 존재**: `UNCONFIRMED` 또는 `name unverified`인 항목, 그리고 공식 Go에 없는 `TESLEMETRY-EXT` 항목은 생성 타입으로 존재할 뿐이다. 실차 캡처나 공식 Go로 확인되기 전에는 명령 API, 상태 이벤트, 안전 판단에 연결하지 않는다. 관찰 로그(DEBUG)는 스키마 이름을 쓰되 문서에 "실차 의미 미확인"이라고 적는다. 공식 Go가 모르는 enum 값은 Go와 같은 분류를 유지한다(예: `Session_Info_Status` `INVALID_HANDLE` → `UnknownResponse`, `MessageFault_E` 29는 재시도 대상 아님).
4. **Wire가 새로 아는 값**: 전에는 `unknownFields`로 가서 `Unknown*` 원시 코드가 되던 값 중 스냅샷에 있는 것은 이름 있는 값이 된다(`KeychainRejected`, `VcsecRejected`, `ProtocolFault`). `temporary`·`mayHaveSucceeded`는 바뀌지 않는다. 공개 모델 enum(`KeyRole`, `KeyFormFactor`)에는 값을 더하지 않고 새 Wire 값을 `UNKNOWN`으로 둔다.
5. **갱신 방법**: 새 커밋을 검토할 때 9개 파일의 package/`java_package`/import, 기존 번호의 이름·타입·oneof 변화, 예약 번호 충돌을 기계적으로 비교한다(우리 코드가 쓰는 필드에 타입·oneof·번호 변화가 있으면 중단하고 보고). 통과하면 `{{PATHS_FILE}}`의 `PROTO_SCHEMA_COMMIT`(`docs:` 커밋)과 `tools/proto/sync-protos.sh`의 `EXPECTED_COMMIT`을 같은 PR에서 바꾸고, 고정 커밋으로 체크아웃한 로컬 사본으로 `tools/proto/sync-protos.sh <checkout>`(네트워크 없음) → `./gradlew :domain:generateCommonMainProtos` → `apiDump` → 테스트 → 라이브러리 사용 문서 순으로 진행한다.

### 결과
- 생성 타입이 크게 늘어난다(`:domain` `apiDump`: 클래스 516 → 998, 필드가 늘어난 메시지 48개의 생성자와 56개의 `copy` 시그니처가 바뀜, 삭제된 클래스 없음). Kotlin 소스는 이름 있는 인자를 쓰면 호환되지만 JVM 바이너리 시그니처는 바뀐다.
- 새 메시지의 packed repeated 필드에서 Wire 7.0.4가 불필요한 `!!`를 생성해 `:domain`에서 `UNNECESSARY_NOT_NULL_ASSERTION` 경고를 끈다.
- `NOTICE`에 Teslemetry tesla-protocol 귀속(Apache-2.0, 고정 커밋)을 적는다. 위 "결과"의 "`.proto`는 복사본이며 무수정"은 "Teslemetry 스냅샷의 무수정 복사본"으로 읽는다.
- 이후 틀린 이름이 고쳐지면 생성 API 호환성 문제가 된다. 미검증 이름을 공개 모델로 승격하지 않는 이유다.
