# CI self-hosted runner 구성 계획

> **For agentic workers:** `superpowers:executing-plans`로 실행한다. 작업마다 커밋한다. 결정 근거는 `{{ADR_DIR}}0012-self-hosted-ci-runners.md`.

**Goal:** `.github/workflows/ci.yml`의 Linux job 5개를 Docker runner 2대(Colima, amd64/Rosetta, ephemeral)에서, `ios` job을 이 Mac에 직접 설치한 runner 1대에서 실행한다.

**브랜치:** `ci/self-hosted-runners` (워크트리 `.claude/worktrees/ci-self-hosted`). M2 작업과 별개로 진행한다.

**참고 매뉴얼:** 해당 없음. 포팅 작업이 아니다.

**검증 방식:** 인프라 작업이라 단위 테스트 대신 다음으로 검증한다.
- 스크립트: `shellcheck`, `docker build`
- 동작: 실제 PR에서 CI가 self-hosted runner로 Green이 되는지 확인한다.

## 사용자가 해야 하는 일 (에이전트가 할 수 없음)
- 호스트 `gh` 로그인(관리자 권한, `repo` scope)을 그대로 쓰므로 PAT 발급은 필요 없다.
- **U1.** Colima 재시작에 동의한다. 지금 실행 중인 `altitude-probe-pg` 컨테이너가 멈춘다.

## 작업

### Task 1: 경로 키와 무시 규칙 (`docs:`)
- `{{PATHS_FILE}}`에 `RUNNER_DIR = tools/runner/`를 추가한다.
- `.gitignore`의 `.env` 규칙이 `tools/runner/.env`를 덮는지 확인한다(현재 `.env`가 있으므로 덮음).
- ADR-0012와 이 계획을 같은 커밋에 넣는다.

### Task 2: Docker runner 이미지 (`chore:`)
파일:
- `tools/runner/Dockerfile`: `FROM ghcr.io/actions/actions-runner:<고정 버전>`, `unzip jq curl xz-utils` 설치
- `tools/runner/entrypoint.sh`
  1. 환경변수 `RUNNER_TOKEN`(등록 토큰), `RUNNER_NAME`, `GITHUB_REPO`를 받는다.
  2. `config.sh --unattended --ephemeral --replace --labels tesla-docker --name "$RUNNER_NAME"`으로 등록한다.
  3. `unset RUNNER_TOKEN` 후 `run.sh`를 exec한다.

검증: `shellcheck tools/runner/*.sh`, `docker build --platform linux/amd64 tools/runner`

### Task 3: 호스트 supervisor (`chore:`)
- `tools/runner/supervisor.sh`: 슬롯 `tesla-runner-1`, `tesla-runner-2`를 10초마다 확인한다. 실행 중이 아닌 슬롯은 다음처럼 띄운다.
  - `gh api -X POST repos/$REPO/actions/runners/registration-token --jq .token`으로 토큰을 받는다.
  - `docker run --rm -d --name tesla-runner-N --platform linux/amd64 --cpus 3 --memory 5.5g -e RUNNER_TOKEN ...`로 띄운다.
  - 토큰 발급이나 Docker가 실패하면 로그를 남기고 백오프한 뒤 다시 시도한다.
  - SIGTERM을 받으면 컨테이너를 멈추고 GitHub에서 남은 `tesla-docker` runner를 삭제한다.
- `tools/runner/launchd/io.github.smallmiro.tesla-runner.plist`와 `install.sh`/`uninstall.sh`: supervisor를 로그인 시 자동 시작하는 사용자 LaunchAgent로 등록한다.

### Task 3-1: Colima 재구성과 기동 (로컬 작업, 커밋 없음, U1 이후)
1. `colima stop`
2. `colima start --vm-type vz --vz-rosetta --cpu 6 --memory 12`
3. `docker build --platform linux/amd64 -t tesla-runner tools/runner`
4. `tools/runner/launchd/install.sh`

확인: `gh api repos/smallmiro/tesla-ble-kmp/actions/runners`에 `tesla-docker` runner 2대가 online으로 나온다. job 하나가 끝나면 해당 컨테이너가 사라지고 새 컨테이너가 등록되는지 본다.

### Task 4: macOS 네이티브 runner (`chore:`)
- `tools/runner/macos/install.sh`
  - `actions/runner` osx-arm64 릴리스(고정 버전, SHA-256 검증)를 `~/actions-runner-tesla`에 설치한다. 저장소 밖이다.
  - `gh api`로 받은 등록 토큰으로 `--labels tesla-macos` 등록한다.
  - `./svc.sh install && ./svc.sh start`로 launchd 서비스를 만든다.
- `tools/runner/macos/uninstall.sh`: 서비스를 제거하고 등록을 해제한다.

확인: runner 목록에 `tesla-macos`가 online으로 나온다.

### Task 5: workflow 전환 (`chore:`)
`.github/workflows/ci.yml`:
- Linux job 5개의 `runs-on`을 다음으로 바꾼다.
  `${{ vars.CI_RUNNER == 'hosted' && 'ubuntu-latest' || fromJSON('["self-hosted","linux","tesla-docker"]') }}`
- `ios`의 `runs-on`을 다음으로 바꾼다.
  `${{ vars.CI_RUNNER == 'hosted' && 'macos-15' || fromJSON('["self-hosted","macOS","tesla-macos"]') }}`
- Xcode 선택 단계: `/Applications/Xcode_26*.app`과 `/Applications/Xcode.app` 중 `xcodebuild -version`이 26.x인 것을 찾아 `DEVELOPER_DIR`을 `$GITHUB_ENV`에 쓴다. `sudo`를 쓰지 않는다.
- `brew install xcodegen` 대신 `command -v xcodegen`으로 확인하고, 없으면 실패한다. hosted일 때만 설치한다.
- `ios` job 끝에 `if: always()`로 샘플 빌드 산출물(`samples/ios/build`, 생성된 `.xcodeproj`)을 정리하는 단계를 넣는다.

확인: 이 브랜치로 PR을 열고 6개 job이 self-hosted runner에서 Green이 되는지 본다. job 로그의 "Runner name"으로 어느 runner에서 돌았는지 확인한다.

### Task 6: fork PR 승인 정책과 문서 (`docs:` + 저장소 설정)
- 저장소 설정 변경(**실행 전 사용자 확인**):
  `gh api -X PUT repos/smallmiro/tesla-ble-kmp/actions/permissions/fork-pr-contributor-approval -f approval_policy=all_external_contributors`
- `tools/runner/README.md`에 다음을 적는다: 기동·중지, 로그 확인, `gh` 로그인이 풀렸을 때 조치, `CI_RUNNER=hosted` 되돌리기, 보안 주의(승인 전에 PR diff 확인).
- `{{WORKFLOW_FILE}}` §6(CI)에 runner 구성을 반영한다.

### Task 7: 리뷰와 병합
- `superpowers:requesting-code-review`로 리뷰받는다. 특히 `gh` 토큰이 컨테이너나 로그로 새지 않는지, supervisor의 실패·종료 처리를 본다.
- PR 본문에 리뷰 요약을 붙이고 rebase merge한다.
