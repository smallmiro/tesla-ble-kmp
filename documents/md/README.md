# vehicle-command 에이전트용 매뉴얼 (진입점)

이 디렉터리는 AI 코딩 에이전트(Claude Code, Codex 등)가 `vehicle-command/` 저장소를 다룰 때 컨텍스트로
읽도록 작성한 Markdown 매뉴얼입니다. 사람이 읽는 HTML 버전은 `../html/index.html`에 있습니다.

기준: `github.com/teslamotors/vehicle-command` 태그 `v0.4.1` 이후 커밋 `a4b43c1`, Go 1.23.
저장소 루트는 이 문서 기준으로 `../../vehicle-command/` 입니다. 아래 파일 경로는 모두 저장소 루트 기준입니다.

## 읽기 순서

1. `00-agent-guide.md` — 반드시 먼저. 작업 규칙, 절대 어기면 안 되는 프로토콜 불변 조건, 빠른 참조 카드.
2. 작업 종류에 따라 아래 표에서 필요한 파일만 추가로 읽는다. 전부 읽을 필요는 없다.

## 작업별 참조 파일

| 하려는 작업 | 읽을 파일 |
|---|---|
| 저장소 구조 파악, 어떤 패키지를 고칠지 결정 | `01-architecture.md` |
| BLE 연결/스캔/프레이밍 수정, 다른 BLE 스택으로 교체, 연결 실패 디버깅 | `02-ble-transport.md`, `08-errors.md` |
| 핸드셰이크, 서명/암호화, 카운터, 세션 캐시 로직 수정 | `03-protocol.md` (테스트 벡터 포함), `01-architecture.md` |
| SDK로 새 애플리케이션 작성 (Go) | `04-go-api-reference.md`, `09-recipes.md`, `05-command-catalog.md` |
| 새 차량 명령 추가 (Go 메서드 + CLI + 프록시) | `05-command-catalog.md`, `04-go-api-reference.md`, `09-recipes.md`의 "새 명령 추가" |
| CLI 도구 옵션/환경 변수 변경 | `06-cli-tools.md` |
| HTTP 프록시 수정, 엔드포인트 추가, 배포 | `07-http-proxy.md`, `05-command-catalog.md` |
| 오류 처리, 재시도 정책, 문제 해결 | `08-errors.md` |
| 다른 언어(Python, Swift, Kotlin, Rust 등)로 BLE 클라이언트 구현 | `10-porting-guide.md`, `02-ble-transport.md`, `03-protocol.md` |

## 파일 목록

| 파일 | 내용 |
|---|---|
| `00-agent-guide.md` | 작업 규칙, 불변 조건, 빠른 참조 카드 (UUID, 상수, 환경 변수, 명령어 한 줄 요약) |
| `01-architecture.md` | 패키지 계층 (`connector` → `dispatcher` → `vehicle`), 데이터 흐름, 동시성 모델, 빌드/테스트 |
| `02-ble-transport.md` | GATT UUID, 광고 이름 계산, 2바이트 길이 프레이밍, MTU/블록 분할, 스캔/연결/재시도, 플랫폼별 차이 |
| `03-protocol.md` | RoutableMessage, 도메인, 핸드셰이크, ECDH/KDF, 메타데이터 TLV, HMAC/AES-GCM, 응답 복호화, 세션 복구, 테스트 벡터 |
| `04-go-api-reference.md` | `pkg/protocol`, `pkg/connector`, `pkg/connector/ble`, `pkg/connector/inet`, `pkg/vehicle`, `pkg/cache`, `pkg/cli`, `pkg/account`, `pkg/proxy`, `pkg/sign` 공개 API |
| `05-command-catalog.md` | 전체 명령 표: CLI 이름 ↔ Go 메서드 ↔ 프록시 엔드포인트 ↔ 도메인 ↔ 인증 요구 ↔ 인자 |
| `06-cli-tools.md` | tesla-keygen, tesla-control, tesla-auth-token, tesla-jws, tesla-http-proxy 옵션과 환경 변수, 키링 |
| `07-http-proxy.md` | 라우팅 규칙, 응답 형식, 상태 코드, TLS, Docker, `pkg/proxy` 확장 방법 |
| `08-errors.md` | `protocol.Error` 인터페이스, MessageFault 전체 표, VCSEC/Infotainment 애플리케이션 오류, 재시도 정책 |
| `09-recipes.md` | BLE 연결, 세션 캐시, 키 페어링, 상태 조회, 커스텀 Connector, 새 명령 추가, 테스트 작성 코드 예제 |
| `10-porting-guide.md` | 다른 언어로 BLE 클라이언트를 구현할 때의 단계별 체크리스트와 검증 방법 |

## 저장소 원본 문서 (권위 있는 출처)

- `README.md` — 설치, 프록시, 키 배포
- `pkg/protocol/protocol.md` — 프로토콜 사양 원문. 이 매뉴얼의 `03-protocol.md`와 충돌하면 원문이 우선.
- `pkg/protocol/protobuf/*.proto` — 메시지 정의. 필드 번호/enum 값의 최종 출처.
- `cmd/tesla-control/commands.go` — CLI 명령 표의 최종 출처.
- `pkg/proxy/command.go` — 프록시 엔드포인트 표의 최종 출처.
