# ADR-0012: 로컬 검증 게이트가 GitHub Actions CI를 대체

| 항목 | 내용 |
|---|---|
| 상태 | **채택** (2026-09-27, 사용자 결정) |
| 관련 | `{{WORKFLOW_FILE}}` §5.3, §6, `{{AGENTS_FILE}}` §3, §6, §7 |

## 맥락
이 프로젝트는 혼자 + 에이전트로 작업하는 워크플로이고, 개발에 쓰는 이 Mac 한 대가 `.github/workflows/ci.yml`의 6개 잡(lint, jvm-test, android, ios, license, secrets)을 iOS 시뮬레이터 테스트까지 포함해 전부 실행할 수 있다. GitHub Actions는 macOS 러너의 분당 과금이 Linux보다 비싸고 대기열이 생겨 PR 병합이 느려졌으며, 이를 자체 호스팅 러너로 옮기려던 제안(PR #37, `chore: run CI on self-hosted runners`)은 병합되지 않은 채 닫혔다. GitHub-hosted든 self-hosted든 별도 CI 인프라를 두는 이유가, 로컬에서 전부 돌릴 수 있는 이 환경에서는 약해진다.

## 결정
GitHub Actions CI(`.github/workflows/ci.yml`)를 삭제한다. `tools/ci/verify.sh`가 머지 게이트다 — 모든 푸시와 병합 전에 로컬에서 실행해 통과해야 한다(`{{WORKFLOW_FILE}}` §6). `main` 브랜치 보호는 더 이상 상태 검사를 요구하지 않지만, PR과 선형 히스토리(리니어 히스토리) 요구는 유지한다. 머지 방식(rebase merge, squash 금지)도 그대로다.

## 결과
- 별도의 GitHub-hosted/self-hosted CI 인프라나 과금이 필요 없다.
- 독립된 클린 환경에서의 검증이 없어진다 — 커밋을 병합하기 전 클린 워크트리에서 `tools/ci/verify.sh`를 실행해 완화한다.
- 강제는 인프라가 아니라 규칙 + PR 템플릿 체크리스트로 한다(`{{AGENTS_FILE}}` §3 규칙 3, `.github/pull_request_template.md`).
- M6 배포(GitHub Packages, SPM 릴리스)를 위한 릴리스 전용 워크플로는 필요하면 M6에서 별도로 추가할 수 있다(이 ADR의 범위 밖).
- 자체 호스팅 러너 제안(PR #37)을 대체한다 — 그 PR은 이 ADR로 대신하고 병합하지 않는다.

## 대안
- **자체 호스팅 러너(PR #37)**: Docker 러너 2대 + macOS 호스트 러너로 과금 문제는 풀리지만, 여전히 러너 프로세스를 이 Mac에서 띄우고 관리해야 하고 GitHub Actions 문법·트리거·시크릿 관리가 남는다. 로컬에서 바로 실행하는 것보다 얻는 게 적어 기각.
- **GitHub-hosted CI 유지**: 대기열과 macOS 분당 과금이 그대로 남는다. 기각.
