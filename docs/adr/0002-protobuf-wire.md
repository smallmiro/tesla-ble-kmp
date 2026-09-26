# ADR-0002: protobuf 코드 생성: Wire

| 항목 | 내용 |
|---|---|
| 상태 | 제안 (2026-09-26) — SDD 승인 시 채택 |
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
