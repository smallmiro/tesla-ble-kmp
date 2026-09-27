# ADR-0012: CI를 self-hosted runner(Docker 2대 + macOS 네이티브 1대)로 실행

| 항목 | 내용 |
|---|---|
| 상태 | **제안** (2026-09-27) |
| 관련 | `{{WORKFLOW_FILE}}` §6(CI), `.github/workflows/ci.yml`, `{{RUNNER_DIR}}` |

## 맥락
CI는 지금 GitHub-hosted runner(`ubuntu-latest` 5개 job, `macos-15` 1개 job)에서 돈다. 개발자 Mac(Apple Silicon, 8코어/16GB, Colima, Xcode 26.6)에서 CI job을 직접 실행하도록 바꾼다. 요구사항은 다음과 같다.
- runner는 GitHub를 폴링하며 대기하다가 job이 오면 실행한다.
- Docker 기반 runner는 최대 2대까지 동시에 실행한다.
- `ios` job은 Linux 컨테이너에서 Xcode를 쓸 수 없으므로 macOS에 직접 설치한 runner에서 돈다.

제약은 다음과 같다.
- 저장소가 **public**이다. self-hosted runner는 fork PR의 코드를 이 Mac에서 실행할 수 있다.
- Android 빌드 도구(aapt2, build-tools)의 Linux 배포판은 x86_64 전용이다. Colima VM은 aarch64다.
- Colima VM의 기본 리소스(2 CPU/4GB)로는 KMP Gradle 빌드 2개를 동시에 돌릴 수 없다.
- 등록 토큰은 1시간 뒤 만료되므로, ephemeral runner는 job마다 새 토큰이 필요하다.
- 저장소 관리자 권한과 `repo` scope가 있는 토큰으로 호스트의 `gh`가 이미 로그인돼 있다. 이 토큰은 권한이 넓으므로 job이 실행되는 컨테이너 안에 들어가면 안 된다.

## 결정
1. **Linux job 5개**(`lint`, `jvm-test`, `android`, `license`, `secrets`)는 Docker runner에서 돈다.
   - 이미지: `ghcr.io/actions/actions-runner`(버전 고정)에 `unzip`, `jq`, `curl` 등을 더한 이미지, `linux/amd64`
   - Colima: `--vm-type vz --vz-rosetta --cpu 6 --memory 12`. amd64 컨테이너는 Rosetta로 실행한다.
   - **호스트 supervisor**(`{{RUNNER_DIR}}supervisor.sh`, launchd 서비스)가 슬롯 2개를 유지한다. 10초마다 슬롯을 확인하고, 비어 있으면 `gh api`로 등록 토큰을 받아 `docker run`으로 새 컨테이너를 띄운다. 종료된 컨테이너는 종료 코드를 확인한 뒤 지우고, 0이 아니면 백오프한다.
   - 컨테이너는 받은 등록 토큰으로 `--ephemeral` 등록 후 job 1개를 실행하고 종료된다. 다음 job은 항상 새 컨테이너에서 돈다.
   - 컨테이너마다 CPU 3개, 메모리 5.5GB로 제한한다.
   - 라벨: `self-hosted, linux, x64, tesla-docker`
2. **`ios` job**은 이 Mac에 직접 설치한 runner 1대에서 돈다(Docker 아님).
   - 라벨: `self-hosted, macOS, ARM64, tesla-macos`
   - launchd 서비스로 상시 대기하는 persistent 등록이다. job이 끝나면 workflow 단계에서 작업 디렉터리를 정리한다.
   - `sudo xcode-select` 대신 `DEVELOPER_DIR`로 Xcode 26을 고른다. self-hosted에서는 sudo 암호를 입력할 수 없기 때문이다.
   - `xcodegen`은 호스트에 미리 설치된 것을 쓴다. CI가 호스트에 `brew install`을 하지 않는다.
3. **fork PR도 self-hosted에서 돈다.** 대신 저장소 설정 `fork-pr-contributor-approval`을 `all_external_contributors`로 둬서, 외부 기여자의 workflow 실행은 매번 관리자 승인을 받는다(사용자 결정, 2026-09-27).
4. **되돌리기 스위치**: 저장소 변수 `CI_RUNNER`가 `hosted`이면 모든 job이 예전처럼 GitHub-hosted(`ubuntu-latest`/`macos-15`)에서 돈다. 변수가 없거나 다른 값이면 self-hosted에서 돈다. Mac이 꺼져 job이 대기열에 머물 때 이 변수로 되돌린다.
5. **인증**: 별도 PAT를 만들지 않고 호스트의 `gh` 로그인으로 등록 토큰을 발급한다(2026-09-27에 발급 동작 확인). `gh` 토큰은 호스트 supervisor만 쓰며 컨테이너에는 **1시간짜리 등록 토큰만** 전달한다. 컨테이너 entrypoint는 등록 후 이 변수를 지우고 `run.sh`를 실행한다.

## 결과
- GitHub-hosted 대기열과 무관하게 이 Mac에서 CI가 돈다. Gradle 캐시가 컨테이너 수명 동안 남는다.
- **위험**: 승인을 실수로 누르면 외부 코드가 이 Mac의 Docker와 **호스트의 macOS runner**에서 실행된다. 특히 macOS runner는 격리되지 않았으므로 사용자 계정 권한으로 실행된다.
- fork PR은 PR 쪽 `ci.yml`로 실행되므로 `CI_RUNNER` 변수로는 fork PR을 막을 수 없다. 승인 전 diff 검토가 유일한 방어선이다.
- Mac이 꺼져 있거나 Colima가 멈추면 CI가 돌지 않는다(최대 24시간 대기 후 실패). 되돌리기 스위치로 대응한다.
- Rosetta로 실행하므로 Linux job이 arm64 네이티브보다 느리다.
- Colima VM이 호스트 자원을 6코어/12GB까지 쓴다. 재시작하면 기존 컨테이너가 멈춘다.
- 동시 실행 상한은 Docker 2개 + macOS 1개로 3개다.

## 대안
- **GitHub-hosted 유지**: public 저장소라 무료이고 격리된다. 사용자가 self-hosted를 선택해 기각.
- **arm64 컨테이너**: Rosetta가 필요 없어 빠르지만 `android` job(aapt2)이 실행되지 않는다. 기각.
- **fine-grained PAT + `docker compose`(`restart: always`)**: PAT가 job이 실행되는 컨테이너 안에 있어야 하고, 재시작한 컨테이너는 파일시스템이 남아 깨끗하지 않다. 기각.
- **actions-runner-controller**: 자동 확장이 되지만 Kubernetes가 필요하다. 2대 고정에는 과하다. 기각.
- **fork PR은 GitHub-hosted로 분기**: 더 안전하지만 사용자가 승인 방식을 선택해 기각.
