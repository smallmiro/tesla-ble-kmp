# CI self-hosted runner

결정 배경은 `docs/adr/0012-self-hosted-ci-runners.md`(ADR-0012)에 있습니다.

| runner | 라벨 | 실행 위치 | 대수 | 담당 job |
|---|---|---|---|---|
| Docker | `tesla-docker` | Colima VM 안의 `linux/amd64` 컨테이너(Rosetta) | 최대 2 | `lint`, `jvm-test`, `android`, `license`, `secrets` |
| macOS | `tesla-macos` | 호스트 Mac, 사용자 권한 | 1 | `ios` |

## 동작 방식
- `supervisor.sh`가 launchd(`io.github.smallmiro.tesla-runner`)로 상시 실행되며 10초마다 슬롯 2개를 확인합니다.
- 빈 슬롯이 있으면 호스트의 `gh` 로그인으로 1시간짜리 등록 토큰을 받아 `docker run --rm`으로 컨테이너를 띄웁니다.
- 컨테이너는 `--ephemeral`로 등록하고 job 1개를 실행한 뒤 종료되며 삭제됩니다. 다음 job은 새 컨테이너에서 돕니다.
- `gh` 토큰은 컨테이너에 들어가지 않습니다. 컨테이너가 받는 것은 등록 토큰뿐이고, entrypoint가 등록 직후 지웁니다.

## 사전 조건
- Apple Silicon Mac, `gh` 로그인(저장소 관리자), Docker CLI
- Colima: `colima start --vm-type vz --vz-rosetta --cpu 6 --memory 12`
  - Rosetta가 필요합니다. qemu 에뮬레이션에서는 runner가 `'EmitDefaultValue' property specified was not found` 오류로 GitHub에 연결하지 못합니다.
- macOS runner: Xcode 26, `xcodegen`(`brew install xcodegen`)

## 설치와 제거
| 작업 | 명령 |
|---|---|
| Docker runner 설치(이미지 빌드 + launchd 등록) | `tools/runner/install.sh` |
| Docker runner 제거 | `tools/runner/uninstall.sh` |
| macOS runner 설치 | `tools/runner/macos/install.sh` |
| macOS runner 제거 | `tools/runner/macos/uninstall.sh` |

`supervisor.sh`를 고친 뒤에는 `install.sh`를 다시 실행해야 반영됩니다. 스크립트를 `~/.tesla-runner/`에 복사해 쓰기 때문입니다.

## 상태 확인
| 확인할 것 | 방법 |
|---|---|
| GitHub에 등록된 runner | `gh api repos/smallmiro/tesla-ble-kmp/actions/runners --jq '.runners[] \| "\(.name) \(.status) \(.busy)"'` |
| supervisor 로그 | `tail -f ~/Library/Logs/tesla-runner.log` |
| 실행 중인 컨테이너 | `docker ps --filter name=tesla-runner` |
| macOS runner 로그 | `~/Library/Logs/actions.runner.smallmiro-tesla-ble-kmp.tesla-macos-*/` |

## 문제가 생겼을 때
- **job이 `Waiting for a runner`에서 멈춤**: Mac이 잠자기 상태이거나 Colima가 멈췄는지 확인합니다. 급하면 GitHub-hosted로 되돌립니다.
  - 되돌리기: `gh variable set CI_RUNNER --body hosted`
  - 복귀: `gh variable delete CI_RUNNER`
- **로그에 `failed to start slot`이 반복됨**: `gh auth status`로 로그인 상태를 확인합니다. 로그인이 풀렸다면 `gh auth login` 후 `launchctl kickstart -k gui/$(id -u)/io.github.smallmiro.tesla-runner`로 재시작합니다.
- **runner 버전이 오래돼 GitHub가 거부함**: `Dockerfile`과 `macos/install.sh`의 버전과 SHA-256을 올린 뒤 각 설치 스크립트를 다시 실행합니다.

## 보안 주의
- 저장소가 public이고 fork PR도 이 Mac에서 돕니다. 외부 기여자의 workflow 실행은 매번 승인이 필요하도록 설정돼 있습니다(`fork-pr-contributor-approval = all_external_contributors`).
- **승인하기 전에 PR diff 전체를 확인합니다.** 특히 `.github/`, `tools/`, `build-logic/`, `*.gradle.kts` 변경을 봅니다.
- macOS runner는 격리되지 않아 호스트 사용자 권한으로 실행됩니다.
- `pull_request` 이벤트는 **PR 쪽 `ci.yml`** 을 실행합니다. 외부 PR이 `runs-on`을 바꿔 self-hosted runner를 지정할 수 있으므로, `CI_RUNNER=hosted`로 바꿔도 보호되지 않습니다. 의심스러운 PR은 승인하지 않습니다.
