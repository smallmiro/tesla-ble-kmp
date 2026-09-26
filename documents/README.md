# Tesla vehicle-command 개발 매뉴얼

`vehicle-command/` 저장소(Tesla Vehicle Command SDK, Go)를 기반으로 BLE 및 인터넷 경로로 Tesla 차량에
종단 간 인증 명령을 보내는 애플리케이션을 개발하기 위한 매뉴얼입니다.

기준 버전: 태그 `v0.4.1` 이후 커밋 `a4b43c1` (Go 1.23, 모듈 `github.com/teslamotors/vehicle-command`).

## 두 벌의 매뉴얼

| 디렉터리 | 독자 | 형식 | 진입점 |
|---|---|---|---|
| `html/` | 사람 (개발자, 리뷰어, 운영자) | HTML | `html/index.html` 을 브라우저로 열기 |
| `md/`   | AI 코딩 에이전트 (Claude Code, Codex 등) | Markdown | `md/README.md` |

두 벌은 같은 사실을 다루지만 목적이 다릅니다.

- **HTML**은 개념 설명, 다이어그램, 단계별 절차, 비교 표 중심으로 사람이 읽고 이해하도록 썼습니다.
- **MD**는 정확한 API 시그니처, 프로토콜 불변 조건, 파일 경로, 검증 체크리스트 중심으로 에이전트가
  코드를 수정하거나 새 클라이언트를 구현할 때 컨텍스트로 읽도록 썼습니다. 에이전트에게 작업을 시킬 때는
  `documents/md/README.md`를 먼저 읽게 하고, 작업 종류에 따라 필요한 파일만 추가로 읽히면 됩니다.

## 디렉터리 구조

```
documents/
├── README.md                      # 이 파일
├── html/                          # 사람용
│   ├── index.html                 # 홈 / 문서 지도
│   ├── assets/style.css
│   ├── 01-overview.html           # 시스템 개요와 아키텍처
│   ├── 02-getting-started.html    # 설치, 키 생성, 페어링, 첫 명령
│   ├── 03-ble-transport.html      # BLE 전송 계층 (UUID, 프레이밍, MTU, 스캔)
│   ├── 04-protocol.html           # 프로토콜 사양 (핸드셰이크, 인증, 암호화, 세션 복구)
│   ├── 05-go-sdk.html             # Go SDK 사용법
│   ├── 06-cli-tools.html          # CLI 도구
│   ├── 07-command-reference.html  # 명령 레퍼런스
│   ├── 08-http-proxy.html         # HTTP 프록시
│   └── 09-errors-troubleshooting.html
└── md/                            # AI 에이전트용
    ├── README.md                  # 진입점: 읽기 순서와 작업별 참조 파일
    ├── 00-agent-guide.md          # 작업 규칙, 불변 조건, 빠른 참조 카드
    ├── 01-architecture.md         # 패키지 구조, 계층, 데이터 흐름
    ├── 02-ble-transport.md        # BLE 상세
    ├── 03-protocol.md             # 프로토콜 상세 (테스트 벡터 포함)
    ├── 04-go-api-reference.md     # 패키지별 공개 API
    ├── 05-command-catalog.md      # 명령 카탈로그 (CLI / Go / Proxy / 도메인 / 인증)
    ├── 06-cli-tools.md            # CLI 도구와 환경 변수
    ├── 07-http-proxy.md           # HTTP 프록시
    ├── 08-errors.md               # 오류 코드와 재시도 정책
    ├── 09-recipes.md              # 코드 레시피
    └── 10-porting-guide.md        # 다른 언어/플랫폼 포팅 체크리스트
```

## 원본 참조

- `vehicle-command/README.md`
- `vehicle-command/pkg/protocol/protocol.md` (프로토콜 사양 원문)
- `vehicle-command/cmd/tesla-control/README.md`
- `vehicle-command/pkg/protocol/protobuf/*.proto`
- https://pkg.go.dev/github.com/teslamotors/vehicle-command/pkg
- https://developer.tesla.com/docs/fleet-api

## HTML 보기

`html/index.html`을 브라우저에서 직접 열면 됩니다(외부 리소스 없음). 로컬 서버로 보려면:

```bash
cd documents && python3 -m http.server 8000
# http://localhost:8000/html/
```
