# samples/AGENTS.md — 샘플 앱 공통 지침

> 루트 [`AGENTS.md`](../AGENTS.md) 의 규칙을 **모두** 따릅니다. 이 파일에는 샘플 작업에만 해당하는 **차이**만 둡니다.
> 충돌하면 루트 `AGENTS.md` 가 우선합니다. 경로는 [`docs/PATHS.md`](../docs/PATHS.md) 의 `{{키}}` 로 참조합니다.

## 1. 샘플의 목적
샘플은 제품이 아니라 **라이브러리를 검증·모니터링·탐색하는 도구**입니다. 처음 라이브러리를 쓰는 앱 개발자의 입장에서,
라이브러리 사용 문서만 보고 만듭니다. 문서가 모자라면 그 사실이 곧 라이브러리 작업의 입력입니다.

## 2. 기준 문서 (순서대로)
| 순서 | 키 | 언제 |
|---|---|---|
| 1 | `{{SAMPLES_DOCS_INDEX}}` | 항상. 특히 `02-constraints.md` 는 샘플을 고치기 전에 반드시 |
| 2 | `{{LIB_DOCS_INDEX}}` 의 "Reference files by task" 표 → 해당 `11-lib-*`~`19-lib-*` 페이지 | 라이브러리를 부르는 코드를 쓰기 전 |
| 3 | `samples/android/AGENTS.md` 또는 `samples/ios/AGENTS.md` | 해당 플랫폼 작업 시 |

- **읽지 않아도 되는 것**: `{{REF_REPO_DIR}}`(Go 원본), `{{MANUAL_DIR}}` 의 `00`~`10`(Go 참고 문서), 라이브러리 내부 코드(`:domain`·`:application`·`:adapter-*`). 샘플의 정답 기준은 라이브러리 사용 문서입니다(루트 절대 규칙 14).

## 3. 샘플 작업에서 달라지는 점
- **라이브러리 코드를 고치지 않습니다.** API가 부족하거나 문서와 동작이 다르면 `library-api-request` 스킬로 요청서를 쓰고 계획에 올립니다. 샘플에서 우회하지 않습니다.
- **TDD 범위**: 차량 없이 시험할 수 있는 로직(파싱, 마스킹, 타이머, 상태 계산, 로그 형식)은 실패 테스트부터 씁니다. 화면과 실차 동작은 실기기 확인 체크리스트로 검증합니다(`{{SAMPLES_DOCS_DIR}}02-constraints.md` §4).
- **문서 갱신 의무**: 샘플 동작이 바뀌면 `{{SAMPLES_DOCS_DIR}}` 와 `{{SAMPLES_DOCS_HTML_DIR}}`(ko/en/ja)를 같은 PR에서 고칩니다. 라이브러리 사용 문서의 예제가 틀린 것을 발견하면 그것도 같은 PR에서 고칩니다.
- **스타일·정적 분석**: 샘플 Kotlin 코드도 `lintKotlin`·`detekt` 게이트(`tools/ci/verify.sh` 단계 (a))를 통과해야 합니다. 샘플 전용 완화는 `config/detekt/detekt-samples.yml` 에 이유와 함께 두고, 명령은 `samples/android/AGENTS.md` 에 있습니다.
- **로그와 실차 데이터**: 기기에서 가져온 로그는 `{{SAMPLE_LOGS_DIR}}`(git-ignored)에만 둡니다. 커밋하지 않습니다. 분석은 `protocol-analyst` 몫입니다.
- **PR 본문**: 참고한 라이브러리 사용 문서 페이지와 절, 지킨 샘플 요구사항 ID(`SFR-xxx`)를 적습니다.

## 4. 역할
샘플 작업은 `android-developer`, `ios-developer` 가 맡습니다. 역할 경계, 넘기는 곳, Codex 협의 규칙은 루트 `AGENTS.md` 의
"역할 분담과 Codex 협의" 절을 따릅니다.
