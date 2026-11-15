# Handoff — M6 stage 1 complete: iOS release through SPM `v1.0.0` (2026-11-15)

## 목표
- iOS 앱 개발자가 Xcode에서 `https://github.com/smallmiro/tesla-ble-kmp` 를 SPM으로 추가해 `Teslable` 1.0.0을 쓸 수 있게 한다(D13, ADR-0017).

## 완료
- 계획 `{{PLANS_DIR}}2026-11-14-m6-release.md`(Claude·Codex 결정 기록, 구현 중 판단 표 포함)를 subagent-driven development로 진행했다.
  - **#163 PR A**: 배포용 정적 XCFramework(iOS 16+, `ios-arm64` + `ios-arm64-simulator`), SKIE analytics 끔, 배포물의 빌드 머신 경로 제거(가짜 절대 루트 `/teslable`, 앱 Release dSYM에 Kotlin 파일·줄 정보 유지), 격리된 iOS 소비자 앱 검사, 두 단계 릴리스 스크립트(`{{RELEASE_TOOLS_DIR}}`, 오프라인 테스트 28개, `verify.sh` 9단계), ADR-0017, 사용 문서 11/12/17/18(MD + HTML ko/en/ja), 오프라인 사용자 가이드와 생성기, README 영어판과 한국어판.
  - **#164 PR B**: `prepare.sh 1.0.0` 이 만든 `Package.swift`. checksum `bcaed3dea92b5932ec7f4173b93df588bc043ad62e44114d3c55777dc64495c7`.
  - `publish.sh 1.0.0` → `RELEASED v1.0.0`: `verify.sh` 통과, 태그만 푸시, 초안 Release → 자산 체크섬 확인 → 정식 공개, 공개 URL zip 체크섬 일치, `check-consumer-ios.sh --remote 1.0.0` 테스트 4개 통과.
- 마무리 PR: `11-lib-overview` iOS 패키징 행을 릴리스됨으로 바꿈(MD + HTML 3언어, 사용자 가이드 다시 생성), PRD 로드맵 M6 1단계 완료 표시, 계획 Task 5 체크, 이 노트.

## 결정
- 버전 `1.0.0`은 사용자 결정. 실차 확인 대기 항목(#118)을 릴리스 노트와 문서에 적었다. 이후 공개 API 호환성이 깨지면 `2.0.0`.
- CocoaPods는 지원하지 않음(trunk 2026-12-02 읽기 전용). Android는 Maven Central(`dev.mirolab`)로 2단계에서.
- 구현 중 판단(ruling)은 PR #163 본문 "판단해서 정한 것"에 10개 항목으로 남겼다.

## 막힌 점
- 없음. 검증하지 못한 것: App Store가 Xcode가 넣는 빈 stub framework를 받아들이는지(계정이 필요함, 문서에 "검증 안 됨"으로 적음).

## 다음 한 걸음
1. M6 2단계 Android Maven Central: 별도 계획. 합의된 내용은 계획 문서 끝 "2단계" 절에 있다(vanniktech 플러그인, 좌표 `dev.mirolab.teslable:teslable-<module>`). 사용자 준비: Central 가입, `mirolab.dev` DNS TXT, User Token, GPG 키.
2. 실차 확인(#118), Android 실기기 확인. 그 뒤 README의 Android "Coming soon" 문구를 바꾼다.
3. 후속(선택): `samples/ios` 테스트 타깃의 XCTest 17.0 링커 경고, 다음 iOS 릴리스는 `{{RELEASE_TOOLS_DIR}}README.md` 절차대로(같은 Mac에서 prepare → PR → publish).

## 읽을 파일
- `{{PLANS_DIR}}2026-11-14-m6-release.md`, `{{ADR_DIR}}0017-ios-spm-release.md`
- `{{RELEASE_TOOLS_DIR}}README.md`
- `{{LIB_DOCS_DIR}}11-lib-overview.md` §Installation, `17-lib-platform-notes.md` §iOS
